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
import com.google.android.material.loadingindicator.LoadingIndicatorSpecHelper;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;

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
    private LoadingIndicatorSpec loadingIndicatorSpec;
    private int lastSpecContainerW = -1;
    private int lastSpecContainerH = -1;
    private int lastSpecIndicatorSize = -1;
    private int lastSpecColor = 0;

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
                syncLoadingIndicatorSpec();
                drawable.setBounds(getBounds());
                drawable.draw(canvas);
                // Дергаем outer, чтобы кейсы с ручным draw(canvas)
                // (callback == null / CrossfadeDrawable) тоже анимировались.
                invalidateSelf();
                return;
            }
        } else if (loadingIndicatorDrawable != null) {
            // Останавливаем MD3-аниматор, когда выбран Telegram-стиль.
            loadingIndicatorDrawable.setVisible(false, false);
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

    private Context resolveContext() {
        Drawable.Callback callback = getCallback();
        if (callback instanceof View) {
            return ((View) callback).getContext();
        }
        // Кейсы: CrossfadeDrawable (callback — анонимный Drawable.Callback),
        // ручной draw(canvas) (callback == null), фон вью и т.д.
        // Без fallback-контекста MD3-версия никогда не создавалась
        // и индикатор оставался старым.
        try {
            if (ApplicationLoader.applicationContext != null) {
                return ApplicationLoader.applicationContext;
            }
        } catch (Throwable ignore) {
        }
        return null;
    }

    private LoadingIndicatorDrawable getOrCreateLoadingIndicatorDrawable() {
        if (loadingIndicatorDrawable != null) {
            return loadingIndicatorDrawable;
        }
        Context context = resolveContext();
        if (context == null) {
            return null;
        }
        Context themedContext = new ContextThemeWrapper(context, com.google.android.material.R.style.Theme_Material3_DayNight);
        LoadingIndicatorSpec spec = new LoadingIndicatorSpec(themedContext, null);
        loadingIndicatorSpec = spec;
        android.graphics.Rect b = getBounds();
        int indicatorSize = (int) (size * 0.9f);
        int containerW = b.width() > 0 ? b.width() : Math.max(indicatorSize, 1);
        int containerH = b.height() > 0 ? b.height() : Math.max(indicatorSize, 1);
        int color = paint.getColor();
        LoadingIndicatorSpecHelper.configure(
                spec,
                indicatorSize,
                containerW,
                containerH,
                new int[] { color },
                android.graphics.Color.TRANSPARENT);
        lastSpecContainerW = containerW;
        lastSpecContainerH = containerH;
        lastSpecIndicatorSize = indicatorSize;
        lastSpecColor = color;
        loadingIndicatorDrawable = LoadingIndicatorDrawable.create(themedContext, spec);
        loadingIndicatorDrawable.setCallback(loadingIndicatorCallback);
        loadingIndicatorDrawable.setBounds(getBounds());
        loadingIndicatorDrawable.setAlpha(paint.getAlpha());
        ColorFilter cf = paint.getColorFilter();
        if (cf != null) {
            loadingIndicatorDrawable.setColorFilter(cf);
        }
        loadingIndicatorDrawable.setVisible(true, false);
        return loadingIndicatorDrawable;
    }

    private void syncLoadingIndicatorSpec() {
        if (loadingIndicatorSpec == null || loadingIndicatorDrawable == null) {
            return;
        }
        android.graphics.Rect b = getBounds();
        int indicatorSize = (int) (size * 0.9f);
        int containerW = b.width();
        int containerH = b.height();
        // На первом draw() bounds могут быть ещё пустыми (0x0) —
        // LoadingIndicatorDrawable.draw() тогда ничего не рисует.
        // Подменяем на indicatorSize, чтобы MD3 всегда был виден.
        if (containerW <= 0) {
            containerW = Math.max(indicatorSize, 1);
        }
        if (containerH <= 0) {
            containerH = Math.max(indicatorSize, 1);
        }
        int color = paint.getColor();
        if (containerW != lastSpecContainerW || containerH != lastSpecContainerH
                || indicatorSize != lastSpecIndicatorSize || color != lastSpecColor) {
            LoadingIndicatorSpecHelper.configure(
                    loadingIndicatorSpec,
                    indicatorSize,
                    containerW,
                    containerH,
                    new int[] { color },
                    android.graphics.Color.TRANSPARENT);
            lastSpecContainerW = containerW;
            lastSpecContainerH = containerH;
            lastSpecIndicatorSize = indicatorSize;
            lastSpecColor = color;
            // spec — тот же объект, что лежит в drawing/animator делегатах,
            // поэтому пересоздавать drawable не нужно. Ре-активируем аниматор,
            // если он был остановлен при выключенном тогле.
            loadingIndicatorDrawable.setVisible(true, false);
        } else if (!loadingIndicatorDrawable.isVisible()) {
            loadingIndicatorDrawable.setVisible(true, false);
        }
    }

    public void reset() {
        start = -1;
        if (loadingIndicatorDrawable != null) {
            loadingIndicatorDrawable.setVisible(false, false);
            loadingIndicatorDrawable.setCallback(null);
            loadingIndicatorDrawable = null;
        }
        loadingIndicatorSpec = null;
        lastSpecContainerW = -1;
        lastSpecContainerH = -1;
        lastSpecIndicatorSize = -1;
        lastSpecColor = 0;
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
        if (loadingIndicatorDrawable != null) {
            loadingIndicatorDrawable.setBounds(getBounds());
        }
    }

    @Override
    public boolean setVisible(boolean visible, boolean restart) {
        boolean changed = super.setVisible(visible, restart);
        if (loadingIndicatorDrawable != null) {
            loadingIndicatorDrawable.setVisible(visible, restart);
        }
        return changed;
    }

    public void setColor(int color) {
        paint.setColor(color);
        // Цвет подхватится в syncLoadingIndicatorSpec() на следующем draw(),
        // пересоздавать drawable не нужно (иначе рестарт анимации).
    }

    public int getColor() {
        return paint.getColor();
    }

    @Override
    public void setAlpha(int alpha) {
        paint.setAlpha(alpha);
        if (loadingIndicatorDrawable != null) {
            loadingIndicatorDrawable.setAlpha(alpha);
        }
    }

    @Override
    public void setColorFilter(@Nullable ColorFilter colorFilter) {
        paint.setColorFilter(colorFilter);
        if (loadingIndicatorDrawable != null) {
            loadingIndicatorDrawable.setColorFilter(colorFilter);
        }
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
