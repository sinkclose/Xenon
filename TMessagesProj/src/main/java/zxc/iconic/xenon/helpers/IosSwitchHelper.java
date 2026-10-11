package zxc.iconic.xenon.helpers;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.View;
import org.telegram.ui.Components.Switch;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.ui.ActionBar.Theme;

/** Canvas adaptation of AndroidLiquidGlass LiquidToggle, using Xenon's glass renderer. */
public final class IosSwitchHelper {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF();
    private final RectF thumb = new RectF();
    private IosGlassThumb glass;
    private final Switch owner;
    private final IosSwitchDynamics motion;
    private boolean framePosted;
    private long lastFrameNanos;
    private final Runnable frame = this::advanceFrame;

    public IosSwitchHelper(Switch owner) {
        this.owner = owner;
        motion = new IosSwitchDynamics(owner.getProgress());
    }

    private void requestFrame() {
        if (!owner.isAttachedToWindow()) {
            motion.snapTo(owner.isChecked() ? 1f : 0f);
            owner.setProgress(motion.fraction());
            return;
        }
        owner.invalidate();
        if (!framePosted) {
            framePosted = true;
            lastFrameNanos = System.nanoTime();
            owner.postOnAnimation(frame);
        }
    }

    private void advanceFrame() {
        long now = System.nanoTime();
        motion.advance(Math.min(.064f, (now - lastFrameNanos) / 1_000_000_000f));
        lastFrameNanos = now;
        owner.setProgress(motion.fraction());
        owner.invalidate();
        if (motion.isRunning()) {
            owner.postOnAnimation(frame);
        } else {
            framePosted = false;
        }
    }

    public void setChecked(boolean checked, boolean animated, boolean gesture) {
        if (!animated || !owner.isAttachedToWindow()) {
            owner.removeCallbacks(frame);
            framePosted = false;
            motion.snapTo(checked ? 1f : 0f);
            owner.setProgress(motion.fraction());
        } else {
            motion.animateTo(checked ? 1f : 0f, !gesture);
            requestFrame();
        }
    }

    public void press() { motion.press(); requestFrame(); }
    public void dragTo(float fraction) { motion.animateTo(fraction, false); requestFrame(); }
    public void releasePress() { motion.release(); requestFrame(); }


    public void release() {
        owner.removeCallbacks(frame);
        framePosted = false;
        motion.snapTo(owner.isChecked() ? 1f : 0f);
        owner.setProgress(motion.fraction());
        if (glass != null) glass.release();
        glass = null;
    }

    public void draw(Canvas canvas, View owner, float progress, boolean pressed, boolean rtl,
                     Drawable icon, float iconVisibility, Theme.ResourcesProvider resourcesProvider) {
        float fraction = motion.fraction();
        // The legacy 37x20 dp slot must not shrink this 64x28 dp control.
        float scale = AndroidUtilities.density;
        float left = (owner.getMeasuredWidth() - 64f * scale) / 2f;
        float top = (owner.getMeasuredHeight() - 28f * scale) / 2f;
        boolean dark = Theme.isCurrentThemeDark();
        int off = dark ? 0x5C787880 : 0x33787878;
        int on = dark ? 0xFF30D158 : 0xFF34C759;
        int color = ColorUtils.blendARGB(off, on, fraction);
        track.set(left, top, left + 64f * scale, top + 28f * scale);
        paint.setColor(color);
        canvas.drawRoundRect(track, 14f * scale, 14f * scale, paint);
        float position = rtl ? 1f - fraction : fraction;
        float thumbLeft = left + (2f + 20f * position) * scale;
        thumb.set(thumbLeft, top + 2f * scale, thumbLeft + 40f * scale, top + 26f * scale);
        float pressProgress = motion.pressProgress();
        float thumbWidth = thumb.width() * motion.thumbScaleX();
        float thumbHeight = thumb.height() * motion.thumbScaleY();
        float thumbCx = thumb.centerX(), thumbCy = thumb.centerY();
        thumb.set(thumbCx - thumbWidth / 2f, thumbCy - thumbHeight / 2f,
                thumbCx + thumbWidth / 2f, thumbCy + thumbHeight / 2f);
        paint.setColor(0x16000000);
        canvas.drawRoundRect(thumb.left, thumb.top + scale, thumb.right, thumb.bottom + scale,
                thumb.height() / 2f, thumb.height() / 2f, paint);
        boolean glassEnabled = LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS);
        if (!glassEnabled && glass != null) {
            glass.release();
            glass = null;
        }
        if (glassEnabled && pressProgress > .001f && Build.VERSION.SDK_INT >= 33 && canvas.isHardwareAccelerated()) {
            if (glass == null) glass = new IosGlassThumb(owner, resourcesProvider);
            glass.draw(canvas, thumb, pressProgress, IosSwitchDynamics.backdropScaleX(pressProgress),
                    IosSwitchDynamics.backdropScaleY(pressProgress), resourcesProvider, recording -> {
                        paint.setColor(color);
                        recording.drawRoundRect(track, track.height() / 2f, track.height() / 2f, paint);
                    });
            paint.setColor(Color.WHITE);
            paint.setAlpha(Math.round(255f * (1f - pressProgress)));
            canvas.drawRoundRect(thumb, thumb.height() / 2f, thumb.height() / 2f, paint);
            paint.setAlpha(255);
        } else {
            paint.setColor(Color.WHITE);
            canvas.drawRoundRect(thumb, thumb.height() / 2f, thumb.height() / 2f, paint);
        }
        // Preserve semantic icons (locks, etc.) supplied by callers.
        if (icon != null && iconVisibility > 0f) {
            canvas.save();
            canvas.scale(iconVisibility, iconVisibility, thumb.centerX(), thumb.centerY());
            int half = Math.round(8f * scale);
            int cx = Math.round(thumb.centerX()), cy = Math.round(thumb.centerY());
            icon.setTint(ColorUtils.blendARGB(0xFF787880, on, fraction));
            icon.setBounds(cx - half, cy - half, cx + half, cy + half);
            icon.draw(canvas);
            canvas.restore();
        }
    }

}
