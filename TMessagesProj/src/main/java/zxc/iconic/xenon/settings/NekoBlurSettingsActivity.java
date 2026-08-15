package zxc.iconic.xenon.settings;

import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

import zxc.iconic.xenon.NekoConfig;

public class NekoBlurSettingsActivity extends BaseNekoSettingsActivity {

    private final int testBottomSheetRow = rowId++;
    private final int blurOverlayRow = rowId++;
    private final int replaceDialogsWithSheetRow = rowId++;
    private final int blurPopupInChatRow = rowId++;
    private final int blurOverlayRadiusRow = rowId++;
    private final int blurPixelationRow = rowId++;
    private final int blurSmoothlyRow = rowId++;
    private final int blurAnimationDurationRow = rowId++;
    private final int disableBlurBsRow = rowId++;

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asButton(testBottomSheetRow, R.drawable.msg_settings, LocaleController.getString(R.string.TestBottomSheet)).slug("testBottomSheet"));
        items.add(UItem.asShadow(null));
        items.add(UItem.asCheck(blurOverlayRow, LocaleController.getString(R.string.BlurOverlay)).setChecked(NekoConfig.blurOverlay).slug("blurOverlay"));
        items.add(UItem.asCheck(replaceDialogsWithSheetRow, LocaleController.getString(R.string.ReplaceDialogsWithSheet)).setChecked(NekoConfig.replaceDialogsWithSheet).slug("replaceDialogsWithSheet"));
        items.add(UItem.asCheck(blurPopupInChatRow, LocaleController.getString(R.string.BlurPopupInChat)).setChecked(NekoConfig.blurPopupInChat).slug("blurPopupInChat"));
        items.add(UItem.asShadow(null));

        items.add(UItem.asHeader(LocaleController.getString(R.string.BlurSettingsHeader)));
        if (NekoConfig.blurOverlay || NekoConfig.blurPopupInChat) {
            SeekbarConfig radiusConfig = new SeekbarConfig(
                    LocaleController.getString(R.string.BlurOverlayRadius),
                    "2", "20", 2, 20, 1,
                    progress -> NekoConfig.setBlurOverlayRadius(Math.round(progress)));
            items.add(SeekbarCellFactory.of(blurOverlayRadiusRow, radiusConfig, NekoConfig.blurOverlayRadius).slug("blurOverlayRadius"));
            SeekbarConfig pixelationConfig = new SeekbarConfig(
                    LocaleController.getString(R.string.BlurPixelation),
                    "0", "100", 0, 100, 1,
                    progress -> NekoConfig.setBlurPixelation(Math.round(progress)));
            items.add(SeekbarCellFactory.of(blurPixelationRow, pixelationConfig, NekoConfig.blurPixelation).slug("blurPixelation"));
            items.add(UItem.asCheck(blurSmoothlyRow, LocaleController.getString(R.string.BlurSmoothly)).setChecked(NekoConfig.blurSmoothly).slug("blurSmoothly"));
            if (NekoConfig.blurSmoothly) {
                SeekbarConfig animDurationConfig = new SeekbarConfig(
                        LocaleController.getString(R.string.BlurAnimationDuration),
                        "100", "1000", 100, 1000, 10,
                        progress -> NekoConfig.setBlurAnimationDuration(Math.round(progress / 10f) * 10));
                items.add(SeekbarCellFactory.of(blurAnimationDurationRow, animDurationConfig, NekoConfig.blurAnimationDuration).slug("blurAnimationDuration"));
            }
            items.add(UItem.asCheck(disableBlurBsRow, LocaleController.getString(R.string.DisableBlurBs)).setChecked(NekoConfig.disableBlurBs).slug("disableBlurBs"));
        }
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        var id = item.id;
        if (id == testBottomSheetRow) {
            BottomSheet.Builder builder = new BottomSheet.Builder(getParentActivity(), false, null);
            FrameLayout content = new FrameLayout(getParentActivity());
            TextView textView = new TextView(getParentActivity());
            textView.setText(LocaleController.getString(R.string.TestBottomSheetText));
            textView.setTextSize(16);
            textView.setTextColor(Theme.getColor(Theme.key_dialogTextBlack));
            textView.setGravity(android.view.Gravity.LEFT | android.view.Gravity.CENTER_VERTICAL);
            textView.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(16), AndroidUtilities.dp(24), AndroidUtilities.dp(16));
            content.addView(textView);
            builder.setCustomView(content);
            builder.show();
        } else if (id == blurOverlayRow) {
            NekoConfig.toggleBlurOverlay();
            item.checked = NekoConfig.blurOverlay;
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.blurOverlay);
            }
            listView.adapter.update(true);
            if (NekoConfig.blurOverlay && !NekoConfig.replaceDialogsWithSheet) {
                BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip,
                        LocaleController.getString(R.string.BlurOverlayBulletinText),
                        LocaleController.getString(R.string.BlurOverlayBulletinButton),
                        () -> {
                            NekoConfig.toggleReplaceDialogsWithSheet();
                            listView.adapter.update(true);
                        }).show();
            }
        } else if (id == replaceDialogsWithSheetRow) {
            if (!NekoConfig.replaceDialogsWithSheet && NekoConfig.material3Dialogs) {
                showReplaceDialogsConflictBulletin();
                return;
            }
            NekoConfig.toggleReplaceDialogsWithSheet();
            item.checked = NekoConfig.replaceDialogsWithSheet;
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.replaceDialogsWithSheet);
            }
        } else if (id == blurPopupInChatRow) {
            NekoConfig.toggleBlurPopupInChat();
            item.checked = NekoConfig.blurPopupInChat;
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.blurPopupInChat);
            }
        } else if (id == blurSmoothlyRow) {
            NekoConfig.toggleBlurSmoothly();
            item.checked = NekoConfig.blurSmoothly;
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.blurSmoothly);
            }
            listView.adapter.update(true);
        } else if (id == disableBlurBsRow) {
            NekoConfig.toggleDisableBlurBs();
            item.checked = NekoConfig.disableBlurBs;
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.disableBlurBs);
            }
        }
    }

    private void showReplaceDialogsConflictBulletin() {
        BulletinFactory.of(this).createSimpleBulletin(R.raw.chats_infotip,
                LocaleController.formatString(R.string.Material3DialogsConflict,
                        LocaleController.getString(R.string.Material3Dialogs),
                        LocaleController.getString(R.string.ReplaceDialogsWithSheet)),
                LocaleController.getString(R.string.Disable),
                () -> {
                    NekoConfig.toggleMaterial3Dialogs();
                    NekoConfig.toggleReplaceDialogsWithSheet();
                    listView.adapter.update(true);
                }).show();
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.BlurSettings);
    }
}
