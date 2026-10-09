package org.telegram.ui.Components.chat.buttons;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import androidx.annotation.DrawableRes;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.CounterView;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LiquidTouchEffect;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;


@SuppressLint("ViewConstructor")
public class ChatActivityBlurredRoundPageDownButton extends FrameLayout {
    private final Theme.ResourcesProvider resourcesProvider;

    private ChatActivityBlurredRoundButton buttonView;
    private CounterView counterView;
    private LiquidTouchEffect liquidTouch;

    public void setLiquidTouchEnabled(boolean enabled) {
        if (enabled == (liquidTouch != null)) return;
        if (liquidTouch != null) liquidTouch.reset();
        liquidTouch = enabled ? new LiquidTouchEffect(this) : null;
        if (liquidTouch != null && getParent() instanceof View) liquidTouch.setContentView((View) getParent());
        // The spring replaces the old press scale; visibility scale remains on the View.
        if (enabled) {
            setStateListAnimator(null);
        } else {
            ScaleStateListAnimator.apply(this, .13f, 2f);
        }
    }

    private boolean liquidTouchAllowed() {
        return liquidTouch != null && isEnabled()
                && !zxc.iconic.xenon.helpers.NonIslandHelper.chatElements();
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (liquidTouch != null) {
            liquidTouch.setBounds(0, getHeight() - getWidth(), getWidth(), getHeight());
            liquidTouch.onTouchEvent(event, liquidTouchAllowed());
        }
        boolean handled = super.dispatchTouchEvent(event);
        if (!handled && event.getActionMasked() == MotionEvent.ACTION_DOWN && liquidTouch != null) liquidTouch.reset();
        return handled;
    }

    public int beginLiquidDraw(Canvas canvas) {
        if (buttonView != null) {
            buttonView.updateLiquidBackground(liquidTouchAllowed() ? liquidTouch : null, buttonView.getX(), buttonView.getY());
        }
        return liquidTouchAllowed() ? liquidTouch.beginInParent(canvas, getX(), getY()) : -1;
    }

    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);
        if (liquidTouchAllowed()) liquidTouch.drawHighlight(canvas, dp(22), dp(6));
    }

    @Override
    protected void onDetachedFromWindow() {
        if (liquidTouch != null) liquidTouch.reset();
        super.onDetachedFromWindow();
    }

    public ChatActivityBlurredRoundPageDownButton(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
    }

    public void addButtonView(ChatActivityBlurredRoundButton button, int size) {
        this.buttonView = button;
        addView(button, LayoutHelper.createFrame(size, size, Gravity.BOTTOM));
        button.setIconPadding(dp(2));
    }


    private boolean reversedCounter;

    public void reverseCounter() {
        reversedCounter = true;
        if (counterView != null) {
            counterView.setReverse(true);
        }
    }

    public void setCount(int count, boolean animated) {
        if (counterView == null) {
            counterView = new CounterView(getContext(), resourcesProvider);
            counterView.setReverse(reversedCounter);
            addView(counterView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 28, Gravity.TOP));
        }

        counterView.setCount(count, animated);
    }





    public void showLoading(boolean loading, boolean animated) {
        buttonView.showLoading(loading, animated);
    }

    @Override
    public void setEnabled(boolean enabled) {
        setEnabled(enabled, false);
    }

    public void setEnabled(boolean enabled, boolean animated) {
        super.setEnabled(enabled);
        buttonView.setEnabled(enabled, animated);
    }

    public void reverseIconByY() {
        buttonView.reverseIconByY();
    }

    public void updateColors() {
        if (buttonView != null) {
            buttonView.updateColors();
            invalidate();
        }
    }

    public static ChatActivityBlurredRoundPageDownButton create(
        Context context,
        int size, int iconSize,
        Theme.ResourcesProvider resourcesProvider,
        BlurredBackgroundDrawableViewFactory factory,
        BlurredBackgroundColorProvider colorProvider,
        @DrawableRes int res
    ) {
        ChatActivityBlurredRoundPageDownButton button = new ChatActivityBlurredRoundPageDownButton(context, resourcesProvider);
        button.addButtonView(ChatActivityBlurredRoundButton.create(context, factory, colorProvider, resourcesProvider, res, iconSize), size);
        ScaleStateListAnimator.apply(button, .13f, 2f);

        return button;
    }
}
