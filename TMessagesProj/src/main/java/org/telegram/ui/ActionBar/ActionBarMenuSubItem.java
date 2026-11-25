package org.telegram.ui.ActionBar;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.FileLog;
import org.telegram.messenger.ImageLocation;
import org.telegram.messenger.LocaleController;
import org.telegram.ui.Components.AnimatedEmojiSpan;
import org.telegram.ui.Components.BackupImageView;
import org.telegram.ui.Components.CheckBox2;
import org.telegram.ui.Components.CubicBezierInterpolator;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RLottieImageView;

import java.util.HashSet;

import zxc.iconic.xenon.AlphaTextViewSwitcher;

public class ActionBarMenuSubItem extends FrameLayout implements Theme.Colorable {

    public AnimatedEmojiSpan.TextViewEmojis textView;
    public TextView subtextView;
    public AlphaTextViewSwitcher animatedSubtextView;
    public RLottieImageView imageView;
    public boolean checkViewLeft;
    public CheckBox2 checkView;
    private ImageView rightIcon;
    private BackupImageView backupImageView;

    private int textColor;
    private int iconColor;
    private PorterDuff.Mode iconColorMode;
    private int selectorColor;

    // theme keys the current colors came from (-1: custom color set by caller) and the colors they produced
    private int textColorKey = Theme.key_actionBarDefaultSubmenuItem, iconColorKey = Theme.key_actionBarDefaultSubmenuItemIcon, selectorColorKey = Theme.key_dialogButtonSelector;
    private int themedTextColor, themedIconColor, themedSelectorColor;
    private boolean iconColorSet; // imageView filter mode is SRC_IN until setIconColor applies one
    private android.graphics.ColorFilter appliedIconFilter;
    private Drawable appliedSelectorBackground;

    int selectorRad = 12;
    private boolean iosPopupStyle = zxc.iconic.xenon.NekoConfig.iosPopups;
    private org.telegram.ui.Components.MaterialPopupRowDrawable materialPopupBackground;
    private boolean materialPopupStyle = zxc.iconic.xenon.NekoConfig.material3Popups;
    private boolean customItemHeight;

    public void setIosPopupStyle(boolean enabled) {
        if (iosPopupStyle == enabled) return;
        iosPopupStyle = enabled;
        if (!customItemHeight) itemHeight = enabled ? 44 : materialPopupStyle ? 56 : 48;
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, enabled ? 17 : 16);
        requestLayout();
        updateBackground();
    }
    boolean top;
    boolean bottom;

    private int itemHeight = zxc.iconic.xenon.NekoConfig.iosPopups ? 44 : zxc.iconic.xenon.NekoConfig.material3Popups ? 56 : 48;
    protected final Theme.ResourcesProvider resourcesProvider;
    public Runnable openSwipeBackLayout;

    public ActionBarMenuSubItem(Context context, boolean top, boolean bottom) {
        this(context, false, top, bottom);
    }

    public ActionBarMenuSubItem(Context context, boolean needCheck, boolean top, boolean bottom) {
        this(context, needCheck ? 1 : 0, top, bottom, null);
    }

    public ActionBarMenuSubItem(Context context, boolean top, boolean bottom, Theme.ResourcesProvider resourcesProvider) {
        this(context, 0, top, bottom, resourcesProvider);
    }

    public ActionBarMenuSubItem(Context context, boolean needCheck, boolean top, boolean bottom, Theme.ResourcesProvider resourcesProvider) {
        this(context, needCheck ? 1 : 0, top, bottom, resourcesProvider);
    }

    public ActionBarMenuSubItem(Context context, int needCheck, boolean top, boolean bottom, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;

        this.top = top;
        this.bottom = bottom;

        textColor = getThemedColor(Theme.key_actionBarDefaultSubmenuItem);
        iconColor = getThemedColor(Theme.key_actionBarDefaultSubmenuItemIcon);
        iconColorMode = PorterDuff.Mode.MULTIPLY;
        selectorColor = getThemedColor(Theme.key_dialogButtonSelector);
        themedTextColor = textColor;
        themedIconColor = iconColor;
        themedSelectorColor = selectorColor;

        updateBackground();
        setPadding(dp(18), 0, dp(18), 0);

        imageView = new RLottieImageView(context);
        imageView.setScaleType(ImageView.ScaleType.CENTER);
        imageView.setColorFilter(appliedIconFilter = new PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN));
        addView(imageView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, 40, Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT)));

        textView = new AnimatedEmojiSpan.TextViewEmojis(context);
        textView.setLines(1);
        textView.setSingleLine(true);
        textView.setGravity(Gravity.LEFT);
        textView.setEllipsize(TextUtils.TruncateAt.END);
        textView.setTextColor(textColor);
        textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
        if (zxc.iconic.xenon.helpers.TypefaceHelper.supportsRoundedTypeface()) textView.setTypeface(zxc.iconic.xenon.helpers.TypefaceHelper.createRoundedTypeface(500, false));
        addView(textView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL));

        checkViewLeft = LocaleController.isRTL;
        makeCheckView(needCheck);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        zxc.iconic.xenon.helpers.RoundedUiFonts.observe(this);
    }

    public void makeCheckView(int needCheck) {
        if (needCheck > 0) {
            if (needCheck == 3) {
                checkView = new CheckBox2(getContext(), 20, resourcesProvider);
                checkView.setColor(Theme.key_telegram_color, Theme.key_dialogCheckboxSquareDisabled, Theme.key_checkboxCheck);
                checkView.setDrawBackgroundAsArc(10);
            } else {
                checkView = new CheckBox2(getContext(), 26, resourcesProvider);
                checkView.setDrawUnchecked(false);
                checkView.setColor(-1, -1, Theme.key_actionBarDefaultSubmenuItem);
                checkView.setDrawBackgroundAsArc(-1);
            }
            if (needCheck == 1) {
                checkViewLeft = !LocaleController.isRTL;
                addView(checkView, LayoutHelper.createFrame(26, LayoutHelper.MATCH_PARENT, Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT)));
                textView.setPadding(!LocaleController.isRTL ? dp(34) : 0, 0, !LocaleController.isRTL ? 0 : dp(34), 0);
            } else {
                addView(checkView, LayoutHelper.createFrame(26, LayoutHelper.MATCH_PARENT, Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT)));
                textView.setPadding(LocaleController.isRTL ? dp(34) : 0, 0, LocaleController.isRTL ? 0 : dp(34), 0);
            }
        }
    }

    public void setEmojiCacheType(int cacheType) {
        textView.setCacheType(cacheType);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (iosPopupStyle) {
            setPadding(dp(12), 0, dp(12), 0);
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 17);
            boolean hasIcon = imageView.getVisibility() == VISIBLE && imageView.getDrawable() != null;
            int start = hasIcon ? dp(40) : 0;
            int end = 0;
            if (checkView != null && checkView.getVisibility() == VISIBLE) {
                if (checkViewLeft != LocaleController.isRTL) start = dp(40);
                else end = dp(28);
            }
            textView.setPadding(LocaleController.isRTL ? end : start, 0, LocaleController.isRTL ? start : end, 0);
            if (rightIcon != null) rightIcon.setBackground(null);
            FrameLayout.LayoutParams iconParams=(FrameLayout.LayoutParams)imageView.getLayoutParams();
            iconParams.width=dp(28); iconParams.height=dp(32);
            imageView.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        }
        super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(dp(itemHeight), View.MeasureSpec.EXACTLY));
        if (expandIfMultiline && textView.getLayout().getLineCount() > 1) {
            super.onMeasure(widthMeasureSpec, View.MeasureSpec.makeMeasureSpec(dp(itemHeight + 8), View.MeasureSpec.EXACTLY));
        }
    }

    public void setItemHeight(int itemHeight) {
        customItemHeight = true;
        this.itemHeight = itemHeight;
    }

    public void setChecked(boolean checked) {
        if (checkView == null) {
            return;
        }
        checkView.setChecked(checked, true);
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setEnabled(isEnabled());
        if (checkView != null && checkView.isChecked()) {
            info.setCheckable(true);
            info.setChecked(checkView.isChecked());
            info.setClassName("android.widget.CheckBox");
        }
    }

    public void setCheckColor(int colorKey) {
        checkView.setColor(-1, -1, colorKey);
    }

    public void setRightIcon(int icon) {
        setRightIcon(icon, null);
    }

    public void setRightIcon(int icon, OnClickListener listener) {
        if (rightIcon == null) {
            rightIcon = new ImageView(getContext());
            rightIcon.setScaleType(ImageView.ScaleType.CENTER);
            rightIcon.setColorFilter(iconColor, PorterDuff.Mode.MULTIPLY);
            if (LocaleController.isRTL) {
                rightIcon.setScaleX(-1);
            }
            addView(rightIcon, LayoutHelper.createFrame(24, LayoutHelper.MATCH_PARENT, Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT)));
        }
        FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) textView.getLayoutParams();
        if (LocaleController.isRTL) {
            layoutParams.leftMargin = rightIcon != null ? dp(32) : 0;
        } else {
            layoutParams.rightMargin = rightIcon != null ? dp(32) : 0;
        }
        textView.setLayoutParams(layoutParams);
        setPadding(dp(LocaleController.isRTL ? listener != null ? 0 : 8 : 18), 0, dp(LocaleController.isRTL ? 18 : listener != null ? 0 : 8), 0);
        if (icon == 0) {
            rightIcon.setVisibility(View.GONE);
        } else {
            rightIcon.setVisibility(View.VISIBLE);
            rightIcon.setImageResource(icon);
            if (listener != null) {
                rightIcon.getLayoutParams().width = dp(40);
                rightIcon.setOnClickListener(listener);
                rightIcon.setBackground(Theme.createRadSelectorDrawable(selectorColor, 6, 0, 0, 6));
            }
        }
    }

    public void setTextAndIcon(CharSequence text, int icon) {
        setTextAndIcon(text, icon, null);
    }

    boolean expandIfMultiline;

    public void setMultiline() {
        setMultiline(true);
    }

    public void setMultiline(boolean changeSize) {
        textView.setLines(2);
        if (changeSize) {
            textView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14);
        } else {
            expandIfMultiline = true;
        }
        textView.setSingleLine(false);
        textView.setGravity(Gravity.CENTER_VERTICAL);
    }

    public void setTextAndIcon(CharSequence text, int icon, Drawable iconDrawable) {
        textView.setText(text);
        if (icon != 0 || iconDrawable != null || checkView != null) {
            if (iconDrawable != null) {
                iconResId = 0;
                imageView.setImageDrawable(iconDrawable);
            } else {
                iconResId = icon;
                imageView.setImageResource(icon);
            }
            imageView.setVisibility(VISIBLE);
            textView.setPadding(checkViewLeft ? (checkView != null ? dp(43) : 0) : dp(icon != 0 || iconDrawable != null ? 43 : 0), 0, checkViewLeft ? dp(icon != 0 || iconDrawable != null ? 43 : 0) : (checkView != null ? dp(43) : 0), 0);
        } else {
            iconResId = 0;
            imageView.setVisibility(INVISIBLE);
            textView.setPadding(0, 0, 0, 0);
        }
    }

    public void setTextAndIcon(CharSequence text, ImageLocation imageLocation, String imageFilter, Drawable thumb, Object parentObject) {
        textView.setText(text);
        textView.setPadding(checkViewLeft ? (checkView != null ? dp(43) : 0) : dp(43), 0, checkViewLeft ? dp(43) : (checkView != null ? dp(43) : 0), 0);
        if (backupImageView == null) {
            backupImageView = new BackupImageView(getContext());
            backupImageView.setRoundRadius(dp(5));
            addView(backupImageView, LayoutHelper.createFrame(28, 28, Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT)));
        }
        imageView.setVisibility(INVISIBLE);
        backupImageView.setImage(imageLocation, imageFilter, thumb, parentObject);
    }


    public void setIconColorImage(int iconColor) {
        if (backupImageView != null) {
            backupImageView.setColorFilter(new PorterDuffColorFilter(iconColor, PorterDuff.Mode.SRC_IN));
        }
    }

    public void setImageSize(int widthDp, int heightDp) {
        if (backupImageView != null) {
            backupImageView.setLayoutParams(LayoutHelper.createFrame(widthDp, heightDp, Gravity.CENTER_VERTICAL | (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT)));
        }
    }


    public ActionBarMenuSubItem setColors(int textColor, int iconColor) {
        setTextColor(textColor);
        setIconColor(iconColor);
        return this;
    }

    /** same as setColors(getThemedColor(textKey), getThemedColor(iconKey)), but follows theme changes */
    public ActionBarMenuSubItem setColorKeys(int textKey, int iconKey) {
        setColors(getThemedColor(textKey), getThemedColor(iconKey));
        textColorKey = textKey;
        iconColorKey = iconKey;
        themedTextColor = textColor;
        themedIconColor = iconColor;
        return this;
    }

    public void setTextColor(int textColor) {
        if (this.textColor != textColor) {
            textView.setTextColor(this.textColor = textColor);
        }
    }

    public void setIconColor(int iconColor) {
        setIconColor(iconColor, PorterDuff.Mode.SRC_IN);
    }

    public void setIconColor(int iconColor, PorterDuff.Mode mode) {
        if (this.iconColor != iconColor || this.iconColorMode != mode) {
            imageView.setColorFilter(appliedIconFilter = new PorterDuffColorFilter(this.iconColor = iconColor, this.iconColorMode = mode));
            iconColorSet = true;
        }
    }

    private ValueAnimator enabledAnimator;
    private boolean enabled;
    public void setEnabledByColor(boolean enabled, int colorDisabled, int colorEnabled) {
        if (enabledAnimator != null) {
            enabledAnimator.cancel();
        }
        enabledAnimator = ValueAnimator.ofFloat(this.enabled ? 1.0f : 0.0f, enabled ? 1.0f : 0.0f);
        this.enabled = enabled;
        enabledAnimator.addUpdateListener(anm -> {
            final float t = (float) anm.getAnimatedValue();
            setTextColor(ColorUtils.blendARGB(colorDisabled, colorEnabled, t));
            setIconColor(ColorUtils.blendARGB(colorDisabled, colorEnabled, t));
        });
        enabledAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                final float t = enabled ? 1.0f : 0.0f;
                setTextColor(ColorUtils.blendARGB(colorDisabled, colorEnabled, t));
                setIconColor(ColorUtils.blendARGB(colorDisabled, colorEnabled, t));
            }
        });
        enabledAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        enabledAnimator.start();
    }

    public void setEnabledByColor(boolean enabled, int textColorDisabled, int iconColorDisabled, int colorEnabled) {
        if (enabledAnimator != null) {
            enabledAnimator.cancel();
        }
        enabledAnimator = ValueAnimator.ofFloat(this.enabled ? 1.0f : 0.0f, enabled ? 1.0f : 0.0f);
        this.enabled = enabled;
        enabledAnimator.addUpdateListener(anm -> {
            final float t = (float) anm.getAnimatedValue();
            setTextColor(ColorUtils.blendARGB(textColorDisabled, colorEnabled, t));
            setIconColor(ColorUtils.blendARGB(iconColorDisabled, colorEnabled, t));
        });
        enabledAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                final float t = enabled ? 1.0f : 0.0f;
                setTextColor(ColorUtils.blendARGB(textColorDisabled, colorEnabled, t));
                setIconColor(ColorUtils.blendARGB(iconColorDisabled, colorEnabled, t));
            }
        });
        enabledAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        enabledAnimator.start();
    }

    private int iconResId;
    public int getIconResId() {
        return iconResId;
    }

    public void setIcon(int resId) {
        imageView.setImageResource(iconResId = resId);
    }

    public void setIcon(Drawable drawable) {
        iconResId = 0;
        imageView.setImageDrawable(drawable);
    }

    public void setAnimatedIcon(int resId) {
        iconResId = 0;
        imageView.setAnimation(resId, 24, 24);
    }

    public void onItemShown() {
        if (imageView.getAnimatedDrawable() != null) {
            imageView.getAnimatedDrawable().start();
        }
    }

    public void setText(CharSequence text) {
        textView.setText(text);
    }

    public void setSubtextColor(int color) {
        if (subtextView != null) {
            // the caller owns the subtext color now: drop the default key binding
            ThemeBind.clear(subtextView);
            subtextView.setTextColor(color);
        }
    }

    public void setSubtext(CharSequence text) {
        if (subtextView == null) {
            subtextView = new TextView(getContext());
            subtextView.setLines(1);
            subtextView.setSingleLine(true);
            subtextView.setGravity(Gravity.LEFT);
            subtextView.setEllipsize(TextUtils.TruncateAt.END);
            ThemeBind.textColor(subtextView, Theme.key_groupcreate_sectionText, resourcesProvider);
            subtextView.setVisibility(GONE);
            subtextView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            if (imageView.getVisibility() == VISIBLE) subtextView.setPadding(LocaleController.isRTL ? 0 : dp(43), 0, LocaleController.isRTL ? dp(43) : 0, 0);
            addView(subtextView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL, 0, 10, 0, 0));
        }
        boolean visible = !TextUtils.isEmpty(text);
        boolean oldVisible = subtextView.getVisibility() == VISIBLE;
        if (visible != oldVisible) {
            subtextView.setVisibility(visible ? VISIBLE : GONE);
            FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) textView.getLayoutParams();
            layoutParams.bottomMargin = visible ? dp(10) : 0;
            textView.setLayoutParams(layoutParams);
        }
        subtextView.setText(text);
    }

    public void setAnimatedSubtextColorKey(int colorKey) {
        if (animatedSubtextView != null) {
            animatedSubtextView.setTextColor(getThemedColor(colorKey));
        }
    }

    public void setAnimatedSubtext(CharSequence text, boolean animated) {
        if (animatedSubtextView == null) {
            animatedSubtextView = new AlphaTextViewSwitcher(getContext());
            animatedSubtextView.setFactory(() -> {
                var view = new TextView(getContext());
                view.setLines(1);
                view.setSingleLine(true);
                view.setGravity(Gravity.LEFT);
                view.setEllipsize(TextUtils.TruncateAt.END);
                view.setTextColor(getThemedColor(Theme.key_groupcreate_sectionText));
                view.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
                return view;
            });
            animatedSubtextView.setVisibility(GONE);
            if (imageView.getVisibility() == VISIBLE) animatedSubtextView.setPadding(LocaleController.isRTL ? 0 : dp(43), 0, LocaleController.isRTL ? dp(43) : 0, 0);
            addView(animatedSubtextView, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL, 0, 10, 0, 0));
        }
        boolean visible = !TextUtils.isEmpty(text);
        boolean oldVisible = animatedSubtextView.getVisibility() == VISIBLE;
        if (visible != oldVisible) {
            animatedSubtextView.setVisibility(visible ? VISIBLE : GONE);
            FrameLayout.LayoutParams layoutParams = (FrameLayout.LayoutParams) textView.getLayoutParams();
            layoutParams.bottomMargin = visible ? dp(10) : 0;
            textView.setLayoutParams(layoutParams);
        }
        animatedSubtextView.setText(text, animated);
    }

    public AnimatedEmojiSpan.TextViewEmojis getTextView() {
        return textView;
    }

    public ImageView getImageView() {
        return imageView;
    }

    private float selectorAlpha = 1f;

    /** same as setSelectorColor(Theme.multAlpha(getThemedColor(key), alpha)), but follows theme changes */
    public void setSelectorColorKey(int key, float alpha) {
        selectorAlpha = alpha;
        setSelectorColor(Theme.multAlpha(getThemedColor(key), alpha));
        selectorColorKey = key;
        themedSelectorColor = selectorColor;
    }

    public void setSelectorColor(int selectorColor) {
        if (this.selectorColor != selectorColor) {
            this.selectorColor = selectorColor;
            if (appliedSelectorBackground != null && getBackground() == appliedSelectorBackground) {
                Theme.setSelectorDrawableColor(appliedSelectorBackground, selectorColor, true);
            } else {
                updateBackground();
            }
        }
    }

    public void updateSelectorBackground(boolean top, boolean bottom) {
        if (this.top == top && this.bottom == bottom) {
            return;
        }
        this.top = top;
        this.bottom = bottom;
        updateBackground();
    }

    public void updateSelectorBackground(boolean top, boolean bottom, int selectorRad) {
        if (this.top == top && this.bottom == bottom && this.selectorRad == selectorRad) {
            return;
        }
        this.top = top;
        this.bottom = bottom;
        this.selectorRad = selectorRad;
        updateBackground();
    }

    public void refreshPopupStyle() {
        boolean material = zxc.iconic.xenon.NekoConfig.material3Popups;
        if (materialPopupStyle == material) return;
        materialPopupStyle = material;
        if (!customItemHeight) itemHeight = iosPopupStyle ? 44 : material ? 56 : 48;
        updateBackground();
        requestLayout();
    }

    public void updateBackground() {
        if (!iosPopupStyle && zxc.iconic.xenon.NekoConfig.material3Popups) {
            if (materialPopupBackground == null) {
                materialPopupBackground = new org.telegram.ui.Components.MaterialPopupRowDrawable(
                        getThemedColor(Theme.key_actionBarDefaultSubmenuBackground), top, bottom);
            }
            materialPopupBackground.setCorners(top, bottom);
            if (getBackground() != materialPopupBackground) setBackground(materialPopupBackground);
            return;
        }
        setBackground(iosPopupStyle
                ? null
                : (appliedSelectorBackground = Theme.createRadSelectorDrawable(selectorColor, top ? selectorRad : 0, bottom ? selectorRad : 0)));
    }

    private int getThemedColor(int key) {
        return Theme.getColor(key, resourcesProvider);
    }

    public CheckBox2 getCheckView() {
        return checkView;
    }

    public void openSwipeBack() {
        if (openSwipeBackLayout != null) {
            openSwipeBackLayout.run();
        }
    }

    public ImageView getRightIcon() {
        return rightIcon;
    }

    @Override
    public void updateColors() {
        try {
            if (textColorKey >= 0 && textColor == themedTextColor && textView != null && textView.getCurrentTextColor() == textColor) {
                final int color = getThemedColor(textColorKey);
                setTextColor(color);
                themedTextColor = color;
            }
            if (iconColorKey >= 0 && iconColor == themedIconColor && imageView != null && imageView.getColorFilter() == appliedIconFilter) {
                final int color = getThemedColor(iconColorKey);
                if (color != iconColor) {
                    imageView.setColorFilter(appliedIconFilter = new PorterDuffColorFilter(iconColor = color, iconColorSet && iconColorMode != null ? iconColorMode : PorterDuff.Mode.SRC_IN));
                    if (rightIcon != null) {
                        rightIcon.setColorFilter(color, PorterDuff.Mode.MULTIPLY);
                    }
                }
                themedIconColor = color;
            }
            if (selectorColorKey >= 0 && selectorColor == themedSelectorColor) {
                final int color = Theme.multAlpha(getThemedColor(selectorColorKey), selectorAlpha);
                if (color != selectorColor) {
                    selectorColor = color;
                    final Drawable background = getBackground();
                    if (background == appliedSelectorBackground && background instanceof android.graphics.drawable.RippleDrawable) {
                        ((android.graphics.drawable.RippleDrawable) background).setColor(android.content.res.ColorStateList.valueOf(color));
                    }
                }
                themedSelectorColor = color;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override
    public void addColorKeys(HashSet<Integer> colors) {
        if (textColorKey >= 0) colors.add(textColorKey);
        if (iconColorKey >= 0) colors.add(iconColorKey);
        if (selectorColorKey >= 0) colors.add(selectorColorKey);
    }
}
