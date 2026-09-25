package zxc.iconic.xenon.helpers;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;

import androidx.core.graphics.ColorUtils;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.HeaderCell;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.SettingsActivity;

import zxc.iconic.xenon.NekoConfig;

public class M3SectionsHelper {

    private static final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path path = new Path();
    private static final RectF rect = new RectF();
    private static final float[] radii = new float[8];

    static {
        paint.setStyle(Paint.Style.FILL);
    }

    public static boolean isEnabled() {
        return NekoConfig.m3SectionsStyle;
    }

    public static int shadowHeightDp(UItem nextItem, int stockDp) {
        if (!isEnabled()) return stockDp;
        if (nextItem != null && isHeaderViewType(nextItem.viewType)) return 10;
        return 16;
    }

    public static boolean isHeaderViewType(int viewType) {
        return viewType == UniversalAdapter.VIEW_TYPE_HEADER
                || viewType == UniversalAdapter.VIEW_TYPE_BLACK_HEADER
                || viewType == UniversalAdapter.VIEW_TYPE_LARGE_HEADER
                || viewType == UniversalAdapter.VIEW_TYPE_ANIMATED_HEADER;
    }

    public static void markMerged(View view, boolean withPrev, boolean withNext) {
        if (!isEnabled()) return;
        view.setTag(org.telegram.messenger.R.id.merge_with_prev, withPrev ? Boolean.TRUE : null);
        view.setTag(org.telegram.messenger.R.id.merge_with_next, withNext ? Boolean.TRUE : null);
    }

    private static boolean isMergedWithPrev(View view) {
        return view.getTag(org.telegram.messenger.R.id.merge_with_prev) == Boolean.TRUE;
    }

    private static boolean isMergedWithNext(View view) {
        return view.getTag(org.telegram.messenger.R.id.merge_with_next) == Boolean.TRUE;
    }

    private static float getOuterR() {
        return AndroidUtilities.dp(20f);
    }

    private static float getInnerR() {
        return AndroidUtilities.dp(4f);
    }

    public static int getGap() {
        return AndroidUtilities.dp(1f);
    }

    private static float outerRForChild(View child) {
        if (child instanceof TextCheckCell && ((TextCheckCell) child).drawCheckRipple) {
            return child.getHeight() / 2f;
        }
        return getOuterR();
    }

    public static void drawSectionsBackgrounds(Canvas canvas, RecyclerListView listView) {
        RecyclerListView.ListSectionsDecoration deco = listView.sectionsItemDecoration;
        if (deco == null) return;
        Utilities.CallbackReturn<View, Boolean> isSection = deco.isSectionItem;
        int bgColor = Theme.getColor(Theme.key_windowBackgroundWhite, listView.resourcesProvider);
        for (int i = 0; i < listView.getChildCount(); i++) {
            View child = listView.getChildAt(i);
            if (child == null || child.getVisibility() != View.VISIBLE || child.getAlpha() <= 0f) continue;
            if (!isSection.run(child)) continue;
            if (child instanceof HeaderCell) continue;
            float[] tRbR = computeRadii(listView, child, i, isSection);
            float tR = tRbR[0];
            float bR = tRbR[1];
            rect.set(child.getLeft(), RecyclerListView.top(child), child.getRight(), RecyclerListView.bottom(child));
            setRadii(tR, bR);
            path.rewind();
            path.addRoundRect(rect, radii, Path.Direction.CW);
            paint.setColor(multAlpha(bgColor, child.getAlpha()));
            canvas.drawPath(path, paint);
        }
    }

    public static void clipChild(Canvas canvas, View child, RecyclerListView listView) {
        if (child == null) return;
        RecyclerListView.ListSectionsDecoration deco = listView.sectionsItemDecoration;
        if (deco == null) return;
        Utilities.CallbackReturn<View, Boolean> isSection = deco.isSectionItem;
        if (!isSection.run(child) || child instanceof HeaderCell) return;
        int index = listView.indexOfChild(child);
        float[] tRbR = computeRadii(listView, child, index, isSection);
        float tR = tRbR[0];
        float bR = tRbR[1];
        rect.set(child.getX(), RecyclerListView.top(child), child.getX() + child.getWidth(), RecyclerListView.bottom(child));
        setRadii(tR, bR);
        path.rewind();
        path.addRoundRect(rect, radii, Path.Direction.CW);
        canvas.clipPath(path);
    }

    private static float[] computeRadii(RecyclerListView listView, View child, int childIndex, Utilities.CallbackReturn<View, Boolean> isSection) {
        View prev = childIndex >= 0 ? visualSibling(listView, childIndex, false) : null;
        View next = childIndex >= 0 ? visualSibling(listView, childIndex, true) : null;
        boolean prevIsSection = prev != null && isSection.run(prev) && !(prev instanceof HeaderCell);
        boolean nextIsSection = next != null && isSection.run(next) && !(next instanceof HeaderCell);
        return m3Radii(child, prevIsSection, nextIsSection);
    }

    private static View visualSibling(RecyclerListView listView, int fromIndex, boolean forward) {
        int step = forward ? 1 : -1;
        for (int i = fromIndex + step; i >= 0 && i < listView.getChildCount(); i += step) {
            View v = listView.getChildAt(i);
            if (v != null && v.getVisibility() == View.VISIBLE && v.getAlpha() > 0.01f) return v;
        }
        return null;
    }

    private static float[] m3Radii(View child, boolean prevIsSection, boolean nextIsSection) {
        float outer = outerRForChild(child);
        float tR = isMergedWithPrev(child) ? 0f : (prevIsSection ? getInnerR() : outer);
        float bR = isMergedWithNext(child) ? 0f : (nextIsSection ? getInnerR() : outer);
        return new float[]{tR, bR};
    }

    public static Drawable makeClipBackground(RecyclerListView listView, View child) {
        if (child instanceof HeaderCell) return null;
        float[] tRbR = sectionRadiiFor(listView, child);
        if (tRbR == null) return null;
        float tR = tRbR[0];
        float bR = tRbR[1];
        int bgColor = Theme.getColor(Theme.key_windowBackgroundWhite, listView.resourcesProvider);
        int cw = child.getWidth();
        int ch = child.getHeight();
        float[] radiiArr = new float[]{tR, tR, tR, tR, bR, bR, bR, bR};
        return new Drawable() {
            private final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path clipPath = new Path();
            private final RectF tmp = new RectF();

            @Override
            public void draw(Canvas canvas) {
                canvas.save();
                tmp.set(0f, 0f, cw, ch);
                clipPath.rewind();
                clipPath.addRoundRect(tmp, radiiArr, Path.Direction.CW);
                canvas.clipPath(clipPath);
                p.setColor(ColorUtils.setAlphaComponent(bgColor, p.getAlpha()));
                canvas.drawRect(tmp, p);
                canvas.restore();
            }

            @Override
            public void setAlpha(int alpha) {
                p.setAlpha(alpha);
            }

            @Override
            public void setColorFilter(ColorFilter cf) {}

            @Override
            public int getOpacity() {
                return PixelFormat.TRANSPARENT;
            }
        };
    }

    public static void applyScrimClip(Canvas canvas, View child) {
        RecyclerListView listView = child.getParent() instanceof RecyclerListView ? (RecyclerListView) child.getParent() : null;
        if (listView == null || !listView.hasSections()) return;
        RecyclerListView.ListSectionsDecoration deco = listView.sectionsItemDecoration;
        if (deco == null) return;
        Utilities.CallbackReturn<View, Boolean> isSection = deco.isSectionItem;
        if (!isSection.run(child) || child instanceof HeaderCell) return;
        rect.set(0f, 0f, child.getWidth(), child.getHeight());
        path.rewind();
        if (isEnabled()) {
            float[] tRbR = sectionRadiiFor(listView, child);
            if (tRbR == null) return;
            setRadii(tRbR[0], tRbR[1]);
            path.addRoundRect(rect, radii, Path.Direction.CW);
        } else {
            int position = listView.getChildAdapterPosition(child);
            View prevView = position != RecyclerView.NO_POSITION ? listView.findViewByPosition(position - 1) : null;
            View nextView = position != RecyclerView.NO_POSITION ? listView.findViewByPosition(position + 1) : null;
            boolean prev = prevView != null && isSection.run(prevView);
            boolean next = nextView != null && isSection.run(nextView);
            if (prev && next) return;
            if (!prev && !next) {
                path.addRoundRect(rect, listView.sectionRadius, listView.sectionRadius, Path.Direction.CW);
            } else if (!prev) {
                path.addRoundRect(rect, listView.sectionRadiusTop, Path.Direction.CW);
            } else {
                path.addRoundRect(rect, listView.sectionRadiusBottom, Path.Direction.CW);
            }
        }
        canvas.clipPath(path);
    }

    public static void augmentItemOffsets(Rect outRect, View view, int position) {
        if (position > 0 && !isMergedWithPrev(view)) {
            outRect.top += getGap();
        }
    }

    private static float[] sectionRadiiFor(RecyclerListView listView, View child) {
        RecyclerListView.ListSectionsDecoration deco = listView.sectionsItemDecoration;
        if (deco == null) return null;
        Utilities.CallbackReturn<View, Boolean> isSection = deco.isSectionItem;
        if (!isSection.run(child)) return null;
        int index = listView.indexOfChild(child);
        if (index < 0) return null;
        return computeRadii(listView, child, index, isSection);
    }

    private static void setRadii(float top, float bottom) {
        radii[0] = top; radii[1] = top; radii[2] = top; radii[3] = top;
        radii[4] = bottom; radii[5] = bottom; radii[6] = bottom; radii[7] = bottom;
    }

    private static int multAlpha(int color, float alpha) {
        int a = (int)(((color >>> 24) & 0xFF) * alpha);
        a = Math.max(0, Math.min(255, a));
        return (a << 24) | (color & 0x00FFFFFF);
    }
}
