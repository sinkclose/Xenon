package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import java.util.IdentityHashMap;
import java.util.Map;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

/** Native menu styling and window animation, with the same pull gesture for every style. */
public final class PopupMenuSurface extends FrameLayout {
    private final View content;
    private final ViewGroup.LayoutParams previousLayoutParams;
    private final IosPopupInteraction interaction;
    private final IosPopupGestureBridge bridge;
    private final int[] location = new int[2];
    private float scaleX = 1, scaleY = 1, pivotX, pivotY;
    private boolean released;
    private final Runnable itemsShown;
    private final IdentityHashMap<View, Drawable> legacyBackgrounds = new IdentityHashMap<>();

    private static boolean containsMenuRows(View view) {
        if (view instanceof org.telegram.ui.ActionBar.ActionBarPopupWindow.ActionBarPopupWindowLayout
                || view instanceof org.telegram.ui.ActionBar.ActionBarMenuSubItem) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                if (containsMenuRows(group.getChildAt(i))) return true;
            }
        }
        return false;
    }

    private void stripLegacyContainers(View view) {
        // Custom pickers have their own surface; only remove known menu wrappers.
        if (!containsMenuRows(view)) return;
        if (view instanceof org.telegram.ui.ActionBar.ActionBarPopupWindow.ActionBarPopupWindowLayout
                || view instanceof org.telegram.ui.ActionBar.ActionBarMenuSubItem || view.isClickable()) return;
        if (view instanceof ViewGroup) {
            if (view.getBackground() != null) {
                legacyBackgrounds.put(view, view.getBackground());
                view.setBackground(null);
            }
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) stripLegacyContainers(group.getChildAt(i));
        }
    }

    private static void notifyItemsShown(View view) {
        if (view.getVisibility() != VISIBLE) return;
        if (view instanceof org.telegram.ui.ActionBar.ActionBarMenuSubItem) {
            ((org.telegram.ui.ActionBar.ActionBarMenuSubItem) view).onItemShown();
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) notifyItemsShown(group.getChildAt(i));
        }
    }

    public PopupMenuSurface(View content, View source) {
        super(content.getContext());
        this.content = content;
        itemsShown = () -> notifyItemsShown(content);
        previousLayoutParams = content.getLayoutParams();
        if (zxc.iconic.xenon.NekoConfig.material3Popups) stripLegacyContainers(content);
        interaction = new IosPopupInteraction(content, this::postInvalidateOnAnimation, false);
        setClipChildren(false);
        setClipToPadding(false);
        addView(content, new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        if (IosPopupMorph.canAnimate(source)) {
            IosPopupOpener.install(source);
            bridge = new IosPopupGestureBridge(source, this::forwardTouch);
        } else bridge = null;
    }

    private void updateTransform() {
        scaleY = 1 + interaction.pull() / Math.max(1, content.getHeight());
        scaleX = 1 / (1 + (scaleY - 1) * .45f);
        pivotX = content.getPaddingLeft() + interaction.pivotX()
                * (content.getWidth() - content.getPaddingLeft() - content.getPaddingRight());
        pivotY = content.getPaddingTop() + interaction.pivotY()
                * (content.getHeight() - content.getPaddingTop() - content.getPaddingBottom());
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        postDelayed(itemsShown, 200);
    }

    @Override public void draw(Canvas canvas) {
        updateTransform();
        int save = canvas.save();
        canvas.scale(scaleX, scaleY, pivotX, pivotY);
        super.draw(canvas);
        canvas.restoreToCount(save);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        updateTransform();
        MotionEvent local = MotionEvent.obtain(event);
        local.setLocation(pivotX + (event.getX() - pivotX) / scaleX,
                pivotY + (event.getY() - pivotY) / scaleY);
        try { return super.dispatchTouchEvent(local); }
        finally { local.recycle(); }
    }

    public boolean forwardTouch(MotionEvent event) {
        if (released) return false;
        content.getLocationOnScreen(location);
        updateTransform();
        float x = pivotX + (event.getRawX() - location[0] - pivotX) / scaleX;
        float y = pivotY + (event.getRawY() - location[1] - pivotY) / scaleY;
        return interaction.touch(event, x, y, true);
    }

    public void release() {
        if (released) return;
        released = true;
        removeCallbacks(itemsShown);
        interaction.release();
        if (bridge != null) bridge.release();
        for (Map.Entry<View, Drawable> entry : legacyBackgrounds.entrySet()) entry.getKey().setBackground(entry.getValue());
        legacyBackgrounds.clear();
        removeView(content);
        content.setLayoutParams(previousLayoutParams != null ? previousLayoutParams
                : new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
    }
}
