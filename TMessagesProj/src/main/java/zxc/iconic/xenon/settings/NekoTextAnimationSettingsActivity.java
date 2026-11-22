package zxc.iconic.xenon.settings;

import android.content.Context;
import android.os.Build;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.RecyclerView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.TextCheckCell;
import org.telegram.ui.Components.ItemOptions;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.TextAnimationEditText;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

import java.util.ArrayList;

import zxc.iconic.xenon.NekoConfig;
import zxc.iconic.xenon.TextRemovalAnimationSettings;
import zxc.iconic.xenon.TextRemovalAnimationSettings.Mode;
import zxc.iconic.xenon.TextRemovalAnimationSettings.Phase;
import zxc.iconic.xenon.TextRemovalAnimationSettings.Slider;
import zxc.iconic.xenon.TextRemovalAnimationSettings.Toggle;

public class NekoTextAnimationSettingsActivity extends BaseNekoSettingsActivity {

    private final int textAnimationRow = rowId++;
    private final int textAnimTestInputRow = rowId++;
    private final int textAnimCursorRow = rowId++;
    private final int textAnimFadeRow = rowId++;
    private final int textAnimBlurRow = rowId++;
    private final int textAnimBlurDurationRow = rowId++;
    private final int removalModeRow = rowId++;
    private final int firstRemovalSliderRow = rowId;
    private final int firstRemovalToggleRow = firstRemovalSliderRow + Slider.values().length;
    private final int firstPhaseToggleRow = firstRemovalToggleRow + Toggle.values().length;
    private final TextRemovalAnimationSettings removalSettings = NekoConfig.textRemovalAnimation;
    private int keyboardInset;

    @Override
    public View createView(Context context) {
        View root = super.createView(context);
        root.setFocusableInTouchMode(true);
        listView.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                View preview = recyclerView.getFocusedChild();
                if (dy > 0 && preview instanceof TextAnimationEditText && preview.getY() < recyclerView.getPaddingTop()) {
                    // Release focus before the preview scrolls behind the header.
                    // Android can pan the whole window to reveal a focused cursor.
                    stopPreviewInput(preview);
                }
            }
        });
        listView.addOnChildAttachStateChangeListener(new RecyclerView.OnChildAttachStateChangeListener() {
            @Override
            public void onChildViewAttachedToWindow(@NonNull View view) {}

            @Override
            public void onChildViewDetachedFromWindow(@NonNull View view) {
                if (view instanceof TextAnimationEditText && view.hasFocus()) stopPreviewInput(view);
            }
        });
        return root;
    }

    private void stopPreviewInput(View preview) {
        AndroidUtilities.hideKeyboard(preview);
        contentView.requestFocus();
    }

    @Override
    public WindowInsetsCompat onInsetsInternal(@NonNull View view, @NonNull WindowInsetsCompat insets) {
        keyboardInset = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
        return super.onInsetsInternal(view, insets);
    }

    @Override
    public void onInsets(int left, int top, int right, int bottom) {
        super.onInsets(left, top, right, Math.max(bottom, keyboardInset));
    }

    @Override
    protected void fillItems(ArrayList<UItem> items, UniversalAdapter adapter) {
        boolean animSupported = Build.VERSION.SDK_INT >= 26;
        items.add(UItem.asCheck(textAnimationRow, LocaleController.getString(R.string.TextAnimationToggle), animSupported ? null : LocaleController.getString(R.string.TextAnimationApiWarning)).slug("textAnimation").setChecked(NekoConfig.textAnimationEnabled).setEnabled(animSupported));
        if (NekoConfig.textAnimationEnabled && animSupported) {
            items.add(TextAnimationTestInputFactory.of(textAnimTestInputRow));
            Mode mode = removalSettings.mode();
            items.add(TextSettingsCellFactory.of(removalModeRow,
                    LocaleController.getString(R.string.TextRemovalAnimation),
                    LocaleController.getString(mode.title)).slug("textRemovalAnimation"));
            addPhaseToggle(items, Phase.APPEARANCE, mode == Mode.SLIDE
                    ? R.string.TextAnimationSkipSlideAppearance : Phase.APPEARANCE.title);
            if (mode == Mode.SLIDE) {
                addPhaseToggle(items, Phase.BLUR_APPEARANCE, Phase.BLUR_APPEARANCE.title);
            }
            addPhaseToggle(items, Phase.REMOVAL, Phase.REMOVAL.title);
            SeekbarConfig cursorConfig = new SeekbarConfig(
                    LocaleController.getString(R.string.TextAnimCursor),
                    "0", "100", 0, 100,
                    progress -> NekoConfig.setTextAnimCursorSpeed(Math.round(progress)));
            items.add(SeekbarCellFactory.of(textAnimCursorRow, cursorConfig, NekoConfig.textAnimCursorSpeed).slug("textAnimCursor"));

            if (mode != Mode.SLIDE || !removalSettings.isDisabled(Phase.BLUR_APPEARANCE)) {
                SeekbarConfig fadeConfig = new SeekbarConfig(
                        LocaleController.getString(R.string.TextAnimFadeDuration),
                        "50", "800", 50, 800, 5,
                        progress -> NekoConfig.setTextAnimFadeDuration(Math.round(progress / 5f) * 5));
                items.add(SeekbarCellFactory.of(textAnimFadeRow, fadeConfig, NekoConfig.textAnimFadeDuration).slug("textAnimFadeDuration"));

                SeekbarConfig blurConfig = new SeekbarConfig(
                        LocaleController.getString(R.string.TextAnimBlurStrength),
                        "0", "30", 0, 30,
                        progress -> NekoConfig.setTextAnimBlurStrength(Math.round(progress)));
                items.add(SeekbarCellFactory.of(textAnimBlurRow, blurConfig, NekoConfig.textAnimBlurStrength).slug("textAnimBlurStrength"));

                SeekbarConfig blurDurationConfig = new SeekbarConfig(
                        LocaleController.getString(R.string.TextAnimBlurDuration),
                        "50", "1000", 50, 1000, 5,
                        progress -> NekoConfig.setTextAnimBlurDuration(Math.round(progress / 5f) * 5));
                items.add(SeekbarCellFactory.of(textAnimBlurDurationRow, blurDurationConfig, NekoConfig.textAnimBlurDuration).slug("textAnimBlurDuration"));
            }

            switch (mode) {
                case EXPLOSION:
                    addSlider(items, Slider.FORCE);
                    addSlider(items, Slider.GRAVITY);
                    addSlider(items, Slider.BOUNCE);
                    addSlider(items, Slider.SPIN);
                    addSlider(items, Slider.SHARDS);
                    addSlider(items, Slider.DIRECTION);
                    addSlider(items, Slider.LIFETIME_MIN);
                    addSlider(items, Slider.LIFETIME_MAX);
                    addToggle(items, Toggle.FALL);
                    addToggle(items, Toggle.IGNORE_FLOOR);
                    break;
                case SLIDE:
                    addSlider(items, Slider.SLIDE_ANGLE);
                    addSlider(items, Slider.SLIDE_DISTANCE_MIN);
                    addSlider(items, Slider.SLIDE_DISTANCE_MAX);
                    addSlider(items, Slider.SLIDE_DURATION);
                    break;
            }
        }
    }

    private void addSlider(ArrayList<UItem> items, Slider slider) {
        SeekbarConfig config = new SeekbarConfig(LocaleController.getString(slider.title),
                String.valueOf(slider.min), String.valueOf(slider.max), slider.min, slider.max, slider.step,
                progress -> setSlider(slider, slider.min + Math.round((progress - slider.min) / slider.step) * slider.step));
        if (!slider.unit.isEmpty()) {
            String unit = "ms".equals(slider.unit) ? LocaleController.getString(R.string.TextRemovalMilliseconds) : slider.unit;
            config.valueFormatter = value -> value + " " + unit;
        }
        if (slider == Slider.DIRECTION) {
            config.setDescription(LocaleController.getString(R.string.TextRemovalDirectionHint));
        } else if (slider == Slider.SLIDE_ANGLE) {
            config.setDescription(LocaleController.getString(R.string.TextRemovalSlideAngleHint));
        }
        items.add(SeekbarCellFactory.of(firstRemovalSliderRow + slider.ordinal(), config, removalSettings.get(slider)));
    }

    private void setSlider(Slider slider, int value) {
        Slider other = slider.otherBound();
        int previousOther = other == null ? 0 : removalSettings.get(other);
        removalSettings.set(slider, value);
        if (other != null && previousOther != removalSettings.get(other)) {
            int id = firstRemovalSliderRow + other.ordinal();
            UItem item = listView.adapter.findItem(id);
            if (item != null) {
                item.intValue = removalSettings.get(other);
                notifyItemChanged(id);
            }
        }
    }

    private void addToggle(ArrayList<UItem> items, Toggle toggle) {
        items.add(UItem.asCheck(firstRemovalToggleRow + toggle.ordinal(), LocaleController.getString(toggle.title))
                .setChecked(removalSettings.isEnabled(toggle)));
    }

    private void addPhaseToggle(ArrayList<UItem> items, Phase phase, int title) {
        items.add(UItem.asCheck(firstPhaseToggleRow + phase.ordinal(), LocaleController.getString(title))
                .setChecked(removalSettings.isDisabled(phase)));
    }

    @Override
    protected void onItemClick(UItem item, View view, int position, float x, float y) {
        var id = item.id;
        if (id == textAnimTestInputRow) {
            if (view instanceof TextAnimationEditText) {
                view.requestFocus();
                AndroidUtilities.showKeyboard(view);
            }
        } else if (id == removalModeRow) {
            ItemOptions options = ItemOptions.makeOptions(this, view);
            for (Mode mode : Mode.values()) {
                options.addChecked(removalSettings.mode() == mode, LocaleController.getString(mode.title), () -> {
                    removalSettings.setMode(mode);
                    listView.adapter.update(true);
                });
            }
            options.show();
        } else if (id >= firstPhaseToggleRow && id < firstPhaseToggleRow + Phase.values().length) {
            Phase phase = Phase.values()[id - firstPhaseToggleRow];
            boolean disabled = !removalSettings.isDisabled(phase);
            removalSettings.setDisabled(phase, disabled);
            item.setChecked(disabled);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(disabled);
            }
            if (phase == Phase.BLUR_APPEARANCE) listView.adapter.update(true);
        } else if (id >= firstRemovalToggleRow && id < firstPhaseToggleRow) {
            Toggle toggle = Toggle.values()[id - firstRemovalToggleRow];
            boolean enabled = !removalSettings.isEnabled(toggle);
            removalSettings.setEnabled(toggle, enabled);
            if (view instanceof TextCheckCell) {
                ((TextCheckCell) view).setChecked(enabled);
            }
            listView.adapter.update(true);
        } else if (id == textAnimationRow) {
            if (Build.VERSION.SDK_INT >= 26) {
                NekoConfig.toggleTextAnimation();
                if (view instanceof TextCheckCell) {
                    ((TextCheckCell) view).setChecked(NekoConfig.textAnimationEnabled);
                }
                listView.adapter.update(true);
            }
        }
    }

    @Override
    protected String getActionBarTitle() {
        return LocaleController.getString(R.string.TextAnimation);
    }

    private static class TextAnimationTestInputFactory extends UItem.UItemFactory<TextAnimationEditText> {
        static {
            setup(new TextAnimationTestInputFactory());
        }

        @Override
        public TextAnimationEditText createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
            TextAnimationEditText editText = new TextAnimationEditText(context, resourcesProvider) {
                @Override
                public boolean onTouchEvent(MotionEvent event) {
                    if (event.getAction() == MotionEvent.ACTION_DOWN) {
                        getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    return super.onTouchEvent(event);
                }
            };
            editText.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            editText.setHint(LocaleController.getString(R.string.TextAnimationTestHint));
            editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            editText.setHintTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteHintText, resourcesProvider));
            editText.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            editText.setBackground(null);
            editText.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(12), AndroidUtilities.dp(16), AndroidUtilities.dp(12));
            editText.setSingleLine(false);
            editText.setMaxLines(3);
            editText.setMinHeight(AndroidUtilities.dp(48));
            editText.setFocusable(true);
            editText.setFocusableInTouchMode(true);
            return editText;
        }

        @Override
        public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
        }

        public static UItem of(int id) {
            var item = UItem.ofFactory(TextAnimationTestInputFactory.class);
            item.id = id;
            return item;
        }
    }
}
