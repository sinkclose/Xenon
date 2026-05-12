package zxc.iconic.xenon.settings;

import android.content.Context;
import android.content.Intent;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.LayoutHelper;

import java.util.ArrayList;

import zxc.iconic.xenon.proxy.XrayAppProxyManager;

/**
 * Read-only monospace viewer for recent Xray core lifecycle logs.
 * Uses the Telegram native action bar; overflow menu hosts copy/share/clear.
 */
public class NekoXrayProxyLogsActivity extends BaseFragment {

    private static final int MENU_COPY = 1;
    private static final int MENU_SHARE = 2;
    private static final int MENU_CLEAR = 3;

    private TextView logView;
    private ScrollView scrollView;

    @Override
    public View createView(Context context) {
        actionBar.setBackButtonImage(R.drawable.ic_ab_back);
        actionBar.setAllowOverlayTitle(false);
        actionBar.setTitle(LocaleController.getString(R.string.XrayProxyLogs));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                    return;
                }
                if (id == MENU_COPY) {
                    copyAllLogs();
                    return;
                }
                if (id == MENU_SHARE) {
                    shareAllLogs();
                    return;
                }
                if (id == MENU_CLEAR) {
                    clearLogs();
                }
            }
        });

        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem overflow = menu.addItem(0, R.drawable.ic_ab_other);
        overflow.setContentDescription(LocaleController.getString(R.string.AccDescrMoreOptions));
        overflow.addSubItem(MENU_COPY, R.drawable.msg_copy, LocaleController.getString(R.string.XrayProxyCopyLogs));
        overflow.addSubItem(MENU_SHARE, R.drawable.msg_share, LocaleController.getString(R.string.ShareFile));
        overflow.addSubItem(MENU_CLEAR, R.drawable.msg_delete, LocaleController.getString(R.string.XrayProxyClearLogs));

        FrameLayout root = new FrameLayout(context);
        root.setBackgroundColor(Theme.getColor(Theme.key_windowBackgroundWhite));

        scrollView = new ScrollView(context);
        scrollView.setFillViewport(true);

        logView = new TextView(context);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        logView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        logView.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(16));
        logView.setTextIsSelectable(true);
        logView.setGravity(Gravity.TOP | Gravity.START);

        scrollView.addView(logView, LayoutHelper.createScroll(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0));
        root.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));

        fragmentView = root;
        refreshLogs();
        return fragmentView;
    }

    private void refreshLogs() {
        if (logView == null) {
            return;
        }
        ArrayList<String> logs = XrayAppProxyManager.getRecentLogs();
        if (logs == null || logs.isEmpty()) {
            logView.setText(LocaleController.getString(R.string.XrayProxyLogsEmpty));
            logView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
            return;
        }
        logView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < logs.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(logs.get(i));
        }
        logView.setText(sb.toString());
        if (scrollView != null) {
            scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
        }
    }

    private void copyAllLogs() {
        ArrayList<String> logs = XrayAppProxyManager.getRecentLogs();
        if (logs == null || logs.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < logs.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(logs.get(i));
        }
        AndroidUtilities.addToClipboard(sb.toString());
        BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
    }

    private void shareAllLogs() {
        ArrayList<String> logs = XrayAppProxyManager.getRecentLogs();
        if (logs == null || logs.isEmpty()) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < logs.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(logs.get(i));
        }
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_TEXT, sb.toString());
        intent.putExtra(Intent.EXTRA_SUBJECT, LocaleController.getString(R.string.XrayProxyLogs));
        if (getParentActivity() != null) {
            getParentActivity().startActivity(
                    Intent.createChooser(intent, LocaleController.getString(R.string.ShareFile)));
        }
    }

    private void clearLogs() {
        XrayAppProxyManager.clearRecentLogs();
        refreshLogs();
    }

    @Override
    public void onResume() {
        super.onResume();
        refreshLogs();
    }
}
