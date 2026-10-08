package zxc.iconic.xenon.helpers;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Stories.recorder.ButtonWithCounterView;

import java.io.File;
import java.io.FileOutputStream;

import androidx.core.content.FileProvider;

import zxc.iconic.xenon.NekoConfig;

public class CloudSettingsHelper {

    private static final class InstanceHolder {
        private static final CloudSettingsHelper instance = new CloudSettingsHelper();
    }

    public static CloudSettingsHelper getInstance() {
        return InstanceHolder.instance;
    }

    public void showDialog(BaseFragment parentFragment) {
        if (parentFragment == null) {
            return;
        }

        Context context = parentFragment.getParentActivity();
        Theme.ResourcesProvider resourcesProvider = parentFragment.getResourceProvider();

        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(LocaleController.getString(R.string.CloudConfig));
        builder.setMessage(AndroidUtilities.replaceTags(LocaleController.getString(R.string.XenonCloudBackupDescription)));
        builder.setTopImage(R.drawable.cloud, Theme.getColor(Theme.key_dialogTopBackground, resourcesProvider));

        LinearLayout linearLayout = new LinearLayout(context);
        linearLayout.setOrientation(LinearLayout.VERTICAL);

        ButtonWithCounterView saveButton = new ButtonWithCounterView(context, true, resourcesProvider).setRound();
        saveButton.setText(LocaleController.getString(R.string.XenonSaveToSavedMessages), false);
        linearLayout.addView(saveButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 0, 16, 0));
        saveButton.setOnClickListener(view -> {
            String caption = AutoBackupService.saveNow(AutoBackupService.targetAccount(parentFragment.getCurrentAccount()));
            BulletinFactory.global().createSimpleBulletin(R.raw.chats_infotip,
                    LocaleController.getString(caption == null ? R.string.XenonBackupSaveFailed : R.string.XenonBackupQueued)).show();
        });
        saveButton.setOnLongClickListener(view -> {
            try {
                String json = NekoConfig.exportConfigs();
                File dir = new File(ApplicationLoader.applicationContext.getFilesDir(), "cache");
                if (!dir.exists()) dir.mkdirs();
                File file = new File(dir, "xenon_settings_backup.json");
                try (FileOutputStream fos = new FileOutputStream(file)) {
                    fos.write(json.getBytes("UTF-8"));
                }
                Uri uri = FileProvider.getUriForFile(context, context.getPackageName() + ".provider", file);
                Intent share = new Intent(Intent.ACTION_SEND);
                share.setType("application/json");
                share.putExtra(Intent.EXTRA_STREAM, uri);
                share.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                context.startActivity(Intent.createChooser(share, LocaleController.getString(R.string.SaveSettingsToFile)));
            } catch (Exception e) {
                FileLog.e(e);
                BulletinFactory.global().createSimpleBulletin(R.raw.chats_infotip, LocaleController.getString(R.string.XenonBackupSaveFailed)).show();
            }
            return true;
        });

        ButtonWithCounterView restoreButton = new ButtonWithCounterView(context, false, resourcesProvider).setRound();
        restoreButton.setText(LocaleController.getString(R.string.XenonRestoreLastFromSavedMessages), false);
        linearLayout.addView(restoreButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 16, 8, 16, 0));
        restoreButton.setOnClickListener(view -> {
            restoreButton.setLoading(true);
            restoreButton.setEnabled(false);
            CloudBackupRestore.restore(AutoBackupService.targetAccount(parentFragment.getCurrentAccount()), result -> {
                restoreButton.setLoading(false);
                restoreButton.setEnabled(true);
                BulletinFactory.global().createSimpleBulletin(R.raw.chats_infotip, LocaleController.getString(result)).show();
            });
        });
        restoreButton.setOnLongClickListener(view -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*");
            parentFragment.startActivityForResult(intent, 2001);
            return true;
        });

        android.widget.ScrollView scroll = new android.widget.ScrollView(context);
        addAutoBackupControls(parentFragment, linearLayout, context, resourcesProvider);
        scroll.addView(linearLayout);
        builder.setView(scroll);
        parentFragment.showDialog(builder.create());
    }

    private void addAutoBackupControls(BaseFragment fragment, LinearLayout layout, Context context, Theme.ResourcesProvider provider) {
        org.telegram.ui.Cells.TextCheckCell enabled = new org.telegram.ui.Cells.TextCheckCell(context);
        enabled.setTextAndCheck(LocaleController.getString(R.string.XenonAutoBackup), AutoBackupService.enabled(), false);
        layout.addView(enabled, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56));
        LinearLayout options = new LinearLayout(context);
        options.setOrientation(LinearLayout.VERTICAL);
        layout.addView(options, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        options.setVisibility(AutoBackupService.enabled() ? android.view.View.VISIBLE : android.view.View.GONE);
        enabled.setOnClickListener(v -> {
            AutoBackupService.setEnabled(!AutoBackupService.enabled());
            enabled.setChecked(AutoBackupService.enabled());
            options.setVisibility(AutoBackupService.enabled() ? android.view.View.VISIBLE : android.view.View.GONE);
        });
        org.telegram.ui.Cells.TextSettingsCell interval = new org.telegram.ui.Cells.TextSettingsCell(context);
        Runnable refresh = () -> interval.setTextAndValue(LocaleController.getString(R.string.XenonAutoBackupInterval), formatBackupInterval(AutoBackupService.interval()), false);
        refresh.run();
        options.addView(interval, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56));
        interval.setOnClickListener(v -> {
            int[] labels = {R.string.XenonBackupHour, R.string.XenonBackup12Hours, R.string.XenonBackupDay, R.string.XenonBackupWeek, R.string.XenonBackupMonth, R.string.XenonBackupCustom};
            CharSequence[] items = new CharSequence[labels.length];
            for (int i = 0; i < labels.length; i++) items[i] = LocaleController.getString(labels[i]);
            long[] durations = {3_600_000L, 43_200_000L, 86_400_000L, 604_800_000L, 2_592_000_000L};
            fragment.showDialog(new AlertDialog.Builder(context, provider).setTitle(LocaleController.getString(R.string.XenonAutoBackupInterval))
                    .setItems(items, (dialog, which) -> {
                        if (which < durations.length) { AutoBackupService.setInterval(durations[which]); refresh.run(); }
                        else showCustomInterval(fragment, context, provider, refresh);
                    }).create());
        });
        org.telegram.ui.Cells.TextCheckCell changed = new org.telegram.ui.Cells.TextCheckCell(context);
        changed.setTextAndCheck(LocaleController.getString(R.string.XenonBackupOnlyChanged), AutoBackupService.prefs().getBoolean("onlyChanged", true), false);
        options.addView(changed, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56));
        changed.setOnClickListener(v -> {
            boolean value = !AutoBackupService.prefs().getBoolean("onlyChanged", true);
            AutoBackupService.prefs().edit().putBoolean("onlyChanged", value).apply();
            changed.setChecked(value);
        });
        java.util.ArrayList<Integer> accounts = new java.util.ArrayList<>();
        java.util.ArrayList<CharSequence> names = new java.util.ArrayList<>();
        for (int i = 0; i < org.telegram.messenger.UserConfig.MAX_ACCOUNT_COUNT; i++) {
            org.telegram.tgnet.TLRPC.User user = org.telegram.messenger.UserConfig.getInstance(i).getCurrentUser();
            if (user != null && org.telegram.messenger.UserConfig.getInstance(i).isClientActivated()) {
                accounts.add(i);
                names.add(org.telegram.messenger.ContactsController.formatName(user.first_name, user.last_name));
            }
        }
        if (accounts.size() > 1) {
            org.telegram.ui.Cells.TextSettingsCell account = new org.telegram.ui.Cells.TextSettingsCell(context);
            Runnable update = () -> {
                int selected = accounts.indexOf(AutoBackupService.targetAccount(fragment.getCurrentAccount()));
                account.setTextAndValue(LocaleController.getString(R.string.XenonBackupAccount), selected >= 0 ? names.get(selected).toString() : LocaleController.getString(R.string.XenonBackupChooseAccount), false);
            };
            update.run();
            layout.addView(account, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 56));
            account.setOnClickListener(v -> fragment.showDialog(new AlertDialog.Builder(context, provider)
                    .setTitle(LocaleController.getString(R.string.XenonBackupAccount)).setItems(names.toArray(new CharSequence[0]), (dialog, which) -> {
                        AutoBackupService.selectAccount(accounts.get(which)); update.run();
                        if (AutoBackupService.enabled()) AutoBackupService.runBackup(true);
                    }).create()));
        }
        android.widget.TextView hint = new android.widget.TextView(context);
        hint.setText(LocaleController.getString(R.string.XenonBackupHint));
        hint.setTextColor(Theme.getColor(Theme.key_dialogTextGray, provider));
        hint.setTextSize(14);
        options.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 16, 8, 16, 16));
    }

    private String formatBackupInterval(long millis) {
        long totalMinutes = millis / 60_000;
        long minutes = totalMinutes % 60, hours = totalMinutes / 60 % 24, days = totalMinutes / 1440;
        if (days == 0 && hours == 0) return LocaleController.formatPluralString("Minutes", (int) minutes);
        if (days == 0 && minutes == 0) return LocaleController.formatPluralString("Hours", (int) hours);
        if (minutes == 0 && hours == 0 && days <= Integer.MAX_VALUE) return LocaleController.formatPluralString("Days", (int) days);
        return AutoBackupPolicy.formatDisplayInterval(millis, LocaleController.getString(R.string.XenonBackupMinuteUnit),
                LocaleController.getString(R.string.XenonBackupHourUnit), LocaleController.getString(R.string.XenonBackupDayUnit));
    }

    private void showCustomInterval(BaseFragment fragment, Context context, Theme.ResourcesProvider provider, Runnable refresh) {
        LinearLayout content = new LinearLayout(context);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(AndroidUtilities.dp(24), 0, AndroidUtilities.dp(24), AndroidUtilities.dp(16));
        android.widget.TextView description = new android.widget.TextView(context);
        description.setText(LocaleController.getString(R.string.XenonBackupCustomHint));
        description.setTextSize(14);
        description.setTextColor(Theme.getColor(Theme.key_dialogTextGray, provider));
        content.addView(description, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 16));
        org.telegram.ui.Components.EditTextBoldCursor input = new org.telegram.ui.Components.EditTextBoldCursor(context);
        input.setText(AutoBackupPolicy.formatInterval(AutoBackupService.interval()));
        input.setSingleLine(true);
        input.setTextSize(18);
        input.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, provider));
        input.setHintTextColor(Theme.getColor(Theme.key_dialogTextHint, provider));
        input.setCursorColor(Theme.getColor(Theme.key_dialogInputFieldActivated, provider));
        input.setHint(LocaleController.getString(R.string.XenonBackupIntervalPlaceholder));
        input.setBackground(Theme.createEditTextDrawable(context, Theme.getColor(Theme.key_dialogInputField, provider),
                Theme.getColor(Theme.key_dialogInputFieldActivated, provider)));
        content.addView(input, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 0, 0, 16));
        ButtonWithCounterView save = new ButtonWithCounterView(context, true, provider).setRound();
        save.setText(LocaleController.getString(R.string.Save), false);
        content.addView(save, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48));
        ButtonWithCounterView cancel = new ButtonWithCounterView(context, false, provider).setRound();
        cancel.setText(LocaleController.getString(R.string.Cancel), false);
        content.addView(cancel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 48, 0, 8, 0, 0));
        org.telegram.ui.ActionBar.BottomSheet sheet = new org.telegram.ui.ActionBar.BottomSheet.Builder(context, true, provider)
                .setTitle(LocaleController.getString(R.string.XenonBackupCustom)).setCustomView(content).create();
        save.setOnClickListener(v -> {
            try {
                AutoBackupService.setInterval(AutoBackupPolicy.parseInterval(input.getText().toString()));
                refresh.run();
                AndroidUtilities.hideKeyboard(input);
                sheet.dismiss();
            } catch (IllegalArgumentException e) { input.setError(LocaleController.getString(R.string.XenonBackupInvalidInterval)); }
        });
        cancel.setOnClickListener(v -> { AndroidUtilities.hideKeyboard(input); sheet.dismiss(); });
        fragment.showDialog(sheet);
    }

    public void doAutoSync() {
    }
}
