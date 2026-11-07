package org.telegram.ui.Components;

import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.OperationCanceledException;
import android.provider.MediaStore;

import androidx.recyclerview.widget.DiffUtil;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MediaPickerGalleryLoader;
import org.telegram.messenger.MediaPickerGalleryDiff;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.PhotoViewer;

import java.util.ArrayList;

/**
 * V2 owns its gallery snapshots and query lifecycle. Editing, selection, camera and sending
 * use the shared attachment UI so that both implementations have the same capabilities.
 */
public class ChatAttachAlertPhotoLayoutV2 extends ChatAttachAlertPhotoLayout {
    private final ArrayList<MediaController.AlbumEntry> emptyAlbums = new ArrayList<>();
    private final Context applicationContext;
    private MediaPickerGalleryLoader.Gallery gallery;
    private MediaPickerGalleryLoader.Gallery pendingGallery;
    private CancellationSignal query;
    private boolean inFlight;
    private boolean dirty = true;
    private boolean destroyed;
    private boolean observingGallery;
    private int permissions = -1;
    private CancellationSignal diffQuery;
    private Runnable diffTask;
    private MediaController.AlbumEntry diffPreviousAlbum;
    private MediaController.AlbumEntry diffNextAlbum;
    private DiffUtil.DiffResult galleryDiff;
    private boolean diffReady;
    private boolean refreshScheduled;

    private final Runnable refresh = () -> {
        refreshScheduled = false;
        loadGalleryPhotos();
    };
    private final Runnable applyGallery = new Runnable() {
        @Override
        public void run() {
            if (destroyed || pendingGallery == null) return;
            // PhotoViewer holds the current album array. Keep its indices and edits stable.
            if (PhotoViewer.getInstance().isVisible()) {
                AndroidUtilities.runOnUIThread(this, 250);
                return;
            }
            MediaController.AlbumEntry previous = getSelectedGalleryAlbum();
            MediaController.AlbumEntry next = resolveGalleryAlbum(shouldLoadAllMedia() ? pendingGallery.allMedia : pendingGallery.allPhotos,
                    shouldLoadAllMedia() ? pendingGallery.mediaAlbums : pendingGallery.photoAlbums);
            if (MediaPickerGalleryDiff.sameAlbum(previous, next) && !isGalleryPrefix(previous, next)
                    && (!diffReady || diffPreviousAlbum != previous || diffNextAlbum != next)) {
                if (diffQuery != null && diffPreviousAlbum == previous && diffNextAlbum == next) return;
                cancelDiff();
                diffPreviousAlbum = previous;
                diffNextAlbum = next;
                CancellationSignal cancellation = diffQuery = new CancellationSignal();
                Utilities.globalQueue.postRunnable(diffTask = () -> {
                    try {
                        DiffUtil.DiffResult difference = MediaPickerGalleryDiff.calculate(previous, next, cancellation);
                        AndroidUtilities.runOnUIThread(() -> {
                            if (destroyed || diffQuery != cancellation || cancellation.isCanceled()) return;
                            galleryDiff = difference;
                            diffReady = true;
                            // Recheck the album and viewer: either can change while diffing.
                            applyGallery.run();
                        });
                    } catch (OperationCanceledException ignored) {
                    }
                });
                return;
            }
            gallery = pendingGallery;
            pendingGallery = null;
            onGalleryLoaded();
            cancelDiff();
        }
    };
    private final ContentObserver galleryObserver = new ContentObserver(ApplicationLoader.applicationHandler) {
        @Override
        public void onChange(boolean selfChange, Uri uri) {
            invalidateGallery();
        }

        @Override
        public void onChange(boolean selfChange) {
            invalidateGallery();
        }
    };

    public ChatAttachAlertPhotoLayoutV2(ChatAttachAlert alert, Context context, boolean forceDarkTheme, boolean needCamera, Theme.ResourcesProvider resourcesProvider) {
        super(alert, context, forceDarkTheme, needCamera, resourcesProvider, true);
        applicationContext = context.getApplicationContext();
        // Global albums belong to V1 and must not replace this picker's partial snapshot.
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.albumsDidLoad);
        gridView.setItemViewCacheSize(12);
        gridView.getRecycledViewPool().setMaxRecycledViews(0, 18);
        try {
            applicationContext.getContentResolver().registerContentObserver(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, true, galleryObserver);
            applicationContext.getContentResolver().registerContentObserver(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, galleryObserver);
            observingGallery = true;
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void invalidateGallery() {
        if (destroyed) return;
        dirty = true;
        // Finish the current pass. A burst of notifications needs at most one follow-up.
        if (!inFlight) scheduleRefresh();
    }

    private void scheduleRefresh() {
        // Do not postpone an already scheduled refresh during continuous downloads.
        if (refreshScheduled || !parentAlert.isShowing() || parentAlert.isDismissed()) return;
        refreshScheduled = true;
        AndroidUtilities.runOnUIThread(refresh, 250);
    }

    @Override
    public void loadGalleryPhotos() {
        if (destroyed) return;
        AndroidUtilities.cancelRunOnUIThread(refresh);
        refreshScheduled = false;
        int granted = MediaPickerGalleryLoader.getAccessSignature(applicationContext);
        if (permissions != granted) {
            permissions = granted;
            cancelQuery();
            gallery = null;
            dirty = true;
            onGalleryLoaded();
        }
        if (inFlight || dirty) onGalleryLoading();
        if (inFlight || !dirty) return;
        boolean needsPreview = gallery == null;
        CancellationSignal cancellation = query = new CancellationSignal();
        inFlight = true;
        dirty = false;
        Thread worker = new Thread(() -> MediaPickerGalleryLoader.load(applicationContext, cancellation, (result, complete) -> {
            AndroidUtilities.runOnUIThread(() -> {
                if (destroyed || query != cancellation || cancellation.isCanceled()) return;
                if (complete) {
                    inFlight = false;
                    // Keep failed queries retryable on the next opening.
                    dirty |= result == null;
                }
                if (result != null && (needsPreview || complete)) {
                    cancelDiff();
                    pendingGallery = result;
                    AndroidUtilities.cancelRunOnUIThread(applyGallery);
                    applyGallery.run();
                } else if (complete && gallery == null) {
                    onGalleryLoaded();
                }
                if (complete && result != null && dirty) scheduleRefresh();
            });
        }), "media-picker-v2");
        worker.setPriority(Thread.MIN_PRIORITY);
        worker.start();
    }

    private void cancelQuery() {
        if (query != null) {
            query.cancel();
            query = null;
        }
        inFlight = false;
        pendingGallery = null;
        cancelDiff();
        AndroidUtilities.cancelRunOnUIThread(applyGallery);
    }

    private void cancelDiff() {
        if (diffQuery != null) diffQuery.cancel();
        if (diffTask != null) Utilities.globalQueue.cancelRunnable(diffTask);
        diffQuery = null;
        diffTask = null;
        galleryDiff = null;
        diffReady = false;
        diffPreviousAlbum = diffNextAlbum = null;
    }

    @Override
    protected DiffUtil.DiffResult getGalleryDiff(MediaController.AlbumEntry previous, MediaController.AlbumEntry next) {
        return diffReady && diffPreviousAlbum == previous && diffNextAlbum == next ? galleryDiff : null;
    }

    @Override
    protected boolean isNoGalleryPermissions() {
        Context context = getContext();
        return !MediaPickerGalleryLoader.canRead(context, false)
                && (!shouldLoadAllMedia() || !MediaPickerGalleryLoader.canRead(context, true));
    }

    @Override
    protected MediaController.AlbumEntry getGalleryAlbumEntry(boolean allMedia) {
        return gallery == null ? null : allMedia ? gallery.allMedia : gallery.allPhotos;
    }

    @Override
    protected ArrayList<MediaController.AlbumEntry> getGalleryAlbums(boolean allMedia) {
        return gallery == null ? emptyAlbums : allMedia ? gallery.mediaAlbums : gallery.photoAlbums;
    }

    @Override
    public void onInit(boolean hasVideo, boolean hasPhoto, boolean hasDocuments) {
        super.onInit(hasVideo, hasPhoto, hasDocuments);
        loadGalleryPhotos();
    }

    @Override
    public void onResume() {
        super.onResume();
        loadGalleryPhotos();
    }

    @Override
    public void onPause() {
        super.onPause();
        // ContentObserver covers full access. Selected-media access can change while paused
        // without a collection notification, so only that mode needs an unconditional refresh.
        if (!observingGallery || (permissions & 4) != 0 && (permissions & 3) != 3) {
            dirty = true;
        }
    }

    @Override
    public boolean onDismiss() {
        boolean consumed = super.onDismiss();
        if (!consumed) stopLoading();
        return consumed;
    }

    @Override
    public void onDismissWithButtonClick(int item) {
        super.onDismissWithButtonClick(item);
        stopLoading();
    }

    private void stopLoading() {
        if (inFlight || pendingGallery != null) dirty = true;
        cancelQuery();
        AndroidUtilities.cancelRunOnUIThread(refresh);
        refreshScheduled = false;
    }

    @Override
    public void onDestroy() {
        if (destroyed) return;
        destroyed = true;
        stopLoading();
        applicationContext.getContentResolver().unregisterContentObserver(galleryObserver);
        gallery = null;
        super.onDestroy();
    }
}
