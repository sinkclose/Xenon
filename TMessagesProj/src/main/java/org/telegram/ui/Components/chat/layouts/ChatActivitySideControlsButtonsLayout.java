package org.telegram.ui.Components.chat.layouts;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.Build;
import android.view.View;
import android.view.Gravity;
import android.widget.FrameLayout;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.FileLog;
import org.telegram.ui.Components.LiquidTouchEffect;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.LiquidGlassInputGroup;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;
import org.telegram.ui.Components.chat.buttons.ChatActivityBlurredRoundPageDownButton;

import me.vkryl.android.AnimatorUtils;
import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;

@SuppressLint("ViewConstructor")
public class ChatActivitySideControlsButtonsLayout extends FrameLayout implements FactorAnimator.Target {
    public static final int BUTTON_ATTACH = 0;
    public static final int BUTTON_PAGE_DOWN = 1;
    public static final int BUTTON_MENTION = 2;
    public static final int BUTTON_REACTIONS = 3;
    public static final int BUTTON_POLL_VOTES = 4;
    public static final int BUTTON_SEARCH_DOWN = 5;
    public static final int BUTTON_SEARCH_UP = 6;

    private static final int BUTTONS_COUNT = 7;

    private static final int ANIMATOR_ID_VISIBILITY = 1;
    private static final int ANIMATOR_ID_COUNTER_VISIBILITY = 2;

    private static final @DrawableRes int[] buttonIcons = new int[] {
        R.drawable.msg_input_attach2,
        R.drawable.pagedown,
        R.drawable.mentionbutton,
        R.drawable.reactionbutton,
        R.drawable.menu_poll_notify,
        R.drawable.pagedown,
        R.drawable.pagedown
    };

    private final String[] buttonDescriptions = new String[] {
        LocaleController.getString(R.string.AttachMenu),
        LocaleController.getString(R.string.AccDescrPageDown),
        LocaleController.getString(R.string.AccDescrMentionDown),
        LocaleController.getString(R.string.AccDescrReactionMentionDown),
        LocaleController.getString(R.string.AccDescrPollVotesMentionDown),
        LocaleController.getString(R.string.AccDescrSearchPrev),
        LocaleController.getString(R.string.AccDescrSearchNext)
    };

    private final Theme.ResourcesProvider resourcesProvider;
    private final BlurredBackgroundColorProvider colorProvider;
    private final BlurredBackgroundDrawableViewFactory blurredBackgroundDrawableViewFactory;
    private final ButtonHolder[] buttonHolders = new ButtonHolder[BUTTONS_COUNT];
    private final ButtonPendingState[] pendingStates = new ButtonPendingState[BUTTONS_COUNT];

    private ButtonOnClickListener onClickListener;
    private ButtonOnLongClickListener onLongClickListener;

    public ChatActivitySideControlsButtonsLayout(@NonNull Context context,
                                            Theme.ResourcesProvider resourcesProvider,
                                            BlurredBackgroundColorProvider colorProvider,
                                            BlurredBackgroundDrawableViewFactory blurredBackgroundDrawableViewFactory) {
        super(context);
        this.blurredBackgroundDrawableViewFactory = blurredBackgroundDrawableViewFactory;
        this.colorProvider = colorProvider;
        this.resourcesProvider = resourcesProvider;
        setClipChildren(false);
        setClipToPadding(false);
    }

    private LiquidGlassInputGroup liquidGroup;
    private boolean liquidGroupFailed;
    private final BlurredBackgroundDrawable[] groupMembers = new BlurredBackgroundDrawable[BUTTONS_COUNT];
    private final LiquidTouchEffect[] groupEffects = new LiquidTouchEffect[BUTTONS_COUNT];
    private final RectF[] groupBounds = new RectF[BUTTONS_COUNT];
    private final RectF[] restingBounds = new RectF[BUTTONS_COUNT];
    private final float[] groupAlpha = new float[BUTTONS_COUNT];
    private final float[] groupOrigins = new float[BUTTONS_COUNT * 2];
    private final float[] groupSourceOrigins = new float[BUTTONS_COUNT * 2];

    @Override
    protected void dispatchDraw(Canvas canvas) {
        boolean merged = false;
        if (Build.VERSION.SDK_INT >= 33 && canvas.isHardwareAccelerated() && liquidTouchEnabled
                && !zxc.iconic.xenon.helpers.NonIslandHelper.chatElements() && !liquidGroupFailed) {
            try {
                if (liquidGroup == null) liquidGroup = new LiquidGlassInputGroup();
                for (int i = 0; i < BUTTONS_COUNT; i++) {
                    ButtonHolder holder = buttonHolders[i];
                    groupMembers[i] = null;
                    groupEffects[i] = null;
                    groupAlpha[i] = 0f;
                    if (groupBounds[i] == null) { groupBounds[i] = new RectF(); restingBounds[i] = new RectF(); }
                    if (holder == null || holder.button.getVisibility() != VISIBLE) continue;
                    ChatActivityBlurredRoundPageDownButton host = holder.button;
                    View surface = host.getLiquidSurfaceView();
                    BlurredBackgroundDrawable drawable = host.getLiquidSurface();
                    if (drawable == null || surface.getWidth() <= 0 || surface.getHeight() <= 0) continue;
                    groupMembers[i] = drawable;
                    groupEffects[i] = host.getLiquidEffect();
                    groupAlpha[i] = 1f;
                    drawable.setAlpha(Math.round(255 * host.getAlpha()));
                    drawable.setBounds(0, 0, surface.getWidth(), surface.getHeight());
                    RectF bounds = groupBounds[i];
                    bounds.set(drawable.getPaddedBounds());
                    bounds.offset(surface.getX(), surface.getY());
                    restingBounds[i].set(bounds);
                    restingBounds[i].offset(host.getX(), host.getY());
                    if (groupEffects[i] != null) groupEffects[i].mapBounds(bounds, bounds);
                    bounds.set((bounds.left - host.getPivotX()) * host.getScaleX() + host.getPivotX() + host.getX(),
                            (bounds.top - host.getPivotY()) * host.getScaleY() + host.getPivotY() + host.getY(),
                            (bounds.right - host.getPivotX()) * host.getScaleX() + host.getPivotX() + host.getX(),
                            (bounds.bottom - host.getPivotY()) * host.getScaleY() + host.getPivotY() + host.getY());
                    groupOrigins[i * 2] = host.getX(); groupOrigins[i * 2 + 1] = host.getY();
                    groupSourceOrigins[i * 2] = host.getX() + surface.getX();
                    groupSourceOrigins[i * 2 + 1] = host.getY() + surface.getY();
                }
                for (int i = 0; i < BUTTONS_COUNT; i++) {
                    if (groupMembers[i] == null) continue;
                    View surface = buttonHolders[i].button.getLiquidSurfaceView();
                    LiquidTouchEffect.updateBackground(groupMembers[i], groupEffects[i], surface.getX(), surface.getY());
                    if (!groupMembers[i].hasDisplayList()) groupMembers[i].updateDisplayList();
                    for (int j = i + 1; j < BUTTONS_COUNT; j++) {
                        if (groupMembers[j] == null) continue;
                        float low = Math.min(restingBounds[i].centerY(), restingBounds[j].centerY());
                        float high = Math.max(restingBounds[i].centerY(), restingBounds[j].centerY());
                        boolean adjacent = true;
                        for (int k = 0; k < BUTTONS_COUNT; k++) {
                            if (k != i && k != j && groupMembers[k] != null
                                    && restingBounds[k].centerY() > low && restingBounds[k].centerY() < high) adjacent = false;
                        }
                        float gap = Math.max(0f, high - low - restingBounds[i].height() / 2f - restingBounds[j].height() / 2f);
                        float mergeInset = (i == BUTTON_PAGE_DOWN || i == BUTTON_MENTION
                                || j == BUTTON_PAGE_DOWN || j == BUTTON_MENTION) ? dp(5.5f) : dp(1.5f);
                        liquidGroup.setPairMergeDistance(i, j, adjacent ? Math.max(dp(6), (gap - mergeInset) * 2f) : dp(6));
                    }
                }
                liquidGroup.draw(canvas, groupMembers, groupBounds, groupAlpha, groupEffects, groupOrigins, groupSourceOrigins);
                merged = true;
            } catch (RuntimeException error) {
                FileLog.e(error); liquidGroupFailed = true; liquidGroup = null;
            }
        }
        for (int i = 0; i < buttonHolders.length; i++) {
            ButtonHolder holder = buttonHolders[i];
            if (holder == null) continue;
            boolean memberDrawn = merged && groupMembers[i] != null;
            holder.button.setLiquidGroupDrawn(memberDrawn);
            if (!memberDrawn && holder.button.getLiquidSurface() != null) holder.button.getLiquidSurface().setAlpha(255);
        }
        super.dispatchDraw(canvas);
    }

    @Override
    protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        int save = child instanceof ChatActivityBlurredRoundPageDownButton
                ? ((ChatActivityBlurredRoundPageDownButton) child).beginLiquidDraw(canvas) : -1;
        boolean result = super.drawChild(canvas, child, drawingTime);
        if (save != -1) canvas.restoreToCount(save);
        return result;
    }

    private boolean liquidTouchEnabled;

    public void setLiquidTouchEnabled(boolean enabled) {
        liquidTouchEnabled = enabled;
        for (ButtonHolder holder : buttonHolders) {
            if (holder != null) holder.button.setLiquidTouchEnabled(enabled);
        }
    }

    private int gravity = Gravity.LEFT | Gravity.BOTTOM;
    public void setGravity(int gravity) {
        this.gravity = gravity;
    }

    public void setOnClickListener(ButtonOnClickListener onClickListener) {
        this.onClickListener = onClickListener;
    }

    public void setOnLongClickListener(ButtonOnLongClickListener onLongClickListener) {
        this.onLongClickListener = onLongClickListener;
    }

    public boolean getButtonLocationInWindow(final int buttonId, int[] loc) {
        final ButtonHolder holder = buttonHolders[buttonId];
        if (holder != null) {
            holder.button.getLocationInWindow(loc);
            return true;
        }
        return false;
    }

    public void updateColors() {
        for (ButtonHolder holder : buttonHolders) {
            if (holder != null) {
                holder.button.updateColors();
            }
        }
    }

    public void showButton(final int buttonId, boolean show, boolean animated) {
        if (buttonHolders[buttonId] == null && !show) {
            return;
        }

        final ButtonHolder holder = getOrCreateButtonHolder(buttonId);
        holder.visibilityAnimator.setValue(show, animated);
    }

    public void setButtonCount(final int buttonId, int count, boolean animated) {
        getOrCreatePendingState(buttonId).count = count;
        final ButtonHolder holder = buttonHolders[buttonId];
        if (holder != null) {
            holder.button.setCount(count, animated);
            holder.counterVisibilityAnimator.setValue(count > 0, animated);
        }
    }

    public void setButtonLoading(final int buttonId, boolean loading, boolean animated) {
        getOrCreatePendingState(buttonId).loading = loading;
        final ButtonHolder holder = buttonHolders[buttonId];
        if (holder != null) {
            holder.button.showLoading(loading, animated);
        }
    }

    public boolean isButtonVisible(final int buttonId) {
        final ButtonHolder holder = getButtonHolder(buttonId);

        return holder != null && holder.visibilityAnimator.getValue();
    }

    public void setButtonEnabled(final int buttonId, boolean enabled, boolean animated) {
        getOrCreatePendingState(buttonId).enabled = enabled;
        final ButtonHolder holder = buttonHolders[buttonId];
        if (holder != null) {
            holder.button.setEnabled(enabled, animated);
        }
    }


    @Override
    public void onFactorChanged(int id, float factor, float fraction, FactorAnimator callee) {
        final int buttonId = id >> 16;
        final int animatorId = id & 0xFFFF;
        if (buttonId < 0 || buttonId >= buttonHolders.length || buttonHolders[buttonId] == null) {
            return;
        }

        if (animatorId == ANIMATOR_ID_VISIBILITY || animatorId == ANIMATOR_ID_COUNTER_VISIBILITY) {
            checkButtonsPositionsAndVisibility();
        }
    }

    private void checkButtonsPositionsAndVisibility() {
        float totalHeight = 0;

        for (int buttonId = 0; buttonId < buttonHolders.length; buttonId++) {
            ButtonHolder holder = buttonHolders[buttonId];

            if (holder == null) {
                continue;
            }

            final float visibility = holder.visibilityAnimator.getFloatValue();
            final float counterVisibility = holder.counterVisibilityAnimator.getFloatValue();

            holder.button.setVisibility(visibility > 0 ? VISIBLE : GONE);
            holder.button.setAlpha(visibility);
            holder.button.setScaleX(lerp(0.7f, 1f, visibility));
            holder.button.setScaleY(lerp(0.7f, 1f, visibility));
            if (buttonId != BUTTON_ATTACH) {
                holder.button.setTranslationY(dp(80) * (1f - visibility) - totalHeight);
            }

            final int height = dp(44);
            final int gap = dp(10 + 10 * counterVisibility);

            totalHeight += (height + gap) * visibility;
        }
        // The shared background is recorded in this parent's display list, so native
        // child alpha/translation updates must also redraw it on every animation frame.
        invalidate();
    }



    private ButtonPendingState getOrCreatePendingState(final int buttonId) {
        if (pendingStates[buttonId] == null) {
            pendingStates[buttonId] = new ButtonPendingState();
        }
        return pendingStates[buttonId];
    }

    @Nullable
    private ButtonHolder getButtonHolder(final int buttonId) {
        if (buttonId < 0 || buttonId >= buttonHolders.length) {
            return null;
        }

        return buttonHolders[buttonId];
    }

    private ButtonHolder getOrCreateButtonHolder(final int buttonId) {
        if (buttonHolders[buttonId] == null) {

            final BoolAnimator visibilityAnimator = new BoolAnimator(
                (buttonId << 16) | ANIMATOR_ID_VISIBILITY, this,
                buttonId == BUTTON_ATTACH ? CubicBezierInterpolator.EASE_OUT_QUINT : AnimatorUtils.DECELERATE_INTERPOLATOR,
                buttonId == BUTTON_ATTACH ? 300 : 280
            );

            final BoolAnimator counterVisibilityAnimator = new BoolAnimator(
                (buttonId << 16) | ANIMATOR_ID_COUNTER_VISIBILITY, this,
                buttonId == BUTTON_ATTACH ? CubicBezierInterpolator.EASE_OUT_QUINT : AnimatorUtils.DECELERATE_INTERPOLATOR,
                buttonId == BUTTON_ATTACH ? 300 : 280);

            int size = 56, iconSize = 48;
            if (buttonId == BUTTON_ATTACH) {
                size = 50;
                iconSize = 32;
            }
            final ChatActivityBlurredRoundPageDownButton button = ChatActivityBlurredRoundPageDownButton.create(
                getContext(),
                size, iconSize,
                resourcesProvider,
                blurredBackgroundDrawableViewFactory,
                colorProvider,
                buttonIcons[buttonId]
            );

            button.setPivotX(dp(size / 2f));
            button.setPivotY(dp(size / 2f + 8));
            button.setVisibility(GONE);
            button.setContentDescription(buttonDescriptions[buttonId]);
            button.setOnClickListener(v -> {
                if (onClickListener != null) {
                    onClickListener.onClick(buttonId, v);
                }
            });
            button.setOnLongClickListener(v -> {
                if (onLongClickListener != null) {
                    return onLongClickListener.onLongClick(buttonId, v);
                }
                return false;
            });

            if (buttonId == BUTTON_SEARCH_UP) {
                button.reverseIconByY();
            }
            if (buttonId == BUTTON_PAGE_DOWN) {
                button.reverseCounter();
            }

            addView(button, LayoutHelper.createFrame(size, size + 8, gravity));
            button.setLiquidTouchEnabled(liquidTouchEnabled);
            if (buttonId == BUTTON_ATTACH || buttonId == BUTTON_PAGE_DOWN || buttonId == BUTTON_MENTION) {
                button.setMinimumLiquidPressDuration(100);
            }

            buttonHolders[buttonId] = new ButtonHolder(button, visibilityAnimator, counterVisibilityAnimator);

            final ButtonPendingState pending = pendingStates[buttonId];
            if (pending != null) {
                button.setCount(pending.count, false);
                visibilityAnimator.setValue(false, false);
                counterVisibilityAnimator.setValue(pending.count > 0, false);
                button.showLoading(pending.loading, false);
                button.setEnabled(pending.enabled, false);
            }

            checkButtonsPositionsAndVisibility();
        }

        return buttonHolders[buttonId];
    }

    @Override
    public boolean hasOverlappingRendering() {
        return false;
    }

    private static class ButtonHolder {
        public final ChatActivityBlurredRoundPageDownButton button;
        public final BoolAnimator visibilityAnimator;
        public final BoolAnimator counterVisibilityAnimator;

        private ButtonHolder(ChatActivityBlurredRoundPageDownButton button, BoolAnimator visibilityAnimator, BoolAnimator counterVisibilityAnimator) {
            this.button = button;
            this.visibilityAnimator = visibilityAnimator;
            this.counterVisibilityAnimator = counterVisibilityAnimator;
        }
    }

    private static class ButtonPendingState {
        public int count = 0;
        public boolean loading = false;
        public boolean enabled = true;
    }
}
