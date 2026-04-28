package zxc.iconic.xenon.settings;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;

import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.AlertsCreator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;

import java.util.ArrayList;

import zxc.iconic.xenon.proxy.XrayConfigSummary;
import zxc.iconic.xenon.proxy.XrayConfigValidator;
import zxc.iconic.xenon.proxy.XrayProxyProfileStore;
import zxc.iconic.xenon.proxy.XrayUriConfigFactory;

/**
 * Full editor for a single proxy profile.
 */
public class NekoXrayProxyProfileEditActivity extends BaseNekoSettingsActivity {

    private static final String ARG_PROFILE_ID = "xray_profile_id";

    private final int setActiveRow = rowId++;
    private final int nameRow = rowId++;
    private final int endpointRow = rowId++;
    private final int localPortRow = rowId++;
    private final int checkUrlRow = rowId++;
    private final int configRow = rowId++;
    private final int importClipboardRow = rowId++;
    private final int importUriRow = rowId++;
    private final int deleteRow = rowId++;

    private String profileId;
    private XrayProxyProfileStore.Profile profile;

    public NekoXrayProxyProfileEditActivity() {
        super();
    }

    private NekoXrayProxyProfileEditActivity(Bundle args) {
        super(args);
    }

    public static NekoXrayProxyProfileEditActivity forProfile(String id) {
        Bundle args = new Bundle();
        args.putString(ARG_PROFILE_ID, id);
        return new NekoXrayProxyProfileEditActivity(args);
    }

    @Override
    public boolean onFragmentCreate() {
        Bundle args = getArguments();
        profileId = args == null ? "" : args.getString(ARG_PROFILE_ID, "");
        reloadProfile();
        return super.onFragmentCreate();
    }

    @Override
    public void onResume() {
        super.onResume();
        reloadProfile();
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        reloadProfile();
        if (profile == null) {
            items.add(UItem.asShadow(LocaleController.getString(R.string.XrayProxyProfileNotFound)));
            return;
        }

        XrayProxyProfileStore.Profile active = XrayProxyProfileStore.getActiveProfile();
        boolean isActive = active != null && TextUtils.equals(active.id, profile.id);

        items.add(UItem.asHeader(LocaleController.getString(R.string.XrayProxyProfileSection)));
        items.add(UItem.asButton(nameRow, R.drawable.msg_edit,
                LocaleController.getString(R.string.XrayProxyProfileName),
                profile.name).slug("xrayProfileName"));
        items.add(UItem.asButton(endpointRow, R.drawable.msg_link2,
                LocaleController.getString(R.string.XrayProxyEndpoint),
                XrayConfigSummary.endpoint(profile.configJson, LocaleController.getString(R.string.XrayProxyConfigEmpty))).slug("xrayProfileEndpoint"));
        items.add(UItem.asButton(localPortRow, R.drawable.msg_settings,
                LocaleController.getString(R.string.XrayProxyLocalPort),
                String.valueOf(profile.localPort)).slug("xrayProfilePort"));
        items.add(UItem.asButton(checkUrlRow, R.drawable.msg_link,
                LocaleController.getString(R.string.XrayProxyCheckUrl),
                profile.checkUrl).slug("xrayProfileCheckUrl"));
        items.add(UItem.asButton(configRow, R.drawable.msg_info,
                LocaleController.getString(R.string.XrayProxyConfig),
                TextUtils.isEmpty(profile.configJson) ? LocaleController.getString(R.string.XrayProxyConfigEmpty) : LocaleController.getString(R.string.XrayProxyConfigReady)).slug("xrayProfileConfig"));
        items.add(UItem.asShadow(LocaleController.getString(R.string.XrayProxyProfileHint)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.XrayProxyImportSection)));
        items.add(UItem.asButton(importClipboardRow, R.drawable.msg_copy,
                LocaleController.getString(R.string.XrayProxyImportClipboard)).accent().slug("xrayProfileImportClipboard"));
        items.add(UItem.asButton(importUriRow, R.drawable.msg_link2,
                LocaleController.getString(R.string.XrayProxyImportUri)).slug("xrayProfileImportUri"));
        items.add(UItem.asShadow(LocaleController.getString(R.string.XrayProxyImportHint)));

        items.add(UItem.asHeader(LocaleController.getString(R.string.XrayProxyActionsSection)));
        UItem setActive = UItem.asButton(setActiveRow, R.drawable.msg_settings,
                LocaleController.getString(R.string.XrayProxySetActive)).slug("xrayProfileSetActive");
        if (!isActive) {
            setActive.accent();
        }
        setActive.setEnabled(!isActive);
        items.add(setActive);

        items.add(UItem.asButton(deleteRow, R.drawable.msg_delete,
                LocaleController.getString(R.string.XrayProxyDeleteProfile)).red().slug("xrayProfileDelete"));
        items.add(UItem.asShadow(LocaleController.getString(R.string.XrayProxyDeleteHint)));
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        if (profile == null) {
            return;
        }

        int id = item.id;
        if (id == setActiveRow) {
            XrayProxyProfileStore.setActiveProfile(profile.id);
            listView.adapter.update(true);
            return;
        }

        if (id == nameRow) {
            showSingleFieldDialog(LocaleController.getString(R.string.XrayProxyProfileName), profile.name,
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES,
                    value -> {
                        String newValue = value.trim();
                        if (TextUtils.isEmpty(newValue)) {
                            showError(LocaleController.getString(R.string.XrayProxyProfileNameEmpty));
                            return false;
                        }
                        profile.name = newValue;
                        saveProfile();
                        return true;
                    });
            return;
        }

        if (id == localPortRow) {
            showSingleFieldDialog(LocaleController.getString(R.string.XrayProxyLocalPort), String.valueOf(profile.localPort),
                    InputType.TYPE_CLASS_NUMBER,
                    value -> {
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
                        profile.localPort = parsed;
                        saveProfile();
                        return true;
                    });
            return;
        }

        if (id == checkUrlRow) {
            showSingleFieldDialog(LocaleController.getString(R.string.XrayProxyCheckUrl), profile.checkUrl,
                    InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI,
                    value -> {
                        String newValue = value.trim();
                        if (TextUtils.isEmpty(newValue)) {
                            showError(LocaleController.getString(R.string.XrayProxyErrorUrlEmpty));
                            return false;
                        }
                        profile.checkUrl = newValue;
                        saveProfile();
                        return true;
                    });
            return;
        }

        if (id == configRow) {
            showMultiLineConfigDialog();
            return;
        }

        if (id == importClipboardRow) {
            importFromClipboard();
            return;
        }

        if (id == importUriRow) {
            showUriImportDialog();
            return;
        }

        if (id == deleteRow) {
            boolean deleted = XrayProxyProfileStore.deleteProfile(profile.id);
            if (!deleted) {
                showError(LocaleController.getString(R.string.XrayProxyDeleteLastProfileError));
                return;
            }
            finishFragment();
        }
    }

    private void showUriImportDialog() {
        Activity context = getParentActivity();
        if (context == null || profile == null) {
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
                String raw = editText.getText() == null ? "" : editText.getText().toString().trim();
                XrayUriConfigFactory.ParseResult result = XrayUriConfigFactory.fromLink(raw, profile.localPort);
                if (!result.valid) {
                    showError(result.message);
                    return;
                }
                applyImportedConfig(result);
                dialog.dismiss();
            });
        }
    }

    /**
     * Imports profile transport config from clipboard.
     */
    private void importFromClipboard() {
        if (profile == null) {
            return;
        }
        String clipText = readClipboardText();
        XrayUriConfigFactory.ParseResult result = XrayUriConfigFactory.fromClipboardText(clipText, profile.localPort);
        if (!result.valid) {
            showError(result.message);
            return;
        }
        applyImportedConfig(result);
    }

    private void applyImportedConfig(XrayUriConfigFactory.ParseResult result) {
        if (profile == null || result.config == null) {
            showError(LocaleController.getString(R.string.XrayProxyErrorImportFailed));
            return;
        }

        String json;
        try {
            json = result.config.toString(2);
        } catch (Throwable ignore) {
            json = result.config.toString();
        }

        XrayConfigValidator.ValidationResult validation = XrayConfigValidator.validate(json, profile.localPort);
        if (!validation.valid) {
            showError(validation.message);
            return;
        }

        profile.configJson = json;
        if (TextUtils.isEmpty(profile.name) || "Proxy".equals(profile.name)) {
            profile.name = TextUtils.isEmpty(result.nodeName)
                    ? (result.protocol + " " + result.host + ":" + result.port)
                    : result.nodeName;
        }
        saveProfile();
    }

    private void showMultiLineConfigDialog() {
        Activity context = getParentActivity();
        if (context == null || profile == null) {
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
        editText.setText(profile.configJson);
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
                XrayConfigValidator.ValidationResult validation = XrayConfigValidator.validate(value, profile.localPort);
                if (!validation.valid) {
                    showError(validation.message);
                    return;
                }
                profile.configJson = value;
                saveProfile();
                dialog.dismiss();
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

    private void saveProfile() {
        if (profile == null) {
            return;
        }
        XrayProxyProfileStore.updateProfile(profile);
        reloadProfile();
        if (listView != null) {
            listView.adapter.update(true);
        }
    }

    private void reloadProfile() {
        if (TextUtils.isEmpty(profileId)) {
            profile = null;
            return;
        }
        ArrayList<XrayProxyProfileStore.Profile> allProfiles = XrayProxyProfileStore.getProfiles();
        profile = null;
        for (int i = 0; i < allProfiles.size(); i++) {
            XrayProxyProfileStore.Profile candidate = allProfiles.get(i);
            if (TextUtils.equals(candidate.id, profileId)) {
                profile = candidate;
                break;
            }
        }
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

    private void showError(String message) {
        AlertsCreator.showSimpleAlert(this, LocaleController.getString(R.string.ErrorOccurred), message);
    }

    @Override
    protected String getActionBarTitle() {
        return profile == null ? LocaleController.getString(R.string.XrayProxyProfile) : profile.name;
    }

    @Override
    protected String getKey() {
        return "xrayProfileEdit";
    }

    private interface ValueCommit {
        boolean commit(String value);
    }
}
