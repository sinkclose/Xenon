package zxc.iconic.xenon.helpers;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.view.Gravity;
import android.view.View;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.animation.ValueAnimator;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.FragmentSearchField;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.LiquidTouchEffect;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;

/** Telegram iOS NavigationSearchView: one surface morphs from the loupe into the input. */
public final class IosBottomSearchView extends FrameLayout {
    public interface ProgressListener { void onProgress(float progress); }
    private final Theme.ResourcesProvider provider;
    private final FragmentSearchField field;
    private final View searchButton;
    private final ImageView closeButton;
    private final android.graphics.drawable.Drawable loupe;
    private final BlurredBackgroundDrawable fieldGlass, closeGlass;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF fieldBounds = new RectF(), closeBounds = new RectF();
    private org.telegram.ui.Components.blur3.drawable.LiquidGlassInputGroup glassGroup;
    private final BlurredBackgroundDrawable[] glassMembers;
    private final RectF[] glassBounds = {new RectF(), new RectF()};
    private final LiquidTouchEffect fieldTouch = new LiquidTouchEffect(this);
    private final LiquidTouchEffect closeTouch = new LiquidTouchEffect(this);
    private final LiquidTouchEffect[] touchEffects = {fieldTouch, closeTouch};
    private final float[] glassVisibility = {1f, 0f};
    private ValueAnimator transition;
    private float collapsedOffset;
    private final ProgressListener listener;
    private float progress;
    private boolean active;
    private Runnable closeAction;
    private float lastTextBlur = -1f;

    public IosBottomSearchView(Context context, Theme.ResourcesProvider provider,
            FragmentSearchField field, BlurredBackgroundDrawableViewFactory factory,
            Runnable open, Runnable close, ProgressListener listener) {
        super(context);
        this.provider = provider;
        this.field = field;
        this.listener = listener;
        closeAction = close;
        setWillNotDraw(false);
        setClipChildren(false);
        setClipToPadding(false);
        loupe = context.getDrawable(R.drawable.outline_search_1_24).mutate();
        fieldGlass = factory.create(this, BlurredBackgroundProviderImpl.mainTabs(provider));
        closeGlass = factory.create(this, BlurredBackgroundProviderImpl.mainTabs(provider));
        glassMembers = new BlurredBackgroundDrawable[]{fieldGlass, closeGlass};
        fieldGlass.setPadding(0);
        closeGlass.setPadding(0);
        fieldGlass.setCallback(this);
        closeGlass.setCallback(this);
        AndroidUtilities.removeFromParent(field);
        field.setExternalSearchContainer(true);
        field.setPadding(0, 0, 0, 0);
        field.setTranslationY(0);
        addView(field, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48, Gravity.TOP));
        fieldTouch.setContentView(field);
        searchButton = new View(context);
        searchButton.setContentDescription(LocaleController.getString(R.string.Search));
        searchButton.setOnClickListener(v -> open.run());
        addView(searchButton, LayoutHelper.createFrame(48, 48, Gravity.TOP));
        closeButton = new ImageView(context);
        closeButton.setImageResource(R.drawable.miniplayer_close);
        closeButton.setScaleType(ImageView.ScaleType.CENTER);
        closeButton.setContentDescription(LocaleController.getString(R.string.Close));
        closeButton.setOnClickListener(v -> closeAction.run());
        addView(closeButton, LayoutHelper.createFrame(48, 48, Gravity.TOP));
        closeTouch.setContentView(closeButton);
        fieldTouch.setPressStrength(1.7f);
        closeTouch.setPressStrength(1.7f);
        fieldTouch.setPressSpring(.56f, 260f);
        closeTouch.setPressSpring(.56f, 260f);
        fieldTouch.setDragStrength(1.2f);
        closeTouch.setDragStrength(1.2f);
        applyProgress(0f);
    }

    public static final float COLLAPSED_SIZE_DP = 72f - 2f * 7.666f;

    public static float barOffset(float tabsWidth, float progress, float presence, boolean rtl, float density) {
        float p = clamp(progress);
        return (rtl ? 1f : -1f) * clamp(presence)
                * ((COLLAPSED_SIZE_DP + 8f) / 2f * density * (1f - p) + (tabsWidth + 24f * density) * p);
    }

    public static float collapsedOffset(float availableWidth, float tabsWidth, boolean rtl, float density) {
        float extra = Math.max(0f, (availableWidth - tabsWidth - (COLLAPSED_SIZE_DP + 8f) * density) / 2f);
        return rtl ? extra : -extra;
    }

    public static float clamp(float p) { return Math.max(0f, Math.min(1f, p)); }
    public static float textProgress(float p) { return clamp((p - .2f) / .6f); }
    public static float fieldLeft(float width, float p, boolean rtl, float density) {
        return rtl ? 64f * density * clamp(p) : (width - COLLAPSED_SIZE_DP * density) * (1f - clamp(p));
    }
    public static float fieldWidth(float width, float p, float density) {
        return COLLAPSED_SIZE_DP * density + (Math.max(COLLAPSED_SIZE_DP * density, width - 64f * density) - COLLAPSED_SIZE_DP * density) * clamp(p);
    }
    public void setCollapsedOffset(float offset) {
        if (collapsedOffset == offset) return;
        collapsedOffset = offset;
        requestLayout();
    }
    public static float closeReveal(float p) { return clamp((p - .15f) / .85f); }

    public static float closeLeft(float width, float fieldLeft, float fieldWidth, float p,
            boolean rtl, float density) {
        float inside = rtl ? fieldLeft : fieldLeft + fieldWidth - 48f * density;
        float outside = rtl ? 0f : width - 48f * density;
        return inside + (outside - inside) * closeReveal(p);
    }

    public float getProgress() { return progress; }
    public boolean isActive() { return active; }
    public FragmentSearchField getField() { return field; }
    public void closeSearch() { if (active) closeAction.run(); }

    public void setActive(boolean active, boolean animated) {
        if (this.active == active && animated) return;
        this.active = active;
        if (transition != null) transition.cancel();
        if (animated && isAttachedToWindow()) {
            fieldTouch.pulsePress();
            closeTouch.pulsePress();
            transition = ValueAnimator.ofFloat(progress, active ? 1f : 0f);
            transition.setDuration(500);
            transition.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
            transition.addUpdateListener(animation -> applyProgress((float) animation.getAnimatedValue()));
            transition.start();
        } else {
            applyProgress(active ? 1f : 0f);
        }
        field.setVisibility(active || progress > 0f ? VISIBLE : INVISIBLE);
        field.setEnabled(active);
    }

    private void applyProgress(float value) {
        progress = clamp(value);
        float text = textProgress(progress);
        field.setAlpha(text);
        field.setVisibility(active || progress > 0f ? VISIBLE : INVISIBLE);
        searchButton.setVisibility(active ? GONE : VISIBLE);
        closeButton.setVisibility(progress > 0f ? VISIBLE : INVISIBLE);
        closeButton.setAlpha(text);
        float reveal = closeReveal(progress);
        closeButton.setScaleX(.65f + .35f * reveal);
        closeButton.setScaleY(.65f + .35f * reveal);
        closeButton.setRotation(-45f * (1f - reveal));
        closeButton.setEnabled(active);
        if (Build.VERSION.SDK_INT >= 31) {
            float blur = 10f * AndroidUtilities.density * (1f - text);
            if (lastTextBlur != blur) {
                field.setRenderEffect(blur > .01f ? android.graphics.RenderEffect.createBlurEffect(
                        blur, blur, android.graphics.Shader.TileMode.CLAMP) : null);
                lastTextBlur = blur;
            }
        }
        requestLayout();
        invalidate();
        listener.onProgress(progress);
    }

    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        float density = AndroidUtilities.density;
        float height = (COLLAPSED_SIZE_DP + (48f - COLLAPSED_SIZE_DP) * progress) * density;
        float top = getHeight() - height - ((72f - COLLAPSED_SIZE_DP) / 2f * (1f - progress) + 4f * progress) * density;
        float left = fieldLeft(getWidth(), progress, LocaleController.isRTL, density) + collapsedOffset * (1f - progress);
        float width = Math.min(getWidth(), fieldWidth(getWidth(), progress, density));
        fieldBounds.set(left, top, left + width, top + height);
        fieldTouch.setBounds(left, top, left + width, top + height);
        float closeLeft = closeLeft(getWidth(), left, width, progress, LocaleController.isRTL, density);
        float closeTop = fieldBounds.centerY() - AndroidUtilities.dp(24);
        closeBounds.set(closeLeft, closeTop, closeLeft + AndroidUtilities.dp(48), closeTop + AndroidUtilities.dp(48));
        closeTouch.setBounds(closeBounds.left, closeBounds.top, closeBounds.right, closeBounds.bottom);
        // The actual EditText is laid out in the changing capsule, keeping cursor/hit areas aligned.
        field.measure(MeasureSpec.makeMeasureSpec(Math.max(1, Math.round(width)), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(Math.round(height), MeasureSpec.EXACTLY));
        field.layout(Math.round(left), Math.round(top), Math.round(left + width), Math.round(top + height));
        searchButton.layout(Math.round(left), Math.round(top), Math.round(left + width), Math.round(top + height));
        closeButton.layout((int) closeBounds.left, (int) closeBounds.top, (int) closeBounds.right, (int) closeBounds.bottom);
    }

    private void drawSurface(Canvas canvas, RectF bounds, BlurredBackgroundDrawable glass, float alpha) {
        if (alpha <= 0f) return;
        if (LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS)) {
            glass.setRadius(bounds.height() / 2f);
            glass.setBounds(Math.round(bounds.left), Math.round(bounds.top), Math.round(bounds.right), Math.round(bounds.bottom));
            glass.setAlpha(Math.round(255f * alpha));
            glass.draw(canvas);
        } else {
            paint.setColor(Theme.getColor(Theme.key_windowBackgroundWhite, provider));
            paint.setAlpha(Math.round(255f * alpha));
            canvas.drawRoundRect(bounds, bounds.height() / 2f, bounds.height() / 2f, paint);
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        if (Build.VERSION.SDK_INT >= 33 && canvas.isHardwareAccelerated()
                && LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS)) {
            if (glassGroup == null) {
                glassGroup = new org.telegram.ui.Components.blur3.drawable.LiquidGlassInputGroup(2);
                glassGroup.setPairMergeDistance(0, 1, AndroidUtilities.dp(6));
            }
            for (int i = 0; i < glassMembers.length; i++) {
                RectF bounds = i == 0 ? fieldBounds : closeBounds;
                glassMembers[i].setRadius(bounds.height() / 2f);
                glassMembers[i].setBounds(Math.round(bounds.left), Math.round(bounds.top), Math.round(bounds.right), Math.round(bounds.bottom));
                glassMembers[i].setAlpha(255);
                touchEffects[i].mapBounds(bounds, glassBounds[i]);
                LiquidTouchEffect.updateBackground(glassMembers[i], touchEffects[i], 0f, 0f);
            }
            glassVisibility[1] = textProgress(progress);
            glassGroup.draw(canvas, glassMembers, glassBounds, glassVisibility, touchEffects);
        } else {
            drawInteractiveSurface(canvas, fieldBounds, fieldGlass, fieldTouch, 1f);
            drawInteractiveSurface(canvas, closeBounds, closeGlass, closeTouch, textProgress(progress));
        }
        float size = AndroidUtilities.dp(24) * (1f - .2f * progress);
        float normalCx = fieldBounds.centerX();
        float activeCx = LocaleController.isRTL ? fieldBounds.right - AndroidUtilities.dp(22) : fieldBounds.left + AndroidUtilities.dp(22);
        float cx = normalCx + (activeCx - normalCx) * progress, cy = fieldBounds.centerY();
        loupe.setTint(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, provider));
        loupe.setBounds(Math.round(cx - size / 2), Math.round(cy - size / 2), Math.round(cx + size / 2), Math.round(cy + size / 2));
        int loupeSave = fieldTouch.begin(canvas);
        loupe.draw(canvas);
        if (loupeSave != -1) canvas.restoreToCount(loupeSave);
        closeButton.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, provider));
    }

    private void drawInteractiveSurface(Canvas canvas, RectF bounds, BlurredBackgroundDrawable glass,
            LiquidTouchEffect effect, float alpha) {
        LiquidTouchEffect.updateBackground(glass, effect, 0f, 0f);
        int save = effect.begin(canvas);
        drawSurface(canvas, bounds, glass, alpha);
        if (alpha > 0f) effect.drawHighlight(canvas, bounds.height() / 2f);
        if (save != -1) canvas.restoreToCount(save);
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            fieldTouch.reset();
            closeTouch.reset();
            return false;
        }
        // Observe before EditText/selection and button listeners; keep their normal gesture handling.
        fieldTouch.onTouchEvent(event, true);
        closeTouch.onTouchEvent(event, active);
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            fieldTouch.setMinimumPressDuration(180);
            closeTouch.setMinimumPressDuration(180);
        }
        boolean handled = super.dispatchTouchEvent(event);
        if (!handled) { fieldTouch.reset(); closeTouch.reset(); }
        return handled;
    }

    @Override protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
        LiquidTouchEffect effect = child == closeButton ? closeTouch : fieldTouch;
        int save = effect.begin(canvas);
        boolean drawn = super.drawChild(canvas, child, drawingTime);
        if (save != -1) canvas.restoreToCount(save);
        return drawn;
    }

    @Override protected boolean verifyDrawable(android.graphics.drawable.Drawable who) {
        return who == fieldGlass || who == closeGlass || super.verifyDrawable(who);
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        setActive(active, false);
    }
    @Override protected void onDetachedFromWindow() {
        if (transition != null) transition.cancel();
        fieldTouch.reset();
        closeTouch.reset();
        super.onDetachedFromWindow();
    }
    public void dispose() {
        if (transition != null) transition.cancel();
        fieldTouch.reset();
        closeTouch.reset();
        if (Build.VERSION.SDK_INT >= 31) field.setRenderEffect(null);
        field.setExternalSearchContainer(false);
        fieldGlass.setCallback(null);
        closeGlass.setCallback(null);
        if (glassGroup != null && Build.VERSION.SDK_INT >= 33) glassGroup.release();
        glassGroup = null;

        AndroidUtilities.removeFromParent(field);
    }
}
