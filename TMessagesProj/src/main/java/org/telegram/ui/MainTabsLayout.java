package org.telegram.ui;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.lerp;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.dynamicanimation.animation.FloatPropertyCompat;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;


import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedLinearLayout;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.glass.GlassTabView;

import zxc.iconic.xenon.helpers.MainTabsUiHelper;
import zxc.iconic.xenon.helpers.LiquidNavigationMath;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawableRenderNode;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSource;

import java.util.HashSet;
import java.util.Set;

import me.vkryl.android.animator.ListAnimator;
import me.vkryl.android.util.ClickHelper;

@SuppressLint("ViewConstructor")
public class MainTabsLayout extends AnimatedLinearLayout {

    private final Theme.ResourcesProvider resourcesProvider;

    public MainTabsLayout(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        setClipChildren(false);
        setClipToPadding(false);
    }

    private static final float[] PASS_TEXT_SIZES_DP = {12f, 12f, 10f};
    private static final int[] PASS_PADDINGS_DP = {16, 8, 4};

    private int maxWidthPx;
    private int navigationContentInset;
    private boolean swipeSelectionEnabled = true;

    public void setSwipeSelectionEnabled(boolean enabled) {
        if (!enabled && swipeSelectionEnabled) resetLiquidGesture();
        swipeSelectionEnabled = enabled;
    }

    public void setMaxWidth(int maxWidthPx) {
        if (this.maxWidthPx != maxWidthPx) {
            this.maxWidthPx = maxWidthPx;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        final int height = MeasureSpec.getSize(heightMeasureSpec);
        final int tabHeight = height - getPaddingTop() - getPaddingBottom();

        if (maxWidthPx > 0 && width > maxWidthPx) {
            width = maxWidthPx;
        }

        final int maxTotalWidthForTabs = width - getPaddingLeft() - getPaddingRight();
        final int minTotalWidthForTabs = Math.min(dp(320), maxTotalWidthForTabs);

        final boolean m3 = zxc.iconic.xenon.helpers.MainTabsUiHelper.isMaterial3NavigationBar();
        if (m3) {
            measureMaterial3Tabs(width, height, tabHeight);
            return;
        }
        navigationContentInset = 0;
        measureTabTexts(12f);
        // Preserve the original compact width, then distribute it into equal slots.
        float preferredWidth = 0;
        for (int i = 0; i < getChildCount(); i++) {
            if (isViewVisible(getChildAt(i))) preferredWidth += tabsTextWidth[i] + dp(32);
        }
        int contentWidth = Math.min(maxTotalWidthForTabs, Math.round(preferredWidth));
        if (!zxc.iconic.xenon.NekoConfig.dynamicTabSize && visibleChildCount > 0) {
            contentWidth = Math.max(minTotalWidthForTabs, contentWidth);
        }
        // Every label must fit its own equal slot, not the sum of all label widths.
        int chosenPass = PASS_TEXT_SIZES_DP.length - 1;
        for (int pass = 0; pass < PASS_TEXT_SIZES_DP.length; pass++) {
            measureTabTexts(PASS_TEXT_SIZES_DP[pass]);
            if (biggestTabTextWidth + dp(PASS_PADDINGS_DP[pass] * 2)
                    <= contentWidth / Math.max(1, visibleChildCount)) {
                chosenPass = pass;
                break;
            }
        }
        applyPassTextSize(chosenPass);
        int left = 0;
        int remaining = visibleChildCount;
        int remainingWidth = contentWidth;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            int itemWidth = isViewVisible(child) && remaining > 0 ? remainingWidth / remaining : 0;
            tabsWidth[i] = itemWidth;
            tabsLeftPos[i] = left;
            if (isViewVisible(child)) {
                left += itemWidth;
                remainingWidth -= itemWidth;
                remaining--;
            }
            child.measure(MeasureSpec.makeMeasureSpec(itemWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.max(0, tabHeight), MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(left + getPaddingLeft() + getPaddingRight(), height);
        calculateTotalSizesAfterMeasure();
    }

    private void measureMaterial3Tabs(int width, int height, int tabHeight) {
        navigationContentInset = 0;
        final int available = Math.max(0, width - getPaddingLeft() - getPaddingRight());
        final boolean horizontal = MainTabsUiHelper.useHorizontalItems(available);
        final float textSize = horizontal ? 14f : 12f;
        measureTabTexts(textSize);
        if (visibleChildCount == 0) {
            setMeasuredDimension(width, height);
            calculateTotalSizesAfterMeasure();
            return;
        }
        int contentWidth = available;
        if (horizontal && visibleChildCount < 6) {
            // ShortNavigationBar Centered arrangement: grow for long labels.
            float padding = (100 - 10 * (visibleChildCount + 3)) / 200f;
            contentWidth = Math.min(available, Math.max(Math.round(available * (1 - 2 * padding)),
                    (biggestTabTextWidth + dp(60)) * visibleChildCount));
        }
        navigationContentInset = (available - contentWidth) / 2;
        int left = getPaddingLeft() + navigationContentInset;
        int remaining = visibleChildCount;
        int remainingWidth = contentWidth;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (!isViewVisible(child)) {
                tabsWidth[i] = 0;
                continue;
            }
            int itemWidth = remainingWidth / remaining;
            tabsWidth[i] = itemWidth;
            tabsLeftPos[i] = left;
            left += itemWidth;
            remainingWidth -= itemWidth;
            remaining--;
            if (child instanceof GlassTabView) {
                ((GlassTabView) child).setMaterial3Horizontal(horizontal);
                ((GlassTabView) child).setTextSizeDp(textSize);
            }
            child.measure(MeasureSpec.makeMeasureSpec(itemWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(Math.max(0, tabHeight), MeasureSpec.EXACTLY));
        }
        setMeasuredDimension(width, height);
        calculateTotalSizesAfterMeasure();
    }

    public interface Tab {
        float measureTextWidth();
        default float measureTextWidth(float textSizeDp) { return measureTextWidth(); }
        default void setTextSizeDp(float textSizeDp) {}
    }



    // fills tabsTextWidth[] and return visible child count;

    private float[] tabsTextWidth;
    private float[] tabsTextWidthWithMargin;
    private int[] tabsWeight;
    private int[] tabsWidth;

    private int[] tabsLeftPos;


    private int visibleChildCount;
    private int biggestTabTextWidth;

    private void measureTabTexts(float textSizeDp) {
        final int childCount = getChildCount();
        if (tabsTextWidth == null || tabsTextWidth.length < childCount) {
            tabsTextWidth = new float[childCount];
            tabsTextWidthWithMargin = new float[childCount];
            tabsWeight = new int[childCount];
            tabsLeftPos = new int[childCount];
            tabsWidth = new int[childCount];
        }

        float maxTabWidthF = 0;
        int index = 0;

        for (int a = 0; a < childCount; a++) {
            final View child = getChildAt(a);
            if (!isViewVisible(child)) {
                tabsTextWidth[a] = -1;
                continue;
            }

            final float tabWidth;
            if (child instanceof MainTabsLayout.Tab) {
                tabWidth = ((MainTabsLayout.Tab) child).measureTextWidth(textSizeDp);
            } else {
                tabWidth = 0;
            }

            tabsTextWidth[a] = tabWidth;
            maxTabWidthF = Math.max(maxTabWidthF, tabWidth);
            index++;
        }

        biggestTabTextWidth = (int) Math.ceil(maxTabWidthF);
        visibleChildCount = index;
    }

    private void applyPassTextSize(int pass) {
        final float textSizeDp = PASS_TEXT_SIZES_DP[pass];
        for (int a = 0, N = getChildCount(); a < N; a++) {
            final View child = getChildAt(a);
            if (child instanceof MainTabsLayout.Tab) {
                ((MainTabsLayout.Tab) child).setTextSizeDp(textSizeDp);
            }
        }
    }

    @Override
    protected void setChildVisibilityFactor(View view, float factor) {
        final float s = lerp(0.7f, 1f, factor);
        view.setAlpha(factor);
        view.setScaleX(s);
        view.setScaleY(s);
        if (MainTabsUiHelper.isMaterial3NavigationBar()) {
            view.setTranslationX(view.getTranslationX() + navigationContentInset);
        }
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        super.onLayout(changed, l, t, r, b);
        checkVisualWidth();
        float slot = getChildrenCenterSpan() / Math.max(1, visibleChildCount - 1);
        selectedTabPositionX.setMinimumVisibleChange(Math.max(0.001f, slot * 0.001f / 0.75f));
    }

    @Override
    protected void onItemsChanged() {
        super.onItemsChanged();
        checkVisualWidth();
    }

    private void checkVisualWidth() {
        for (int a = 0, N = getEntriesCount(); a < N; a++) {
            final ListAnimator.Entry<Holder> entry = getEntry(a);
            final float width = entry.getRectF().width();
            ((GlassTabView) entry.item.view).setVisualWidth(width);
        }
    }








    public void setTabSelected(View tab, boolean animated) {
        View previous = findSelectedTab();
        if (!animated && drawCustomSelector && !isInLongPress) resetLiquidGesture();
        if (swipeSelectionEnabled && !MainTabsUiHelper.isMaterial3NavigationBar()
                && !isInLongPress && animated && isAttachedToWindow()
                && previous != null && tab != null && previous != tab && isViewVisible(tab)) {
            if (!drawCustomSelector) animatedLongSelectedViewCenterX = getCenterX(previous);
            AndroidUtilities.cancelRunOnUIThread(restoreDrawSelector);
            setSkipDrawSelector(true);
            // Retarget the existing springs even while the previous capsule is shrinking.
            animateLiquidPress();
            selectedTabPositionX.animateToFinalPosition(getCenterX(tab));
            AndroidUtilities.runOnUIThread(restoreDrawSelector, 16);
        }
        for (int a = 0, N = getChildCount(); a < N; a++) {
            final View child = getChildAt(a);
            if (child instanceof GlassTabView) {
                ((GlassTabView) child).setSelected(child == tab,
                        animated && !(drawCustomSelector && !MainTabsUiHelper.isMaterial3NavigationBar()));
            }
        }
    }

    private View findSelectedTab() {
        for (int a = 0, N = getChildCount(); a < N; a++) {
            final View child = getChildAt(a);
            if (child.getVisibility() != View.VISIBLE) {
                continue;
            }

            if (child instanceof GlassTabView) {
                if (((GlassTabView) child).isTabSelected()) {
                    return child;
                }
            }
        }
        return null;
    }

    // Reference release: await a frame, then shrink once within 2.5% of the tab range.
    private final Runnable restoreDrawSelector = new Runnable() {
        @Override public void run() {
            if (isInLongPress || liquidTouchDown) return;
            if (!liquidReleasing) {
                float threshold = Math.max(0.1f, getChildrenCenterSpan() * 0.025f);
                if (Math.abs(animatedLongSelectedViewCenterX
                        - selectedTabPositionX.getSpring().getFinalPosition()) > threshold) {
                    AndroidUtilities.runOnUIThread(this, 16);
                    return;
                }
                liquidReleasing = true;
                liquidPress.animateToFinalPosition(0f);
                liquidScaleX.animateToFinalPosition(1f);
                liquidScaleY.animateToFinalPosition(1f);
            }
            if (selectedTabPositionX.isRunning() || liquidPress.isRunning()
                    || liquidScaleX.isRunning() || liquidScaleY.isRunning()) {
                AndroidUtilities.runOnUIThread(this, 16);
            } else {
                setSkipDrawSelector(false);
            }
        }
    };

    private boolean drawCustomSelector;
    private void setSkipDrawSelector(boolean skipDrawSelector) {
        drawCustomSelector = skipDrawSelector;
        if (skipDrawSelector) liquidReleasing = false;
        if (drawCustomSelector) {
            selectorPaint.setColor(Theme.multAlpha(Theme.getColor(Theme.key_glass_tabSelected, resourcesProvider), 0.09f));
        }
        for (int a = 0, N = getChildCount(); a < N; a++) {
            final View child = getChildAt(a);
            if (child.getVisibility() != View.VISIBLE) {
                continue;
            }

            if (child instanceof GlassTabView) {
                ((GlassTabView) child).setSkipDrawSelector(skipDrawSelector);
            }
        }
        invalidate();
    }







    private final RectF liquidBounds = new RectF();
    private final Path liquidClip = new Path();
    private final Paint liquidLight = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix liquidLightMatrix = new Matrix();
    private final RadialGradient liquidSpotlight = new RadialGradient(0, 0, 1,
            Color.WHITE, Color.TRANSPARENT, Shader.TileMode.CLAMP);
    private BlurredBackgroundDrawable liquidLens;
    private BlurredBackgroundDrawable liquidBackdrop;
    private boolean liquidTouchDown;
    private float liquidPressProgress, liquidWidthScale = 1f, liquidHeightScale = 1f;
    private float liquidVelocity, gestureStartX;
    private View gestureInitialTab;
    private boolean liquidReleasing;

    public void setLiquidLens(BlurredBackgroundDrawable drawable) {
        if (liquidLens != null) liquidLens.setCallback(null);
        liquidBackdrop = drawable; // Its factory tracks the panel's position in the content source.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            BlurredBackgroundSource combined = new BlurredBackgroundSource() {
                @Override public BlurredBackgroundDrawable createDrawable() {
                    return new BlurredBackgroundDrawableRenderNode(this);
                }
                @Override public void prepareToDraw() {
                    liquidBackdrop.getSource().prepareToDraw();
                }
                @Override public void draw(Canvas canvas, float l, float t, float r, float b) {
                    drawLiquidCapture(canvas, l, t, r, b);
                }
            };
            BlurredBackgroundDrawableRenderNode lens = new BlurredBackgroundDrawableRenderNode(combined);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                lens.setLiquidGlassEffectAllowed(true); // Fixed slider value of 50; still respects LiteMode.
            }
            lens.setThickness(dp(10));
            lens.setIntensity(1f);
            liquidLens = lens;
        } else {
            liquidLens = drawable;
        }
        liquidLens.setCallback(this);
    }

    private void drawLiquidCapture(Canvas canvas, float l, float t, float r, float b) {
        float ox = liquidBackdrop.getSourceOffsetX(), oy = liquidBackdrop.getSourceOffsetY();
        canvas.save();
        canvas.translate(-ox, -oy);
        liquidBackdrop.getSource().draw(canvas, l + ox, t + oy, r + ox, b + oy);
        canvas.restore();
        // Sample the screen behind the bar. Capturing the dark panel here hides
        // that backdrop and makes the refracting capsule look like a flat fill.
        drawLiquidForeground(canvas, LiquidNavigationMath.contentScale(liquidPressProgress));
    }

    private void animateLiquidPress() {
        liquidReleasing = false;
        liquidPress.getSpring().setStiffness(1000f);
        liquidScaleX.getSpring().setStiffness(250f).setDampingRatio(0.6f);
        liquidScaleY.getSpring().setStiffness(250f).setDampingRatio(0.7f);
        liquidPress.animateToFinalPosition(1f);
        liquidScaleX.animateToFinalPosition(78f / 56f);
        liquidScaleY.animateToFinalPosition(78f / 56f);
    }

    private void beginLiquidPress(float x, float y) {
        if (MainTabsUiHelper.isMaterial3NavigationBar()) return;
        View selected = findSelectedTab();
        View touched = findChildUnder(this, x, y);
        if (selected == null || touched == null || tabsWithIgnoreClick.contains(touched)) return;
        gestureStartX = x;
        // In the reference only the current capsule owns the press gesture.
        // Other slots launch the selection spring from their click on ACTION_UP.
        if (touched != selected) return;
        gestureInitialTab = selected;
        gestureStartX = x;
        liquidTouchDown = true;
        AndroidUtilities.cancelRunOnUIThread(restoreDrawSelector);
        if (!drawCustomSelector) {
            selectedTabPositionX.cancel();
            animatedLongSelectedViewCenterX = getCenterX(selected);
            selectedTabPositionX.setStartValue(animatedLongSelectedViewCenterX).setStartVelocity(0f);
        }
        setSkipDrawSelector(true);
        animateLiquidPress();
        selectedTabPositionX.getSpring().setStiffness(1000f);
        selectedTabPositionX.animateToFinalPosition(getCenterX(touched));
    }

    @Override protected boolean verifyDrawable(@NonNull Drawable drawable) {
        return drawable == liquidLens || super.verifyDrawable(drawable);
    }

    private SpringAnimation liquidSpring(float initial, float damping, float stiffness,
            java.util.function.Consumer<Float> update) {
        SpringAnimation spring = new SpringAnimation(new FloatValueHolder(initial));
        spring.setSpring(new SpringForce(initial).setDampingRatio(damping).setStiffness(stiffness));
        spring.setMinimumVisibleChange(0.001f / 0.75f);
        spring.addUpdateListener((animation, value, velocity) -> { update.accept(value); invalidate(); });
        return spring;
    }

    private final SpringAnimation liquidVelocitySpring = liquidSpring(0f, 0.5f, 300f, v -> liquidVelocity = v);
    private final SpringAnimation liquidPress = liquidSpring(0f, 1f, 1000f, v -> liquidPressProgress = v);
    private final SpringAnimation liquidScaleX = liquidSpring(1f, 0.6f, 250f, v -> liquidWidthScale = v);
    private final SpringAnimation liquidScaleY = liquidSpring(1f, 0.7f, 250f, v -> liquidHeightScale = v);

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (!drawCustomSelector || MainTabsUiHelper.isMaterial3NavigationBar()) {
            super.draw(canvas);
            return;
        }
        final float progress = LiquidNavigationMath.clamp(liquidPressProgress, 0f, 1f);
        final float x = animatedLongSelectedViewCenterX;
        final float cy = (getPaddingTop() + getHeight() - getPaddingBottom()) / 2f;
        final float width = getInterpolatedWidthByX(x, this);
        final float height = Math.max(0, getHeight() - getPaddingTop() - getPaddingBottom());
        final float sx = LiquidNavigationMath.lensScaleX(liquidWidthScale, liquidVelocity, 1f);
        final float sy = LiquidNavigationMath.lensScaleY(liquidHeightScale, liquidVelocity, 1f);
        liquidBounds.set(x - width * sx / 2, cy - height * sy / 2,
                x + width * sx / 2, cy + height * sy / 2);
        float radius = Math.min(liquidBounds.width(), liquidBounds.height()) / 2f;
        liquidClip.rewind();
        liquidClip.addRoundRect(liquidBounds, radius, radius, Path.Direction.CW);
        drawLiquidBarBackground(canvas);
        canvas.save();
        canvas.clipPath(liquidClip, android.graphics.Region.Op.DIFFERENCE);
        float panelScale = LiquidNavigationMath.panelScale(progress, getWidth(), dp(16));
        canvas.scale(panelScale, panelScale, getWidth() / 2f, cy);
        super.dispatchDraw(canvas);
        canvas.restore();
        final boolean glassEnabled = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && canvas.isHardwareAccelerated() && liquidLens != null
                && org.telegram.messenger.LiteMode.isEnabled(org.telegram.messenger.LiteMode.FLAG_LIQUID_GLASS);
        // Neutral reference surface: no accent/white fill layered over the panel.
        boolean dark = Theme.isCurrentThemeDark();
        selectorPaint.setColor(dark ? Color.WHITE : Color.BLACK);
        selectorPaint.setAlpha(Math.round(25.5f * (glassEnabled ? 1f - progress : 1f)));
        canvas.drawRoundRect(liquidBounds, radius, radius, selectorPaint);
        if (glassEnabled && progress > 0.001f && width > 0 && height > 0) {
            liquidLens.setBounds(Math.round(liquidBounds.left), Math.round(liquidBounds.top),
                    Math.round(liquidBounds.right), Math.round(liquidBounds.bottom));
            liquidLens.setRadius(radius);
            liquidLens.setAlpha(Math.round(255 * progress));
            if (liquidLens instanceof BlurredBackgroundDrawableRenderNode) {
                ((BlurredBackgroundDrawableRenderNode) liquidLens).invalidateDisplayList();
            }
            liquidLens.draw(canvas);
        }
        selectorPaint.setColor(Color.BLACK);
        selectorPaint.setAlpha(Math.round(255f * 0.03f * progress));
        canvas.drawRoundRect(liquidBounds, radius, radius, selectorPaint);
        if (glassEnabled && progress > 0.001f && width > 0 && height > 0) {
            liquidLightMatrix.setScale(height * 1.5f, height * 1.5f);
            liquidLightMatrix.postTranslate(x, cy);
            liquidSpotlight.setLocalMatrix(liquidLightMatrix);
            liquidLight.setShader(liquidSpotlight);
            liquidLight.setAlpha(Math.round(38 * progress));
            canvas.drawRoundRect(liquidBounds, radius, radius, liquidLight);
            // The lens shader draws the rim using the configured glare angle.
            liquidLight.setShader(null);
        }
        canvas.save();
        canvas.clipPath(liquidClip);
        if (glassEnabled) {
            // Crossfade sharp contents into the captured contents without double drawing.
            int layer = canvas.saveLayerAlpha(liquidBounds, Math.round(255f * (1f - progress)));
            drawLiquidForeground(canvas, LiquidNavigationMath.contentScale(progress));
            canvas.restoreToCount(layer);
        } else {
            drawLiquidForeground(canvas, LiquidNavigationMath.contentScale(progress));
        }
        canvas.restore();
    }

    private void drawLiquidBarBackground(Canvas canvas) {
        Drawable background = getBackground();
        if (background == null) return;
        canvas.save();
        float scale = LiquidNavigationMath.panelScale(liquidPressProgress, getWidth(), dp(16));
        canvas.scale(scale, scale, getWidth() / 2f, getHeight() / 2f);
        background.draw(canvas);
        canvas.restore();
    }

    private void drawLiquidForeground(Canvas canvas, float scale) {
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            canvas.save();
            // Same as LocalLiquidBottomTabScale: each slot has its own center.
            canvas.scale(scale, scale, getCenterX(child), child.getY() + child.getHeight() / 2f);
            drawChild(canvas, child, getDrawingTime());
            canvas.restore();
        }
    }

    private float getChildrenCenterSpan() {
        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            if (child.getVisibility() != View.VISIBLE) continue;
            min = Math.min(min, getCenterX(child));
            max = Math.max(max, getCenterX(child));
        }
        return max >= min ? max - min : 0f;
    }

    final Paint selectorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    final SpringAnimation selectedTabPositionOffsetX = new SpringAnimation(this, new FloatPropertyCompat<MainTabsLayout>("selectedTabPositionOffsetX") {
        @Override
        public float getValue(MainTabsLayout object) {
            return object.animatedLongSelectedViewOffsetX;
        }

        @Override
        public void setValue(MainTabsLayout object, float value) {
            object.animatedLongSelectedViewOffsetX = value;
            object.setTranslationX(value);
            object.invalidate();
        }
    });
    final SpringAnimation selectedTabPositionX = new SpringAnimation(this, new FloatPropertyCompat<MainTabsLayout>("selectedTabPositionX") {
        @Override
        public float getValue(MainTabsLayout object) {
            return object.animatedLongSelectedViewCenterX;
        }

        @Override
        public void setValue(MainTabsLayout object, float value) {
            object.animatedLongSelectedViewCenterX = value;
            object.invalidate();
        }
    });

    {
        selectedTabPositionOffsetX.setSpring(new SpringForce(0f)
            .setStiffness(300f).setDampingRatio(1f));
        selectedTabPositionX.setSpring(new SpringForce(0f)
            .setDampingRatio(1f).setStiffness(1000f));
        selectedTabPositionX.setMinimumVisibleChange(0.1f);
        liquidVelocitySpring.setMinimumVisibleChange(0.01f / 0.75f);
        selectedTabPositionOffsetX.setMinimumVisibleChange(dp(0.5f) / 0.75f);
        selectedTabPositionX.addUpdateListener((animation, value, velocity) -> {
            if (this.isInLongPress) {
                float range = getChildrenCenterSpan();
                liquidVelocitySpring.animateToFinalPosition(range > 0 ? velocity / range : 0f);
            }
        });
    }

    private float animatedLongSelectedViewCenterX;
    private float animatedLongSelectedViewOffsetX;

    private boolean isInLongPress;
    private View lastLongSelectedView;




    public static View findChildUnder(ViewGroup parent, float x, float y) {
        for (int i = parent.getChildCount() - 1; i >= 0; i--) {
            View child = parent.getChildAt(i);

            if (child.getVisibility() != View.VISIBLE) continue;

            if (x >= child.getLeft() && x <= child.getRight()
                    && y >= child.getTop() && y <= child.getBottom()) {
                return child;
            }
        }
        return null;
    }

    private void checkLongMove(float x_, float y, boolean start, boolean end) {
        final float x = clampXToChildrenCenters(x_, this);
        final View found = findNearestVisibleChildByX(x, this);
        if (start && gestureInitialTab == null) gestureInitialTab = findSelectedTab();

        if (!end) {
            selectedTabPositionX.animateToFinalPosition(x);
            selectedTabPositionOffsetX.animateToFinalPosition(
                    LiquidNavigationMath.panelOffset(x_ - gestureStartX, getWidth(), dp(4)));
        }

        if (found != null) {
            lastLongSelectedView = found;
            setTabSelected(found, true);

            if (end) {
                liquidVelocitySpring.animateToFinalPosition(0f);
                selectedTabPositionX.animateToFinalPosition(getCenterX(found));
            }
        }
    }

    private final Set<View> tabsWithIgnoreClick = new HashSet<>();
    public void addTabToIgnoreClick(View v) {
        tabsWithIgnoreClick.add(v);
    }

    private final ClickHelper clickHelper = new ClickHelper(new ClickHelper.Delegate() {
        @Override
        public boolean needClickAt(View view, float x, float y) {
            if (!swipeSelectionEnabled) return false;
            lastLongSelectedView = null;
            final View found = findChildUnder(MainTabsLayout.this, x, y);
            return found != null && !tabsWithIgnoreClick.contains(found);
        }

        @Override public void onClickTouchDown(View view, float x, float y) {
            beginLiquidPress(x, y);
        }

        @Override
        public void onClickAt(View view, float x, float y) {
        }

        @Override
        public boolean needLongPress(float x, float y) {
            return true;
        }

        @Override
        public boolean needLongPressOnSlopMove() {
            return true;
        }

        @Override
        public boolean needCancelTouchBySlopMove() {
            return false;
        }


        @Override
        public boolean onLongPressRequestedAt(View view, float x, float y) {
            checkPivot(view, x, y);
            if (!drawCustomSelector) {
                View selected = findSelectedTab();
                if (selected != null) {
                    selectedTabPositionX.cancel();
                    animatedLongSelectedViewCenterX = getCenterX(selected);
                    selectedTabPositionX.setStartValue(animatedLongSelectedViewCenterX).setStartVelocity(0f);
                }
            }
            isInLongPress = true;
            AndroidUtilities.cancelRunOnUIThread(restoreDrawSelector);
            setSkipDrawSelector(true);
            checkLongMove(x, y, true, false);
            invalidate();
            longTouchStart();
            return true;
        }

        @Override
        public void onLongPressMove(View view, MotionEvent e, float x, float y, float startX, float startY) {
            checkPivot(view, x, y);
            checkLongMove(x, y, false, false);
            invalidate();
        }

        @Override
        public long getLongPressDuration() {
            // Was 0, which made ClickHelper schedule the "hold and slide to switch tabs"
            // gesture essentially instantly on ACTION_DOWN (with slop-cancel disabled via
            // needCancelTouchBySlopMove() == false below). That meant any plain drag/swipe
            // across the tab bar - not just a genuine long-press-and-slide - was interpreted
            // as a request to switch the active tab. Use the standard system long-press
            // timeout so a real hold is required before drag-to-switch engages.
            return android.view.ViewConfiguration.getLongPressTimeout();
        }

        @Override
        public void onLongPressFinish(View view, float x, float y) {
            // ClickHelper.resetTouch also invokes finish on ACTION_CANCEL.
            if (cancellingLiquidTouch) {
                onLongPressCancelled(view, x, y);
                return;
            }
            checkPivot(view, x, y);
            checkLongMove(x, y, false, true);
            isInLongPress = false;
            selectedTabPositionOffsetX.animateToFinalPosition(0f);
            AndroidUtilities.runOnUIThread(restoreDrawSelector, 16);
            if (lastLongSelectedView != null) {
                lastLongSelectedView.performClick();
            }
            lastLongSelectedView = gestureInitialTab = null;
            invalidate();
            longTouchEnd();
        }

        @Override
        public void onLongPressCancelled(View view, float x, float y) {
            checkPivot(view, x, y);
            if (gestureInitialTab != null && gestureInitialTab.getParent() == MainTabsLayout.this) {
                setTabSelected(gestureInitialTab, true);
                liquidVelocitySpring.animateToFinalPosition(0f);
                selectedTabPositionX.animateToFinalPosition(getCenterX(gestureInitialTab));
            }
            isInLongPress = false;
            selectedTabPositionOffsetX.animateToFinalPosition(0f);
            AndroidUtilities.runOnUIThread(restoreDrawSelector, 16);
            gestureInitialTab = null;
            lastLongSelectedView = null;
            invalidate();
            longTouchEnd();
        }

        private void longTouchStart() {
            animateLiquidPress();
        }

        private void longTouchEnd() {
            liquidTouchDown = false;
        }
    });

    private void checkPivot(View view, float x, float y) {
        view.setPivotX(view.getWidth() / 2f);
        view.setPivotY(view.getHeight() / 2f);
    }

    private void resetLiquidGesture() {
        cancellingLiquidTouch = true;
        try {
            clickHelper.cancel(this, 0f, 0f);
        } finally {
            cancellingLiquidTouch = false;
        }
        AndroidUtilities.cancelRunOnUIThread(restoreDrawSelector);
        selectedTabPositionX.cancel();
        selectedTabPositionOffsetX.cancel();
        liquidPress.cancel(); liquidScaleX.cancel(); liquidScaleY.cancel();
        liquidVelocitySpring.cancel();
        liquidVelocitySpring.setStartValue(0f).setStartVelocity(0f);
        liquidPress.setStartValue(0f).setStartVelocity(0f);
        liquidScaleX.setStartValue(1f).setStartVelocity(0f);
        liquidScaleY.setStartValue(1f).setStartVelocity(0f);
        liquidPressProgress = liquidVelocity = animatedLongSelectedViewOffsetX = 0f;
        liquidWidthScale = liquidHeightScale = 1f;
        isInLongPress = false;
        lastLongSelectedView = gestureInitialTab = null;
        liquidTouchDown = false;
        setTranslationX(0f);
        setSkipDrawSelector(false);
    }

    @Override protected void onDetachedFromWindow() {
        resetLiquidGesture();
        super.onDetachedFromWindow();
    }

    private boolean cancellingLiquidTouch;
    private boolean childTouchCancelled;

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) childTouchCancelled = false;
        boolean wasDragging = isInLongPress;
        cancellingLiquidTouch = ev.getActionMasked() == MotionEvent.ACTION_CANCEL;
        boolean finishTouch = ev.getActionMasked() == MotionEvent.ACTION_UP || cancellingLiquidTouch;
        if (finishTouch) liquidTouchDown = false;
        try {
            if (swipeSelectionEnabled) clickHelper.onTouchEvent(this, ev);
        } finally {
            cancellingLiquidTouch = false;
        }
        if (wasDragging || isInLongPress) {
            // Prevent a child ACTION_UP from opening the tab where this drag began.
            if (!childTouchCancelled) {
                MotionEvent cancel = MotionEvent.obtain(ev);
                cancel.setAction(MotionEvent.ACTION_CANCEL);
                super.dispatchTouchEvent(cancel);
                cancel.recycle();
                childTouchCancelled = true;
            }
            return true;
        }
        boolean handled = super.dispatchTouchEvent(ev);
        if (finishTouch && drawCustomSelector) {
            View target = findSelectedTab();
            if (target != null) {
                liquidVelocitySpring.animateToFinalPosition(0f);
                selectedTabPositionX.animateToFinalPosition(getCenterX(target));
            }
            gestureInitialTab = null;
            AndroidUtilities.cancelRunOnUIThread(restoreDrawSelector);
            AndroidUtilities.runOnUIThread(restoreDrawSelector, 16);
        }
        return handled;
    }


    private static float clampXToChildrenCenters(float x, ViewGroup parent) {
        if (parent == null || parent.getChildCount() == 0) {
            return x;
        }

        float min = Float.MAX_VALUE;
        float max = -Float.MAX_VALUE;
        boolean found = false;

        for (int i = 0; i < parent.getChildCount(); i++) {
            View view = parent.getChildAt(i);
            if (view == null || view.getVisibility() != View.VISIBLE) {
                continue;
            }

            float centerX = view.getX() + view.getWidth() * 0.5f;

            if (centerX < min) min = centerX;
            if (centerX > max) max = centerX;

            found = true;
        }

        if (!found) {
            return x;
        }

        if (x < min) return min;
        if (x > max) return max;
        return x;
    }

    @Nullable
    private View findNearestVisibleChildByX(float x, ViewGroup parent) {
        if (parent == null || parent.getChildCount() == 0) {
            return null;
        }

        View nearest = null;
        float nearestDistance = Float.MAX_VALUE;

        for (int i = 0; i < parent.getChildCount(); i++) {
            View view = parent.getChildAt(i);
            if (view == null || view.getVisibility() != View.VISIBLE || tabsWithIgnoreClick.contains(view)) {
                continue;
            }

            float centerX = view.getX() + view.getWidth() * 0.5f;
            float distance = Math.abs(centerX - x);

            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearest = view;
            }
        }

        return nearest;
    }

    private static float getInterpolatedWidthByX(float x, ViewGroup parent) {
        if (parent == null || parent.getChildCount() == 0) {
            return 0f;
        }

        View left = null;
        View right = null;

        for (int i = 0; i < parent.getChildCount(); i++) {
            View view = parent.getChildAt(i);
            if (view == null || view.getVisibility() != View.VISIBLE) {
                continue;
            }

            float centerX = view.getX() + view.getWidth() * 0.5f;

            if (centerX <= x && (left == null || centerX > getCenterX(left))) {
                left = view;
            }

            if (centerX >= x && (right == null || centerX < getCenterX(right))) {
                right = view;
            }
        }

        if (left == null && right == null) {
            return 0f;
        }

        if (left == null) {
            return right.getWidth();
        }

        if (right == null) {
            return left.getWidth();
        }

        float leftX = getCenterX(left);
        float rightX = getCenterX(right);

        if (left == right || leftX == rightX) {
            return left.getWidth();
        }

        float ratio = (x - leftX) / (rightX - leftX);
        return lerp(left.getWidth(), right.getWidth(), ratio);
    }

    private static float getCenterX(View v) {
        return v.getX() + v.getWidth() * 0.5f;
    }

    private static int targetTotalWidth(boolean m3, int maxTotalWidth, int minTotalWidth) {
        return m3 ? maxTotalWidth : minTotalWidth;
    }
}
