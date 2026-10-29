package zxc.iconic.xenon.helpers;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.DispatchQueue;
import org.telegram.messenger.FileLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.R;
import org.telegram.messenger.VideoEditedInfo;
import org.telegram.messenger.video.MediaCodecVideoConvertor;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;

import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.atomic.AtomicBoolean;

public final class PhotoGifConverter {
    private static final DispatchQueue queue = new DispatchQueue("photoGifConversion");
    private PhotoGifConverter() {}

    public interface Callback {
        void onConverted(MediaController.PhotoEntry entry);
    }

    public static boolean canConvert(Object object) {
        if (!(object instanceof MediaController.PhotoEntry)) return false;
        MediaController.PhotoEntry entry = (MediaController.PhotoEntry) object;
        return !entry.isVideo && entry.ttl == 0 && entry.starsAmount == 0;
    }

    public static void convert(Context context, Theme.ResourcesProvider provider,
                               MediaController.PhotoEntry source, int account, Callback callback) {
        AtomicBoolean canceled = new AtomicBoolean();
        AlertDialog dialog = new AlertDialog(context, AlertDialog.ALERT_TYPE_LOADING, provider);
        dialog.setTitle(LocaleController.getString(R.string.SendAsGif));
        dialog.setMessage(LocaleController.getString(R.string.ConvertingPhotoToGif));
        dialog.setProgress(0);
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnCancelListener(d -> canceled.set(true));
        dialog.setNegativeButton(LocaleController.getString(R.string.Cancel), (d, which) -> canceled.set(true));
        dialog.show();
        queue.postRunnable(() -> {
            File image = null;
            File output = null;
            Bitmap bitmap = null;
            try {
                if (canceled.get()) return;
                String path = source.imagePath != null ? source.imagePath : source.path;
                bitmap = ImageLoader.loadBitmap(path, null, 1280, 1280, true);
                if (bitmap == null) throw new IllegalStateException("Cannot decode photo");
                int width = Math.max(16, Math.round(bitmap.getWidth() / 16f) * 16);
                int height = Math.max(16, Math.round(bitmap.getHeight() / 16f) * 16);
                // The photo renderer fits inside the codec-aligned frame, leaving black
                // margins when its aspect ratio differs. Fill that frame before encoding.
                Bitmap frame = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                float scale = Math.max(width / (float) bitmap.getWidth(), height / (float) bitmap.getHeight());
                float scaledWidth = bitmap.getWidth() * scale;
                float scaledHeight = bitmap.getHeight() * scale;
                float left = (width - scaledWidth) / 2f;
                float top = (height - scaledHeight) / 2f;
                new Canvas(frame).drawBitmap(bitmap, null,
                    new RectF(left, top, left + scaledWidth, top + scaledHeight),
                    new Paint(Paint.FILTER_BITMAP_FLAG));
                bitmap.recycle();
                bitmap = frame;
                image = File.createTempFile("photo_gif_", ".png", FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE));
                try (FileOutputStream stream = new FileOutputStream(image)) {
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                        throw new IllegalStateException("Cannot save photo");
                    }
                }
                bitmap.recycle();
                bitmap = null;
                output = File.createTempFile("photo_gif_", ".mp4", FileLoader.getDirectory(FileLoader.MEDIA_DIR_CACHE));
                VideoEditedInfo renderInfo = new VideoEditedInfo();
                renderInfo.isPhoto = true;
                renderInfo.muted = true;
                renderInfo.account = account;
                MediaController.VideoConvertorListener listener = new MediaController.VideoConvertorListener() {
                    @Override
                    public boolean checkConversionCanceled() { return canceled.get(); }
                    @Override
                    public void didWriteData(long size, float progress) {
                        int percent = Math.max(0, Math.min(99, (int) (progress * 100)));
                        AndroidUtilities.runOnUIThread(() -> {
                            if (!canceled.get()) dialog.setProgress(percent);
                        });
                    }
                };
                MediaCodecVideoConvertor.ConvertVideoParams params =
                    MediaCodecVideoConvertor.ConvertVideoParams.of(image.getAbsolutePath(), output, 0,
                        0, false, width, height, width, height, 30, 900000, 0,
                        0, 1000000, -1, true, 1000, listener, renderInfo);
                params.reportProgressOnEveryFrame = true;
                boolean error = new MediaCodecVideoConvertor().convertVideo(params);
                if (error || canceled.get() || output.length() == 0) {
                    throw new IllegalStateException("Photo GIF conversion failed or canceled");
                }
                MediaController.PhotoEntry result = source.clone();
                result.path = output.getAbsolutePath();
                result.imagePath = null;
                result.thumbPath = null;
                result.isVideo = true;
                result.isLivePhoto = false;
                result.discardLivePhoto = true;
                result.duration = 1;
                result.orientation = result.invert = 0;
                result.width = width;
                result.height = height;
                result.size = output.length();
                result.canDeleteAfter = true;
                VideoEditedInfo info = new VideoEditedInfo();
                // Already encoded. Muted makes SendMessagesHelper add the animated attribute.
                info.bitrate = -2;
                info.muted = true;
                info.startTime = 0;
                info.endTime = -1;
                info.originalDuration = info.estimatedDuration = 1000;
                info.originalWidth = info.resultWidth = width;
                info.originalHeight = info.resultHeight = height;
                info.originalPath = result.path;
                info.estimatedSize = result.size;
                result.editedInfo = info;
                File completedFile = output;
                AndroidUtilities.runOnUIThread(() -> {
                    dialog.setProgress(100);
                    dialog.dismiss();
                    if (canceled.get()) completedFile.delete();
                    else callback.onConverted(result);
                });
                output = null;
            } catch (Throwable e) {
                if (!canceled.get()) FileLog.e(e);
                AndroidUtilities.runOnUIThread(() -> {
                    dialog.dismiss();
                    if (!canceled.get()) {
                        new AlertDialog.Builder(context, provider)
                            .setTitle(LocaleController.getString(R.string.SendAsGif))
                            .setMessage(LocaleController.getString(R.string.PhotoToGifFailed))
                            .setPositiveButton(LocaleController.getString(R.string.OK), null).show();
                    }
                });
            } finally {
                if (bitmap != null) bitmap.recycle();
                if (image != null) image.delete();
                if (output != null) output.delete();
            }
        });
    }
}
