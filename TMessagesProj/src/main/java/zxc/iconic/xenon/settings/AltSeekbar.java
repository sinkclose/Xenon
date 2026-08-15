package zxc.iconic.xenon.settings;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import com.google.android.material.slider.Slider;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.AnimatedTextView;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.EditTextBoldCursor;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.SeekBarView;

import zxc.iconic.xenon.NekoConfig;

/**
 * Reusable integer slider cell styled like the system PowerSaverSlider:
 * header text + animated current value badge, a SeekBarView, and
 * left/right anchor labels under the bar.
 *
 * The cell measures to a fixed 112dp height. Integer-rounded value is shown
 * in the header badge; the {@link OnDrag} callback receives the live float
 * progress on every change.
 */
@SuppressLint("ViewConstructor")
public class AltSeekbar extends FrameLayout {

    public interface OnDrag {
        void run(float progress);
    }

    private final AnimatedTextView headerValue;
    private final TextView leftTextView;
    private final TextView rightTextView;
    private SeekBarView seekBarView;
    private Slider slider;
    private final Theme.ResourcesProvider resourcesProvider;
    private final OnDrag onDrag;
    private java.util.function.Function<Integer, String> valueFormatter;

    private final int min, max;
    private int step = 1;
    private float currentValue;
    private int roundedValue;
    private int defaultValue;
    private int subtitleOffset = 0;

    public AltSeekbar(Context context, OnDrag onDrag, int min, int max, String title, String left, String right, Theme.ResourcesProvider resourcesProvider) {
        this(context, onDrag, min, max, title, left, right, resourcesProvider, null);
    }

    public AltSeekbar(Context context, OnDrag onDrag, int min, int max, String title, String left, String right, Theme.ResourcesProvider resourcesProvider, String subtitle) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        this.onDrag = onDrag;

        this.max = max;
        this.min = min;
        defaultValue = min;

        int offset = subtitle != null ? 20 : 0;
        this.subtitleOffset = offset;

        LinearLayout headerLayout = new LinearLayout(context);
        headerLayout.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);

        TextView headerTextView = new TextView(context);
        headerTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15);
        headerTextView.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM));
        headerTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, resourcesProvider));
        headerTextView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
        headerTextView.setText(title);
        headerLayout.addView(headerTextView, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.CENTER_VERTICAL));

        headerValue = new AnimatedTextView(context, false, true, true) {
            final Drawable backgroundDrawable = Theme.createRoundRectDrawable(AndroidUtilities.dp(4), Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, resourcesProvider), 0.15f));

            @Override
            protected void onDraw(Canvas canvas) {
                backgroundDrawable.setBounds(0, 0, (int) (getPaddingLeft() + getDrawable().getCurrentWidth() + getPaddingRight()), getMeasuredHeight());
                backgroundDrawable.draw(canvas);

                super.onDraw(canvas);
            }
        };
        headerValue.setAnimationProperties(.45f, 0, 240, CubicBezierInterpolator.EASE_OUT_QUINT);
        headerValue.setTypeface(AndroidUtilities.getTypeface(AndroidUtilities.TYPEFACE_ROBOTO_MEDIUM));
        headerValue.setPadding(AndroidUtilities.dp(5.33f), AndroidUtilities.dp(2), AndroidUtilities.dp(5.33f), AndroidUtilities.dp(2));
        headerValue.setTextSize(AndroidUtilities.dp(12));
        headerValue.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader, resourcesProvider));
        headerValue.setClickable(true);
        headerValue.setOnClickListener(v -> showInputDialog(context, title));
        headerLayout.addView(headerValue, LayoutHelper.createLinear(LayoutHelper.WRAP_CONTENT, 17, Gravity.CENTER_VERTICAL, 6, 1, 0, 0));

        addView(headerLayout, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.FILL_HORIZONTAL, 21, 17, 21, 0));

if (subtitle != null) {
            TextView subtitleView = new TextView(context);
            subtitleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            subtitleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            subtitleView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            subtitleView.setText(subtitle);
            addView(subtitleView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.FILL_HORIZONTAL, 21, 40, 21, 0));
        }

        FrameLayout valuesView = new FrameLayout(context);

        leftTextView = new TextView(context);
        leftTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        leftTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        leftTextView.setGravity(Gravity.LEFT);
        leftTextView.setText(left);
        valuesView.addView(leftTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.LEFT | Gravity.CENTER_VERTICAL));

        rightTextView = new TextView(context);
        rightTextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
        rightTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        rightTextView.setGravity(Gravity.RIGHT);
        rightTextView.setText(right);
        valuesView.addView(rightTextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, Gravity.RIGHT | Gravity.CENTER_VERTICAL));

        addView(valuesView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, Gravity.TOP | Gravity.FILL_HORIZONTAL, 21, 52 + subtitleOffset, 21, 0));

        initSlider();

        roundedValue = min;
        updateText();
    }

    private void initSlider() {
        if (NekoConfig.materialSliders) {
            Slider materialSlider = MaterialSliderUiHelper.create(getContext());
            this.slider = materialSlider;
            MaterialSliderUiHelper.applyContinuousStyle(materialSlider);
            MaterialSliderUiHelper.applyThemeColors(materialSlider);
            materialSlider.setValueFrom(min);
            materialSlider.setValueTo(max);
            if (step > 1) {
                materialSlider.setStepSize(step);
            }
            materialSlider.addOnChangeListener((slider, value, fromUser) -> {
                currentValue = value;
                if (fromUser) {
                    onDrag.run(value);
                }
                int newRounded = step > 1 ? Math.round(value / step) * step : Math.round(value);
                if (newRounded != roundedValue) {
                    roundedValue = newRounded;
                    updateText();
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                }
            });
            addView(materialSlider, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 44, Gravity.TOP, 6, 68 + subtitleOffset, 6, 0));
        } else {
            SeekBarView bar = new SeekBarView(getContext(), true, resourcesProvider);
            this.seekBarView = bar;
            bar.setReportChanges(true);
            bar.setDelegate((stop, progress) -> {
                currentValue = min + (max - min) * progress;
                onDrag.run(currentValue);
                int newRounded = step > 1 ? Math.round(currentValue / step) * step : Math.round(currentValue);
                if (newRounded != roundedValue) {
                    roundedValue = newRounded;
                    updateText();
                    performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP, HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING);
                }
            });
            addView(bar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 38 + 6, Gravity.TOP, 6, 68 + subtitleOffset, 6, 0));
        }
    }

    public void setDefaultValue(int value) {
        defaultValue = value;
    }

    public void setStep(int step) {
        this.step = step;
    }

    private void showInputDialog(Context context, String title) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context, resourcesProvider);
        builder.setTitle(title);

        LinearLayout container = new LinearLayout(context);
        container.setOrientation(LinearLayout.VERTICAL);
        container.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(16), AndroidUtilities.dp(24), 0);

        EditTextBoldCursor editText = new EditTextBoldCursor(context);
        editText.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        editText.setTextColor(Theme.getColor(Theme.key_dialogTextBlack, resourcesProvider));
        editText.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED);
        editText.setText(String.valueOf(roundedValue));
        editText.setSelection(editText.getText().length());

        container.addView(editText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT));
        builder.setView(container);

        builder.setPositiveButton(LocaleController.getString("OK", R.string.OK), (dialog, which) -> {
            try {
                int parsed = Integer.parseInt(editText.getText().toString());
                parsed = Math.max(min, Math.min(max, parsed));
                setValue(parsed);
                if (onDrag != null) {
                    onDrag.run(parsed);
                }
            } catch (Exception ignore) { }
        });

        builder.setNegativeButton(LocaleController.getString("Cancel", R.string.Cancel), null);
        AlertDialog dialog = builder.show();

        editText.requestFocus();
        AndroidUtilities.showKeyboard(editText);
    }

    private void updateValues() {
        int middle = (max - min) / 2 + min;
        if (currentValue >= middle * 1.5f - min * 0.5f) {
            rightTextView.setTextColor(ColorUtils.blendARGB(
                    Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider),
                    Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider),
                    (currentValue - (middle * 1.5f - min * 0.5f)) / (max - (middle * 1.5f - min * 0.5f))
            ));
            leftTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        } else if (currentValue <= (middle + min) * 0.5f) {
            leftTextView.setTextColor(ColorUtils.blendARGB(
                    Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider),
                    Theme.getColor(Theme.key_windowBackgroundWhiteBlueText, resourcesProvider),
                    (currentValue - (middle + min) * 0.5f) / (min - (middle + min) * 0.5f)
            ));
            rightTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        } else {
            leftTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
            rightTextView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteGrayText, resourcesProvider));
        }
    }

    public void setValue(float value) {
        currentValue = value;
        if (slider != null) {
            slider.setValue(value);
        } else if (seekBarView != null) {
            seekBarView.setProgress((value - min) / (float) (max - min));
        }
        int newRounded = step > 1 ? Math.round(currentValue / step) * step : Math.round(currentValue);
        if (newRounded != roundedValue) {
            roundedValue = newRounded;
            updateText();
        }
    }

    public void setValueFormatter(java.util.function.Function<Integer, String> formatter) {
        this.valueFormatter = formatter;
    }

    private void updateText() {
        headerValue.cancelAnimation();
        headerValue.setText(getTextForHeader(), true);
        updateValues();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(
                MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(112 + subtitleOffset), MeasureSpec.EXACTLY)
        );
    }

    public CharSequence getTextForHeader() {
        CharSequence text;
        if (roundedValue == min) {
            text = leftTextView.getText();
        } else if (roundedValue == max) {
            text = rightTextView.getText();
        } else if (valueFormatter != null) {
            text = valueFormatter.apply(roundedValue);
        } else {
            text = String.valueOf(roundedValue);
        }
        return text.toString().toUpperCase();
    }

    public void updateStyle() {
        if (slider != null) {
            removeView(slider);
            slider = null;
        }
        if (seekBarView != null) {
            removeView(seekBarView);
            seekBarView = null;
        }
        initSlider();
        setValue(currentValue);
    }
}
