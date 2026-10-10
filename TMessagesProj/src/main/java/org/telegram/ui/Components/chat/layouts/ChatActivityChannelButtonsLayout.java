package org.telegram.ui.Components.chat.layouts;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;
import static org.telegram.messenger.LocaleController.getString;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.utils.ViewOutlineProviderImpl;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LiquidTouchEffect;
import org.telegram.ui.Components.LiquidTouchDispatcher;
import org.telegram.ui.Components.LiquidPressAnimationSuppressor;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.LiquidGlassInputGroup;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;
import zxc.iconic.xenon.helpers.NonIslandHelper;
import org.telegram.ui.Components.chat.buttons.ChatActivityBlurredRoundButton;

import java.util.HashSet;

import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;

@SuppressLint("ViewConstructor")
public class ChatActivityChannelButtonsLayout extends FrameLayout implements FactorAnimator.Target, LiquidTouchDispatcher.Target {
    public static final int BUTTON_SEARCH = 0;
    public static final int BUTTON_GIFT = 1;
    public static final int BUTTON_DIRECT = 2;
    public static final int BUTTON_GIGA_GROUP_INFO = 3;
    public static final int BUTTON_RECENT_ACTIONS_INFO = 4;
    private static final int BUTTONS_COUNT = 5;

    private final ButtonHolder[] buttonHolders = new ButtonHolder[BUTTONS_COUNT];
    private final OnClickListener[] onClickListeners = new OnClickListener[BUTTONS_COUNT];
    private final OnButtonFullyVisibleListener[] onButtonFullyVisible = new OnButtonFullyVisibleListener[BUTTONS_COUNT];
    private OnButtonsTotalWidthChanged onButtonsTotalWidthChanged;
    private final FrameLayout container;
    private boolean centerUsesAvailableWidth;
    private boolean iosInputLayout;
    private int inputLayoutWidth;
    private boolean liquidTouchEnabled;
    private LiquidTouchEffect centerLiquid;
    private float centerLiquidX, centerLiquidY;
    private LiquidGlassInputGroup liquidGroup;
    private boolean liquidGroupFailed, liquidGroupDrawn;
    private final BlurredBackgroundDrawable[] groupMembers = new BlurredBackgroundDrawable[6];
    private final LiquidTouchEffect[] groupEffects = new LiquidTouchEffect[6];
    private final RectF[] groupBounds = {new RectF(), new RectF(), new RectF(), new RectF(), new RectF(), new RectF()};
    private final float[] groupAlpha = new float[6];
    private final float[] groupOrigins = new float[12];

    /** Draw in the input container's coordinates, keeping each button's spring independent. */
    public boolean drawLiquidGlassGroup(Canvas canvas, BlurredBackgroundDrawable center,
                                        LiquidTouchEffect centerEffect, float originX, float originY) {
        liquidGroupDrawn = false;
        for (ButtonHolder holder : buttonHolders) {
            if (holder == null) continue;
            if (holder.button.getLiquidSurface() != null) holder.button.getLiquidSurface().setAlpha(255);
        }
        if (!liquidTouchAllowed() || Build.VERSION.SDK_INT < 33 || !canvas.isHardwareAccelerated()
                || liquidGroupFailed || center == null) {
            for (ButtonHolder holder : buttonHolders) if (holder != null) holder.button.setLiquidGroupDrawn(false);
            return false;
        }
        try {
            if (liquidGroup == null) {
                liquidGroup = new LiquidGlassInputGroup();
                for (int a = 0; a < 6; a++) for (int b = a + 1; b < 6; b++)
                    liquidGroup.setPairMergeDistance(a, b, dp(6));
            }
            for (int i = 0; i < 6; i++) {
                groupMembers[i] = null; groupEffects[i] = null; groupAlpha[i] = 0f;
                groupOrigins[i * 2] = groupOrigins[i * 2 + 1] = 0f;
            }
            groupMembers[1] = center;
            groupEffects[1] = centerEffect;
            groupAlpha[1] = 1f;
            groupBounds[1].set(center.getPaddedBounds());
            if (centerEffect != null) centerEffect.mapBounds(groupBounds[1], groupBounds[1]);
            LiquidTouchEffect.updateBackground(center, centerEffect, 0f, 0f);
            if (!center.hasDisplayList()) center.updateDisplayList();
            for (int i = 0; i < BUTTONS_COUNT; i++) {
                ButtonHolder holder = buttonHolders[i];
                if (holder == null || holder.button.getVisibility() != VISIBLE
                        || holder.button.getWidth() <= 0 || holder.button.getHeight() <= 0) continue;
                View button = holder.button;
                BlurredBackgroundDrawable drawable = holder.button.getLiquidSurface();
                if (drawable == null) continue;
                int slot = i == 0 ? 0 : i + 1;
                groupMembers[slot] = drawable; groupEffects[slot] = holder.liquid; groupAlpha[slot] = 1f;
                drawable.setAlpha(Math.round(255 * button.getAlpha()));
                drawable.setBounds(0, 0, button.getWidth(), button.getHeight());
                RectF bounds = groupBounds[slot];
                bounds.set(drawable.getPaddedBounds());
                bounds.set((bounds.left - button.getPivotX()) * button.getScaleX() + button.getPivotX() + button.getX(),
                        (bounds.top - button.getPivotY()) * button.getScaleY() + button.getPivotY() + button.getY(),
                        (bounds.right - button.getPivotX()) * button.getScaleX() + button.getPivotX() + button.getX(),
                        (bounds.bottom - button.getPivotY()) * button.getScaleY() + button.getPivotY() + button.getY());
                if (holder.liquid != null) holder.liquid.mapBounds(bounds, bounds);
                bounds.offset(originX, originY);
                groupOrigins[slot * 2] = originX;
                groupOrigins[slot * 2 + 1] = originY;
                LiquidTouchEffect.updateBackground(drawable, holder.liquid, button.getX(), button.getY());
                if (!drawable.hasDisplayList()) drawable.updateDisplayList();
            }
            liquidGroup.draw(canvas, groupMembers, groupBounds, groupAlpha, groupEffects, groupOrigins);
            liquidGroupDrawn = true;
            for (int i = 0; i < BUTTONS_COUNT; i++) {
                if (buttonHolders[i] != null)
                    buttonHolders[i].button.setLiquidGroupDrawn(groupMembers[i == 0 ? 0 : i + 1] != null);
            }
        } catch (RuntimeException error) {
            FileLog.e(error); liquidGroupFailed = true; liquidGroup = null;
            for (ButtonHolder holder : buttonHolders) {
                if (holder != null) holder.button.setLiquidGroupDrawn(false);
                if (holder != null && holder.button.getLiquidSurface() != null)
                    holder.button.getLiquidSurface().setAlpha(255);
            }
        }
        return liquidGroupDrawn;
    }

    @Override
    public void invalidate() {
        super.invalidate();
        // The optical union is recorded by the enclosing input container.
        if (liquidGroupDrawn && getParent() != null && getParent().getParent() instanceof View)
            ((View) getParent().getParent()).invalidate();
    }
    private final LiquidPressAnimationSuppressor liquidPressAnimations = new LiquidPressAnimationSuppressor();

    public void setLiquidTouchEnabled(boolean enabled) {
        if (liquidTouchEnabled == enabled) return;
        liquidTouchEnabled = enabled;
        for (ButtonHolder holder : buttonHolders) {
            if (holder == null) continue;
            if (holder.liquid != null) holder.liquid.reset();
            holder.liquid = enabled ? new LiquidTouchEffect(this) : null;
        }
        if (!enabled) liquidPressAnimations.restore();
        setClipChildren(false);
        setClipToPadding(false);
        invalidate();
    }

    public void setLiquidCenterEffect(LiquidTouchEffect effect, float x, float y) {
        centerLiquid = effect;
        centerLiquidX = x;
        centerLiquidY = y;
    }

    private boolean liquidTouchAllowed() {
        return liquidTouchEnabled && !NonIslandHelper.chatElements();
    }

    public boolean isLiquidSideButtonHit(float x, float y) {
        for (ButtonHolder holder : buttonHolders) {
            if (holder == null) continue;
            View button = holder.button;
            if (button.getVisibility() == VISIBLE && x >= button.getX() && x < button.getX() + button.getWidth()
                    && y >= button.getY() && y < button.getY() + button.getHeight()) return true;
        }
        return false;
    }

    @Override
    public void observeLiquidTouch(MotionEvent event) {
        boolean enabled = liquidTouchAllowed();
        liquidPressAnimations.sync(this, enabled);
        for (ButtonHolder holder : buttonHolders) {
            if (holder == null || holder.liquid == null) continue;
            View button = holder.button;
            holder.liquid.setBounds(button.getX(), button.getY(),
                    button.getX() + button.getWidth(), button.getY() + button.getHeight());
            holder.liquid.onTouchEvent(event, enabled && button.getVisibility() == VISIBLE && button.isEnabled());
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) holder.liquid.setMinimumPressDuration(100);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (!LiquidTouchDispatcher.isDispatchingControls()) observeLiquidTouch(event);
        boolean handled = super.dispatchTouchEvent(event);
        if (!LiquidTouchDispatcher.isDispatchingControls() && !handled && event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            for (ButtonHolder holder : buttonHolders) {
                if (holder != null && holder.liquid != null) holder.liquid.reset();
            }
        }
        return handled;
    }

    @Override
    protected void onDetachedFromWindow() {
        for (ButtonHolder holder : buttonHolders) {
            if (holder != null && holder.liquid != null) holder.liquid.reset();
        }
        centerLiquid = null;
        liquidPressAnimations.restore();
        super.onDetachedFromWindow();
    }

    private final HashSet<View> wrapContentButtons = new HashSet<>();

    private static final @DrawableRes int[] buttonIcons = new int[] {
        R.drawable.msg_search,
        R.drawable.input_gift_s,
        R.drawable.input_message,
        R.drawable.msg_help,
        R.drawable.msg_help
    };
    private static final int[] buttonsOrderLeft = new int[] {
        BUTTON_SEARCH
    };
    private static final int[] buttonsOrderRight = new int[] {
        BUTTON_GIFT,
        BUTTON_DIRECT,
        BUTTON_GIGA_GROUP_INFO,
        BUTTON_RECENT_ACTIONS_INFO
    };
    private static final int[] iosButtonsOrderLeft = new int[] {
        BUTTON_GIFT
    };
    private static final int[] iosButtonsOrderRight = new int[] {
        BUTTON_SEARCH,
        BUTTON_DIRECT,
        BUTTON_GIGA_GROUP_INFO,
        BUTTON_RECENT_ACTIONS_INFO
    };

    private final Theme.ResourcesProvider resourcesProvider;
    private final BlurredBackgroundDrawableViewFactory blurredBackgroundDrawableViewFactory;
    private final BlurredBackgroundColorProvider colorProvider;

    public ChatActivityChannelButtonsLayout(@NonNull Context context,
                                            Theme.ResourcesProvider resourcesProvider,
                                            BlurredBackgroundColorProvider colorProvider,
                                            BlurredBackgroundDrawableViewFactory blurredBackgroundDrawableViewFactory) {
        super(context);
        this.blurredBackgroundDrawableViewFactory = blurredBackgroundDrawableViewFactory;
        this.colorProvider = colorProvider;
        this.resourcesProvider = resourcesProvider;

        container = new FrameLayout(context);
        container.setClipToOutline(true);
        container.setOutlineProvider(ViewOutlineProviderImpl.boundsWithPaddingRoundRect(0, dp(22)));
        addView(container, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 44, Gravity.CENTER_VERTICAL));
    }

    public void updateColors() {
        for (ButtonHolder holder : buttonHolders) {
            if (holder != null) {
                holder.button.updateColors();
            }
        }
    }

    public FrameLayout getContainer() {
        return container;
    }

    public void setIosInputLayout(boolean enabled) {
        if (iosInputLayout == enabled) return;
        iosInputLayout = enabled;
        checkContainerPaddings(true);
        checkButtonsPositionsAndVisibility();
        requestLayout();
        invalidate();
    }

    public void setCenterUsesAvailableWidth(boolean enabled) {
        if (centerUsesAvailableWidth == enabled) return;
        centerUsesAvailableWidth = enabled;
        requestLayout();
        invalidate();
    }

    public void setInputCollapsed(boolean collapsed) {
        animatorInputCollapsed.setValue(collapsed, isLaidOut() && getVisibility() == VISIBLE);
    }

    private int getCollapsedInset() {
        if (!iosInputLayout || !centerUsesAvailableWidth) return 0;
        // Match the chat field's 10% resting-width reduction, split between both sides.
        int centerWidth = Math.max(0, inputLayoutWidth - dp(2 * (7 + 2 * (44 + 10))));
        return Math.round(centerWidth * 0.05f * animatorInputCollapsed.getFloatValue());
    }

    public void makeViewWrapContent(View view) {
        wrapContentButtons.add(view);
    }

    public void showButton(final int buttonId, boolean show, boolean animated) {
        if (buttonId < 0 || buttonId >= buttonHolders.length) {
            return;
        }

        if (buttonHolders[buttonId] == null && !show) {
            return;
        }

        if (buttonHolders[buttonId] == null) {
            final int animatorId = (buttonId << 16) | VISIBILITY_ANIMATOR_ID;
            final BoolAnimator visibilityAnimator = new BoolAnimator(animatorId, this,
                CubicBezierInterpolator.EASE_OUT_QUINT, 300);

            final ChatActivityBlurredRoundButton button = ChatActivityBlurredRoundButton.create(
                getContext(),
                blurredBackgroundDrawableViewFactory,
                colorProvider,
                resourcesProvider,
                buttonIcons[buttonId],
                48
            );

            if (buttonId == BUTTON_GIFT) {
                button.setContentDescription(getString(R.string.ProfileActionsGift));
            } else if (buttonId == BUTTON_DIRECT) {
                button.setContentDescription(getString(R.string.ChannelOpenDirect));
            } else if (buttonId == BUTTON_SEARCH) {
                button.setContentDescription(getString(R.string.Search));
            } else if (buttonId == BUTTON_GIGA_GROUP_INFO) {
                button.setContentDescription(getString(R.string.BroadcastGroupInfo));
            }

            ScaleStateListAnimator.apply(button, .13f, 2f);
            if (NonIslandHelper.chatElements()) {
                button.setBlurredBackgroundDrawable(null);
            }
            button.setVisibility(GONE);
            button.setOnClickListener(v -> {
                if (onClickListeners[buttonId] != null) {
                    onClickListeners[buttonId].onClick(v);
                }
            });
            addView(button, LayoutHelper.createFrame(56, 56, Gravity.CENTER_VERTICAL | Gravity.LEFT));

            buttonHolders[buttonId] = new ButtonHolder(button, visibilityAnimator);
            if (liquidTouchEnabled) buttonHolders[buttonId].liquid = new LiquidTouchEffect(this);
            checkButtonsPositionsAndVisibility();
        }

        buttonHolders[buttonId].visibilityAnimator.setValue(show, animated);
    }

    private BlurredBackgroundDrawable containerDrawable;
    public void setupDrawableForContainer() {
        containerDrawable = blurredBackgroundDrawableViewFactory.create(this)
            .setColorProvider(colorProvider)
            .setRadius(dp(22))
            .setPadding(dp(6));
    }

    public boolean isButtonVisible(final int buttonId) {
        if (buttonId < 0 || buttonId >= buttonHolders.length || buttonHolders[buttonId] == null) {
            return false;
        }

        return buttonHolders[buttonId].visibilityAnimator.getValue();
    }

    public void setButtonOnClickListener(int buttonId, View.OnClickListener listener) {
        this.onClickListeners[buttonId] = listener;
    }

    public void setButtonOnFullyVisibleListener(int buttonId, OnButtonFullyVisibleListener listener) {
        this.onButtonFullyVisible[buttonId] = listener;
    }

    public void setOnButtonsTotalWidthChanged(OnButtonsTotalWidthChanged onButtonsTotalWidthChanged) {
        this.onButtonsTotalWidthChanged = onButtonsTotalWidthChanged;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // Compute the inset from the new width, including after rotation.
        inputLayoutWidth = MeasureSpec.getSize(widthMeasureSpec);
        checkContainerPaddings(false);
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        checkButtonsPositionsAndVisibility();
    }



    private static final int CENTER_ACCENT_BACKGROUND_ANIMATOR_ID = 99;
    private final BoolAnimator animatorCenterAccentBackground = new BoolAnimator(
        CENTER_ACCENT_BACKGROUND_ANIMATOR_ID, this, CubicBezierInterpolator.EASE_OUT_QUINT, 320L
    );
    private static final int WRAPPING_BUTTON_ANIMATOR_ID = 100;
    private final BoolAnimator animatorWrappingButton = new BoolAnimator(
        WRAPPING_BUTTON_ANIMATOR_ID, this, CubicBezierInterpolator.EASE_OUT_QUINT, 320L
    );

    public void setCenterAccentBackground(boolean accent, boolean animated) {
        animatorCenterAccentBackground.setValue(accent, animated);
    }

    private static final int VISIBILITY_ANIMATOR_ID = 1;
    private static final int INPUT_COLLAPSED_ANIMATOR_ID = 101;
    private final BoolAnimator animatorInputCollapsed = new BoolAnimator(
        INPUT_COLLAPSED_ANIMATOR_ID, this, CubicBezierInterpolator.EASE_OUT_QUINT, 300L
    );

    private float totalVisibilityFactor;
    public void setTotalVisibilityFactor(float factor) {
        if (totalVisibilityFactor != factor) {
            totalVisibilityFactor = factor;
            checkButtonsPositionsAndVisibility();
            invalidate();
        }
    }

    @Override
    public void onFactorChanged(int id, float factor, float fraction, FactorAnimator callee) {
        if (id == INPUT_COLLAPSED_ANIMATOR_ID) {
            checkContainerPaddings(true);
            checkButtonsPositionsAndVisibility();
            invalidate();
            return;
        }
        if (id == CENTER_ACCENT_BACKGROUND_ANIMATOR_ID) {
            invalidate();
            return;
        }
        if (id == WRAPPING_BUTTON_ANIMATOR_ID) {
            checkButtonsPositionsAndVisibility();
            invalidate();
        }

        final int buttonId = id >> 16;
        final int animatorId = id & 0xFFFF;
        if (buttonId < 0 || buttonId >= buttonHolders.length || buttonHolders[buttonId] == null) {
            return;
        }

        if (animatorId == VISIBILITY_ANIMATOR_ID) {
            checkContainerPaddings(true);
            checkButtonsPositionsAndVisibility();
            invalidate();
        }
    }

    @Override
    public void onFactorChangeFinished(int id, float finalFactor, FactorAnimator callee) {
        if (id == CENTER_ACCENT_BACKGROUND_ANIMATOR_ID || id == WRAPPING_BUTTON_ANIMATOR_ID) {
            invalidate();
        }

        final int buttonId = id >> 16;
        final int animatorId = id & 0xFFFF;
        if (buttonId < 0 || buttonId >= buttonHolders.length || buttonHolders[buttonId] == null) {
            return;
        }

        final ButtonHolder holder = buttonHolders[buttonId];
        if (animatorId == VISIBILITY_ANIMATOR_ID) {
            if (holder.visibilityAnimator.getValue()) {
                if (onButtonFullyVisible[buttonId] != null) {
                    onButtonFullyVisible[buttonId].onButtonFullyVisible(holder.button, buttonId, !holder.wasShown);
                }
                holder.wasShown = true;
            }
        }
    }

    private float totalWidthLeft, totalWidthRight;

    private void checkContainerPaddings(boolean canRequestLayout) {
        int paddingLeft = NonIslandHelper.chatElements() ? 0 : dp(7), paddingRight = NonIslandHelper.chatElements() ? 0 : dp(7);
        for (final int buttonId : iosInputLayout ? iosButtonsOrderLeft : buttonsOrderLeft) {
            final ButtonHolder holder = buttonHolders[buttonId];
            if (holder == null) {
                continue;
            }
            paddingLeft += holder.visibilityAnimator.getValue() ? dp(44 + 10) : 0;
        }

        for (final int buttonId : iosInputLayout ? iosButtonsOrderRight : buttonsOrderRight) {
            final ButtonHolder holder = buttonHolders[buttonId];
            if (holder == null) {
                continue;
            }
            paddingRight += holder.visibilityAnimator.getValue() ? dp(44 + 10) : 0;
        }

        final FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) container.getLayoutParams();
        // Keep the centered width with search and direct messages on the same side,
        // even when direct messages or gifts are unavailable in this channel.
        if (centerUsesAvailableWidth) {
            paddingLeft = paddingRight = dp(7 + 2 * (44 + 10));
            // Search (with or without a gift) has room to move without narrowing mute.
            ButtonHolder direct = buttonHolders[BUTTON_DIRECT];
            if (direct != null) {
                int inset = Math.round(getCollapsedInset() * direct.visibilityAnimator.getFloatValue());
                paddingLeft += inset;
                paddingRight += inset;
            }
        }
        int width = LayoutHelper.MATCH_PARENT;
        int gravity = centerUsesAvailableWidth ? Gravity.CENTER : Gravity.CENTER_VERTICAL;
        if (lp.leftMargin != paddingLeft || lp.rightMargin != paddingRight || lp.width != width || lp.gravity != gravity) {
            lp.width = width;
            lp.gravity = gravity;
            lp.leftMargin = paddingLeft;
            lp.rightMargin = paddingRight;
            if (canRequestLayout) {
                container.requestLayout();
            }
        }
    }

    private void checkButtonsPositionsAndVisibility() {
        totalWidthLeft = 0;
        totalWidthRight = 0;
        final int collapsedInset = getCollapsedInset();

        for (final ButtonHolder holder: buttonHolders) {
            if (holder == null) {
                continue;
            }

            final float visibility = holder.visibilityAnimator.getFloatValue() * totalVisibilityFactor;
            holder.button.setVisibility(visibility > 0 ? VISIBLE : GONE);
            holder.button.setAlpha(visibility);
            holder.button.setScaleX(lerp(0.4f, 1f, visibility));
            holder.button.setScaleY(lerp(0.4f, 1f, visibility));
        }

        for (final int buttonId : iosInputLayout ? iosButtonsOrderLeft : buttonsOrderLeft) {
            final ButtonHolder holder = buttonHolders[buttonId];
            if (holder == null) {
                continue;
            }

            final float width = holder.visibilityAnimator.getFloatValue() * dp(44 + 10);    // width + margin
            holder.button.setTranslationX(dp(1) + collapsedInset + totalWidthLeft);
            totalWidthLeft += width;
        }

        for (final int buttonId : iosInputLayout ? iosButtonsOrderRight : buttonsOrderRight) {
            final ButtonHolder holder = buttonHolders[buttonId];
            if (holder == null) {
                continue;
            }

            final float width = holder.visibilityAnimator.getFloatValue() * dp(44 + 10);    // width + margin
            holder.button.setTranslationX(getMeasuredWidth() - holder.button.getMeasuredWidth() - dp(1) - collapsedInset - totalWidthRight);
            totalWidthRight += width;
        }

        if (totalVisibilityFactor < 1) {
            for (final int buttonId : iosInputLayout ? iosButtonsOrderLeft : buttonsOrderLeft) {
                final ButtonHolder holder = buttonHolders[buttonId];
                if (holder == null) {
                    continue;
                }

                holder.button.setTranslationX(holder.button.getTranslationX() - totalWidthLeft * (1 - totalVisibilityFactor));
            }

            for (final int buttonId : iosInputLayout ? iosButtonsOrderRight : buttonsOrderRight) {
                final ButtonHolder holder = buttonHolders[buttonId];
                if (holder == null) {
                    continue;
                }

                holder.button.setTranslationX(holder.button.getTranslationX() + totalWidthRight * (1 - totalVisibilityFactor));
            }

            totalWidthLeft *= totalVisibilityFactor;
            totalWidthRight *= totalVisibilityFactor;
        }

        final float wrapping = animatorWrappingButton.getFloatValue();
        if (wrapping > 0 && getMeasuredWidth() > 0) {
            float left = getMeasuredWidth(), right = 0;
            for (int i = 0; i < getContainer().getChildCount(); ++i) {
                final View child = getContainer().getChildAt(i);
                if (wrapContentButtons.contains(child)) {
                    left  = Math.min(left, child.getLeft());
                    right = Math.max(right, child.getRight());
                }
            }
            if (left > right) {
                left = right = (left + right) / 2f;
            }
            totalWidthLeft = lerp(totalWidthLeft, left - dp(3.33f), wrapping);
            totalWidthRight = lerp(totalWidthRight, getMeasuredWidth() - right - dp(17.66f), wrapping);
        }

        if (centerUsesAvailableWidth && totalVisibilityFactor > 0 && container.getWidth() > 0) {
            totalWidthLeft = Math.max(totalWidthLeft, container.getLeft() - dp(7));
            totalWidthRight = Math.max(totalWidthRight, getMeasuredWidth() - container.getRight() - dp(7));
        }
        if (onButtonsTotalWidthChanged != null) {
            onButtonsTotalWidthChanged.onButtonsTotalWidthChanged(totalWidthLeft, totalWidthRight);
        }
    }

    public void updateWrappingVisible(boolean animated) {
        boolean hasVisibleWrapping = false;
        if (getVisibility() == View.VISIBLE && getContainer().getVisibility() == View.VISIBLE) {
            for (int i = 0; i < getContainer().getChildCount(); ++i) {
                final View child = getContainer().getChildAt(i);
                if (wrapContentButtons.contains(child) && child.getVisibility() == View.VISIBLE) {
                    hasVisibleWrapping = true;
                }
            }
        }
        animatorWrappingButton.setValue(hasVisibleWrapping, animated);
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);
        updateWrappingVisible(false);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);

        checkButtonsPositionsAndVisibility();
    }

    public interface OnButtonsTotalWidthChanged {
        void onButtonsTotalWidthChanged(float left, float right);
    }

    private static final RectF tmpRect = new RectF();
    private final Paint backgroundAccentPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public interface OnButtonFullyVisibleListener {
        void onButtonFullyVisible(View v, int buttonId, boolean firstTime);
    }

    @Override
    protected boolean drawChild(@NonNull Canvas canvas, View child, long drawingTime) {
        liquidPressAnimations.sync(this, liquidTouchAllowed());
        LiquidTouchEffect sideLiquid = null;
        for (ButtonHolder holder : buttonHolders) {
            if (holder != null && holder.button == child) {
                sideLiquid = holder.liquid;
                break;
            }
        }
        int save = -1;
        if (child instanceof ChatActivityBlurredRoundButton) {
            ((ChatActivityBlurredRoundButton) child).updateLiquidBackground(
                    liquidTouchAllowed() ? sideLiquid : null, child.getX(), child.getY());
        }
        if (liquidTouchAllowed()) {
            if (child == container && centerLiquid != null) {
                save = centerLiquid.beginInChild(canvas, centerLiquidX, centerLiquidY);
            } else if (sideLiquid != null) {
                save = sideLiquid.begin(canvas);
            }
        }
        if (child == container && containerDrawable != null) {
            tmpRect.set(
                totalWidthLeft + dp(1), 0,
                getMeasuredWidth() - dp(1) - totalWidthRight,
                getMeasuredHeight());

            tmpRect.round(AndroidUtilities.rectTmp2);
            containerDrawable.setBounds(AndroidUtilities.rectTmp2);
            LiquidTouchEffect.updateBackground(containerDrawable, liquidTouchAllowed() ? centerLiquid : null, centerLiquidX, centerLiquidY);
            containerDrawable.draw(canvas);
        }

        boolean result = super.drawChild(canvas, child, drawingTime);
        if (!liquidGroupDrawn && sideLiquid != null && liquidTouchAllowed()) sideLiquid.drawHighlight(canvas, dp(22), dp(6));
        if (save != -1) canvas.restoreToCount(save);
        return result;
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        final int accentAlpha = (int) (255 * totalVisibilityFactor * animatorCenterAccentBackground.getFloatValue());
        if (accentAlpha > 0) {
            int save = liquidTouchAllowed() && centerLiquid != null
                    ? centerLiquid.beginInChild(canvas, centerLiquidX, centerLiquidY) : -1;
            tmpRect.set(
                totalWidthLeft + dp(10),
                dp(9),
                getMeasuredWidth() - dp(10) - totalWidthRight,
                getMeasuredHeight() - dp(9)
            );
            backgroundAccentPaint.setColor(accentColor);
            backgroundAccentPaint.setAlpha(accentAlpha);
            canvas.drawRoundRect(tmpRect, dp(19), dp(19), backgroundAccentPaint);
            if (save != -1) canvas.restoreToCount(save);
        }

        super.dispatchDraw(canvas);
    }

    private int accentColor = 0;

    public void setAccentColor(int accentColor) {
        this.accentColor = accentColor;
    }

    private static class ButtonHolder {
        public final ChatActivityBlurredRoundButton button;
        public final BoolAnimator visibilityAnimator;
        public boolean wasShown;
        public LiquidTouchEffect liquid;

        private ButtonHolder(ChatActivityBlurredRoundButton button, BoolAnimator visibilityAnimator) {
            this.button = button;
            this.visibilityAnimator = visibilityAnimator;
        }
    }
}
