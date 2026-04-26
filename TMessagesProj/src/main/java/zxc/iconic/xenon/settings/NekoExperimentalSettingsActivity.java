package zxc.iconic.xenon.settings;

import android.os.CountDownTimer;
import android.util.TypedValue;
import android.view.View;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.Locale;

import zxc.iconic.xenon.Extra;
import zxc.iconic.xenon.NekoConfig;
import zxc.iconic.xenon.helpers.AnalyticsHelper;
import zxc.iconic.xenon.helpers.PopupHelper;
import zxc.iconic.xenon.helpers.SettingsHelper;
import zxc.iconic.xenon.helpers.remote.UpdateHelper;

public class NekoExperimentalSettingsActivity extends BaseNekoSettingsActivity {

    private final int disableTypingRow = rowId++;
    private final int telegaDetectorRow = rowId++;

    private final int liquidGlassIntensityRow = rowId++;
    private final int liquidGlassThicknessRow = rowId++;
    private final int useAdvancedLiquidGlassRow = rowId++;
    private final int useCamera2ApiRow = rowId++;

    private final int downloadSpeedBoostRow = rowId++;
    private final int keepFormattingRow = rowId++;
    private final int forceFontWeightFallbackRow = rowId++;
    private final int contentRestrictionRow = rowId++;
    private final int showRPCErrorRow = rowId++;

    private final int sendBugReportRow = rowId++;
    private final int deleteDataRow = rowId++;
    private final int copyReportIdRow = rowId++;

    private final int deleteAccountRow = rowId++;

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.Experiment)));
        items.add(UItem.asCheck(disableTypingRow, LocaleController.getString(R.string.DisableTypingIndicator), LocaleController.getString(R.string.DisableTypingIndicatorDesc)).slug("disableTyping").setChecked(NekoConfig.disableTypingIndicator));
        items.add(UItem.asCheck(telegaDetectorRow, LocaleController.getString(R.string.TelegaDetectorEnabled), LocaleController.getString(R.string.TelegaDetectorHint)).slug("telegaDetector").setChecked(NekoConfig.telegaDetectorEnabled));
        items.add(UItem.asShadow(null));

        if (android.os.Build.VERSION.SDK_INT >= 33) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.LiquidGlassSettings)));
            items.add(UItem.asCheck(useAdvancedLiquidGlassRow, LocaleController.getString(R.string.UseAdvancedLiquidGlass), LocaleController.getString(R.string.UseAdvancedLiquidGlassDesc)).slug("useAdvancedLiquidGlass").setChecked(NekoConfig.useAdvancedLiquidGlass));
            items.add(TextSettingsCellFactory.of(liquidGlassIntensityRow, LocaleController.getString(R.string.LiquidGlassIntensity), String.format("%.2f", NekoConfig.liquidGlassIntensity)).slug("liquidGlassIntensity").setEnabled(!NekoConfig.useAdvancedLiquidGlass));
            items.add(TextSettingsCellFactory.of(liquidGlassThicknessRow, LocaleController.getString(R.string.LiquidGlassThickness), String.valueOf(NekoConfig.liquidGlassThickness)).slug("liquidGlassThickness").setEnabled(!NekoConfig.useAdvancedLiquidGlass));
            items.add(UItem.asShadow(null));
        }

        items.add(UItem.asHeader(LocaleController.getString(R.string.CameraSettings)));
        items.add(UItem.asCheck(useCamera2ApiRow, LocaleController.getString(R.string.UseCamera2Api), LocaleController.getString(R.string.UseCamera2ApiDesc)).slug("useCamera2Api").setChecked(NekoConfig.useCamera2Api));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.General)));
        if (!MessagesController.getInstance(currentAccount).getfileExperimentalParams) {
            items.add(TextSettingsCellFactory.of(downloadSpeedBoostRow, LocaleController.getString(R.string.DownloadSpeedBoost), switch (NekoConfig.downloadSpeedBoost) {
                case NekoConfig.BOOST_NONE ->
                        LocaleController.getString(R.string.DownloadSpeedBoostNone);
                case NekoConfig.BOOST_EXTREME ->
                        LocaleController.getString(R.string.DownloadSpeedBoostExtreme);
                default -> LocaleController.getString(R.string.DownloadSpeedBoostAverage);
            }).slug("downloadSpeedBoost"));
        }
        items.add(UItem.asCheck(keepFormattingRow, LocaleController.getString(R.string.TranslationKeepFormatting)).slug("keepFormatting").setChecked(NekoConfig.keepFormatting));
        items.add(UItem.asCheck(forceFontWeightFallbackRow, LocaleController.getString(R.string.ForceFontWeightFallback)).slug("forceFontWeightFallback").setChecked(NekoConfig.forceFontWeightFallback));
        if (Extra.isDirectApp()) {
            items.add(UItem.asCheck(contentRestrictionRow, LocaleController.getString(R.string.IgnoreContentRestriction)).slug("contentRestriction").setChecked(NekoConfig.ignoreContentRestriction));
        }
        items.add(UItem.asCheck(showRPCErrorRow, LocaleController.getString(R.string.ShowRPCError), LocaleController.formatString(R.string.ShowRPCErrorException, "FILE_REFERENCE_EXPIRED")).slug("showRPCError").setChecked(NekoConfig.showRPCError));
        items.add(UItem.asShadow(null));

        if (AnalyticsHelper.isSettingsAvailable()) {
            items.add(UItem.asHeader(LocaleController.getString(R.string.SendAnonymousData)));
            items.add(UItem.asCheck(sendBugReportRow, LocaleController.getString(R.string.SendBugReport), LocaleController.getString(R.string.SendBugReportDesc)).slug("sendBugReport").setChecked(!AnalyticsHelper.analyticsDisabled && AnalyticsHelper.sendBugReport).setEnabled(!AnalyticsHelper.analyticsDisabled));
            items.add(TextDetailSettingsCellFactory.of(deleteDataRow, LocaleController.getString(R.string.AnonymousDataDelete), LocaleController.getString(R.string.AnonymousDataDeleteDesc)).slug("deleteData"));
        }
        items.add(TextDetailSettingsCellFactory.of(copyReportIdRow, LocaleController.getString(R.string.CopyReportId), LocaleController.getString(R.string.CopyReportIdDescription)).slug("copyReportId"));
        items.add(UItem.asShadow(!AnalyticsHelper.isSettingsAvailable() ? null : LocaleController.formatString(R.string.SendAnonymousDataDesc, "Firebase Analytics", "Google")));

        items.add(TextSettingsCellFactory.of(deleteAccountRow, LocaleController.getString(R.string.DeleteAccount), "").slug("deleteAccount").red());
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;
        if (id == disableTypingRow) {
            NekoConfig.toggleDisableTypingIndicator();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.disableTypingIndicator);
            }
        } else if (id == telegaDetectorRow) {
            NekoConfig.setTelegaDetectorEnabled(!NekoConfig.telegaDetectorEnabled);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.telegaDetectorEnabled);
            }
        } else if (id == useCamera2ApiRow) {
            NekoConfig.toggleUseCamera2Api();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.useCamera2Api);
            }
        } else if (false) {
            var builder = new AlertDialog.Builder(getParentActivity(), resourcesProvider);
            var message = new TextView(getParentActivity());
            message.setText(getSpannedString(R.string.SoonRemovedOption, "https://t.me/" + LocaleController.getString(R.string.OfficialChannelUsername)));
            message.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            message.setLinkTextColor(getThemedColor(Theme.key_dialogTextLink));
            message.setHighlightColor(getThemedColor(Theme.key_dialogLinkSelection));
            message.setPadding(AndroidUtilities.dp(23), 0, AndroidUtilities.dp(23), 0);
            message.setMovementMethod(new AndroidUtilities.LinkMovementMethodMy());
            message.setTextColor(getThemedColor(Theme.key_dialogTextBlack));
            builder.setView(message);
            builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
            showDialog(builder.create());
        }
        if (id == deleteAccountRow) {
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), resourcesProvider);
            builder.setMessage(LocaleController.getString(R.string.TosDeclineDeleteAccount));
            builder.setTitle(LocaleController.getString(R.string.DeleteAccount));
            builder.setPositiveButton(LocaleController.getString(R.string.Deactivate), (dialog, which) -> {
                if (BuildConfig.DEBUG) return;
                final AlertDialog progressDialog = new AlertDialog(getParentActivity(), AlertDialog.ALERT_TYPE_SPINNER);
                progressDialog.setCanCancel(false);

                ArrayList<TLRPC.Dialog> dialogs = new ArrayList<>(getMessagesController().getAllDialogs());
                for (TLRPC.Dialog TLdialog : dialogs) {
                    if (TLdialog instanceof TLRPC.TL_dialogFolder) {
                        continue;
                    }
                    TLRPC.Peer peer = getMessagesController().getPeer((int) TLdialog.id);
                    if (peer.channel_id != 0) {
                        TLRPC.Chat chat = getMessagesController().getChat(peer.channel_id);
                        if (!chat.broadcast) {
                            getMessageHelper().deleteUserHistoryWithSearch(NekoExperimentalSettingsActivity.this, TLdialog.id);
                        }
                    }
                    if (peer.user_id != 0) {
                        getMessagesController().deleteDialog(TLdialog.id, 0, true);
                    }
                }

                Utilities.globalQueue.postRunnable(() -> {
                    TL_account.deleteAccount req = new TL_account.deleteAccount();
                    req.reason = "Meow";
                    getConnectionsManager().sendRequest(req, (response, error) -> AndroidUtilities.runOnUIThread(() -> {
                        try {
                            progressDialog.dismiss();
                        } catch (Exception e) {
                            FileLog.e(e);
                        }
                        if (response instanceof TLRPC.TL_boolTrue) {
                            getMessagesController().performLogout(0);
                        } else if (error == null || error.code != -1000) {
                            String errorText = LocaleController.getString(R.string.ErrorOccurred);
                            if (error != null) {
                                errorText += "\n" + error.text;
                            }
                            AlertDialog.Builder builder1 = new AlertDialog.Builder(getParentActivity(), resourcesProvider);
                            builder1.setTitle(LocaleController.getString(R.string.AppName));
                            builder1.setMessage(errorText);
                            builder1.setPositiveButton(LocaleController.getString(R.string.OK), null);
                            builder1.show();
                        }
                    }));
                }, 20000);
                progressDialog.show();
            });
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            AlertDialog dialog = builder.create();
            dialog.setOnShowListener(dialog1 -> {
                var button = (TextView) dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                button.setTextColor(getThemedColor(Theme.key_text_RedBold));
                button.setEnabled(false);
                var buttonText = button.getText();
                new CountDownTimer(60000, 100) {
                    @Override
                    public void onTick(long millisUntilFinished) {
                        button.setText(String.format(Locale.getDefault(), "%s (%d)", buttonText, millisUntilFinished / 1000 + 1));
                    }

                    @Override
                    public void onFinish() {
                        button.setText(buttonText);
                        button.setEnabled(true);
                    }
                }.start();
            });
            showDialog(dialog);
        } else if (id == showRPCErrorRow) {
            NekoConfig.toggleShowRPCError();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.showRPCError);
            }
        } else if (id == downloadSpeedBoostRow) {
            ArrayList<String> arrayList = new ArrayList<>();
            ArrayList<Integer> types = new ArrayList<>();
            arrayList.add(LocaleController.getString(R.string.DownloadSpeedBoostNone));
            types.add(NekoConfig.BOOST_NONE);
            arrayList.add(LocaleController.getString(R.string.DownloadSpeedBoostAverage));
            types.add(NekoConfig.BOOST_AVERAGE);
            arrayList.add(LocaleController.getString(R.string.DownloadSpeedBoostExtreme));
            types.add(NekoConfig.BOOST_EXTREME);
            PopupHelper.show(arrayList, LocaleController.getString(R.string.DownloadSpeedBoost), types.indexOf(NekoConfig.downloadSpeedBoost), getParentActivity(), view, i -> {
                NekoConfig.setDownloadSpeedBoost(types.get(i));
                item.textValue = arrayList.get(i);
                listView.adapter.notifyItemChanged(position, PARTIAL);
            }, resourcesProvider);
        } else if (id == sendBugReportRow) {
            if (AnalyticsHelper.analyticsDisabled) {
                return;
            }
            AnalyticsHelper.toggleSendBugReport();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(AnalyticsHelper.sendBugReport);
            }
            var copyItem = listView.findItemByItemId(copyReportIdRow);
            copyItem.setEnabled(AnalyticsHelper.sendBugReport);
            notifyItemChanged(copyReportIdRow);
        } else if (id == deleteDataRow) {
            if (AnalyticsHelper.analyticsDisabled) {
                return;
            }
            AlertDialog.Builder builder = new AlertDialog.Builder(getParentActivity(), resourcesProvider);
            builder.setTitle(LocaleController.getString(R.string.AnonymousDataDelete));
            builder.setMessage(LocaleController.getString(R.string.AnonymousDataDeleteDesc));
            builder.setPositiveButton(LocaleController.getString(R.string.Delete), (dialog, which) -> {
                AnalyticsHelper.setAnalyticsDisabled();
                listView.adapter.update(true);
            });
            builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
            AlertDialog dialog = builder.create();
            showDialog(dialog);
            dialog.redPositive();
        } else if (id == contentRestrictionRow) {
            NekoConfig.toggleIgnoreContentRestriction();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.ignoreContentRestriction);
            }
        } else if (id == copyReportIdRow) {
            if (AnalyticsHelper.analyticsDisabled || !AnalyticsHelper.sendBugReport) {
                return;
            }
            SettingsHelper.copyReportId();
        } else if (id == forceFontWeightFallbackRow) {
            NekoConfig.toggleForceFontWeightFallback();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.forceFontWeightFallback);
            }
            showRestartBulletin();
        } else if (id == keepFormattingRow) {
            NekoConfig.toggleKeepFormatting();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.keepFormatting);
            }
        } else if (id == liquidGlassIntensityRow) {
            ArrayList<String> arrayList = new ArrayList<>();
            ArrayList<Float> values = new ArrayList<>();
            for (float v = 0.0f; v <= 1.5f; v += 0.25f) {
                arrayList.add(String.format("%.2f", v));
                values.add(v);
            }
            int current = 0;
            for (int i = 0; i < values.size(); i++) {
                if (Math.abs(values.get(i) - NekoConfig.liquidGlassIntensity) < 0.01f) {
                    current = i;
                    break;
                }
            }
            PopupHelper.show(arrayList, LocaleController.getString(R.string.LiquidGlassIntensity), current, getParentActivity(), view, i -> {
                NekoConfig.setLiquidGlassIntensity(values.get(i));
                item.textValue = arrayList.get(i);
                listView.adapter.notifyItemChanged(position, PARTIAL);
            }, resourcesProvider);
        } else if (id == liquidGlassThicknessRow) {
            ArrayList<String> arrayList = new ArrayList<>();
            ArrayList<Integer> values = new ArrayList<>();
            for (int v = 5; v <= 20; v += 1) {
                arrayList.add(String.valueOf(v));
                values.add(v);
            }
            PopupHelper.show(arrayList, LocaleController.getString(R.string.LiquidGlassThickness), values.indexOf(NekoConfig.liquidGlassThickness), getParentActivity(), view, i -> {
                NekoConfig.setLiquidGlassThickness(values.get(i));
                item.textValue = arrayList.get(i);
                listView.adapter.notifyItemChanged(position, PARTIAL);
            }, resourcesProvider);
        } else if (id == useAdvancedLiquidGlassRow) {
            NekoConfig.toggleUseAdvancedLiquidGlass();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.useAdvancedLiquidGlass);
            }
            listView.adapter.update(true);
            showRestartBulletin();
        }
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.NotificationsOther);
    }

    @Override
    protected String getKey() {
        return "e";
    }

    @Override
    public Integer getSelectorColor(int position) {
        var item = listView.adapter.getItem(position);
        if (item.id == deleteAccountRow) {
            return Theme.multAlpha(getThemedColor(Theme.key_text_RedRegular), .1f);
        }
        return super.getSelectorColor(position);
    }
}
