package zxc.iconic.xenon.settings;

import android.content.Context;
import android.animation.ValueAnimator;
import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.graphics.Canvas;
import android.graphics.drawable.ColorDrawable;
import android.view.View;
import android.view.MotionEvent;
import android.widget.FrameLayout;
import androidx.recyclerview.widget.RecyclerView;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;
import java.util.ArrayList;
import zxc.iconic.xenon.triangles.TriangleSettings;
import zxc.iconic.xenon.triangles.TriangleWallpaperRenderer;
import static zxc.iconic.xenon.triangles.TriangleSettings.Flag.*;
import static zxc.iconic.xenon.triangles.TriangleSettings.Value.*;

public class NekoTriangleSettingsActivity extends BaseNekoSettingsActivity {
    private final TriangleSettings settings = TriangleSettings.get();
    private TrianglesBackground trianglesBackground;
    private boolean backgroundActive;
    private boolean settingsHidden;
    private ValueAnimator controlsFade;
    private ActionBarMenuItem visibilityItem;

    @Override
    public View createView(Context context) {
        cancelControlsFade();
        View result = super.createView(context);
        contentView.setBackground(trianglesBackground = new TrianglesBackground());
        // Keep the native rounded sections, allowing the animated activity background to show through.
        listView.setSections(view -> view.getParent() == listView && !UniversalAdapter.isShadow(listView.getChildViewHolder(view).getItemViewType()),
                type -> !UniversalAdapter.isShadow(type), AndroidUtilities.dp(12), AndroidUtilities.dp(16),
                (canvas, rect, topRadius, bottomRadius, alpha) -> listView.drawBackgroundRect(canvas, rect, topRadius, bottomRadius, alpha * 0.75f), false);
        visibilityItem = actionBar.createMenu().addItem(1, R.drawable.ic_triangles_visibility);
        visibilityItem.setPlainButtonCircle(true);
        visibilityItem.setOnClickListener(view -> toggleSettingsVisibility());
        listView.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override public boolean onInterceptTouchEvent(RecyclerView recyclerView, MotionEvent event) {
                return settingsHidden;
            }
        });
        updateVisibilityButton();
        listView.setAlpha(settingsHidden ? 0 : 1);
        listView.setVisibility(settingsHidden ? View.INVISIBLE : View.VISIBLE);
        listView.setImportantForAccessibility(settingsHidden ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        return result;
    }

    private void updateVisibilityButton() {
        visibilityItem.setIcon(settingsHidden ? R.drawable.ic_triangles_visibility_off : R.drawable.ic_triangles_visibility);
        visibilityItem.setContentDescription(LocaleController.getString(settingsHidden ? R.string.TriangleShowSettings : R.string.TriangleHideSettings));
    }

    private void cancelControlsFade() {
        ValueAnimator previous = controlsFade;
        controlsFade = null;
        if (previous != null) previous.cancel();
    }

    private void toggleSettingsVisibility() {
        cancelControlsFade();
        settingsHidden = !settingsHidden;
        updateVisibilityButton();
        listView.stopScroll();
        listView.setVisibility(View.VISIBLE);
        listView.setImportantForAccessibility(settingsHidden ? View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS : View.IMPORTANT_FOR_ACCESSIBILITY_AUTO);
        controlsFade = ValueAnimator.ofFloat(listView.getAlpha(), settingsHidden ? 0 : 1);
        controlsFade.setDuration(300);
        controlsFade.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        controlsFade.addUpdateListener(animation -> {
            listView.setAlpha((float) animation.getAnimatedValue());
            // Keep cached header blur in step with the actual list transparency.
            blur3_InvalidateBlur();
            contentView.invalidate();
        });
        controlsFade.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (controlsFade != animation) return;
                controlsFade = null;
                if (settingsHidden) listView.setVisibility(View.INVISIBLE);
            }
        });
        controlsFade.start();
    }

    @Override
    public void onFragmentDestroy() {
        cancelControlsFade();
        if (trianglesBackground != null) trianglesBackground.renderer.suspend();
        super.onFragmentDestroy();
    }

    private void invalidateTriangleBackground() {
        if (contentView != null) contentView.invalidate();
    }

    @Override
    public void onResume() {
        super.onResume();
        backgroundActive = true;
        if (listView != null) listView.adapter.update(false);
        invalidateTriangleBackground();
    }

    @Override
    public void onPause() {
        backgroundActive = false;
        if (trianglesBackground != null) trianglesBackground.renderer.suspend();
        super.onPause();
    }

    @Override
    protected String getActionBarTitle() { return LocaleController.getString(R.string.TriangleBackground); }

    private void flag(ArrayList<UItem> items, TriangleSettings.Flag f) {
        items.add(UItem.asCheck(10 + f.ordinal(), LocaleController.getString(f.title))
                .setChecked(settings.flag(f)).slug("triangles_" + f.key));
    }

    private void slider(ArrayList<UItem> items, TriangleSettings.Value v) {
        SeekbarConfig config = new SeekbarConfig(LocaleController.getString(v.title),
                v == THICKNESS ? Float.toString(v.min / 10f) : Integer.toString(v.min),
                v == THICKNESS ? Float.toString(v.max / 10f) : Integer.toString(v.max), v.min, v.max, progress -> {
                    settings.set(v, Math.round(progress));
                    invalidateTriangleBackground();
                });
        config.valueFormatter = value -> {
            if (v == SIZE) return value + " dp";
            if (v == THICKNESS) return (value / 10f) + " dp";
            if (v == SPEED) return value + " dp/s";
            if (v == SPIN) return value + "°/s";
            if (v == FPS) return value + " fps";
            if (v == HUE || v == DIRECTION || v == ROTATION || v == GRADIENT_HUE || v == GRADIENT_ANGLE) return value + "°";
            return value + "%";
        };
        items.add(TriangleSliderFactory.of(v, config).slug("triangles_" + v.key));
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        flag(items, ENABLED);
        items.add(UItem.asShadow(LocaleController.getString(R.string.TriangleDescription)));
        items.add(UItem.asHeader(LocaleController.getString(R.string.TriangleGeometry)));
        slider(items, SIZE); slider(items, DENSITY); slider(items, THICKNESS);
        slider(items, OPACITY); slider(items, SIZE_VARIANCE);
        flag(items, FILLED); flag(items, RANDOM_ROTATION); slider(items, ROTATION);
        items.add(UItem.asHeader(LocaleController.getString(R.string.TriangleMovement)));
        flag(items, ANIMATE);
        slider(items, SPEED); slider(items, SPEED_VARIANCE); slider(items, DIRECTION);
        slider(items, SPIN);
        flag(items, SYNC_REFRESH);
        if (!settings.flag(SYNC_REFRESH)) slider(items, FPS);
        items.add(UItem.asShadow(LocaleController.getString(R.string.TriangleDirectionHint)));
        flag(items, CUSTOMIZE_COLOR);
        if (settings.flag(CUSTOMIZE_COLOR)) {
            flag(items, AUTO_COLOR);
            if (!settings.flag(AUTO_COLOR)) {
                slider(items, HUE); slider(items, SATURATION); slider(items, BRIGHTNESS);
            }
            flag(items, GRADIENT);
            if (settings.flag(GRADIENT)) { slider(items, GRADIENT_HUE); slider(items, GRADIENT_ANGLE); }
            flag(items, FADE);
            if (settings.flag(FADE)) { slider(items, FADE_TOP); slider(items, FADE_BOTTOM); slider(items, FADE_POWER); }
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.TriangleColorHint)));
        items.add(TextSettingsCellFactory.of(1000, LocaleController.getString(R.string.TriangleReset)));
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        boolean updateRows = false;
        if (item.id == 1000) {
            settings.reset();
            updateRows = true;
        } else if (item.id >= 10 && item.id < 10 + TriangleSettings.Flag.values().length) {
            TriangleSettings.Flag flag = TriangleSettings.Flag.values()[item.id - 10];
            settings.toggle(flag);
            item.checked = settings.flag(flag);
            if (view instanceof TextCheckCell) ((TextCheckCell) view).setChecked(item.checked);
            updateRows = flag == SYNC_REFRESH || flag == CUSTOMIZE_COLOR || flag == AUTO_COLOR || flag == GRADIENT || flag == FADE;
        } else return;
        if (updateRows) listView.adapter.update(true);
        invalidateTriangleBackground();
    }

    private class TrianglesBackground extends ColorDrawable {
        private final TriangleWallpaperRenderer renderer = new TriangleWallpaperRenderer();
        @Override
        public void draw(Canvas canvas) {
            setColor(getThemedColor(Theme.key_windowBackgroundGray));
            super.draw(canvas);
            renderer.draw(canvas, contentView, this, getColor(), getBounds().width(), getBounds().height(), backgroundActive);
        }
    }

    private static class SliderBinding {
        final TriangleSettings.Value value;
        final SeekbarConfig config;
        SliderBinding(TriangleSettings.Value value, SeekbarConfig config) { this.value = value; this.config = config; }
    }

    /** Recycled rows bind from persisted live state, not the value captured when the list was built. */
    private static class TriangleSliderFactory extends UItem.UItemFactory<TriangleSliderCell> {
        static { setup(new TriangleSliderFactory()); }
        @Override
        public TriangleSliderCell createView(Context context, RecyclerListView listView, int account, int guid, Theme.ResourcesProvider provider) {
            return new TriangleSliderCell(context, provider);
        }
        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            ((TriangleSliderCell) view).bind((SliderBinding) item.object);
        }
        static UItem of(TriangleSettings.Value value, SeekbarConfig config) {
            UItem item = UItem.ofFactory(TriangleSliderFactory.class);
            item.id = 100 + value.ordinal();
            item.text = config.title;
            item.object = new SliderBinding(value, config);
            item.intValue = TriangleSettings.get().value(value);
            return item;
        }
        @Override public boolean isClickable() { return false; }
    }

    private static class TriangleSliderCell extends FrameLayout {
        private final Theme.ResourcesProvider provider;
        private SliderBinding binding;
        private AltSeekbar slider;
        private boolean dragging;
        private float displayedValue;
        private ValueAnimator valueAnimator;
        TriangleSliderCell(Context context, Theme.ResourcesProvider provider) { super(context); this.provider = provider; }
        void bind(SliderBinding next) {
            boolean recreate = binding == null || binding.value != next.value;
            binding = next;
            if (recreate) {
                cancelValueAnimation();
                if (slider != null) removeView(slider);
                dragging = false;
                SeekbarConfig config = next.config;
                slider = new AltSeekbar(getContext(), progress -> {
                    cancelValueAnimation();
                    displayedValue = progress;
                    binding.config.onDrag.run(progress);
                },
                        config.min, config.max, config.title, config.left, config.right, provider, config.description);
                slider.setValueFormatter(config.valueFormatter);
                slider.setStep(config.step);
                slider.setDefaultValue(next.value.initial);
                slider.setOnDragStateChanged(value -> {
                    dragging = value;
                    if (value) cancelValueAnimation();
                });
                addView(slider, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
            }
            if (!dragging) {
                int target = TriangleSettings.get().value(next.value);
                cancelValueAnimation();
                if (!recreate && isAttachedToWindow() && Math.abs(displayedValue - target) > 0.01f) {
                    valueAnimator = ValueAnimator.ofFloat(displayedValue, target);
                    valueAnimator.setDuration(180);
                    valueAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
                    valueAnimator.addUpdateListener(animation -> {
                        displayedValue = (float) animation.getAnimatedValue();
                        slider.setValue(displayedValue);
                    });
                    valueAnimator.start();
                } else {
                    displayedValue = target;
                    slider.setValue(target);
                }
            }
        }
        private void cancelValueAnimation() {
            if (valueAnimator != null) { valueAnimator.cancel(); valueAnimator = null; }
        }
        @Override protected void onDetachedFromWindow() {
            cancelValueAnimation();
            dragging = false;
            super.onDetachedFromWindow();
        }
    }
}
