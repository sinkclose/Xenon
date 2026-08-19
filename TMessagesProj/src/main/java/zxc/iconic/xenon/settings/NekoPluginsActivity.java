package zxc.iconic.xenon.settings;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.util.TypedValue;

import androidx.core.content.FileProvider;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BotWebViewVibrationEffect;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.ScaleStateListAnimator;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import zxc.iconic.xenon.NekoConfig;
import zxc.iconic.xenon.plugins.PluginManager;

public class NekoPluginsActivity extends BaseNekoSettingsActivity {

    private static final int REQUEST_CODE_INSTALL = 7001;

    private final int enableRow = rowId++;
    private final int autoSafeModeRow = rowId++;
    private final int godModeRow = rowId++;
    private final int installRow = rowId++;
    private final int pluginsHeaderRow = rowId++;
    private int nextPluginRow = rowId;

    private final Map<Integer, PluginManager.PluginInfo> pluginRows = new HashMap<>();
    private boolean firstLoad = true;

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.Plugins)));
        items.add(UItem.asCheck(enableRow, LocaleController.getString(R.string.PluginsEnable),
                LocaleController.getString(R.string.PluginsEnableDesc)).setChecked(NekoConfig.pluginsEnabled));
        items.add(UItem.asShadow(null));

        items.add(UItem.asCheck(autoSafeModeRow, LocaleController.getString(R.string.PluginsAutoSafeMode),
                LocaleController.getString(R.string.PluginsAutoSafeModeDesc))
                .setChecked(NekoConfig.pluginAutoSafeMode));
        items.add(UItem.asShadow(null));

        items.add(UItem.asCheck(godModeRow, LocaleController.getString(R.string.PluginGodMode),
                LocaleController.getString(R.string.PluginGodModeDesc)).setChecked(NekoConfig.pluginGodMode));
        items.add(UItem.asShadow(null));

        items.add(TextSettingsCellFactory.of(installRow, LocaleController.getString(R.string.PluginsInstall)).accent());
        items.add(UItem.asShadow(null));

        // Plugin list is always shown — parsed from disk, independent of whether
        // the engine is enabled. The user can toggle/remove plugins before
        // turning the engine back on.
        List<PluginManager.PluginInfo> infos = PluginManager.getInstance().getAllPluginInfos();
        if (infos.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.PluginsEmpty)));
        } else {
            // No "Installed" header — cards stand on their own.
            pluginRows.clear();
            nextPluginRow = rowId;
            int unnamedCount = 0;
            for (PluginManager.PluginInfo info : infos) {
                int rid = nextPluginRow++;
                pluginRows.put(rid, info);
                String title = info.name != null ? info.name : info.fileName + " ⚠";
                String desc = info.description != null
                        ? info.description
                        : LocaleController.getString(R.string.PluginsNoDescription);
                boolean enabled = PluginSettingsActivity.getPrefs()
                        .getBoolean("plugin_enabled_" + info.fileName, true);
                // Card with toggle + description + settings/delete buttons.
                items.add(PluginCardFactory.of(rid, title, desc, info.author, info.version, enabled, NekoConfig.pluginsEnabled));
                if (info.name == null) unnamedCount++;
            }
            items.add(UItem.asShadow(null));

            if (firstLoad) {
                firstLoad = false;
                int unnamed = unnamedCount;
                if (unnamed > 0) {
                    AndroidUtilities.runOnUIThread(() -> {
                        if (isFinishing()) return;
                        BulletinFactory.of(this).createErrorBulletin(
                                LocaleController.getString(R.string.PluginsPluginNoName)).show();
                    }, 300);
                }
            }
        }
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        if (!item.enabled) return;
        int id = item.id;
        if (id == enableRow) {
            boolean wasEnabled = NekoConfig.pluginsEnabled;
            NekoConfig.togglePluginsEnabled();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.pluginsEnabled);
            }
            if (!wasEnabled && NekoConfig.pluginsEnabled) {
                showRestartBulletin();
            }
            updateRows();
        } else if (id == autoSafeModeRow) {
            NekoConfig.togglePluginAutoSafeMode();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.pluginAutoSafeMode);
            }
        } else if (id == godModeRow) {
            if (NekoConfig.pluginGodMode) {
                // Turning off is unconditional — no confirmation needed.
                NekoConfig.togglePluginGodMode();
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(NekoConfig.pluginGodMode);
                }
            } else {
                // Turning on is dangerous: force a 60s cooldown on the confirm
                // button so it can't be enabled by an accidental tap.
                showGodModeConfirmDialog(view);
            }
        } else if (id == installRow) {
            // Allow installing plugins regardless of the engine state. They'll
            // activate once the engine is enabled.
            launchFilePicker();
        }
        // Plugin cards handle their own clicks (toggle + settings + delete) in
        // PluginCardFactory, so there's no top-level click for them here.
    }

    /**
     * God Mode confirmation dialog. The OK button is disabled for 60s with a
     * live countdown, so enabling it can't be an accident; only once the timer
     * reaches zero does the button turn active and actually flip the toggle.
     */
    private void showGodModeConfirmDialog(View toggleView) {
        Activity activity = getParentActivity();
        if (activity == null) return;

        BottomSheet.Builder builder = new BottomSheet.Builder(activity, false, resourcesProvider);
        LinearLayout sheet = new LinearLayout(activity);
        sheet.setOrientation(LinearLayout.VERTICAL);
        int pad = AndroidUtilities.dp(24);
        sheet.setPadding(pad, AndroidUtilities.dp(20), pad, pad);

        TextView title = new TextView(activity);
        title.setText(LocaleController.getString(R.string.PluginGodMode));
        title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
        title.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        title.setPadding(0, 0, 0, AndroidUtilities.dp(12));
        sheet.addView(title);

        TextView desc = new TextView(activity);
        desc.setText(LocaleController.getString(R.string.PluginGodModeWarn));
        desc.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        desc.setLineSpacing(AndroidUtilities.dp(2), 1f);
        desc.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        desc.setPadding(0, 0, 0, AndroidUtilities.dp(20));
        sheet.addView(desc);

        ButtonWithCounterView enableBtn = new ButtonWithCounterView(activity, resourcesProvider).setRound();
        ScaleStateListAnimator.apply(enableBtn, 0.02f, 1.5f);
        enableBtn.setText(LocaleController.getString(R.string.PluginGodModeEnable), false);
        enableBtn.setTimer(20, () -> {});
        final BottomSheet[] sheetRef = new BottomSheet[1];
        enableBtn.setOnClickListener(v -> {
            if (enableBtn.isTimerActive()) {
                AndroidUtilities.shakeViewSpring(enableBtn, 3);
                BotWebViewVibrationEffect.APP_ERROR.vibrate();
            } else {
                NekoConfig.togglePluginGodMode();
                if (toggleView instanceof TextCheckCell) {
                    ((TextCheckCell) toggleView).setChecked(NekoConfig.pluginGodMode);
                }
                if (sheetRef[0] != null) sheetRef[0].dismiss();
                AndroidUtilities.runOnUIThread(NekoPluginsActivity.this::showRestartBulletin, 300);
            }
        });
        sheet.addView(enableBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 0, 0, 8));

        TextView cancelBtn = new TextView(activity);
        cancelBtn.setText(LocaleController.getString(R.string.Cancel));
        cancelBtn.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        cancelBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        cancelBtn.setGravity(android.view.Gravity.CENTER);
        cancelBtn.setPadding(0, AndroidUtilities.dp(14), 0, AndroidUtilities.dp(4));
        cancelBtn.setOnClickListener(v -> { if (sheetRef[0] != null) sheetRef[0].dismiss(); });
        sheet.addView(cancelBtn);

        builder.setCustomView(sheet);
        sheetRef[0] = builder.show();
    }

    private void showPluginBottomSheet(PluginManager.PluginInfo info) {
        Activity activity = getParentActivity();
        if (activity == null) return;

        BottomSheet.Builder builder = new BottomSheet.Builder(activity, false, resourcesProvider);
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(16), AndroidUtilities.dp(24), AndroidUtilities.dp(16));

        final BottomSheet[] sheetRef = new BottomSheet[1];

        String titleText = info.name != null ? info.name : info.fileName;
        TextView titleView = new TextView(activity);
        titleView.setText(titleText);
        titleView.setTextSize(18);
        titleView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
        titleView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        titleView.setPadding(0, 0, 0, AndroidUtilities.dp(8));
        layout.addView(titleView);

        if (info.description != null || info.name == null) {
            TextView descView = new TextView(activity);
            String descText = info.description != null
                    ? info.description
                    : LocaleController.getString(R.string.PluginsNoDescription);
            if (info.name == null) {
                descText = LocaleController.getString(R.string.PluginsPluginNoName);
            }
            descView.setText(descText);
            descView.setTextSize(14);
            descView.setTextColor(Theme.getColor(Theme.key_dialogTextGray));
            descView.setPadding(0, 0, 0, AndroidUtilities.dp(16));
            layout.addView(descView);
        }

        View separator = new View(activity);
        separator.setBackgroundColor(Theme.getColor(Theme.key_divider));
        separator.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(1)));
        layout.addView(separator);

        // Toggle writes directly to prefs, so it works even with the engine off.
        final boolean[] enabled = { PluginSettingsActivity.getPrefs().getBoolean("plugin_enabled_" + info.fileName, true) };
        TextCheckCell toggleCell = new TextCheckCell(activity);
        toggleCell.setTextAndCheck(LocaleController.getString(R.string.PluginsPluginEnabled),
                enabled[0], false);
        toggleCell.setOnClickListener(v -> {
            enabled[0] = !enabled[0];
            PluginSettingsActivity.getPrefs().edit()
                    .putBoolean("plugin_enabled_" + info.fileName, enabled[0]).apply();
            toggleCell.setChecked(enabled[0]);
            // If the engine is on, apply the change live.
            if (NekoConfig.pluginsEnabled) {
                PluginManager.getInstance().reloadAll();
            }
            updateRows();
        });
        layout.addView(toggleCell);

        // Settings button: only meaningful for an active plugin (needs Globals).
        if (NekoConfig.pluginsEnabled) {
            PluginManager.LoadedPlugin loaded = PluginManager.getInstance().findPlugin(info.fileName);
            if (loaded != null && !loaded.settings.isEmpty()) {
                int accentColorPill = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
                double luminance = (0.299 * android.graphics.Color.red(accentColorPill) + 0.587 * android.graphics.Color.green(accentColorPill) + 0.114 * android.graphics.Color.blue(accentColorPill)) / 255.0;
                int pillTextColor = luminance > 0.5 ? 0xff000000 : 0xffffffff;

                android.widget.FrameLayout settingsWrap = new android.widget.FrameLayout(activity);
                settingsWrap.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(8), AndroidUtilities.dp(16), AndroidUtilities.dp(4));

                TextView settingsBtn = new TextView(activity);
                settingsBtn.setText(LocaleController.getString(R.string.PluginsOpenSettings));
                settingsBtn.setTextColor(pillTextColor);
                settingsBtn.setTextSize(15);
                settingsBtn.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
                settingsBtn.setGravity(android.view.Gravity.CENTER);
                settingsBtn.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(24), accentColorPill));
                settingsBtn.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12));
                settingsBtn.setElevation(AndroidUtilities.dp(2));
                settingsBtn.setOnClickListener(v -> {
                    sheetRef[0].dismiss();
                    presentFragment(new PluginSettingsActivity().setPlugin(loaded));
                });
                settingsWrap.addView(settingsBtn, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48));
                layout.addView(settingsWrap);
            }
        }

        android.widget.FrameLayout deleteWrap = new android.widget.FrameLayout(activity);
        deleteWrap.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(8));

        int redColor = Theme.getColor(Theme.key_text_RedBold, resourcesProvider);
        TextView deleteBtn = new TextView(activity);
        deleteBtn.setText(LocaleController.getString(R.string.Delete));
        deleteBtn.setTextColor(0xffffffff);
        deleteBtn.setTextSize(15);
        deleteBtn.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        deleteBtn.setGravity(android.view.Gravity.CENTER);
        deleteBtn.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(24), redColor));
        deleteBtn.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12));
        deleteBtn.setElevation(AndroidUtilities.dp(2));
        deleteBtn.setOnClickListener(v -> {
            PluginManager.getInstance().remove(info.fileName);
            BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip,
                    LocaleController.getString(R.string.PluginsRemoved)).show();
            sheetRef[0].dismiss();
            updateRows();
        });
        deleteWrap.addView(deleteBtn, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48));
        layout.addView(deleteWrap);

        builder.setCustomView(layout);
        sheetRef[0] = builder.show();
    }

    private void launchFilePicker() {
        try {
            Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            startActivityForResult(intent, REQUEST_CODE_INSTALL);
        } catch (Exception e) {
            FileLog.e(e);
            BulletinFactory.of(this).createErrorBulletin(
                    LocaleController.getString(R.string.PluginsInstallFailed)).show();
        }
    }

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQUEST_CODE_INSTALL) return;
        if (resultCode != android.app.Activity.RESULT_OK || data == null || data.getData() == null) return;
        installFromUri(data.getData());
    }

    private void installFromUri(Uri uri) {
        Activity activity = getParentActivity();
        if (activity == null) return;
        AlertDialog progressDialog = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
        progressDialog.setCanCancel(false);
        progressDialog.show();
        Utilities.globalQueue.postRunnable(() -> {
            File tempFile = null;
            String fileName = null;
            String[] meta = null;
            try (InputStream is = activity.getContentResolver().openInputStream(uri)) {
                if (is != null) {
                    fileName = queryFileName(uri);
                    if (fileName == null || !fileName.endsWith(PluginManager.PLUGIN_EXT)) {
                        fileName = "plugin" + PluginManager.PLUGIN_EXT;
                    }
                    tempFile = new File(activity.getCacheDir(), fileName);
                    AndroidUtilities.copyFile(is, tempFile);
                    meta = PluginManager.parseMetadata(tempFile);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            final File fTemp = tempFile;
            final String fName = fileName;
            final String[] fMeta = meta;
            AndroidUtilities.runOnUIThread(() -> {
                try { progressDialog.dismiss(); } catch (Exception e) { FileLog.e(e); }
                if (fTemp == null || !fTemp.exists()) {
                    BulletinFactory.of(this).createErrorBulletin(
                            LocaleController.getString(R.string.PluginsInstallFailed)).show();
                    return;
                }
                showInstallPreview(fTemp, fName, fMeta);
            });
        });
    }

    public static void showInstallBottomSheet(Activity activity, File pluginFile, Theme.ResourcesProvider resourcesProvider, java.util.function.Consumer<PluginManager.LoadedPlugin> onInstalled) {
        if (activity == null || pluginFile == null) return;
        String[] meta = PluginManager.parseMetadata(pluginFile);
        String fileName = pluginFile.getName();
        String pluginId = meta != null && meta.length > 2 ? meta[2] : null;
        if (pluginId == null || pluginId.isEmpty()) {
            BulletinFactory.global().createSimpleBulletin(R.raw.chats_infotip,
                    "Plugin missing plugin_id").show();
            return;
        }
        String pluginName = meta != null && meta[0] != null ? meta[0] : (fileName != null ? fileName : "Plugin");
        boolean hasDesc = meta != null && meta[1] != null && !meta[1].isEmpty();
        String pluginDesc = hasDesc ? meta[1] : LocaleController.getString(R.string.PluginsNoDescription);
        String pluginAuthor = meta != null && meta.length > 3 ? meta[3] : null;
        String pluginVersion = meta != null && meta.length > 4 ? meta[4] : null;
        // Check if a plugin with the same pluginId is already installed
        PluginManager.LoadedPlugin existingSameId = pluginId != null ? PluginManager.getInstance().findByPluginId(pluginId) : null;
        // Check if the file content is identical to an already installed plugin
        boolean isIdentical = PluginManager.isPluginFileIdentical(pluginFile, pluginId);
        boolean isUpdate = existingSameId != null && !isIdentical;

        BottomSheet.Builder builder = new BottomSheet.Builder(activity, false, resourcesProvider);
        LinearLayout layout = new LinearLayout(activity);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(20), AndroidUtilities.dp(24), AndroidUtilities.dp(16));

        // Star icon + plugin name (centered, horizontal)
        LinearLayout headerLine = new LinearLayout(activity);
        headerLine.setOrientation(LinearLayout.HORIZONTAL);
        headerLine.setGravity(android.view.Gravity.CENTER);
        headerLine.setPadding(0, 0, 0, AndroidUtilities.dp(8));

        org.telegram.ui.Components.BackupImageView iconView = new org.telegram.ui.Components.BackupImageView(activity);
        iconView.setImageResource(R.drawable.msg_fave);
        int starColor = org.telegram.ui.ActionBar.Theme.isCurrentThemeDark() ? 0xffffffff : 0xff000000;
        iconView.setColorFilter(new android.graphics.PorterDuffColorFilter(starColor, android.graphics.PorterDuff.Mode.SRC_IN));
        int iconSize = AndroidUtilities.dp(9);
        headerLine.addView(iconView, LayoutHelper.createLinear(iconSize, iconSize, 0, 0, AndroidUtilities.dp(4), 0));

        TextView nameView = new TextView(activity);
        nameView.setText(pluginName);
        nameView.setTextSize(18);
        nameView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        nameView.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        headerLine.addView(nameView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));

        // Version next to the name (gray), like in PluginCardCell. For an update
        // show "old → new".
        if (pluginVersion != null && !pluginVersion.isEmpty()) {
            TextView headerVersion = new TextView(activity);
            String vText;
            if (isUpdate && existingSameId.version != null && !existingSameId.version.isEmpty()) {
                vText = existingSameId.version + " → " + pluginVersion;
            } else {
                vText = "v" + pluginVersion;
            }
            headerVersion.setText(vText);
            headerVersion.setTextSize(13);
            headerVersion.setTextColor(isUpdate
                    ? Theme.getColor(Theme.key_text_RedBold, resourcesProvider)
                    : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            headerLine.addView(headerVersion, LayoutHelper.createLinear(
                    LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 0));
            // small gap between name and version
            ((LinearLayout.LayoutParams) headerVersion.getLayoutParams()).leftMargin = AndroidUtilities.dp(6);
        }
        layout.addView(headerLine);

        // Plugin ID line
        if (pluginId != null) {
            TextView idView = new TextView(activity);
            String idText = pluginId;
            if (isUpdate) {
                idText = pluginId + " (" + LocaleController.getString(R.string.PluginsUpdate) + ")";
            }
            idView.setText(idText);
            idView.setTextSize(12);
            idView.setTextColor(isUpdate
                    ? Theme.getColor(Theme.key_text_RedBold, resourcesProvider)
                    : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3, resourcesProvider));
            idView.setGravity(android.view.Gravity.CENTER);
            idView.setPadding(0, 0, 0, AndroidUtilities.dp(12));
            layout.addView(idView);
        }

        // Author line
        if (pluginAuthor != null && !pluginAuthor.isEmpty()) {
            TextView authorTv = new TextView(activity);
            authorTv.setText("by " + pluginAuthor);
            authorTv.setTextSize(12);
            authorTv.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3, resourcesProvider));
            authorTv.setGravity(android.view.Gravity.CENTER);
            authorTv.setPadding(0, 0, 0, AndroidUtilities.dp(8));
            layout.addView(authorTv);
        }

        // Description or placeholder
        TextView descView = new TextView(activity);
        descView.setText(pluginDesc);
        descView.setTextSize(14);
        descView.setTextColor(hasDesc
                ? Theme.getColor(Theme.key_dialogTextGray, resourcesProvider)
                : Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider));
        descView.setGravity(android.view.Gravity.CENTER);
        descView.setPadding(0, 0, 0, AndroidUtilities.dp(20));
        layout.addView(descView);

        View separator = new View(activity);
        separator.setBackgroundColor(Theme.getColor(Theme.key_divider, resourcesProvider));
        separator.setLayoutParams(new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(1)));
        layout.addView(separator);

        int accentColor = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
        double installLuminance = (0.299 * android.graphics.Color.red(accentColor) + 0.587 * android.graphics.Color.green(accentColor) + 0.114 * android.graphics.Color.blue(accentColor)) / 255.0;
        int installTextColor = installLuminance > 0.5 ? 0xff000000 : 0xffffffff;

        // Pill-shaped install button
        android.widget.FrameLayout installWrap = new android.widget.FrameLayout(activity);
        installWrap.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(6));

        TextView installBtn = new TextView(activity);
        installBtn.setText(isIdentical
                ? LocaleController.getString(R.string.PluginsOpenPlugins)
                : LocaleController.getString(R.string.PluginsInstall));
        installBtn.setTextColor(installTextColor);
        installBtn.setTextSize(15);
        installBtn.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        installBtn.setGravity(android.view.Gravity.CENTER);
        installBtn.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(24), accentColor));
        installBtn.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12));
        installBtn.setElevation(AndroidUtilities.dp(2));

        BottomSheet[] sheetRef = new BottomSheet[1];
        installBtn.setOnClickListener(v -> {
            if (sheetRef[0] != null) sheetRef[0].dismiss();
            if (isIdentical) {
                if (activity instanceof org.telegram.ui.LaunchActivity) {
                    ((org.telegram.ui.LaunchActivity) activity).presentFragment(new NekoPluginsActivity());
                }
            } else {
                doInstallPluginBackground(activity, pluginFile, fileName, onInstalled);
            }
        });
        installWrap.addView(installBtn, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48));
        layout.addView(installWrap);

        // Pill-shaped close button
        android.widget.FrameLayout closeWrap = new android.widget.FrameLayout(activity);
        closeWrap.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(8));

        TextView closeBtn = new TextView(activity);
        closeBtn.setText(LocaleController.getString(R.string.Close));
        closeBtn.setTextColor(Theme.getColor(Theme.key_text_RedBold, resourcesProvider));
        closeBtn.setTextSize(15);
        closeBtn.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
        closeBtn.setGravity(android.view.Gravity.CENTER);
        closeBtn.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(24),
                Theme.multAlpha(accentColor, 0.10f)));
        closeBtn.setPadding(0, AndroidUtilities.dp(12), 0, AndroidUtilities.dp(12));
        closeBtn.setOnClickListener(v -> {
            if (sheetRef[0] != null) sheetRef[0].dismiss();
        });
        closeWrap.addView(closeBtn, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 48));
        layout.addView(closeWrap);

        builder.setCustomView(layout);
        sheetRef[0] = builder.show();
    }

    private static void doInstallPluginBackground(Activity activity, File tempFile, String fileName, java.util.function.Consumer<PluginManager.LoadedPlugin> onInstalled) {
        Utilities.globalQueue.postRunnable(() -> {
            PluginManager.LoadedPlugin result = null;
            try {
                File dest = new File(PluginManager.getPluginsDir(), fileName);
                if (dest.exists()) dest.delete();
                if (tempFile.renameTo(dest)) {
                    result = PluginManager.getInstance().installFrom(dest);
                } else {
                    AndroidUtilities.copyFile(tempFile, dest);
                    result = PluginManager.getInstance().installFrom(dest);
                }
            } catch (Exception e) {
                FileLog.e(e);
            }
            final PluginManager.LoadedPlugin installed = result;
            AndroidUtilities.runOnUIThread(() -> {
                if (installed != null) {
                    if (onInstalled != null) {
                        onInstalled.accept(installed);
                    } else {
                        BulletinFactory.global().createSimpleBulletin(R.raw.chats_infotip,
                                LocaleController.getString(R.string.PluginsInstallSuccess)).show();
                    }
                } else if (PluginManager.isLastInstallEngineOff()) {
                    BulletinFactory.global().createSimpleBulletin(R.raw.chats_infotip,
                            LocaleController.getString(R.string.PluginsInstallSuccess)).show();
                } else {
                    String errorText = PluginManager.getLastParseError();
                    if (errorText != null) {
                        BulletinFactory.global().createSimpleBulletin(R.raw.chats_infotip,
                                LocaleController.getString(R.string.PluginsInstallFailed),
                                LocaleController.getString(R.string.CopyError), () -> {
                                    AndroidUtilities.addToClipboard(errorText);
                                    BulletinFactory.global().createCopyBulletin(
                                            LocaleController.getString(R.string.ErrorCopied)).show();
                                }).show();
                    } else {
                        BulletinFactory.global().createErrorBulletin(
                                LocaleController.getString(R.string.PluginsInstallFailed)).show();
                    }
                }
            });
        });
    }

    private void showInstallPreview(File tempFile, String fileName, String[] meta) {
        showInstallBottomSheet(getParentActivity(), tempFile, resourcesProvider, plugin -> updateRows());
    }

    private String queryFileName(Uri uri) {
        String name = null;
        android.database.Cursor cursor = null;
        try {
            cursor = getParentActivity().getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) name = cursor.getString(idx);
            }
        } catch (Exception e) {
            FileLog.e(e);
        } finally {
            if (cursor != null) cursor.close();
        }
        return name;
    }

    @Override
    public void onResume() {
        super.onResume();
        PluginManager.setCurrentActivity(getParentActivity());
        PluginCardHost.host = this;
        maybeShowSafeModeIntro();
    }

    /**
     * On the very first time the user opens the plugins screen, show a one-time
     * BottomSheet explaining how to start in Safe Mode (hold a volume button at
     * launch). Persisted via a pref flag so it only shows once.
     */
    private void maybeShowSafeModeIntro() {
        if (PluginSettingsActivity.getPrefs().getBoolean("safe_mode_intro_shown", false)) {
            return;
        }
        AndroidUtilities.runOnUIThread(() -> {
            if (isFinishing()) return;
            Activity activity = getParentActivity();
            if (activity == null) return;
            PluginSettingsActivity.getPrefs().edit().putBoolean("safe_mode_intro_shown", true).apply();

            BottomSheet.Builder builder = new BottomSheet.Builder(activity, false, resourcesProvider);
            LinearLayout sheet = new LinearLayout(activity);
            sheet.setOrientation(LinearLayout.VERTICAL);
            int pad = AndroidUtilities.dp(24);
            sheet.setPadding(pad, AndroidUtilities.dp(20), pad, pad);

            TextView title = new TextView(activity);
            title.setText(LocaleController.getString(R.string.PluginsSafeModeTitle));
            title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
            title.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            title.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            title.setPadding(0, 0, 0, AndroidUtilities.dp(12));
            sheet.addView(title);

            TextView desc = new TextView(activity);
            desc.setText(LocaleController.getString(R.string.PluginsSafeModeDesc));
            desc.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
            desc.setLineSpacing(AndroidUtilities.dp(2), 1f);
            desc.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            desc.setPadding(0, 0, 0, AndroidUtilities.dp(16));
            sheet.addView(desc);

            final BottomSheet[] ref = new BottomSheet[1];
            int accent = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
            double lum = (0.299 * android.graphics.Color.red(accent) + 0.587 * android.graphics.Color.green(accent) + 0.114 * android.graphics.Color.blue(accent)) / 255.0;
            int btnTextColor = lum > 0.5 ? 0xff000000 : 0xffffffff;
            TextView gotBtn = new TextView(activity);
            gotBtn.setText(LocaleController.getString(R.string.PluginsSafeModeGot));
            gotBtn.setTextColor(btnTextColor);
            gotBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
            gotBtn.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
            gotBtn.setGravity(android.view.Gravity.CENTER);
            gotBtn.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(24), accent));
            gotBtn.setPadding(0, AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14));
            gotBtn.setOnClickListener(v -> { if (ref[0] != null) ref[0].dismiss(); });
            sheet.addView(gotBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));

            builder.setCustomView(sheet);
            ref[0] = builder.show();
        }, 500);
    }

    @Override
    public void onPause() {
        super.onPause();
        if (PluginCardHost.host == this) {
            PluginCardHost.host = null;
        }
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.Plugins);
    }

    @Override
    protected String getKey() {
        return "plugins";
    }

    // ------------------------------------------------------------------
    // Plugin card factory: renders each plugin as a card with a toggle,
    // description, and settings/delete action buttons.
    // ------------------------------------------------------------------

    protected static class PluginCardFactory extends UItem.UItemFactory<PluginCardCell> {
        static {
            setup(new PluginCardFactory());
        }

        private Theme.ResourcesProvider resourcesProvider;

        @Override
        public PluginCardCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            this.resourcesProvider = resourcesProvider;
            return new PluginCardCell(context, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            PluginCardCell cell = (PluginCardCell) view;
            cell.bind(item);

            // Resolve the plugin info this card represents.
            final PluginManager.PluginInfo info = findInfoById(item.id);
            if (info == null) return;

            // Toggle: click on the whole toggle cell flips the switch. This
            // TextCheckCell variant has no OnCheckedChangeListener — clicks go
            // through the standard View OnClickListener, so we toggle manually.
            cell.getToggleSwitch().setOnClickListener(v -> {
                boolean newState = !PluginSettingsActivity.getPrefs()
                        .getBoolean("plugin_enabled_" + info.fileName, true);
                PluginSettingsActivity.getPrefs().edit()
                        .putBoolean("plugin_enabled_" + info.fileName, newState).apply();
                if (cell.getToggleSwitch() instanceof org.telegram.ui.Components.Switch) {
                    ((org.telegram.ui.Components.Switch) cell.getToggleSwitch()).setChecked(newState, true);
                }
                if (NekoConfig.pluginsEnabled) {
                    PluginManager.getInstance().reloadAll();
                }
            });

            // Settings button: open the plugin's settings. Works even when the
            // plugin (or the engine) is disabled — we load the plugin on demand
            // so the user can tweak its values before enabling it.
            cell.getSettingsButton().setOnClickListener(v -> {
                PluginManager pm = PluginManager.getInstance();
                PluginManager.LoadedPlugin loaded = pm.findPlugin(info.fileName);
                if (loaded == null) {
                    // Plugin not loaded (disabled) — load it on demand just to
                    // edit settings. It won't receive hooks until enabled.
                    loaded = pm.loadPluginForSettings(info.fileName);
                }
                if (loaded != null) {
                    hostActivity().presentFragment(new PluginSettingsActivity().setPlugin(loaded));
                }
            });

            // Share button: send plugin file via any app (Telegram, etc.).
            cell.getShareButton().setOnClickListener(v -> {
                Activity a = hostActivity().getParentActivity();
                if (a == null) return;
                try {
                    File pluginFile = new File(PluginManager.getPluginsDir(), info.fileName);
                    Uri uri = FileProvider.getUriForFile(a,
                            a.getPackageName() + ".provider", pluginFile);
                    Intent shareIntent = new Intent(Intent.ACTION_SEND);
                    shareIntent.setType("application/octet-stream");
                    shareIntent.putExtra(Intent.EXTRA_STREAM, uri);
                    shareIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    a.startActivity(Intent.createChooser(shareIntent,
                            LocaleController.getString(R.string.ShareFile)));
                } catch (Exception e) {
                    FileLog.e(e);
                }
            });

            // Delete button: confirm via BottomSheet, then remove.
            cell.getDeleteButton().setOnClickListener(v -> {
                Activity a = hostActivity().getParentActivity();
                if (a == null) return;
                BottomSheet.Builder builder = new BottomSheet.Builder(a, false, resourcesProvider);
                LinearLayout sheet = new LinearLayout(a);
                sheet.setOrientation(LinearLayout.VERTICAL);
                int pad = AndroidUtilities.dp(24);
                sheet.setPadding(pad, AndroidUtilities.dp(20), pad, pad);

                TextView title = new TextView(a);
                title.setText(LocaleController.getString(R.string.Delete));
                title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
                title.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
                title.setTextColor(Theme.getColor(Theme.key_text_RedBold, resourcesProvider));
                title.setPadding(0, 0, 0, AndroidUtilities.dp(12));
                sheet.addView(title);

                TextView msg = new TextView(a);
                msg.setText(LocaleController.formatString(R.string.PluginsDeleteConfirm,
                        info.name != null ? info.name : info.fileName));
                msg.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
                msg.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
                msg.setPadding(0, 0, 0, AndroidUtilities.dp(16));
                sheet.addView(msg);

                final BottomSheet[] sheetRef = new BottomSheet[1];
                TextView deleteBtn = new TextView(a);
                deleteBtn.setText(LocaleController.getString(R.string.Delete));
                deleteBtn.setTextColor(0xffffffff);
                deleteBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                deleteBtn.setTypeface(AndroidUtilities.getTypeface("fonts/rmedium.ttf"));
                deleteBtn.setGravity(android.view.Gravity.CENTER);
                deleteBtn.setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(24),
                        Theme.getColor(Theme.key_text_RedBold, resourcesProvider)));
                deleteBtn.setPadding(0, AndroidUtilities.dp(14), 0, AndroidUtilities.dp(14));
                deleteBtn.setOnClickListener(v2 -> {
                    PluginManager.getInstance().remove(info.fileName);
                    BulletinFactory.of(hostActivity()).createSimpleBulletin(R.raw.chats_infotip,
                            LocaleController.getString(R.string.PluginsRemoved)).show();
                    if (sheetRef[0] != null) sheetRef[0].dismiss();
                    hostActivity().updateRows();
                });
                sheet.addView(deleteBtn, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));

                TextView cancelBtn = new TextView(a);
                cancelBtn.setText(LocaleController.getString(R.string.Cancel));
                cancelBtn.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
                cancelBtn.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                cancelBtn.setGravity(android.view.Gravity.CENTER);
                cancelBtn.setPadding(0, AndroidUtilities.dp(14), 0, AndroidUtilities.dp(4));
                cancelBtn.setOnClickListener(v2 -> { if (sheetRef[0] != null) sheetRef[0].dismiss(); });
                sheet.addView(cancelBtn);

                builder.setCustomView(sheet);
                sheetRef[0] = builder.show();
            });
        }

        @Override
        public boolean isClickable() {
            // The card itself isn't clickable as a whole — the toggle and the
            // two buttons handle their own clicks.
            return false;
        }

        private PluginManager.PluginInfo findInfoById(int itemId) {
            NekoPluginsActivity host = hostActivity();
            if (host == null) return null;
            for (Map.Entry<Integer, PluginManager.PluginInfo> e : host.pluginRows.entrySet()) {
                if (e.getKey() == itemId) return e.getValue();
            }
            return null;
        }

        private NekoPluginsActivity hostActivity() {
            return PluginCardHost.host;
        }

        public static UItem of(int id, CharSequence title, CharSequence desc, CharSequence author, CharSequence version, boolean checked, boolean enabled) {
            UItem item = UItem.ofFactory(PluginCardFactory.class);
            item.id = id;
            item.text = title;
            item.subtext = desc;
            item.textValue = author;
            item.texts = (version != null) ? new String[]{version.toString()} : null;
            item.checked = checked;
            item.enabled = enabled;
            return item;
        }
    }

    /** Lets the static factory reach the host activity's plugin map. */
    private static class PluginCardHost {
        static NekoPluginsActivity host;
    }
}
