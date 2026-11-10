package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.RectF;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import androidx.annotation.RequiresApi;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.LiquidGlassInputGroup;
import org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundColorProvider;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSource;
import org.telegram.ui.Components.chat.ViewPositionWatcher;
import zxc.iconic.xenon.NekoConfig;
import java.util.ArrayList;
import java.util.WeakHashMap;

/** Toolbar buttons share one optical union and independent liquid springs. */
@RequiresApi(Build.VERSION_CODES.S)
public final class FadeHeaderButtonGlass {
    private final View root;
    private final BlurredBackgroundDrawableViewFactory factory;
    private final BlurredBackgroundColorProvider colors;
    private final WeakHashMap<View, Button> buttons = new WeakHashMap<>();
    private final ArrayList<Button> active = new ArrayList<>();
    private final RectF barInRoot = new RectF(), childInBar = new RectF();
    private ActionBar bar;
    private LiquidGlassInputGroup group;
    private boolean grouped;
    private final LiquidPressAnimationSuppressor pressAnimations = new LiquidPressAnimationSuppressor();
    private BlurredBackgroundDrawable[] members;
    private RectF[] bounds;
    private float[] alpha;
    private LiquidTouchEffect[] effects;

    private static final class Button {
        final View view;
        final BlurredBackgroundDrawable drawable;
        final LiquidTouchEffect effect;
        final RectF bounds = new RectF();
        float x, y;
        Button(View view, BlurredBackgroundDrawable drawable, ActionBar bar) {
            this.view = view;
            this.drawable = drawable;
            effect = new LiquidTouchEffect(bar);
            effect.setContentView(view);
        }
    }

    public FadeHeaderButtonGlass(View root, BlurredBackgroundSource source, Theme.ResourcesProvider resources) {
        this.root = root;
        factory = new BlurredBackgroundDrawableViewFactory(source);
        factory.setLiquidGlassEffectAllowed(true);
        colors = BlurredBackgroundProviderImpl.topPanel(resources);
    }

    private void add(View view, float cx, float cy) {
        if (view == null || view.getVisibility() != View.VISIBLE || view.getAlpha() <= 0f) return;
        Button button = buttons.get(view);
        if (button == null) {
            if (view instanceof ViewGroup) {
                ((ViewGroup) view).setClipChildren(false);
                ((ViewGroup) view).setClipToPadding(false);
            }
            button = new Button(view, factory.create(bar).setColorProvider(colors)
                    .setRadius(AndroidUtilities.dp(23)).setPadding(0), bar);
            buttons.put(view, button);
        }
        if (!ViewPositionWatcher.computeRectInParent(view, bar, childInBar)) return;
        if (button.x != childInBar.left || button.y != childInBar.top) {
            // Local icon display lists may still contain a surface or spring
            // recorded before the menu was laid out on the newly selected tab.
            view.invalidate();
        }
        button.x = childInBar.left;
        button.y = childInBar.top;
        float radius = AndroidUtilities.dp(23);
        button.bounds.set(cx - radius, cy - radius, cx + radius, cy + radius);
        button.effect.setBounds(button.bounds.left, button.bounds.top, button.bounds.right, button.bounds.bottom);
        button.drawable.setSourceOffset(barInRoot.left, barInRoot.top);
        button.drawable.setBounds(Math.round(button.bounds.left), Math.round(button.bounds.top),
                Math.round(button.bounds.right), Math.round(button.bounds.bottom));
        button.drawable.updateColors();
        active.add(button);
    }

    public void drawToolbar(Canvas canvas, ActionBar toolbar, float backX, float backY) {
        bar = toolbar;
        boolean wasGrouped = grouped;
        grouped = false;
        if (wasGrouped) {
            for (Button button : active) button.view.invalidate();
        }
        active.clear();
        pressAnimations.sync(toolbar, NekoConfig.liquidChatElements && SharedConfig.chatBlurEnabled());
        if (!SharedConfig.chatBlurEnabled() || toolbar.isSearchFieldVisible() || toolbar.isActionModeShowed()
                || !canvas.isHardwareAccelerated()) return;
        // Window positions include the navigation/page animation transform.
        // Shapes and their source must both remain in toolbar/root coordinates.
        if (!ViewPositionWatcher.computeRectInParent(bar, root, barInRoot)) return;
        add(bar.getBackButton(), backX, backY);
        ActionBarMenu menu = bar.createMenu();
        if (menu.getVisibility() == View.VISIBLE) {
            for (int i = 0; i < menu.getChildCount(); i++) {
                View child = menu.getChildAt(i);
                if (!(child instanceof org.telegram.ui.ActionBar.ActionBarMenuItem)
                        || child.getWidth() == 0 || child.getHeight() == 0) continue;
                if (!ViewPositionWatcher.computeRectInParent(child, bar, childInBar)) continue;
                add(child, childInBar.centerX(), childInBar.centerY());
            }
        }
        if (Build.VERSION.SDK_INT < 33 || !NekoConfig.liquidChatElements || active.isEmpty() || active.size() > 8) {
            // Child icon display lists may be retained while the toolbar redraws.
            // Refresh their referenced surfaces here as well as in draw().
            for (Button button : active) button.drawable.updateDisplayList();
            return;
        }
        if (members == null || members.length != active.size()) {
            if (group != null) group.release();
            int count = active.size();
            group = new LiquidGlassInputGroup(count);
            members = new BlurredBackgroundDrawable[count];
            bounds = new RectF[count];
            effects = new LiquidTouchEffect[count];
            alpha = new float[count];
            for (int i = 0; i < count; i++) {
                bounds[i] = new RectF();
                for (int j = 0; j < count; j++) group.setPairMergeDistance(i, j, AndroidUtilities.dp(6));
            }
        }
        for (int i = 0; i < active.size(); i++) {
            Button button = active.get(i);
            members[i] = button.drawable;
            effects[i] = button.effect;
            button.effect.mapBounds(button.bounds, bounds[i]);
            alpha[i] = button.view.getAlpha();
            LiquidTouchEffect.updateBackground(button.drawable, button.effect, 0, 0);
        }
        group.draw(canvas, members, bounds, alpha, effects);
        grouped = true;
        if (!wasGrouped) {
            for (Button button : active) button.view.invalidate();
        }
    }

    public void observeTouch(MotionEvent event) {
        boolean enabled = grouped && NekoConfig.liquidChatElements;
        for (Button button : active) button.effect.onTouchEvent(event, enabled && button.view.isEnabled());
    }

    public int beginChild(Canvas canvas, View child, boolean localCanvas) {
        if (!grouped) return -1;
        Button button = buttons.get(child);
        if (button == null) return -1;
        return localCanvas ? button.effect.beginInChild(canvas, button.x, button.y) : button.effect.begin(canvas);
    }

    public boolean draw(Canvas canvas, View view, float cx, float cy) {
        if (!SharedConfig.chatBlurEnabled() || !canvas.isHardwareAccelerated()) return false;
        if (grouped) return true;
        Button button = buttons.get(view == bar ? bar.getBackButton() : view);
        if (button == null || !active.contains(button)) return false;
        int save = canvas.save();
        if (view != bar) canvas.translate(-button.x, -button.y);
        LiquidTouchEffect.updateBackground(button.drawable, null, 0, 0);
        button.drawable.updateDisplayList();
        button.drawable.draw(canvas);
        canvas.restoreToCount(save);
        return true;
    }

    public void release() {
        for (Button button : buttons.values()) {
            button.effect.reset();
            button.view.invalidate();
        }
        if (group != null) group.release();
        pressAnimations.restore();
        grouped = false;
    }
}
