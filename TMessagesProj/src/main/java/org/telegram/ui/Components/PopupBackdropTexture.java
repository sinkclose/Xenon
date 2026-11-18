package org.telegram.ui.Components;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.HardwareRenderer;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RenderNode;
import android.hardware.HardwareBuffer;
import android.media.Image;
import android.media.ImageReader;

import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.Utilities;

/** Rasterize the frozen preview blur once; glass then samples a texture, not another blur graph. */
@RequiresApi(31)
final class PopupBackdropTexture {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private Bitmap bitmap;
    private boolean released;
    private int generation;

    void capture(RenderNode snapshot, int width, int height, Runnable invalidate) {
        Utilities.globalQueue.postRunnable(() -> {
            Bitmap result = render(snapshot, width, height);
            AndroidUtilities.runOnUIThread(() -> {
                if (released || result == null) return;
                bitmap = result;
                generation++;
                invalidate.run();
            });
        });
    }

    private static Bitmap render(RenderNode snapshot, int width, int height) {
        HardwareRenderer renderer = null;
        try (ImageReader reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 1,
                HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE | HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)) {
            renderer = new HardwareRenderer();
            renderer.setSurface(reader.getSurface());
            renderer.setContentRoot(snapshot);
            renderer.setOpaque(false);
            renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw();
            try (Image image = reader.acquireNextImage()) {
                if (image == null) return null;
                try (HardwareBuffer buffer = image.getHardwareBuffer()) {
                    return buffer == null ? null : Bitmap.wrapHardwareBuffer(buffer, null);
                }
            }
        } catch (RuntimeException e) {
            // Keep the existing RenderNode path on devices without offscreen GPU rendering.
            FileLog.e(e);
            return null;
        } finally {
            if (renderer != null) renderer.destroy();
        }
    }

    boolean draw(Canvas canvas) {
        if (released || bitmap == null) return false;
        canvas.drawBitmap(bitmap, 0, 0, paint);
        return true;
    }

    int generation() { return generation; }

    void release() {
        released = true;
        // RenderNode recordings may still reference the texture until their next frame.
        bitmap = null;
    }
}
