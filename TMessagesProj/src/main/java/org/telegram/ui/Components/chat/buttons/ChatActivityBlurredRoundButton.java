package org.telegram.ui.Components.chat.buttons;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;

import android.content.Context;
import android.graphics.BlendMode;
import android.graphics.BlendModeColorFilter;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.ImageView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CircularProgressDrawable;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LiquidTouchEffect;
import org.telegram.ui.Components.LiquidTouchDispatcher;
import org.telegram.ui.Components.LiquidPressAnimationSuppressor;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;

import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;

public class ChatActivityBlurredRoundButton extends FrameLayout implements FactorAnimator.Target, LiquidTouchDispatcher.Target {
    public static final int CLICK_ZONE_MARGIN = 6;
    public static final int BUTTON_SIZE = 44;

    public ChatActivityBlurredRoundButton(Context context) {
        super(context);
    }

    private LiquidTouchEffect liquidTouch;
    private final LiquidPressAnimationSuppressor liquidPressAnimations = new LiquidPressAnimationSuppressor();

    public void setLiquidTouchEnabled(boolean enabled) {
        if (enabled == (liquidTouch != null)) return;
        if (liquidTouch != null) liquidTouch.reset();
        liquidTouch = enabled ? new LiquidTouchEffect(this) : null;
        if (!enabled) LiquidTouchEffect.updateBackground(backgroundDrawable, null, 0f, 0f);
        liquidPressAnimations.sync(this, liquidTouchAllowed());
        invalidate();
    }

    private boolean liquidTouchAllowed() {
        return liquidTouch != null && isEnabled()
                && !zxc.iconic.xenon.helpers.NonIslandHelper.chatElements();
    }

    @Override
    public void observeLiquidTouch(MotionEvent event) {
        liquidPressAnimations.sync(this, liquidTouchAllowed());
        if (liquidTouch != null) {
            liquidTouch.setBounds(0, 0, getWidth(), getHeight());
            liquidTouch.onTouchEvent(event, liquidTouchAllowed());
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (!LiquidTouchDispatcher.isDispatchingControls()) observeLiquidTouch(event);
        boolean handled = super.dispatchTouchEvent(event);
        if (!LiquidTouchDispatcher.isDispatchingControls() && !handled && event.getActionMasked() == MotionEvent.ACTION_DOWN && liquidTouch != null) liquidTouch.reset();
        return handled;
    }

    @Override
    protected void onDetachedFromWindow() {
        if (liquidTouch != null) liquidTouch.reset();
        liquidPressAnimations.restore();
        super.onDetachedFromWindow();
    }

    private static final int ANIMATOR_ID_LOADING_VISIBILITY = 0;
    private static final int ANIMATOR_ID_IS_ENABLED = 1;

    private final BoolAnimator animatorLoadingVisibility = new BoolAnimator(ANIMATOR_ID_LOADING_VISIBILITY, this, CubicBezierInterpolator.EASE_OUT_QUINT, 320);
    private final BoolAnimator animatorIsEnabled = new BoolAnimator(ANIMATOR_ID_IS_ENABLED, this, CubicBezierInterpolator.EASE_OUT_QUINT, 320, true);

    private @Nullable ImageView imageView;
    private @Nullable ImageView loadingIndicatorView;
    private CircularProgressDrawable loadingIndicatorDrawable;
    private Theme.ResourcesProvider resourcesProvider;

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        if (backgroundDrawable != null) backgroundDrawable.setBounds(0, 0, w, h);
    }

    @Override
    public void draw(@NonNull Canvas canvas) {
        liquidPressAnimations.sync(this, liquidTouchAllowed());
        if (liquidTouch != null) {
            LiquidTouchEffect.updateBackground(backgroundDrawable, liquidTouchAllowed() ? liquidTouch : null, 0f, 0f);
        }
        int save = liquidTouchAllowed() ? liquidTouch.begin(canvas) : -1;
        if (backgroundDrawable != null) backgroundDrawable.draw(canvas);
        super.draw(canvas);
        if (save != -1) canvas.restoreToCount(save);
    }

    /** Update before a parent draws our cached display list under its liquid transform. */
    public void updateLiquidBackground(LiquidTouchEffect effect, float originX, float originY) {
        if (LiquidTouchEffect.updateBackground(backgroundDrawable, effect, originX, originY)) {
            invalidate();
        }
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        if (liquidTouchAllowed()) liquidTouch.drawHighlight(canvas, dp(22), dp(6));
        super.dispatchDraw(canvas);
    }

    public void setIcon(@DrawableRes int resId) {
        setIcon(resId, 48);
    }
    public void setIcon(@DrawableRes int resId, int size) {
        if (imageView == null) {
            if (resId == 0) {
                return;
            }

            imageView = new ImageView(getContext());
            imageView.setScaleType(ImageView.ScaleType.CENTER);
            addView(imageView, LayoutHelper.createFrame(size, size, Gravity.CENTER));
            checkUi_IconViewVisibility();
        }

        imageView.setImageResource(resId);
    }

    public void setIconPadding(int paddingTop) {
        if (imageView != null) {
            imageView.setPadding(0, paddingTop, 0, 0);
        }
    }

    private float buttonScaleY = 1;
    public void reverseIconByY() {
        buttonScaleY = -1;
        checkUi_IconViewVisibility();
    }

    public void setIconColor(int color) {
        if (imageView == null) {
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            imageView.setColorFilter(new BlendModeColorFilter(color, BlendMode.SRC_IN));
        } else {
            imageView.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.SRC_IN));
        }
    }

    @Override
    public void setEnabled(boolean enabled) {
        setEnabled(enabled, false);
    }

    public void setEnabled(boolean enabled, boolean animated) {
        super.setEnabled(enabled);
        animatorIsEnabled.setValue(enabled, animated);
    }

    private BlurredBackgroundDrawable backgroundDrawable;
    public void setBlurredBackgroundDrawable(BlurredBackgroundDrawable drawable) {
        backgroundDrawable = drawable;
        if (backgroundDrawable != null) {
            backgroundDrawable.setPadding(dp(CLICK_ZONE_MARGIN));
            backgroundDrawable.setRadius(dp(BUTTON_SIZE / 2f));
        }
    }

    public void showLoading(boolean loading, boolean animated) {
        if (loadingIndicatorView == null) {
            if (!loading) {
                return;
            }

            loadingIndicatorDrawable = new CircularProgressDrawable(AndroidUtilities.dp(18), AndroidUtilities.dp(1.7f), 0xFF757575);
            loadingIndicatorDrawable.setAngleOffset(90);

            loadingIndicatorView = new ImageView(getContext());
            loadingIndicatorView.setBackground(loadingIndicatorDrawable);
            loadingIndicatorView.setVisibility(GONE);

            addView(loadingIndicatorView, LayoutHelper.createFrame(46, 46, Gravity.CENTER));
        }

        if (!animatorLoadingVisibility.getValue() && animatorLoadingVisibility.getFloatValue() == 0) {
            loadingIndicatorDrawable.reset();
        }
        animatorLoadingVisibility.setValue(loading, animated);
    }

    @Override
    public void onFactorChanged(int id, float factor, float fraction, FactorAnimator callee) {
        if (id == ANIMATOR_ID_LOADING_VISIBILITY) {
            checkUi_IconViewVisibility();
            checkUi_LoadingViewVisibility();
        }
        if (id == ANIMATOR_ID_IS_ENABLED) {
            checkUi_IconViewVisibility();
            checkUi_LoadingViewVisibility();
        }
    }
    public static ChatActivityBlurredRoundButton create(
        Context context,
        BlurredBackgroundDrawableViewFactory factory,
        BlurredBackgroundColorProvider colorProvider,
        Theme.ResourcesProvider resourcesProvider
    ) {
        ChatActivityBlurredRoundButton button;

        final int color = Theme.getColor(Theme.key_glass_defaultIcon, resourcesProvider);
        button = new ChatActivityBlurredRoundButton(context);
        button.resourcesProvider = resourcesProvider;
        button.setBlurredBackgroundDrawable(factory.create(button, colorProvider));
        button.setIconColor(color);
        int rad = dp(22);
        int pressedColor = Theme.multAlpha(color, .15f);
        button.setBackground(Theme.createInsetRoundRectDrawable(pressedColor, rad, dp(6)));

        return button;
    }

    public static ChatActivityBlurredRoundButton create(
        Context context,
        BlurredBackgroundDrawableViewFactory factory,
        BlurredBackgroundColorProvider colorProvider,
        Theme.ResourcesProvider resourcesProvider,
        @DrawableRes int res,
        int iconSize
    ) {
        ChatActivityBlurredRoundButton button;

        final int color = Theme.getColor(Theme.key_glass_defaultIcon, resourcesProvider);
        button = new ChatActivityBlurredRoundButton(context);
        button.resourcesProvider = resourcesProvider;
        button.setBlurredBackgroundDrawable(factory.create(button, colorProvider));
        button.setIcon(res, iconSize);
        button.setIconColor(color);
        int rad = dp(22);
        int pressedColor = Theme.multAlpha(color, .15f);
        button.setBackground(Theme.createInsetRoundRectDrawable(pressedColor, rad, dp(6)));

        return button;
    }

    public void updateColors() {
        if (backgroundDrawable != null) {
            backgroundDrawable.updateColors();
            invalidate();
        }

        final int color = Theme.getColor(Theme.key_glass_defaultIcon, resourcesProvider);
        setIconColor(Theme.getColor(Theme.key_glass_defaultIcon, resourcesProvider));
        int rad = dp(22);
        int pressedColor = Theme.multAlpha(color, .15f);
        setBackground(Theme.createInsetRoundRectDrawable(pressedColor, rad, dp(6)));
    }

    private void checkUi_IconViewVisibility() {
        final float visibility = 1f - animatorLoadingVisibility.getFloatValue();
        final float alpha = lerp(visibility / 2f, visibility, animatorIsEnabled.getFloatValue());

        if (imageView != null) {
            imageView.setAlpha(alpha);
            imageView.setScaleX(lerp(0.4f, 1f, visibility));
            imageView.setScaleY(lerp(0.4f, 1f, visibility) * buttonScaleY);
            imageView.setVisibility(visibility > 0 ? VISIBLE : GONE);
        }
    }

    private void checkUi_LoadingViewVisibility() {
        final float visibility = animatorLoadingVisibility.getFloatValue();
        final float alpha = lerp(visibility / 2f, visibility, animatorIsEnabled.getFloatValue());

        if (loadingIndicatorView != null) {
            loadingIndicatorView.setAlpha(alpha);
            loadingIndicatorView.setScaleX(lerp(0.4f, 1f, visibility));
            loadingIndicatorView.setScaleY(lerp(0.4f, 1f, visibility));

            final int newVisibility = visibility > 0 ? VISIBLE : GONE;
            if (loadingIndicatorView.getVisibility() != newVisibility) {
                loadingIndicatorView.setVisibility(newVisibility);
                loadingIndicatorDrawable.reset();
            }
        }
    }
}
