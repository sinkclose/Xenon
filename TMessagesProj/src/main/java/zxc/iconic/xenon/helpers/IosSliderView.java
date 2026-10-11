package zxc.iconic.xenon.helpers;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.LiteMode;
import org.telegram.ui.ActionBar.Theme;

/** AndroidLiquidGlass LiquidSlider's capsule, damped drag and composed track backdrop. */
public final class IosSliderView extends View {
    public interface Listener {
        void onValue(float value, boolean stop);
        void onPressed(boolean pressed);
    }
    private final Theme.ResourcesProvider resourcesProvider;
    private final Listener listener;
    private final IosSwitchDynamics motion = new IosSwitchDynamics(0f);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF track = new RectF(), fill = new RectF(), thumb = new RectF();
    private IosGlassThumb glass;
    private float value, minimum, buffered, downX, downY, downValue;
    private boolean dragging, touchedThumb, framePosted;
    private int pointer = -1;
    private long lastFrame;
    private final Runnable frame = this::advanceFrame;

    public IosSliderView(Context context, Theme.ResourcesProvider resourcesProvider, Listener listener) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        this.listener = listener;
        setClickable(true);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }
    public static float fraction(float x, float left, float width, boolean rtl) {
        float p = IosSwitchDynamics.clamp((x - left) / Math.max(1f, width), 0f, 1f);
        return rtl ? 1f - p : p;
    }
    public static float thumbCenter(float left, float width, float progress, boolean rtl, float density) {
        float p = rtl ? 1f - progress : progress;
        return left + IosSwitchDynamics.clamp(width * p, 10f * density, Math.max(10f * density, width - 10f * density));
    }
    public void setValue(float value, boolean animated) {
        this.value = IosSwitchDynamics.clamp(value, minimum, 1f);
        if ((animated && isAttachedToWindow()) || pointer != -1) {
            motion.animateTo(this.value, false);
            requestFrame();
        } else {
            motion.snapTo(this.value);
            invalidate();
        }
    }
    public void setMinimum(float minimum) { this.minimum = Math.max(0f, minimum); }
    public void setBuffered(float buffered) { this.buffered = buffered; invalidate(); }
    public void release() {
        if (pointer != -1) finish(false);
        removeCallbacks(frame);
        framePosted = false;
        motion.snapTo(value);
        if (glass != null) glass.release();
        glass = null;
    }
    @Override protected void onDetachedFromWindow() { release(); super.onDetachedFromWindow(); }
    private void requestFrame() {
        invalidate();
        if (!framePosted && isAttachedToWindow()) {
            framePosted = true;
            lastFrame = System.nanoTime();
            postOnAnimation(frame);
        }
    }
    private void advanceFrame() {
        long now = System.nanoTime();
        motion.advance(Math.min(.064f, (now - lastFrame) / 1_000_000_000f));
        lastFrame = now;
        invalidate();
        if (motion.isRunning()) postOnAnimation(frame); else framePosted = false;
    }
    private void change(float next, boolean stop) {
        value = IosSwitchDynamics.clamp(next, minimum, 1f);
        motion.animateTo(value, false);
        listener.onValue(value, stop);
        requestFrame();
    }
    private void finish(boolean commit) {
        pointer = -1;
        dragging = false;
        if (commit) listener.onValue(value, true);
        listener.onPressed(false);
        motion.release();
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        requestFrame();
    }
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) { if (pointer != -1) finish(false); return false; }
        float inset = AndroidUtilities.dp(16), width = getWidth() - 2 * inset;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                pointer = event.getPointerId(0);
                downX = event.getX(); downY = event.getY(); downValue = value;
                dragging = false;
                float center = thumbCenter(inset, width, motion.fraction(), LocaleController.isRTL, AndroidUtilities.density);
                touchedThumb = Math.abs(downX - center) <= AndroidUtilities.dp(24);
                motion.press(); listener.onPressed(true); requestFrame();
                return true;
            case MotionEvent.ACTION_MOVE:
                int index = event.findPointerIndex(pointer);
                if (index < 0) return false;
                float dx = event.getX(index) - downX, dy = event.getY(index) - downY;
                if (!dragging && Math.abs(dy) > ViewConfiguration.get(getContext()).getScaledTouchSlop() && Math.abs(dy) > Math.abs(dx)) {
                    finish(false); return false;
                }
                if (!dragging && Math.abs(dx) > AndroidUtilities.dp(2)) {
                    dragging = true;
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                }
                if (dragging) change(touchedThumb
                        ? downValue + (LocaleController.isRTL ? -dx : dx) / Math.max(1f, width)
                        : fraction(event.getX(index), inset, width, LocaleController.isRTL), false);
                return true;
            case MotionEvent.ACTION_UP:
                if (pointer == -1) return false;
                if (!dragging && !touchedThumb) change(fraction(event.getX(), inset, width, LocaleController.isRTL), false);
                performClick();
                finish(true); return true;
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_POINTER_DOWN:
                if (pointer != -1) finish(false);
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerId(event.getActionIndex()) == pointer) finish(false);
                return true;
        }
        return false;
    }
    @Override public boolean performClick() { super.performClick(); return true; }
    @Override protected void onDraw(Canvas canvas) {
        float density = AndroidUtilities.density, inset = 16f * density;
        float cy = getHeight() / 2f, width = Math.max(1f, getWidth() - 2f * inset);
        float p = motion.fraction(), press = motion.pressProgress();
        boolean rtl = LocaleController.isRTL, dark = Theme.isCurrentThemeDark();
        int accent = Theme.getColor(Theme.key_player_progress, resourcesProvider);
        int off = dark ? 0x5C787880 : 0x33787878;
        track.set(inset, cy - 3f * density, inset + width, cy + 3f * density);
        fill.set(rtl ? track.right - width * p : track.left, track.top,
                rtl ? track.right : track.left + width * p, track.bottom);
        paint.setAlpha(255); paint.setColor(off); canvas.drawRoundRect(track, 3f * density, 3f * density, paint);
        if (buffered > 0) {
            paint.setColor(Theme.getColor(Theme.key_player_progressCachedBackground, resourcesProvider));
            canvas.drawRoundRect(rtl ? track.right - width * buffered : track.left, track.top,
                    rtl ? track.right : track.left + width * buffered, track.bottom, 3f * density, 3f * density, paint);
        }
        paint.setColor(accent); canvas.drawRoundRect(fill, 3f * density, 3f * density, paint);
        float cx = thumbCenter(inset, width, p, rtl, density);
        float halfW = 20f * density * motion.thumbScaleX(10f), halfH = 12f * density * motion.thumbScaleY(10f);
        thumb.set(cx - halfW, cy - halfH, cx + halfW, cy + halfH);
        paint.setColor(0x0D000000);
        canvas.drawRoundRect(thumb.left, thumb.top + density, thumb.right, thumb.bottom + density, halfH, halfH, paint);
        boolean glassEnabled = LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS);
        if (!glassEnabled && glass != null) {
            glass.release();
            glass = null;
        }
        boolean drawGlass = glassEnabled && press > .001f && Build.VERSION.SDK_INT >= 33 && canvas.isHardwareAccelerated();
        if (drawGlass) {
            if (glass == null) glass = new IosGlassThumb(this, resourcesProvider);
            glass.draw(canvas, thumb, press, 2f / 3f + press / 3f, press, resourcesProvider, recording -> {
                paint.setColor(off); recording.drawRoundRect(track, 3f * density, 3f * density, paint);
                paint.setColor(accent); recording.drawRoundRect(fill, 3f * density, 3f * density, paint);
            });
            paint.setColor(Color.WHITE); paint.setAlpha(Math.round(255f * (1f - press)));
        } else paint.setColor(Color.WHITE);
        canvas.drawRoundRect(thumb, halfH, halfH, paint);
        paint.setAlpha(255);
        if (drawGlass && glass != null) {
            glass.drawInnerShadow(canvas, thumb, press);
        }
    }
}
