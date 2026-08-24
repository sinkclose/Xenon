package zxc.iconic.xenon.settings;

import android.content.Context;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.Utilities;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

import zxc.iconic.xenon.NekoConfig;
import zxc.iconic.xenon.deleted.XenonDeletedMessagesController;
import zxc.iconic.xenon.edits.XenonEditsHistoryController;

public class NekoAyuSettingsActivity extends BaseNekoSettingsActivity {

    private final int saveDeletedMessagesRow = rowId++;
    private final int saveEditsHistoryRow = rowId++;
    private final int pushServiceRow = rowId++;
    private final int optimizedPushServiceRow = rowId++;
    private final int clearDatabaseRow = rowId++;

    private String dbSizeText = "";

    @Override
    public boolean onFragmentCreate() {
        recomputeStorageSize();
        return super.onFragmentCreate();
    }

    @Override
    public void onResume() {
        super.onResume();
        recomputeStorageSize();
    }

    private void recomputeStorageSize() {
        Utilities.globalQueue.postRunnable(() -> {
            long size = XenonDeletedMessagesController.getInstance().getStorageSize()
                    + XenonEditsHistoryController.getInstance().getStorageSize();
            String formatted = size > 0 ? AndroidUtilities.formatFileSize(size) : "";
            AndroidUtilities.runOnUIThread(() -> {
                dbSizeText = formatted;
                if (listView != null && listView.adapter != null) {
                    listView.adapter.update(true);
                }
            });
        });
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asCheck(saveDeletedMessagesRow, (LocaleController.getString(R.string.SaveDeletedMessages) + " (WIP)"), LocaleController.getString(R.string.SaveDeletedMessagesDesc)).slug("saveDeletedMessages").setChecked(NekoConfig.enableSaveDeletedMessages));
        items.add(UItem.asCheck(saveEditsHistoryRow, LocaleController.getString(R.string.SaveEditsHistory), LocaleController.getString(R.string.SaveEditsHistoryDesc)).slug("saveEditsHistory").setChecked(NekoConfig.enableSaveEditsHistory));
        items.add(UItem.asCheck(pushServiceRow, LocaleController.getString(R.string.EnablePushService), LocaleController.getString(R.string.EnablePushServiceDesc)).slug("pushService").setChecked(NekoConfig.enablePushService));
        items.add(UItem.asCheck(optimizedPushServiceRow, LocaleController.getString(R.string.OptimizedPushService), LocaleController.getString(R.string.OptimizedPushServiceDesc)).slug("optimizedPushService").setChecked(NekoConfig.optimizedPushService));
        items.add(UItem.asShadow(null));
        items.add(TextSettingsCellFactory.of(clearDatabaseRow, LocaleController.getString(R.string.ClearDatabase), dbSizeText).accent().slug("clearDatabase"));
        items.add(UItem.asShadow(LocaleController.getString(R.string.ClearDatabaseDesc)));
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;
        if (id == saveDeletedMessagesRow) {
            NekoConfig.toggleEnableSaveDeletedMessages();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.enableSaveDeletedMessages);
            }
        } else if (id == saveEditsHistoryRow) {
            NekoConfig.toggleEnableSaveEditsHistory();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.enableSaveEditsHistory);
            }
        } else if (id == pushServiceRow) {
            NekoConfig.toggleEnablePushService();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.enablePushService);
            }
        } else if (id == optimizedPushServiceRow) {
            NekoConfig.toggleOptimizedPushService();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.optimizedPushService);
            }
        } else if (id == clearDatabaseRow) {
            showClearDatabaseSheet();
        }
    }

    private void showClearDatabaseSheet() {
        Context context = getParentActivity();
        if (context == null) return;

        Utilities.globalQueue.postRunnable(() -> {
            int deletedCount = XenonDeletedMessagesController.getInstance().getItemCount();
            int editsCount = XenonEditsHistoryController.getInstance().getItemCount();
            String sizeText = dbSizeText;

            AndroidUtilities.runOnUIThread(() -> {
                Context ctx = getParentActivity();
                if (ctx == null) return;

                BottomSheet.Builder builder = new BottomSheet.Builder(ctx, false, getResourceProvider());

                LinearLayout content = new LinearLayout(ctx);
                content.setOrientation(LinearLayout.VERTICAL);
                content.setPadding(AndroidUtilities.dp(22), AndroidUtilities.dp(18), AndroidUtilities.dp(22), AndroidUtilities.dp(12));

                TextView title = new TextView(ctx);
                title.setText(LocaleController.getString(R.string.ClearDatabase));
                title.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 20);
                title.setTypeface(AndroidUtilities.bold());
                title.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, getResourceProvider()));
                content.addView(title, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

                TextView message = new TextView(ctx);
                String msg = LocaleController.getString(R.string.ClearDatabaseConfirm);
                if (sizeText != null && !sizeText.isEmpty()) {
                    msg += "\n\n" + LocaleController.formatString(R.string.ClearDatabaseFreeUp, sizeText, deletedCount, editsCount);
                }
                message.setText(msg);
                message.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                message.setTextColor(Theme.getColor(Theme.key_dialogTextGray3, getResourceProvider()));
                message.setPadding(0, AndroidUtilities.dp(10), 0, AndroidUtilities.dp(16));
                content.addView(message, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

                TextView clearButton = new TextView(ctx);
                clearButton.setText(LocaleController.getString(R.string.Clear));
                clearButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                clearButton.setTypeface(AndroidUtilities.bold());
                clearButton.setGravity(Gravity.CENTER);
                clearButton.setTextColor(Theme.getColor(Theme.key_text_RedBold, getResourceProvider()));
                clearButton.setBackground(Theme.getRoundRectSelectorDrawable(Theme.getColor(Theme.key_text_RedBold, getResourceProvider())));
                clearButton.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12));
                content.addView(clearButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 0));

                TextView cancelButton = new TextView(ctx);
                cancelButton.setText(LocaleController.getString(R.string.Cancel));
                cancelButton.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
                cancelButton.setTypeface(AndroidUtilities.bold());
                cancelButton.setGravity(Gravity.CENTER);
                cancelButton.setTextColor(Theme.getColor(Theme.key_dialogTextBlue2, getResourceProvider()));
                cancelButton.setBackground(Theme.getRoundRectSelectorDrawable(Theme.getColor(Theme.key_dialogTextBlue2, getResourceProvider())));
                cancelButton.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12));
                content.addView(cancelButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 4, 0, 4));

                builder.setCustomView(content);
                BottomSheet sheet = builder.create();

                clearButton.setOnClickListener(v -> {
                    sheet.dismiss();
                    Utilities.globalQueue.postRunnable(() -> {
                        XenonDeletedMessagesController.getInstance().clearAll();
                        XenonEditsHistoryController.getInstance().clearAll();
                        AndroidUtilities.runOnUIThread(() -> {
                            BulletinFactory.global().createSimpleBulletin(R.raw.chats_infotip, LocaleController.getString(R.string.ClearDatabaseDone)).show();
                            recomputeStorageSize();
                        });
                    });
                });
                cancelButton.setOnClickListener(v -> sheet.dismiss());

                sheet.show();
            });
        });
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.Ayugram);
    }
}
