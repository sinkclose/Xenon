package org.telegram.ui.Components;

import android.graphics.Color;
import android.view.View;

import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.OrientationHelper;
import androidx.recyclerview.widget.RecyclerView;

/** Stable scroll anchors and theme contrast for the fixed header separator. */
public final class HeaderSurfaceDivider {
    private HeaderSurfaceDivider() {}

    public static int color(int background, int divider) {
        background |= 0xff000000;
        int candidate = ColorUtils.compositeColors(divider, background);
        if (ColorUtils.calculateContrast(candidate, background) >= 1.5) return candidate;
        int foreground = ColorUtils.calculateLuminance(background) > .179 ? Color.BLACK : Color.WHITE;
        for (float amount = .28f; amount <= .64f; amount += .04f) {
            candidate = ColorUtils.blendARGB(background, foreground, amount);
            if (ColorUtils.calculateContrast(candidate, background) >= 1.5) return candidate;
        }
        return foreground;
    }

    /** NO_POSITION chooses the leading adapter edge, including reversed chat lists. */
    public static boolean isScrolled(View scrolling, int firstPosition, boolean previous) {
        if (scrolling == null) return previous;
        if (scrolling instanceof RecyclerView) {
            RecyclerView list = (RecyclerView) scrolling;
            RecyclerView.Adapter<?> adapter = list.getAdapter();
            if (adapter != null && adapter.getItemCount() == 0) return false;
            if (adapter == null || list.isComputingLayout() || list.isLayoutRequested()
                    || list.getHeight() == 0) return previous;
            RecyclerView.LayoutManager manager = list.getLayoutManager();
            if (manager == null || list.getChildCount() == 0) return previous;
            if (firstPosition == RecyclerView.NO_POSITION) {
                firstPosition = manager instanceof LinearLayoutManager && ((LinearLayoutManager) manager).getReverseLayout()
                        ? adapter.getItemCount() - 1 : 0;
            }
            if (firstPosition >= adapter.getItemCount()) return false;
            View first = manager.findViewByPosition(firstPosition);
            if (first == null) return true;
            if (first.getHeight() == 0) return previous;
            int top = manager instanceof LinearLayoutManager
                    ? OrientationHelper.createVerticalHelper(manager).getDecoratedStart(first) : manager.getDecoratedTop(first);
            return top < list.getPaddingTop();
        }
        if (scrolling.isLayoutRequested() || scrolling.getHeight() == 0) return previous;
        return scrolling.getScrollY() > 0;
    }
}
