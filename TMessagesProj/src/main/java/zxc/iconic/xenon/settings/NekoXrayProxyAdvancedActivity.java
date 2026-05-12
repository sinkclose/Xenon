package zxc.iconic.xenon.settings;

import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.BulletinFactory;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

import zxc.iconic.xenon.NekoConfig;
import zxc.iconic.xenon.proxy.XrayAppProxyManager;
import zxc.iconic.xenon.proxy.XrayLocalSocksAuth;
import zxc.iconic.xenon.proxy.XrayProxyProfileStore;
import zxc.iconic.xenon.proxy.XrayTelegramProxyBridge;

/**
 * Advanced/power-user options for Xray app proxy: reveal and copy SOCKS credentials,
 * regenerate them, inspect local endpoint. Kept out of the main Hub to avoid UI noise.
 */
public class NekoXrayProxyAdvancedActivity extends BaseNekoSettingsActivity {

    private final int revealRow = rowId++;
    private final int usernameRow = rowId++;
    private final int passwordRow = rowId++;
    private final int resetRow = rowId++;

    private final int endpointRow = rowId++;

    private boolean credentialsRevealed;

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        XrayLocalSocksAuth.Credentials credentials = XrayLocalSocksAuth.getOrCreateCredentials();
        XrayProxyProfileStore.Profile active = XrayProxyProfileStore.getActiveProfile();

        items.add(UItem.asHeader(LocaleController.getString(R.string.XrayProxySocksAuth)));
        items.add(UItem.asCheck(revealRow,
                        LocaleController.getString(R.string.XrayProxySocksAuthReveal))
                .setChecked(credentialsRevealed)
                .slug("xrayAdvRevealAuth"));
        items.add(UItem.asButtonSubtext(usernameRow, R.drawable.msg_copy,
                        LocaleController.getString(R.string.XrayProxySocksAuthUsername),
                        credentialsRevealed ? credentials.username : maskedValue(credentials.username))
                .slug("xrayAdvUsername"));
        items.add(UItem.asButtonSubtext(passwordRow, R.drawable.msg_copy,
                        LocaleController.getString(R.string.XrayProxySocksAuthPassword),
                        credentialsRevealed ? credentials.password : maskedValue(credentials.password))
                .slug("xrayAdvPassword"));
        items.add(UItem.asButton(resetRow, R.drawable.msg_reset,
                        LocaleController.getString(R.string.XrayProxySocksAuthReset))
                .red().slug("xrayAdvReset"));
        items.add(UItem.asShadow(LocaleController.getString(R.string.XrayProxySocksAuthHint)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.XrayProxyEndpointSection)));
        String endpointValue;
        if (active == null) {
            endpointValue = LocaleController.getString(R.string.XrayProxyNoProfiles);
        } else {
            endpointValue = LocaleController.formatStringSimple(
                    LocaleController.getString(R.string.XrayProxyLocalEndpointFormat),
                    String.valueOf(active.localPort));
        }
        items.add(UItem.asButton(endpointRow, R.drawable.msg_copy,
                        LocaleController.getString(R.string.XrayProxyLocalEndpoint), endpointValue)
                .setEnabled(active != null).slug("xrayAdvEndpoint"));
        items.add(UItem.asShadow(LocaleController.getString(R.string.XrayProxyLocalEndpointHint)));
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;
        if (id == revealRow) {
            credentialsRevealed = !credentialsRevealed;
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(credentialsRevealed);
            }
            listView.adapter.update(true);
            return;
        }

        if (id == usernameRow) {
            XrayLocalSocksAuth.Credentials credentials = XrayLocalSocksAuth.getOrCreateCredentials();
            copyToClipboard(credentials.username);
            return;
        }

        if (id == passwordRow) {
            XrayLocalSocksAuth.Credentials credentials = XrayLocalSocksAuth.getOrCreateCredentials();
            copyToClipboard(credentials.password);
            return;
        }

        if (id == resetRow) {
            resetLocalSocksAuth();
            return;
        }

        if (id == endpointRow) {
            XrayProxyProfileStore.Profile active = XrayProxyProfileStore.getActiveProfile();
            if (active != null) {
                copyToClipboard("127.0.0.1:" + active.localPort);
            }
        }
    }

    /**
     * Regenerates SOCKS credentials and restarts the proxy if it is currently running,
     * so the new auth is applied to the live Telegram connection.
     */
    private void resetLocalSocksAuth() {
        XrayLocalSocksAuth.resetCredentials();
        credentialsRevealed = false;
        listView.adapter.update(true);

        if (!NekoConfig.xrayAppProxyEnabled) {
            AlertsCreator.showSimpleAlert(this,
                    LocaleController.getString(R.string.XrayProxySocksAuth),
                    LocaleController.getString(R.string.XrayProxySocksAuthResetDone));
            return;
        }

        if (!XrayAppProxyManager.isRunning()) {
            AlertsCreator.showSimpleAlert(this,
                    LocaleController.getString(R.string.XrayProxySocksAuth),
                    LocaleController.getString(R.string.XrayProxySocksAuthResetDone));
            return;
        }

        XrayAppProxyManager.stop((success, message) -> AndroidUtilities.runOnUIThread(() -> {
            if (!success) {
                showError(message);
                return;
            }
            XrayTelegramProxyBridge.disableLocalProxyIfOwned();
            AlertsCreator.showSimpleAlert(NekoXrayProxyAdvancedActivity.this,
                    LocaleController.getString(R.string.XrayProxySocksAuth),
                    LocaleController.getString(R.string.XrayProxySocksAuthResetDone));
        }));
    }

    private void copyToClipboard(String value) {
        AndroidUtilities.addToClipboard(value);
        BulletinFactory.of(this).createCopyBulletin(LocaleController.getString(R.string.TextCopied)).show();
    }

    private String maskedValue(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            sb.append('•');
        }
        return sb.toString();
    }

    private void showError(String message) {
        AlertsCreator.showSimpleAlert(this, LocaleController.getString(R.string.ErrorOccurred), message);
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.XrayProxyAdvancedTitle);
    }

    @Override
    protected String getKey() {
        return "xrayAdvanced";
    }
}
