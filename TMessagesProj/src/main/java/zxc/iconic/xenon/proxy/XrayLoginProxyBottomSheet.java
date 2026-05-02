package zxc.iconic.xenon.proxy;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import zxc.iconic.xenon.NekoConfig;

/**
 * Compact login-time controller for Xray app-only proxy.
 * Accepts URI input, starts/stops local Xray core and synchronizes Telegram proxy state.
 */
public class XrayLoginProxyBottomSheet extends BottomSheet {

    private static final String TAG = "XrayLoginProxyBottomSheet";

    private final EditText uriField;
    private final TextView statusText;
    private final TextView startButton;
    private final TextView stopButton;
    private final TextView delayButton;
    private final TextView pasteButton;

    /**
     * Creates compact proxy controls that can be used before authentication.
     */
    public XrayLoginProxyBottomSheet(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, false, resourcesProvider);
        fixNavigationBar();
        setTitle(LocaleController.getString(R.string.XrayProxyLoginQuickTitle), true);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(6), AndroidUtilities.dp(16), AndroidUtilities.dp(12));

        TextView hintText = new TextView(context);
        hintText.setText(LocaleController.getString(R.string.XrayProxyLoginQuickHint));
        hintText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        hintText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        hintText.setLineSpacing(AndroidUtilities.dp(2), 1.0f);
        hintText.setGravity(Gravity.START);
        container.addView(hintText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        TextView uriLabel = new TextView(context);
        uriLabel.setText(LocaleController.getString(R.string.XrayProxyLoginUriLabel));
        uriLabel.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        uriLabel.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText2));
        uriLabel.setGravity(Gravity.START);
        container.addView(uriLabel, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 4));

        uriField = new EditText(context);
        uriField.setHint(LocaleController.getString(R.string.XrayProxyLoginUriHint));
        uriField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText));
        uriField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText));
        uriField.setBackgroundDrawable(Theme.createEditTextDrawable(context, false));
        uriField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        uriField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
        uriField.setSingleLine(true);
        uriField.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        uriField.setPadding(AndroidUtilities.dp(10), AndroidUtilities.dp(8), AndroidUtilities.dp(10), AndroidUtilities.dp(8));
        container.addView(uriField, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        pasteButton = createSecondaryButton(context, LocaleController.getString(R.string.XrayProxyLoginPaste));
        pasteButton.setOnClickListener(v -> pasteFromClipboard());
        container.addView(pasteButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 42, 0, 8, 0, 0));

        startButton = createPrimaryButton(context, LocaleController.getString(R.string.XrayProxyLoginConnect));
        startButton.setOnClickListener(v -> startProxy());
        container.addView(startButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 42, 0, 8, 0, 0));

        LinearLayout actionsRow = new LinearLayout(context);
        actionsRow.setOrientation(LinearLayout.HORIZONTAL);
        actionsRow.setGravity(Gravity.CENTER_VERTICAL);
        container.addView(actionsRow, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 40, 0, 8, 0, 0));

        delayButton = createSecondaryButton(context, LocaleController.getString(R.string.XrayProxyDelayCheck));
        delayButton.setOnClickListener(v -> runDelayCheck());
        actionsRow.addView(delayButton, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, Gravity.CENTER_VERTICAL, 0, 0, 6, 0));

        stopButton = createDangerButton(context, LocaleController.getString(R.string.XrayProxyLoginDisconnect));
        stopButton.setOnClickListener(v -> stopProxy());
        actionsRow.addView(stopButton, LayoutHelper.createLinear(0, LayoutHelper.MATCH_PARENT, 1f, Gravity.CENTER_VERTICAL, 6, 0, 0, 0));

        statusText = new TextView(context);
        statusText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        statusText.setGravity(Gravity.CENTER_HORIZONTAL);
        statusText.setPadding(0, AndroidUtilities.dp(8), 0, AndroidUtilities.dp(2));
        container.addView(statusText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 6, 0, 0));

        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(container);
        setCustomView(scrollView);

        refreshStatus();
    }

    private TextView createPrimaryButton(Context context, String text) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        button.setTypeface(AndroidUtilities.bold());
        button.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText));
        button.setPadding(AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6), 0);
        button.setIncludeFontPadding(false);
        int normal = Theme.getColor(Theme.key_featuredStickers_addButton);
        int pressed = Theme.getColor(Theme.key_featuredStickers_addButtonPressed);
        button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(10), normal, pressed));
        return button;
    }

    private TextView createSecondaryButton(Context context, String text) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        button.setTypeface(AndroidUtilities.bold());
        button.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4));
        button.setPadding(AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6), 0);
        button.setIncludeFontPadding(false);
        int normal = Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), 0.06f);
        int pressed = Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText), 0.12f);
        button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(10), normal, pressed));
        return button;
    }

    private TextView createDangerButton(Context context, String text) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        button.setTypeface(AndroidUtilities.bold());
        button.setTextColor(Theme.getColor(Theme.key_text_RedRegular));
        button.setPadding(AndroidUtilities.dp(6), 0, AndroidUtilities.dp(6), 0);
        button.setIncludeFontPadding(false);
        int normal = Theme.multAlpha(Theme.getColor(Theme.key_text_RedRegular), 0.10f);
        int pressed = Theme.multAlpha(Theme.getColor(Theme.key_text_RedRegular), 0.16f);
        button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(10), normal, pressed));
        return button;
    }

    /**
     * Parses clipboard text as URI and inserts it into input field.
     */
    private void pasteFromClipboard() {
        try {
            ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                showToast(LocaleController.getString(R.string.XrayProxyErrorImportFailed));
                return;
            }
            ClipData clipData = clipboard.getPrimaryClip();
            if (clipData == null || clipData.getItemCount() <= 0) {
                showToast(LocaleController.getString(R.string.XrayProxyImportClipboard));
                return;
            }
            CharSequence text = clipData.getItemAt(0).coerceToText(getContext());
            if (TextUtils.isEmpty(text)) {
                showToast(LocaleController.getString(R.string.XrayProxyImportClipboard));
                return;
            }
            uriField.setText(text.toString().trim());
        } catch (Throwable t) {
            showToast(LocaleController.getString(R.string.XrayProxyErrorImportFailed));
        }
    }

    /**
     * Starts Xray core with URI from input field or active profile config.
     */
    private void startProxy() {
        if (!XrayAppProxyManager.isLibraryAvailable()) {
            setStatus(LocaleController.getString(R.string.XrayProxyLibMissing), true);
            return;
        }

        String rawConfig = resolveSourceConfig(true);
        if (TextUtils.isEmpty(rawConfig)) {
            return;
        }

        XrayProxyProfileStore.Profile active = getActiveProfileSafe();
        int localPort = active != null ? active.localPort : NekoConfig.xrayAppProxyLocalPort;

        RuntimeConfig runtimeConfig;
        try {
            XrayLocalSocksAuth.Credentials credentials = XrayLocalSocksAuth.getOrCreateCredentials();
            String configJson = XrayLocalSocksAuth.applyCredentials(rawConfig, localPort, credentials);
            runtimeConfig = new RuntimeConfig(localPort, configJson, credentials);
        } catch (Throwable t) {
            setStatus(LocaleController.getString(R.string.XrayProxyConfigApplyAuthError), true);
            return;
        }

        XrayConfigValidator.ValidationResult validation = XrayConfigValidator.validate(runtimeConfig.configJson, runtimeConfig.localPort);
        if (!validation.valid) {
            setStatus(validation.message, true);
            return;
        }

        setLoading(true);
        if (XrayAppProxyManager.isRunning()) {
            XrayAppProxyManager.stop((success, message) -> AndroidUtilities.runOnUIThread(() -> {
                if (!success && XrayAppProxyManager.isRunning()) {
                    setLoading(false);
                    setStatus(message, true);
                    refreshStatus();
                    return;
                }
                startRuntimeProxy(runtimeConfig);
            }));
            return;
        }
        startRuntimeProxy(runtimeConfig);
    }

    private void startRuntimeProxy(RuntimeConfig runtimeConfig) {
        XrayAppProxyManager.start(runtimeConfig.configJson, (success, message) -> AndroidUtilities.runOnUIThread(() -> {
            setLoading(false);
            if (!success) {
                NekoConfig.setXrayAppProxyEnabled(XrayAppProxyManager.isRunning());
                setStatus(message, true);
                refreshStatus();
                return;
            }
            NekoConfig.setXrayAppProxyEnabled(true);
            XrayTelegramProxyBridge.enableLocalProxy(runtimeConfig.localPort, runtimeConfig.credentials);
            setStatus(LocaleController.getString(R.string.XrayProxyStatusRunning), false);
            refreshStatus();
        }));
    }

    /**
     * Stops Xray core and disables local proxy in Telegram networking settings.
     */
    private void stopProxy() {
        setLoading(true);
        XrayAppProxyManager.stop((success, message) -> AndroidUtilities.runOnUIThread(() -> {
            setLoading(false);
            if (!success) {
                setStatus(message, true);
                refreshStatus();
                return;
            }
            NekoConfig.setXrayAppProxyEnabled(false);
            XrayTelegramProxyBridge.disableLocalProxyIfOwned();
            setStatus(LocaleController.getString(R.string.XrayProxyStatusStopped), false);
            refreshStatus();
        }));
    }

    /**
     * Measures current route delay using active profile check URL.
     */
    private void runDelayCheck() {
        String rawConfig = resolveSourceConfig(false);
        if (TextUtils.isEmpty(rawConfig)) {
            return;
        }

        XrayProxyProfileStore.Profile active = getActiveProfileSafe();
        int localPort = active != null ? active.localPort : NekoConfig.xrayAppProxyLocalPort;
        String checkUrl = active == null || TextUtils.isEmpty(active.checkUrl)
                ? XrayProxyProfileStore.DEFAULT_CHECK_URL
                : active.checkUrl.trim();

        RuntimeConfig runtimeConfig;
        try {
            XrayLocalSocksAuth.Credentials credentials = XrayLocalSocksAuth.getOrCreateCredentials();
            String configJson = XrayLocalSocksAuth.applyCredentials(rawConfig, localPort, credentials);
            runtimeConfig = new RuntimeConfig(localPort, configJson, credentials);
        } catch (Throwable t) {
            setStatus(LocaleController.getString(R.string.XrayProxyConfigApplyAuthError), true);
            return;
        }

        XrayConfigValidator.ValidationResult validation = XrayConfigValidator.validate(runtimeConfig.configJson, localPort);
        if (!validation.valid) {
            setStatus(validation.message, true);
            return;
        }

        setLoading(true);
        XrayAppProxyManager.measureDelay(runtimeConfig.configJson, checkUrl, (success, delayMs, message) -> AndroidUtilities.runOnUIThread(() -> {
            setLoading(false);
            if (!success) {
                setStatus(message, true);
                return;
            }
            String text = LocaleController.formatStringSimple(LocaleController.getString(R.string.XrayProxyDelayCheckResult), String.valueOf(delayMs));
            setStatus(text, false);
        }));
    }

    /**
     * Resolves source config with optional URI override from input field and persists it into active profile.
     */
    private String resolveSourceConfig(boolean persistInputUri) {
        try {
            XrayProxyProfileStore.Profile active = getActiveProfileSafe();
            int localPort = active != null ? active.localPort : NekoConfig.xrayAppProxyLocalPort;
            String rawUri = uriField.getText() == null ? "" : uriField.getText().toString().trim();

            if (!TextUtils.isEmpty(rawUri)) {
                XrayUriConfigFactory.ParseResult parseResult = XrayUriConfigFactory.fromLink(rawUri, localPort);
                if (parseResult == null || !parseResult.valid || parseResult.config == null) {
                    XrayUriConfigFactory.ParseResult extractedResult = XrayUriConfigFactory.fromClipboardText(rawUri, localPort);
                    if (extractedResult != null && extractedResult.valid && extractedResult.config != null) {
                        parseResult = extractedResult;
                    }
                }
                if (parseResult == null || !parseResult.valid || parseResult.config == null) {
                    String message = parseResult == null ? "" : parseResult.message;
                    if (TextUtils.isEmpty(message)) {
                        message = LocaleController.getString(R.string.XrayProxyErrorImportFailed);
                    }
                    setStatus(message, true);
                    return "";
                }
                String configJson = parseResult.config.toString();
                if (persistInputUri) {
                    persistConfig(configJson, active);
                }
                return configJson;
            }

            if (active != null && !TextUtils.isEmpty(active.configJson)) {
                return active.configJson;
            }
            if (!TextUtils.isEmpty(NekoConfig.xrayAppProxyConfigJson)) {
                return NekoConfig.xrayAppProxyConfigJson;
            }

            setStatus(LocaleController.getString(R.string.XrayProxyConfigEmpty), true);
            return "";
        } catch (Throwable t) {
            FileLog.e(TAG + ": resolve source config failed", t);
            setStatus(LocaleController.getString(R.string.XrayProxyConfigEmpty), true);
            return "";
        }
    }

    private void persistConfig(String configJson, XrayProxyProfileStore.Profile active) {
        NekoConfig.setXrayAppProxyConfigJson(configJson);
        if (active == null) {
            return;
        }
        active.configJson = configJson;
        XrayProxyProfileStore.updateProfile(active);
    }

    private XrayProxyProfileStore.Profile getActiveProfileSafe() {
        try {
            return XrayProxyProfileStore.getActiveProfile();
        } catch (Throwable t) {
            FileLog.e(TAG + ": get active profile failed", t);
            return null;
        }
    }

    private void refreshStatus() {
        boolean running = XrayAppProxyManager.isRunning();
        SharedPreferences preferences = MessagesController.getGlobalMainSettings();
        String ip = preferences.getString("proxy_ip", "");
        int port = preferences.getInt("proxy_port", 0);
        boolean proxyEnabled = preferences.getBoolean("proxy_enabled", false);
        boolean localProxyEnabled = proxyEnabled && XrayTelegramProxyBridge.isLocalProxyAddress(ip) && port > 0;

        if (running) {
            setStatus(LocaleController.getString(R.string.XrayProxyStatusRunning), false);
        } else if (localProxyEnabled) {
            String text = LocaleController.formatStringSimple(LocaleController.getString(R.string.XrayProxyLoginLocalEndpoint), String.valueOf(port));
            setStatus(text, false);
        } else {
            setStatus(LocaleController.getString(R.string.XrayProxyStatusStopped), false);
        }
        stopButton.setEnabled(running || localProxyEnabled);
    }

    private void setStatus(String text, boolean error) {
        statusText.setText(text);
        statusText.setTextColor(Theme.getColor(error ? Theme.key_text_RedRegular : Theme.key_windowBackgroundWhiteGrayText2));
    }

    private void setLoading(boolean loading) {
        startButton.setEnabled(!loading);
        stopButton.setEnabled(!loading);
        delayButton.setEnabled(!loading);
        pasteButton.setEnabled(!loading);
        uriField.setEnabled(!loading);
    }

    private void showToast(String text) {
        Toast.makeText(ApplicationLoader.applicationContext, text, Toast.LENGTH_SHORT).show();
    }

    private static final class RuntimeConfig {
        final int localPort;
        final String configJson;
        final XrayLocalSocksAuth.Credentials credentials;

        RuntimeConfig(int localPort, String configJson, XrayLocalSocksAuth.Credentials credentials) {
            this.localPort = localPort;
            this.configJson = configJson;
            this.credentials = credentials;
        }
    }
}
