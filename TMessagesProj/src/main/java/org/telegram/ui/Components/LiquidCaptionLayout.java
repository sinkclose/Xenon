package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;
import android.widget.FrameLayout;

import zxc.iconic.xenon.NekoConfig;
import zxc.iconic.xenon.helpers.NonIslandHelper;

/** Keeps a media caption's glass, text and controls on the same liquid surface. */
public class LiquidCaptionLayout extends FrameLayout implements LiquidTouchDispatcher.Target {
    private final LiquidTouchEffect liquidTouch = new LiquidTouchEffect(this);
    private final LiquidPressAnimationSuppressor pressAnimations = new LiquidPressAnimationSuppressor();
    private boolean wasLiquidEnabled;

    public LiquidCaptionLayout(Context context) {
        super(context);
        liquidTouch.setDragStrength(1.65f);
        setClipChildren(false);
        setClipToPadding(false);
    }

    private boolean liquidTouchAllowed() {
        return NekoConfig.liquidChatElements && !NonIslandHelper.chatElements() && isEnabled();
    }

    @Override
    public void observeLiquidTouch(MotionEvent event) {
        boolean enabled = liquidTouchAllowed();
        pressAnimations.sync(this, enabled);
        liquidTouch.setBounds(0, 0, getWidth(), getHeight());
        liquidTouch.onTouchEvent(event, enabled);
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (!LiquidTouchDispatcher.isDispatchingControls()) observeLiquidTouch(event);
        boolean handled = super.dispatchTouchEvent(event);
        if (!LiquidTouchDispatcher.isDispatchingControls() && !handled) liquidTouch.reset();
        return handled;
    }

    @Override
    public void draw(Canvas canvas) {
        boolean enabled = liquidTouchAllowed();
        pressAnimations.sync(this, enabled);
        if (!enabled && wasLiquidEnabled) liquidTouch.reset();
        wasLiquidEnabled = enabled;
        int save = enabled ? liquidTouch.begin(canvas) : -1;
        super.draw(canvas);
        if (save != -1) canvas.restoreToCount(save);
    }

    @Override
    protected void onDetachedFromWindow() {
        liquidTouch.reset();
        pressAnimations.restore();
        super.onDetachedFromWindow();
    }
}
