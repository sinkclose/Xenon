package zxc.iconic.xenon.proxy;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.drawable.GradientDrawable;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.BottomSheet;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;

import zxc.iconic.xenon.NekoConfig;

/**
 * Login-time compact controller for Xray app-only proxy, rewritten in a strict
 * Telegram BottomSheet style: a single primary action whose label/color flips
 * between {@code Connect} and {@code Disconnect} depending on the current core
 * state, plus a secondary {@code Check delay} action. The active profile card
 * at the top lets the user connect with one tap; an optional URI input below
 * lets them paste a different share URI and swap the transport in place.
 */
public class XrayLoginProxyBottomSheet extends BottomSheet {

    private static final String TAG = "XrayLoginProxyBottomSheet";

    /** Active profile summary card. */
    private final LinearLayout activeCard;
    private final TextView activeNameView;
    private final TextView activeEndpointView;

    /** URI input + paste button row. */
    private final EditText uriField;
    private final ImageView pasteIconView;

    /** Bottom actions and status. */
    private final TextView statusText;
    private final TextView primaryButton;
    private final TextView delayButton;

    public XrayLoginProxyBottomSheet(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, false, resourcesProvider);
        fixNavigationBar();
        setTitle(LocaleController.getString(R.string.XrayProxyLoginQuickTitle), true);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(4), AndroidUtilities.dp(16), AndroidUtilities.dp(16));

        TextView hint = makeLabel(context, 13, Theme.key_windowBackgroundWhiteGrayText2);
        hint.setText(LocaleController.getString(R.string.XrayProxyLoginQuickHint));
        hint.setLineSpacing(AndroidUtilities.dp(2), 1.0f);
        container.addView(hint, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 16));

        TextView activeHeader = makeSectionHeader(context, R.string.XrayProxyActiveProfile);
        container.addView(activeHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

        activeCard = new LinearLayout(context);
        activeCard.setOrientation(LinearLayout.VERTICAL);
        activeCard.setPadding(AndroidUtilities.dp(14), AndroidUtilities.dp(12), AndroidUtilities.dp(14), AndroidUtilities.dp(12));
        activeCard.setBackground(makeRoundedFill(Theme.multAlpha(
                Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider), 0.05f), 10));

        activeNameView = makeLabel(context, 15, Theme.key_windowBackgroundWhiteBlackText);
        activeNameView.setTypeface(AndroidUtilities.bold());
        activeNameView.setSingleLine(true);
        activeCard.addView(activeNameView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        activeEndpointView = makeLabel(context, 13, Theme.key_windowBackgroundWhiteGrayText2);
        activeEndpointView.setSingleLine(true);
        activeCard.addView(activeEndpointView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 2, 0, 0));

        container.addView(activeCard, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 16));

        TextView uriHeader = makeSectionHeader(context, R.string.XrayProxyLoginUriLabel);
        container.addView(uriHeader, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 6));

        FrameLayout uriContainer = new FrameLayout(context);
        uriContainer.setBackground(makeRoundedFill(Theme.multAlpha(
                Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider), 0.05f), 10));

        uriField = new EditText(context);
        uriField.setHint(LocaleController.getString(R.string.XrayProxyLoginUriHint));
        uriField.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, resourcesProvider));
        uriField.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
        uriField.setBackground(null);
        uriField.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        uriField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        uriField.setSingleLine(true);
        uriField.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        uriField.setPadding(AndroidUtilities.dp(12), AndroidUtilities.dp(10), AndroidUtilities.dp(40), AndroidUtilities.dp(10));
        uriField.addTextChangedListener(new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override public void afterTextChanged(android.text.Editable s) { refreshState(); }
        });
        uriContainer.addView(uriField, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));

        ImageView pasteIcon = new ImageView(context);
        pasteIcon.setImageResource(R.drawable.msg_copy);
        pasteIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        pasteIcon.setPadding(AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8), AndroidUtilities.dp(8));
        pasteIcon.setColorFilter(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3, resourcesProvider));
        pasteIcon.setBackground(Theme.createSelectorDrawable(
                Theme.getColor(Theme.key_listSelector, resourcesProvider), Theme.RIPPLE_MASK_CIRCLE_20DP));
        pasteIcon.setContentDescription(LocaleController.getString(R.string.XrayProxyLoginPaste));
        pasteIcon.setOnClickListener(v -> pasteFromClipboard());
        uriContainer.addView(pasteIcon, LayoutHelper.createFrame(32, 32, Gravity.END | Gravity.CENTER_VERTICAL, 0, 0, 4, 0));
        pasteIconView = pasteIcon;

        container.addView(uriContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        statusText = makeLabel(context, 13, Theme.key_windowBackgroundWhiteGrayText2);
        statusText.setGravity(Gravity.CENTER_HORIZONTAL);
        statusText.setVisibility(View.GONE);
        container.addView(statusText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 10));

        primaryButton = makePrimaryButton(context);
        primaryButton.setOnClickListener(v -> onPrimaryClick());
        container.addView(primaryButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 46));

        delayButton = makeSecondaryButton(context, LocaleController.getString(R.string.XrayProxyDelayCheck));
        delayButton.setOnClickListener(v -> runDelayCheck());
        container.addView(delayButton, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 42, 0, 8, 0, 0));

        ScrollView scrollView = new ScrollView(context);
        scrollView.addView(container, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT, ScrollView.LayoutParams.WRAP_CONTENT));
        setCustomView(scrollView);

        refreshState();
    }

    /* -------- view builders -------- */

    private TextView makeLabel(Context context, int sizeSp, int themeColorKey) {
        TextView tv = new TextView(context);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeSp);
        tv.setTextColor(Theme.getColor(themeColorKey, resourcesProvider));
        return tv;
    }

    private TextView makeSectionHeader(Context context, int stringRes) {
        TextView tv = makeLabel(context, 13, Theme.key_windowBackgroundWhiteBlueHeader);
        tv.setText(LocaleController.getString(stringRes));
        tv.setTypeface(AndroidUtilities.bold());
        return tv;
    }

    private TextView makePrimaryButton(Context context) {
        TextView button = new TextView(context);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        button.setTypeface(AndroidUtilities.bold());
        button.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText, resourcesProvider));
        button.setIncludeFontPadding(false);
        return button;
    }

    private TextView makeSecondaryButton(Context context, CharSequence text) {
        TextView button = new TextView(context);
        button.setText(text);
        button.setGravity(Gravity.CENTER);
        button.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        button.setTypeface(AndroidUtilities.bold());
        button.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, resourcesProvider));
        button.setIncludeFontPadding(false);
        int normal = Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, resourcesProvider), 0.08f);
        int pressed = Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlueText4, resourcesProvider), 0.16f);
        button.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(10), normal, pressed));
        return button;
    }

    private GradientDrawable makeRoundedFill(int color, int cornerDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        drawable.setCornerRadius(AndroidUtilities.dp(cornerDp));
        drawable.setColor(color);
        return drawable;
    }

    /* -------- state / actions -------- */

    /**
     * Recomputes UI state: active profile card, primary button label/color, status line,
     * and enabled/disabled flags of secondary actions.
     */
    private void refreshState() {
        boolean running = XrayAppProxyManager.isRunning();
        XrayProxyProfileStore.Profile active = getActiveProfileSafe();
        boolean hasActive = active != null && !TextUtils.isEmpty(active.configJson);
        boolean inputFilled = !TextUtils.isEmpty(uriField.getText() == null ? "" : uriField.getText().toString().trim());

        // Active profile card.
        if (hasActive) {
            activeCard.setVisibility(View.VISIBLE);
            activeNameView.setText(active.name);
            String endpoint = XrayConfigSummary.endpoint(active.configJson,
                    LocaleController.getString(R.string.XrayProxyConfigEmpty));
            activeEndpointView.setText(endpoint);
        } else {
            activeCard.setVisibility(View.VISIBLE);
            activeNameView.setText(LocaleController.getString(R.string.XrayProxyLoginActiveNoneTitle));
            activeEndpointView.setText(LocaleController.getString(R.string.XrayProxyLoginActiveNoneHint));
        }

        // Primary button: toggle Connect / Disconnect with matching color.
        if (running) {
            primaryButton.setText(LocaleController.getString(R.string.XrayProxyLoginDisconnect));
            int red = Theme.getColor(Theme.key_text_RedRegular, resourcesProvider);
            int redPressed = Theme.multAlpha(red, 0.85f);
            primaryButton.setTextColor(Theme.getColor(Theme.key_featuredStickers_buttonText, resourcesProvider));
            primaryButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(12), red, redPressed));
            primaryButton.setEnabled(true);
            primaryButton.setAlpha(1f);
        } else {
            primaryButton.setText(LocaleController.getString(R.string.XrayProxyLoginConnect));
            int accent = Theme.getColor(Theme.key_featuredStickers_addButton, resourcesProvider);
            int accentPressed = Theme.getColor(Theme.key_featuredStickers_addButtonPressed, resourcesProvider);
            primaryButton.setBackground(Theme.createSimpleSelectorRoundRectDrawable(AndroidUtilities.dp(12), accent, accentPressed));
            boolean canConnect = hasActive || inputFilled;
            primaryButton.setEnabled(canConnect);
            primaryButton.setAlpha(canConnect ? 1f : 0.5f);
        }

        // Delay button enabled only when we have something to measure.
        boolean canDelay = !running && (hasActive || inputFilled);
        delayButton.setEnabled(canDelay);
        delayButton.setAlpha(canDelay ? 1f : 0.5f);

        // Status text: when running show local endpoint summary.
        if (running) {
            SharedPreferences preferences = MessagesController.getGlobalMainSettings();
            int port = preferences.getInt("proxy_port", 0);
            if (port > 0) {
                setStatus(LocaleController.formatStringSimple(
                        LocaleController.getString(R.string.XrayProxyLoginLocalEndpoint), String.valueOf(port)), false);
            } else {
                setStatus(LocaleController.getString(R.string.XrayProxyStatusRunning), false);
            }
        } else if (!hasActive && !inputFilled) {
            setStatus(LocaleController.getString(R.string.XrayProxyLoginActiveNoneHint), false);
        } else {
            setStatus("", false);
        }
    }

    private void onPrimaryClick() {
        if (XrayAppProxyManager.isRunning()) {
            stopProxy();
        } else {
            startProxy();
        }
    }

    private void pasteFromClipboard() {
        try {
            ClipboardManager clipboard = (ClipboardManager) getContext().getSystemService(Context.CLIPBOARD_SERVICE);
            if (clipboard == null) {
                setStatus(LocaleController.getString(R.string.XrayProxyErrorImportFailed), true);
                return;
            }
            ClipData clipData = clipboard.getPrimaryClip();
            if (clipData == null || clipData.getItemCount() <= 0) {
                setStatus(LocaleController.getString(R.string.XrayProxyImportClipboard), true);
                return;
            }
            CharSequence text = clipData.getItemAt(0).coerceToText(getContext());
            if (TextUtils.isEmpty(text)) {
                setStatus(LocaleController.getString(R.string.XrayProxyImportClipboard), true);
                return;
            }
            uriField.setText(text.toString().trim());
            uriField.setSelection(uriField.getText() == null ? 0 : uriField.getText().length());
            refreshState();
        } catch (Throwable t) {
            setStatus(LocaleController.getString(R.string.XrayProxyErrorImportFailed), true);
        }
    }

    /**
     * Starts Xray core using either URI from the input field (preferred, replaces
     * active profile's transport) or the active profile's stored config.
     */
    private void startProxy() {
        if (!XrayAppProxyManager.isLibraryAvailable()) {
            setStatus(LocaleController.getString(R.string.XrayProxyLibMissing), true);
            return;
        }

        String rawConfig = resolveSourceConfig(true);
        if (TextUtils.isEmpty(rawConfig)) {
            refreshState();
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
        XrayAppProxyManager.start(runtimeConfig.configJson, (success, message) -> AndroidUtilities.runOnUIThread(() -> {
            setLoading(false);
            if (!success) {
                NekoConfig.setXrayAppProxyEnabled(XrayAppProxyManager.isRunning());
                setStatus(message, true);
                refreshState();
                return;
            }
            NekoConfig.setXrayAppProxyEnabled(true);
            XrayTelegramProxyBridge.enableLocalProxy(runtimeConfig.localPort, runtimeConfig.credentials);
            refreshState();
        }));
    }

    private void stopProxy() {
        setLoading(true);
        XrayAppProxyManager.stop((success, message) -> AndroidUtilities.runOnUIThread(() -> {
            setLoading(false);
            if (!success) {
                setStatus(message, true);
                refreshState();
                return;
            }
            NekoConfig.setXrayAppProxyEnabled(false);
            XrayTelegramProxyBridge.disableLocalProxyIfOwned();
            refreshState();
        }));
    }

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
            setStatus(LocaleController.formatStringSimple(
                    LocaleController.getString(R.string.XrayProxyDelayCheckResult), String.valueOf(delayMs)), false);
        }));
    }

    private String resolveSourceConfig(boolean persistInputUri) {
        try {
            XrayProxyProfileStore.Profile active = getActiveProfileSafe();
            int localPort = active != null ? active.localPort : NekoConfig.xrayAppProxyLocalPort;
            String rawUri = uriField.getText() == null ? "" : uriField.getText().toString().trim();

            if (!TextUtils.isEmpty(rawUri)) {
                XrayUriConfigFactory.ParseResult parseResult = XrayUriConfigFactory.fromLink(rawUri, localPort);
                if (parseResult == null || !parseResult.valid || parseResult.config == null) {
                    XrayUriConfigFactory.ParseResult extracted = XrayUriConfigFactory.fromClipboardText(rawUri, localPort);
                    if (extracted != null && extracted.valid && extracted.config != null) {
                        parseResult = extracted;
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

            setStatus(LocaleController.getString(R.string.XrayProxyLoginActiveNoneHint), true);
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

    private void setStatus(String text, boolean error) {
        if (TextUtils.isEmpty(text)) {
            statusText.setText("");
            statusText.setVisibility(View.GONE);
            return;
        }
        statusText.setText(text);
        statusText.setVisibility(View.VISIBLE);
        statusText.setTextColor(Theme.getColor(
                error ? Theme.key_text_RedRegular : Theme.key_windowBackgroundWhiteGrayText2,
                resourcesProvider));
    }

    private void setLoading(boolean loading) {
        uriField.setEnabled(!loading);
        pasteIconView.setEnabled(!loading);
        primaryButton.setEnabled(!loading);
        delayButton.setEnabled(!loading);
        primaryButton.setAlpha(loading ? 0.5f : 1f);
        delayButton.setAlpha(loading ? 0.5f : 1f);
        pasteIconView.setAlpha(loading ? 0.5f : 1f);
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
