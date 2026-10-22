package zxc.iconic.xenon.settings;

import android.os.CountDownTimer;
import android.text.TextUtils;
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
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.tgnet.tl.TL_account;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.Locale;

import zxc.iconic.xenon.Extra;
import zxc.iconic.xenon.NekoConfig;
import zxc.iconic.xenon.osu.OsuLocalCache;
import zxc.iconic.xenon.osu.OsuSkinStore;
import org.telegram.ui.Components.BulletinFactory;
import zxc.iconic.xenon.helpers.PopupHelper;
import zxc.iconic.xenon.helpers.remote.UpdateHelper;
import zxc.iconic.xenon.proxy.XrayProxyProfileStore;

public class NekoExperimentalSettingsActivity extends BaseNekoSettingsActivity {
    private final int osuLogoKiaiSoundRow = rowId++, osuHitsoundVolumeRow = rowId++, osuMapMusicVolumeRow = rowId++;
    private final int osuKeepHitsoundsPlayingRow = rowId++;
    private final int osuClearLocalFilesRow = rowId++;
    private final int osuVideoBackgroundRow=rowId++,osuKiaiIntensityRow=rowId++;
    private final int osuBackgroundDimRow=rowId++,osuBackgroundBlurRow=rowId++,osuBackgroundParallaxRow=rowId++,osuSkinRow=rowId++;
    private static final int OSU_SKIN_PICKER=49422;
    private boolean importingOsuSkin;

    private void chooseOsuSkin(){
        java.util.List<java.io.File> skins=OsuSkinStore.list();
        CharSequence[] choices=new CharSequence[skins.size()+3];
        choices[0]="osu! argon pro (2022)";choices[1]=LocaleController.getString(R.string.OsuSkinImport);
        choices[2]=LocaleController.getString(R.string.OsuSkinDefault);
        for(int i=0;i<skins.size();i++)choices[i+3]=OsuSkinStore.name(skins.get(i));
        showDialog(new AlertDialog.Builder(getParentActivity(),resourcesProvider).setTitle(LocaleController.getString(R.string.OsuReplaySkin))
                .setItems(choices,(dialog,index)->{
                    if(index==0)NekoConfig.setOsuReplaySkin(OsuSkinStore.ARGON_PRO);
                    else if(index==1){try{startActivityForResult(new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT).setType("*/*").addCategory(android.content.Intent.CATEGORY_OPENABLE),OSU_SKIN_PICKER);}catch(Exception error){FileLog.e(error);BulletinFactory.of(this).createErrorBulletin(LocaleController.getString(R.string.OsuSkinImportFailed)).show();}}
                    else NekoConfig.setOsuReplaySkin(index==2?"":skins.get(index-3).getName());
                    if(listView!=null)listView.adapter.update(true);
                }).create());
    }
    @Override public void onActivityResultFragment(int request,int result,android.content.Intent data){
        super.onActivityResultFragment(request,result,data);
        if(request!=OSU_SKIN_PICKER||result!=android.app.Activity.RESULT_OK||data==null||data.getData()==null||importingOsuSkin||getParentActivity()==null)return;
        android.content.Context context=getParentActivity().getApplicationContext();android.net.Uri uri=data.getData();
        importingOsuSkin=true;if(listView!=null)listView.adapter.update(true);
        Utilities.globalQueue.postRunnable(()->{
            String key=null;
            try(java.io.InputStream input=context.getContentResolver().openInputStream(uri)){if(input==null)throw new java.io.IOException("Skin missing");key=OsuSkinStore.importSkin(input);}catch(Exception error){FileLog.e(error);}
            String imported=key;
            AndroidUtilities.runOnUIThread(()->{
                importingOsuSkin=false;if(imported!=null)NekoConfig.setOsuReplaySkin(imported);
                if(listView!=null)listView.adapter.update(true);
                if(getParentActivity()!=null){BulletinFactory factory=BulletinFactory.of(this);if(imported==null)factory.createErrorBulletin(LocaleController.getString(R.string.OsuSkinImportFailed)).show();else factory.createSuccessBulletin(LocaleController.getString(R.string.OsuSkinImported)).show();}
            });
        });
    }
    private long osuCacheBytes = -1;
    private boolean osuCacheBusy;

    @Override public void onResume() {
        super.onResume();
        refreshOsuCache(false);
    }
    private void refreshOsuCache(boolean clear) {
        if (osuCacheBusy) return;
        osuCacheBusy = true;
        if (listView != null) listView.adapter.update(true);
        Utilities.globalQueue.postRunnable(() -> {
            boolean failed = false;
            try { if (clear) OsuLocalCache.clear(); } catch (java.io.IOException error) { FileLog.e(error); failed = true; }
            long size = -1;
            try { size = OsuLocalCache.size(); } catch (java.io.IOException error) { FileLog.e(error); }
            final long bytes = size;
            final boolean showError = failed;
            AndroidUtilities.runOnUIThread(() -> {
                osuCacheBytes = bytes; osuCacheBusy = false;
                if (listView != null) listView.adapter.update(true);
                if (showError && getParentActivity() != null) android.widget.Toast.makeText(getParentActivity(), LocaleController.getString(R.string.ErrorOccurred), android.widget.Toast.LENGTH_SHORT).show();
            });
        });
    }

    private final int disableTypingRow = rowId++;
    private final int ghostModeRow = rowId++;
    private final int telegaDetectorRow = rowId++;
    private final int optimizedPushServiceRow = rowId++;
    private final int disableOpeningFailureBottomsheetRow = rowId++;

    private final int useCamera2ApiRow = rowId++;

    private final int downloadSpeedBoostRow = rowId++;
    private final int localCustomEmojiRow = rowId++;
    private final int keepFormattingRow = rowId++;
    private final int forceFontWeightFallbackRow = rowId++;
    private final int contentRestrictionRow = rowId++;
    private final int showRPCErrorRow = rowId++;
    private final int removeChatDelayRow = rowId++;
    private final int bypassBlockingRow = rowId++;
    private final int xrayProxySettingsRow = rowId++;

    private final int deleteAccountRow = rowId++;
    private final int buildInfoRow = rowId++;
    private final int logcatRow = rowId++;

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.OsuLogoAudio)));
        items.add(UItem.asCheck(osuKeepHitsoundsPlayingRow, LocaleController.getString(R.string.OsuLogoKeepHitsoundsPlaying),
                LocaleController.getString(R.string.OsuLogoKeepHitsoundsPlayingHint))
                .setChecked(NekoConfig.osuKeepHitsoundsPlaying).slug("osuKeepHitsoundsPlaying"));
        SeekbarConfig hits = new SeekbarConfig(LocaleController.getString(R.string.OsuLogoHitsoundVolume), "0%", "100%", 0, 100, 1,
                value -> NekoConfig.setOsuHitsoundVolume(Math.round(value)));
        hits.valueFormatter = value -> value + "%";
        items.add(SeekbarCellFactory.of(osuHitsoundVolumeRow, hits, NekoConfig.osuHitsoundVolume).slug("osuHitsoundVolume"));
        SeekbarConfig music = new SeekbarConfig(LocaleController.getString(R.string.OsuLogoMapMusicVolume), "0%", "100%", 0, 100, 1,
                value -> NekoConfig.setOsuMapMusicVolume(Math.round(value)));
        music.valueFormatter = value -> value + "%";
        items.add(SeekbarCellFactory.of(osuMapMusicVolumeRow, music, NekoConfig.osuMapMusicVolume).slug("osuMapMusicVolume"));
        items.add(UItem.asCheck(osuLogoKiaiSoundRow, LocaleController.getString(R.string.OsuLogoKiaiSound))
                .setChecked(NekoConfig.osuLogoKiaiSound).slug("osuLogoKiaiSound"));
        items.add(UItem.asShadow(LocaleController.getString(R.string.OsuLogoKiaiSoundHint)));
        items.add(UItem.asCheck(osuVideoBackgroundRow,LocaleController.getString(R.string.OsuReplayVideoBackground)).setChecked(NekoConfig.osuReplayVideoBackground).slug("osuReplayVideoBackground"));
        SeekbarConfig dim=new SeekbarConfig(LocaleController.getString(R.string.OsuReplayBackgroundDim),"0%","100%",0,100,1,value->NekoConfig.setOsuReplayBackgroundDim(Math.round(value)));
        dim.valueFormatter=value->value+"%";items.add(SeekbarCellFactory.of(osuBackgroundDimRow,dim,NekoConfig.osuReplayBackgroundDim).slug("osuReplayBackgroundDim"));
        SeekbarConfig blur=new SeekbarConfig(LocaleController.getString(R.string.OsuReplayBackgroundBlur),"0%","100%",0,100,1,value->NekoConfig.setOsuReplayBackgroundBlur(Math.round(value)));
        blur.valueFormatter=value->value+"%";items.add(SeekbarCellFactory.of(osuBackgroundBlurRow,blur,NekoConfig.osuReplayBackgroundBlur).slug("osuReplayBackgroundBlur"));
        SeekbarConfig parallax=new SeekbarConfig(LocaleController.getString(R.string.OsuReplayBackgroundParallax),"-200%","200%",-200,200,1,value->NekoConfig.setOsuReplayBackgroundParallax(Math.round(value)));
        parallax.valueFormatter=value->value+"%";items.add(SeekbarCellFactory.of(osuBackgroundParallaxRow,parallax,NekoConfig.osuReplayBackgroundParallax).slug("osuReplayBackgroundParallax"));
        SeekbarConfig kiai=new SeekbarConfig(LocaleController.getString(R.string.OsuReplayKiaiIntensity),"0%","200%",0,200,1,value->NekoConfig.setOsuReplayKiaiIntensity(Math.round(value)));
        kiai.valueFormatter=value->value+"%";items.add(SeekbarCellFactory.of(osuKiaiIntensityRow,kiai,NekoConfig.osuReplayKiaiIntensity).slug("osuReplayKiaiIntensity"));
        java.io.File skin=OsuSkinStore.directory(NekoConfig.osuReplaySkin);
        String skinName=importingOsuSkin?LocaleController.getString(R.string.Loading):OsuSkinStore.isArgonPro(NekoConfig.osuReplaySkin)?"osu! argon pro (2022)":skin!=null&&skin.isDirectory()?OsuSkinStore.name(skin):LocaleController.getString(R.string.OsuSkinDefault);
        items.add(TextSettingsCellFactory.of(osuSkinRow,LocaleController.getString(R.string.OsuReplaySkin),skinName).slug("osuReplaySkin").setEnabled(!importingOsuSkin));
        items.add(TextSettingsCellFactory.of(osuClearLocalFilesRow, LocaleController.getString(R.string.OsuClearLocalFiles),
                osuCacheBusy || osuCacheBytes < 0 ? LocaleController.getString(R.string.Loading) : AndroidUtilities.formatFileSize(osuCacheBytes))
                .slug("osuClearLocalFiles").red().setEnabled(!osuCacheBusy));
        items.add(UItem.asShadow(LocaleController.getString(R.string.OsuClearLocalFilesHint)));
        items.add(UItem.asHeader(LocaleController.getString(R.string.Experiment)));
        items.add(UItem.asCheck(disableTypingRow, LocaleController.getString(R.string.DisableTypingIndicator), LocaleController.getString(R.string.DisableTypingIndicatorDesc)).slug("disableTyping").setChecked(NekoConfig.disableTypingIndicator));
        items.add(UItem.asCheck(ghostModeRow, LocaleController.getString(R.string.GhostMode), LocaleController.getString(R.string.GhostModeDesc)).slug("ghostMode").setChecked(NekoConfig.ghostModeEnabled));
        items.add(UItem.asCheck(telegaDetectorRow, LocaleController.getString(R.string.TelegaDetectorEnabled), LocaleController.getString(R.string.TelegaDetectorHint)).slug("telegaDetector").setChecked(NekoConfig.telegaDetectorEnabled));
        items.add(UItem.asCheck(optimizedPushServiceRow, LocaleController.getString(R.string.OptimizedPushService), LocaleController.getString(R.string.OptimizedPushServiceDesc)).slug("optimizedPushService").setChecked(NekoConfig.optimizedPushService));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.Tweaks)));
        items.add(UItem.asCheck(disableOpeningFailureBottomsheetRow,
                LocaleController.getString(R.string.DisableOpeningFailureBottomsheet),
                LocaleController.getString(R.string.DisableOpeningFailureBottomsheetDesc))
                .slug("disableOpeningFailureBottomsheet").setChecked(NekoConfig.disableOpeningFailureBottomsheet));
        items.add(UItem.asCheck(useCamera2ApiRow, LocaleController.getString(R.string.UseCamera2Api), LocaleController.getString(R.string.UseCamera2ApiDesc)).slug("useCamera2Api").setChecked(NekoConfig.useCamera2Api));
        if (!MessagesController.getInstance(currentAccount).getfileExperimentalParams) {
            items.add(TextSettingsCellFactory.of(downloadSpeedBoostRow, LocaleController.getString(R.string.DownloadSpeedBoost), switch (NekoConfig.downloadSpeedBoost) {
                case NekoConfig.BOOST_NONE ->
                        LocaleController.getString(R.string.DownloadSpeedBoostNone);
                case NekoConfig.BOOST_EXTREME ->
                        LocaleController.getString(R.string.DownloadSpeedBoostExtreme);
                default -> LocaleController.getString(R.string.DownloadSpeedBoostAverage);
            }).slug("downloadSpeedBoost"));
        }
        items.add(UItem.asCheck(localCustomEmojiRow, LocaleController.getString(R.string.LocalCustomEmoji)).slug("localCustomEmoji").setChecked(NekoConfig.localCustomEmoji));
        items.add(UItem.asCheck(keepFormattingRow, LocaleController.getString(R.string.TranslationKeepFormatting)).slug("keepFormatting").setChecked(NekoConfig.keepFormatting));
        items.add(UItem.asCheck(forceFontWeightFallbackRow, LocaleController.getString(R.string.ForceFontWeightFallback)).slug("forceFontWeightFallback").setChecked(NekoConfig.forceFontWeightFallback));
        if (Extra.isDirectApp()) {
            items.add(UItem.asCheck(contentRestrictionRow, LocaleController.getString(R.string.IgnoreContentRestriction)).slug("contentRestriction").setChecked(NekoConfig.ignoreContentRestriction));
        }
        items.add(UItem.asCheck(showRPCErrorRow, LocaleController.getString(R.string.ShowRPCError), LocaleController.formatString(R.string.ShowRPCErrorException, "FILE_REFERENCE_EXPIRED")).slug("showRPCError").setChecked(NekoConfig.showRPCError));
        items.add(UItem.asCheck(removeChatDelayRow, LocaleController.getString(R.string.RemoveChatDelay)).slug("removeChatDelay").setChecked(NekoConfig.removeChatDelay));
        items.add(UItem.asCheck(bypassBlockingRow, LocaleController.getString(R.string.BypassBlocking), LocaleController.getString(R.string.BypassBlockingDesc)).slug("bypassBlocking").setChecked(NekoConfig.bypassBlocking));
        XrayProxyProfileStore.Profile activeProfile = XrayProxyProfileStore.getActiveProfile();
        String xrayStatus = NekoConfig.xrayAppProxyEnabled ? LocaleController.getString(R.string.XrayProxyStatusRunning) : LocaleController.getString(R.string.XrayProxyStatusStopped);
        if (activeProfile != null && !TextUtils.isEmpty(activeProfile.name)) {
            xrayStatus = xrayStatus + " • " + activeProfile.name;
        }
        items.add(TextDetailSettingsCellFactory.of(xrayProxySettingsRow, LocaleController.getString(R.string.XrayProxyTitle), xrayStatus).slug("xrayProxy"));
        items.add(UItem.asShadow(null));

        items.add(TextSettingsCellFactory.of(deleteAccountRow, LocaleController.getString(R.string.DeleteAccount), "").slug("deleteAccount").red());
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.Channel)));
        items.add(TextSettingsCellFactory.of(buildInfoRow, NekoConfig.getChannelName(), "").slug("channelInfo"));
        items.add(TextSettingsCellFactory.of(logcatRow, "Logcat", "").slug("logcat"));
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        if (!item.enabled) {
            return;
        }
        int id = item.id;
        if(id==osuSkinRow){
            chooseOsuSkin();
        } else if (id == osuClearLocalFilesRow) {
            refreshOsuCache(true);
        } else if(id==osuVideoBackgroundRow){NekoConfig.setOsuReplayVideoBackground(!NekoConfig.osuReplayVideoBackground);if(view instanceof TextCheckCell)((TextCheckCell)view).setChecked(NekoConfig.osuReplayVideoBackground);
        } else if (id == osuKeepHitsoundsPlayingRow) {
            NekoConfig.setOsuKeepHitsoundsPlaying(!NekoConfig.osuKeepHitsoundsPlaying);
            listView.adapter.update(true);
        } else if (id == osuLogoKiaiSoundRow) {
            NekoConfig.setOsuLogoKiaiSound(!NekoConfig.osuLogoKiaiSound);
            listView.adapter.update(true);
        } else if (id == disableTypingRow) {
            NekoConfig.toggleDisableTypingIndicator();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.disableTypingIndicator);
            }
        } else if (id == ghostModeRow) {
            NekoConfig.toggleGhostMode();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.ghostModeEnabled);
            }
        } else if (id == telegaDetectorRow) {
            NekoConfig.setTelegaDetectorEnabled(!NekoConfig.telegaDetectorEnabled);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.telegaDetectorEnabled);
            }
        } else if (id == optimizedPushServiceRow) {
            NekoConfig.toggleOptimizedPushService();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.optimizedPushService);
            }
        } else if (id == disableOpeningFailureBottomsheetRow) {
            NekoConfig.toggleDisableOpeningFailureBottomsheet();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.disableOpeningFailureBottomsheet);
            }
        } else if (id == useCamera2ApiRow) {
            NekoConfig.toggleUseCamera2Api();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.useCamera2Api);
            }
        } else if (id == xrayProxySettingsRow) {
            presentFragment(new NekoXrayProxyHubActivity());
        } else if (id == logcatRow) {
            presentFragment(new NekoLogcatActivity());
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
        } else if (id == removeChatDelayRow) {
            NekoConfig.toggleRemoveChatDelay();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.removeChatDelay);
            }
        } else if (id == bypassBlockingRow) {
            NekoConfig.toggleBypassBlocking();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.bypassBlocking);
            }
            ConnectionsManager.applyBypassOptionsFromConfig();
        } else if (id == downloadSpeedBoostRow) {
            ArrayList<String> arrayList = new ArrayList<>();
            ArrayList<Integer> types = new ArrayList<>();
            arrayList.add(LocaleController.getString(R.string.DownloadSpeedBoostNone));
            types.add(NekoConfig.BOOST_NONE);
            arrayList.add(LocaleController.getString(R.string.DownloadSpeedBoostAverage));
            types.add(NekoConfig.BOOST_AVERAGE);
            arrayList.add(LocaleController.getString(R.string.DownloadSpeedBoostExtreme));
            types.add(NekoConfig.BOOST_EXTREME);
            showPopup(arrayList, types.indexOf(NekoConfig.downloadSpeedBoost), item, view, i -> {
                NekoConfig.setDownloadSpeedBoost(types.get(i));
                listView.adapter.notifyItemChanged(position, PARTIAL);
            }, resourcesProvider);
        } else if (id == contentRestrictionRow) {
            NekoConfig.toggleIgnoreContentRestriction();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.ignoreContentRestriction);
            }
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
        } else if (id == localCustomEmojiRow) {
            NekoConfig.toggleLocalCustomEmoji();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.localCustomEmoji);
            }
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
