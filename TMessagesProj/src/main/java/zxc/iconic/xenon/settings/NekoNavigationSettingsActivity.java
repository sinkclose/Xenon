package zxc.iconic.xenon.settings;

import android.content.Context;
import android.content.DialogInterface;
import android.text.InputType;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.util.TypedValue;
import android.view.View;
import android.widget.LinearLayout;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.URLSpanNoUnderline;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;

import zxc.iconic.xenon.NekoConfig;
import zxc.iconic.xenon.helpers.PopupHelper;

public class NekoNavigationSettingsActivity extends BaseNekoSettingsActivity {

    private final int openAnimationStyleRow = rowId++;
    private final int closeAnimationStyleRow = rowId++;
    private final int predictiveBackAnimationStyleRow = rowId++;

    private final int predictiveBackIntensityRow = rowId++;

    private final int mainTabsRow = rowId++;
    private final int tabletModeRow = rowId++;

    private final int transitionSpeedRow = rowId++;
    private final int easeRow = rowId++;
    private final int easeDescriptionRow = rowId++;

    private final int fadeSpeedRow = rowId++;
    private final int fadeEaseRow = rowId++;
    private final int slideSpeedRow = rowId++;
    private final int slideEaseRow = rowId++;
    private boolean advancedSettings;

    @Override
    public View createView(Context context) {
        advancedSettings = hasCustomAnimationSettings();
        View result = super.createView(context);
        var more = actionBar.createMenu().addItem(900, R.drawable.ic_ab_other);
        more.setOnClickListener(v -> ItemOptions.makeOptions(this, more)
                .add(0, LocaleController.getString(advancedSettings ? R.string.HideAdvancedSettings : R.string.ShowAdvancedSettings), () -> {
                    advancedSettings = !advancedSettings;
                    if (!advancedSettings) resetAnimationSettings();
                    listView.adapter.update(true);
                }).show());
        return result;
    }

    private boolean hasCustomAnimationSettings() {
        return NekoConfig.alternativeTransitionSpeed != NekoConfig.DEFAULT_IOS_DURATION
                || !NekoConfig.DEFAULT_IOS_EASE.equals(NekoConfig.alternativeTransitionEase)
                || NekoConfig.fadeDuration != NekoConfig.DEFAULT_FADE_DURATION
                || !NekoConfig.DEFAULT_FADE_EASE.equals(NekoConfig.fadeEase)
                || NekoConfig.slideDuration != NekoConfig.DEFAULT_SLIDE_DURATION
                || !NekoConfig.DEFAULT_SLIDE_EASE.equals(NekoConfig.slideEase);
    }

    private void resetAnimationSettings() {
        NekoConfig.setAlternativeTransitionSpeed(NekoConfig.DEFAULT_IOS_DURATION);
        NekoConfig.setAlternativeTransitionEase(NekoConfig.DEFAULT_IOS_EASE);
        NekoConfig.setFadeDuration(NekoConfig.DEFAULT_FADE_DURATION);
        NekoConfig.setFadeEase(NekoConfig.DEFAULT_FADE_EASE);
        NekoConfig.setSlideDuration(NekoConfig.DEFAULT_SLIDE_DURATION);
        NekoConfig.setSlideEase(NekoConfig.DEFAULT_SLIDE_EASE);
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        items.add(UItem.asHeader(LocaleController.getString(R.string.Navigation)));
        items.add(TextSettingsCellFactory.of(openAnimationStyleRow, LocaleController.getString(R.string.OpenAnimationStyle), animationStyleName(NekoConfig.openAnimationStyle)).slug("openAnimationStyle"));
        items.add(TextSettingsCellFactory.of(closeAnimationStyleRow, LocaleController.getString(R.string.CloseAnimationStyle), animationStyleName(NekoConfig.closeAnimationStyle)).slug("closeAnimationStyle"));
        items.add(TextSettingsCellFactory.of(predictiveBackAnimationStyleRow, LocaleController.getString(R.string.PredictiveBackAnimationStyle), animationStyleName(NekoConfig.predictiveBackAnimationStyle)).slug("predictiveBackAnimationStyle"));

        SeekbarConfig intensityConfig = new SeekbarConfig(
                LocaleController.getString(R.string.PredictiveBackIntensity),
                LocaleController.getString(R.string.Off),
                "MAX", 0, 20, 1,
                progress -> {
                    int newValue = Math.round(progress);
                    boolean wasEnabled = NekoConfig.predictiveBackIntensity > 0;
                    NekoConfig.setPredictiveBackIntensity(newValue);
                    boolean nowEnabled = newValue > 0;
                    if (wasEnabled != nowEnabled) {
                        showRestartBulletin();
                    }
                });
        intensityConfig.valueFormatter = value -> {
            float f = value / 10f;
            return String.format(java.util.Locale.US, "%.1f", f);
        };
        items.add(SeekbarCellFactory.of(predictiveBackIntensityRow, intensityConfig, NekoConfig.predictiveBackIntensity).slug("predictiveBackIntensity"));

        items.add(TextSettingsCellFactory.of(mainTabsRow, LocaleController.getString(R.string.MainTabsCustomizeTitle), LocaleController.getString(R.string.MainTabsCustomizeHint)).slug("mainTabs"));
        items.add(TextSettingsCellFactory.of(tabletModeRow, LocaleController.getString(R.string.TabletMode), switch (NekoConfig.tabletMode) {
            case NekoConfig.TABLET_AUTO -> LocaleController.getString(R.string.TabletModeAuto);
            case NekoConfig.TABLET_ENABLE -> LocaleController.getString(R.string.Enable);
            default -> LocaleController.getString(R.string.Disable);
        }).slug("tabletMode"));

        if (!advancedSettings) return;

        items.add(UItem.asHeader(LocaleController.getString(R.string.IOSAnimation)));
        SeekbarConfig speedConfig = new SeekbarConfig(
                LocaleController.getString(R.string.TransitionSpeed),
                "100", "1000", 100, 1000, 5,
                progress -> NekoConfig.setAlternativeTransitionSpeed(Math.round(progress / 5f) * 5));
        items.add(SeekbarCellFactory.of(transitionSpeedRow, speedConfig, NekoConfig.alternativeTransitionSpeed).slug("transitionSpeed"));
        items.add(TextSettingsCellFactory.of(easeRow, LocaleController.getString(R.string.Ease), NekoConfig.alternativeTransitionEase).slug("ease"));
        var description = new SpannableStringBuilder(LocaleController.getString(R.string.AlternativeTransitionEaseDescription));
        int linkStart = description.toString().indexOf("cubic-bezier.com");
        if (linkStart >= 0) {
            description.setSpan(new URLSpanNoUnderline("https://cubic-bezier.com"), linkStart, linkStart + "cubic-bezier.com".length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        items.add(UItem.asShadow(easeDescriptionRow, description));

        items.add(UItem.asHeader(LocaleController.getString(R.string.FadeAnimation)));
        SeekbarConfig fadeConfig = new SeekbarConfig(
                LocaleController.getString(R.string.FadeTransitionSpeed),
                "100", "1000", 100, 1000, 5,
                progress -> NekoConfig.setFadeDuration(Math.round(progress / 5f) * 5));
        items.add(SeekbarCellFactory.of(fadeSpeedRow, fadeConfig, NekoConfig.fadeDuration).slug("fadeDuration"));
        items.add(TextSettingsCellFactory.of(fadeEaseRow, LocaleController.getString(R.string.Ease), NekoConfig.fadeEase).slug("fadeEase"));

        items.add(UItem.asHeader(LocaleController.getString(R.string.AnimationStyleSlide)));
        SeekbarConfig slideConfig = new SeekbarConfig(LocaleController.getString(R.string.TransitionSpeed),
                "100", "1000", 100, 1000, 5,
                progress -> NekoConfig.setSlideDuration(Math.round(progress / 5f) * 5));
        items.add(SeekbarCellFactory.of(slideSpeedRow, slideConfig, NekoConfig.slideDuration).slug("slideDuration"));
        items.add(TextSettingsCellFactory.of(slideEaseRow, LocaleController.getString(R.string.Ease), NekoConfig.slideEase).slug("slideEase"));
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        var id = item.id;
        if (id == openAnimationStyleRow) {
            showAnimationStylePopup(R.string.OpenAnimationStyle, NekoConfig.openAnimationStyle, view, position, NekoConfig::setOpenAnimationStyle, item, false);
        } else if (id == closeAnimationStyleRow) {
            showAnimationStylePopup(R.string.CloseAnimationStyle, NekoConfig.closeAnimationStyle, view, position, NekoConfig::setCloseAnimationStyle, item, false);
        } else if (id == predictiveBackAnimationStyleRow) {
            showAnimationStylePopup(R.string.PredictiveBackAnimationStyle, NekoConfig.predictiveBackAnimationStyle, view, position, NekoConfig::setPredictiveBackAnimationStyle, item, true);
        } else if (id == tabletModeRow) {
            ArrayList<String> arrayList = new ArrayList<>();
            ArrayList<Integer> types = new ArrayList<>();
            arrayList.add(LocaleController.getString(R.string.TabletModeAuto));
            types.add(NekoConfig.TABLET_AUTO);
            arrayList.add(LocaleController.getString(R.string.Enable));
            types.add(NekoConfig.TABLET_ENABLE);
            arrayList.add(LocaleController.getString(R.string.Disable));
            types.add(NekoConfig.TABLET_DISABLE);
            PopupHelper.show(arrayList, LocaleController.getString(R.string.TabletMode), types.indexOf(NekoConfig.tabletMode), getParentActivity(), view, i -> {
                NekoConfig.setTabletMode(types.get(i));
                item.textValue = arrayList.get(i);
                listView.adapter.notifyItemChanged(position, PARTIAL);
                AndroidUtilities.resetTabletFlag();
                if (getParentActivity() instanceof LaunchActivity) {
                    ((LaunchActivity) getParentActivity()).invalidateTabletMode();
                }
            }, resourcesProvider);
        } else if (id == mainTabsRow) {
            presentFragment(new MainTabsSettingsActivity());
        } else if (id == easeRow || id == fadeEaseRow || id == slideEaseRow) {
            showEaseDialog(id);
        }
    }

    private void showAnimationStylePopup(int titleRes, int currentStyle, View view, int position,
                                         java.util.function.IntConsumer setter, UItem item, boolean needsRestart) {
        ArrayList<String> arrayList = new ArrayList<>();
        ArrayList<Integer> types = new ArrayList<>();
        arrayList.add(LocaleController.getString(R.string.Default));
        types.add(NekoConfig.ANIMATION_STYLE_DEFAULT);
        arrayList.add(LocaleController.getString(R.string.AnimationStyleIos));
        types.add(NekoConfig.ANIMATION_STYLE_IOS);
        arrayList.add(LocaleController.getString(R.string.AnimationStyleAosp));
        types.add(NekoConfig.ANIMATION_STYLE_AOSP);
        if (titleRes == R.string.PredictiveBackAnimationStyle) {
            arrayList.add(LocaleController.getString(R.string.AnimationStyleAospAlt));
            types.add(NekoConfig.ANIMATION_STYLE_AOSP_ALT);
        }
        arrayList.add(LocaleController.getString(R.string.AnimationStyleFade));
        types.add(NekoConfig.ANIMATION_STYLE_FADE);
        arrayList.add(LocaleController.getString(R.string.AnimationStyleSlide));
        types.add(NekoConfig.ANIMATION_STYLE_SLIDE);
        arrayList.add(LocaleController.getString(R.string.AnimationStyleAospLegacy));
        types.add(NekoConfig.ANIMATION_STYLE_AOSP_LEGACY);
        arrayList.add(LocaleController.getString(R.string.AnimationStyleAosp9));
        types.add(NekoConfig.ANIMATION_STYLE_AOSP_9);
        PopupHelper.show(arrayList, LocaleController.getString(titleRes), types.indexOf(currentStyle), getParentActivity(), view, i -> {
            setter.accept(types.get(i));
            item.textValue = arrayList.get(i);
            listView.adapter.notifyItemChanged(position, PARTIAL);
            if (needsRestart) {
                showRestartBulletin();
            }
        }, resourcesProvider);
    }

    private String animationStyleName(int style) {
        return switch (style) {
            case NekoConfig.ANIMATION_STYLE_IOS -> LocaleController.getString(R.string.AnimationStyleIos);
            case NekoConfig.ANIMATION_STYLE_AOSP -> LocaleController.getString(R.string.AnimationStyleAosp);
            case NekoConfig.ANIMATION_STYLE_AOSP_ALT -> LocaleController.getString(R.string.AnimationStyleAospAlt);
            case NekoConfig.ANIMATION_STYLE_AOSP_9 -> LocaleController.getString(R.string.AnimationStyleAosp9);
            case NekoConfig.ANIMATION_STYLE_SLIDE -> LocaleController.getString(R.string.AnimationStyleSlide);
            case NekoConfig.ANIMATION_STYLE_AOSP_LEGACY -> LocaleController.getString(R.string.AnimationStyleAospLegacy);
            case NekoConfig.ANIMATION_STYLE_FADE -> LocaleController.getString(R.string.AnimationStyleFade);
            default -> LocaleController.getString(R.string.Default);
        };
    }

    private void showEaseDialog(int row) {
        String currentEase = row == slideEaseRow ? NekoConfig.slideEase : row == fadeEaseRow ? NekoConfig.fadeEase : NekoConfig.alternativeTransitionEase;
        String defaultEase = row == slideEaseRow ? NekoConfig.DEFAULT_SLIDE_EASE : row == fadeEaseRow ? NekoConfig.DEFAULT_FADE_EASE : NekoConfig.DEFAULT_IOS_EASE;
        java.util.function.Consumer<String> setter = row == slideEaseRow ? NekoConfig::setSlideEase : row == fadeEaseRow ? NekoConfig::setFadeEase : NekoConfig::setAlternativeTransitionEase;
        Context context = getParentActivity();
        if (context == null) return;

        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(LocaleController.getString(R.string.Ease));

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(16), AndroidUtilities.dp(24), 0);

        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        editText.setInputType(InputType.TYPE_CLASS_TEXT);
        editText.setText(currentEase);
        editText.setSelection(editText.getText().length());

        container.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        builder.setView(container);

        builder.setPositiveButton(LocaleController.getString("OK", R.string.OK), (dialog, which) -> {
            String text = editText.getText().toString().trim();
            if (!text.isEmpty()) {
                setter.accept(text);
                listView.adapter.update(true);
            }
        });

        builder.setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), null);

        builder.setNeutralButton(LocaleController.getString("Reset", R.string.Reset), (dialog, which) -> {
            setter.accept(defaultEase);
            listView.adapter.update(true);
        });

        AlertDialog dialog = builder.show();
        if (currentEase.equals(defaultEase)) {
            dialog.getButton(DialogInterface.BUTTON_NEUTRAL).setAlpha(0.5f);
        }

        editText.requestFocus();
        AndroidUtilities.showKeyboard(editText);
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.NavigationSettings);
    }

    @Override
    protected String getKey() {
        return "nav";
    }
}
