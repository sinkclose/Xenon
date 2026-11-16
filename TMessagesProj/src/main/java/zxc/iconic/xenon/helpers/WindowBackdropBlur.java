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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Consumer;

/** A blur owned by a foreground window, with no screen capture or refresh loop. */
@RequiresApi(Build.VERSION_CODES.S)
public final class WindowBackdropBlur implements View.OnAttachStateChangeListener {
    private static final class BackgroundBlur {
        final Map<WindowBackdropBlur, Boolean> owners = new IdentityHashMap<>();
        float maximum;
    }

    private static final WeakHashMap<View, BackgroundBlur> FALLBACKS = new WeakHashMap<>();
    // Cache filter descriptions, never image buffers. Float keys preserve the
    // exact requested radius, including fractional gesture/animation values.
    private static final Map<Float, RenderEffect> EFFECTS = new LinkedHashMap<Float, RenderEffect>(64, .75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Float, RenderEffect> eldest) {
            return size() > 64;
        }
    };
    private final View foreground;
    private final View background;
    private final Window window;
    private final boolean preferViewBlur;
    private final WindowManager manager;
    private final Consumer<Boolean> blurEnabledListener = enabled -> update();
    private final android.view.ViewTreeObserver.OnPreDrawListener attributesListener = () -> {
        update();
        return true;
    };
    private android.view.ViewTreeObserver observer;
    private View windowRoot;
    private ValueAnimator animator;
    private float radius;
    private boolean released;
    private boolean listening;
    private boolean nativeApplied;
    private int originalRadius;
    private boolean originalFlag;
    private boolean viewBlurOnly;
    private float fallbackRadius;
    private final Runnable syncAfterAttach = this::syncAfterAttach;

    private void syncAfterAttach() {
        if (released || !foreground.isAttachedToWindow()) return;
        update();
        if (nativeApplied) applyAttributes(attributes());
    }

    public WindowBackdropBlur(Window window, View background) {
        this(window, background, false);
    }

    public WindowBackdropBlur(Window window, View background, boolean preferViewBlur) {
        this(window.getDecorView(), background, window, preferViewBlur);
    }

    public WindowBackdropBlur(View foreground, View background) {
        this(foreground, background, null, false);
    }

    private WindowBackdropBlur(View foreground, View background, Window window, boolean preferViewBlur) {
        this.foreground = foreground;
        this.background = background;
        this.window = window;
        this.preferViewBlur = preferViewBlur;
        manager = foreground.getContext().getSystemService(WindowManager.class);
        foreground.addOnAttachStateChangeListener(this);
        if (foreground.isAttachedToWindow()) onViewAttachedToWindow(foreground);
    }

    public float getRadius() {
        return radius;
    }

    public boolean canUseNativeBlur() {
        if (released || !foreground.isAttachedToWindow() || viewBlurOnly || manager == null
                || !manager.isCrossWindowBlurEnabled()) return false;
        WindowManager.LayoutParams params = currentAttributes();
        return params != null && (background == null
                || background.getWindowToken() != foreground.getWindowToken());
    }

    public void setRadius(float radius) {
        if (released) return;
        float next = Math.max(0f, Math.min(150f, radius));
        if (this.radius == next) return;
        this.radius = next;
        update();
    }

    public void animateTo(float target, long duration, boolean releaseAfter) {
        cancelAnimation();
        if (released) return;
        target = Math.max(0f, Math.min(150f, target));
        if (duration <= 0 || radius == target) {
            setRadius(target);
            if (releaseAfter) release();
            return;
        }
        ValueAnimator animation = ValueAnimator.ofFloat(radius, target);
        animator = animation;
        animation.setDuration(duration);
        animation.setInterpolator(target < radius
                ? CubicBezierInterpolator.DEFAULT : CubicBezierInterpolator.EASE_OUT_QUINT);
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

    private WindowManager.LayoutParams currentAttributes() {
        return window != null ? window.getAttributes()
                : windowRoot != null && windowRoot.getLayoutParams() instanceof WindowManager.LayoutParams
                ? (WindowManager.LayoutParams) windowRoot.getLayoutParams() : null;
    }

    private WindowManager.LayoutParams attributes() {
        WindowManager.LayoutParams current = currentAttributes();
        if (current == null) return null;
        // Do not mutate the live attributes before WindowManager receives them.
        WindowManager.LayoutParams copy = new WindowManager.LayoutParams();
        copy.copyFrom(current);
        return copy;
    }

    private void applyAttributes(WindowManager.LayoutParams params) {
        // During detach the parameters still need restoring, but the decor
        // has already left WindowManager and must not be submitted again.
        if (window != null) {
            if (foreground.isAttachedToWindow()) window.setAttributes(params);
            else window.getAttributes().copyFrom(params);
        } else if (windowRoot != null && windowRoot.isAttachedToWindow()) {
            manager.updateViewLayout(windowRoot, params);
        } else if (windowRoot != null && windowRoot.getLayoutParams() instanceof WindowManager.LayoutParams) {
            ((WindowManager.LayoutParams) windowRoot.getLayoutParams()).copyFrom(params);
        }
        // Updating layout attributes alone can leave a clean hardware-rendered
        // window without a new buffer. Commit the blur with a foreground frame,
        // rather than waiting for a system overlay to trigger one.
        if (windowRoot != null && windowRoot.isAttachedToWindow()) {
            windowRoot.invalidate();
        }
    }

    private void update() {
        if (released || !foreground.isAttachedToWindow()) return;
        if (viewBlurOnly) {
            setFallback(radius);
            return;
        }
        boolean nativeBlur = canUseNativeBlur();
        if (nativeBlur && radius > 0f) {
            WindowManager.LayoutParams params = attributes();
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
        if (background == null || fallbackRadius == value) return;
        BackgroundBlur state = FALLBACKS.get(background);
        if (state == null) {
            if (value <= 0f) return;
            state = new BackgroundBlur();
            FALLBACKS.put(background, state);
        }
        float previousRadius = fallbackRadius;
        fallbackRadius = value;
        if (value > 0f) {
            if (previousRadius == 0f) state.owners.put(this, Boolean.TRUE);
        } else {
            state.owners.remove(this);
        }
        float maximum = state.maximum;
        if (state.owners.isEmpty()) {
            maximum = 0f;
        } else if (value >= maximum || (value > 0f && state.owners.size() == 1)) {
            maximum = value;
        } else if (previousRadius == maximum) {
            maximum = 0f;
            for (WindowBackdropBlur owner : state.owners.keySet()) maximum = Math.max(maximum, owner.fallbackRadius);
        }
        // Only the strongest owner changes the GPU filter. HWUI automatically
        // caches a RenderEffect node in a layer; setLayerType would also dirty
        // the View's display list unnecessarily when entering/leaving blur.
        if (maximum != state.maximum) {
            state.maximum = maximum;
            RenderEffect effect = null;
            if (maximum > 0f) {
                effect = EFFECTS.get(maximum);
                if (effect == null) {
                    effect = RenderEffect.createBlurEffect(maximum, maximum, Shader.TileMode.CLAMP);
                    EFFECTS.put(maximum, effect);
                }
            }
            background.setRenderEffect(effect);
        }
        if (state.owners.isEmpty()) FALLBACKS.remove(background);
    }

    public void release() {
        if (released) return;
        cancelAnimation();
        released = true;
        foreground.removeCallbacks(syncAfterAttach);
        if (listening) manager.removeCrossWindowBlurEnabledListener(blurEnabledListener);
        listening = false;
        clearNative();
        setFallback(0f);
        if (observer != null && observer.isAlive()) observer.removeOnPreDrawListener(attributesListener);
        observer = null;
        foreground.removeOnAttachStateChangeListener(this);
        windowRoot = null;
    }

    @Override
    public void onViewAttachedToWindow(View v) {
        if (released) return;
        windowRoot = foreground.getRootView();
        viewBlurOnly = preferViewBlur || (background != null
                && background.getWindowToken() == foreground.getWindowToken());
        if (!viewBlurOnly) {
            observer = foreground.getViewTreeObserver();
            observer.addOnPreDrawListener(attributesListener);
        }
        if (!viewBlurOnly && manager != null && !listening) {
            listening = true;
            manager.addCrossWindowBlurEnabledListener(foreground.getContext().getMainExecutor(), blurEnabledListener);
        }
        update();
        // Attachment happens inside the first traversal. Submit once more after
        // it finishes, when the window's surface and initial attributes exist.
        if (!viewBlurOnly) foreground.post(syncAfterAttach);
    }

    @Override
    public void onViewDetachedFromWindow(View v) {
        release();
    }
}
