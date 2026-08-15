package zxc.iconic.xenon.settings;

import android.content.Context;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.AvatarDrawable;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

import zxc.iconic.xenon.NekoConfig;

public class NekoAvatarShapeSettingsActivity extends BaseNekoSettingsActivity {

    private static final int SHAPES_OFFSET = 10000;

    private final int applyInChatListRow = rowId++;
    private final int applyInChatMessagesRow = rowId++;
    private final int rotateShapeRow = rowId++;
    private final int rotateSpeedRow = rowId++;
    private final int squareBaseRow = rowId++;

    private AvatarShapePreviewView previewView;

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        if (previewView == null) {
            previewView = new AvatarShapePreviewView(getParentActivity());
            previewView.setShape(NekoConfig.avatarShape);
        }
        items.add(UItem.asCustom(previewView, 120));
        items.add(UItem.asShadow(null));
        items.add(UItem.asCheck(applyInChatListRow, LocaleController.getString(R.string.ApplyInChatList)).setChecked(NekoConfig.avatarShapeInChatList).slug("applyInChatList"));
        items.add(UItem.asCheck(applyInChatMessagesRow, LocaleController.getString(R.string.ApplyForChatMessages)).setChecked(NekoConfig.avatarShapeInChatMessages).slug("applyInChatMessages"));
        items.add(UItem.asCheck(rotateShapeRow, LocaleController.getString(R.string.RotateShape)).setChecked(NekoConfig.rotateAvatarShape).slug("rotateShape"));
        if (NekoConfig.rotateAvatarShape) {
            SeekbarConfig speedConfig = new SeekbarConfig(
                    LocaleController.getString(R.string.RotationSpeed),
                    "1", "360", 1, 360, 1,
                    progress -> NekoConfig.setAvatarShapeRotationSpeed(Math.round(progress)));
            items.add(SeekbarCellFactory.of(rotateSpeedRow, speedConfig, NekoConfig.avatarShapeRotationSpeed).slug("rotateSpeed"));
        }
        items.add(UItem.asCheck(squareBaseRow, LocaleController.getString(R.string.SquareBase)).setChecked(NekoConfig.avatarShapeSquareBase).slug("squareBase"));
        items.add(UItem.asShadow(null));
        items.add(UItem.asHeader(LocaleController.getString(R.string.AvatarShapesHeader)));

        int accentColor = Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider);
        int grayColor = Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider);
        int iconSize = AndroidUtilities.dp(24);
        for (int i = 0; i < AvatarShapeHelper.shapeCount(); i++) {
            int color = (i == NekoConfig.avatarShape) ? accentColor : grayColor;
            Drawable icon = AvatarShapeHelper.iconForShape(i, iconSize, color);
            items.add(UItem.asButton(SHAPES_OFFSET + i, icon, AvatarShapeHelper.nameAt(i)));
        }
        items.add(UItem.asShadow(null));
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;
        if (id >= SHAPES_OFFSET && id < SHAPES_OFFSET + AvatarShapeHelper.shapeCount()) {
            int shapeIndex = id - SHAPES_OFFSET;
            NekoConfig.setAvatarShape(shapeIndex);
            if (previewView != null) {
                previewView.setShape(shapeIndex);
            }
            listView.adapter.update(true);
        } else if (id == applyInChatListRow) {
            NekoConfig.toggleAvatarShapeInChatList();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.avatarShapeInChatList);
            }
            listView.adapter.update(true);
        } else if (id == applyInChatMessagesRow) {
            NekoConfig.toggleAvatarShapeInChatMessages();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.avatarShapeInChatMessages);
            }
            listView.adapter.update(true);
        } else if (id == rotateShapeRow) {
            NekoConfig.toggleRotateAvatarShape();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.rotateAvatarShape);
            }
            listView.adapter.update(true);
            listView.post(() -> { if (previewView != null) previewView.invalidate(); });
        } else if (id == squareBaseRow) {
            NekoConfig.toggleAvatarShapeSquareBase();
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.avatarShapeSquareBase);
            }
            listView.adapter.update(true);
            listView.post(() -> { if (previewView != null) previewView.setShape(NekoConfig.avatarShape); });
        }
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.Avatars);
    }

    private static class AvatarShapePreviewView extends FrameLayout {
        private final BackupImageView avatarView;
        private final int avatarSize;
        private int shapeIndex = 0;

        public AvatarShapePreviewView(Context context) {
            super(context);
            setWillNotDraw(false);
            avatarSize = AndroidUtilities.dp(80);
            avatarView = new BackupImageView(context);
            addView(avatarView, new FrameLayout.LayoutParams(avatarSize, avatarSize, Gravity.CENTER));
            int currentAccount = UserConfig.selectedAccount;
            TLRPC.User user = UserConfig.getInstance(currentAccount).getCurrentUser();
            if (user != null) {
                AvatarDrawable avatarDrawable = new AvatarDrawable();
                avatarDrawable.setInfo(currentAccount, user);
                avatarView.setForUserOrChat(user, avatarDrawable);
            }
            applyRoundRadius();
        }

        public void setShape(int index) {
            this.shapeIndex = index;
            applyRoundRadius();
            invalidate();
        }

        @Override
        protected void onAttachedToWindow() {
            super.onAttachedToWindow();
            applyRoundRadius();
            if (NekoConfig.rotateAvatarShape) {
                invalidate();
            }
        }

        @Override
        protected void onDetachedFromWindow() {
            super.onDetachedFromWindow();
            avatarView.getImageReceiver().setVisible(false, false);
        }

        private void applyRoundRadius() {
            if (shapeIndex == 0 || !NekoConfig.avatarShapeSquareBase) {
                avatarView.setRoundRadius(avatarSize / 2);
            } else {
                avatarView.setRoundRadius(0);
            }
        }
    }
}
