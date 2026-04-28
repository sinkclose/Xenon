package zxc.iconic.xenon.settings;

import android.content.SharedPreferences;
import android.text.TextUtils;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

import zxc.iconic.xenon.NekoConfig;
import zxc.iconic.xenon.proxy.XrayAppProxyManager;
import zxc.iconic.xenon.proxy.XrayConfigValidator;
import zxc.iconic.xenon.proxy.XrayLocalSocksAuth;
import zxc.iconic.xenon.proxy.XrayProxyProfileStore;

/**
 * Main dashboard for app-only Xray proxy with quick status/actions and navigation to sub-screens.
 */
public class NekoXrayProxyHubActivity extends BaseNekoSettingsActivity {

    private final int enabledRow = rowId++;
    private final int statusRow = rowId++;
    private final int activeProfileRow = rowId++;
    private final int socksAuthRow = rowId++;
    private final int resetSocksAuthRow = rowId++;

    private final int profilesRow = rowId++;
    private final int logsRow = rowId++;

    private final int delayCheckRow = rowId++;

    private boolean socksAuthRevealed;

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    private void resetLocalSocksAuth() {
        XrayLocalSocksAuth.resetCredentials();
        socksAuthRevealed = false;
        listView.adapter.update(true);

        if (!NekoConfig.xrayAppProxyEnabled) {
            AlertsCreator.showSimpleAlert(this,
                    LocaleController.getString(R.string.XrayProxySocksAuth),
                    LocaleController.getString(R.string.XrayProxySocksAuthResetDone));
            return;
        }

        if (!XrayAppProxyManager.isRunning()) {
            startProxyFlow();
            return;
        }

        XrayProxyProfileStore.Profile active = XrayProxyProfileStore.getActiveProfile();
        int localPort = active != null ? active.localPort : NekoConfig.xrayAppProxyLocalPort;
        XrayAppProxyManager.stop((success, message) -> AndroidUtilities.runOnUIThread(() -> {
            if (!success) {
                showError(message);
                return;
            }
            applyTelegramLocalProxy(false, localPort, null);
            startProxyFlow();
        }));
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        XrayProxyProfileStore.Profile active = XrayProxyProfileStore.getActiveProfile();
        boolean running = XrayAppProxyManager.isRunning();
        boolean canRun = hasUsableActiveProfile(active);
        XrayLocalSocksAuth.Credentials credentials = XrayLocalSocksAuth.getOrCreateCredentials();

        items.add(UItem.asHeader(LocaleController.getString(R.string.Connection)));
        items.add(UItem.asCheck(enabledRow, LocaleController.getString(R.string.XrayProxyEnable), LocaleController.getString(R.string.XrayProxyEnableDesc))
                .setChecked(NekoConfig.xrayAppProxyEnabled)
                .slug("xrayProxyEnabled"));
        UItem statusItem = UItem.asButton(statusRow,
                running ? R.drawable.msg_online : R.drawable.msg_disable,
                LocaleController.getString(R.string.XrayProxyStatus),
                running ? LocaleController.getString(R.string.XrayProxyStatusRunning) : LocaleController.getString(R.string.XrayProxyStatusStopped)).slug("xrayProxyStatus");
        if (running) {
            statusItem.accent();
        } else {
            statusItem.red();
        }
        statusItem.setEnabled(false);
        items.add(statusItem);

        String activeName = active == null ? LocaleController.getString(R.string.XrayProxyNoProfiles) : active.name;
        UItem activeItem = UItem.asButton(activeProfileRow, R.drawable.msg_settings,
                LocaleController.getString(R.string.XrayProxyActiveProfile),
                activeName).slug("xrayProxyActiveProfile");
        if (active != null) {
            activeItem.accent();
        }
        items.add(activeItem);

        items.add(UItem.asButton(socksAuthRow, R.drawable.msg_info,
                LocaleController.getString(R.string.XrayProxySocksAuth),
                socksAuthRevealed ? LocaleController.getString(R.string.XrayProxySocksAuthHide)
                                  : LocaleController.getString(R.string.XrayProxySocksAuthReveal))
                .slug("xrayProxySocksAuth"));
        if (socksAuthRevealed) {
            String authDetail = LocaleController.formatStringSimple(
                    LocaleController.getString(R.string.XrayProxySocksAuthValue),
                    credentials.username,
                    credentials.password
            );
            items.add(UItem.asShadow(authDetail));
        }
        items.add(UItem.asButton(resetSocksAuthRow, R.drawable.msg_delete,
                LocaleController.getString(R.string.XrayProxySocksAuthReset)).red().slug("xrayProxySocksAuthReset"));
        items.add(UItem.asShadow(LocaleController.getString(R.string.XrayProxyConnectionHint)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.XrayProxyManagementSection)));
        items.add(UItem.asButton(profilesRow, R.drawable.msg_settings,
                LocaleController.getString(R.string.XrayProxyProfiles),
                LocaleController.formatStringSimple(LocaleController.getString(R.string.XrayProxyProfilesCount),
                        String.valueOf(XrayProxyProfileStore.getProfiles().size()))).slug("xrayProxyProfiles"));
        items.add(UItem.asButton(logsRow, R.drawable.msg_log,
                LocaleController.getString(R.string.XrayProxyLogs),
                String.valueOf(XrayAppProxyManager.getRecentLogs().size())).slug("xrayProxyLogs"));
        items.add(UItem.asShadow(LocaleController.getString(R.string.XrayProxyManagementHint)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.XrayProxyActionsSection)));
        items.add(UItem.asButton(delayCheckRow, R.drawable.proxy_check,
                LocaleController.getString(R.string.XrayProxyDelayCheck))
                .setEnabled(canRun).slug("xrayProxyDelay"));
        items.add(UItem.asShadow(LocaleController.getString(R.string.XrayProxyRuntimeHint)));
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        int id = item.id;
        if (id == enabledRow) {
            NekoConfig.setXrayAppProxyEnabled(!NekoConfig.xrayAppProxyEnabled);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(NekoConfig.xrayAppProxyEnabled);
            }
            if (NekoConfig.xrayAppProxyEnabled) {
                startProxyFlow();
            } else {
                stopProxyFlow();
            }
            listView.adapter.update(true);
            return;
        }

        if (id == profilesRow) {
            presentFragment(new NekoXrayProxyProfilesActivity());
            return;
        }

        if (id == activeProfileRow) {
            XrayProxyProfileStore.Profile active = XrayProxyProfileStore.getActiveProfile();
            if (active != null) {
                presentFragment(NekoXrayProxyProfileEditActivity.forProfile(active.id));
            } else {
                presentFragment(new NekoXrayProxyProfilesActivity());
            }
            return;
        }

        if (id == socksAuthRow) {
            socksAuthRevealed = !socksAuthRevealed;
            listView.adapter.update(true);
            return;
        }

        if (id == resetSocksAuthRow) {
            resetLocalSocksAuth();
            return;
        }

        if (id == logsRow) {
            presentFragment(new NekoXrayProxyLogsActivity());
            return;
        }

        if (id == delayCheckRow) {
            runDelayCheck();
        }
    }

    private boolean hasUsableActiveProfile(XrayProxyProfileStore.Profile active) {
        if (active == null || TextUtils.isEmpty(active.configJson)) {
            return false;
        }
        return XrayConfigValidator.validate(active.configJson, active.localPort).valid;
    }

    /**
     * Starts core with active profile and applies localhost proxy to Telegram networking.
     */
    private void startProxyFlow() {
        XrayProxyProfileStore.Profile active = XrayProxyProfileStore.getActiveProfile();
        if (active == null) {
            NekoConfig.setXrayAppProxyEnabled(false);
            showError(LocaleController.getString(R.string.XrayProxyNoProfiles));
            listView.adapter.update(true);
            return;
        }

        if (!XrayAppProxyManager.isLibraryAvailable()) {
            NekoConfig.setXrayAppProxyEnabled(false);
            showError(LocaleController.getString(R.string.XrayProxyLibMissing));
            listView.adapter.update(true);
            return;
        }

        XrayLocalSocksAuth.Credentials credentials = XrayLocalSocksAuth.getOrCreateCredentials();
        String runtimeConfig;
        try {
            runtimeConfig = XrayLocalSocksAuth.applyCredentials(active.configJson, active.localPort, credentials);
        } catch (Throwable t) {
            NekoConfig.setXrayAppProxyEnabled(false);
            showError(LocaleController.getString(R.string.XrayProxyConfigApplyAuthError));
            listView.adapter.update(true);
            return;
        }

        XrayConfigValidator.ValidationResult result = XrayConfigValidator.validate(runtimeConfig, active.localPort);
        if (!result.valid) {
            NekoConfig.setXrayAppProxyEnabled(false);
            showError(result.message);
            listView.adapter.update(true);
            return;
        }

        XrayAppProxyManager.start(runtimeConfig, (success, message) -> AndroidUtilities.runOnUIThread(() -> {
            if (!success) {
                NekoConfig.setXrayAppProxyEnabled(false);
                showError(message);
                listView.adapter.update(true);
                return;
            }
            applyTelegramLocalProxy(true, active.localPort, credentials);
            listView.adapter.update(true);
        }));
    }

    /**
     * Stops core and disables local Telegram proxy if it was configured by this feature.
     */
    private void stopProxyFlow() {
        XrayProxyProfileStore.Profile active = XrayProxyProfileStore.getActiveProfile();
        int localPort = active != null ? active.localPort : NekoConfig.xrayAppProxyLocalPort;
        XrayAppProxyManager.stop((success, message) -> AndroidUtilities.runOnUIThread(() -> {
            if (!success) {
                showError(message);
                return;
            }
            applyTelegramLocalProxy(false, localPort, null);
            listView.adapter.update(true);
        }));
    }

    private void applyTelegramLocalProxy(boolean enabled, int localPort, XrayLocalSocksAuth.Credentials credentials) {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();

        if (enabled) {
            String proxyUser = credentials == null ? "" : credentials.username;
            String proxyPass = credentials == null ? "" : credentials.password;
            editor.putBoolean("proxy_enabled", true);
            editor.putString("proxy_ip", "127.0.0.1");
            editor.putInt("proxy_port", localPort);
            editor.putString("proxy_user", proxyUser);
            editor.putString("proxy_pass", proxyPass);
            editor.putString("proxy_secret", "");
            editor.apply();

            SharedConfig.currentProxy = new SharedConfig.ProxyInfo("127.0.0.1", localPort, proxyUser, proxyPass, "");
            ConnectionsManager.setProxySettings(true, "127.0.0.1", localPort, proxyUser, proxyPass, "");
        } else {
            boolean isOurProxy = "127.0.0.1".equals(preferences.getString("proxy_ip", ""))
                    && preferences.getInt("proxy_port", 0) == localPort;
            if (!isOurProxy) {
                return;
            }

            editor.putBoolean("proxy_enabled", false);
            editor.putString("proxy_ip", "");
            editor.putInt("proxy_port", 1080);
            editor.putString("proxy_user", "");
            editor.putString("proxy_pass", "");
            editor.putString("proxy_secret", "");
            editor.apply();

            ConnectionsManager.setProxySettings(false, "", 0, "", "", "");
            SharedConfig.currentProxy = null;
        }
    }

    private void runDelayCheck() {
        XrayProxyProfileStore.Profile active = XrayProxyProfileStore.getActiveProfile();
        if (active == null) {
            showError(LocaleController.getString(R.string.XrayProxyNoProfiles));
            return;
        }

        if (!XrayAppProxyManager.isLibraryAvailable()) {
            showError(LocaleController.getString(R.string.XrayProxyLibMissing));
            return;
        }

        XrayConfigValidator.ValidationResult result = XrayConfigValidator.validate(active.configJson, active.localPort);
        if (!result.valid) {
            showError(result.message);
            return;
        }

        XrayAppProxyManager.measureDelay(active.configJson, active.checkUrl, (success, delayMs, message) -> AndroidUtilities.runOnUIThread(() -> {
            if (!success) {
                showError(message);
                return;
            }
            AlertsCreator.showSimpleAlert(this,
                    LocaleController.getString(R.string.XrayProxyDelayCheck),
                    LocaleController.formatStringSimple(LocaleController.getString(R.string.XrayProxyDelayCheckResult), String.valueOf(delayMs)));
        }));
    }

    private void showError(String message) {
        AlertsCreator.showSimpleAlert(this, LocaleController.getString(R.string.ErrorOccurred), message);
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.XrayProxyTitle);
    }

    @Override
    protected String getKey() {
        return "xrayHub";
    }
}
