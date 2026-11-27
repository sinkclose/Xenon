package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ScrollView;

import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.OrientationHelper;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.SimpleActionBar;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.chat.ViewPositionWatcher;
import org.telegram.ui.Components.chat.layouts.ChatActivityFadeView;
import org.telegram.utils.glass.fade.GlassFadeBlurredDrawable;
import org.telegram.utils.glass.fade.GlassFadeDirection;

import java.util.ArrayList;

import zxc.iconic.xenon.NekoConfig;

/** Xenon's collapsing/material top fade for flat upstream simple glass screens. */
public final class SimpleActivityHeadersController {
    private final BaseFragment fragment;
    private final View content;
    private final View scrolling;
    private final SimpleActionBar bar;
    private final FrameLayout host;
    private final ContentCapture capture;
    private final MaterialActivityTitleView title;
    private final ArrayList<RemovedFade> removedFades = new ArrayList<>();
    private final RectF captureOffset = new RectF();
    private final View scrollChild;
    private final int childMinimumHeight;
    private final int left, top, right, bottom;
    private final int initialMarginTop;
    private final boolean clipToPadding;

    private View surface;
    private ChatActivityFadeView plain;
    private ProgressiveFadeBlurController blur;
    private org.telegram.utils.glass.source.GlassSourceRenderNode buttonSource;
    private FadeHeaderButtonGlass buttonGlass;
    private boolean paused, detached, disabled;
    private int surfaceBottom = -1, surfaceColor;
    private boolean settlePosted, atContentEnd;
    private boolean collapseGestureStarted;
    private float lastDistance = -1;
    private final Runnable settle = this::finishCollapse;

    private final RecyclerView.OnScrollListener gestureListener = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrollStateChanged(RecyclerView recyclerView, int state) {
            if (state == RecyclerView.SCROLL_STATE_DRAGGING) collapseGestureStarted = true;
        }
    };

    private final ViewTreeObserver.OnPreDrawListener listener = this::updateSafely;

    public static SimpleActivityHeadersController attach(BaseFragment fragment) {
        if (!NekoConfig.materialActivityHeaders && !NekoConfig.fadeViewOtherActivities()) return null;
        if (fragment instanceof org.telegram.ui.ProfileActivity
                || fragment instanceof org.telegram.ui.ChatActivity
                || fragment instanceof org.telegram.ui.DialogsActivity
                || fragment instanceof zxc.iconic.xenon.settings.BaseNekoSettingsActivity) return null;
        View content = fragment.getFragmentView();
        SimpleActionBar bar = fragment.getSimpleActionBar();
        if (content == null || bar == null || !(bar.getParent() instanceof FrameLayout)
                || android.text.TextUtils.isEmpty(bar.getTitle())) return null;
        FrameLayout host = (FrameLayout) bar.getParent();
        View scrolling = findScrolling(content);
        // Nested coordinators (Stars/TON-style screens) own their overlay fades and scroll math.
        // The generic controller only takes the flat simple-list layout upstream creates itself.
        if (scrolling == null || scrolling.getParent() != host || !isAncestor(host, scrolling) || !hasUpstreamFade(host)) return null;
        SimpleActivityHeadersController controller = new SimpleActivityHeadersController(fragment, content, scrolling, bar, host);
        try {
            controller.install();
            return controller;
        } catch (Throwable t) {
            FileLog.e(t);
            controller.emergencyDetach();
            return null;
        }
    }

    private static View findScrolling(View view) {
        if (view.getVisibility() != View.VISIBLE) return null;
        if (view instanceof ScrollView || view instanceof androidx.core.widget.NestedScrollView) return view;
        if (view instanceof RecyclerView) {
            RecyclerView.LayoutManager manager = ((RecyclerView) view).getLayoutManager();
            if (manager != null && manager.canScrollVertically()) return view;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View candidate = findScrolling(group.getChildAt(i));
                if (candidate != null) return candidate;
            }
        }
        return null;
    }

    private static boolean isAncestor(View ancestor, View view) {
        ViewParent parent = view.getParent();
        while (parent != null) {
            if (parent == ancestor) return true;
            parent = parent.getParent();
        }
        return false;
    }

    private SimpleActivityHeadersController(BaseFragment fragment, View content, View scrolling, SimpleActionBar bar, FrameLayout host) {
        this.fragment = fragment;
        this.content = content;
        this.scrolling = scrolling;
        this.bar = bar;
        this.host = host;
        capture = new ContentCapture(host.getContext());
        title = new MaterialActivityTitleView(host.getContext());
        title.setOnClickListener(v -> bar.performClick());
        scrollChild = scrolling instanceof ScrollView || scrolling instanceof androidx.core.widget.NestedScrollView
                ? ((ViewGroup) scrolling).getChildAt(0) : null;
        childMinimumHeight = scrollChild == null ? 0 : scrollChild.getMinimumHeight();
        left = scrolling.getPaddingLeft();
        top = logicalPaddingTop();
        right = scrolling.getPaddingRight();
        bottom = scrolling.getPaddingBottom();
        initialMarginTop = scrolling.getLayoutParams() instanceof ViewGroup.MarginLayoutParams
                ? ((ViewGroup.MarginLayoutParams) scrolling.getLayoutParams()).topMargin : 0;
        clipToPadding = scrolling instanceof ViewGroup && ((ViewGroup) scrolling).getClipToPadding();
    }

    private int logicalPaddingTop() {
        return scrolling instanceof BlurredRecyclerView ? ((BlurredRecyclerView) scrolling).topPadding : scrolling.getPaddingTop();
    }

    private void install() {
        collectUpstreamFades();
        final int insertAt = insertionIndex();

        surface = new View(host.getContext());
        surface.setVisibility(View.GONE);
        host.addView(surface, insertAt, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 0, Gravity.TOP));

        plain = new ChatActivityFadeView(host.getContext());
        plain.setupColorKey(Theme.key_windowBackgroundGray);
        plain.setOpaqueFade(true);
        plain.setFadeHeightTop(AndroidUtilities.dp(32), false);
        plain.setVisibility(View.GONE);
        host.addView(plain, insertAt + 1, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            blur = new ProgressiveFadeBlurController(host, scrolling, insertAt + 2,
                    () -> Theme.getColor(Theme.key_windowBackgroundGray, fragment.getResourceProvider()));
            blur.setUniformBlurAllowed(true);
            blur.setOpaqueFade(true);
            blur.setFadeHeightTop(AndroidUtilities.dp(32), false);
            blur.setContentCapture(host, (canvas, bounds) -> captureScrolling(canvas));
        }

        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                AndroidUtilities.dp(152), Gravity.TOP);
        titleParams.topMargin = statusInset();
        final int titleIndex = host.indexOfChild(bar) >= 0 ? host.indexOfChild(bar) : host.getChildCount();
        host.addView(title, titleIndex, titleParams);
        if (scrolling instanceof ViewGroup) ((ViewGroup) scrolling).setClipToPadding(false);
        if (scrolling instanceof RecyclerListView) ((RecyclerListView) scrolling).setCaptureSectionsDecoratorAllowed(true);
        if (scrolling instanceof RecyclerView) ((RecyclerView) scrolling).addOnScrollListener(gestureListener);

        host.getViewTreeObserver().addOnPreDrawListener(listener);
        updateSafely();
    }

    private int statusInset() {
        return bar.getOccupyStatusBar() ? AndroidUtilities.statusBarHeight : 0;
    }

    private static boolean isUpstreamTopFade(View view) {
        Drawable background = view.getBackground();
        return background instanceof GlassFadeBlurredDrawable
                && ((GlassFadeBlurredDrawable) background).getDirection() == GlassFadeDirection.TOP;
    }

    private static boolean hasUpstreamFade(FrameLayout host) {
        for (int i = 0; i < host.getChildCount(); i++) {
            if (isUpstreamTopFade(host.getChildAt(i))) return true;
        }
        return false;
    }

    private void collectUpstreamFades() {
        for (int i = 0; i < host.getChildCount(); i++) {
            View child = host.getChildAt(i);
            if (isUpstreamTopFade(child)) {
                removedFades.add(new RemovedFade(child, child.getBackground()));
            }
        }
    }

    private int insertionIndex() {
        int index = host.indexOfChild(bar);
        for (int i = 0; i < removedFades.size(); i++) {
            View view = removedFades.get(i).view;
            if (view != bar) {
                int fadeIndex = host.indexOfChild(view);
                if (fadeIndex >= 0) index = Math.min(index, fadeIndex);
            }
        }
        return Math.max(0, index);
    }

    private void setUpstreamFadesVisible(boolean visible) {
        for (int i = 0; i < removedFades.size(); i++) {
            RemovedFade fade = removedFades.get(i);
            if (visible) {
                if (fade.view.getBackground() == null) fade.view.setBackground(fade.background);
            } else if (fade.view.getBackground() == fade.background) {
                fade.view.setBackground(null);
            }
        }
    }

    private float upstreamFadeAlpha() {
        float alpha = 1f;
        for (int i = 0; i < removedFades.size(); i++) {
            View view = removedFades.get(i).view;
            if (view != bar) {
                alpha = Math.min(alpha, view.getVisibility() == View.VISIBLE ? view.getAlpha() : 0f);
            }
        }
        return alpha;
    }

    private void captureScrolling(Canvas canvas) {
        if (!(scrolling.getParent() instanceof View)) return;
        View parent = (View) scrolling.getParent();
        if (!ViewPositionWatcher.computeRectInParent(parent, host, captureOffset)) return;
        int save = canvas.save();
        try {
            canvas.translate(captureOffset.left, captureOffset.top);
            capture.capture(canvas, scrolling);
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private float distance() {
        if (scrolling instanceof ScrollView || scrolling instanceof androidx.core.widget.NestedScrollView) return scrolling.getScrollY();
        if (!(scrolling instanceof RecyclerView)) return 0;
        RecyclerView recycler = (RecyclerView) scrolling;
        RecyclerView.LayoutManager manager = recycler.getLayoutManager();
        View first = manager == null ? null : manager.findViewByPosition(0);
        if (first != null) return Math.max(0, recycler.getPaddingTop() - first.getTop());
        return recycler.canScrollVertically(-1) ? AndroidUtilities.dp(88) : 0;
    }

    private void finishCollapse() {
        settlePosted = false;
        if (paused || detached || disabled || title.getParent() == null || !atContentEnd || !NekoConfig.materialActivityHeaders
                || bar.isSearchFieldVisible() || bar.isActionModeShowed()) return;
        int remaining = AndroidUtilities.dp(88) - Math.round(distance());
        if (remaining <= 0 || remaining >= AndroidUtilities.dp(88)) return;
        if (scrolling instanceof RecyclerView) {
            RecyclerView recycler = (RecyclerView) scrolling;
            if (!collapseGestureStarted) return;
            if (recycler.getScrollState() == RecyclerView.SCROLL_STATE_IDLE) {
                collapseGestureStarted = false;
                recycler.smoothScrollBy(0, remaining);
            }
        } else if (scrolling instanceof ScrollView) ((ScrollView) scrolling).smoothScrollBy(0, remaining);
        else if (scrolling instanceof androidx.core.widget.NestedScrollView)
            ((androidx.core.widget.NestedScrollView) scrolling).smoothScrollBy(0, remaining);
    }

    private static int collapseSpace(int range, float distance, int remaining) {
        return Math.max(0, range - Math.round(distance) - remaining);
    }

    private int collapseBottomPadding(int range, int paddingTop) {
        atContentEnd = false;
        if (range == 0 || scrolling.getHeight() == 0) return bottom;
        if (scrollChild != null) {
            // fillViewport must not shrink the child as the extra tail is added.
            int minimum = Math.max(childMinimumHeight, scrolling.getHeight() - paddingTop - bottom);
            if (scrollChild.getMinimumHeight() != minimum) scrollChild.setMinimumHeight(minimum);
            int remaining = Math.max(scrollChild.getHeight(), minimum) + paddingTop + bottom
                    - scrolling.getHeight() - scrolling.getScrollY();
            atContentEnd = remaining <= 1;
            return bottom + collapseSpace(range, distance(), remaining);
        }
        if (scrolling instanceof RecyclerView) {
            RecyclerView recycler = (RecyclerView) scrolling;
            RecyclerView.Adapter adapter = recycler.getAdapter();
            RecyclerView.LayoutManager manager = recycler.getLayoutManager();
            View last = adapter == null || manager == null ? null : manager.findViewByPosition(adapter.getItemCount() - 1);
            if (last != null) {
                int remaining = manager.getDecoratedBottom(last) + bottom - recycler.getHeight();
                atContentEnd = remaining <= 1;
                return bottom + collapseSpace(range, distance(), remaining);
            }
        }
        return bottom;
    }

    private boolean applyScrollPadding(int padding, int paddingBottom) {
        if (logicalPaddingTop() == padding && scrolling.getPaddingBottom() == paddingBottom) return false;
        LinearLayoutManager manager = null;
        int position = RecyclerView.NO_POSITION;
        int offset = 0;
        if (scrolling instanceof RecyclerView) {
            RecyclerView.LayoutManager layout = ((RecyclerView) scrolling).getLayoutManager();
            if (layout instanceof LinearLayoutManager) {
                manager = (LinearLayoutManager) layout;
                position = manager.findFirstVisibleItemPosition();
                View anchor = manager.findViewByPosition(position);
                if (anchor == null) position = RecyclerView.NO_POSITION;
                else {
                    OrientationHelper helper = OrientationHelper.createVerticalHelper(manager);
                    offset = manager.getReverseLayout()
                            ? helper.getEndAfterPadding() - helper.getDecoratedEnd(anchor)
                            : helper.getDecoratedStart(anchor) - helper.getStartAfterPadding();
                }
            }
        }
        scrolling.setPadding(left, padding, right, paddingBottom);
        if (manager != null && position != RecyclerView.NO_POSITION) {
            manager.scrollToPositionWithOffset(position, offset);
        }
        return true;
    }

    private void restoreScrollState() {
        scrolling.setPadding(left, top, right, bottom);
        if (scrollChild != null) scrollChild.setMinimumHeight(childMinimumHeight);
        if (scrolling instanceof ViewGroup) ((ViewGroup) scrolling).setClipToPadding(clipToPadding);
    }

    private boolean updateSafely() {
        if (paused || detached || disabled) return true;
        try {
            update();
        } catch (Throwable t) {
            FileLog.e(t);
            failSafe();
        }
        return true;
    }

    private void update() {
        final boolean material = NekoConfig.materialActivityHeaders;
        final boolean normal = !bar.isSearchFieldVisible() && !bar.isActionModeShowed();
        final boolean fade = NekoConfig.fadeViewOtherActivities() && normal;
        if (!material && !fade) {
            restoreScrollState();
            setUpstreamFadesVisible(true);
            bar.setMaterialActivityHeader(false);
            bar.setExternalHeaderFade(false);
            bar.setPlainBackCircle(false);
            bar.setFadeButtonGlass(null);
            bar.setTranslationY(0);
            bar.setExternalHeaderTitleVisible(true);
            title.setVisibility(View.GONE);
            surface.setVisibility(View.GONE);
            plain.setVisibility(View.GONE);
            if (blur != null) {
                blur.setFadeViewVisibility(View.GONE);
                blur.stopContinuousUpdates();
            }
            return;
        }
        setUpstreamFadesVisible(false);

        final int compact = material ? AndroidUtilities.dp(64) : ActionBar.getCurrentActionBarHeight();
        final int range = material && normal ? AndroidUtilities.dp(88) : 0;
        int padding = top;
        if (material) {
            final int targetVisualTop = statusInset() + compact + range;
            final int initialVisualTop = initialMarginTop + top;
            padding = top + targetVisualTop - initialVisualTop;
        }
        final int paddingBottom = collapseBottomPadding(range, padding);
        if (applyScrollPadding(padding, paddingBottom)) {
            // New padding and old child positions describe different layouts.
            host.removeCallbacks(settle);
            settlePosted = false;
            collapseGestureStarted = false;
            lastDistance = -1;
            return;
        }

        FrameLayout.LayoutParams titleParams = (FrameLayout.LayoutParams) title.getLayoutParams();
        if (titleParams.topMargin != statusInset()) {
            titleParams.topMargin = statusInset();
            title.setLayoutParams(titleParams);
        }
        final float progress = range == 0 ? 1f : Math.max(0f, Math.min(1f, distance() / range));
        final int headerBottom = statusInset() + compact + Math.round(range * (1f - progress));
        final int color = Theme.getColor(Theme.key_windowBackgroundGray, fragment.getResourceProvider());
        if (surfaceBottom != headerBottom || surfaceColor != color) {
            surfaceBottom = headerBottom;
            surfaceColor = color;
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) surface.getLayoutParams();
            params.height = headerBottom;
            surface.setLayoutParams(params);
            surface.setBackgroundColor(color);
        }
        final float fadeAlpha = upstreamFadeAlpha();
        surface.setAlpha(fadeAlpha);
        surface.setVisibility(fade ? View.GONE : View.VISIBLE);

        bar.setMaterialActivityHeader(material);
        bar.setExternalHeaderFade(fade);
        bar.setPlainBackCircle(material || fade);
        bar.setTranslationY(material ? AndroidUtilities.dp(4) : 0);
        bar.setExternalHeaderTitleVisible(!material || !normal);
        title.setVisibility(material && normal ? View.VISIBLE : View.GONE);
        if (material && normal) {
            final int actionsWidth = bar.getMenu() == null ? 0 : bar.getMenu().getMeasuredWidth();
            final int leadingInset = bar.getBackButton() != null && bar.getBackButton().getVisibility() == View.VISIBLE
                    ? AndroidUtilities.dp(80) : AndroidUtilities.dp(24);
            title.update(bar.getTitle(), progress, NekoConfig.centerTitle, true, color,
                    Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, fragment.getResourceProvider()),
                    actionsWidth, leadingInset);
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && fade && !material) {
            if (buttonSource == null) {
                buttonSource = new org.telegram.utils.glass.source.GlassSourceRenderNode(null);
                buttonGlass = new FadeHeaderButtonGlass(host, buttonSource, fragment.getResourceProvider(),
                        org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl.simpleActionBar(fragment.getResourceProvider()));
            }
            if (org.telegram.messenger.LiteMode.isEnabled(org.telegram.messenger.LiteMode.FLAG_LIQUID_GLASS)) {
                buttonSource.setGlassBlur(AndroidUtilities.dp(8));
            } else {
                buttonSource.setPlainBlur(AndroidUtilities.dpf2(NekoConfig.blurStrength * 4f / 3f));
            }
            if (host.getWidth() > 0 && host.getHeight() > 0) {
                Canvas backdrop = buttonSource.beginRecording(host.getWidth(), host.getHeight());
                try {
                    backdrop.drawColor(color);
                    captureScrolling(backdrop);
                } finally {
                    buttonSource.endRecording();
                }
            }
            bar.setFadeButtonGlass(buttonGlass);
        } else {
            bar.setFadeButtonGlass(null);
        }

        final int edge = headerBottom + Math.round(AndroidUtilities.dp(12) * Math.min(1f, distance() / AndroidUtilities.dp(48)));
        final boolean useBlur = fade && blur != null && blur.isEnabled();
        plain.setAlpha(fadeAlpha);
        plain.setVisibility(fade && !useBlur ? View.VISIBLE : View.GONE);
        plain.setIos27Dimming(NekoConfig.ios27DimmingEnabled(), headerBottom, color);
        plain.setIos27SeparatorSource(scrolling);
        plain.setFadeZoneTop(edge);
        plain.setFadeTopAlpha(NekoConfig.blurredFadeDimming ? NekoConfig.blurredFadeDimStrength * 255 / 100 : 0);
        if (blur != null) {
            blur.setAlpha(fadeAlpha);
            blur.setIos27Dimming(NekoConfig.ios27DimmingEnabled(), headerBottom, color);
            blur.setIos27SeparatorSource(scrolling);
            blur.setFadeViewVisibility(useBlur ? View.VISIBLE : View.GONE);
            if (useBlur) {
                blur.setProgressiveBlurZoneTop(headerBottom + AndroidUtilities.dp(31));
                blur.setFadeZoneTop(edge);
                blur.invalidate();
            } else blur.stopContinuousUpdates();
        }

        final float currentDistance = distance();
        if (currentDistance != lastDistance) {
            host.removeCallbacks(settle);
            settlePosted = false;
            lastDistance = currentDistance;
        }
        if (!settlePosted && atContentEnd && currentDistance > 0 && currentDistance < range
                && (!(scrolling instanceof RecyclerView) || collapseGestureStarted)) {
            settlePosted = true;
            host.postDelayed(settle, 150);
        }
    }

    private void failSafe() {
        disabled = true;
        try {
            setUpstreamFadesVisible(true);
            if (surface != null) surface.setVisibility(View.GONE);
            if (plain != null) plain.setVisibility(View.GONE);
            if (blur != null) {
                blur.setFadeViewVisibility(View.GONE);
                blur.stopContinuousUpdates();
            }
            title.setVisibility(View.GONE);
            restoreScrollState();
            bar.setMaterialActivityHeader(false);
            bar.setExternalHeaderFade(false);
            bar.setPlainBackCircle(false);
            bar.setFadeButtonGlass(null);
            bar.setTranslationY(0);
            bar.setExternalHeaderTitleVisible(true);
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    public void pause() {
        paused = true;
        collapseGestureStarted = false;
        host.removeCallbacks(settle);
        settlePosted = false;
        if (blur != null) blur.stopContinuousUpdates();
    }

    public boolean resume() {
        if ((!NekoConfig.materialActivityHeaders && !NekoConfig.fadeViewOtherActivities()) || detached || fragment.getFragmentView() != content
                || fragment.getSimpleActionBar() != bar || bar.getParent() != host || scrolling.getParent() == null) return false;
        if (disabled) return true;
        paused = false;
        updateSafely();
        return true;
    }

    public void detach() {
        if (detached) return;
        detached = true;
        if (scrolling instanceof RecyclerView) ((RecyclerView) scrolling).removeOnScrollListener(gestureListener);
        host.removeCallbacks(settle);
        try {
            if (host.getViewTreeObserver().isAlive()) host.getViewTreeObserver().removeOnPreDrawListener(listener);
        } catch (Throwable t) {
            FileLog.e(t);
        }
        if (blur != null) {
            try {
                blur.detach();
            } catch (Throwable t) {
                FileLog.e(t);
            }
        }
        if (plain != null) AndroidUtilities.removeFromParent(plain);
        if (surface != null) AndroidUtilities.removeFromParent(surface);
        AndroidUtilities.removeFromParent(title);
        try {
            bar.setMaterialActivityHeader(false);
            bar.setExternalHeaderFade(false);
            bar.setPlainBackCircle(false);
            bar.setFadeButtonGlass(null);
            bar.setTranslationY(0);
            bar.setExternalHeaderTitleVisible(true);
            restoreScrollState();
        } catch (Throwable t) {
            FileLog.e(t);
        }
        try {
            setUpstreamFadesVisible(true);
        } catch (Throwable t) {
            FileLog.e(t);
        }
        host.requestLayout();
    }

    private void emergencyDetach() {
        try {
            detach();
        } catch (Throwable t) {
            FileLog.e(t);
        }
    }

    private static final class RemovedFade {
        final View view;
        final Drawable background;

        RemovedFade(View view, Drawable background) {
            this.view = view;
            this.background = background;
        }
    }

    private static final class ContentCapture extends FrameLayout {
        ContentCapture(android.content.Context context) { super(context); }
        void capture(Canvas canvas, View child) {
            super.drawChild(canvas, child, SystemClock.uptimeMillis());
        }
    }
}
