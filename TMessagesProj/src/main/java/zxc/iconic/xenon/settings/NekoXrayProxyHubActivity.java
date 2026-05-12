package zxc.iconic.xenon.settings;

import android.app.Activity;
import android.content.Context;
import android.text.TextUtils;
import android.view.View;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;
import java.util.List;

import zxc.iconic.xenon.NekoConfig;
import zxc.iconic.xenon.proxy.XrayAppProxyManager;
import zxc.iconic.xenon.proxy.XrayConfigValidator;
import zxc.iconic.xenon.proxy.XrayLocalSocksAuth;
import zxc.iconic.xenon.proxy.XrayProxyProfileStore;
import zxc.iconic.xenon.proxy.XrayTelegramProxyBridge;
import zxc.iconic.xenon.proxy.XrayUriConfigFactory;

/**
 * Dashboard for app-only Xray proxy — compact Telegram-style settings screen.
 * Connect/disconnect and active profile selection live on main list;
 * logs / advanced / about are in the ActionBar overflow menu.
 */
public class NekoXrayProxyHubActivity extends BaseNekoSettingsActivity {

    private static final int MENU_LOGS = 100;
    private static final int MENU_ADVANCED = 101;
    private static final int MENU_ABOUT = 102;

    private final int enabledRow = rowId++;
    private final int statusRow = rowId++;
    private final int activeProfileRow = rowId++;

    private final int profilesRow = rowId++;
    private final int delayCheckRow = rowId++;

    @Override
    public ActionBar createActionBar(Context context) {
        ActionBar actionBar = super.createActionBar(context);
        ActionBarMenu menu = actionBar.createMenu();
        ActionBarMenuItem overflow = menu.addItem(0, R.drawable.ic_ab_other);
        overflow.setContentDescription(LocaleController.getString(R.string.AccDescrMoreOptions));
        overflow.addSubItem(MENU_LOGS, R.drawable.msg_log, LocaleController.getString(R.string.XrayProxyLogs));
        overflow.addSubItem(MENU_ADVANCED, R.drawable.msg_settings, LocaleController.getString(R.string.XrayProxyAdvancedSection));
        overflow.addSubItem(MENU_ABOUT, R.drawable.msg_info, LocaleController.getString(R.string.XrayProxyAbout));
        actionBar.setActionBarMenuOnItemClick(new ActionBar.ActionBarMenuOnItemClick() {
            @Override
            public void onItemClick(int id) {
                if (id == -1) {
                    finishFragment();
                    return;
                }
                if (id == MENU_LOGS) {
                    presentFragment(new NekoXrayProxyLogsActivity());
                    return;
                }
                if (id == MENU_ADVANCED) {
                    presentFragment(new NekoXrayProxyAdvancedActivity());
                    return;
                }
                if (id == MENU_ABOUT) {
                    showAboutDialog();
                }
            }
        });
        return actionBar;
    }

    @Override
    public void onResume() {
        super.onResume();
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        XrayProxyProfileStore.Profile active = XrayProxyProfileStore.getActiveProfile();
        boolean running = XrayAppProxyManager.isRunning();
        boolean canRun = hasUsableActiveProfile(active);

        items.add(UItem.asHeader(LocaleController.getString(R.string.Connection)));
        items.add(UItem.asCheck(enabledRow,
                        LocaleController.getString(R.string.XrayProxyEnable),
                        LocaleController.getString(R.string.XrayProxyEnableDesc))
                .setChecked(NekoConfig.xrayAppProxyEnabled)
                .setEnabled(canRun || NekoConfig.xrayAppProxyEnabled)
                .slug("xrayProxyEnabled"));
        UItem statusItem = UItem.asButton(statusRow,
                running ? R.drawable.msg_online : R.drawable.msg_disable,
                LocaleController.getString(R.string.XrayProxyStatus),
                running ? LocaleController.getString(R.string.XrayProxyStatusRunning)
                        : LocaleController.getString(R.string.XrayProxyStatusStopped))
                .slug("xrayProxyStatus");
        if (running) {
            statusItem.accent();
        } else {
            statusItem.red();
        }
        statusItem.setEnabled(false);
        items.add(statusItem);

        String activeName = active == null
                ? LocaleController.getString(R.string.XrayProxyNoProfiles)
                : active.name;
        UItem activeItem = UItem.asButtonSubtext(activeProfileRow, R.drawable.msg_settings,
                LocaleController.getString(R.string.XrayProxyActiveProfile), activeName)
                .slug("xrayProxyActiveProfile");
        if (active != null) {
            activeItem.accent();
        }
        items.add(activeItem);
        items.add(UItem.asShadow(LocaleController.getString(R.string.XrayProxyConnectionHint)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.XrayProxyProfileSection)));
        items.add(UItem.asButton(profilesRow, R.drawable.msg_link2,
                LocaleController.getString(R.string.XrayProxyProfiles),
                LocaleController.formatStringSimple(
                        LocaleController.getString(R.string.XrayProxyProfilesCount),
                        String.valueOf(XrayProxyProfileStore.getProfiles().size())))
                .slug("xrayProxyProfiles"));
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

        if (id == delayCheckRow) {
            runDelayCheck();
        }
    }

    private boolean hasUsableActiveProfile(XrayProxyProfileStore.Profile active) {
        if (active == null || TextUtils.isEmpty(active.configJson)) {
            return false;
        }
        RuntimeConfig runtimeConfig;
        try {
            runtimeConfig = buildRuntimeConfig(active);
        } catch (Throwable t) {
            return false;
        }
        return XrayConfigValidator.validate(runtimeConfig.configJson, active.localPort).valid;
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

        RuntimeConfig runtimeConfig;
        try {
            runtimeConfig = buildRuntimeConfig(active);
        } catch (Throwable t) {
            NekoConfig.setXrayAppProxyEnabled(false);
            showError(LocaleController.getString(R.string.XrayProxyConfigApplyAuthError));
            listView.adapter.update(true);
            return;
        }

        XrayConfigValidator.ValidationResult result = XrayConfigValidator.validate(runtimeConfig.configJson, active.localPort);
        if (!result.valid) {
            NekoConfig.setXrayAppProxyEnabled(false);
            showError(result.message);
            listView.adapter.update(true);
            return;
        }

        XrayAppProxyManager.start(runtimeConfig.configJson, (success, message) -> AndroidUtilities.runOnUIThread(() -> {
            if (!success) {
                NekoConfig.setXrayAppProxyEnabled(false);
                showError(message);
                listView.adapter.update(true);
                return;
            }
            NekoConfig.setXrayAppProxyEnabled(true);
            XrayTelegramProxyBridge.enableLocalProxy(active.localPort, runtimeConfig.credentials);
            listView.adapter.update(true);
        }));
    }

    /**
     * Stops core and disables local Telegram proxy if it was configured by this feature.
     */
    private void stopProxyFlow() {
        XrayAppProxyManager.stop((success, message) -> AndroidUtilities.runOnUIThread(() -> {
            if (!success) {
                showError(message);
                return;
            }
            XrayTelegramProxyBridge.disableLocalProxyIfOwned();
            listView.adapter.update(true);
        }));
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

        RuntimeConfig runtimeConfig;
        try {
            runtimeConfig = buildRuntimeConfig(active);
        } catch (Throwable t) {
            showError(LocaleController.getString(R.string.XrayProxyConfigApplyAuthError));
            return;
        }

        XrayConfigValidator.ValidationResult result = XrayConfigValidator.validate(runtimeConfig.configJson, active.localPort);
        if (!result.valid) {
            showError(result.message);
            return;
        }

        String checkUrl = TextUtils.isEmpty(active.checkUrl)
                ? XrayProxyProfileStore.DEFAULT_CHECK_URL
                : active.checkUrl.trim();

        XrayAppProxyManager.measureDelay(runtimeConfig.configJson, checkUrl, (success, delayMs, message) -> AndroidUtilities.runOnUIThread(() -> {
            if (!success) {
                showError(message);
                return;
            }
            AlertsCreator.showSimpleAlert(this,
                    LocaleController.getString(R.string.XrayProxyDelayCheck),
                    LocaleController.formatStringSimple(
                            LocaleController.getString(R.string.XrayProxyDelayCheckResult),
                            String.valueOf(delayMs)));
        }));
    }

    /**
     * Builds runtime config by injecting current local SOCKS credentials into selected profile.
     */
    private RuntimeConfig buildRuntimeConfig(XrayProxyProfileStore.Profile active) throws Exception {
        XrayLocalSocksAuth.Credentials credentials = XrayLocalSocksAuth.getOrCreateCredentials();
        String configJson = XrayLocalSocksAuth.applyCredentials(active.configJson, active.localPort, credentials);
        return new RuntimeConfig(configJson, credentials);
    }

    private static final class RuntimeConfig {
        final String configJson;
        final XrayLocalSocksAuth.Credentials credentials;

        RuntimeConfig(String configJson, XrayLocalSocksAuth.Credentials credentials) {
            this.configJson = configJson;
            this.credentials = credentials;
        }
    }

    /**
     * Shows an informational Telegram-style alert listing share-URI protocols
     * supported by {@link XrayUriConfigFactory}. The protocol list is pulled
     * from the factory, not hardcoded in the UI layer.
     */
    private void showAboutDialog() {
        Activity context = getParentActivity();
        if (context == null) {
            return;
        }

        List<XrayUriConfigFactory.ProtocolInfo> protocols = XrayUriConfigFactory.getSupportedProtocols();

        StringBuilder message = new StringBuilder();
        message.append(LocaleController.getString(R.string.XrayProxyAboutSummary));
        if (!protocols.isEmpty()) {
            message.append("\n\n");
            message.append(LocaleController.getString(R.string.XrayProxyAboutSupportedProtocols));
            String rowTemplate = LocaleController.getString(R.string.XrayProxyAboutProtocolRow);
            for (XrayUriConfigFactory.ProtocolInfo info : protocols) {
                StringBuilder schemes = new StringBuilder();
                for (String scheme : info.uriSchemes) {
                    if (schemes.length() > 0) {
                        schemes.append(", ");
                    }
                    schemes.append(scheme).append("://");
                }
                message.append('\n');
                message.append(LocaleController.formatStringSimple(rowTemplate, info.displayName, schemes.toString()));
            }
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(LocaleController.getString(R.string.XrayProxyAboutTitle));
        builder.setMessage(message.toString());
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        showDialog(builder.create());
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
