package zxc.iconic.xenon.settings;

import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildVars;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.List;

import zxc.iconic.xenon.NekoConfig;
import zxc.iconic.xenon.helpers.PopupHelper;

/**
 * Settings screen for Blur and Liquid Glass.
 * Contains a live {@link GlassPreviewCell}, the Blur/Liquid Glass master
 * switches, glass-glare mode, blur/tint strength sliders and advanced-glass
 * parameters, plus a reset.
 */
public class NekoLiquidGlassSettingsActivity extends BaseNekoSettingsActivity {

    // --- Blur and Liquid glass section ---
    private final int liquidGlassFlagRow = rowId++;
    private final int blurFlagRow = rowId++;
    private final int forceBlurLiquidGlassRow = rowId++;
    private final int prismGlassRow = rowId++;

    // --- Glass / fade ---
    private final int disableScrimBlurRow = rowId++;
    private final int hideFadeViewRow = rowId++;
    private final int glassGlareRow = rowId++;
    private final int advancedGlassGlareRow = rowId++;
    private final int advancedGlassGlareAngleRow = rowId++;

    // --- Strength sliders ---
    private final int blurStrengthRow = rowId++;
    private final int ios27LiquidGlassRow = rowId++;
    private final int ios27GlassBlurRow = rowId++;
    private final int ios27GlassRefractionRow = rowId++;
    private final int ios27GlassDispersionRow = rowId++;
    private final int ios27GlassAlphaRow = rowId++;
    private final int ios27GlassTintRow = rowId++;
    private final int ios27GlassTintBlackWhiteRow = rowId++;
    private final int ios27GlassGlareRow = rowId++;
    private final int ios27GlassGlareAngleRow = rowId++;
    private final int ios27GlassLightIntensityRow = rowId++;
    private final int tintStrengthRow = rowId++;

    // --- Existing toggles ---
    private final int useAdvancedLiquidGlassRow  = rowId++;
    private final int advancedGlassAlphaRow      = rowId++;

    // --- Live preview ---
    private final int previewRow                 = rowId++;

    // --- Glass parameter sliders ---
    private final int advancedGlassFresnelRow    = rowId++;
    private final int advancedGlassDispersionRow = rowId++;
    private final int advancedGlassTintBlackWhiteRow = rowId++;
    private final int liquidGlassIntensityRow    = rowId++;
    private final int liquidGlassThicknessRow    = rowId++;

    // --- Reset ---
    private final int resetRow                   = rowId++;

    private GlassPreviewCell previewCell;
    private FrameLayout previewContainer;

    private void ensurePreviewCreated() {
        if (previewContainer != null || getContext() == null
                || android.os.Build.VERSION.SDK_INT < 33) return;

        previewContainer = new FrameLayout(getContext());
        previewContainer.setClipToPadding(false);
        previewContainer.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(8));
        previewContainer.setMinimumHeight(GlassPreviewCell.heightPx() + AndroidUtilities.dp(16));

        previewCell = new GlassPreviewCell(getContext(), resourcesProvider);
        previewContainer.addView(previewCell,
                LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, GlassPreviewCell.heightDp(),
                        Gravity.CENTER, 12, 0, 12, 0));
    }

    private boolean canBlur() {
        return SharedConfig.getDevicePerformanceClass() >= SharedConfig.PERFORMANCE_CLASS_AVERAGE
                || BuildVars.DEBUG_PRIVATE_VERSION || NekoConfig.forceBlurLiquidGlass;
    }

    private boolean canLiquidGlass() {
        return android.os.Build.VERSION.SDK_INT >= 33 && canBlur();
    }

    private CharSequence glassGlareModeValue() {
        return glassGlareModeValue(NekoConfig.glassGlareMode);
    }

    private CharSequence glassGlareModeValue(int mode) {
        if (mode == NekoConfig.GLASS_GLARE_FULL) {
            return LocaleController.getString(R.string.GlassGlareFull);
        } else if (mode == NekoConfig.GLASS_GLARE_SOLID) {
            return LocaleController.getString(R.string.GlassGlareSolid);
        }
        return LocaleController.getString(R.string.GlassGlareDisable);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            items.add(UItem.asCheck(ios27LiquidGlassRow,
                    LocaleController.getString(R.string.Ios27LiquidGlass),
                    LocaleController.getString(R.string.Ios27LiquidGlassInfo))
                    .setChecked(NekoConfig.ios27LiquidGlass).slug("ios27LiquidGlass"));
            items.add(UItem.asShadow(null));
        }
        if (NekoConfig.ios27LiquidGlass) {
            fillIos27Items(items);
            return;
        }
        // --- Blur and Liquid glass section ---
        items.add(UItem.asHeader(LocaleController.getString(R.string.BlurAndLiquidGlass)));
        if (canLiquidGlass()) {
            items.add(TextCheckbox2CellFactory.of(liquidGlassFlagRow, LocaleController.getString("LiteOptionsLiquidGlass"))
                    .setChecked(LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS)).slug("liteLiquidGlass"));
        }
        if (canBlur()) {
            items.add(TextCheckbox2CellFactory.of(blurFlagRow, LocaleController.getString(R.string.LiteOptionsBlur2))
                    .setChecked(LiteMode.isEnabled(LiteMode.FLAG_CHAT_BLUR)).slug("liteBlur"));
        }
        items.add(UItem.asCheck(forceBlurLiquidGlassRow, LocaleController.getString(R.string.ForceBlurLiquidGlass))
                .setChecked(NekoConfig.forceBlurLiquidGlass).slug("forceBlurLiquidGlass"));
        if (canLiquidGlass()) {
            items.add(UItem.asCheck(prismGlassRow,
                    LocaleController.getString(R.string.PrismGlass),
                    LocaleController.getString(R.string.PrismGlassDesc))
                    .setChecked(NekoConfig.usePrismGlass).slug("usePrismGlass"));
        }
        items.add(UItem.asShadow(null));

        // --- Glass / fade ---
        items.add(UItem.asCheck(disableScrimBlurRow, LocaleController.getString(R.string.DisableScrimBlur))
                .setChecked(NekoConfig.disableScrimBlur).slug("disableScrimBlur"));
        items.add(UItem.asCheck(hideFadeViewRow, LocaleController.getString(R.string.HideFadeView))
                .setChecked(NekoConfig.hideFadeView).slug("hideFadeView"));
        items.add(TextSettingsCellFactory.of(glassGlareRow, LocaleController.getString(R.string.GlassGlare), glassGlareModeValue())
                .slug("glassGlare"));
        if (NekoConfig.glassGlareMode == NekoConfig.GLASS_GLARE_FULL) {
            items.add(SeekbarCellFactory.of(advancedGlassGlareRow,
                    new SeekbarConfig(LocaleController.getString(R.string.AdvancedGlassGlare),
                            "10", "200", 10, 200,
                            progress -> {
                                float v = Math.max(0.1f, Math.min(2f, progress / 100f));
                                if (Math.abs(v - NekoConfig.advancedGlassGlare) > 0.001f) {
                                    NekoConfig.setAdvancedGlassGlare(v);
                                    invalidatePreview();
                                }
                            }),
                    Math.round(NekoConfig.advancedGlassGlare * 100)).slug("advancedGlassGlare"));
            items.add(SeekbarCellFactory.of(advancedGlassGlareAngleRow,
                    new SeekbarConfig(LocaleController.getString(R.string.GlassGlareAngle),
                            "0", "360", 0, 360,
                            progress -> {
                                float v = Math.max(0f, Math.min(360f, progress));
                                if (Math.abs(v - NekoConfig.advancedGlassGlareAngle) > 0.5f) {
                                    NekoConfig.setAdvancedGlassGlareAngle(v);
                                    invalidatePreview();
                                }
                            }),
                    Math.round(NekoConfig.advancedGlassGlareAngle)).slug("advancedGlassGlareAngle"));
        }
        items.add(UItem.asShadow(null));

        // --- Strength sliders ---
        items.add(SeekbarCellFactory.of(blurStrengthRow,
                new SeekbarConfig(LocaleController.getString(R.string.AdvancedGlassBlur),
                        "0", "40", 0, 40,
                        progress -> {
                            int v = Math.max(0, Math.min(40, Math.round(progress)));
                            if (v != NekoConfig.blurStrength) {
                                NekoConfig.setBlurStrength(v);
                                invalidatePreview();
                            }
                        }).setDescription(LocaleController.getString(R.string.AdvancedGlassBlurDesc)),
                NekoConfig.blurStrength).slug("blurStrength"));
        items.add(SeekbarCellFactory.of(tintStrengthRow,
                new SeekbarConfig(LocaleController.getString(R.string.AdvancedGlassTintPercent),
                        "0", "100", 0, 100,
                        progress -> {
                            int v = Math.max(0, Math.min(100, Math.round(progress)));
                            if (v != NekoConfig.advancedGlassTintPercent) {
                                NekoConfig.setAdvancedGlassTintPercent(v);
                                invalidatePreview();
                            }
                        }),
                NekoConfig.advancedGlassTintPercent).slug("advancedGlassTintPercent"));
        items.add(UItem.asShadow(null));

        // --- Toggles ---
        items.add(UItem.asCheck(useAdvancedLiquidGlassRow,
                LocaleController.getString(R.string.UseAdvancedLiquidGlass),
                LocaleController.getString(R.string.UseAdvancedLiquidGlassDesc))
                .slug("useAdvancedLiquidGlass").setChecked(NekoConfig.useAdvancedLiquidGlass));
        items.add(UItem.asCheck(advancedGlassTintBlackWhiteRow,
                        LocaleController.getString(R.string.AdvancedGlassTintBlackWhite),
                        LocaleController.getString(R.string.AdvancedGlassTintBlackWhiteDesc))
                .slug("advancedGlassTintBlackWhite")
                .setChecked(NekoConfig.advancedGlassTintBlackWhite));
        items.add(UItem.asShadow(null));

        // --- Live preview ---
        ensurePreviewCreated();
        if (previewContainer != null) {
            UItem pi = UItem.asCustom(previewContainer, GlassPreviewCell.heightDp() + 16);
            pi.id = previewRow;
            items.add(pi);
            items.add(UItem.asShadow(null));
        }

        // --- Glass parameter sliders ---
        items.add(UItem.asHeader(LocaleController.getString(R.string.AdvancedGlassSection)));
        if (NekoConfig.useAdvancedLiquidGlass) {
            items.add(SeekbarCellFactory.of(advancedGlassFresnelRow,
                    new SeekbarConfig(LocaleController.getString(R.string.AdvancedGlassRefraction),
                            "0", "200", 0, 200,
                            progress -> {
                                float v = Math.max(0f, Math.min(2f, progress / 100f));
                                if (Math.abs(v - NekoConfig.advancedGlassFresnel) > 0.001f) {
                                    NekoConfig.setAdvancedGlassFresnel(v);
                                    invalidatePreview();
                                }
                            }),
                    Math.round(NekoConfig.advancedGlassFresnel * 100)).slug("advancedGlassFresnel"));
            items.add(SeekbarCellFactory.of(advancedGlassDispersionRow,
                    new SeekbarConfig(LocaleController.getString(R.string.AdvancedGlassDispersion),
                            "0", "100", 0, 100,
                            progress -> {
                                float v = Math.max(0f, Math.min(1f, progress / 100f));
                                if (Math.abs(v - NekoConfig.advancedGlassDispersion) > 0.001f) {
                                    NekoConfig.setAdvancedGlassDispersion(v);
                                    invalidatePreview();
                                }
                            }),
                    Math.round(NekoConfig.advancedGlassDispersion * 100)).slug("advancedGlassDispersion"));
        } else {
            items.add(SeekbarCellFactory.of(liquidGlassIntensityRow,
                    new SeekbarConfig(LocaleController.getString(R.string.LiquidGlassIntensity),
                            "0", "150", 0, 150,
                            progress -> {
                                float v = Math.max(0f, Math.min(1.5f, progress / 100f));
                                if (Math.abs(v - NekoConfig.liquidGlassIntensity) > 0.001f) {
                                    NekoConfig.setLiquidGlassIntensity(v);
                                    invalidatePreview();
                                }
                            }),
                    Math.round(NekoConfig.liquidGlassIntensity * 100)).slug("liquidGlassIntensity"));
            items.add(SeekbarCellFactory.of(liquidGlassThicknessRow,
                    new SeekbarConfig(LocaleController.getString(R.string.LiquidGlassThickness),
                            "5", "20", 5, 20,
                            progress -> {
                                int v = Math.max(5, Math.min(20, Math.round(progress)));
                                if (v != NekoConfig.liquidGlassThickness) {
                                    NekoConfig.setLiquidGlassThickness(v);
                                    invalidatePreview();
                                }
                            }),
                    NekoConfig.liquidGlassThickness).slug("liquidGlassThickness"));
        }
        items.add(UItem.asShadow(null));

        // --- Reset ---
        items.add(UItem.asButton(resetRow, R.drawable.msg_reset,
                LocaleController.getString(R.string.AdvancedGlassReset)).accent().slug("advancedGlassReset"));
        items.add(UItem.asShadow(null));
    }

    private void addIos27Slider(ArrayList<UItem> items, int id, int title,
            int min, int max, float value, float scale,
            java.util.function.Consumer<Float> setter, String slug) {
        items.add(SeekbarCellFactory.of(id,
                new SeekbarConfig(LocaleController.getString(title), String.valueOf(min), String.valueOf(max),
                        min, max, progress -> {
                            setter.accept(progress / scale);
                            invalidatePreview();
                        }), Math.round(value * scale)).slug(slug));
    }

    private void fillIos27Items(ArrayList<UItem> items) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.Ios27LiquidGlass)));
        ensurePreviewCreated();
        if (previewContainer != null) {
            items.add(UItem.asCustom(previewContainer, GlassPreviewCell.heightDp() + 16));
        }
        addIos27Slider(items, ios27GlassBlurRow, R.string.AdvancedGlassBlur, 0, 40,
                NekoConfig.ios27GlassBlur, 1f, v -> NekoConfig.setIos27GlassBlur(Math.round(v)), "ios27GlassBlur");
        addIos27Slider(items, ios27GlassRefractionRow, R.string.AdvancedGlassRefraction, 0, 200,
                NekoConfig.ios27GlassRefraction, 100f, NekoConfig::setIos27GlassRefraction, "ios27GlassRefraction");
        addIos27Slider(items, ios27GlassDispersionRow, R.string.AdvancedGlassDispersion, 0, 100,
                NekoConfig.ios27GlassDispersion, 100f, NekoConfig::setIos27GlassDispersion, "ios27GlassDispersion");
        addIos27Slider(items, ios27GlassAlphaRow, R.string.AdvancedGlassAlpha, 0, 100,
                NekoConfig.ios27GlassAlpha, 1f, v -> NekoConfig.setIos27GlassAlpha(Math.round(v)), "ios27GlassAlpha");
        addIos27Slider(items, ios27GlassTintRow, R.string.AdvancedGlassTintPercent, 0, 100,
                NekoConfig.ios27GlassTintPercent, 1f, v -> NekoConfig.setIos27GlassTintPercent(Math.round(v)), "ios27GlassTintPercent");
        items.add(UItem.asCheck(ios27GlassTintBlackWhiteRow,
                LocaleController.getString(R.string.AdvancedGlassTintBlackWhite),
                LocaleController.getString(R.string.AdvancedGlassTintBlackWhiteDesc))
                .setChecked(NekoConfig.ios27GlassTintBlackWhite).slug("ios27GlassTintBlackWhite"));
        items.add(UItem.asShadow(null));
        items.add(TextSettingsCellFactory.of(glassGlareRow, LocaleController.getString(R.string.GlassGlare),
                glassGlareModeValue(NekoConfig.ios27GlassGlareMode)).slug("ios27GlassGlareMode"));
        if (NekoConfig.ios27GlassGlareMode != NekoConfig.GLASS_GLARE_DISABLE) {
            addIos27Slider(items, ios27GlassLightIntensityRow, R.string.Ios27GlassLightIntensity, 0, 100,
                    NekoConfig.ios27GlassLightIntensity, 100f, NekoConfig::setIos27GlassLightIntensity, "ios27GlassLightIntensity");
            if (NekoConfig.ios27GlassGlareMode == NekoConfig.GLASS_GLARE_FULL) {
                addIos27Slider(items, ios27GlassGlareRow, R.string.AdvancedGlassGlare, 10, 200,
                        NekoConfig.ios27GlassGlare, 100f, NekoConfig::setIos27GlassGlare, "ios27GlassGlare");
                addIos27Slider(items, ios27GlassGlareAngleRow, R.string.GlassGlareAngle, 0, 360,
                        NekoConfig.ios27GlassGlareAngle, 1f, NekoConfig::setIos27GlassGlareAngle, "ios27GlassGlareAngle");
            }
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.Ios27GlassSettingsInfo)));
        items.add(UItem.asButton(resetRow, R.drawable.msg_reset,
                LocaleController.getString(R.string.AdvancedGlassReset)).accent().slug("ios27GlassReset"));
        items.add(UItem.asShadow(null));
    }

    private void invalidatePreview() {
        if (previewCell != null) {
            previewCell.invalidateGlass();
        }
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null) listView.adapter.update(true);
        invalidatePreview();
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        if (!item.enabled) return;
        final int id = item.id;

        if (id == ios27LiquidGlassRow) {
            NekoConfig.toggleIos27LiquidGlass();
            listView.adapter.update(true);
            listView.post(this::invalidatePreview);
            showRestartBulletin();
        } else if (id == ios27GlassTintBlackWhiteRow) {
            NekoConfig.toggleIos27GlassTintBlackWhite();
            listView.adapter.update(true);
            listView.post(this::invalidatePreview);
        } else if (id == liquidGlassFlagRow) {
            LiteMode.toggleFlag(LiteMode.FLAG_LIQUID_GLASS);
            listView.adapter.update(true);
            listView.post(this::invalidatePreview);
        } else if (id == prismGlassRow) {
            NekoConfig.toggleUsePrismGlass();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.usePrismGlass);
            }
            listView.post(this::invalidatePreview);
        } else if (id == blurFlagRow) {
            LiteMode.toggleFlag(LiteMode.FLAG_CHAT_BLUR);
            listView.adapter.update(true);
            listView.post(this::invalidatePreview);
            refreshFadeBlurState();
        } else if (id == forceBlurLiquidGlassRow) {
            NekoConfig.toggleForceBlurLiquidGlass();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.forceBlurLiquidGlass);
            }
            listView.adapter.update(true);
            listView.post(this::invalidatePreview);
        } else if (id == disableScrimBlurRow) {
            NekoConfig.toggleDisableScrimBlur();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.disableScrimBlur);
            }
        } else if (id == hideFadeViewRow) {
            NekoConfig.toggleHideFadeView();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.hideFadeView);
            }
        } else if (id == glassGlareRow) {
            showGlassGlarePopup(view, position);
        } else if (id == useAdvancedLiquidGlassRow) {
            NekoConfig.toggleUseAdvancedLiquidGlass();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.useAdvancedLiquidGlass);
            }
            listView.adapter.update(true);
            listView.post(this::invalidatePreview);
            showRestartBulletin();
        } else if (id == advancedGlassTintBlackWhiteRow) {
            NekoConfig.toggleAdvancedGlassTintBlackWhite();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.advancedGlassTintBlackWhite);
            }
            listView.post(this::invalidatePreview);
            showRestartBulletin();
        } else if (id == resetRow) {
            confirmReset();
        }
    }

    private void showGlassGlarePopup(View view, int position) {
        List<String> entries = new ArrayList<>();
        entries.add(LocaleController.getString(R.string.GlassGlareFull));
        entries.add(LocaleController.getString(R.string.GlassGlareSolid));
        entries.add(LocaleController.getString(R.string.GlassGlareDisable));
        final boolean ios27 = NekoConfig.ios27LiquidGlass;
        PopupHelper.show(entries, LocaleController.getString(R.string.GlassGlare),
                ios27 ? NekoConfig.ios27GlassGlareMode : NekoConfig.glassGlareMode, getParentActivity(), view, i -> {
                    if (ios27) NekoConfig.setIos27GlassGlareMode(i);
                    else NekoConfig.setGlassGlareMode(i);
                    listView.adapter.update(true);
                    listView.post(this::invalidatePreview);
                    showRestartBulletin();
                }, resourcesProvider);
    }

    private void confirmReset() {
        final boolean ios27 = NekoConfig.ios27LiquidGlass;
        AlertDialog.Builder b = new AlertDialog.Builder(getParentActivity(), resourcesProvider);
        b.setTitle(LocaleController.getString(R.string.AdvancedGlassReset));
        b.setMessage(LocaleController.getString(R.string.AdvancedGlassResetConfirm));
        b.setPositiveButton(LocaleController.getString(R.string.Reset), (d, w) -> {
            if (ios27) NekoConfig.resetIos27GlassToDefaults();
            else NekoConfig.resetAdvancedGlassToDefaults();
            listView.adapter.update(true);
            listView.post(this::invalidatePreview);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip,
                    LocaleController.getString(R.string.AdvancedGlassResetDone)).show();
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        AlertDialog dlg = b.create();
        dlg.show();
        dlg.redPositive();
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.BlurAndLiquidGlass);
    }

    @Override
    protected String getKey() {
        return "liquidglass";
    }
}
