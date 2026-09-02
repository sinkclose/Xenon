package zxc.iconic.xenon.settings;

import android.app.Activity;
import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.TextView;

import android.content.Intent;
import android.net.Uri;

import androidx.appcompat.content.res.AppCompatResources;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;

import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.Utilities;
import org.telegram.messenger.browser.Browser;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.SettingsSearchCell;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.Bulletin;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.FragmentFloatingButton;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UpdateAppAlertDialog;
import org.telegram.ui.ProfileActivity.SearchAdapter.SearchResult;

import java.io.File;
import java.util.ArrayList;
import java.util.Locale;

import me.vkryl.android.animator.BoolAnimator;
import me.vkryl.android.animator.FactorAnimator;
import zxc.iconic.xenon.NekoConfig;
import zxc.iconic.xenon.accessibility.AccessibilitySettingsActivity;
import zxc.iconic.xenon.helpers.ApkInstaller;
import zxc.iconic.xenon.helpers.CloudSettingsHelper;
import zxc.iconic.xenon.helpers.PasscodeHelper;


public class NekoSettingsActivity extends BaseNekoSettingsActivity implements FactorAnimator.Target {

    private static final int ANIMATOR_ID_SEARCH_PAGE_VISIBLE = 0;
    private static final String CREATOR_URL = "https://t.me/thinkaboutrue";
    private static final String CHANNEL_URL = "https://t.me/xenongram";
    private static final String SOURCE_CODE_URL = "https://github.com/sinkclose/Xenon";

    private final BoolAnimator animatorSearchPageVisible = new BoolAnimator(ANIMATOR_ID_SEARCH_PAGE_VISIBLE,
            this, CubicBezierInterpolator.EASE_OUT_QUINT, 350);

    private final int generalRow = rowId++;
    private final int appearanceRow = rowId++;
    private final int chatRow = rowId++;
    private final int passcodeRow = rowId++;
    private final int experimentRow = rowId++;
    private final int pluginsRow = rowId++;
    private final int accessibilityRow = rowId++;

    private final int checkUpdateRow = rowId++;
    private final int forceUpdateRow = rowId++;

    private final int aboutHeaderRow = rowId++;
    private final int creatorRow = rowId++;
    private final int channelRow = rowId++;
    private final int sourceCodeRow = rowId++;

    private final int commitRow = rowId++;

    private ActionBarMenuItem syncItem;
    private final ArrayList<SearchResult> searchArray = createSearchArray();
    private final ArrayList<CharSequence> resultNames = new ArrayList<>();
    private final ArrayList<SearchResult> searchResults = new ArrayList<>();
    private boolean searchWas;
    private Runnable searchRunnable;
    private String lastSearchString;

    private FrameLayout topView;

    @Override
    public View createView(Context context) {
        if (parentLayout != null && parentLayout.isRightLayout()) {
            actionBar.setBackButtonImage(R.drawable.ic_ab_close);
        }
        topView = new FrameLayout(context);

        var logoContainer = new FrameLayout(context);
        var logoView = new BackupImageView(context);

        logoView.setImageDrawable(AppCompatResources.getDrawable(context, R.mipmap.ic_launcher));
        logoContainer.addView(logoView, LayoutHelper.createFrame(90, 90, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 15, 0, 0));
        topView.addView(logoContainer, LayoutHelper.createFrame(120, 120, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 23 - 12, 0, 0));

        var titleView = new TextView(context);
        titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 22);
        titleView.setTypeface(AndroidUtilities.bold());
        titleView.setGravity(Gravity.CENTER);
        titleView.setSingleLine();
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setText(LocaleController.getString(R.string.AppNameNeko));
        titleView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteBlackText));
        topView.addView(titleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 138.333f - 12, 0, 0));

        var subtitleView = new TextView(context);
        subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        subtitleView.setGravity(Gravity.CENTER);
        subtitleView.setSingleLine();
        subtitleView.setEllipsize(TextUtils.TruncateAt.END);
        subtitleView.setText(String.format(Locale.US, "%s (%d)", BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE));
        subtitleView.setTextColor(getThemedColor(Theme.key_windowBackgroundWhiteGrayText));
        topView.addView(subtitleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_HORIZONTAL | Gravity.TOP, 0, 168 - 12, 0, 0));

        var fragmentView = super.createView(context);

        var menu = actionBar.createMenu();
        createSearchItem(menu, new ActionBarMenuItem.ActionBarMenuItemSearchListener() {

            @Override
            public void onSearchCollapse() {
                animatorSearchPageVisible.setValue(false, true);
                updateActionBarVisible();
                listView.adapter.update(true);
            }

            @Override
            public void onSearchExpand() {
                animatorSearchPageVisible.setValue(true, true);
                updateActionBarVisible();
                search("");
                listView.adapter.update(true);
            }

            @Override
            public void onTextChanged(EditText editText) {
                search(editText.getText().toString());
            }
        });
        syncItem = menu.addItem(1, R.drawable.cloud_sync);
        syncItem.setContentDescription(LocaleController.getString(R.string.CloudConfig));
        syncItem.setOnClickListener(v -> CloudSettingsHelper.getInstance().showDialog(this));

        return fragmentView;
    }

    @Override
    protected boolean needActionBarPadding() {
        return false;
    }

    @Override
    protected boolean progressiveBlurEnabled() {
        return false;
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (isSearchFieldVisible()) {
            items.add(UItem.asSpace(ActionBar.getCurrentActionBarHeight()));
            fillSearchItems(items);
            return;
        }

        items.add(UItem.asCustomShadow(topView, 200 - 12));

        items.add(UItem.asButton(generalRow, R.drawable.msg_media, LocaleController.getString(R.string.General)).slug("general"));
        items.add(UItem.asButton(appearanceRow, R.drawable.msg_theme, LocaleController.getString(R.string.ChangeChannelNameColor2)).slug("appearance"));
        items.add(UItem.asButton(chatRow, R.drawable.msg_discussion, LocaleController.getString(R.string.Chat)).slug("chat"));
        if (!PasscodeHelper.isSettingsHidden()) {
            items.add(UItem.asButton(passcodeRow, R.drawable.msg_secret, LocaleController.getString(R.string.PasscodeNeko)).slug("passcode"));
        }
        items.add(UItem.asButton(experimentRow, R.drawable.msg_fave, LocaleController.getString(R.string.NotificationsOther)).slug("experiment"));
        items.add(UItem.asButton(pluginsRow, R.drawable.msg_edit, LocaleController.getString(R.string.Plugins)).slug("plugins"));
        AccessibilityManager am = (AccessibilityManager) ApplicationLoader.applicationContext.getSystemService(Context.ACCESSIBILITY_SERVICE);
        if (am != null && am.isTouchExplorationEnabled()) {
            items.add(UItem.asButton(accessibilityRow, LocaleController.getString(R.string.AccessibilitySettings)).slug("accessibility"));
        }
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.About)));
        items.add(UItem.asButton(creatorRow, R.drawable.msg_contacts, LocaleController.getString(R.string.XenonCreator), LocaleController.getString(R.string.XenonCreatorUsername)).slug("creator"));
        items.add(UItem.asButton(channelRow, R.drawable.msg_channel, LocaleController.getString(R.string.XenonChannel), LocaleController.getString(R.string.XenonTitle)).slug("channel"));
        items.add(UItem.asButton(sourceCodeRow, R.drawable.msg_link, LocaleController.getString(R.string.ViewSourceCode), LocaleController.getString(R.string.XenonGitHub)).slug("sourceCode"));
        items.add(UItem.asButton(checkUpdateRow, R.drawable.msg_repeat, LocaleController.getString(R.string.CheckUpdate)).slug("checkUpdate"));
        items.add(UItem.asButton(forceUpdateRow, R.drawable.msg_download, LocaleController.getString(R.string.ForceUpdate)).slug("forceUpdate"));
        items.add(UItem.asShadow(null));

        items.add(TextDetailSettingsCellFactory.of(commitRow, "Build commit", BuildConfig.GIT_COMMIT_SHORT));
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        if (item.instanceOf(SettingsSearchCell.Factory.class)) {
            if (item.object instanceof SearchResult r) {
                r.open(null);
            }
            return;
        }
        var id = item.id;
        if (id == chatRow) {
            presentFragment(new NekoChatSettingsActivity());
        } else if (id == generalRow) {
            presentFragment(new NekoGeneralSettingsActivity());
        } else if (id == appearanceRow) {
            presentFragment(new NekoAppearanceSettingsActivity());
        } else if (id == passcodeRow) {
            presentFragment(new NekoPasscodeSettingsActivity());
        } else if (id == experimentRow) {
            presentFragment(new NekoExperimentalSettingsActivity());
        } else if (id == pluginsRow) {
            presentFragment(new NekoPluginsActivity());
        } else if (id == accessibilityRow) {
            presentFragment(new AccessibilitySettingsActivity());
        } else if (id == creatorRow) {
            Browser.openUrl(getParentActivity(), CREATOR_URL);
        } else if (id == channelRow) {
            Browser.openUrl(getParentActivity(), CHANNEL_URL);
        } else if (id == sourceCodeRow) {
            Browser.openUrl(getParentActivity(), SOURCE_CODE_URL);
        } else if (id == checkUpdateRow) {
            if (getParentActivity() instanceof org.telegram.ui.LaunchActivity la) {
                var spinner = new org.telegram.ui.ActionBar.AlertDialog(getParentActivity(), org.telegram.ui.ActionBar.AlertDialog.ALERT_TYPE_SPINNER);
                spinner.setCanCancel(true);
                spinner.show();
                la.checkAppUpdate(true, new Browser.Progress(null, () -> {
                    try { spinner.dismiss(); } catch (Throwable ignored) {}
                }));
            }
        } else if (id == forceUpdateRow) {
            var activity = getParentActivity();
            if (activity == null) return;
            var impl = ApplicationLoader.applicationLoaderInstance;
            if (impl != null && impl.isDownloadingUpdate()) {
                showDownloadProgress(activity, impl);
                return;
            }
            var spinner = new org.telegram.ui.ActionBar.AlertDialog(activity, org.telegram.ui.ActionBar.AlertDialog.ALERT_TYPE_SPINNER);
            spinner.setCanCancel(true);
            spinner.show();
            zxc.iconic.xenon.helpers.remote.GitHubUpdateHelper.checkForUpdates(new zxc.iconic.xenon.helpers.remote.GitHubUpdateHelper.UpdateCallback() {
                @Override
                public void onUpdateAvailable(zxc.iconic.xenon.helpers.remote.GitHubUpdateHelper.GitHubRelease release) {
                    try { spinner.dismiss(); } catch (Throwable ignored) {}
                    String apkUrl = zxc.iconic.xenon.helpers.remote.GitHubUpdateHelper.findApkDownloadUrl(release);
                    if (apkUrl == null) {
                        BulletinFactory.of(NekoSettingsActivity.this).createErrorBulletin("No arm64 build available").show();
                        return;
                    }
                    String title = release.name != null ? release.name : release.tagName;
                    var impl = ApplicationLoader.applicationLoaderInstance;
                    if (NekoConfig.autoDownloadUpdate) {
                        final Bulletin[] progBulletin = new Bulletin[1];
                        AndroidUtilities.runOnUIThread(() -> {
                            try {
                                Bulletin b = BulletinFactory.global()
                                        .createSimpleBulletin(R.raw.ic_download, LocaleController.getString(R.string.DownloadingUpdate) + NekoConfig.getChannelLabel(), LocaleController.getString(R.string.Cancel), Integer.MAX_VALUE, () -> impl.cancelDownloadingUpdate());
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
                                                        () -> zxc.iconic.xenon.helpers.ApkInstaller.installUpdate(activity, apkFile));
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
                                                text = LocaleController.getString(R.string.DownloadingUpdate) + NekoConfig.getChannelLabel() + " " + d + " / " + t;
                                            } else {
                                                text = LocaleController.getString(R.string.DownloadingUpdate) + NekoConfig.getChannelLabel() + " " + (int)(prog * 100) + "%";
                                            }
                                            ((Bulletin.LottieLayout) progBulletin[0].getLayout()).textView.setText(text);
                                        } catch (Throwable ignored) {}
                                    }
                                    AndroidUtilities.runOnUIThread(this, 500);
                                }
                            }
                        }, 500);
                        return;
                    }
                    Activity act = activity;
                    String finalText = release.body != null ? release.body : "";
                    AndroidUtilities.runOnUIThread(() -> {
                        TLRPC.TL_help_appUpdate appUpdate = new TLRPC.TL_help_appUpdate();
                        appUpdate.version = title;
                        appUpdate.text = finalText;
                        appUpdate.can_not_skip = false;
                        appUpdate.url = apkUrl;
                        appUpdate.flags |= 4;
                        UpdateAppAlertDialog dialog = new UpdateAppAlertDialog(act, appUpdate, UserConfig.selectedAccount);
                        dialog.setOnDownloadClickListener(() -> {
                                final Bulletin[] progBulletin = new Bulletin[1];
                                AndroidUtilities.runOnUIThread(() -> {
                                    try {
                                        Bulletin b = BulletinFactory.global()
                                                .createSimpleBulletin(R.raw.ic_download, LocaleController.getString(R.string.DownloadingUpdate) + NekoConfig.getChannelLabel(), LocaleController.getString(R.string.Cancel), Integer.MAX_VALUE, () -> impl.cancelDownloadingUpdate());
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
                                                                () -> zxc.iconic.xenon.helpers.ApkInstaller.installUpdate(activity, apkFile));
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
                                                        text = LocaleController.getString(R.string.DownloadingUpdate) + NekoConfig.getChannelLabel() + " " + d + " / " + t;
                                                    } else {
                                                        text = LocaleController.getString(R.string.DownloadingUpdate) + NekoConfig.getChannelLabel() + " " + (int)(prog * 100) + "%";
                                                    }
                                                    ((Bulletin.LottieLayout) progBulletin[0].getLayout()).textView.setText(text);
                                                } catch (Throwable ignored) {}
                                            }
                                            AndroidUtilities.runOnUIThread(this, 500);
                                        }
                                    }
                                }, 500);
                            });
                            dialog.show();
                        });
                    }

                @Override
                public void onNoUpdate() {
                    try { spinner.dismiss(); } catch (Throwable ignored) {}
                }

                @Override
                public void onError(String error) {
                    try { spinner.dismiss(); } catch (Throwable ignored) {}
                    BulletinFactory.of(NekoSettingsActivity.this).createErrorBulletin(error).show();
                }
            }, true);
        }
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.NekoSettings);
    }

    @Override
    protected String getKey() {
        return "";
    }

    @Override
    public void onFactorChanged(int id, float factor, float fraction, FactorAnimator callee) {
        if (id == ANIMATOR_ID_SEARCH_PAGE_VISIBLE) {
            FragmentFloatingButton.setAnimatedVisibility(syncItem, 1f - factor);
        }
    }

    @Override
    public boolean isSwipeBackEnabled(MotionEvent event) {
        return !animatorSearchPageVisible.getValue();
    }

    private static BaseNekoSettingsActivity createFragment(int icon) {
        if (icon == R.drawable.msg_media) {
            return new NekoGeneralSettingsActivity();
        } else if (icon == R.drawable.msg_theme) {
            return new NekoAppearanceSettingsActivity();
        } else if (icon == R.drawable.msg_discussion) {
            return new NekoChatSettingsActivity();
        } else if (icon == R.drawable.msg_fave) {
            return new NekoExperimentalSettingsActivity();
        }
        return new NekoSettingsActivity();
    }

    private ArrayList<SearchResult> createSearchArray() {
        var searchResultList = new ArrayList<SearchResult>();
        var icons = new int[]{
                R.drawable.msg_media,
                R.drawable.msg_theme,
                R.drawable.msg_discussion,
                R.drawable.msg_fave,
        };
        for (var i = 0; i < icons.length; i++) {
            var icon = icons[i];
            var fragment = createFragment(icon);
            var items = new ArrayList<UItem>();
            fragment.fillItems(items, null);
            var fragmentTitle = fragment.getActionBarTitle();
            String headerText = null;
            for (var item : items) {
                if (item.viewType == UniversalAdapter.VIEW_TYPE_HEADER) {
                    headerText = item.text.toString();
                    continue;
                } else if (item.viewType == UniversalAdapter.VIEW_TYPE_SHADOW) {
                    headerText = null;
                    continue;
                }
                if (TextUtils.isEmpty(item.slug)) continue;
                searchResultList.add(new SearchResult(i * 1000 + item.id, item.text.toString(), null, fragmentTitle, fragmentTitle.equals(headerText) ? null : headerText, icon, () -> {
                    var fragment1 = createFragment(icon);
                    presentFragment(fragment1);
                    AndroidUtilities.runOnUIThread(() -> fragment1.scrollToRow(item.slug, () -> {
                    }));
                }));
            }
            searchResultList.add(new SearchResult(10000 + i, fragmentTitle, icon, () -> presentFragment(createFragment(icon))));
        }
        searchResultList.add(new SearchResult(8000, LocaleController.getString(R.string.EmojiUseDefault), null, LocaleController.getString(R.string.Chat), LocaleController.getString(R.string.EmojiSets), R.drawable.msg_theme, () -> {
            var fragment = new NekoEmojiSettingsActivity();
            presentFragment(fragment);
            AndroidUtilities.runOnUIThread(() -> fragment.scrollToRow("useSystemEmoji", () -> {
            }));
        }));

        searchResultList.add(new SearchResult(20002, LocaleController.getString(R.string.XenonCreator), LocaleController.getString(R.string.XenonCreatorUsername), R.drawable.msg_contacts, () -> Browser.openUrl(getParentActivity(), CREATOR_URL)));
        searchResultList.add(new SearchResult(20003, LocaleController.getString(R.string.XenonChannel), LocaleController.getString(R.string.XenonTitle), R.drawable.msg_channel, () -> Browser.openUrl(getParentActivity(), CHANNEL_URL)));
        searchResultList.add(new SearchResult(20004, LocaleController.getString(R.string.ViewSourceCode), LocaleController.getString(R.string.XenonGitHub), R.drawable.msg_link, () -> Browser.openUrl(getParentActivity(), SOURCE_CODE_URL)));

        return searchResultList;
    }

    private void fillSearchItems(ArrayList<UItem> items) {
        if (searchWas) {
            for (int i = 0; i < searchResults.size(); i++) {
                items.add(SettingsSearchCell.Factory.of(resultNames.get(i), searchResults.get(i)));
            }
            if (!searchResults.isEmpty()) items.add(UItem.asShadow(null));
        }
    }

    private void search(String text) {
        lastSearchString = text;
        if (searchRunnable != null) {
            Utilities.searchQueue.cancelRunnable(searchRunnable);
            searchRunnable = null;
        }
        if (TextUtils.isEmpty(text)) {
            searchWas = false;
            searchResults.clear();
            resultNames.clear();
            listView.adapter.update(true);
            return;
        }
        Utilities.searchQueue.postRunnable(searchRunnable = () -> {
            var results = new ArrayList<SearchResult>();
            var names = new ArrayList<CharSequence>();
            var lowerQuery = text.toLowerCase();
            for (var result : searchArray) {
                var title = result.searchTitle.toLowerCase();
                var index = title.indexOf(lowerQuery);
                var matchLen = lowerQuery.length();
                if (index < 0) continue;
                var ssb = new SpannableStringBuilder(result.searchTitle);
                ssb.setSpan(new ForegroundColorSpan(getThemedColor(Theme.key_windowBackgroundWhiteBlueText4)), index, Math.min(index + matchLen, ssb.length()), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                results.add(result);
                names.add(ssb);
            }

            AndroidUtilities.runOnUIThread(() -> {
                if (!text.equals(lastSearchString)) {
                    return;
                }
                searchWas = true;
                searchResults.clear();
                resultNames.clear();
                searchResults.addAll(results);
                resultNames.addAll(names);
                listView.adapter.update(true);
            });
        }, 300);
    }

    private static final int REQUEST_CODE_LOAD_SETTINGS = 2001;

    @Override
    public void onActivityResultFragment(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQUEST_CODE_LOAD_SETTINGS && resultCode == android.app.Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                try {
                    StringBuilder sb = new StringBuilder();
                    try (java.io.BufferedReader br = new java.io.BufferedReader(new java.io.InputStreamReader(getParentActivity().getContentResolver().openInputStream(uri), "UTF-8"))) {
                        String line;
                        while ((line = br.readLine()) != null) {
                            sb.append(line);
                        }
                    }
                    NekoConfig.importConfigs(sb.toString());
                    BulletinFactory.global().createSimpleBulletin(R.raw.chats_infotip, "Settings restored!").show();
                } catch (Exception e) {
                    FileLog.e(e);
                    BulletinFactory.global().createSimpleBulletin(R.raw.chats_infotip, "Failed to restore settings").show();
                }
            }
        }
    }

    private void showDownloadProgress(Activity activity, ApplicationLoader impl) {
        final Bulletin[] progBulletin = new Bulletin[1];
        AndroidUtilities.runOnUIThread(() -> {
            try {
                Bulletin b = BulletinFactory.global()
                        .createSimpleBulletin(R.raw.ic_download, LocaleController.getString(R.string.DownloadingUpdate) + NekoConfig.getChannelLabel(), LocaleController.getString(R.string.Cancel), Integer.MAX_VALUE, () -> impl.cancelDownloadingUpdate());
                if (b.getLayout() instanceof Bulletin.LottieLayout) {
                    ((Bulletin.LottieLayout) b.getLayout()).setIconPaddingBottom(2);
                }
                b.show();
                progBulletin[0] = b;
            } catch (Throwable ignored) {}
        }, 100);
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
                                text = LocaleController.getString(R.string.DownloadingUpdate) + NekoConfig.getChannelLabel() + " " + d + " / " + t;
                            } else {
                                text = LocaleController.getString(R.string.DownloadingUpdate) + NekoConfig.getChannelLabel() + " " + (int)(prog * 100) + "%";
                            }
                            ((Bulletin.LottieLayout) progBulletin[0].getLayout()).textView.setText(text);
                        } catch (Throwable ignored) {}
                    }
                    AndroidUtilities.runOnUIThread(this, 500);
                } else if (progBulletin[0] != null) {
                    try { progBulletin[0].hide(); } catch (Throwable ignored) {}
                    File apkFile = impl.getDownloadedUpdateFile();
                    if (apkFile != null && apkFile.exists()) {
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
                    }
                }
            }
        }, 500);
    }

}
