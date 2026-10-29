package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;

import androidx.annotation.RequiresApi;

/** A frozen chat backdrop for the modal message preview. The message and menu stay live. */
@RequiresApi(Build.VERSION_CODES.S)
public final class PopupPreviewBackdrop extends View {
    private static final float SCALE = .25f;
    private final RenderNode snapshot = new RenderNode("MessagePreviewBackdrop");
    private boolean released;
    private float targetAlpha;

    public PopupPreviewBackdrop(Context context, View source, View foreground, float radius) {
        super(context);
        int width = Math.max(1, (int) Math.ceil(foreground.getWidth() * SCALE));
        int height = Math.max(1, (int) Math.ceil(foreground.getHeight() * SCALE));
        int[] sourcePosition = new int[2], foregroundPosition = new int[2];
        source.getLocationOnScreen(sourcePosition);
        foreground.getLocationOnScreen(foregroundPosition);
        snapshot.setPosition(0, 0, width, height);
        snapshot.setRenderEffect(RenderEffect.createBlurEffect(radius * SCALE, radius * SCALE, Shader.TileMode.CLAMP));
        Canvas recording = snapshot.beginRecording(width, height);
        int foregroundVisibility = foreground.getVisibility();
        boolean foregroundFocused = foreground.hasFocus();
        // The preview may share the activity's decor. Exclude it entirely from
        // the capture so its RenderNode cannot end up referencing itself.
        foreground.setVisibility(INVISIBLE);
        try {
            recording.scale(SCALE, SCALE);
            recording.translate(sourcePosition[0] - foregroundPosition[0], sourcePosition[1] - foregroundPosition[1]);
            source.draw(recording);
            // The chat's dim animation has just started when this snapshot is
            // taken. Include its final 20% shade in the cached backdrop.
            recording.drawColor(0x33000000);
        } finally {
            snapshot.endRecording();
            foreground.setVisibility(foregroundVisibility);
            if (foregroundFocused) foreground.requestFocus();
        }
        // Alpha animation reuses this recording; it never blurs the activity's live surface.
        setAlpha(0f);
    }

    public void fadeTo(float alpha, long duration) {
        if (released || targetAlpha == alpha) return;
        targetAlpha = alpha;
        animate().cancel();
        if (duration <= 0) {
            setAlpha(alpha);
        } else {
            animate().alpha(alpha).setDuration(duration).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).start();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (released || !canvas.isHardwareAccelerated()) return;
        int saved = canvas.save();
        canvas.scale(1f / SCALE, 1f / SCALE);
        canvas.drawRenderNode(snapshot);
        canvas.restoreToCount(saved);
    }

    public void release() {
        if (released) return;
        released = true;
        animate().cancel();
        setAlpha(0f);
        snapshot.discardDisplayList();
        snapshot.setRenderEffect(null);
    }

    @Override
    protected void onDetachedFromWindow() {
        release();
        super.onDetachedFromWindow();
    }
}
