package org.telegram.ui.Components;

import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.ScrollView;

import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.OrientationHelper;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.chat.layouts.ChatActivityFadeView;

import zxc.iconic.xenon.NekoConfig;

/** Shared headers for ordinary list/form fragments; custom headers own their rendering. */
public final class ActivityHeadersController {
    private final BaseFragment fragment;
    private final View content, scrolling;
    private final ActionBar bar;
    private final ViewGroup barParent;
    private final int barIndex;
    private final ViewGroup.LayoutParams barParams;
    private final Drawable background;
    private final boolean shadows, clipToPadding;
    private final int left, top, right, bottom;
    private final Overlay overlay;
    private final MaterialActivityTitleView title;
    private final ChatActivityFadeView plain;
    private ProgressiveFadeBlurController blur;
    private org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode buttonSource;
    private FadeHeaderButtonGlass buttonGlass;
    private boolean paused;
    private final float barTranslation;
    private final ContentCapture capture;
    private final View scrollChild;
    private final int childMinimumHeight;
    private boolean settlePosted, atContentEnd;
    private boolean collapseGestureStarted;
    private final RecyclerView.OnScrollListener gestureListener = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrollStateChanged(RecyclerView recyclerView, int state) {
            if (state == RecyclerView.SCROLL_STATE_DRAGGING) collapseGestureStarted = true;
        }
    };
    private float lastDistance = -1;
    private final Runnable settle = this::finishCollapse;

    private void finishCollapse() {
        settlePosted = false;
        if (paused || overlay.getParent() == null || !atContentEnd || !NekoConfig.materialActivityHeaders
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
    private final ViewTreeObserver.OnPreDrawListener listener = this::update;
    private final int[] scrollLocation = new int[2], overlayLocation = new int[2];

    public static ActivityHeadersController attach(BaseFragment fragment) {
        if (!NekoConfig.materialActivityHeaders && !NekoConfig.fadeViewOtherActivities()) return null;
        if (fragment instanceof org.telegram.ui.ProfileActivity
                || fragment instanceof org.telegram.ui.ChatActivity
                || fragment instanceof org.telegram.ui.DialogsActivity
                || fragment instanceof zxc.iconic.xenon.settings.BaseNekoSettingsActivity) return null;
        View content = fragment.getFragmentView();
        ActionBar bar = fragment.getActionBar();
        if (content == null || !(content.getParent() instanceof FrameLayout) || bar == null
                || !(bar.getParent() instanceof ViewGroup) || android.text.TextUtils.isEmpty(bar.getTitle())) return null;
        View scrolling = findScrolling(content);
        if (scrolling == null && content instanceof ViewGroup) scrolling = content;
        if (scrolling == null) return null;
        return new ActivityHeadersController(fragment, content, scrolling, bar);
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

    private ActivityHeadersController(BaseFragment fragment, View content, View scrolling, ActionBar bar) {
        this.fragment = fragment;
        this.content = content;
        this.scrolling = scrolling;
        scrollChild = scrolling instanceof ScrollView || scrolling instanceof androidx.core.widget.NestedScrollView
                ? ((ViewGroup) scrolling).getChildAt(0) : null;
        childMinimumHeight = scrollChild == null ? 0 : scrollChild.getMinimumHeight();
        this.bar = bar;
        barParent = (ViewGroup) bar.getParent();
        barIndex = barParent.indexOfChild(bar);
        barParams = bar.getLayoutParams();
        background = bar.getBackground();
        shadows = bar.getCastShadows();
        barTranslation = bar.getTranslationY();
        left = scrolling.getPaddingLeft(); top = logicalPaddingTop();
        right = scrolling.getPaddingRight(); bottom = scrolling.getPaddingBottom();
        clipToPadding = ((ViewGroup) scrolling).getClipToPadding();
        overlay = new Overlay(content.getContext());
        capture = new ContentCapture(content.getContext());
        FrameLayout host = (FrameLayout) content.getParent();
        // Taking the toolbar out of the navigation container lets content occupy
        // the full window, including the status-bar capture region.
        barParent.removeView(bar);
        host.addView(overlay, new FrameLayout.LayoutParams(-1, -1));
        plain = new ChatActivityFadeView(content.getContext());
        plain.setupColorKey(Theme.key_windowBackgroundGray);
        plain.setOpaqueFade(true);
        plain.setFadeHeightTop(AndroidUtilities.dp(32), false);
        overlay.addView(plain, new FrameLayout.LayoutParams(-1, -1));
        title = new MaterialActivityTitleView(content.getContext());
        title.setOnClickListener(v -> bar.performClick());
        FrameLayout.LayoutParams titleParams = new FrameLayout.LayoutParams(-1, AndroidUtilities.dp(152), Gravity.TOP);
        titleParams.topMargin = statusInset();
        overlay.addView(title, titleParams);
        overlay.addView(bar, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        bar.setMaterialActivityHeader(NekoConfig.materialActivityHeaders);
        bar.setBackgroundColor(Color.TRANSPARENT);
        bar.setCastShadows(false);
        ((ViewGroup) scrolling).setClipToPadding(false);
        if (scrolling instanceof RecyclerListView) ((RecyclerListView) scrolling).setCaptureSectionsDecoratorAllowed(true);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            blur = new ProgressiveFadeBlurController(overlay, scrolling, 1,
                    () -> Theme.getColor(Theme.key_windowBackgroundGray, fragment.getResourceProvider()));
            blur.setUniformBlurAllowed(true);
            blur.setFadeHeightTop(AndroidUtilities.dp(32), false);
            blur.setContentCapture(overlay, (canvas, bounds) -> {
                if (!(scrolling.getParent() instanceof View)) return;
                ((View) scrolling.getParent()).getLocationInWindow(scrollLocation);
                overlay.getLocationInWindow(overlayLocation);
                int save = canvas.save();
                try {
                    canvas.translate(scrollLocation[0] - overlayLocation[0], scrollLocation[1] - overlayLocation[1]);
                    capture.capture(canvas, scrolling);
                } finally {
                    canvas.restoreToCount(save);
                }
            });
        }
        overlay.getViewTreeObserver().addOnPreDrawListener(listener);
        if (scrolling instanceof RecyclerView) ((RecyclerView) scrolling).addOnScrollListener(gestureListener);
        host.requestLayout();
        update();
    }

    private int statusInset() { return bar.getOccupyStatusBar() ? AndroidUtilities.statusBarHeight : 0; }

    private int logicalPaddingTop() {
        return scrolling instanceof BlurredRecyclerView ? ((BlurredRecyclerView) scrolling).topPadding : scrolling.getPaddingTop();
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

    private boolean update() {
        if (paused) return true;
        boolean material = NekoConfig.materialActivityHeaders;
        boolean normal = !bar.isSearchFieldVisible() && !bar.isActionModeShowed();
        boolean fade = NekoConfig.fadeViewOtherActivities() && normal;
        int compact = material ? AndroidUtilities.dp(64) : ActionBar.getCurrentActionBarHeight();
        int range = material && normal ? AndroidUtilities.dp(88) : 0;
        int nativeInset = content == barParent ? 0 : statusInset();
        int padding = top + nativeInset + compact + range;
        int paddingBottom = collapseBottomPadding(range, padding);
        if (applyScrollPadding(padding, paddingBottom)) {
            // New padding and old child positions describe different layouts.
            // Do not derive collapse progress (or schedule scrolling) from them,
            // and defer this draw until requestLayout has placed the children.
            overlay.removeCallbacks(settle);
            settlePosted = false;
            collapseGestureStarted = false;
            lastDistance = -1;
            return false;
        }
        FrameLayout.LayoutParams titleParams = (FrameLayout.LayoutParams) title.getLayoutParams();
        if (titleParams.topMargin != statusInset()) {
            titleParams.topMargin = statusInset();
            title.setLayoutParams(titleParams);
        }
        float progress = range == 0 ? 1f : Math.max(0f, Math.min(1f, distance() / range));
        int headerBottom = statusInset() + compact + Math.round(range * (1f - progress));
        int color = Theme.getColor(Theme.key_windowBackgroundGray, fragment.getResourceProvider());
        boolean drawSurface = !fade;
        if (overlay.surfaceBottom != headerBottom || overlay.surface.getColor() != color || overlay.drawSurface != drawSurface) {
            overlay.surfaceBottom = headerBottom;
            overlay.surface.setColor(color);
            overlay.drawSurface = drawSurface;
            overlay.invalidate();
        }
        bar.setMaterialActivityHeader(material);
        bar.setExternalHeaderFade(fade);
        // Theme descriptions may have replaced the native toolbar background
        // after attach. It must never cover the external title or live fade.
        if (bar.getBackground() != null) bar.setBackground(null);
        bar.setPlainBackCircle(material || fade);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && fade && !material) {
            if (buttonSource == null) {
                buttonSource = new org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode(null);
                buttonGlass = new FadeHeaderButtonGlass(overlay, buttonSource, fragment.getResourceProvider());
            }
            if (org.telegram.messenger.LiteMode.isEnabled(org.telegram.messenger.LiteMode.FLAG_LIQUID_GLASS)) {
                buttonSource.setGlassBlur(AndroidUtilities.dp(8));
            } else buttonSource.setPlainBlur(AndroidUtilities.dpf2(NekoConfig.blurStrength * 4f / 3f));
            if (overlay.getWidth() > 0 && overlay.getHeight() > 0) {
                android.graphics.Canvas backdrop = buttonSource.beginRecording(overlay.getWidth(), overlay.getHeight());
                try {
                    backdrop.drawColor(Theme.getColor(Theme.key_windowBackgroundGray, fragment.getResourceProvider()));
                    ((View) scrolling.getParent()).getLocationInWindow(scrollLocation);
                    overlay.getLocationInWindow(overlayLocation);
                    backdrop.translate(scrollLocation[0] - overlayLocation[0], scrollLocation[1] - overlayLocation[1]);
                    capture.capture(backdrop, scrolling);
                } finally {
                    buttonSource.endRecording();
                }
            }
            bar.setFadeButtonGlass(buttonGlass);
        } else bar.setFadeButtonGlass(null);
        bar.setTranslationY(material ? AndroidUtilities.dp(4) : 0);
        bar.setExternalHeaderTitleVisible(!material || !normal);
        title.setVisibility(material && normal ? View.VISIBLE : View.GONE);
        if (material && normal) title.update(bar.getTitle(), progress, NekoConfig.centerTitle, true,
                overlay.surface.getColor(), Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, fragment.getResourceProvider()),
                bar.createMenu().getMeasuredWidth(), bar.getBackButton() != null && bar.getBackButton().getVisibility() == View.VISIBLE
                        ? AndroidUtilities.dp(80) : AndroidUtilities.dp(24));
        int edge = headerBottom + Math.round(AndroidUtilities.dp(12) * Math.min(1f, distance() / AndroidUtilities.dp(48)));
        boolean useBlur = fade && blur != null && blur.isEnabled();
        plain.setVisibility(fade && !useBlur ? View.VISIBLE : View.GONE);
        plain.setFadeZoneTop(edge);
        plain.setFadeTopAlpha(NekoConfig.blurredFadeDimming ? NekoConfig.blurredFadeDimStrength * 255 / 100 : 0);
        if (blur != null) {
            blur.setFadeViewVisibility(useBlur ? View.VISIBLE : View.GONE);
            if (useBlur) {
                blur.setProgressiveBlurZoneTop(headerBottom + AndroidUtilities.dp(31));
                blur.setFadeZoneTop(edge);
                blur.invalidate();
            } else blur.stopContinuousUpdates();
        }
        float currentDistance = distance();
        if (currentDistance != lastDistance) {
            overlay.removeCallbacks(settle);
            settlePosted = false;
            lastDistance = currentDistance;
        }
        if (!settlePosted && atContentEnd && currentDistance > 0 && currentDistance < range
                && (!(scrolling instanceof RecyclerView) || collapseGestureStarted)) {
            settlePosted = true;
            overlay.postDelayed(settle, 150);
        }
        return true;
    }

    public void detach() {
        if (scrolling instanceof RecyclerView) ((RecyclerView) scrolling).removeOnScrollListener(gestureListener);
        overlay.removeCallbacks(settle);
        if (overlay.getViewTreeObserver().isAlive()) overlay.getViewTreeObserver().removeOnPreDrawListener(listener);
        if (blur != null) blur.stopContinuousUpdates();
        overlay.removeView(bar);
        barParent.addView(bar, Math.min(barIndex, barParent.getChildCount()), barParams);
        bar.setMaterialActivityHeader(false);
        bar.setExternalHeaderFade(false);
        bar.setPlainBackCircle(false);
        bar.setFadeButtonGlass(null);
        bar.setBackground(background);
        bar.setCastShadows(shadows);
        bar.setTranslationY(barTranslation);
        bar.setExternalHeaderTitleVisible(true);
        scrolling.setPadding(left, top, right, bottom);
        if (scrollChild != null) scrollChild.setMinimumHeight(childMinimumHeight);
        ((ViewGroup) scrolling).setClipToPadding(clipToPadding);
        AndroidUtilities.removeFromParent(overlay);
        barParent.requestLayout();
    }

    public void pause() {
        paused = true;
        collapseGestureStarted = false;
        overlay.removeCallbacks(settle);
        settlePosted = false;
        if (blur != null) blur.stopContinuousUpdates();
    }

    public boolean resume() {
        if ((!NekoConfig.materialActivityHeaders && !NekoConfig.fadeViewOtherActivities())
                || fragment.getFragmentView() != content || fragment.getActionBar() != bar
                || overlay.getParent() == null || overlay.getParent() != content.getParent()) return false;
        paused = false;
        update();
        return true;
    }

    private static final class ContentCapture extends FrameLayout {
        ContentCapture(android.content.Context context) { super(context); }
        void capture(android.graphics.Canvas canvas, View child) {
            // Share the displayed RenderNode, including stretch. Calling draw()
            // would record a second list and advance its EdgeEffects again.
            super.drawChild(canvas, child, android.os.SystemClock.uptimeMillis());
        }
    }

    private static final class Overlay extends FrameLayout {
        final Paint surface = new Paint();
        int surfaceBottom;
        boolean drawSurface;
        Overlay(android.content.Context context) { super(context); setWillNotDraw(false); setClipChildren(false); setClipToPadding(false); }
        @Override protected void onDraw(android.graphics.Canvas canvas) {
            if (drawSurface) canvas.drawRect(0, 0, getWidth(), surfaceBottom, surface);
        }
    }
}
