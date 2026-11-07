package org.telegram.messenger;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.Environment;
import android.os.OperationCanceledException;
import android.provider.MediaStore;
import android.text.TextUtils;
import android.util.SparseArray;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;

/** Independent V2 gallery. Never publishes partial albums to MediaController's global cache. */
public final class MediaPickerGalleryLoader {
    private static final int FIRST_SCREEN_SIZE = 24;
    private static final int PREVIEW_SIZE = 96;

    public interface Callback {
        // Called on the worker thread. A null gallery means the query failed.
        void onLoaded(Gallery gallery, boolean complete);
    }

    public static final class Gallery {
        public final ArrayList<MediaController.AlbumEntry> mediaAlbums = new ArrayList<>();
        public final ArrayList<MediaController.AlbumEntry> photoAlbums = new ArrayList<>();
        public MediaController.AlbumEntry allMedia;
        public MediaController.AlbumEntry allPhotos;
    }

    private MediaPickerGalleryLoader() {}

    public static boolean canRead(Context context, boolean video) {
        return (getAccessSignature(context) & ((video ? 2 : 1) | 4)) != 0;
    }

    public static int getAccessSignature(Context context) {
        if (Build.VERSION.SDK_INT < 23) return 3;
        if (Build.VERSION.SDK_INT < 33) {
            return context.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED ? 3 : 0;
        }
        return (context.checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED ? 1 : 0)
                | (context.checkSelfPermission(Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED ? 2 : 0)
                | (Build.VERSION.SDK_INT >= 34 && context.checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) == PackageManager.PERMISSION_GRANTED ? 4 : 0);
    }

    public static void load(Context context, CancellationSignal cancellation, Callback callback) {
        try (MediaCursor images = open(context, false, cancellation);
             MediaCursor videos = open(context, true, cancellation)) {
            Builder builder = new Builder();
            // Merge already sorted cursors once, instead of sorting every album afterwards.
            while (images.entry != null || videos.entry != null) {
                cancellation.throwIfCanceled();
                MediaCursor next = videos.entry == null || images.entry != null && images.entry.dateTaken >= videos.entry.dateTaken ? images : videos;
                builder.add(next.entry, next.bucketName, next.video);
                if (builder.media.photos.size() == FIRST_SCREEN_SIZE || builder.media.photos.size() == PREVIEW_SIZE) {
                    callback.onLoaded(builder.snapshot(true, cancellation), false);
                }
                next.advance(cancellation);
            }
            cancellation.throwIfCanceled();
            callback.onLoaded(builder.snapshot(false, cancellation), true);
        } catch (OperationCanceledException ignored) {
            // The picker was destroyed or a newer query replaced this one.
        } catch (Exception e) {
            FileLog.e(e);
            if (!cancellation.isCanceled()) callback.onLoaded(null, true);
        }
    }

    private static MediaCursor open(Context context, boolean video, CancellationSignal cancellation) {
        if (!canRead(context, video)) return new MediaCursor(null, video, cancellation);
        String date = Build.VERSION.SDK_INT > 28 ? MediaStore.MediaColumns.DATE_MODIFIED : MediaStore.Images.Media.DATE_TAKEN;
        String[] projection = {
                MediaStore.MediaColumns._ID, MediaStore.Images.Media.BUCKET_ID,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME, MediaStore.MediaColumns.DATA, date,
                video ? MediaStore.Video.Media.DURATION : MediaStore.Images.Media.ORIENTATION,
                MediaStore.MediaColumns.WIDTH, MediaStore.MediaColumns.HEIGHT, MediaStore.MediaColumns.SIZE
        };
        Uri uri = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Cursor cursor = context.getContentResolver().query(uri, projection, null, null,
                date + " DESC, " + MediaStore.MediaColumns._ID + " DESC", cancellation);
        try {
            return new MediaCursor(cursor, video, cancellation);
        } catch (RuntimeException e) {
            if (cursor != null) cursor.close();
            throw e;
        }
    }

    private static final class MediaCursor implements AutoCloseable {
        private final Cursor cursor;
        private final boolean video;
        private MediaController.PhotoEntry entry;
        private String bucketName;

        private MediaCursor(Cursor cursor, boolean video, CancellationSignal cancellation) {
            this.cursor = cursor;
            this.video = video;
            advance(cancellation);
        }

        private void advance(CancellationSignal cancellation) {
            entry = null;
            while (cursor != null && cursor.moveToNext()) {
                cancellation.throwIfCanceled();
                String path = cursor.getString(3);
                if (TextUtils.isEmpty(path)) continue;
                bucketName = cursor.getString(2);
                entry = new MediaController.PhotoEntry(cursor.getInt(1), cursor.getInt(0), cursor.getLong(4), path,
                        video ? 0 : cursor.getInt(5), video ? (int) (cursor.getLong(5) / 1000) : 0,
                        video, cursor.getInt(6), cursor.getInt(7), cursor.getLong(8));
                return;
            }
        }

        @Override
        public void close() {
            if (cursor != null) cursor.close();
        }
    }

    private static final class Builder {
        private final MediaController.AlbumEntry media = new MediaController.AlbumEntry(0, LocaleController.getString(R.string.AllMedia), null);
        private final MediaController.AlbumEntry photos = new MediaController.AlbumEntry(0, LocaleController.getString(R.string.AllPhotos), null);
        private final MediaController.AlbumEntry videos = new MediaController.AlbumEntry(0, LocaleController.getString(R.string.AllVideos), null);
        private final SparseArray<MediaController.AlbumEntry> mediaBuckets = new SparseArray<>();
        private final SparseArray<MediaController.AlbumEntry> photoBuckets = new SparseArray<>();
        private final ArrayList<MediaController.AlbumEntry> mediaOrder = new ArrayList<>();
        private final ArrayList<MediaController.AlbumEntry> photoOrder = new ArrayList<>();
        // Live Photo detection can turn PhotoEntry.isVideo on after publishing a preview.
        // Album membership follows the MediaStore source, independent of later edits.
        private final HashSet<Integer> imageIds = new HashSet<>();
        private final String cameraFolder = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM).getAbsolutePath() + "/Camera/";
        private boolean mediaCameraFound;
        private boolean photoCameraFound;

        private Builder() {
            videos.videoOnly = true;
        }

        private void add(MediaController.PhotoEntry entry, String bucketName, boolean video) {
            addToAlbum(media, entry);
            addToAlbum(video ? videos : photos, entry);
            boolean camera = entry.path.startsWith(cameraFolder);
            addToBucket(mediaBuckets, mediaOrder, entry, bucketName, camera && !mediaCameraFound);
            if (camera) mediaCameraFound = true;
            if (!video) {
                imageIds.add(entry.imageId);
                addToBucket(photoBuckets, photoOrder, entry, bucketName, camera && !photoCameraFound);
                if (camera) photoCameraFound = true;
            }
        }

        private static void addToAlbum(MediaController.AlbumEntry album, MediaController.PhotoEntry entry) {
            if (album.coverPhoto == null) album.coverPhoto = entry;
            album.photos.add(entry);
        }

        private static void addToBucket(SparseArray<MediaController.AlbumEntry> buckets, ArrayList<MediaController.AlbumEntry> order,
                                        MediaController.PhotoEntry entry, String name, boolean camera) {
            MediaController.AlbumEntry album = buckets.get(entry.bucketId);
            if (album == null) {
                album = new MediaController.AlbumEntry(entry.bucketId, name, entry);
                buckets.put(entry.bucketId, album);
                order.add(camera ? 0 : order.size(), album);
            }
            addToAlbum(album, entry);
        }

        private static MediaController.AlbumEntry snapshotAlbum(MediaController.AlbumEntry album, boolean copy) {
            if (!copy) return album;
            MediaController.AlbumEntry result = new MediaController.AlbumEntry(album.bucketId, album.bucketName, album.coverPhoto);
            result.videoOnly = album.videoOnly;
            result.photos.addAll(album.photos);
            return result;
        }

        private Gallery snapshot(boolean copy, CancellationSignal cancellation) {
            Gallery result = new Gallery();
            MediaController.AlbumEntry allMedia = snapshotAlbum(media, copy);
            MediaController.AlbumEntry allPhotos = snapshotAlbum(photos, copy);
            MediaController.AlbumEntry allVideos = snapshotAlbum(videos, copy);
            if (!allMedia.photos.isEmpty()) {
                result.allMedia = allMedia;
                result.mediaAlbums.add(allMedia);
            }
            if (!allPhotos.photos.isEmpty()) {
                result.allPhotos = allPhotos;
                result.photoAlbums.add(allPhotos);
                result.mediaAlbums.add(allPhotos);
            }
            if (!allVideos.photos.isEmpty()) result.mediaAlbums.add(allVideos);
            SparseArray<MediaController.AlbumEntry> mediaIndex = new SparseArray<>();
            SparseArray<MediaController.AlbumEntry> photoIndex = new SparseArray<>();
            for (MediaController.AlbumEntry album : mediaOrder) {
                MediaController.AlbumEntry snapshot = snapshotAlbum(album, copy);
                result.mediaAlbums.add(snapshot);
                mediaIndex.put(snapshot.bucketId, snapshot);
            }
            for (MediaController.AlbumEntry album : photoOrder) {
                MediaController.AlbumEntry snapshot = snapshotAlbum(album, copy);
                result.photoAlbums.add(snapshot);
                photoIndex.put(snapshot.bucketId, snapshot);
            }
            // Descending ids make SparseArray.put shift O(N²) entries. Sort the index once,
            // then append in ascending id order; album display order stays chronological.
            ArrayList<MediaController.PhotoEntry> byId = new ArrayList<>(media.photos);
            byId.sort(Comparator.comparingInt(entry -> entry.imageId));
            for (MediaController.PhotoEntry entry : byId) {
                cancellation.throwIfCanceled();
                allMedia.photosByIds.append(entry.imageId, entry);
                mediaIndex.get(entry.bucketId).photosByIds.append(entry.imageId, entry);
                if (!imageIds.contains(entry.imageId)) {
                    allVideos.photosByIds.append(entry.imageId, entry);
                } else {
                    allPhotos.photosByIds.append(entry.imageId, entry);
                    photoIndex.get(entry.bucketId).photosByIds.append(entry.imageId, entry);
                }
            }
            return result;
        }
    }
}
