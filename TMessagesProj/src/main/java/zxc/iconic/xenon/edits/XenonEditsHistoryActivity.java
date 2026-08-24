package zxc.iconic.xenon.edits;

import android.content.Context;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.tgnet.TLRPC;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;

import java.util.Date;
import java.util.List;

public class XenonEditsHistoryActivity extends BaseFragment {

    private final long dialogId;
    private final int messageId;
    private final int account;

    public XenonEditsHistoryActivity(long dialogId, int messageId, int account) {
        this.dialogId = dialogId;
        this.messageId = messageId;
        this.account = account;
    }

    @Override
    public boolean onFragmentCreate() {
        return super.onFragmentCreate();
    }

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(true);
        actionBar.setTitle(LocaleController.getString(R.string.EditsHistoryMenuText));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                }
            }
        });

        ScrollView scrollView = new ScrollView(context);
        scrollView.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundGray));

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.VERTICAL);
        scrollView.addView(root, new FrameLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        List<TLRPC.Message> revisions = XenonEditsHistoryController.getInstance().getRevisions(dialogId, messageId, account);
        if (revisions.isEmpty()) {
            TextView empty = new TextView(context);
            empty.setText(LocaleController.getString(R.string.EditsHistoryEmpty));
            empty.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(32), AndroidUtilities.dp(16), AndroidUtilities.dp(32));
            root.addView(empty);
        } else {
            for (TLRPC.Message m : revisions) {
                root.addView(buildRevision(context, m));
            }
        }

        fragmentView = scrollView;
        return fragmentView;
    }

    private View buildRevision(Context context, TLRPC.Message m) {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));
        card.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12));

        TextView time = new TextView(context);
        long ts = m.edit_date != 0 ? m.edit_date * 1000L : m.date * 1000L;
        time.setText(DateFormat.format("dd.MM.yyyy HH:mm:ss", new Date(ts)).toString());
        time.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText));
        time.setTextSize(12);

        TextView text = new TextView(context);
        text.setText(m.message != null ? m.message : "");
        text.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        text.setTextSize(15);

        card.addView(time);
        card.addView(text);

        LinearLayout wrap = new LinearLayout(context);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.addView(card);
        LinearLayout.LayoutParams sep = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, AndroidUtilities.dp(8));
        View divider = new View(context);
        divider.setLayoutParams(sep);
        wrap.addView(divider);
        return wrap;
    }
}