package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;

import org.telegram.messenger.AndroidUtilities;

import zxc.iconic.xenon.NekoConfig;

public class InfiniteProgress {

    private long lastUpdateTime;
    private float radOffset;
    private float currentCircleLength;
    private boolean risingCircleLength;
    private float currentProgressTime;
    private RectF cicleRect = new RectF();

    private int progressColor;

    private Paint progressPaint;
    private static final float rotationTime = 2000;
    private static final float risingTime = 500;
    private int radius;

    private final Path wavyProgressPath = new Path();
    private final PathMeasure wavyProgressPathMeasure = new PathMeasure();
    private final Path wavySegmentPath = new Path();
    private RectF wavyLastOval = new RectF();
    private int wavyLastGeneration;
    private float wavePhaseAngle;
    private float wavyAmplitudeSmooth = 1f;
    private float wavyLastAmplitudeSmooth = 1f;
    private float bgThicknessScale;

    public InfiniteProgress(int rad) {
        radius = rad;

        progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
    }

    public void setAlpha(float alpha) {
        progressPaint.setAlpha((int) (alpha * Color.alpha(progressColor)));
    }

    public void setColor(int color) {
        progressColor = color;
        progressPaint.setColor(progressColor);
    }

    private void updateAnimation() {
        long newTime = System.currentTimeMillis();
        long dt = newTime - lastUpdateTime;
        if (dt > 17) {
            dt = 17;
        }
        lastUpdateTime = newTime;

        radOffset += 360 * dt / rotationTime;
        int count = (int) (radOffset / 360);
        radOffset -= count * 360;

        wavePhaseAngle += (dt * 50.0f) / 1000f;
        wavePhaseAngle %= 360f;
        wavyAmplitudeSmooth += (1f - wavyAmplitudeSmooth) * Math.min(1f, dt / 80f);

        currentProgressTime += dt;
        if (currentProgressTime >= risingTime) {
            currentProgressTime = risingTime;
        }
        if (risingCircleLength) {
            currentCircleLength = 4 + 266 * AndroidUtilities.accelerateInterpolator.getInterpolation(currentProgressTime / risingTime);
        } else {
            currentCircleLength = 4 - 270 * (1.0f - AndroidUtilities.decelerateInterpolator.getInterpolation(currentProgressTime / risingTime));
        }
        if (currentProgressTime == risingTime) {
            if (risingCircleLength) {
                radOffset += 270;
                currentCircleLength = -266;
            }
            risingCircleLength = !risingCircleLength;
            currentProgressTime = 0;
        }
    }

    public void draw(Canvas canvas, float cx, float cy, float scale) {
        if (!NekoConfig.wavyEnabled) {
            cicleRect.set(cx - radius * scale, cy - radius * scale, cx + radius * scale, cy + radius * scale);
            cicleRect.inset(AndroidUtilities.dp(1f), AndroidUtilities.dp(1f));
            progressPaint.setStrokeWidth(AndroidUtilities.dp(2) * scale);
            canvas.drawArc(cicleRect, radOffset, currentCircleLength, false, progressPaint);
            updateAnimation();
            return;
        }
        cicleRect.set(cx - radius * scale, cy - radius * scale, cx + radius * scale, cy + radius * scale);
        progressPaint.setStrokeWidth(AndroidUtilities.dp(2) * scale);
        float inset = AndroidUtilities.dp(1f);
        RectF insetOval = new RectF(cicleRect);
        insetOval.inset(inset, inset);
        float absSweep = Math.abs(currentCircleLength);
        if (absSweep < 360) {
            int alpha = progressPaint.getAlpha();
            progressPaint.setAlpha(alpha * 40 / 100);
            float saveWidth = progressPaint.getStrokeWidth();
            progressPaint.setStrokeWidth(saveWidth * bgThicknessScale);
            float gap = 16;
            float dir = currentCircleLength >= 0 ? 1 : -1;
            float bgSweep = 360 - absSweep - 2 * gap;
            if (bgSweep > 0) {
                canvas.drawArc(insetOval, radOffset + currentCircleLength + dir * gap, dir * bgSweep, false, progressPaint);
            }
            progressPaint.setStrokeWidth(saveWidth);
            progressPaint.setAlpha(alpha);
        }
        drawWavyArc(canvas, insetOval, radOffset, currentCircleLength, progressPaint);
        updateAnimation();
    }

    private void drawWavyArc(Canvas canvas, RectF oval, float startAngle, float sweepAngle, Paint paint) {
        if (!oval.equals(wavyLastOval) || wavyLastGeneration != 0 || wavyLastAmplitudeSmooth != wavyAmplitudeSmooth) {
            wavyLastOval.set(oval);
            wavyProgressPath.rewind();

            float cx = oval.centerX();
            float cy = oval.centerY();
            float baseRadius = Math.min(oval.width(), oval.height()) / 2f;

            float amplitude = baseRadius * 0.05f * wavyAmplitudeSmooth;
            int waves = 11;
            int steps = 180;

            for (int i = 0; i <= steps; i++) {
                float angle = (i * 360f) / steps;
                float rad = (float) Math.toRadians(angle);
                float r = baseRadius + amplitude * (float) Math.sin(waves * rad);
                float x = cx + r * (float) Math.cos(rad);
                float y = cy + r * (float) Math.sin(rad);

                if (i == 0) {
                    wavyProgressPath.moveTo(x, y);
                } else {
                    wavyProgressPath.lineTo(x, y);
                }
            }
            wavyProgressPath.close();
            wavyProgressPathMeasure.setPath(wavyProgressPath, false);
            wavyLastGeneration = 0;
            wavyLastAmplitudeSmooth = wavyAmplitudeSmooth;
        }

        float length = wavyProgressPathMeasure.getLength();
        float sweepDist = (Math.abs(sweepAngle) / 360f) * length;

        float startDist = (wavePhaseAngle / 360f) * length;
        startDist = (startDist % length + length) % length;
        float stopDist = startDist + sweepDist;

        wavySegmentPath.reset();

        if (stopDist <= length) {
            wavyProgressPathMeasure.getSegment(startDist, stopDist, wavySegmentPath, true);
        } else {
            wavyProgressPathMeasure.getSegment(startDist, length, wavySegmentPath, true);
            wavyProgressPathMeasure.getSegment(0, stopDist - length, wavySegmentPath, false);
        }
        wavySegmentPath.rLineTo(0, 0);

        canvas.save();
        canvas.rotate(startAngle - wavePhaseAngle, oval.centerX(), oval.centerY());
        canvas.drawPath(wavySegmentPath, paint);
        canvas.restore();
    }
}
