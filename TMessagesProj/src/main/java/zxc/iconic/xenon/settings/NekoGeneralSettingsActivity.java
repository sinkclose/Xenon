package zxc.iconic.xenon.settings;

import android.text.TextUtils;
import android.view.View;

import androidx.core.text.HtmlCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

import zxc.iconic.xenon.NekoConfig;
import zxc.iconic.xenon.helpers.ApkInstaller;
import zxc.iconic.xenon.helpers.PopupHelper;
import zxc.iconic.xenon.helpers.remote.GitHubUpdateHelper;
import zxc.iconic.xenon.translator.Translator;
import zxc.iconic.xenon.translator.TranslatorApps;

import tw.nekomimi.nekogram.translator.deepl.DeepLOAuth;

public class NekoGeneralSettingsActivity extends BaseNekoSettingsActivity {

    private final int ipv6Row = rowId++;

    private final int showOriginalRow = rowId++;
    private final int translatorTypeRow = rowId++;
    private final int translatorExternalAppRow = rowId++;
    private final int deeplAuthRow = rowId++;
    private final int translationProviderRow = rowId++;
    private final int translationTargetRow = rowId++;
    private final int doNotTranslateRow = rowId++;
    private final int autoTranslateRow = rowId++;

    private final int accentAsNotificationColorRow = rowId++;
    private final int silenceNonContactsRow = rowId++;

    private final int nameOrderRow = rowId++;
    private final int idTypeRow = rowId++;
    private final int hidePhoneNumberRow = rowId++;

    private final int disabledInstantCameraRow = rowId++;
    private final int askBeforeCallRow = rowId++;
    private final int openArchiveOnPullRow = rowId++;
    private final int autoCheckUpdateRow = rowId++;
    private final int autoDownloadUpdateRow = rowId++;
    private final int switchToAyuRow = rowId++;

    private CharSequence getTranslationProvider() {
        var providers = Translator.getProviders();
        var names = providers.first;
        var types = providers.second;
        if (names == null || types == null) {
            return "";
        }
        int index = types.indexOf(NekoConfig.translationProvider);
        if (index < 0) {
            return "";
        } else {
            return names.get(index);
        }
    }

    private CharSequence getTranslationTarget() {
        var language = NekoConfig.translationTarget;
        CharSequence value;
        if (language.equals("app")) {
            value = LocaleController.getString(R.string.TranslationTargetApp);
        } else {
            Locale locale = Locale.forLanguageTag(language);
            if (!TextUtils.isEmpty(locale.getScript())) {
                value = HtmlCompat.fromHtml(locale.getDisplayScript(), HtmlCompat.FROM_HTML_MODE_LEGACY);
            } else {
                value = locale.getDisplayName();
            }
        }
        return value;
    }

    private CharSequence getRestrictedLanguages() {
        var langCodes = Translator.getRestrictedLanguages();
        CharSequence value;
        if (langCodes.size() == 1) {
            Locale locale = Locale.forLanguageTag(langCodes.get(0));
            if (!TextUtils.isEmpty(locale.getScript())) {
                value = HtmlCompat.fromHtml(locale.getDisplayScript(), HtmlCompat.FROM_HTML_MODE_LEGACY);
            } else {
                value = locale.getDisplayName();
            }
        } else {
            value = LocaleController.formatPluralString("Languages", langCodes.size());
        }
        return value;
    }

    private CharSequence getTranslatorType() {
        return switch (NekoConfig.transType) {
            case NekoConfig.TRANS_TYPE_TG -> LocaleController.getString(R.string.TranslatorTypeTG);
            case NekoConfig.TRANS_TYPE_EXTERNAL ->
                    LocaleController.getString(R.string.TranslatorTypeExternal);
            default -> LocaleController.getString(R.string.TranslatorTypeNeko);
        };
    }

    private CharSequence getTranslatorExternalApp() {
        var app = TranslatorApps.getTranslatorApp();
        return app == null ? "" : app.title;
    }

    private CharSequence getDeepLState() {
        var idInfo = DeepLOAuth.getIdInfo();
        if (idInfo == null) {
            return LocaleController.getString(R.string.BotAuthLogin);
        } else {
            var email = idInfo.email;
            var atIndex = email.indexOf('@');
            var localPart = email.substring(0, atIndex);
            var domainPart = email.substring(atIndex);
            var visiblePart = localPart.length() <= 3
                    ? localPart
                    : localPart.substring(0, 3);
            return visiblePart + "..." + domainPart;
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.Connection)));
        items.add(UItem.asCheck(ipv6Row, LocaleController.getString(R.string.PreferIPv6)).slug("ipv6").setChecked(NekoConfig.preferIPv6));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.Translator)));
        items.add(TextSettingsCellFactory.of(translatorTypeRow, LocaleController.getString(R.string.TranslatorType), getTranslatorType()).slug("translatorType"));
        if (NekoConfig.transType != NekoConfig.TRANS_TYPE_EXTERNAL) {
            if (NekoConfig.transType == NekoConfig.TRANS_TYPE_NEKO) {
                items.add(UItem.asCheck(showOriginalRow, LocaleController.getString(R.string.TranslatorShowOriginal)).slug("showOriginalRow").setChecked(NekoConfig.showOriginal));
            }
            items.add(TextSettingsCellFactory.of(translationProviderRow, LocaleController.getString(R.string.TranslationProviderShort), getTranslationProvider()).slug("translationProvider"));
            if (Translator.PROVIDER_DEEPL.equals(NekoConfig.translationProvider)) {
                items.add(TextSettingsCellFactory.of(deeplAuthRow, LocaleController.getString(R.string.ProviderDeepLTranslate), getDeepLState()).slug("deeplAUth"));
            }
            items.add(TextSettingsCellFactory.of(translationTargetRow, LocaleController.getString(R.string.TranslationTarget), getTranslationTarget()).slug("translationTarget"));
            items.add(TextSettingsCellFactory.of(doNotTranslateRow, LocaleController.getString(R.string.DoNotTranslate), getRestrictedLanguages()).slug("doNotTranslate"));
            items.add(UItem.asCheck(autoTranslateRow, LocaleController.getString(R.string.AutoTranslate), LocaleController.getString(R.string.AutoTranslateAbout)).slug("autoTranslate").setChecked(NekoConfig.autoTranslate));
        } else {
            items.add(TextSettingsCellFactory.of(translatorExternalAppRow, LocaleController.getString(R.string.TranslationProviderShort), getTranslatorExternalApp()).slug("translatorExternalApp"));
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.TranslateMessagesInfo1)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.Notifications)));
        items.add(UItem.asCheck(accentAsNotificationColorRow, LocaleController.getString(R.string.AccentAsNotificationColor)).slug("accentAsNotificationColor").setChecked(NekoConfig.accentAsNotificationColor));
        items.add(UItem.asCheck(silenceNonContactsRow, LocaleController.getString(R.string.SilenceNonContacts)).slug("silenceNonContacts").setChecked(NekoConfig.silenceNonContacts));
        items.add(UItem.asShadow(LocaleController.getString(R.string.SilenceNonContactsAbout)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.UserColorTabProfile)));
        items.add(TextSettingsCellFactory.of(nameOrderRow, LocaleController.getString(R.string.NameOrder), switch (NekoConfig.nameOrder) {
            case 2 -> LocaleController.getString(R.string.LastFirst);
            default -> LocaleController.getString(R.string.FirstLast);
        }).slug("nameOrder"));
        items.add(TextSettingsCellFactory.of(idTypeRow, LocaleController.getString(R.string.IdType), switch (NekoConfig.idType) {
            case NekoConfig.ID_TYPE_HIDDEN -> LocaleController.getString(R.string.IdTypeHidden);
            case NekoConfig.ID_TYPE_BOTAPI -> LocaleController.getString(R.string.IdTypeBOTAPI);
            default -> LocaleController.getString(R.string.IdTypeAPI);
        }).slug("idType"));
        items.add(UItem.asCheck(hidePhoneNumberRow, LocaleController.getString(R.string.HidePhoneNumber)).slug("hidePhoneNumber").setChecked(NekoConfig.hidePhoneNumber));
        items.add(UItem.asShadow(LocaleController.getString(R.string.IdTypeAbout)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.General)));
        items.add(UItem.asCheck(disabledInstantCameraRow, LocaleController.getString(R.string.DisableInstantCamera)).slug("disabledInstantCamera").setChecked(NekoConfig.disableInstantCamera));
        items.add(UItem.asCheck(askBeforeCallRow, LocaleController.getString(R.string.AskBeforeCalling)).slug("askBeforeCall").setChecked(NekoConfig.askBeforeCall));
        items.add(UItem.asCheck(openArchiveOnPullRow, LocaleController.getString(R.string.OpenArchiveOnPull)).slug("openArchiveOnPull").setChecked(NekoConfig.openArchiveOnPull));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.UpdatesHeader)));
        items.add(UItem.asCheck(autoCheckUpdateRow, LocaleController.getString(R.string.AutoCheckUpdate)).slug("autoCheckUpdate").setChecked(NekoConfig.autoCheckUpdate));
        items.add(UItem.asCheck(autoDownloadUpdateRow, LocaleController.getString(R.string.AutoDownloadUpdate)).slug("autoDownloadUpdate").setChecked(NekoConfig.autoDownloadUpdate));
        items.add(UItem.asShadow(null));

        items.add(UItem.asButton(switchToAyuRow, R.drawable.msg_download, LocaleController.getString(R.string.SwitchToAyu)).slug("switchToAyu"));
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        var id = item.id;
        if (id == ipv6Row) {
            NekoConfig.toggleIPv6();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.preferIPv6);
            }
            for (int a = 0; a < UserConfig.MAX_ACCOUNT_COUNT; a++) {
                if (UserConfig.getInstance(a).isClientActivated()) {
                    ConnectionsManager.getInstance(a).checkConnection();
                }
            }
        } else if (id == disabledInstantCameraRow) {
            NekoConfig.toggleDisabledInstantCamera();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.disableInstantCamera);
            }
        } else if (id == nameOrderRow) {
            ArrayList<String> arrayList = new ArrayList<>();
            ArrayList<Integer> types = new ArrayList<>();
            arrayList.add(LocaleController.getString(R.string.FirstLast));
            types.add(1);
            arrayList.add(LocaleController.getString(R.string.LastFirst));
            types.add(2);
            showPopup(arrayList, types.indexOf(NekoConfig.nameOrder), item, view, i -> {
                NekoConfig.setNameOrder(types.get(i));
                listView.adapter.notifyItemChanged(position, PARTIAL);
                parentLayout.rebuildAllFragmentViews(false, false);
            });
        } else if (id == translationProviderRow) {
            var oldProvider = NekoConfig.translationProvider;
            Translator.showTranslationProviderSelector(this, view, param -> {
                item.textValue = getTranslationProvider();
                listView.adapter.notifyItemChanged(position, PARTIAL);
                if (!param) {
                    updateLanguageItems();
                }
                var newProvider = NekoConfig.translationProvider;
                if (!oldProvider.equals(newProvider)) {
                    if (Translator.PROVIDER_DEEPL.equals(oldProvider)) {
                        notifyItemRemoved(deeplAuthRow);
                        updateRows();
                    } else if (Translator.PROVIDER_DEEPL.equals(newProvider)) {
                        updateRows();
                        notifyItemInserted(deeplAuthRow);
                    }
                }
            });
        } else if (id == translationTargetRow) {
            Translator.showTranslationTargetSelector(this, view, this::updateLanguageItems);
        } else if (id == openArchiveOnPullRow) {
            NekoConfig.toggleOpenArchiveOnPull();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.openArchiveOnPull);
            }
        } else if (id == autoCheckUpdateRow) {
            NekoConfig.toggleAutoCheckUpdate();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.autoCheckUpdate);
            }
        } else if (id == autoDownloadUpdateRow) {
            NekoConfig.toggleAutoDownloadUpdate();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.autoDownloadUpdate);
            }
            if (NekoConfig.autoDownloadUpdate) {
                if (getParentActivity() instanceof org.telegram.ui.LaunchActivity) {
                    ((org.telegram.ui.LaunchActivity) getParentActivity()).startAutoUpdateCheck();
                }
            }
        } else if (id == askBeforeCallRow) {
            NekoConfig.toggleAskBeforeCall();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.askBeforeCall);
            }
        } else if (id == idTypeRow) {
            ArrayList<String> arrayList = new ArrayList<>();
            ArrayList<Integer> types = new ArrayList<>();
            arrayList.add(LocaleController.getString(R.string.IdTypeHidden));
            types.add(NekoConfig.ID_TYPE_HIDDEN);
            arrayList.add(LocaleController.getString(R.string.IdTypeAPI));
            types.add(NekoConfig.ID_TYPE_API);
            arrayList.add(LocaleController.getString(R.string.IdTypeBOTAPI));
            types.add(NekoConfig.ID_TYPE_BOTAPI);
            showPopup(arrayList, types.indexOf(NekoConfig.idType), item, view, i -> {
                NekoConfig.setIdType(types.get(i));
                listView.adapter.notifyItemChanged(position, PARTIAL);
                parentLayout.rebuildAllFragmentViews(false, false);
            }, resourcesProvider);
        } else if (id == hidePhoneNumberRow) {
            NekoConfig.toggleHidePhoneNumber();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.hidePhoneNumber);
            }
            parentLayout.rebuildAllFragmentViews(false, false);
        } else if (id == accentAsNotificationColorRow) {
            NekoConfig.toggleAccentAsNotificationColor();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.accentAsNotificationColor);
            }
        } else if (id == silenceNonContactsRow) {
            NekoConfig.toggleSilenceNonContacts();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.silenceNonContacts);
            }
        } else if (id == translatorTypeRow) {
            int oldType = NekoConfig.transType;
            Translator.showTranslatorTypeSelector(this, view, () -> {
                int newType = NekoConfig.transType;
                item.textValue = getTranslatorType();
                listView.adapter.notifyItemChanged(position, PARTIAL);
                if (oldType != newType) {
                    int count = 4;
                    if (oldType == NekoConfig.TRANS_TYPE_NEKO || newType == NekoConfig.TRANS_TYPE_NEKO) {
                        count++;
                    }
                    if (Translator.PROVIDER_DEEPL.equals(NekoConfig.translationProvider)) {
                        count++;
                    }
                    if (oldType == NekoConfig.TRANS_TYPE_EXTERNAL) {
                        notifyItemRemoved(translatorExternalAppRow);
                        updateRows();
                        notifyItemRangeInserted(translationProviderRow, count);
                    } else if (newType == NekoConfig.TRANS_TYPE_EXTERNAL) {
                        notifyItemRangeRemoved(translationProviderRow, count);
                        updateRows();
                        notifyItemInserted(translatorExternalAppRow);
                    } else if (oldType == NekoConfig.TRANS_TYPE_NEKO) {
                        notifyItemRemoved(showOriginalRow);
                        updateRows();
                    } else if (newType == NekoConfig.TRANS_TYPE_NEKO) {
                        updateRows();
                        notifyItemInserted(showOriginalRow);
                    }
                }
            });
        } else if (id == doNotTranslateRow) {
            presentFragment(new NekoLanguagesSelectActivity(NekoLanguagesSelectActivity.TYPE_RESTRICTED));
        } else if (id == autoTranslateRow) {
            NekoConfig.toggleAutoTranslate();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.autoTranslate);
            }
        } else if (id == showOriginalRow) {
            NekoConfig.toggleShowOriginal();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.showOriginal);
            }
        } else if (id == translatorExternalAppRow) {
            Translator.showTranslationProviderSelector(this, view, param -> {
                item.textValue = getTranslatorExternalApp();
                listView.adapter.notifyItemChanged(position, PARTIAL);
            });
        } else if (id == switchToAyuRow) {
            var activity = getParentActivity();
            if (activity == null) return;
            AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            builder.setTitle(LocaleController.getString(R.string.SwitchToAyu));
            builder.setMessage(LocaleController.getString(R.string.SwitchToAyuConfirm));
            builder.setPositiveButton(LocaleController.getString(R.string.OK), (dialog, which) -> {
                var impl = ApplicationLoader.applicationLoaderInstance;
                if (impl != null && impl.isDownloadingUpdate()) {
                    BulletinFactory.of(NekoGeneralSettingsActivity.this).createErrorBulletin("Already downloading").show();
                    return;
                }
                var spinner = new AlertDialog(activity, AlertDialog.ALERT_TYPE_SPINNER);
                spinner.setCanCancel(true);
                spinner.show();
                GitHubUpdateHelper.checkForAyuUpdate(new GitHubUpdateHelper.UpdateCallback() {
                    @Override
                    public void onUpdateAvailable(GitHubUpdateHelper.GitHubRelease release) {
                        try { spinner.dismiss(); } catch (Throwable ignored) {}
                        String apkUrl = GitHubUpdateHelper.findApkDownloadUrl(release);
                        if (apkUrl == null) {
                            BulletinFactory.of(NekoGeneralSettingsActivity.this).createErrorBulletin("No arm64 build available").show();
                            return;
                        }
                        String title = release.name != null ? release.name : release.tagName;
                        var impl = ApplicationLoader.applicationLoaderInstance;
                        final Bulletin[] progBulletin = new Bulletin[1];
                        AndroidUtilities.runOnUIThread(() -> {
                            try {
                                Bulletin b = BulletinFactory.global()
                                        .createSimpleBulletin(R.raw.ic_download, LocaleController.getString(R.string.DownloadingUpdate), LocaleController.getString(R.string.Cancel), Integer.MAX_VALUE, () -> impl.cancelDownloadingUpdate());
                                if (b.getLayout() instanceof Bulletin.LottieLayout) {
                                    ((Bulletin.LottieLayout) b.getLayout()).setIconPaddingBottom(2);
                                }
                                b.show();
                                progBulletin[0] = b;
                            } catch (Throwable ignored) {}
                        }, 100);
                        impl.downloadUpdate(apkUrl, () -> {
                            AndroidUtilities.runOnUIThread(() -> {
                                try { if (progBulletin[0] != null) progBulletin[0].hide(); } catch (Throwable ignored) {}
                            });
                            File apkFile = impl.getDownloadedUpdateFile();
                            if (apkFile != null && apkFile.exists()) {
                                AndroidUtilities.runOnUIThread(() -> {
                                    try {
                                        Bulletin b2 = BulletinFactory.global()
                                                .createSimpleBulletin(R.raw.ic_download,
                                                        LocaleController.getString(R.string.UpdateDownloaded),
                                                        LocaleController.getString(R.string.NekoUpdate),
                                                        Integer.MAX_VALUE,
                                                        () -> ApkInstaller.installUpdate(activity, apkFile));
                                        if (b2.getLayout() instanceof Bulletin.LottieLayout) {
                                            ((Bulletin.LottieLayout) b2.getLayout()).setIconPaddingBottom(2);
                                        }
                                        b2.show();
                                    } catch (Throwable ignored) {}
                                });
                            }
                        });
                        AndroidUtilities.runOnUIThread(new Runnable() {
                            @Override
                            public void run() {
                                if (impl.isDownloadingUpdate() && progBulletin[0] != null) {
                                    if (!impl.isRetryingUpdate()) {
                                        try {
                                            float prog = impl.getDownloadingUpdateProgress();
                                            long total = impl.getDownloadTotalSize();
                                            long downloaded = impl.getDownloadBytesDownloaded();
                                            String text;
                                            if (total > 0) {
                                                String d = android.text.format.Formatter.formatShortFileSize(activity, downloaded);
                                                String t = android.text.format.Formatter.formatShortFileSize(activity, total);
                                                text = LocaleController.getString(R.string.DownloadingUpdate) + " " + d + " / " + t;
                                            } else {
                                                text = LocaleController.getString(R.string.DownloadingUpdate) + " " + (int)(prog * 100) + "%";
                                            }
                                            ((Bulletin.LottieLayout) progBulletin[0].getLayout()).textView.setText(text);
                                        } catch (Throwable ignored) {}
                                    }
                                    AndroidUtilities.runOnUIThread(this, 500);
                                }
                            }
                        }, 500);
                    }

                    @Override
                    public void onNoUpdate() {
                        try { spinner.dismiss(); } catch (Throwable ignored) {}
                        BulletinFactory.of(NekoGeneralSettingsActivity.this).createErrorBulletin("No ayu-features release found").show();
                    }

                    @Override
                    public void onError(String error) {
                        try { spinner.dismiss(); } catch (Throwable ignored) {}
                        BulletinFactory.of(NekoGeneralSettingsActivity.this).createErrorBulletin(error != null ? error : "Unknown error").show();
                    }
                });
            });
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            builder.show();
        } else if (id == deeplAuthRow) {
            var idInfo = DeepLOAuth.getIdInfo();
            if (idInfo != null) {
                var options = ItemOptions.makeOptions(this, view);
                options.setScrimViewBackground(listView.getClipBackground(view));
                options.addText(idInfo.email, 13);
                options.addGap();
                options.add(R.drawable.msg_leave, LocaleController.getString(R.string.LogOut), true, () -> {
                    DeepLOAuth.clearToken();
                    item.textValue = getDeepLState();
                    listView.adapter.notifyItemChanged(position, PARTIAL);
                });
                options.show();
            } else {
                DeepLOAuth.startOAuth(this, () -> {
                    item.textValue = getDeepLState();
                    notifyItemChanged(deeplAuthRow, PARTIAL);
                });
            }
        }
    }

    private void updateLanguageItems() {
        if (listView == null) return;
        var restrictedLanguageItem = listView.findItemByItemId(doNotTranslateRow);
        if (restrictedLanguageItem != null) {
            restrictedLanguageItem.textValue = getRestrictedLanguages();
            notifyItemChanged(doNotTranslateRow, PARTIAL);
        }
        var translationTargetItem = listView.findItemByItemId(translationTargetRow);
        if (translationTargetItem != null) {
            translationTargetItem.textValue = getTranslationTarget();
            notifyItemChanged(translationTargetRow, PARTIAL);
        }
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.General);
    }

    @Override
    protected String getKey() {
        return "g";
    }

    @Override
    public void onResume() {
        super.onResume();
        updateLanguageItems();
    }
}
