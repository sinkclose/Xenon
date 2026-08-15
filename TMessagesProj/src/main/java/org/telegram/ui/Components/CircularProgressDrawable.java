package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.view.ContextThemeWrapper;
import androidx.interpolator.view.animation.FastOutSlowInInterpolator;

import com.google.android.material.loadingindicator.LoadingIndicatorDrawable;
import com.google.android.material.loadingindicator.LoadingIndicatorSpec;

import org.telegram.messenger.AndroidUtilities;

import zxc.iconic.xenon.NekoConfig;

public class CircularProgressDrawable extends Drawable {

    public float size = AndroidUtilities.dp(18);
    public float thickness = AndroidUtilities.dp(2.25f);

    public CircularProgressDrawable() {
        this(0xffffffff);
    }
    public CircularProgressDrawable(int color) {
        setColor(color);
    }
    public CircularProgressDrawable(float size, float thickness, int color) {
        this.size = size;
        this.thickness = thickness;
        setColor(color);
    }

    private long start = -1;
    private final float[] segment = new float[2];

    private LoadingIndicatorDrawable loadingIndicatorDrawable;

    public static final FastOutSlowInInterpolator interpolator = new FastOutSlowInInterpolator();

    public static void getSegments(float t, float[] segments) {
        segments[0] = Math.max(0, 1520 * t / 5400f - 20);
        segments[1] = 1520 * t / 5400f;
        for (int i = 0; i < 4; ++i) {
            segments[1] += interpolator.getInterpolation((t - i * 1350) / 667f) * 250;
            segments[0] += interpolator.getInterpolation((t - (667 + i * 1350)) / 667f) * 250;
        }
    }

    private final Paint paint = new Paint(); {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setStrokeJoin(Paint.Join.ROUND);
    }

    private float angleOffset;
    private final RectF bounds = new RectF();
    private final RectF offBounds = new RectF();

    private final Drawable.Callback loadingIndicatorCallback = new Drawable.Callback() {
        @Override
        public void invalidateDrawable(@NonNull Drawable who) {
            invalidateSelf();
        }
        @Override
        public void scheduleDrawable(@NonNull Drawable who, @NonNull Runnable what, long when) {
            scheduleSelf(what, when);
        }
        @Override
        public void unscheduleDrawable(@NonNull Drawable who, @NonNull Runnable what) {
            unscheduleSelf(what);
        }
    };

    @Override
    public void draw(@NonNull Canvas canvas) {
        if (NekoConfig.wavyEnabled) {
            LoadingIndicatorDrawable drawable = getOrCreateLoadingIndicatorDrawable();
            if (drawable != null) {
                drawable.setBounds(getBounds());
                drawable.draw(canvas);
                return;
            }
        }
        if (start < 0) {
            start = SystemClock.elapsedRealtime();
        }
        getSegments((SystemClock.elapsedRealtime() - start) % 5400, segment);
        offBounds.set(bounds);
        offBounds.inset(AndroidUtilities.dp(1f), AndroidUtilities.dp(1f));
        canvas.drawArc(offBounds, angleOffset + segment[0], segment[1] - segment[0], false, paint);
        invalidateSelf();
    }

    private LoadingIndicatorDrawable getOrCreateLoadingIndicatorDrawable() {
        if (loadingIndicatorDrawable != null) {
            return loadingIndicatorDrawable;
        }
        Drawable.Callback callback = getCallback();
        Context context = null;
        if (callback instanceof View) {
            context = ((View) callback).getContext();
        }
        if (context == null) {
            return null;
        }
        Context themedContext = new ContextThemeWrapper(context, com.google.android.material.R.style.Theme_Material3_DayNight);
        LoadingIndicatorSpec spec = new LoadingIndicatorSpec(themedContext, null);
        android.graphics.Rect b = getBounds();
        com.google.android.material.loadingindicator.LoadingIndicatorSpecHelper.configure(
                spec,
                (int) (size * 0.9f),
                b.width(),
                b.height(),
                new int[] { paint.getColor() },
                android.graphics.Color.TRANSPARENT);
        loadingIndicatorDrawable = LoadingIndicatorDrawable.create(themedContext, spec);
        loadingIndicatorDrawable.setCallback(loadingIndicatorCallback);
        loadingIndicatorDrawable.setBounds(b);
        loadingIndicatorDrawable.setVisible(true, false);
        return loadingIndicatorDrawable;
    }

    public void reset() {
        start = -1;
        if (loadingIndicatorDrawable != null) {
            loadingIndicatorDrawable.setVisible(false, false);
            loadingIndicatorDrawable.setCallback(null);
            loadingIndicatorDrawable = null;
        }
    }

    public void setAngleOffset(float angleOffset) {
        this.angleOffset = angleOffset;
    }

    @Override
    public void setBounds(int left, int top, int right, int bottom) {
        int width = right - left, height = bottom - top;
        bounds.set(
            left + (width - thickness / 2f - size) / 2f,
            top + (height - thickness / 2f - size) / 2f,
            left + (width + thickness / 2f + size) / 2f,
            top + (height + thickness / 2f + size) / 2f
        );
        super.setBounds(left, top, right, bottom);
        paint.setStrokeWidth(thickness);
    }

    public void setColor(int color) {
        paint.setColor(color);
        if (loadingIndicatorDrawable != null) {
            loadingIndicatorDrawable.setVisible(false, false);
            loadingIndicatorDrawable.setCallback(null);
            loadingIndicatorDrawable = null;
        }
    }

    public int getColor() {
        return paint.getColor();
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSPARENT;
    }

    @Override
    public int getIntrinsicWidth() {
        return (int) (size + thickness);
    }

    @Override
    public int getIntrinsicHeight() {
        return (int) (size + thickness);
    }
}
