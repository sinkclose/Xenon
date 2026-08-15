package zxc.iconic.xenon.settings;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.UserObject;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.ChatAvatarContainer;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

import zxc.iconic.xenon.NekoConfig;
import zxc.iconic.xenon.helpers.EmojiHelper;
import zxc.iconic.xenon.helpers.PopupHelper;

public class NekoAppearanceSettingsActivity extends BaseNekoSettingsActivity implements NotificationCenter.NotificationCenterDelegate {

    private final int emojiSetsRow = rowId++;
    private final int navigationSettingsRow = rowId++;
    private final int appBarShadowRow = rowId++;
    private final int formatTimeWithSecondsRow = rowId++;
    private final int disableNumberRoundingRow = rowId++;

    private final int hideStoriesRow = rowId++;
    private final int mediaPreviewRow = rowId++;

    private final int hideAllTabRow = rowId++;
    private final int tabsTitleTypeRow = rowId++;
    private final int tabsPositionRow = rowId++;

    private final int blurSettingsRow = rowId++;
    private final int hideRecordButtonRow = rowId++;
    private final int disableGooeyAvatarAnimationRow = rowId++;
    private final int gooeyAvatarOffsetRow = rowId++;
    private final int keepUnreadChatsOnTopRow = rowId++;
    private final int keepUnreadArchivedOnTopRow = rowId++;
    private final int material3SwitchesRow = rowId++;
    private final int m3SectionsStyleRow = rowId++;
    private final int materialSlidersRow = rowId++;
    private final int material3ChatHeadersRow = rowId++;
    private final int loadingIndicatorsRow = rowId++;
    private final int chatHeaderSettingsRow = rowId++;
    private final int nonIslandTabBarsRow = rowId++;
    private final int nonIslandGlobalSearchRow = rowId++;
    private final int material3BottomNavigationBarRow = rowId++;
    private final int md3PlayerSeekBarRow = rowId++;
    private final int md3FoldersRow = rowId++;
    private final int material3DialogsRow = rowId++;
    private final int avatarShapeRow = rowId++;
    private final int nonIslandChatElementsRow = rowId++;

    private final int textAnimationSettingsRow = rowId++;
    private final int roundedBulletinRow = rowId++;

    private final int liquidGlassRow = rowId++;

    @Override
    public boolean onFragmentCreate() {
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.emojiLoaded);
        return super.onFragmentCreate();
    }

    @Override
    public void onFragmentDestroy() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.emojiLoaded);
        super.onFragmentDestroy();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.emojiLoaded && listView != null) {
            notifyItemChanged(emojiSetsRow, PARTIAL);
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.BlurAndLiquidGlass)));
        items.add(TextSettingsCellFactory.of(blurSettingsRow, LocaleController.getString(R.string.BlurSettings), "›").slug("blurSettings"));
        items.add(TextSettingsCellFactory.of(liquidGlassRow, LocaleController.getString(R.string.BlurAndLiquidGlass), "›").slug("liquidGlass"));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.Navigation)));
        items.add(TextSettingsCellFactory.of(navigationSettingsRow, LocaleController.getString(R.string.NavigationSettings), "›").slug("navigationSettings"));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.ChangeChannelNameColor2)));
        items.add(EmojiSetCellFactory.of(emojiSetsRow, LocaleController.getString(R.string.EmojiSets)).slug("emojiSets"));
        items.add(UItem.asCheck(disableGooeyAvatarAnimationRow, LocaleController.getString(R.string.DisableGooeyAvatarAnimation)).setChecked(NekoConfig.disableGooeyAvatarAnimation).slug("disableGooeyAvatarAnimation"));
        SeekbarConfig offsetConfig = new SeekbarConfig(
                LocaleController.getString(R.string.GooeyAvatarOffset),
                LocaleController.getString(R.string.GooeyAvatarOffsetLeft),
                LocaleController.getString(R.string.GooeyAvatarOffsetRight),
                -100, 100, 1,
                progress -> NekoConfig.setGooeyAvatarOffset(Math.round(progress)));
        items.add(SeekbarCellFactory.of(gooeyAvatarOffsetRow, offsetConfig, NekoConfig.gooeyAvatarOffset).slug("gooeyAvatarOffset"));
        items.add(UItem.asCheck(keepUnreadChatsOnTopRow, LocaleController.getString(R.string.KeepUnreadChatsOnTop)).setChecked(NekoConfig.keepUnreadChatsOnTop).slug("keepUnreadChatsOnTop"));
        if (NekoConfig.keepUnreadChatsOnTop) {
            items.add(UItem.asCheck(keepUnreadArchivedOnTopRow, LocaleController.getString(R.string.KeepUnreadArchivedOnTop)).setChecked(NekoConfig.keepUnreadArchivedOnTop).slug("keepUnreadArchivedOnTop"));
        }
        items.add(UItem.asCheck(hideRecordButtonRow, LocaleController.getString(R.string.HideRecordButton)).setChecked(NekoConfig.hideRecordButton).slug("hideRecordButton"));
        items.add(UItem.asCheck(roundedBulletinRow, LocaleController.getString(R.string.RoundedBulletin)).setChecked(NekoConfig.roundedBulletin).slug("roundedBulletin"));
        items.add(UItem.asCheck(appBarShadowRow, LocaleController.getString(R.string.DisableAppBarShadow)).slug("appBarShadow").setChecked(NekoConfig.disableAppBarShadow));
        items.add(UItem.asCheck(formatTimeWithSecondsRow, LocaleController.getString(R.string.FormatWithSeconds)).slug("formatTimeWithSeconds").setChecked(NekoConfig.formatTimeWithSeconds));
        items.add(UItem.asCheck(disableNumberRoundingRow, LocaleController.getString(R.string.DisableNumberRounding), "4.8K -> 4777").slug("disableNumberRounding").setChecked(NekoConfig.disableNumberRounding));
        items.add(UItem.asHeader("Material Design 3"));
        items.add(UItem.asCheck(material3SwitchesRow, LocaleController.getString(R.string.Switches)).setChecked(NekoConfig.material3Switches).slug("material3Switches"));
        items.add(UItem.asCheck(m3SectionsStyleRow, LocaleController.getString(R.string.ListItems)).setChecked(NekoConfig.m3SectionsStyle).slug("m3SectionsStyle"));
        items.add(UItem.asCheck(materialSlidersRow, LocaleController.getString(R.string.MaterialSliders)).setChecked(NekoConfig.materialSliders).slug("materialSliders"));
        items.add(UItem.asCheck(material3BottomNavigationBarRow, LocaleController.getString(R.string.BottomNavigationBar)).setChecked(NekoConfig.material3BottomNavigationBar).slug("material3BottomNavigationBar"));
        items.add(UItem.asCheck(md3PlayerSeekBarRow, LocaleController.getString(R.string.PlayerSeekBar)).setChecked(NekoConfig.md3PlayerSeekBar).slug("md3PlayerSeekBar"));
        items.add(UItem.asCheck(md3FoldersRow, LocaleController.getString(R.string.Md3Folders)).setChecked(NekoConfig.md3Folders).slug("md3Folders"));
        items.add(InfoCheckCellFactory.of(loadingIndicatorsRow, LocaleController.getString(R.string.LoadingIndicators), NekoConfig.wavyEnabled, () -> showLoadingIndicatorsInfo()).slug("loadingIndicators"));
        items.add(UItem.asCheck(material3DialogsRow, LocaleController.getString(R.string.Material3Dialogs)).setChecked(NekoConfig.material3Dialogs).slug("material3Dialogs"));
        items.add(TextSettingsCellFactory.of(avatarShapeRow, LocaleController.getString(R.string.Avatars), "›").slug("avatarShape"));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader("Chat Header"));
        items.add(TextSettingsCellFactory.of(chatHeaderSettingsRow, LocaleController.getString(R.string.ChatHeaderSettings), "›").slug("chatHeaderSettings"));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.NonIslandUI)));
        items.add(UItem.asCheck(nonIslandTabBarsRow, LocaleController.getString(R.string.NonIslandTabBars)).setChecked(NekoConfig.nonIslandTabBars).slug("nonIslandTabBars"));
        items.add(UItem.asCheck(nonIslandGlobalSearchRow, LocaleController.getString(R.string.NonIslandGlobalSearch)).setChecked(NekoConfig.nonIslandGlobalSearch).slug("nonIslandGlobalSearch"));
        items.add(UItem.asCheck(nonIslandChatElementsRow, LocaleController.getString(R.string.NonIslandChatElements)).setChecked(NekoConfig.nonIslandChatElements).slug("nonIslandChatElements"));
        items.add(UItem.asShadow(LocaleController.getString(R.string.InuNonIslandHint)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.TextAnimation)));
        items.add(TextSettingsCellFactory.of(textAnimationSettingsRow, LocaleController.getString(R.string.TextAnimation), "›").slug("textAnimationSettings"));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.SavedDialogsTab)));
        items.add(UItem.asCheck(hideStoriesRow, LocaleController.getString(R.string.HideStories)).slug("hideStories").setChecked(NekoConfig.hideStories));
        items.add(UItem.asCheck(mediaPreviewRow, LocaleController.getString(R.string.MediaPreview)).slug("mediaPreview").setChecked(NekoConfig.mediaPreview));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.Filters)));
        items.add(UItem.asCheck(hideAllTabRow, LocaleController.getString(R.string.HideAllTab)).slug("hideAllTab").setChecked(NekoConfig.hideAllTab));
        items.add(TextSettingsCellFactory.of(tabsTitleTypeRow, LocaleController.getString(R.string.TabTitleType), switch (NekoConfig.tabsTitleType) {
            case NekoConfig.TITLE_TYPE_TEXT ->
                    LocaleController.getString(R.string.TabTitleTypeText);
            case NekoConfig.TITLE_TYPE_ICON ->
                    LocaleController.getString(R.string.TabTitleTypeIcon);
            default -> LocaleController.getString(R.string.TabTitleTypeMix);
        }).slug("tabsTitleType"));
        items.add(TextSettingsCellFactory.of(tabsPositionRow, LocaleController.getString(R.string.TabsPosition), LocaleController.getString(NekoConfig.bottomFilterTabs ? R.string.TabsPositionBottom : R.string.TabsPositionTop)).slug("tabsPosition"));
        items.add(UItem.asShadow(null));

    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        var id = item.id;
        if (id == textAnimationSettingsRow) {
            presentFragment(new NekoTextAnimationSettingsActivity());
        } else if (id == navigationSettingsRow) {
            presentFragment(new NekoNavigationSettingsActivity());
        } else if (id == emojiSetsRow) {
            presentFragment(new NekoEmojiSettingsActivity());
        } else if (id == disableNumberRoundingRow) {
            NekoConfig.toggleDisableNumberRounding();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.disableNumberRounding);
            }
        } else if (id == appBarShadowRow) {
            NekoConfig.toggleDisableAppBarShadow();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.disableAppBarShadow);
            }
            parentLayout.setHeaderShadow(NekoConfig.disableAppBarShadow ? null : parentLayout.getParentActivity().getDrawable(R.drawable.header_shadow).mutate());
            parentLayout.rebuildAllFragmentViews(false, false);
        } else if (id == mediaPreviewRow) {
            NekoConfig.toggleMediaPreview();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.mediaPreview);
            }
        } else if (id == hideStoriesRow) {
            NekoConfig.toggleHideStories();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.hideStories);
            }
            getNotificationCenter().postNotificationName(NotificationCenter.storiesEnabledUpdate);
        } else if (id == formatTimeWithSecondsRow) {
            NekoConfig.toggleFormatTimeWithSeconds();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.formatTimeWithSeconds);
            }
            parentLayout.rebuildAllFragmentViews(false, false);
        } else if (id == hideAllTabRow) {
            NekoConfig.toggleHideAllTab();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.hideAllTab);
            }
            getNotificationCenter().postNotificationName(NotificationCenter.dialogFiltersUpdated);
            getNotificationCenter().postNotificationName(NotificationCenter.mainUserInfoChanged);
        } else if (id == md3FoldersRow) {
            NekoConfig.toggleMd3Folders();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.md3Folders);
            }
            showRestartBulletin();
        } else if (id == tabsTitleTypeRow) {
            ArrayList<String> arrayList = new ArrayList<>();
            ArrayList<Integer> types = new ArrayList<>();
            arrayList.add(LocaleController.getString(R.string.TabTitleTypeText));
            types.add(NekoConfig.TITLE_TYPE_TEXT);
            arrayList.add(LocaleController.getString(R.string.TabTitleTypeIcon));
            types.add(NekoConfig.TITLE_TYPE_ICON);
            arrayList.add(LocaleController.getString(R.string.TabTitleTypeMix));
            types.add(NekoConfig.TITLE_TYPE_MIX);
            PopupHelper.show(arrayList, LocaleController.getString(R.string.TabTitleType), types.indexOf(NekoConfig.tabsTitleType), getParentActivity(), view, i -> {
                NekoConfig.setTabsTitleType(types.get(i));
                item.textValue = arrayList.get(i);
                listView.adapter.notifyItemChanged(position, PARTIAL);
                getNotificationCenter().postNotificationName(NotificationCenter.dialogFiltersUpdated);
            }, resourcesProvider);
        } else if (id == tabsPositionRow) {
            ArrayList<String> arrayList = new ArrayList<>();
            arrayList.add(LocaleController.getString(R.string.TabsPositionTop));
            arrayList.add(LocaleController.getString(R.string.TabsPositionBottom));
            PopupHelper.show(arrayList, LocaleController.getString(R.string.TabsPosition), NekoConfig.bottomFilterTabs ? 1 : 0, getParentActivity(), view, i -> {
                NekoConfig.setBottomFilterTabs(i == 1);
                item.textValue = arrayList.get(i);
                listView.adapter.notifyItemChanged(position, PARTIAL);
                parentLayout.rebuildAllFragmentViews(false, false);
            }, resourcesProvider);
        } else if (id == blurSettingsRow) {
            presentFragment(new NekoBlurSettingsActivity());
        } else if (id == disableGooeyAvatarAnimationRow) {
            NekoConfig.toggleDisableGooeyAvatarAnimation();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.disableGooeyAvatarAnimation);
            }
            showRestartBulletin();
        } else if (id == keepUnreadChatsOnTopRow) {
            NekoConfig.toggleKeepUnreadChatsOnTop();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.keepUnreadChatsOnTop);
            }
            showRestartBulletin();
            listView.adapter.update(true);
        } else if (id == keepUnreadArchivedOnTopRow) {
            NekoConfig.toggleKeepUnreadArchivedOnTop();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.keepUnreadArchivedOnTop);
            }
        } else if (id == hideRecordButtonRow) {
            NekoConfig.toggleHideRecordButton();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.hideRecordButton);
            }
        } else if (id == material3SwitchesRow) {
            NekoConfig.toggleMaterial3Switches();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.material3Switches);
            }
            showRestartBulletin();
        } else if (id == m3SectionsStyleRow) {
            NekoConfig.toggleM3SectionsStyle();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.m3SectionsStyle);
            }
            showRestartBulletin();
        } else if (id == materialSlidersRow) {
            NekoConfig.toggleMaterialSliders();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.materialSliders);
            }
            listView.adapter.update(true);
        } else if (id == nonIslandTabBarsRow) {
            NekoConfig.toggleNonIslandTabBars();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.nonIslandTabBars);
            }
        } else if (id == nonIslandGlobalSearchRow) {
            NekoConfig.toggleNonIslandGlobalSearch();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.nonIslandGlobalSearch);
            }
        } else if (id == nonIslandChatElementsRow) {
            if (!NekoConfig.nonIslandChatElements && NekoConfig.material3ChatHeaders) {
                showHeaderConflictBulletin(LocaleController.getString(R.string.Material3ChatHeaders), LocaleController.getString(R.string.NonIslandChatElements), () -> {
                    NekoConfig.toggleMaterial3ChatHeaders();
                    NekoConfig.toggleNonIslandChatElements();
                    listView.adapter.update(true);
                });
                return;
            }
            NekoConfig.toggleNonIslandChatElements();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.nonIslandChatElements);
            }
        } else if (id == material3ChatHeadersRow) {
            if (!NekoConfig.material3ChatHeaders && NekoConfig.nonIslandChatElements) {
                showHeaderConflictBulletin(LocaleController.getString(R.string.NonIslandChatElements), LocaleController.getString(R.string.Material3ChatHeaders), () -> {
                    NekoConfig.toggleNonIslandChatElements();
                    NekoConfig.toggleMaterial3ChatHeaders();
                    listView.adapter.update(true);
                });
                return;
            }
            NekoConfig.toggleMaterial3ChatHeaders();
            if (view instanceof InfoCheckCell) {
                ((InfoCheckCell) view).setChecked(NekoConfig.material3ChatHeaders);
            }
        } else if (id == loadingIndicatorsRow) {
            NekoConfig.toggleWavyEnabled();
            if (view instanceof InfoCheckCell) {
                ((InfoCheckCell) view).setChecked(NekoConfig.wavyEnabled);
            }
        } else if (id == material3BottomNavigationBarRow) {
            NekoConfig.toggleMaterial3BottomNavigationBar();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.material3BottomNavigationBar);
            }
            parentLayout.rebuildAllFragmentViews(false, false);
        } else if (id == md3PlayerSeekBarRow) {
            NekoConfig.toggleMd3PlayerSeekBar();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.md3PlayerSeekBar);
            }
            listView.adapter.update(true);
        } else if (id == material3DialogsRow) {
            if (!NekoConfig.material3Dialogs && NekoConfig.replaceDialogsWithSheet) {
                showMaterial3DialogsConflictBulletin();
                return;
            }
            NekoConfig.toggleMaterial3Dialogs();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.material3Dialogs);
            }
            listView.adapter.update(true);
        } else if (id == avatarShapeRow) {
            presentFragment(new NekoAvatarShapeSettingsActivity());
        } else if (id == roundedBulletinRow) {
            NekoConfig.toggleRoundedBulletin();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.roundedBulletin);
            }
        } else if (id == liquidGlassRow) {
            presentFragment(new NekoLiquidGlassSettingsActivity());
        } else if (id == chatHeaderSettingsRow) {
            presentFragment(new NekoChatHeaderSettingsActivity());
        }
    }

    private void showLoadingIndicatorsInfo() {
        if (getParentActivity() == null) return;
        org.telegram.ui.ActionBar.BottomSheet sheet = new org.telegram.ui.ActionBar.BottomSheet(getParentActivity(), false, resourcesProvider);
        sheet.setTitle(LocaleController.getString(R.string.LoadingIndicators));

        LinearLayout container = new LinearLayout(getParentActivity());
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(16), AndroidUtilities.dp(24), AndroidUtilities.dp(24));

        View previewView = new View(getParentActivity()) {
            private final org.telegram.ui.Components.CircularProgressDrawable defaultCpd;
            private final org.telegram.ui.Components.CircularProgressDrawable md3Cpd;
            private final org.telegram.ui.Components.MediaActionDrawable defaultMad;
            private final org.telegram.ui.Components.MediaActionDrawable md3Mad;
            {
                int color = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider);
                defaultCpd = new org.telegram.ui.Components.CircularProgressDrawable(color);
                defaultCpd.size = AndroidUtilities.dp(36);
                defaultCpd.thickness = AndroidUtilities.dp(3);
                defaultCpd.setCallback(this);
                md3Cpd = new org.telegram.ui.Components.CircularProgressDrawable(color);
                md3Cpd.size = AndroidUtilities.dp(36);
                md3Cpd.thickness = AndroidUtilities.dp(3);
                md3Cpd.setCallback(this);
                defaultMad = new org.telegram.ui.Components.MediaActionDrawable();
                defaultMad.setColor(color);
                defaultMad.setCallback(this);
                defaultMad.setIcon(org.telegram.ui.Components.MediaActionDrawable.ICON_CANCEL, false);
                defaultMad.setProgress(0, false);
                md3Mad = new org.telegram.ui.Components.MediaActionDrawable();
                md3Mad.setColor(color);
                md3Mad.setCallback(this);
                md3Mad.setIcon(org.telegram.ui.Components.MediaActionDrawable.ICON_CANCEL, false);
                md3Mad.setProgress(0, false);
                setWillNotDraw(false);
            }
            @Override
            protected void onDraw(Canvas canvas) {
                super.onDraw(canvas);
                int w = getWidth();
                int halfW = w / 2;
                int cpdSize = AndroidUtilities.dp(60);
                int madSize = AndroidUtilities.dp(48);
                int cy1 = getHeight() / 4;
                int cy2 = 3 * getHeight() / 4;
                int leftCenterX = halfW / 2;
                int rightCenterX = halfW + halfW / 2;
                boolean savedWavy = zxc.iconic.xenon.NekoConfig.wavyEnabled;

                zxc.iconic.xenon.NekoConfig.wavyEnabled = false;
                defaultCpd.setBounds(leftCenterX - cpdSize / 2, cy1 - cpdSize / 2, leftCenterX + cpdSize / 2, cy1 + cpdSize / 2);
                defaultCpd.draw(canvas);
                defaultMad.setBounds(leftCenterX - madSize / 2, cy2 - madSize / 2, leftCenterX + madSize / 2, cy2 + madSize / 2);
                defaultMad.draw(canvas);

                zxc.iconic.xenon.NekoConfig.wavyEnabled = true;
                md3Cpd.setBounds(rightCenterX - cpdSize / 2, cy1 - cpdSize / 2, rightCenterX + cpdSize / 2, cy1 + cpdSize / 2);
                md3Cpd.draw(canvas);
                md3Mad.setBounds(rightCenterX - madSize / 2, cy2 - madSize / 2, rightCenterX + madSize / 2, cy2 + madSize / 2);
                md3Mad.draw(canvas);

                zxc.iconic.xenon.NekoConfig.wavyEnabled = savedWavy;
                invalidate();
            }
        };
        previewView.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(140)));
        container.addView(previewView);

        LinearLayout labelsLayout = new LinearLayout(getParentActivity());
        labelsLayout.setOrientation(LinearLayout.HORIZONTAL);
        labelsLayout.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        TextView leftLabel = new TextView(getParentActivity());
        leftLabel.setText("Telegram");
        leftLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        leftLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider));
        leftLabel.setGravity(Gravity.CENTER);
        leftLabel.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        labelsLayout.addView(leftLabel);

        TextView rightLabel = new TextView(getParentActivity());
        rightLabel.setText(LocaleController.getString(R.string.MaterialDesign3));
        rightLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        rightLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider));
        rightLabel.setGravity(Gravity.CENTER);
        rightLabel.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        labelsLayout.addView(rightLabel);

        container.addView(labelsLayout);

        sheet.setCustomView(container);
        sheet.show();
    }

    private void showChatHeadersInfo() {
        if (getParentActivity() == null) return;
        org.telegram.ui.ActionBar.BottomSheet sheet = new org.telegram.ui.ActionBar.BottomSheet(getParentActivity(), false, resourcesProvider);
        sheet.setTitle(LocaleController.getString(R.string.Material3ChatHeaders));

        LinearLayout container = new LinearLayout(getParentActivity());
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(0, 0, 0, 0);

        int currentAccount = UserConfig.selectedAccount;
        TLRPC.User user = org.telegram.messenger.MessagesController.getInstance(currentAccount).getUser(UserConfig.getInstance(currentAccount).clientUserId);
        if (user == null) user = UserConfig.getInstance(currentAccount).getCurrentUser();
        String userName = user != null ? UserObject.getUserName(user) : "User";
        String onlineText = LocaleController.getString(R.string.Online);

        org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceBitmap wallpaperSource = new org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceBitmap();

        org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory factory = new org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory(wallpaperSource);
        org.telegram.ui.Components.blur3.drawable.color.BlurredBackgroundProvider colorProvider = org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl.topPanelChatActivity(resourcesProvider);

        org.telegram.ui.ActionBar.ActionBar normalActionBar = new org.telegram.ui.ActionBar.ActionBar(getParentActivity(), resourcesProvider);
        normalActionBar.setOccupyStatusBar(false);
        normalActionBar.setTitle("");
        normalActionBar.setupGlass(factory, colorProvider, false);
        ChatAvatarContainer normalAvatar = new ChatAvatarContainer(getParentActivity(), null, false, resourcesProvider);
        normalAvatar.setOccupyStatusBar(false);
        normalAvatar.setUserAvatar(user, true);
        normalAvatar.setTitle(userName, false, false, false, false, null, false);
        normalAvatar.setSubtitle(onlineText);
        normalAvatar.setGlassMode();
        normalAvatar.setM3HeaderMode(false);
        normalActionBar.setChatAvatarContainer(normalAvatar);
        normalActionBar.setBackButtonDrawable(new org.telegram.ui.ActionBar.BackDrawable(false));
        normalActionBar.addView(normalAvatar, 0, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT, 52, 0, 52, 0));
        normalActionBar.createMenu().addItem(999, R.drawable.ic_ab_other);
        normalActionBar.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(56)));
        container.addView(normalActionBar);

        TextView normalLabel = new TextView(getParentActivity());
        normalLabel.setText("Telegram");
        normalLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        normalLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider));
        normalLabel.setGravity(Gravity.CENTER);
        normalLabel.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(16));
        container.addView(normalLabel);

        org.telegram.ui.ActionBar.ActionBar m3ActionBar = new org.telegram.ui.ActionBar.ActionBar(getParentActivity(), resourcesProvider);
        m3ActionBar.setOccupyStatusBar(false);
        m3ActionBar.setTitle("");
        m3ActionBar.inu_m3ChatHeader = true;
        m3ActionBar.setupGlass(factory, colorProvider, false);
        ChatAvatarContainer m3Avatar = new ChatAvatarContainer(getParentActivity(), null, false, resourcesProvider);
        m3Avatar.setOccupyStatusBar(false);
        m3Avatar.setUserAvatar(user, true);
        m3Avatar.setTitle(userName, false, false, false, false, null, false);
        m3Avatar.setSubtitle(onlineText);
        m3Avatar.setM3HeaderMode(true);
        m3ActionBar.setChatAvatarContainer(m3Avatar);
        m3ActionBar.setBackButtonDrawable(new org.telegram.ui.ActionBar.BackDrawable(false));
        m3ActionBar.addView(m3Avatar, 0, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.MATCH_PARENT, Gravity.TOP | Gravity.LEFT, 52, 0, 52, 0));
        m3ActionBar.createMenu().addItem(999, R.drawable.ic_ab_other);
        m3ActionBar.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(56)));
        container.addView(m3ActionBar);

        TextView m3Label = new TextView(getParentActivity());
        m3Label.setText(LocaleController.getString(R.string.MaterialDesign3));
        m3Label.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        m3Label.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2, resourcesProvider));
        m3Label.setGravity(Gravity.CENTER);
        m3Label.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(24));
        container.addView(m3Label);

        sheet.setCustomView(container);
        sheet.show();
    }

    private void showHeaderConflictBulletin(String disableWhat, String enableWhat, Runnable onDisable) {
        BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip,
                LocaleController.formatString(R.string.Material3ChatHeadersConflict, disableWhat, enableWhat),
                LocaleController.getString(R.string.Disable),
                onDisable).show();
    }

    private void showMaterial3DialogsConflictBulletin() {
        BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip,
                LocaleController.formatString(R.string.Material3DialogsConflict,
                        LocaleController.getString(R.string.ReplaceDialogsWithSheet),
                        LocaleController.getString(R.string.Material3Dialogs)),
                LocaleController.getString(R.string.Disable),
                () -> {
                    NekoConfig.toggleReplaceDialogsWithSheet();
                    NekoConfig.toggleMaterial3Dialogs();
                    listView.adapter.update(true);
                }).show();
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.ChangeChannelNameColor2);
    }

    @Override
    protected String getKey() {
        return "a";
    }

    private static class EmojiSetCellFactory extends UItem.UItemFactory<EmojiSetCell> {
        static {
            setup(new EmojiSetCellFactory());
        }

        @Override
        public EmojiSetCell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            return new EmojiSetCell(context, false, resourcesProvider);
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
            var cell = (EmojiSetCell) view;
            var pack = cell.getPack();
            var newPack = EmojiHelper.getInstance().getCurrentEmojiPackInfo();
            cell.setData(newPack, pack != null && !pack.getPackId().equals(newPack.getPackId()), divider);
        }

        public static UItem of(int id, String title) {
            var item = UItem.ofFactory(EmojiSetCellFactory.class);
            item.id = id;
            item.text = title;
            return item;
        }
    }
}
