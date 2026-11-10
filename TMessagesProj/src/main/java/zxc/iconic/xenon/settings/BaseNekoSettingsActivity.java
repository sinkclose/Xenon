package zxc.iconic.xenon.settings;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.Build;
import android.os.Bundle;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.appcompat.widget.Toolbar;
import androidx.coordinatorlayout.widget.CoordinatorLayout;
import androidx.core.graphics.ColorUtils;
import com.google.android.material.appbar.AppBarLayout;
import com.google.android.material.appbar.CollapsingToolbarLayout;
import com.google.android.material.color.MaterialColors;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.INavigationLayout;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Cells.CheckBoxCell;
import org.telegram.ui.Cells.TextCheckbox2Cell;
import org.telegram.ui.Cells.TextDetailSettingsCell;
import org.telegram.ui.Cells.TextSettingsCell;
import org.telegram.ui.ChatActivity;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ProgressiveFadeBlurController;
import org.telegram.ui.Components.chat.layouts.ChatActivityFadeView;
import org.telegram.ui.Components.chat.ViewPositionWatcher;
import org.telegram.ui.Components.SizeNotifierFrameLayout;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.URLSpanNoUnderline;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import org.telegram.ui.Components.blur3.DownscaleScrollableNoiseSuppressor;
import org.telegram.ui.Components.blur3.RenderNodeWithHash;
import org.telegram.ui.Components.blur3.ViewGroupPartRenderer;
import org.telegram.ui.Components.blur3.capture.IBlur3Capture;
import org.telegram.ui.Components.blur3.capture.IBlur3Hash;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.IntConsumer;

import zxc.iconic.xenon.helpers.PopupHelper;

import zxc.iconic.xenon.NekoConfig;

public abstract class BaseNekoSettingsActivity extends BaseFragment {

    protected static final Object PARTIAL = new Object();

    protected SizeNotifierFrameLayout contentView;
    protected UniversalRecyclerView listView;
    protected LinearLayoutManager layoutManager;
    protected Theme.ResourcesProvider resourcesProvider;
    protected View actionBarBackground;
    protected FrameLayout actionBarContainer;
    protected ActionBarMenuItem searchItem;

    protected int rowId = 1;

    public BaseNekoSettingsActivity() {
        this(null);
    }

    public BaseNekoSettingsActivity(Bundle args) {
        super(args);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            scrollableViewNoiseSuppressor = new DownscaleScrollableNoiseSuppressor();
            iBlur3SourceGlassFrosted = new BlurredBackgroundSourceRenderNode(null);
            iBlur3SourceGlass = new BlurredBackgroundSourceRenderNode(null);
            iBlur3SourceGlass.setScrollableNoiseSuppressor(scrollableViewNoiseSuppressor,
                    DownscaleScrollableNoiseSuppressor.DRAW_GLASS);
            iBlur3SourceGlass.setUnderSource(new org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceColor() {
                @Override public void draw(Canvas canvas, float left, float top, float right, float bottom) {
                    canvas.drawColor(getThemedColor(Theme.key_windowBackgroundWhite));
                }
            });
            iBlur3SourceGlassFrosted.setupRenderer(new RenderNodeWithHash.Renderer() {
                @Override
                public void renderNodeCalculateHash(IBlur3Hash hash) {
                    hash.add(getThemedColor(Theme.key_windowBackgroundWhite));
                    hash.add(SharedConfig.chatBlurEnabled());
                }

                @Override
                public void renderNodeUpdateDisplayList(Canvas canvas) {
                    canvas.drawColor(getThemedColor(Theme.key_windowBackgroundWhite));
                    if (SharedConfig.chatBlurEnabled()) {
                        scrollableViewNoiseSuppressor.draw(canvas, DownscaleScrollableNoiseSuppressor.DRAW_FROSTED_GLASS);
                    }
                }
            });
        } else {
            scrollableViewNoiseSuppressor = null;
            iBlur3SourceGlassFrosted = null;
            iBlur3SourceGlass = null;
        }
    }

    @Override
    public View createView(Context context) {
        fadeButtonGlass = null;
        materialHeaderApplied = false;
        materialCoordinator = null;
        materialCollapsingToolbar = null;
        materialFadeHost = null;
        materialFadeController = null;
        materialPlainFade = null;
        materialFadeApplied = false;
        materialPlainFadeColor = Integer.MIN_VALUE;
        contentView = new SizeNotifierFrameLayout(context) {
            @Override
            protected void dispatchDraw(Canvas canvas) {
                // Only the short fade edge follows the first row's scroll.
                // The progressive blur keeps its original coverage and density.
                if (materialHeaderEnabled() && materialHeaderSearching != isSearchFieldVisible()) {
                    updateMaterialHeader();
                }
                final boolean fadeBlurActive = progressiveFadeController != null && progressiveFadeController.isEnabled();
                final int fadeZone = Math.max(listView.getPaddingTop(), actionBarContainer.getHeight());
                if (materialHeaderEnabled()) {
                    updateMaterialFade();
                    removePlainTopFade();
                } else if (fadeBlurActive) {
                    progressiveFadeController.setProgressiveBlurZoneTop(fadeZone + AndroidUtilities.dp(31));
                    progressiveFadeController.setFadeZoneTop(settingsFadeZoneTop(fadeZone));
                    progressiveFadeController.setAlpha(1f);
                    progressiveFadeController.invalidate();
                    removePlainTopFade();
                } else if (fadeViewEnabled() && NekoConfig.fadeViewOtherActivities()) {
                    ensurePlainTopFade(settingsFadeZoneTop(fadeZone));
                } else {
                    removePlainTopFade();
                }
                if (!materialHeaderEnabled() && Build.VERSION.SDK_INT >= 31 && scrollableViewNoiseSuppressor != null) {
                    blur3_InvalidateBlur();
                    if (iBlur3SourceGlassFrosted != null) {
                        iBlur3SourceGlassFrosted.setSize(getMeasuredWidth(), getMeasuredHeight());
                        iBlur3SourceGlassFrosted.updateDisplayListIfNeeded();
                    }
                    if (iBlur3SourceGlass != null && !iBlur3SourceGlass.inRecording()) {
                        final Canvas c = iBlur3SourceGlass.beginRecording(getMeasuredWidth(), getMeasuredHeight());
                        c.drawColor(getThemedColor(Theme.key_windowBackgroundWhite));
                        if (SharedConfig.chatBlurEnabled()) {
                            scrollableViewNoiseSuppressor.draw(c, DownscaleScrollableNoiseSuppressor.DRAW_GLASS);
                        }
                        iBlur3SourceGlass.endRecording();
                    }
                    iBlur3Invalidated = false;
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && iBlur3SourceGlass != null
                        && !materialHeaderEnabled() && NekoConfig.fadeViewOtherActivities()) {
                    if (fadeButtonGlass == null) fadeButtonGlass = new org.telegram.ui.Components.FadeHeaderButtonGlass(
                            this, iBlur3SourceGlass, resourcesProvider);
                    actionBar.setFadeButtonGlass(fadeButtonGlass);
                } else actionBar.setFadeButtonGlass(null);
                super.dispatchDraw(canvas);
            }

            @Override
            public void drawBlurRect(Canvas canvas, float y, Rect rectTmp, Paint blurScrimPaint, boolean top) {
                // The fade view (blurred or plain) covers the header zone.
                if (materialHeaderEnabled()) return;
                if (fadeViewEnabled() && NekoConfig.fadeViewOtherActivities()) {
                    return;
                }
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q || !SharedConfig.chatBlurEnabled() || iBlur3SourceGlassFrosted == null) {
                    canvas.drawRect(rectTmp, blurScrimPaint);
                    return;
                }

                canvas.save();
                canvas.translate(0, -y);
                iBlur3SourceGlassFrosted.draw(canvas, rectTmp.left, rectTmp.top + y, rectTmp.right, rectTmp.bottom + y);
                canvas.restore();

                final int oldScrimAlpha = blurScrimPaint.getAlpha();
                blurScrimPaint.setAlpha(ChatActivity.ACTION_BAR_BLUR_ALPHA);
                canvas.drawRect(rectTmp, blurScrimPaint);
                blurScrimPaint.setAlpha(oldScrimAlpha);
            }
        };
        contentView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));

        listView = new UniversalRecyclerView(this, this::fillItems, this::onItemClick, (item, view, position, x, y) -> {
            if (onItemLongClick(item, view, position, x, y)) {
                return true;
            }
            var slug = item.slug;
            var key = getKey();
            if (key != null && item.enabled && !TextUtils.isEmpty(slug)) {
                ItemOptions.makeOptions(this, view)
                        .setScrimViewBackground(listView.getClipBackground(view))
                        .add(R.drawable.msg_copy, LocaleController.getString(R.string.CopyLink), () -> {
                            if ("copyReportId".equals(slug)) {
                                AndroidUtilities.addToClipboard(String.format(Locale.getDefault(), "https://%s/nekosettings/%s", getMessagesController().linkPrefix, "reportId"));
                            } else if ("checkUpdate".equals(slug)) {
                                AndroidUtilities.addToClipboard(String.format(Locale.getDefault(), "https://%s/nekosettings/%s", getMessagesController().linkPrefix, "update"));
                            } else {
                                AndroidUtilities.addToClipboard(String.format(Locale.getDefault(), "https://%s/nekosettings/%s?r=%s", getMessagesController().linkPrefix, key, slug));
                            }
                            BulletinFactory.of(this).createCopyLinkBulletin().show();
                        })
                        .setMinWidth(190)
                        .show();
                return true;
            }
            return false;
        }) {

            @Override
            public Integer getSelectorColor(int position) {
                return BaseNekoSettingsActivity.this.getSelectorColor(position);
            }
        };
        listView.adapter.setApplyBackground(false);
        listView.setClipToPadding(false);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && scrollableViewNoiseSuppressor != null) {
                    scrollableViewNoiseSuppressor.onScrolled(dx, dy);
                    blur3_InvalidateBlur();
                }
                if (progressiveFadeController != null && !materialHeaderEnabled()) {
                    progressiveFadeController.invalidate();
                }
            }
        });
        iBlur3Capture = new ViewGroupPartRenderer(listView, contentView, listView::drawChild) {
            @Override
            public void capture(Canvas canvas, RectF position) {
                float alpha = BaseNekoSettingsActivity.this.listView.getAlpha();
                if (BaseNekoSettingsActivity.this.listView.getVisibility() != View.VISIBLE || alpha <= 0) return;
                if (alpha >= 1) {
                    super.capture(canvas, position);
                    return;
                }
                int save = canvas.saveLayerAlpha(position, Math.round(alpha * 255));
                try {
                    super.capture(canvas, position);
                } finally {
                    canvas.restoreToCount(save);
                }
            }

            @Override
            public void captureCalculateHash(IBlur3Hash hash, RectF position) {
                hash.add(BaseNekoSettingsActivity.this.listView.getVisibility());
                hash.addF(BaseNekoSettingsActivity.this.listView.getAlpha());
                super.captureCalculateHash(hash, position);
            }
        };
        listView.addEdgeEffectListener(() -> listView.postOnAnimation(this::blur3_InvalidateBlur));
        listView.setSections();
        listView.setCaptureSectionsDecoratorAllowed(true);
        listView.setPadding(0, needActionBarPadding() ? ActionBar.getCurrentActionBarHeight() : AndroidUtilities.dp(12), 0, 0);
        contentView.addView(listView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT, Gravity.FILL));

        actionBarBackground = new View(context) {
            private final Paint blurScrimPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            protected void onDraw(@NonNull Canvas canvas) {
                var top = actionBarContainer.getHeight();
                if (materialHeaderEnabled()) return;
                AndroidUtilities.rectTmp2.set(0, 0, getMeasuredWidth(), top);
                blurScrimPaint.setColor(Theme.getColor(Theme.key_actionBarDefault, resourceProvider));
                contentView.drawBlurRect(canvas, 0, AndroidUtilities.rectTmp2, blurScrimPaint, true);
                if (!fadeViewEnabled() || !NekoConfig.fadeViewOtherActivities()) {
                    if (getParentLayout() != null) {
                        getParentLayout().drawHeaderShadow(canvas, top);
                    }
                }
            }
        };
        contentView.addView(actionBarBackground, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 200, Gravity.TOP));
        actionBarContainer = new FrameLayout(context);
        actionBarContainer.setClipChildren(false);
        actionBarContainer.setClipToPadding(false);
        actionBarContainer.addView(actionBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.FILL_HORIZONTAL | Gravity.BOTTOM));
        contentView.addView(actionBarContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.FILL_HORIZONTAL | Gravity.TOP));

        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                updateActionBarVisible();
            }
        });

        updateActionBarVisible(true, false);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && progressiveBlurEnabled() && fadeViewEnabled()) {
            progressiveFadeController = new ProgressiveFadeBlurController(contentView, listView, contentView.indexOfChild(actionBarBackground), () -> getThemedColor(Theme.key_windowBackgroundGray));
            progressiveFadeController.setUniformBlurAllowed(true);
            progressiveFadeController.setFadeZoneTop(listView.getPaddingTop());
            progressiveFadeController.setContentCapture(contentView, iBlur3Capture);
            progressiveFadeController.setOpaqueFade(true);
            progressiveFadeController.setFadeHeightTop(AndroidUtilities.dp(32), false);
        }

        syncMaterialHeader();
        contentView.setClipChildren(false);
        contentView.setClipToPadding(false);
        return fragmentView = contentView;
    }

    protected void createSearchItem(ActionBarMenu menu, ActionBarMenuItem.ActionBarMenuItemSearchListener searchListener) {
        searchItem = menu.addItem(0, R.drawable.outline_header_search, resourcesProvider).setIsSearchField(true).setActionBarMenuItemSearchListener(searchListener);
        searchItem.setSearchFieldHint(LocaleController.getString(R.string.Search));
        searchItem.setContentDescription(LocaleController.getString(R.string.Search));
    }

    protected boolean isSearchFieldVisible() {
        return searchItem != null && searchItem.isSearchFieldVisible2();
    }

    @Override
    public boolean onBackPressed(boolean invoked) {
        if (isSearchFieldVisible()) {
            if (invoked) actionBar.closeSearchField();
            return false;
        }
        return super.onBackPressed(invoked);
    }

    private boolean actionBarVisible;
    private ValueAnimator actionBarVisibleAnimator;

    protected void updateActionBarVisible() {
        updateActionBarVisible(false, true);
    }

    private void updateActionBarVisible(boolean force, boolean animated) {
        if (materialHeaderEnabled()) {
            if (actionBarVisibleAnimator != null) {
                actionBarVisibleAnimator.cancel();
                actionBarVisibleAnimator = null;
            }
            updateMaterialHeader();
            return;
        }
        final boolean visible;
        if (isSearchFieldVisible()) {
            visible = true;
        } else if (listView.getChildCount() > 0) {
            var firstChild = listView.getChildAt(0);
            visible = needActionBarPadding() ? listView.canScrollVertically(-1) : (
                    listView.getChildAdapterPosition(firstChild) > 0 ||
                    firstChild.getY() + firstChild.getHeight() < actionBar.getHeight()
            );
        } else {
            visible = false;
        }
        if (actionBarVisible == visible && !force) return;

        actionBarVisible = visible;
        if (actionBarVisibleAnimator != null) {
            actionBarVisibleAnimator.cancel();
            actionBarVisibleAnimator = null;
        }
        if (!animated) {
            if (!needActionBarPadding())
                actionBar.getTitlesContainer().setAlpha(visible ? 1.0f : 0.0f);
            actionBarBackground.setAlpha(visible ? 1.0f : 0.0f);
        } else {
            actionBarVisibleAnimator = ValueAnimator.ofFloat(actionBarBackground.getAlpha(), visible ? 1.0f : 0.0f);
            actionBarVisibleAnimator.addUpdateListener(a -> {
                final float t = (float) a.getAnimatedValue();
                if (!needActionBarPadding()) actionBar.getTitlesContainer().setAlpha(t);
                actionBarBackground.setAlpha(t);
            });
            actionBarVisibleAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            actionBarVisibleAnimator.setDuration(420);
            actionBarVisibleAnimator.start();
        }
    }


    @Override
    public void setParentLayout(INavigationLayout layout) {
        if (layout != null && layout.getLastFragment() != null) {
            resourcesProvider = layout.getLastFragment().getResourceProvider();
        }
        super.setParentLayout(layout);
    }

    @Override
    public Theme.ResourcesProvider getResourceProvider() {
        return resourcesProvider;
    }

    @Override
    public ActionBar createActionBar(Context context) {
        var actionBar = super.createActionBar(context);
        actionBar.setBackgroundColor(Color.TRANSPARENT);
        actionBar.setAddToContainer(false);
        actionBar.setUseContainerForTitles();
        actionBar.setOccupyStatusBar(false);
        actionBar.setTitle(getActionBarTitle());
        actionBar.setTitleColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        actionBar.setItemsColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText), false);
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        // Plain disc behind the back button, only with the fade toggle on
        // (on every screen, including the main one). Synced live in onResume
        // so switching the toggle off removes it without reopening.
        actionBar.setPlainBackCircle(NekoConfig.fadeViewOtherActivities());
        // Centered title, same scope as the fade view: the main screen keeps
        // the stock left-aligned title via fadeViewEnabled.
        syncCenterTitle(actionBar, false);
        return actionBar;
    }

    public void syncCenterTitle(boolean animate) {
        syncCenterTitle(getActionBar(), animate);
        updateMaterialHeader();
    }

    private void syncCenterTitle(ActionBar actionBar, boolean animate) {
        boolean centered = fadeViewEnabled() && NekoConfig.centerTitle;
        if (actionBar == null || actionBar.isCenterTitleActive() == centered) {
            return;
        }
        if (centered) {
            if (animate) {
                actionBar.centerTitleAnimated();
            } else {
                actionBar.centerTitle();
            }
        } else {
            actionBar.cancelCenterTitle(animate);
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (getActionBar() != null) {
            getActionBar().setPlainBackCircle(NekoConfig.fadeViewOtherActivities());
        }
        syncCenterTitle(false);
        syncMaterialHeader();
    }

    private CoordinatorLayout materialCoordinator;
    private AppBarLayout materialAppBar;
    private CollapsingToolbarLayout materialCollapsingToolbar;
    private Toolbar materialToolbar;
    private boolean materialHeaderApplied;
    private boolean materialHeaderSearching;
    private int headerInsetLeft;
    private int headerInsetTop = AndroidUtilities.statusBarHeight;
    private int headerInsetRight;
    private int headerInsetBottom;
    private Typeface materialDefaultExpandedTypeface;
    private Typeface materialDefaultCollapsedTypeface;
    private int materialFontGeneration = -1;

    protected boolean materialHeaderEnabled() {
        return NekoConfig.materialActivityHeaders;
    }

    // Use Material's nested scrolling and title renderer. In particular, do not
    // derive collapse from accumulated RecyclerView dy or fade two titles to zero.
    protected void syncMaterialHeader() {
        if (listView == null || actionBarContainer == null) return;
        boolean enabled = materialHeaderEnabled();
        if (enabled != materialHeaderApplied) {
            materialHeaderApplied = enabled;
            ((ViewGroup) listView.getParent()).removeView(listView);
            ((ViewGroup) actionBarContainer.getParent()).removeView(actionBarContainer);
            if (enabled) {
                createMaterialHeader();
                listView.setPadding(0, 0, 0, 0);
                listView.setClipToPadding(true);
                listView.setNestedScrollingEnabled(true);
                CoordinatorLayout.LayoutParams scrolling = new CoordinatorLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
                scrolling.setBehavior(new AppBarLayout.ScrollingViewBehavior() {
                    @Override
                    public boolean onMeasureChild(CoordinatorLayout parent, View child, int widthSpec, int widthUsed, int heightSpec, int heightUsed) {
                        boolean measured = super.onMeasureChild(parent, child, widthSpec, widthUsed, heightSpec, heightUsed);
                        if (measured && materialFadeEnabled()) {
                            child.measure(View.MeasureSpec.makeMeasureSpec(child.getMeasuredWidth(), View.MeasureSpec.EXACTLY),
                                    View.MeasureSpec.makeMeasureSpec(child.getMeasuredHeight() + materialListOverlap(), View.MeasureSpec.EXACTLY));
                        }
                        return measured;
                    }

                    @Override
                    public boolean onLayoutChild(CoordinatorLayout parent, View child, int direction) {
                        boolean laidOut = super.onLayoutChild(parent, child, direction);
                        if (materialFadeEnabled()) child.offsetTopAndBottom(-materialListOverlap());
                        return laidOut;
                    }

                    @Override
                    public boolean onDependentViewChanged(CoordinatorLayout parent, View child, View dependency) {
                        boolean changed = super.onDependentViewChanged(parent, child, dependency);
                        if (materialFadeEnabled() && dependency == materialAppBar) {
                            child.offsetTopAndBottom(dependency.getBottom() - materialListOverlap() - child.getTop());
                        }
                        return changed;
                    }
                });
                materialCoordinator.addView(listView, 0, scrolling);
                CollapsingToolbarLayout.LayoutParams actions = new CollapsingToolbarLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(64));
                actions.setCollapseMode(CollapsingToolbarLayout.LayoutParams.COLLAPSE_MODE_PIN);
                materialCollapsingToolbar.addView(actionBarContainer, actions);
                materialBackTarget = new View(contentView.getContext());
                materialBackTarget.setContentDescription(LocaleController.getString(R.string.AccDescrGoBack));
                materialBackTarget.setBackground(Theme.createSelectorDrawable(getThemedColor(Theme.key_listSelector), 1));
                materialBackTarget.setOnClickListener(v -> {
                    if (isSearchFieldVisible()) actionBar.closeSearchField();
                    else finishFragment();
                });
                actionBarContainer.addView(materialBackTarget, LayoutHelper.createFrame(48, 48,
                        Gravity.LEFT | Gravity.CENTER_VERTICAL, 20, 0, 0, 0));
                actionBar.setPlainBackCircle(false);
                actionBar.backButtonImageView.setVisibility(View.INVISIBLE);
                actionBar.getTitlesContainer().setVisibility(View.INVISIBLE);
                actionBar.setBackgroundColor(Color.TRANSPARENT);
                actionBarContainer.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4));
                actionBarBackground.setVisibility(View.GONE);
                removePlainTopFade();
            } else {
                if (materialFadeController != null) materialFadeController.stopContinuousUpdates();
                materialFadeController = null;
                materialPlainFade = null;
                materialFadeHost = null;
                materialFadeApplied = false;
                materialPlainFadeColor = Integer.MIN_VALUE;
                if (materialBackTarget != null) actionBarContainer.removeView(materialBackTarget);
                contentView.removeView(materialCoordinator);
                contentView.addView(listView, 0, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
                contentView.addView(actionBarContainer, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT,
                        LayoutHelper.WRAP_CONTENT, Gravity.TOP));
                listView.setPadding(0, needActionBarPadding() ? ActionBar.getCurrentActionBarHeight() : AndroidUtilities.dp(12), 0, 0);
                listView.setClipToPadding(false);
                contentView.setBackgroundColor(getThemedColor(Theme.key_windowBackgroundGray));
                actionBarContainer.setPadding(0, 0, 0, 0);
                actionBar.backButtonImageView.setVisibility(View.VISIBLE);
                actionBar.getTitlesContainer().setVisibility(View.VISIBLE);
                actionBar.getTitlesContainer().setAlpha(1f);
                actionBarBackground.setVisibility(View.VISIBLE);
                materialCoordinator = null;
                materialCollapsingToolbar = null;
            }
        }
        actionBar.setMaterialActivityHeader(enabled);
        actionBar.setPlainBackCircle(!enabled && NekoConfig.fadeViewOtherActivities());
        if (progressiveFadeController != null) {
            progressiveFadeController.setFadeViewVisibility(enabled ? View.GONE : View.VISIBLE);
            if (enabled) progressiveFadeController.stopContinuousUpdates();
        }
        applyHeaderInsets();
        updateMaterialHeader();
        updateActionBarVisible(true, false);
        contentView.invalidate();
    }

    private void createMaterialHeader() {
        boolean dark = ColorUtils.calculateLuminance(getThemedColor(Theme.key_windowBackgroundGray)) < 0.5;
        ContextThemeWrapper materialContext = new ContextThemeWrapper(getContext(), dark
                ? com.google.android.material.R.style.Theme_Material3_Dark_NoActionBar
                : com.google.android.material.R.style.Theme_Material3_Light_NoActionBar);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            materialContext.getTheme().applyStyle(dark
                    ? com.google.android.material.R.style.ThemeOverlay_Material3_DynamicColors_Dark
                    : com.google.android.material.R.style.ThemeOverlay_Material3_DynamicColors_Light, true);
        }
        int surface = ColorUtils.setAlphaComponent(getThemedColor(Theme.key_windowBackgroundGray), 255);
        int onSurface = getThemedColor(Theme.key_windowBackgroundWhiteBlackText);
        int buttonSurface = ColorUtils.blendARGB(surface, onSurface, 0.08f);
        int onButton = onSurface;
        contentView.setBackgroundColor(surface);
        materialCoordinator = new CoordinatorLayout(materialContext);
        // The controls retain their status-bar inset; only the fade may extend above it.
        materialCoordinator.setClipChildren(false);
        materialCoordinator.setClipToPadding(false);
        materialCoordinator.setBackgroundColor(surface);
        contentView.addView(materialCoordinator, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        materialAppBar = new AppBarLayout(materialContext);
        materialAppBar.setBackgroundColor(surface);
        materialAppBar.setTargetElevation(0);
        materialAppBar.setStateListAnimator(null);
        materialAppBar.setLiftOnScroll(false);
        // Material 3 can animate liftOnScrollColor on collapse even when
        // liftOnScroll is false. The fade owns this background completely.
        materialAppBar.setLiftOnScrollColor(null);
        materialAppBar.setLiftable(false);
        materialAppBar.setLifted(false);
        materialAppBar.setStatusBarForeground(null);
        CoordinatorLayout.LayoutParams bar = new CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        bar.setBehavior(new AppBarLayout.Behavior());
        materialCoordinator.addView(materialAppBar, bar);
        materialFadeHost = new FrameLayout(materialContext);
        materialFadeHost.setClickable(false);
        materialCoordinator.addView(materialFadeHost, 0, new CoordinatorLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        materialAppBar.addOnOffsetChangedListener((appBar, offset) -> {
            if (materialFadeEnabled()) {
                if (materialFadeController != null) materialFadeController.invalidate();
                contentView.invalidate();
            }
        });
        materialCollapsingToolbar = new CollapsingToolbarLayout(materialContext);
        materialCollapsingToolbar.setId(View.generateViewId());
        materialCollapsingToolbar.setMinimumHeight(AndroidUtilities.dp(64));
        materialCollapsingToolbar.setBackgroundColor(surface);
        materialCollapsingToolbar.setContentScrimColor(surface);
        materialCollapsingToolbar.setStatusBarScrimColor(surface);
        materialCollapsingToolbar.setTitleCollapseMode(CollapsingToolbarLayout.TITLE_COLLAPSE_MODE_SCALE);
        materialCollapsingToolbar.setExpandedTitleTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_DisplaySmall);
        materialCollapsingToolbar.setCollapsedTitleTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_HeadlineSmall);
        // Keep Material's default typography when the selected family has no
        // variable rounded face. Supported families use the same ROND/wght path
        // as ActionBar rather than replacing Flex with plain Google Sans.
        materialDefaultExpandedTypeface = materialCollapsingToolbar.getExpandedTitleTypeface();
        materialDefaultCollapsedTypeface = materialCollapsingToolbar.getCollapsedTitleTypeface();
        materialFontGeneration = -1;
        materialCollapsingToolbar.setExpandedTitleColor(onSurface);
        materialCollapsingToolbar.setCollapsedTitleTextColor(onSurface);
        materialCollapsingToolbar.setExpandedTitleMargin(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), AndroidUtilities.dp(24));
        materialCollapsingToolbar.setMaxLines(2);
        materialCollapsingToolbar.setExtraMultilineHeightEnabled(true);
        AppBarLayout.LayoutParams collapsing = new AppBarLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(152));
        collapsing.setScrollFlags(AppBarLayout.LayoutParams.SCROLL_FLAG_SCROLL
                | AppBarLayout.LayoutParams.SCROLL_FLAG_EXIT_UNTIL_COLLAPSED
                | AppBarLayout.LayoutParams.SCROLL_FLAG_SNAP);
        materialAppBar.addView(materialCollapsingToolbar, collapsing);
        materialToolbar = new Toolbar(materialContext);
        materialToolbar.setBackgroundColor(Color.TRANSPARENT);
        materialToolbar.setId(View.generateViewId());
        materialToolbar.setPadding(AndroidUtilities.dp(16), 0, AndroidUtilities.dp(16), 0);
        materialToolbar.setContentInsetsRelative(0, 0);
        materialToolbar.setContentInsetStartWithNavigation(AndroidUtilities.dp(64));
        materialToolbar.setTitleMarginStart(0);
        GradientDrawable circle = new GradientDrawable();
        circle.setShape(GradientDrawable.OVAL);
        circle.setColor(buttonSurface);
        circle.setSize(AndroidUtilities.dp(40), AndroidUtilities.dp(40));
        Drawable arrow = materialContext.getDrawable(R.drawable.ic_material_header_back).mutate();
        arrow.setTint(onButton);
        LayerDrawable back = new LayerDrawable(new Drawable[]{circle, arrow});
        back.setLayerSize(0, AndroidUtilities.dp(40), AndroidUtilities.dp(40));
        back.setLayerSize(1, AndroidUtilities.dp(24), AndroidUtilities.dp(24));
        back.setLayerGravity(1, Gravity.CENTER);
        materialToolbar.setNavigationIcon(back);
        materialToolbar.setNavigationContentDescription(LocaleController.getString(R.string.AccDescrGoBack));
        materialToolbar.setNavigationOnClickListener(v -> {
            if (isSearchFieldVisible()) actionBar.closeSearchField();
            else finishFragment();
        });
        CollapsingToolbarLayout.LayoutParams toolbar = new CollapsingToolbarLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(64));
        toolbar.setCollapseMode(CollapsingToolbarLayout.LayoutParams.COLLAPSE_MODE_PIN);
        materialCollapsingToolbar.addView(materialToolbar, toolbar);
    }

    private void updateMaterialHeader() {
        if (!materialHeaderEnabled() || materialCollapsingToolbar == null) return;
        int generation = zxc.iconic.xenon.helpers.ClientFonts.generation();
        if (materialFontGeneration != generation) {
            materialFontGeneration = generation;
            boolean rounded = zxc.iconic.xenon.helpers.TypefaceHelper.supportsRoundedTypeface();
            Typeface title = rounded ? zxc.iconic.xenon.helpers.TypefaceHelper.createRoundedTypeface(550, false) : null;
            materialCollapsingToolbar.setExpandedTitleTypeface(rounded ? title : materialDefaultExpandedTypeface);
            materialCollapsingToolbar.setCollapsedTitleTypeface(rounded ? title : materialDefaultCollapsedTypeface);
        }
        materialHeaderSearching = isSearchFieldVisible();
        materialCollapsingToolbar.setTitleEnabled(!materialHeaderSearching);
        materialCollapsingToolbar.setTitle(getActionBarTitle());
        // Expanded titles stay at the start and retain their full multiline width.
        materialCollapsingToolbar.setMaxLines(2);
        materialCollapsingToolbar.setExtraMultilineHeightEnabled(true);
        int horizontal = NekoConfig.centerTitle ? Gravity.CENTER_HORIZONTAL : Gravity.START;
        materialCollapsingToolbar.setExpandedTitleGravity(Gravity.BOTTOM | Gravity.START);
        materialCollapsingToolbar.setCollapsedTitleGravity(Gravity.CENTER_VERTICAL | horizontal);
        // Leave room for the existing search/overflow menu, which stays interactive.
        int menuWidth = actionBar.createMenu().getMeasuredWidth();
        int trailing = menuWidth == 0 ? 0 : menuWidth + AndroidUtilities.dp(16);
        int leading = AndroidUtilities.dp(64);
        int titleInset = Math.max(leading, trailing);
        Paint titleMeasure = new Paint(Paint.ANTI_ALIAS_FLAG);
        titleMeasure.setTypeface(materialCollapsingToolbar.getCollapsedTitleTypeface());
        titleMeasure.setTextSize(24 * materialToolbar.getResources().getDisplayMetrics().scaledDensity);
        boolean fitsScreenCenter = titleMeasure.measureText(getActionBarTitle().toString())
                <= materialToolbar.getWidth() - 2 * titleInset;
        boolean symmetric = NekoConfig.centerTitle && fitsScreenCenter;
        materialToolbar.setContentInsetStartWithNavigation(symmetric ? titleInset : leading);
        materialToolbar.setTitleMarginEnd(symmetric ? titleInset : trailing);
        if (materialHeaderSearching) materialAppBar.setExpanded(false, false);
        updateMaterialFade();
    }

    private FrameLayout materialFadeHost;
    private ProgressiveFadeBlurController materialFadeController;
    private ChatActivityFadeView materialPlainFade;
    private View materialBackTarget;
    private boolean materialFadeApplied;
    private int materialPlainFadeColor = Integer.MIN_VALUE;

    private int materialListOverlap() {
        // The viewport starts at screen y=0 when collapsed, not below the status
        // bar. Padding keeps the first row in exactly the same visual position.
        return AndroidUtilities.dp(64) + headerInsetTop;
    }

    private float settingsFadeProgress() {
        if (listView == null || !listView.canScrollVertically(-1)) return 0f;
        View first = listView.layoutManager.findViewByPosition(0);
        if (first == null) return 1f;
        float distance = Math.max(0f, listView.getPaddingTop() - first.getTop());
        return Math.min(1f, distance / Math.max(1, AndroidUtilities.dp(48)));
    }

    private int settingsFadeZoneTop(int headerBottom) {
        return headerBottom + Math.round(AndroidUtilities.dp(12) * settingsFadeProgress());
    }

    private IBlur3Capture materialFadeCapture() {
        final View host = materialFadeHost;
        final ViewGroup root = contentView;
        final ViewGroupPartRenderer renderer = new ViewGroupPartRenderer(listView, root, listView::drawChild);
        return new IBlur3Capture() {
            private final PointF origin = new PointF();
            private final RectF rootBounds = new RectF();

            @Override
            public void capture(Canvas canvas, RectF bounds) {
                if (!ViewPositionWatcher.computeCoordinatesInParent(host, root, origin)) return;
                rootBounds.set(bounds);
                rootBounds.offset(origin.x, origin.y);
                int save = canvas.save();
                try {
                    canvas.translate(-origin.x, -origin.y);
                    renderer.capture(canvas, rootBounds);
                } finally {
                    canvas.restoreToCount(save);
                }
            }
        };
    }

    private boolean materialFadeEnabled() {
        return materialHeaderEnabled() && NekoConfig.fadeViewOtherActivities();
    }

    private void updateMaterialFade() {
        if (materialFadeHost == null || materialCollapsingToolbar == null) return;
        boolean fade = materialFadeEnabled();
        if (materialFadeApplied != fade) {
            materialFadeApplied = fade;
            int surface = ColorUtils.setAlphaComponent(getThemedColor(Theme.key_windowBackgroundGray), 255);
            if (fade) {
                // A transparent ColorDrawable is converted back into a Material
                // shape by AppBarLayout. Remove the drawable instead, so scroll
                // state changes cannot paint a rectangular tonal surface.
                materialAppBar.setBackground(null);
                materialCollapsingToolbar.setBackground(null);
                materialCollapsingToolbar.setContentScrim(null);
                materialCollapsingToolbar.setStatusBarScrim(null);
            } else {
                materialAppBar.setBackgroundColor(surface);
                materialCollapsingToolbar.setBackgroundColor(surface);
                materialCollapsingToolbar.setContentScrimColor(surface);
                materialCollapsingToolbar.setStatusBarScrimColor(surface);
            }
            applyHeaderInsets();
            listView.requestLayout();
        }
        materialFadeHost.setVisibility(fade ? View.VISIBLE : View.GONE);
        if (!fade) {
            if (materialFadeController != null) {
                materialFadeController.setFadeViewVisibility(View.GONE);
                materialFadeController.stopContinuousUpdates();
            }
            return;
        }
        int headerBottom = Math.max(headerInsetTop + AndroidUtilities.dp(64), materialAppBar.getBottom());
        int zone = settingsFadeZoneTop(headerBottom);
        boolean blur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
                && (NekoConfig.blurredFadeViewEnabled() || NekoConfig.progressiveFadeBlurOtherActivitiesEnabled());
        if (blur && materialFadeController == null) {
            materialFadeController = new ProgressiveFadeBlurController(materialFadeHost, listView, -1,
                    () -> getThemedColor(Theme.key_windowBackgroundGray));
            materialFadeController.setUniformBlurAllowed(true);
            materialFadeController.setContentCapture(materialFadeHost, materialFadeCapture());
            materialFadeController.setOpaqueFade(true);
            materialFadeController.setFadeHeightTop(AndroidUtilities.dp(32), false);
        }
        if (materialFadeController != null) {
            materialFadeController.setFadeViewVisibility(blur ? View.VISIBLE : View.GONE);
            materialFadeController.setAlpha(1f);
            if (blur) {
                materialFadeController.setProgressiveBlurZoneTop(headerBottom + AndroidUtilities.dp(31));
                materialFadeController.setFadeZoneTop(zone);
                materialFadeController.invalidate();
            } else materialFadeController.stopContinuousUpdates();
        }
        if (!blur && materialPlainFade == null) {
            materialPlainFade = new ChatActivityFadeView(contentView.getContext());
            materialPlainFade.setupColorKey(Theme.key_windowBackgroundGray);
            materialPlainFade.setOpaqueFade(true);
            materialFadeHost.addView(materialPlainFade, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }
        if (materialPlainFade != null) {
            materialPlainFade.setFadeTopAlpha(NekoConfig.blurredFadeDimming ? NekoConfig.blurredFadeDimStrength * 255 / 100 : 0);
            materialPlainFade.setVisibility(blur ? View.GONE : View.VISIBLE);
            materialPlainFade.setAlpha(1f);
            materialPlainFade.setFadeZoneTop(zone);
            materialPlainFade.setFadeHeightTop(AndroidUtilities.dp(32), false);
            int color = getThemedColor(Theme.key_windowBackgroundGray);
            if (materialPlainFadeColor != color) {
                materialPlainFadeColor = color;
                materialPlainFade.setDimColor(color);
                materialPlainFade.updateColors(resourcesProvider);
            }
        }
    }

    protected String getKey() {
        return null;
    }

    protected abstract void fillItems(ArrayList<UItem> items, UniversalAdapter adapter);

    protected abstract void onItemClick(UItem item, View view, int position, float x, float y);

    protected boolean onItemLongClick(UItem item, View view, int position, float x, float y) {
        return false;
    }

    protected abstract String getActionBarTitle();

    public Integer getSelectorColor(int position) {
        return getThemedColor(Theme.key_listSelector);
    }

    protected boolean needActionBarPadding() {
        return true;
    }

    protected boolean progressiveBlurEnabled() {
        return true;
    }

    // Whether the fade view applies to this screen at all. The main screen
    // keeps the stock header with its logo (the back-button disc still
    // follows the toggle there).
    protected boolean fadeViewEnabled() {
        return true;
    }

    // Theme-colored alternative using the same short edge as the blurred fade.
    private void ensurePlainTopFade(int fadeZone) {
        if (plainTopFadeView == null) {
            plainTopFadeView = new ChatActivityFadeView(contentView.getContext());
            plainTopFadeView.setupColorKey(Theme.key_windowBackgroundGray);
            plainTopFadeView.setOpaqueFade(true);
            plainTopFadeView.setFadeHeightTop(AndroidUtilities.dp(32), false);
            lastPlainFadeColor = Integer.MIN_VALUE;
            contentView.addView(plainTopFadeView, contentView.indexOfChild(actionBarBackground), LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        }
        // Keep the transition within the last 32dp instead of stretching the
        // mask across the header; its lower edge stays above the first item.
        final int plainZone = fadeZone;
        // A color-source fade has no separate dim layer; tint strength is its alpha.
        plainTopFadeView.setFadeTopAlpha(NekoConfig.blurredFadeDimming ? NekoConfig.blurredFadeDimStrength * 255 / 100 : 0);
        plainTopFadeView.setAlpha(1f);
        plainTopFadeView.setFadeZoneTop(plainZone);
        plainTopFadeView.setFadeHeightTop(AndroidUtilities.dp(32), false);
        final int color = getThemedColor(Theme.key_windowBackgroundGray);
        if (color != lastPlainFadeColor) {
            lastPlainFadeColor = color;
            plainTopFadeView.updateColors();
            plainTopFadeView.setDimColor(color);
        }
    }

    private void removePlainTopFade() {
        if (plainTopFadeView != null) {
            contentView.removeView(plainTopFadeView);
            plainTopFadeView = null;
        }
    }

    // Re-applies the stock non-blurred fade immediately when the master blur
    // switch changes (manual toggle or power saver), no re-enter needed.
    protected void refreshFadeBlurState() {
        if (progressiveFadeController != null) {
            progressiveFadeController.syncState();
            progressiveFadeController.invalidate();
        }
        if (listView != null) {
            listView.invalidate();
        }
        if (contentView != null) {
            contentView.invalidate();
        }
    }

    private final Utilities.Callback<Boolean> onPowerSaverApplied = applied -> refreshFadeBlurState();

    @Override
    public void onBecomeFullyVisible() {
        super.onBecomeFullyVisible();
        LiteMode.addOnPowerSaverAppliedListener(onPowerSaverApplied);
    }

    @Override
    public void onBecomeFullyHidden() {
        super.onBecomeFullyHidden();
        LiteMode.removeOnPowerSaverAppliedListener(onPowerSaverApplied);
    }

    protected void showRestartBulletin() {
        Activity activity = getParentActivity();
        if (activity == null) return;
        BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip,
                LocaleController.formatString(R.string.RestartAppToTakeEffect),
                LocaleController.getString(R.string.BotUnblockNoCaps),
                () -> restartApp(activity)).show();
    }

    private static void restartApp(Activity activity) {
        Context ctx = activity.getApplicationContext();
        Intent launch = ctx.getPackageManager().getLaunchIntentForPackage(ctx.getPackageName());
        if (launch == null || launch.getComponent() == null) {
            activity.recreate();
            return;
        }
        ctx.startActivity(Intent.makeRestartActivityTask(launch.getComponent()));
        activity.finishAffinity();
        android.os.Process.killProcess(android.os.Process.myPid());
        System.exit(0);
    }

    protected void updateRows() {
        listView.adapter.update(true);
    }

    protected void notifyItemChanged(int itemId) {
        notifyItemChanged(itemId, null);
    }

    protected void notifyItemChanged(int itemId, Object payload) {
        listView.adapter.notifyItemChanged(listView.findPositionByItemId(itemId), payload);
    }

    protected void notifyItemRemoved(int itemId) {
        listView.adapter.notifyItemRemoved(listView.findPositionByItemId(itemId));
    }

    protected void notifyItemInserted(int itemId) {
        listView.adapter.notifyItemInserted(listView.findPositionByItemId(itemId));
    }

    protected void notifyItemRangeRemoved(int itemId, int itemCount) {
        listView.adapter.notifyItemRangeRemoved(listView.findPositionByItemId(itemId), itemCount);
    }

    protected void notifyItemRangeInserted(int itemId, int itemCount) {
        listView.adapter.notifyItemRangeInserted(listView.findPositionByItemId(itemId), itemCount);
    }

    public static CharSequence getSpannedString(int id, String url) {
        var text = LocaleController.getString(id);
        var builder = new SpannableStringBuilder(text);
        int index1 = text.indexOf("**");
        int index2 = text.lastIndexOf("**");
        if (index1 >= 0 && index2 >= 0 && index1 != index2) {
            builder.replace(index2, index2 + 2, "");
            builder.replace(index1, index1 + 2, "");
            builder.setSpan(new URLSpanNoUnderline(url), index1, index2 - 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        return builder;
    }

    public void scrollToRow(String key, Runnable unknown) {
        if (listView == null) return;
        var position = listView.findPositionByItemSlug(key);
        if (position != -1) {
            listView.highlightRow(() -> {
                var layoutManager = (LinearLayoutManager) listView.getLayoutManager();
                layoutManager.scrollToPositionWithOffset(position, AndroidUtilities.dp(60));
                return position;
            });
        } else {
            if (unknown != null) unknown.run();
        }
    }

    public void showPopup(List<? extends CharSequence> entries, int checkedIndex, UItem item, View itemView, IntConsumer listener) {
        showPopup(entries, checkedIndex, item, itemView, listener, null);
    }

    public void showPopup(List<? extends CharSequence> entries, int checkedIndex, UItem item, View itemView, IntConsumer listener, Theme.ResourcesProvider provider) {
        PopupHelper.show(entries, null, checkedIndex, getParentActivity(), itemView, i -> {
            item.textValue = entries.get(i);
            listener.accept(i);
        }, provider);
    }

    @Override
    public boolean isSupportEdgeToEdge() {
        return true;
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        headerInsetLeft = left;
        headerInsetTop = top;
        headerInsetRight = right;
        headerInsetBottom = bottom;
        applyHeaderInsets();
        super.onInsets(left, top, right, bottom);
    }

    private void applyHeaderInsets() {
        if (listView == null || actionBarContainer == null) return;
        if (materialHeaderEnabled() && materialCoordinator != null) {
            // Insets belong to the whole nested-scrolling layout. Padding the
            // RecyclerView at the top clips row text but leaves section fills
            // visible there, producing empty "ghost" cards below the app bar.
            FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) materialCoordinator.getLayoutParams();
            if (params.leftMargin != headerInsetLeft || params.topMargin != 0
                    || params.rightMargin != headerInsetRight) {
                params.setMargins(headerInsetLeft, 0, headerInsetRight, 0);
                materialCoordinator.setLayoutParams(params);
            }
            // Keep the coordinator's bounds behind the status bar. Its padding
            // insets the app bar and list; the fade cancels only that padding.
            if (materialCoordinator.getPaddingTop() != headerInsetTop) {
                materialCoordinator.setPadding(0, headerInsetTop, 0, 0);
            }
            CoordinatorLayout.LayoutParams fadeParams = (CoordinatorLayout.LayoutParams) materialFadeHost.getLayoutParams();
            if (fadeParams.topMargin != -headerInsetTop) {
                fadeParams.topMargin = -headerInsetTop;
                materialFadeHost.setLayoutParams(fadeParams);
            }
            listView.setPadding(0, materialFadeEnabled() ? materialListOverlap() : 0, 0, headerInsetBottom);
            listView.setClipToPadding(!materialFadeEnabled());
            actionBarContainer.setPadding(0, AndroidUtilities.dp(4), 0, AndroidUtilities.dp(4));
        } else {
            int topPadding = needActionBarPadding() ? ActionBar.getCurrentActionBarHeight() : AndroidUtilities.dp(12);
            listView.setPadding(0, headerInsetTop + topPadding, 0, headerInsetBottom);
            actionBarContainer.setPadding(0, headerInsetTop, 0, 0);
        }
    }

    /* Blur */

    private final @Nullable DownscaleScrollableNoiseSuppressor scrollableViewNoiseSuppressor;
    private final @Nullable BlurredBackgroundSourceRenderNode iBlur3SourceGlassFrosted;
    private final @Nullable BlurredBackgroundSourceRenderNode iBlur3SourceGlass;

    private boolean iBlur3Invalidated;
    private IBlur3Capture iBlur3Capture;
    private ProgressiveFadeBlurController progressiveFadeController;
    // Plain (non-blurred) top fade: same zone as the progressive path, solid
    // theme color instead of a live capture. Shown when the toggle is on but
    // the progressive path is off.
    private org.telegram.ui.Components.FadeHeaderButtonGlass fadeButtonGlass;
    private ChatActivityFadeView plainTopFadeView;
    private int lastPlainFadeColor = Integer.MIN_VALUE;

    private final ArrayList<RectF> iBlur3Positions = new ArrayList<>();
    private final RectF iBlur3PositionActionBar = new RectF();

    {
        iBlur3Positions.add(iBlur3PositionActionBar);
    }

    protected void blur3_InvalidateBlur() {
        if (materialHeaderEnabled() || Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || scrollableViewNoiseSuppressor == null) {
            return;
        }

        final int additionalList = AndroidUtilities.dp(48);
        iBlur3PositionActionBar.set(0, -additionalList, fragmentView.getMeasuredWidth(), actionBarContainer.getMeasuredHeight() + additionalList);

        scrollableViewNoiseSuppressor.setupRenderNodes(iBlur3Positions, 1);
        scrollableViewNoiseSuppressor.invalidateResultRenderNodes(iBlur3Capture, fragmentView.getMeasuredWidth(), fragmentView.getMeasuredHeight());
    }

    protected static class TextSettingsCellFactory extends UItem.UItemFactory<TextSettingsCell> {
        static {
            setup(new TextSettingsCellFactory());
        }

        private Theme.ResourcesProvider resourcesProvider;

        @Override
        public TextSettingsCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            this.resourcesProvider = resourcesProvider;
            return new TextSettingsCell(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            var textCell = (TextSettingsCell) view;
            textCell.setCanDisable(true);
            if (!TextUtils.isEmpty(item.textValue)) {
                var valueText = textCell.getValueTextView().getText();
                var animated = !TextUtils.isEmpty(valueText) && !item.textValue.equals(valueText);
                textCell.setTextAndValue(item.text, item.textValue, animated, divider);
            } else {
                textCell.setText(item.text, divider);
            }
            if (item.red) {
                textCell.setTextColor(Theme.getColor(Theme.key_text_RedRegular, resourcesProvider));
            } else if (item.accent) {
                textCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText2, resourcesProvider));
            } else {
                textCell.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            }
            // Apply item.enabled to the cell view itself. Without this, items
            // marked disabled via UItem#setEnabled(false) only stop the click
            // handler in onItemClick (UniversalAdapter#isEnabled returns
            // false), but the cell View stays enabled — so the press ripple
            // still plays and previously-faded alpha can "stick" on a recycled
            // holder. setEnabled(false) on the cell fades text/value to 50%
            // alpha (because setCanDisable(true) was set above) and prevents
            // touch feedback.
            textCell.setEnabled(item.enabled, null);
        }

        public static UItem of(int id, CharSequence title) {
            return of(id, title, null);
        }

        public static UItem of(int id, CharSequence title, CharSequence value) {
            var item = UItem.ofFactory(TextSettingsCellFactory.class);
            item.id = id;
            item.text = title;
            item.textValue = value;
            return item;
        }
    }

    protected static class PillButtonCellFactory extends UItem.UItemFactory<PillButtonCell> {
        static {
            setup(new PillButtonCellFactory());
        }

        @Override
        public PillButtonCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new PillButtonCell(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            PillButtonCell cell = (PillButtonCell) view;
            cell.setText(item.text);
        }

        public static UItem of(int id, CharSequence title) {
            var item = UItem.ofFactory(PillButtonCellFactory.class);
            item.id = id;
            item.text = title;
            return item;
        }
    }

    protected static class PillButtonCell extends FrameLayout {
        private final TextView textView;

        public PillButtonCell(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            int accentColor = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
            int textColor = org.telegram.ui.ActionBar.Theme.isCurrentThemeDark() ? 0xff000000 : 0xffffffff;

            textView = new TextView(context);
            textView.setTextSize(15);
            textView.setTextColor(textColor);
            textView.setTypeface(zxc.iconic.xenon.helpers.TypefaceHelper.createRoundedTypeface(500, false));
            textView.setGravity(android.view.Gravity.CENTER);
            textView.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(24), accentColor));
            textView.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(12), AndroidUtilities.dp(24), AndroidUtilities.dp(12));
            textView.setElevation(AndroidUtilities.dp(2));

            addView(textView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, android.view.Gravity.CENTER));
            setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(8));
        }

        public void setText(CharSequence text) {
            textView.setText(text);
        }
    }

    protected static class TextDetailSettingsCellFactory extends UItem.UItemFactory<TextDetailSettingsCell> {
        static {
            setup(new TextDetailSettingsCellFactory());
        }

        @Override
        public TextDetailSettingsCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new TextDetailSettingsCell(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            var textCell = (TextDetailSettingsCell) view;
            textCell.setMultilineDetail(true);
            textCell.setTextAndValue(item.text, item.subtext, divider);
            textCell.setEnabled(item.enabled);
        }

        public static UItem of(int id, CharSequence title, CharSequence subtitle) {
            var item = UItem.ofFactory(TextDetailSettingsCellFactory.class);
            item.id = id;
            item.text = title;
            item.subtext = subtitle;
            return item;
        }
    }

    protected static class TextCheckbox2CellFactory extends UItem.UItemFactory<TextCheckbox2Cell> {
        static {
            setup(new TextCheckbox2CellFactory());
        }

        @Override
        public TextCheckbox2Cell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new TextCheckbox2Cell(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            var textCell = (TextCheckbox2Cell) view;
            if (TextUtils.isEmpty(item.subtext)) {
                textCell.setTextAndCheck(item.text, item.checked, divider);
            } else {
                textCell.setTextAndValueAndCheck(item.text, item.subtext, item.checked, true, divider);
            }
        }

        public static UItem of(int id, CharSequence title) {
            var item = UItem.ofFactory(TextCheckbox2CellFactory.class);
            item.id = id;
            item.text = title;
            return item;
        }
    }

    /**
     * Reusable integer slider row backed by {@link AltSeekbar}, styled to match
     * the system PowerSaverSlider used in Lite Mode. Use {@link SeekbarCellFactory#of}
     * to build a {@link UItem} for it. The cell holds a single AltSeekbar and
     * only updates its value on rebind, so theme/list refreshes don't tear down
     * the underlying SeekBarView.
     */
    public static class SeekbarConfig {
        public final String title;
        public final String left;
        public final String right;
        public final int min;
        public final int max;
        public final int step;
        public final AltSeekbar.OnDrag onDrag;
        public String description;
        public java.util.function.Function<Integer, String> valueFormatter;
        public java.util.function.Consumer<Boolean> onDragStateChanged;

        public SeekbarConfig(String title, String left, String right, int min, int max, AltSeekbar.OnDrag onDrag) {
            this(title, left, right, min, max, 1, onDrag);
        }

        public SeekbarConfig(String title, String left, String right, int min, int max, int step, AltSeekbar.OnDrag onDrag) {
            this.title = title;
            this.left = left;
            this.right = right;
            this.min = min;
            this.max = max;
            this.step = step;
            this.onDrag = onDrag;
        }

        public SeekbarConfig setDescription(String desc) {
            this.description = desc;
            return this;
        }
    }

    protected static class SeekbarCellFactory extends UItem.UItemFactory<SeekbarCell> {
        static {
            setup(new SeekbarCellFactory());
        }

        private Theme.ResourcesProvider resourcesProvider;

        @Override
        public SeekbarCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            this.resourcesProvider = resourcesProvider;
            return new SeekbarCell(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            ((SeekbarCell) view).bind((SeekbarConfig) item.object, item.intValue, item);
        }

        public static UItem of(int id, SeekbarConfig config, int currentValue) {
            var item = UItem.ofFactory(SeekbarCellFactory.class);
            item.id = id;
            // text is required by NekoSettingsActivity.createSearchArray() —
            // it indexes every UItem with a non-empty slug by item.text.toString()
            // and would NPE on a null CharSequence here.
            item.text = config.title;
            item.object = config;
            item.intValue = currentValue;
            return item;
        }

        @Override
        public boolean isClickable() {
            return false;
        }
    }

    protected static class SeekbarCell extends FrameLayout {
        private final Theme.ResourcesProvider resourcesProvider;
        private AltSeekbar seekbar;

        public SeekbarCell(Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;
        }

        public void bind(SeekbarConfig config, int currentValue, UItem item) {
            if (seekbar != null) {
                removeView(seekbar);
            }
            seekbar = new AltSeekbar(getContext(), progress -> {
                // Recycled holders rebind from this UItem, not from the config
                // field that the external callback saves. Keep both current.
                item.intValue = Math.round(progress);
                config.onDrag.run(progress);
            }, config.min, config.max,
                    config.title, config.left, config.right, resourcesProvider, config.description);
            seekbar.setValueFormatter(config.valueFormatter);
            seekbar.setOnDragStateChanged(config.onDragStateChanged);
            seekbar.setDefaultValue(currentValue);
            seekbar.setStep(config.step);
            addView(seekbar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            seekbar.setValue(currentValue);
        }
    }

    public interface InfoCheckCallback {
        void onInfoClick();
    }

    protected static class InfoCheckCell extends FrameLayout {
        private final TextCheckCell checkCell;
        private final CheckBoxCell roundCheckCell;
        private final View infoButton;
        private InfoCheckCallback callback;
        private final Theme.ResourcesProvider resourcesProvider;

        public InfoCheckCell(Context context, Theme.ResourcesProvider resourcesProvider) {
            this(context, resourcesProvider, false);
        }

        public InfoCheckCell(Context context, Theme.ResourcesProvider resourcesProvider, boolean roundCheckbox) {
            super(context);
            this.resourcesProvider = resourcesProvider;
            checkCell = roundCheckbox ? null : new TextCheckCell(context, resourcesProvider);
            roundCheckCell = roundCheckbox ? new CheckBoxCell(context, CheckBoxCell.TYPE_CHECK_BOX_ROUND, 21, true, resourcesProvider) : null;
            if (roundCheckCell != null) {
                roundCheckCell.getCheckBoxRound().setColor(Theme.key_switch2TrackChecked, Theme.key_radioBackground, Theme.key_checkboxCheck);
            }
            View control = roundCheckbox ? roundCheckCell : checkCell;
            control.setLayoutParams(new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
            addView(control);

            infoButton = new View(context) {
                @Override
                protected void onDraw(Canvas canvas) {
                    super.onDraw(canvas);
                    int cx = getWidth() / 2;
                    int cy = getHeight() / 2;
                    int r = AndroidUtilities.dp(11);
                    Paint circlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    circlePaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider));
                    circlePaint.setAlpha(60);
                    canvas.drawCircle(cx, cy, r, circlePaint);
                    Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                    textPaint.setColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider));
                    textPaint.setTextSize(AndroidUtilities.dp(13));
                    textPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                    textPaint.setTextAlign(Paint.Align.CENTER);
                    float ty = cy - (textPaint.descent() + textPaint.ascent()) / 2;
                    canvas.drawText("?", cx, ty, textPaint);
                }
            };
            infoButton.setWillNotDraw(false);
            FrameLayout.LayoutParams infoLp = new FrameLayout.LayoutParams(AndroidUtilities.dp(28), AndroidUtilities.dp(28));
            infoLp.gravity = Gravity.CENTER_VERTICAL | Gravity.LEFT;
            addView(infoButton, infoLp);
        }

        @Override
        public boolean onInterceptTouchEvent(MotionEvent ev) {
            if (infoButton.getVisibility() == VISIBLE && callback != null) {
                float x = ev.getX();
                float y = ev.getY();
                if (x >= infoButton.getLeft() && x <= infoButton.getRight() &&
                    y >= infoButton.getTop() && y <= infoButton.getBottom()) {
                    return true;
                }
            }
            return super.onInterceptTouchEvent(ev);
        }

        @Override
        public boolean onTouchEvent(MotionEvent ev) {
            if (infoButton.getVisibility() == VISIBLE && callback != null) {
                float x = ev.getX();
                float y = ev.getY();
                if (x >= infoButton.getLeft() && x <= infoButton.getRight() &&
                    y >= infoButton.getTop() && y <= infoButton.getBottom()) {
                    if (ev.getAction() == MotionEvent.ACTION_UP) {
                        callback.onInfoClick();
                    }
                    return true;
                }
            }
            return super.onTouchEvent(ev);
        }

        public void setTextAndCheck(CharSequence text, boolean checked, boolean divider, InfoCheckCallback cb) {
            callback = cb;
            if (roundCheckCell != null) {
                roundCheckCell.setText(text, "", checked, divider, roundCheckCell.itemId != 0);
            } else {
                checkCell.setTextAndCheck(text, checked, divider);
            }
            infoButton.setVisibility(cb != null ? VISIBLE : GONE);
            if (cb != null) {
                Paint paint = new Paint();
                paint.setTextSize(AndroidUtilities.dp(16));
                float textWidth = paint.measureText(text.toString());
                FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) infoButton.getLayoutParams();
                int padding = AndroidUtilities.dp(roundCheckCell != null ? 60 : 21);
                int offset = padding + (int) textWidth + AndroidUtilities.dp(6);
                if (LocaleController.isRTL) {
                    lp.gravity = Gravity.CENTER_VERTICAL | Gravity.RIGHT;
                    lp.rightMargin = offset;
                    lp.leftMargin = 0;
                } else {
                    lp.gravity = Gravity.CENTER_VERTICAL | Gravity.LEFT;
                    lp.leftMargin = offset;
                    lp.rightMargin = 0;
                }
                infoButton.setLayoutParams(lp);
            }
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            if (roundCheckCell != null) {
                heightMeasureSpec = MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(50), MeasureSpec.EXACTLY);
            }
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }

        @Override
        protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
            super.onLayout(changed, left, top, right, bottom);
            if (roundCheckCell != null && infoButton.getVisibility() == VISIBLE) {
                org.telegram.ui.Components.AnimatedTextView label = roundCheckCell.getAnimatedTextView();
                int textWidth = Math.min(label.getWidth(), (int) Math.ceil(label.getDrawable().getCurrentWidth()));
                int buttonWidth = infoButton.getMeasuredWidth();
                int buttonLeft = LocaleController.isRTL
                        ? Math.max(AndroidUtilities.dp(8), label.getRight() - textWidth - AndroidUtilities.dp(6) - buttonWidth)
                        : Math.min(getWidth() - AndroidUtilities.dp(8) - buttonWidth, label.getLeft() + textWidth + AndroidUtilities.dp(6));
                int buttonTop = (getHeight() - infoButton.getMeasuredHeight()) / 2;
                infoButton.layout(buttonLeft, buttonTop, buttonLeft + buttonWidth, buttonTop + infoButton.getMeasuredHeight());
            }
        }

        public void setChecked(boolean checked) {
            if (roundCheckCell != null) roundCheckCell.setChecked(checked, true);
            else checkCell.setChecked(checked);
        }

        public boolean isCheckEnabled() {
            return roundCheckCell != null ? roundCheckCell.isEnabled() : checkCell.isEnabled();
        }
    }

    protected static class InfoCheckCellFactory extends UItem.UItemFactory<InfoCheckCell> {
        static {
            setup(new InfoCheckCellFactory());
        }

        private Theme.ResourcesProvider resourcesProvider;

        @Override
        public InfoCheckCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            this.resourcesProvider = resourcesProvider;
            return new InfoCheckCell(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            InfoCheckCell cell = (InfoCheckCell) view;
            InfoCheckCallback cb = (InfoCheckCallback) item.object;
            cell.setTextAndCheck(item.text, item.checked, divider, cb);
        }

        public static UItem of(int id, CharSequence text, boolean checked, InfoCheckCallback callback) {
            var item = UItem.ofFactory(InfoCheckCellFactory.class);
            item.id = id;
            item.text = text;
            item.checked = checked;
            item.object = callback;
            return item;
        }
    }

    protected static class InfoCheckboxCellFactory extends UItem.UItemFactory<InfoCheckCell> {
        static {
            setup(new InfoCheckboxCellFactory());
        }

        @Override
        public InfoCheckCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new InfoCheckCell(context, resourcesProvider, true);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            InfoCheckCell cell = (InfoCheckCell) view;
            cell.roundCheckCell.setPad(item.pad);
            cell.setTextAndCheck(item.text, item.checked, divider, (InfoCheckCallback) item.object);
            cell.roundCheckCell.itemId = item.id;
        }

        public static UItem of(int id, CharSequence text, boolean checked, InfoCheckCallback callback) {
            UItem item = UItem.ofFactory(InfoCheckboxCellFactory.class);
            item.id = id;
            item.text = text;
            item.checked = checked;
            item.object = callback;
            return item;
        }
    }

}
