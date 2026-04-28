package zxc.iconic.xenon.settings;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.NotificationCenter;
import org.telegram.messenger.R;
import org.telegram.messenger.SharedConfig;
import org.telegram.tgnet.ConnectionsManager;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;

import zxc.iconic.xenon.NekoConfig;
import zxc.iconic.xenon.proxy.XrayAppProxyManager;
import zxc.iconic.xenon.proxy.XrayConfigValidator;
import zxc.iconic.xenon.proxy.XrayLocalSocksAuth;
import zxc.iconic.xenon.proxy.XrayUriConfigFactory;

public class NekoXrayProxySettingsActivity extends BaseNekoSettingsActivity {

    private final int enabledRow = rowId++;
    private final int statusRow = rowId++;
    private final int endpointRow = rowId++;
    private final int importClipboardRow = rowId++;
    private final int importUriRow = rowId++;
    private final int localPortRow = rowId++;
    private final int checkUrlRow = rowId++;
    private final int configRow = rowId++;
    private final int delayCheckRow = rowId++;
    private final int startRow = rowId++;
    private final int stopRow = rowId++;

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        boolean hasConfig = !TextUtils.isEmpty(NekoConfig.xrayAppProxyConfigJson);
        boolean canRunWithConfig = hasUsableConfig();
        boolean running = XrayAppProxyManager.isRunning();

        items.add(UItem.asHeader(LocaleController.getString(R.string.Connection)));
        items.add(UItem.asCheck(enabledRow, LocaleController.getString(R.string.XrayProxyEnable), LocaleController.getString(R.string.XrayProxyEnableDesc))
                .slug("xrayProxyEnabled").setChecked(NekoConfig.xrayAppProxyEnabled));
        items.add(TextSettingsCellFactory.of(statusRow, LocaleController.getString(R.string.XrayProxyStatus),
                running ? LocaleController.getString(R.string.XrayProxyStatusRunning) : LocaleController.getString(R.string.XrayProxyStatusStopped)).slug("xrayProxyStatus"));

        items.add(TextSettingsCellFactory.of(endpointRow, LocaleController.getString(R.string.XrayProxyEndpoint), getEndpointSummary()).slug("xrayProxyEndpoint"));
        items.add(UItem.asShadow(LocaleController.getString(R.string.XrayProxyConnectionHint)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.XrayProxyImportSection)));
        items.add(TextSettingsCellFactory.of(importClipboardRow, LocaleController.getString(R.string.XrayProxyImportClipboard), "").accent().slug("xrayProxyImportClipboard"));
        items.add(TextSettingsCellFactory.of(importUriRow, LocaleController.getString(R.string.XrayProxyImportUri), "").slug("xrayProxyImportUri"));
        items.add(UItem.asShadow(LocaleController.getString(R.string.XrayProxyImportHint)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.XrayProxyRuntimeSection)));
        items.add(TextSettingsCellFactory.of(localPortRow, LocaleController.getString(R.string.XrayProxyLocalPort), String.valueOf(NekoConfig.xrayAppProxyLocalPort)).slug("xrayProxyPort"));
        items.add(TextSettingsCellFactory.of(checkUrlRow, LocaleController.getString(R.string.XrayProxyCheckUrl), NekoConfig.xrayAppProxyCheckUrl).slug("xrayProxyCheckUrl"));
        items.add(TextSettingsCellFactory.of(configRow, LocaleController.getString(R.string.XrayProxyConfig),
                hasConfig ? LocaleController.getString(R.string.XrayProxyConfigReady) : LocaleController.getString(R.string.XrayProxyConfigEmpty)).slug("xrayProxyConfig"));
        items.add(TextSettingsCellFactory.of(delayCheckRow, LocaleController.getString(R.string.XrayProxyDelayCheck), "").slug("xrayProxyDelayCheck").setEnabled(canRunWithConfig));
        items.add(UItem.asShadow(LocaleController.getString(R.string.XrayProxyRuntimeHint)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.XrayProxyActionsSection)));
        items.add(TextSettingsCellFactory.of(startRow, LocaleController.getString(R.string.XrayProxyStartNow), "").accent().slug("xrayProxyStart").setEnabled(canRunWithConfig && !running));
        items.add(TextSettingsCellFactory.of(stopRow, LocaleController.getString(R.string.XrayProxyStopNow), "").red().slug("xrayProxyStop").setEnabled(running || NekoConfig.xrayAppProxyEnabled));
        items.add(UItem.asShadow(LocaleController.getString(R.string.XrayProxyExperimentalHintCompact)));
    }

    private boolean hasUsableConfig() {
        if (TextUtils.isEmpty(NekoConfig.xrayAppProxyConfigJson)) {
            return false;
        }
        RuntimeConfig runtimeConfig;
        try {
            runtimeConfig = buildRuntimeConfig();
        } catch (Throwable t) {
            return false;
        }
        return XrayConfigValidator.validate(runtimeConfig.configJson, NekoConfig.xrayAppProxyLocalPort).valid;
    }

    /**
     * Imports first supported proxy URI from clipboard and converts it to Xray JSON config.
     * Edge cases handled: empty clipboard, plain text, base64 subscriptions and malformed links.
     */
    private void importFromClipboard() {
        String clipText = readClipboardText();
        XrayUriConfigFactory.ParseResult result = XrayUriConfigFactory.fromClipboardText(clipText, NekoConfig.xrayAppProxyLocalPort);
        if (!result.valid) {
            showError(result.message);
            return;
        }
        applyImportedConfig(result);
    }

    private void showUriImportDialog() {
        Activity context = getParentActivity();
        if (context == null) {
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(LocaleController.getString(R.string.XrayProxyImportUri));

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);

        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(getThemedColor(org.telegram.ui.ActionBar.Theme.key_dialogTextBlack));
        editText.setHintText(LocaleController.getString(R.string.XrayProxyImportUriHint));
        editText.setHintColor(getThemedColor(org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhiteHintText));
        editText.setSingleLine(false);
        editText.setMinLines(3);
        editText.setMaxLines(8);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editText.setBackground(null);
        editText.setPadding(0, 0, 0, 0);
        container.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 24, 0, 24, 0));

        builder.setView(container);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);

        View positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positive != null) {
            positive.setOnClickListener(v -> {
                String value = editText.getText() == null ? "" : editText.getText().toString().trim();
                XrayUriConfigFactory.ParseResult result = XrayUriConfigFactory.fromLink(value, NekoConfig.xrayAppProxyLocalPort);
                if (!result.valid) {
                    showError(result.message);
                    return;
                }
                applyImportedConfig(result);
                dialog.dismiss();
            });
        }
    }

    private void applyImportedConfig(XrayUriConfigFactory.ParseResult result) {
        if (result.config == null) {
            showError(LocaleController.getString(R.string.XrayProxyErrorImportFailed));
            return;
        }

        String json;
        try {
            json = result.config.toString(2);
        } catch (Throwable ignore) {
            json = result.config.toString();
        }

        XrayConfigValidator.ValidationResult validation = XrayConfigValidator.validate(json, NekoConfig.xrayAppProxyLocalPort);
        if (!validation.valid) {
            showError(validation.message);
            return;
        }

        NekoConfig.setXrayAppProxyConfigJson(json);

        listView.adapter.update(true);
        AlertsCreator.showSimpleAlert(
                this,
                LocaleController.getString(R.string.XrayProxyImportDoneTitle),
                LocaleController.formatStringSimple(
                        LocaleController.getString(R.string.XrayProxyImportDoneText),
                        result.protocol,
                        result.host,
                        String.valueOf(result.port)
                )
        );
    }

    private String readClipboardText() {
        try {
            Activity activity = getParentActivity();
            if (activity == null) {
                return "";
            }
            ClipboardManager manager = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (manager == null) {
                return "";
            }
            ClipData clipData = manager.getPrimaryClip();
            if (clipData == null || clipData.getItemCount() == 0) {
                return "";
            }
            CharSequence text = clipData.getItemAt(0).coerceToText(activity);
            return text == null ? "" : text.toString();
        } catch (Throwable ignore) {
            return "";
        }
    }

    private String getEndpointSummary() {
        if (TextUtils.isEmpty(NekoConfig.xrayAppProxyConfigJson)) {
            return LocaleController.getString(R.string.XrayProxyConfigEmpty);
        }
        try {
            JSONObject root = new JSONObject(NekoConfig.xrayAppProxyConfigJson);
            JSONArray outbounds = root.optJSONArray("outbounds");
            if (outbounds == null || outbounds.length() == 0) {
                return LocaleController.getString(R.string.XrayProxyConfigReady);
            }
            JSONObject outbound = outbounds.optJSONObject(0);
            if (outbound == null) {
                return LocaleController.getString(R.string.XrayProxyConfigReady);
            }
            String protocol = outbound.optString("protocol", "proxy");
            JSONObject settings = outbound.optJSONObject("settings");
            String host = "";
            int port = 0;

            if (settings != null) {
                JSONArray vnext = settings.optJSONArray("vnext");
                if (vnext != null && vnext.length() > 0) {
                    JSONObject server = vnext.optJSONObject(0);
                    if (server != null) {
                        host = server.optString("address", "");
                        port = server.optInt("port", 0);
                    }
                } else {
                    JSONArray servers = settings.optJSONArray("servers");
                    if (servers != null && servers.length() > 0) {
                        JSONObject server = servers.optJSONObject(0);
                        if (server != null) {
                            host = server.optString("address", "");
                            port = server.optInt("port", 0);
                        }
                    }
                }
            }

            if (TextUtils.isEmpty(host) || port <= 0) {
                return protocol;
            }
            return protocol + " • " + host + ":" + port;
        } catch (Throwable ignore) {
            return LocaleController.getString(R.string.XrayProxyConfigReady);
        }
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
        } else if (id == importClipboardRow) {
            importFromClipboard();
        } else if (id == importUriRow) {
            showUriImportDialog();
        } else if (id == localPortRow) {
            showSingleFieldDialog(LocaleController.getString(R.string.XrayProxyLocalPort), String.valueOf(NekoConfig.xrayAppProxyLocalPort), InputType.TYPE_CLASS_NUMBER, value -> {
                int parsed;
                try {
                    parsed = Integer.parseInt(value.trim());
                } catch (Throwable t) {
                    showError(LocaleController.getString(R.string.XrayProxyErrorInvalidPort));
                    return false;
                }
                if (parsed < 1024 || parsed > 65535) {
                    showError(LocaleController.getString(R.string.XrayProxyErrorPortRange));
                    return false;
                }
                NekoConfig.setXrayAppProxyLocalPort(parsed);
                listView.adapter.update(true);
                return true;
            });
        } else if (id == checkUrlRow) {
            showSingleFieldDialog(LocaleController.getString(R.string.XrayProxyCheckUrl), NekoConfig.xrayAppProxyCheckUrl, InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI, value -> {
                if (TextUtils.isEmpty(value)) {
                    showError(LocaleController.getString(R.string.XrayProxyErrorUrlEmpty));
                    return false;
                }
                NekoConfig.setXrayAppProxyCheckUrl(value.trim());
                listView.adapter.update(true);
                return true;
            });
        } else if (id == configRow) {
            showMultiLineConfigDialog();
        } else if (id == delayCheckRow) {
            runDelayCheck();
        } else if (id == startRow) {
            NekoConfig.setXrayAppProxyEnabled(true);
            startProxyFlow();
            listView.adapter.update(true);
        } else if (id == stopRow) {
            NekoConfig.setXrayAppProxyEnabled(false);
            stopProxyFlow();
            listView.adapter.update(true);
        }
    }

    /**
     * Validates config and starts embedded local proxy, then points Telegram networking to localhost.
     */
    private void startProxyFlow() {
        if (!XrayAppProxyManager.isLibraryAvailable()) {
            NekoConfig.setXrayAppProxyEnabled(false);
            showError(LocaleController.getString(R.string.XrayProxyLibMissing));
            listView.adapter.update(true);
            return;
        }

        RuntimeConfig runtimeConfig;
        try {
            runtimeConfig = buildRuntimeConfig();
        } catch (Throwable t) {
            NekoConfig.setXrayAppProxyEnabled(false);
            showError(LocaleController.getString(R.string.XrayProxyConfigApplyAuthError));
            listView.adapter.update(true);
            return;
        }

        XrayConfigValidator.ValidationResult result = XrayConfigValidator.validate(runtimeConfig.configJson, NekoConfig.xrayAppProxyLocalPort);
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
            applyTelegramLocalProxy(true, runtimeConfig.credentials);
            listView.adapter.update(true);
        }));
    }

    /**
     * Stops embedded local proxy and disables localhost proxy in Telegram if it was active.
     */
    private void stopProxyFlow() {
        XrayAppProxyManager.stop((success, message) -> AndroidUtilities.runOnUIThread(() -> {
            if (!success) {
                showError(message);
                return;
            }
            applyTelegramLocalProxy(false, null);
            listView.adapter.update(true);
        }));
    }

    private void applyTelegramLocalProxy(boolean enabled, XrayLocalSocksAuth.Credentials credentials) {
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        SharedPreferences.Editor editor = preferences.edit();

        if (enabled) {
            String proxyUser = credentials == null ? "" : credentials.username;
            String proxyPass = credentials == null ? "" : credentials.password;
            editor.putBoolean("proxy_enabled", true);
            editor.putString("proxy_ip", "127.0.0.1");
            editor.putInt("proxy_port", NekoConfig.xrayAppProxyLocalPort);
            editor.putString("proxy_user", proxyUser);
            editor.putString("proxy_pass", proxyPass);
            editor.putString("proxy_secret", "");
            editor.apply();

            SharedConfig.currentProxy = new SharedConfig.ProxyInfo("127.0.0.1", NekoConfig.xrayAppProxyLocalPort, proxyUser, proxyPass, "");
            ConnectionsManager.setProxySettings(true, "127.0.0.1", NekoConfig.xrayAppProxyLocalPort, proxyUser, proxyPass, "");
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        } else {
            boolean isOurProxy = "127.0.0.1".equals(preferences.getString("proxy_ip", ""))
                    && preferences.getInt("proxy_port", 0) == NekoConfig.xrayAppProxyLocalPort;
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
            NotificationCenter.getGlobalInstance().postNotificationName(NotificationCenter.proxySettingsChanged);
        }
    }

    private void showMultiLineConfigDialog() {
        var context = getParentActivity();
        if (context == null) {
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(LocaleController.getString(R.string.XrayProxyConfig));

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);

        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(getThemedColor(org.telegram.ui.ActionBar.Theme.key_dialogTextBlack));
        editText.setHintText(LocaleController.getString(R.string.XrayProxyConfigHint));
        editText.setHintColor(getThemedColor(org.telegram.ui.ActionBar.Theme.key_windowBackgroundWhiteHintText));
        editText.setSingleLine(false);
        editText.setMinLines(8);
        editText.setMaxLines(16);
        editText.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        editText.setText(NekoConfig.xrayAppProxyConfigJson);
        editText.setBackground(null);
        editText.setPadding(0, 0, 0, 0);
        container.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 24, 0, 24, 0));

        builder.setView(container);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);

        View positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positive != null) {
            positive.setOnClickListener(v -> {
                String value = editText.getText() == null ? "" : editText.getText().toString().trim();
                if (TextUtils.isEmpty(value)) {
                    showError(LocaleController.getString(R.string.XrayProxyConfigEmpty));
                    return;
                }
                NekoConfig.setXrayAppProxyConfigJson(value);
                dialog.dismiss();
                listView.adapter.update(true);
            });
        }
    }

    private void showSingleFieldDialog(String title, String value, int inputType, ValueCommit callback) {
        Activity context = getParentActivity();
        if (context == null) {
            return;
        }

        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(title);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);

        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 18);
        editText.setTextColor(getThemedColor(org.telegram.ui.ActionBar.Theme.key_dialogTextBlack));
        editText.setSingleLine(true);
        editText.setInputType(inputType);
        editText.setText(value);
        editText.setBackground(null);
        editText.setPadding(0, 0, 0, 0);
        container.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 36, 0, 24, 0, 24, 0));

        builder.setView(container);
        builder.setNegativeButton(LocaleController.getString(R.string.Cancel), null);
        builder.setPositiveButton(LocaleController.getString(R.string.OK), null);
        AlertDialog dialog = builder.create();
        showDialog(dialog);

        View positive = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        if (positive != null) {
            positive.setOnClickListener(v -> {
                String newValue = editText.getText() == null ? "" : editText.getText().toString();
                if (callback.commit(newValue)) {
                    dialog.dismiss();
                }
            });
        }
    }

    private void showError(String message) {
        AlertsCreator.showSimpleAlert(this, LocaleController.getString(R.string.ErrorOccurred), message);
    }

    private void runDelayCheck() {
        if (!XrayAppProxyManager.isLibraryAvailable()) {
            showError(LocaleController.getString(R.string.XrayProxyLibMissing));
            return;
        }

        RuntimeConfig runtimeConfig;
        try {
            runtimeConfig = buildRuntimeConfig();
        } catch (Throwable t) {
            showError(LocaleController.getString(R.string.XrayProxyConfigApplyAuthError));
            return;
        }

        XrayConfigValidator.ValidationResult result = XrayConfigValidator.validate(runtimeConfig.configJson, NekoConfig.xrayAppProxyLocalPort);
        if (!result.valid) {
            showError(result.message);
            return;
        }

        String checkUrl = TextUtils.isEmpty(NekoConfig.xrayAppProxyCheckUrl)
                ? "https://www.gstatic.com/generate_204"
                : NekoConfig.xrayAppProxyCheckUrl.trim();

        XrayAppProxyManager.measureDelay(runtimeConfig.configJson, checkUrl, (success, delayMs, message) -> AndroidUtilities.runOnUIThread(() -> {
            if (!success) {
                showError(message);
                return;
            }
            AlertsCreator.showSimpleAlert(this, LocaleController.getString(R.string.XrayProxyDelayCheck), LocaleController.formatStringSimple(LocaleController.getString(R.string.XrayProxyDelayCheckResult), String.valueOf(delayMs)));
        }));
    }

    /**
     * Builds runtime config by injecting current local SOCKS credentials into active single-profile config.
     */
    private RuntimeConfig buildRuntimeConfig() throws Exception {
        XrayLocalSocksAuth.Credentials credentials = XrayLocalSocksAuth.getOrCreateCredentials();
        String configJson = XrayLocalSocksAuth.applyCredentials(NekoConfig.xrayAppProxyConfigJson, NekoConfig.xrayAppProxyLocalPort, credentials);
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

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.XrayProxyTitle);
    }

    @Override
    protected String getKey() {
        return "xray";
    }

    private interface ValueCommit {
        boolean commit(String value);
    }
}
