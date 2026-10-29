package zxc.iconic.xenon.helpers;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;

import androidx.annotation.RequiresApi;

import org.telegram.ui.Components.CubicBezierInterpolator;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** A blur owned by a foreground window, with no screen capture or refresh loop. */
@RequiresApi(Build.VERSION_CODES.S)
public final class WindowBackdropBlur implements View.OnAttachStateChangeListener {
    private static final WeakHashMap<View, Map<WindowBackdropBlur, Float>> FALLBACKS = new WeakHashMap<>();
    private final View foreground;
    private final View background;
    private final Window window;
    private final WindowManager manager;
    private final Consumer<Boolean> blurEnabledListener = enabled -> update();
    private View windowRoot;
    private ValueAnimator animator;
    private float radius;
    private boolean released;
    private boolean listening;
    private boolean nativeApplied;
    private int originalRadius;
    private boolean originalFlag;

    public WindowBackdropBlur(Window window, View background) {
        this(window.getDecorView(), background, window);
    }

    public WindowBackdropBlur(View foreground, View background) {
        this(foreground, background, null);
    }

    private WindowBackdropBlur(View foreground, View background, Window window) {
        this.foreground = foreground;
        this.background = background;
        this.window = window;
        manager = foreground.getContext().getSystemService(WindowManager.class);
        foreground.addOnAttachStateChangeListener(this);
        if (foreground.isAttachedToWindow()) onViewAttachedToWindow(foreground);
    }

    public float getRadius() {
        return radius;
    }

    public void setRadius(float radius) {
        if (released) return;
        this.radius = Math.max(0f, Math.min(150f, radius));
        update();
    }

    public void animateTo(float target, long duration, boolean releaseAfter) {
        cancelAnimation();
        if (released) return;
        if (duration <= 0 || radius == target) {
            setRadius(target);
            if (releaseAfter) release();
            return;
        }
        ValueAnimator animation = ValueAnimator.ofFloat(radius, target);
        animator = animation;
        animation.setDuration(duration);
        animation.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        animation.addUpdateListener(a -> setRadius((float) a.getAnimatedValue()));
        animation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (animator != animation) return;
                animator = null;
                if (releaseAfter) release();
            }
        });
        animation.start();
    }

    public void cancelAnimation() {
        if (animator == null) return;
        ValueAnimator old = animator;
        animator = null;
        old.removeAllUpdateListeners();
        old.removeAllListeners();
        old.cancel();
    }

    private WindowManager.LayoutParams attributes() {
        if (window != null) return window.getAttributes();
        return windowRoot != null && windowRoot.getLayoutParams() instanceof WindowManager.LayoutParams
                ? (WindowManager.LayoutParams) windowRoot.getLayoutParams() : null;
    }

    private void applyAttributes(WindowManager.LayoutParams params) {
        // During detach the parameters still need restoring, but the decor
        // has already left WindowManager and must not be submitted again.
        if (window != null) {
            if (foreground.isAttachedToWindow()) window.setAttributes(params);
        } else if (windowRoot != null && windowRoot.isAttachedToWindow()) {
            manager.updateViewLayout(windowRoot, params);
        }
    }

    private void update() {
        if (released || !foreground.isAttachedToWindow()) return;
        WindowManager.LayoutParams params = attributes();
        boolean nativeBlur = manager != null && manager.isCrossWindowBlurEnabled() && params != null;
        if (nativeBlur && radius > 0f) {
            if (!nativeApplied) {
                originalRadius = params.getBlurBehindRadius();
                originalFlag = (params.flags & WindowManager.LayoutParams.FLAG_BLUR_BEHIND) != 0;
                nativeApplied = true;
            }
            int pixels = Math.round(radius);
            if (params.getBlurBehindRadius() != pixels || (params.flags & WindowManager.LayoutParams.FLAG_BLUR_BEHIND) == 0) {
                params.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
                params.setBlurBehindRadius(pixels);
                applyAttributes(params);
            }
        } else {
            clearNative();
        }
        setFallback(nativeBlur ? 0f : radius);
    }

    private void clearNative() {
        if (!nativeApplied) return;
        WindowManager.LayoutParams params = attributes();
        nativeApplied = false;
        if (params == null) return;
        params.setBlurBehindRadius(originalRadius);
        if (!originalFlag) params.flags &= ~WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
        applyAttributes(params);
    }

    private void setFallback(float value) {
        if (background == null) return;
        Map<WindowBackdropBlur, Float> owners = FALLBACKS.get(background);
        if (owners == null) {
            if (value <= 0f) return;
            owners = new IdentityHashMap<>();
            FALLBACKS.put(background, owners);
        }
        float previousMaximum = 0f;
        for (float requested : owners.values()) previousMaximum = Math.max(previousMaximum, requested);
        if (value > 0f) owners.put(this, value);
        else if (owners.remove(this) == null) return;
        float maximum = 0f;
        for (float requested : owners.values()) maximum = Math.max(maximum, requested);
        // A weaker overlapping animation must not rebuild the stronger layer.
        if (maximum != previousMaximum) {
            background.setRenderEffect(maximum > 0f
                    ? RenderEffect.createBlurEffect(maximum, maximum, Shader.TileMode.CLAMP) : null);
        }
        if (owners.isEmpty()) FALLBACKS.remove(background);
    }

    public void release() {
        if (released) return;
        cancelAnimation();
        released = true;
        if (listening) manager.removeCrossWindowBlurEnabledListener(blurEnabledListener);
        listening = false;
        clearNative();
        setFallback(0f);
        foreground.removeOnAttachStateChangeListener(this);
        windowRoot = null;
    }

    @Override
    public void onViewAttachedToWindow(View v) {
        if (released) return;
        windowRoot = foreground.getRootView();
        if (manager != null && !listening) {
            listening = true;
            manager.addCrossWindowBlurEnabledListener(foreground.getContext().getMainExecutor(), blurEnabledListener);
        }
        update();
    }

    @Override
    public void onViewDetachedFromWindow(View v) {
        release();
    }
}
