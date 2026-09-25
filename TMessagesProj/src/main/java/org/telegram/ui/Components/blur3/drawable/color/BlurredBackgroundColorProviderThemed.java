package org.telegram.ui.Components.blur3.drawable.color;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.ui.ActionBar.Theme;

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

    public boolean isDark() {
        final int color = Theme.getColor(backgroundColorId, resourcesProvider);
        return AndroidUtilities.computePerceivedBrightness(color) < .721f;
    }

    public void updateColors() {
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
        return shadowColor;
    }

    @Override
    public int getBackgroundColor() {
        return backgroundColor;
    }

    @Override
    public int getStrokeColorTop() {
        return strokeColorTop;
    }

    @Override
    public int getStrokeColorBottom() {
        return strokeColorBottom;
    }
}

