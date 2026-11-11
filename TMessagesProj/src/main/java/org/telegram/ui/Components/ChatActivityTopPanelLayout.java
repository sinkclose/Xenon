package org.telegram.ui.Components;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;

import androidx.annotation.NonNull;

import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;

import zxc.iconic.xenon.helpers.NonIslandHelper;

import me.vkryl.android.animator.ListAnimator;

public class ChatActivityTopPanelLayout extends AnimatedLinearLayout implements LiquidTouchDispatcher.Target {
    public ChatActivityTopPanelLayout(@NonNull Context context) {
        super(context);

        setOrientation(LinearLayout.VERTICAL);
        updateColors();
    }

    private LiquidTouchEffect liquidTouch;
    private final LiquidPressAnimationSuppressor liquidPressAnimations = new LiquidPressAnimationSuppressor();

    public void setLiquidTouchEnabled(boolean enabled) {
        if (enabled == (liquidTouch != null)) return;
        if (liquidTouch != null) liquidTouch.reset();
        liquidTouch = enabled ? new LiquidTouchEffect(this) : null;
        if (!enabled) liquidPressAnimations.restore();
        invalidate();
    }

    private boolean liquidTouchAllowed() {
        return liquidTouch != null && !NonIslandHelper.chatHeader();
    }

    @Override
    protected void onDetachedFromWindow() {
        if (liquidTouch != null) liquidTouch.reset();
        liquidPressAnimations.restore();
        super.onDetachedFromWindow();
    }

    BlurredBackgroundDrawable backgroundDrawable;
    private boolean liquidGlassGroupDrawn;

    public BlurredBackgroundDrawable prepareLiquidGlassGroup() {
        checkBoundsAndClipping();
        return backgroundDrawable;
    }

    public LiquidTouchEffect getLiquidGlassEffect() {
        return liquidTouchAllowed() ? liquidTouch : null;
    }

    public void setLiquidGlassGroupDrawn(boolean drawn) {
        if (liquidGlassGroupDrawn == drawn) return;
        liquidGlassGroupDrawn = drawn;
        invalidate();
    }

    @Override
    public void invalidate() {
        super.invalidate();
        if (liquidGlassGroupDrawn && getParent() instanceof View) ((View) getParent()).invalidate();
    }

    public void setBlurredBackground(BlurredBackgroundDrawable background) {
        backgroundDrawable = background;
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        checkBoundsAndClipping();
    }

    @Override
    protected void onItemsChanged() {
        super.onItemsChanged();
        checkBoundsAndClipping();
        invalidate();
    }

    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        super.setPadding(left, top, right, bottom);
        checkBoundsAndClipping();
        invalidate();
    }

    @Override
    public void observeLiquidTouch(MotionEvent ev) {
        liquidPressAnimations.sync(this, liquidTouchAllowed());
        if (liquidTouch != null) liquidTouch.onTouchEvent(ev, liquidTouchAllowed());
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (!LiquidTouchDispatcher.isDispatchingControls()) observeLiquidTouch(ev);
        boolean handled = super.dispatchTouchEvent(ev)
                || ev.getAction() == MotionEvent.ACTION_DOWN && backgroundDrawable != null && backgroundDrawable.getBounds().contains((int) ev.getX(), (int) ev.getY());
        if (!LiquidTouchDispatcher.isDispatchingControls() && !handled && liquidTouch != null) liquidTouch.reset();
        return handled;
    }

    private final Path clipPath = new Path();
    private final RectF clipRectF = new RectF();
    private View capsuleView;

    public void setCapsuleView(View view) {
        capsuleView = view;
        checkBoundsAndClipping();
        invalidate();
    }

    private float cornerRadius(float width, float height) {
        if (NonIslandHelper.chatHeader()) return 0f;
        float maximum = Math.max(0f, Math.min(width, height) / 2f);
        float regular = Math.min(dp(18), maximum);
        for (int i = 0; i < getEntriesCount(); i++) {
            final ListAnimator.Entry<AnimatedLinearLayout.Holder> entry = getEntry(i);
            if (entry.item.view == capsuleView) {
                // Extra panels extend the surface, but must not enlarge the pinned capsule's corners.
                final float insets = Math.max(0f, height - getMetadata().getTotalHeight());
                final float capsuleHeight = capsuleView.getMeasuredHeight() + insets;
                final float capsuleRadius = Math.min(maximum, capsuleHeight / 2f);
                return regular + (capsuleRadius - regular) * entry.getVisibility();
            }
        }
        return regular;
    }

    private void checkBoundsAndClipping() {
        final float bgHeight = getMetadata().getTotalHeight();
        final float bgAlpha = NonIslandHelper.chatHeader() ? 1 : getMetadata().getTotalVisibility();

        clipRectF.set(getPaddingLeft(), getPaddingTop(), getMeasuredWidth() - getPaddingRight(), getPaddingTop() + bgHeight);
        if (liquidTouch != null) liquidTouch.setBounds(clipRectF.left, clipRectF.top, clipRectF.right, clipRectF.bottom);

        final float r = cornerRadius(clipRectF.width(), clipRectF.height());
        clipPath.rewind();
        clipPath.addRoundRect(clipRectF, r, r, Path.Direction.CW);

        if (backgroundDrawable != null) {
            backgroundDrawable.setAlpha((int) (bgAlpha * 255));
            backgroundDrawable.setBounds(getPaddingLeft() - dp(7), 0, getMeasuredWidth() - getPaddingRight() + dp(7), getPaddingTop() + getPaddingBottom() + (int) bgHeight);
            if (!NonIslandHelper.chatHeader()) {
                backgroundDrawable.setRadius(cornerRadius(backgroundDrawable.getBounds().width(), backgroundDrawable.getBounds().height()));
            }
        }
    }

    public void updateColors() {
        if (backgroundDrawable != null) {
            backgroundDrawable.updateColors();
        }

        final int color = Theme.getColor(Theme.key_windowBackgroundWhite);
        final int alpha = Color.alpha(color);

        invalidate();
    }

    private FragmentContextView callFragmentContextView;

    public void setCallFragmentContextView(FragmentContextView fragmentContextView) {
        callFragmentContextView = fragmentContextView;
        callFragmentContextView.getCapsuleBlobDrawable().setCallback(this);
    }

    @Override
    protected boolean verifyDrawable(@NonNull Drawable who) {
        return super.verifyDrawable(who) || callFragmentContextView != null && callFragmentContextView.getCapsuleBlobDrawable() == who;
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        if (getMetadata().getTotalVisibility() == 0) return;
        checkBoundsAndClipping();
        liquidPressAnimations.sync(this, liquidTouchAllowed());
        LiquidTouchEffect.updateBackground(backgroundDrawable, liquidTouchAllowed() ? liquidTouch : null, 0f, 0f);
        final int liquidSave = liquidTouchAllowed() ? liquidTouch.begin(canvas) : -1;

        if (backgroundDrawable != null && !liquidGlassGroupDrawn) {
            backgroundDrawable.draw(canvas);
            if (liquidTouchAllowed()) liquidTouch.drawHighlight(canvas, cornerRadius(clipRectF.width(), clipRectF.height()));
        }

        boolean callDrawn = false;
        if (callFragmentContextView != null) {
            final int style = callFragmentContextView.getCurrentStyle();
            if (style == FragmentContextView.STYLE_ACTIVE_GROUP_CALL || style == FragmentContextView.STYLE_CONNECTING_GROUP_CALL) {
                for (int a = 0, N = getEntriesCount(); a < N; a++) {
                    final ListAnimator.Entry<AnimatedLinearLayout.Holder> entry = getEntry(a);
                    final float top = getPaddingTop() + entry.getRectF().top;
                    final View view = entry.item.view;
                    final float alpha = entry.getVisibility();
                    if (alpha <= 0) {
                        continue;
                    }

                    if (callFragmentContextView == view || callFragmentContextView.getParent() == view) {
                        final CapsuleBlobDrawable capsuleBlobDrawable = callFragmentContextView.getCapsuleBlobDrawable();

                        final int p = capsuleBlobDrawable.getRequiredInset();
                        final int h = dp(36) + p * 2;
                        capsuleBlobDrawable.setBounds(getPaddingLeft() - p, -p, getMeasuredWidth() - getPaddingRight() + p, -p + h);
                        capsuleBlobDrawable.setAlpha((int) (255 * alpha));
                        canvas.save();
                        canvas.translate(0, top);
                        capsuleBlobDrawable.draw(canvas);
                        canvas.restore();

                        callDrawn = true;
                    }
                }
            }
        }

        canvas.save();
        canvas.clipPath(clipPath);
        for (int a = 0, N = getEntriesCount(); a < N; a++) {
            final ListAnimator.Entry<AnimatedLinearLayout.Holder> entry = getEntry(a);
            final float top = getPaddingTop() + entry.getRectF().top;
            final View view = entry.item.view;

            final float position = entry.getPosition();
            final float alpha = entry.getVisibility() * Math.min(1, position);

            if (alpha <= 0) {
                continue;
            }

            if (callDrawn && callFragmentContextView != null && (callFragmentContextView == view || callFragmentContextView.getParent() == view)) {
                continue;
            }

            final int wasAlpha = Theme.dividerPaint.getAlpha();
            Theme.dividerPaint.setAlpha((int) (wasAlpha * alpha));
            final float offsetL = getPaddingLeft() + dp(16) * (1f - alpha);
            final float offsetR = getPaddingRight() + dp(16) * (1f - alpha);
            if (!zxc.iconic.xenon.NekoConfig.materialChatElements) {
                canvas.drawLine(offsetL, top, getWidth() - offsetR, top, Theme.dividerPaint);
            }
            Theme.dividerPaint.setAlpha(wasAlpha);
        }

        super.dispatchDraw(canvas);
        if (NonIslandHelper.chatElements()) {
            Theme.dividerPaint.setAlpha(255);
            canvas.drawLine(getPaddingLeft(), getPaddingTop(), getWidth() - getPaddingRight(), getPaddingTop(), Theme.dividerPaint);
        }
        canvas.restore();
        if (liquidSave != -1) canvas.restoreToCount(liquidSave);
    }
}
