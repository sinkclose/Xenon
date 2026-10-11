/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.PorterDuffXfermode;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.util.StateSet;
import android.view.View;
import android.view.ViewParent;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.Keep;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.BaseCell;

import me.vkryl.android.animator.BoolAnimator;

public class Switch extends View {
    private final BoolAnimator animatorIconVisibility = new BoolAnimator(this, CubicBezierInterpolator.EASE_OUT_QUINT, 380L, true);

    private RectF rectF;
    private zxc.iconic.xenon.helpers.IosSwitchHelper iosSwitchHelper;
    private Runnable iosToggleAction;
    private int renderedStyle = switchStyle();

    private static int switchStyle() {
        return zxc.iconic.xenon.NekoConfig.iosSwitches ? 2 : zxc.iconic.xenon.NekoConfig.material3Switches ? 1 : 0;
    }

    /** Diff updates can leave unchanged cells' display lists and measurements intact. */
    public static void invalidateAllSwitches(View root) {
        if (root instanceof Switch) ((Switch) root).refreshStyle();
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) invalidateAllSwitches(group.getChildAt(i));
        }
    }

    private void refreshStyle() {
        int style = switchStyle();
        if (renderedStyle != style) {
            renderedStyle = style;
            cancelCheckAnimator();
            if (iosSwitchHelper != null) iosSwitchHelper.release();
            iosPointerId = -1;
            iosDragging = false;
            if (iosGestureParent != null) iosGestureParent.requestDisallowInterceptTouchEvent(false);
            iosGestureParent = null;
            drawRipple = false;
            setProgress(isChecked ? 1f : 0f);
        }
        requestLayout();
        invalidate();
    }

    private int iosPointerId = -1;
    private ViewParent iosGestureParent;
    private float iosDownX, iosDownY, iosStartFraction, iosDragFraction;
    private boolean iosDragging, iosGestureCommit;

    private zxc.iconic.xenon.helpers.IosSwitchHelper iosHelper() {
        if (iosSwitchHelper == null) iosSwitchHelper = new zxc.iconic.xenon.helpers.IosSwitchHelper(this);
        return iosSwitchHelper;
    }

    /** Uses the same settings callback for a switch tap, drag and accessibility click. */
    public void setIosToggleAction(Runnable action) {
        iosToggleAction = action;
    }

    @Override
    public boolean isClickable() {
        return super.isClickable() || zxc.iconic.xenon.NekoConfig.iosSwitches && isEnabled();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        if (zxc.iconic.xenon.NekoConfig.iosSwitches) {
            setMeasuredDimension(AndroidUtilities.dp(zxc.iconic.xenon.helpers.IosSwitchDynamics.VIEW_WIDTH),
                    AndroidUtilities.dp(zxc.iconic.xenon.helpers.IosSwitchDynamics.VIEW_HEIGHT));
        } else {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        }
    }

    @Override
    public boolean performClick() {
        if (!zxc.iconic.xenon.NekoConfig.iosSwitches) return super.performClick();
        if (!isEnabled()) return false;
        if (super.performClick()) return true;
        if (iosToggleAction != null) {
            iosToggleAction.run();
            return true;
        }
        View row = this;
        ViewParent parent = getParent();
        while (parent instanceof View) {
            if (parent instanceof RecyclerListView) {
                RecyclerListView list = (RecyclerListView) parent;
                int position = list.getChildAdapterPosition(row);
                if (position >= 0) {
                    list.clickItem(row, position);
                    return true;
                }
                return false;
            }
            row = (View) parent;
            if (row.hasOnClickListeners()) return row.performClick();
            parent = row.getParent();
        }
        setChecked(!isChecked, true);
        return true;
    }

    private void cancelIosGesture() {
        iosPointerId = -1;
        iosDragging = false;
        iosHelper().setChecked(isChecked, true, true);
        iosHelper().releasePress();
        if (iosGestureParent != null) iosGestureParent.requestDisallowInterceptTouchEvent(false);
        iosGestureParent = null;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!zxc.iconic.xenon.NekoConfig.iosSwitches) return super.onTouchEvent(event);
        if (!isEnabled()) { if (iosPointerId != -1) cancelIosGesture(); return false; }
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                iosPointerId = event.getPointerId(0);
                iosGestureParent = getParent();
                iosDownX = event.getX();
                iosDownY = event.getY();
                iosStartFraction = iosDragFraction = isChecked ? 1f : 0f;
                iosDragging = false;
                cancelCheckAnimator();
                iosHelper().press();
                return true;
            case MotionEvent.ACTION_MOVE:
                int index = event.findPointerIndex(iosPointerId);
                if (index < 0) { cancelIosGesture(); return false; }
                float dx = event.getX(index) - iosDownX, dy = event.getY(index) - iosDownY;
                if (!iosDragging && Math.abs(dy) > ViewConfiguration.get(getContext()).getScaledTouchSlop()
                        && Math.abs(dy) > Math.abs(dx)) {
                    cancelIosGesture();
                    return false;
                }
                if (!iosDragging && Math.abs(dx) > AndroidUtilities.dp(2f) && Math.abs(dx) > Math.abs(dy)) {
                    iosDragging = true;
                    if (iosGestureParent != null) iosGestureParent.requestDisallowInterceptTouchEvent(true);
                }
                if (iosDragging) {
                    iosDragFraction = zxc.iconic.xenon.helpers.IosSwitchDynamics.dragFraction(
                            iosStartFraction, dx, AndroidUtilities.density, org.telegram.messenger.LocaleController.isRTL);
                    iosHelper().dragTo(iosDragFraction);
                }
                return true;
            case MotionEvent.ACTION_UP:
                if (iosPointerId == -1) return false;
                boolean checked = zxc.iconic.xenon.helpers.IosSwitchDynamics.releaseChecked(
                        isChecked, iosDragging, iosDragFraction);
                iosPointerId = -1;
                iosGestureCommit = true;
                try {
                    if (checked != isChecked) performClick();
                    // Callbacks may reject a change (locked options): settle to the actual setting.
                    iosHelper().setChecked(isChecked, true, true);
                    iosHelper().releasePress();
                } finally {
                    iosGestureCommit = false;
                    iosDragging = false;
                    if (iosGestureParent != null) iosGestureParent.requestDisallowInterceptTouchEvent(false);
                    iosGestureParent = null;
                }
                return true;
            case MotionEvent.ACTION_POINTER_DOWN:
            case MotionEvent.ACTION_CANCEL:
                if (iosPointerId != -1) cancelIosGesture();
                return true;
            case MotionEvent.ACTION_POINTER_UP:
                if (event.getPointerId(event.getActionIndex()) == iosPointerId) cancelIosGesture();
                return true;
        }
        return iosPointerId != -1;
    }


    private float progress;
    private ObjectAnimator checkAnimator;
    private ObjectAnimator iconAnimator;

    private boolean attachedToWindow;
    private boolean isChecked;
    private Paint paint;
    private Paint paint2;

    private int drawIconType;
    private float iconProgress = 1.0f;

    private OnCheckedChangeListener onCheckedChangeListener;

    private int trackColorKey = Theme.key_fill_RedNormal;
    private int trackCheckedColorKey = Theme.key_switch2TrackChecked;
    private int thumbColorKey = Theme.key_windowBackgroundWhite;
    private int thumbCheckedColorKey = Theme.key_windowBackgroundWhite;

    private Drawable iconDrawable;
    private int lastIconColor;

    private boolean drawRipple;
    private RippleDrawable rippleDrawable;
    private Paint ripplePaint;
    private int[] pressedState = new int[]{android.R.attr.state_enabled, android.R.attr.state_pressed};
    private int colorSet;

    private boolean bitmapsCreated;
    private Bitmap[] overlayBitmap;
    private Canvas[] overlayCanvas;
    private Bitmap overlayMaskBitmap;
    private Canvas overlayMaskCanvas;
    private float overlayCx;
    private float overlayCy;
    private float overlayRad;
    private Paint overlayEraserPaint;
    private Paint overlayMaskPaint;

    private Theme.ResourcesProvider resourcesProvider;

    private int overrideColorProgress;

    public interface OnCheckedChangeListener {
        void onCheckedChanged(Switch view, boolean isChecked);
    }

    public Switch(Context context) {
        this(context, null);
    }

    public Switch(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        rectF = new RectF();

        paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint2 = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint2.setStyle(Paint.Style.STROKE);
        paint2.setStrokeCap(Paint.Cap.ROUND);
        paint2.setStrokeWidth(AndroidUtilities.dp(2));

        setHapticFeedbackEnabled(true);
    }

    @Keep
    public void setProgress(float value) {
        if (progress == value) {
            return;
        }
        progress = value;
        invalidate();
    }

    @Keep
    public float getProgress() {
        return progress;
    }

    @Keep
    public void setIconProgress(float value) {
        if (iconProgress == value) {
            return;
        }
        iconProgress = value;
        invalidate();
    }

    @Keep
    public float getIconProgress() {
        return iconProgress;
    }

    private void cancelCheckAnimator() {
        if (checkAnimator != null) {
            checkAnimator.cancel();
            checkAnimator = null;
        }
    }

    private void cancelIconAnimator() {
        if (iconAnimator != null) {
            iconAnimator.cancel();
            iconAnimator = null;
        }
    }

    public void setDrawIconType(int type) {
        drawIconType = type;
    }

    public void setDrawRipple(boolean value) {
        if (Build.VERSION.SDK_INT < 21 || value == drawRipple) {
            return;
        }
        drawRipple = value;
        if (zxc.iconic.xenon.NekoConfig.iosSwitches) {
            if (value) iosHelper().press(); else iosHelper().releasePress();
            return;
        }

        if (rippleDrawable == null) {
            ripplePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            ripplePaint.setColor(0xffffffff);
            Drawable maskDrawable;
            if (Build.VERSION.SDK_INT >= 23) {
                maskDrawable = null;
            } else {
                maskDrawable = new Drawable() {
                    @Override
                    public void draw(Canvas canvas) {
                        android.graphics.Rect bounds = getBounds();
                        canvas.drawCircle(bounds.centerX(), bounds.centerY(), AndroidUtilities.dp(18), ripplePaint);
                    }

                    @Override
                    public void setAlpha(int alpha) {

                    }

                    @Override
                    public void setColorFilter(ColorFilter colorFilter) {

                    }

                    @Override
                    public int getOpacity() {
                        return PixelFormat.UNKNOWN;
                    }
                };
            }
            ColorStateList colorStateList = new ColorStateList(
                new int[][]{StateSet.WILD_CARD},
                new int[]{0}
            );
            rippleDrawable = new BaseCell.RippleDrawableSafe(colorStateList, null, maskDrawable);
            if (Build.VERSION.SDK_INT >= 23) {
                rippleDrawable.setRadius(AndroidUtilities.dp(18));
            }
            rippleDrawable.setCallback(this);
        }
        if (isChecked && colorSet != 2 || !isChecked && colorSet != 1) {
            int color = Theme.getColor(isChecked ? Theme.key_switchTrackBlueSelectorChecked : Theme.key_switchTrackBlueSelector, resourcesProvider);
            color = processColor(color);
            ColorStateList colorStateList = new ColorStateList(
                new int[][]{StateSet.WILD_CARD},
                new int[]{color}
            );
            rippleDrawable.setColor(colorStateList);
            colorSet = isChecked ? 2 : 1;
        }
        if (Build.VERSION.SDK_INT >= 28 && value) {
            rippleDrawable.setHotspot(isChecked ? 0 : AndroidUtilities.dp(100), AndroidUtilities.dp(18));
        }
        rippleDrawable.setState(value ? pressedState : StateSet.NOTHING);
        invalidate();
    }

    @Override
    protected boolean verifyDrawable(Drawable who) {
        return super.verifyDrawable(who) || rippleDrawable != null && who == rippleDrawable;
    }

    protected int processColor(int color) {
        return color;
    }

    public void setColors(int track, int trackChecked, int thumb, int thumbChecked) {
        trackColorKey = track;
        trackCheckedColorKey = trackChecked;
        thumbColorKey = thumb;
        thumbCheckedColorKey = thumbChecked;
    }

    private void animateToCheckedState(boolean newCheckedState) {
        if (zxc.iconic.xenon.NekoConfig.iosSwitches) {
            cancelCheckAnimator();
            iosHelper().setChecked(newCheckedState, true, iosGestureCommit || iosPointerId != -1);
            return;
        }
        checkAnimator = ObjectAnimator.ofFloat(this, "progress", newCheckedState ? 1 : 0);
        checkAnimator.setDuration(200);
        if (zxc.iconic.xenon.NekoConfig.material3Switches) {
            checkAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_BACK);
        }
        checkAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                checkAnimator = null;
            }
        });
        checkAnimator.start();
    }

    private void animateIcon(boolean newCheckedState) {
        iconAnimator = ObjectAnimator.ofFloat(this, "iconProgress", newCheckedState ? 1 : 0);
        iconAnimator.setDuration(200);
        iconAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                iconAnimator = null;
            }
        });
        iconAnimator.start();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attachedToWindow = true;
        refreshStyle();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        attachedToWindow = false;
        if (iosGestureParent != null) iosGestureParent.requestDisallowInterceptTouchEvent(false);
        iosGestureParent = null;
        iosPointerId = -1;
        iosDragging = false;
        if (iosSwitchHelper != null) iosSwitchHelper.release();
    }

    public void setOnCheckedChangeListener(OnCheckedChangeListener listener) {
        onCheckedChangeListener = listener;
    }

    public void setChecked(boolean checked, boolean animated) {
        setChecked(checked, drawIconType, animated);
    }

    public void setChecked(boolean checked, int iconType, boolean animated) {
        if (checked != isChecked) {
            isChecked = checked;
            if (attachedToWindow && animated) {
                animateToCheckedState(checked);
            } else {
                cancelCheckAnimator();
                setProgress(checked ? 1.0f : 0.0f);
            }
            if (onCheckedChangeListener != null) {
                onCheckedChangeListener.onCheckedChanged(this, checked);
            }
        }
        if (zxc.iconic.xenon.NekoConfig.iosSwitches && (!attachedToWindow || !animated)) {
            iosHelper().setChecked(checked, false, false);
        }
        setDrawIconType(iconType, animated);
    }

    public void setIcon(int icon) {
        if (icon != 0) {
            iconDrawable = getResources().getDrawable(icon).mutate();
            if (iconDrawable != null) {
                iconDrawable.setColorFilter(new PorterDuffColorFilter(lastIconColor = Theme.getColor(isChecked ? trackCheckedColorKey : trackColorKey, resourcesProvider), PorterDuff.Mode.MULTIPLY));
            }
        } else {
            iconDrawable = null;
        }
        invalidate();
    }

    public void setIconVisible(boolean visible, boolean animated) {
        animatorIconVisibility.setValue(visible, animated);
    }

    public void setDrawIconType(int iconType, boolean animated) {
        if (drawIconType != iconType) {
            drawIconType = iconType;
            if (attachedToWindow && animated) {
                animateIcon(iconType == 0);
            } else {
                cancelIconAnimator();
                setIconProgress(iconType == 0 ? 1.0f : 0.0f);
            }
        }
    }

    public boolean hasIcon() {
        return iconDrawable != null;
    }

    public boolean isChecked() {
        return isChecked;
    }

    public void setOverrideColor(int override) {
        if (overrideColorProgress == override) {
            return;
        }
        if (overlayBitmap == null) {
            try {
                overlayBitmap = new Bitmap[2];
                overlayCanvas = new Canvas[2];
                for (int a = 0; a < 2; a++) {
                    overlayBitmap[a] = Bitmap.createBitmap(getMeasuredWidth(), getMeasuredHeight(), Bitmap.Config.ARGB_8888);
                    overlayCanvas[a] = new Canvas(overlayBitmap[a]);
                }
                overlayMaskBitmap = Bitmap.createBitmap(getMeasuredWidth(), getMeasuredHeight(), Bitmap.Config.ARGB_8888);
                overlayMaskCanvas = new Canvas(overlayMaskBitmap);

                overlayEraserPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                overlayEraserPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.CLEAR));

                overlayMaskPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                overlayMaskPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_OUT));
                bitmapsCreated = true;
            } catch (Throwable e) {
                return;
            }
        }
        if (!bitmapsCreated) {
            return;
        }
        overrideColorProgress = override;
        overlayCx = 0;
        overlayCy = 0;
        overlayRad = 0;
        invalidate();
    }

    public void setOverrideColorProgress(float cx, float cy, float rad) {
        overlayCx = cx;
        overlayCy = cy;
        overlayRad = rad;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (getVisibility() != VISIBLE) {
            return;
        }

        if (zxc.iconic.xenon.NekoConfig.iosSwitches) {
            iosHelper().draw(canvas, this, progress, drawRipple || isPressed(),
                    org.telegram.messenger.LocaleController.isRTL, iconDrawable,
                    animatorIconVisibility.getFloatValue(), resourcesProvider);
            return;
        }

        if (zxc.iconic.xenon.NekoConfig.material3Switches) {
            zxc.iconic.xenon.helpers.M3SwitchHelper.draw(getMeasuredWidth(), getMeasuredHeight(), progress, isChecked, drawIconType, iconProgress, iconDrawable, animatorIconVisibility.getFloatValue(), trackColorKey, trackCheckedColorKey, thumbCheckedColorKey, resourcesProvider, canvas);
            return;
        }

        int width = AndroidUtilities.dp(31);
        int thumb = AndroidUtilities.dp(20);
        int x = (getMeasuredWidth() - width) / 2;
        float y = (getMeasuredHeight() - AndroidUtilities.dpf2(14)) / 2;
        int tx = x + AndroidUtilities.dp(7) + (int) (AndroidUtilities.dp(17) * progress);
        int ty = getMeasuredHeight() / 2;


        int color1;
        int color2;
        float colorProgress;
        int r1;
        int r2;
        int g1;
        int g2;
        int b1;
        int b2;
        int a1;
        int a2;
        int red;
        int green;
        int blue;
        int alpha;
        int color;

        for (int a = 0; a < 2; a++) {
            if (a == 1 && overrideColorProgress == 0) {
                continue;
            }
            Canvas canvasToDraw = a == 0 ? canvas : overlayCanvas[0];

            if (a == 1) {
                overlayBitmap[0].eraseColor(0);
                paint.setColor(0xff000000);
                overlayMaskCanvas.drawRect(0, 0, overlayMaskBitmap.getWidth(), overlayMaskBitmap.getHeight(), paint);
                overlayMaskCanvas.drawCircle(overlayCx - getX(), overlayCy - getY(), overlayRad, overlayEraserPaint);
            }
            if (overrideColorProgress == 1) {
                colorProgress = a == 0 ? 0 : 1;
            } else if (overrideColorProgress == 2) {
                colorProgress = a == 0 ? 1 : 0;
            } else {
                colorProgress = progress;
            }

            color1 = processColor(Theme.getColor(trackColorKey, resourcesProvider));
            color2 = processColor(Theme.getColor(trackCheckedColorKey, resourcesProvider));
            if (a == 0 && iconDrawable != null && lastIconColor != (isChecked ? color2 : color1)) {
                iconDrawable.setColorFilter(new PorterDuffColorFilter(lastIconColor = (isChecked ? color2 : color1), PorterDuff.Mode.MULTIPLY));
            }

            r1 = Color.red(color1);
            r2 = Color.red(color2);
            g1 = Color.green(color1);
            g2 = Color.green(color2);
            b1 = Color.blue(color1);
            b2 = Color.blue(color2);
            a1 = Color.alpha(color1);
            a2 = Color.alpha(color2);

            red = (int) (r1 + (r2 - r1) * colorProgress);
            green = (int) (g1 + (g2 - g1) * colorProgress);
            blue = (int) (b1 + (b2 - b1) * colorProgress);
            alpha = (int) (a1 + (a2 - a1) * colorProgress);
            color = ((alpha & 0xff) << 24) | ((red & 0xff) << 16) | ((green & 0xff) << 8) | (blue & 0xff);
            paint.setColor(color);
            paint2.setColor(color);

            rectF.set(x, y, x + width, y + AndroidUtilities.dpf2(14));
            canvasToDraw.drawRoundRect(rectF, AndroidUtilities.dpf2(7), AndroidUtilities.dpf2(7), paint);
            canvasToDraw.drawCircle(tx, ty, AndroidUtilities.dpf2(10), paint);

            if (a == 0 && rippleDrawable != null) {
                rippleDrawable.setBounds(tx - AndroidUtilities.dp(18), ty - AndroidUtilities.dp(18), tx + AndroidUtilities.dp(18), ty + AndroidUtilities.dp(18));
                rippleDrawable.draw(canvasToDraw);
            } else if (a == 1) {
                canvasToDraw.drawBitmap(overlayMaskBitmap, 0, 0, overlayMaskPaint);
            }
        }
        if (overrideColorProgress != 0) {
            canvas.drawBitmap(overlayBitmap[0], 0, 0, null);
        }

        for (int a = 0; a < 2; a++) {
            if (a == 1 && overrideColorProgress == 0) {
                continue;
            }
            Canvas canvasToDraw = a == 0 ? canvas : overlayCanvas[1];

            if (a == 1) {
                overlayBitmap[1].eraseColor(0);
            }
            if (overrideColorProgress == 1) {
                colorProgress = a == 0 ? 0 : 1;
            } else if (overrideColorProgress == 2) {
                colorProgress = a == 0 ? 1 : 0;
            } else {
                colorProgress = progress;
            }

            color1 = Theme.getColor(thumbColorKey, resourcesProvider);
            color2 = processColor(Theme.getColor(thumbCheckedColorKey, resourcesProvider));
            r1 = Color.red(color1);
            r2 = Color.red(color2);
            g1 = Color.green(color1);
            g2 = Color.green(color2);
            b1 = Color.blue(color1);
            b2 = Color.blue(color2);
            a1 = Color.alpha(color1);
            a2 = Color.alpha(color2);

            red = (int) (r1 + (r2 - r1) * colorProgress);
            green = (int) (g1 + (g2 - g1) * colorProgress);
            blue = (int) (b1 + (b2 - b1) * colorProgress);
            alpha = (int) (a1 + (a2 - a1) * colorProgress);
            paint.setColor(((alpha & 0xff) << 24) | ((red & 0xff) << 16) | ((green & 0xff) << 8) | (blue & 0xff));

            canvasToDraw.drawCircle(tx, ty, AndroidUtilities.dp(8), paint);

            if (a == 0) {
                if (iconDrawable != null) {
                    final float factor = animatorIconVisibility.getFloatValue();
                    if (factor > 0) {
                        final boolean needScale = factor < 1;
                        if (needScale) {
                            canvas.save();
                            canvas.scale(factor, factor, tx, ty);
                        }
                        iconDrawable.setBounds(tx - iconDrawable.getIntrinsicWidth() / 2, ty - iconDrawable.getIntrinsicHeight() / 2, tx + iconDrawable.getIntrinsicWidth() / 2, ty + iconDrawable.getIntrinsicHeight() / 2);
                        iconDrawable.draw(canvasToDraw);
                        if (needScale) {
                            canvas.restore();
                        }
                    }
                } else if (drawIconType == 1) {
                    tx -= AndroidUtilities.dp(10.8f) - AndroidUtilities.dp(1.3f) * progress;
                    ty -= AndroidUtilities.dp(8.5f) - AndroidUtilities.dp(0.5f) * progress;
                    int startX2 = (int) AndroidUtilities.dpf2(4.6f) + tx;
                    int startY2 = (int) (AndroidUtilities.dpf2(9.5f) + ty);
                    int endX2 = startX2 + AndroidUtilities.dp(2);
                    int endY2 = startY2 + AndroidUtilities.dp(2);

                    int startX = (int) AndroidUtilities.dpf2(7.5f) + tx;
                    int startY = (int) AndroidUtilities.dpf2(5.4f) + ty;
                    int endX = startX + AndroidUtilities.dp(7);
                    int endY = startY + AndroidUtilities.dp(7);

                    startX = (int) (startX + (startX2 - startX) * progress);
                    startY = (int) (startY + (startY2 - startY) * progress);
                    endX = (int) (endX + (endX2 - endX) * progress);
                    endY = (int) (endY + (endY2 - endY) * progress);
                    canvasToDraw.drawLine(startX, startY, endX, endY, paint2);

                    startX = (int) AndroidUtilities.dpf2(7.5f) + tx;
                    startY = (int) AndroidUtilities.dpf2(12.5f) + ty;
                    endX = startX + AndroidUtilities.dp(7);
                    endY = startY - AndroidUtilities.dp(7);
                    canvasToDraw.drawLine(startX, startY, endX, endY, paint2);
                } else if (drawIconType == 2 || iconAnimator != null) {
                    paint2.setAlpha((int) (255 * (1.0f - iconProgress)));
                    canvasToDraw.drawLine(tx, ty, tx, ty - AndroidUtilities.dp(5), paint2);
                    canvasToDraw.save();
                    canvasToDraw.rotate(-90 * iconProgress, tx, ty);
                    canvasToDraw.drawLine(tx, ty, tx + AndroidUtilities.dp(4), ty, paint2);
                    canvasToDraw.restore();
                }
            }
            if (a == 1) {
                canvasToDraw.drawBitmap(overlayMaskBitmap, 0, 0, overlayMaskPaint);
            }
        }
        if (overrideColorProgress != 0) {
            canvas.drawBitmap(overlayBitmap[1], 0, 0, null);
        }
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.Switch");
        info.setCheckable(true);
        info.setChecked(isChecked);
        //info.setContentDescription(isChecked ? LocaleController.getString(R.string.NotificationsOn) : LocaleController.getString(R.string.NotificationsOff));
    }
}
