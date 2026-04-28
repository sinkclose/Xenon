package zxc.iconic.xenon.settings;

import android.text.TextUtils;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

import zxc.iconic.xenon.proxy.XrayAppProxyManager;

/**
 * Read-only screen with recent Xray core lifecycle logs.
 */
public class NekoXrayProxyLogsActivity extends BaseNekoSettingsActivity {

    private final int copyAllRow = rowId++;
    private final int clearRow = rowId++;
    private final int logStartRow = 100;

    private final ArrayList<String> logs = new ArrayList<>();

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        logs.clear();
        logs.addAll(XrayAppProxyManager.getRecentLogs());

        items.add(UItem.asHeader(LocaleController.getString(R.string.XrayProxyLogs)));
        items.add(UItem.asButton(copyAllRow, R.drawable.msg_copy,
                LocaleController.getString(R.string.XrayProxyCopyLogs)).accent().slug("xrayProxyCopyLogs"));
        items.add(UItem.asButton(clearRow, R.drawable.msg_delete,
                LocaleController.getString(R.string.XrayProxyClearLogs)).red().slug("xrayProxyClearLogs"));

        if (logs.isEmpty()) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.XrayProxyLogsEmpty)));
            return;
        }

        items.add(UItem.asHeader(LocaleController.getString(R.string.XrayProxyLogsRecent)));
        for (int i = logs.size() - 1, row = 0; i >= 0; i--, row++) {
            String line = logs.get(i);
            String title = line;
            String subtitle = "";
            if (!TextUtils.isEmpty(line) && line.length() > 10 && line.charAt(8) == ' ') {
                title = line.substring(0, 8);
                subtitle = line.substring(10);
            }
            items.add(TextDetailSettingsCellFactory.of(logStartRow + row, title, subtitle).slug("xrayProxyLogLine" + row));
        }
        items.add(UItem.asShadow(LocaleController.getString(R.string.XrayProxyLogsHint)));
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;
        if (id == copyAllRow) {
            ArrayList<String> copyLogs = XrayAppProxyManager.getRecentLogs();
            StringBuilder builder = new StringBuilder();
            for (int i = 0; i < copyLogs.size(); i++) {
                if (i > 0) {
                    builder.append('\n');
                }
                builder.append(copyLogs.get(i));
            }
            AndroidUtilities.addToClipboard(builder.toString());
            return;
        }

        if (id == clearRow) {
            XrayAppProxyManager.clearRecentLogs();
            listView.adapter.update(true);
            return;
        }

        if (id >= logStartRow) {
            int index = id - logStartRow;
            int reverseIndex = logs.size() - 1 - index;
            if (reverseIndex >= 0 && reverseIndex < logs.size()) {
                AndroidUtilities.addToClipboard(logs.get(reverseIndex));
            }
        }
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.XrayProxyLogs);
    }

    @Override
    protected String getKey() {
        return "xrayLogs";
    }
}
