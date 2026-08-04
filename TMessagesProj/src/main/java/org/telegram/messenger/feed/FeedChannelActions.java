package org.telegram.messenger.feed;

import android.app.Activity;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.BaseFragment;

public class FeedChannelActions {
    public static boolean canLeave(TLRPC.Chat chat) {
        return chat != null && (chat.left || chat.kicked || !chat.creator);
    }

    public static void leaveChannel(BaseFragment fragment, TLRPC.Chat chat, Runnable after) {
        if (fragment == null || fragment.getParentActivity() == null) return;
        AlertDialog.Builder b = new AlertDialog.Builder(fragment.getParentActivity(), fragment.getResourceProvider());
        b.setTitle(LocaleController.getString(R.string.LeaveChannelMenu));
        b.setMessage(LocaleController.getString(R.string.LeaveChannelMenu));
        b.setPositiveButton(LocaleController.getString(R.string.LeaveChannelMenu), (d, w) -> {
            // TODO: actual leave channel via MessagesController
            if (after != null) after.run();
        });
        b.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        fragment.showDialog(b.create());
    }
}
