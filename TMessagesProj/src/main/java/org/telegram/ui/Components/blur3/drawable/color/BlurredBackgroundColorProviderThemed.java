package org.telegram.ui.Components.blur3.drawable.color;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import android.graphics.Color;

import org.telegram.messenger.LiteMode;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.ActionBar.ThemeBind;

public class BlurredBackgroundColorProviderThemed implements BlurredBackgroundColorProvider {

    private final Theme.ResourcesProvider resourcesProvider;
    private final int backgroundColorId;
    private float alpha;
    /**
     * Opt-out for surfaces with no liquid glass at all (e.g. solid-color
     * buttons): keeps the plain theme color so the B/W glass toggle and the
     * accent tint never leak there. Defaults to true.
     */
    private boolean glassTintEnabled = true;

    public BlurredBackgroundColorProviderThemed(Theme.ResourcesProvider resourcesProvider, int backgroundColorId) {
        this(resourcesProvider, backgroundColorId, org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl.glassTintAlpha(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS) ? 0.85f : 0.76f));
    }

    public BlurredBackgroundColorProviderThemed(Theme.ResourcesProvider resourcesProvider, int backgroundColorId, float alpha) {
        this.resourcesProvider = resourcesProvider;
        this.backgroundColorId = backgroundColorId;
        this.alpha = alpha;

        updateColors();
    }

    public void setAlpha(float alpha) {
        this.alpha = alpha;
        updateColors();
    }

    public BlurredBackgroundColorProviderThemed setGlassTintEnabled(boolean enabled) {
        this.glassTintEnabled = enabled;
        updateColors();
        return this;
    }

    private int backgroundColor, shadowColor, strokeColorTop, strokeColorBottom;

    // compact mode draws this glass as in the design: an outline (this key; 0 for none) and soft shadows instead of the white highlight
    private int compactOutlineKey = -1;

    public BlurredBackgroundColorProviderThemed setCompactOutlineKey(int key) {
        compactOutlineKey = key;
        updateColors();
        return this;
    }

    private boolean isCompactGlass() {
        return compactOutlineKey >= 0 && Theme.isCompactMode(resourcesProvider);
    }

    private boolean hasCompactOutline() {
        return isCompactGlass() && Color.alpha(Theme.getColor(compactOutlineKey, resourcesProvider)) != 0;
    }

    @Override
    public int getOutlineColor() {
        return isCompactGlass() ? Theme.getColor(compactOutlineKey, resourcesProvider) : 0;
    }

    @Override
    public float getOutlineWidth() {
        return AndroidUtilities.dpf2(0.6f);
    }

    @Override
    public int getOutlineShadowColor() {
        return isCompactGlass() ? Theme.getColor(Theme.key_chat_compact_glassHairline, resourcesProvider) : 0;
    }

    @Override
    public float getShadowRadiusOverride() {
        return isCompactGlass() ? AndroidUtilities.dpf2(2.25f) : -1;
    }

    @Override
    public float getShadowDyOverride() {
        return isCompactGlass() ? AndroidUtilities.dpf2(1) : -1;
    }

    public boolean isDark() {
        final int color = Theme.getColor(backgroundColorId, resourcesProvider);
        return AndroidUtilities.computePerceivedBrightness(color) < .721f;
    }

    private int themeGeneration = ThemeBind.generation();

    // not a view: nothing dispatches updateColors() on theme switches for every owner, so the getters refresh lazily
    private void checkThemeGeneration() {
        final int generation = ThemeBind.generation();
        if (themeGeneration != generation) {
            updateColors();
        }
    }

    public void updateColors() {
        themeGeneration = ThemeBind.generation();
        final boolean dark = isDark();
        if (glassTintEnabled && org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl.isBlackWhiteTintEnabled()) {
            // Pure black/white base with no accent mixing, mirrors
            // BlurredBackgroundProviderImpl.chatTitlePill/bottomSheet.
            // Only for glass surfaces — see setGlassTintEnabled.
            backgroundColor = Theme.multAlpha(dark ? android.graphics.Color.BLACK : android.graphics.Color.WHITE, alpha);
        } else {
            final int color = Theme.getColor(backgroundColorId, resourcesProvider);
            backgroundColor = org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl.tintWithAccent(Theme.multAlpha(color, alpha), resourcesProvider);
        }

        if (dark) {
            strokeColorTop = 0x28FFFFFF;
            strokeColorBottom = 0x14FFFFFF;
            shadowColor = 0;
        } else {
            strokeColorTop = 0xFFFFFFFF;
            strokeColorBottom = 0xFFFFFFFF;
            shadowColor = 0x20000000; //0x19000000;
        }
    }

    @Override
    public int getShadowColor() {
        checkThemeGeneration();
        if (isCompactGlass()) {
            return Theme.getColor(Theme.key_chat_compact_glassShadow, resourcesProvider);
        }
        return shadowColor;
    }

    @Override
    public int getBackgroundColor() {
        checkThemeGeneration();
        return backgroundColor;
    }

    @Override
    public int getStrokeColorTop() {
        checkThemeGeneration();
        if (hasCompactOutline()) return 0;
        return strokeColorTop;
    }

    @Override
    public int getStrokeColorBottom() {
        checkThemeGeneration();
        if (hasCompactOutline()) return 0;
        return strokeColorBottom;
    }

    public boolean isGlassTintEnabled() {
        return glassTintEnabled;
    }
}
