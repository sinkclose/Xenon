package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;

/** Builds a single open arc without PathMeasure joins or zero-length stroke segments. */
final class WavyProgressPath {
    private static final int STEPS = 180;
    private static final float MIN_EDGE_SQUARED = 0.001f * 0.001f;
    private static final float[] COS = new float[STEPS];
    private static final float[] SIN = new float[STEPS];
    private static final float[] WAVE = new float[STEPS];
    static {
        for (int i = 0; i < STEPS; i++) {
            double angle = i * Math.PI * 2 / STEPS;
            COS[i] = (float) Math.cos(angle);
            SIN[i] = (float) Math.sin(angle);
            WAVE[i] = (float) Math.sin(11 * angle);
        }
    }

    private final Path segment = new Path();
    private final RectF lastOval = new RectF();
    private final float[] x = new float[STEPS], y = new float[STEPS];
    private float lastAmplitude = Float.NaN;
    private float lastX, lastY;

    void draw(Canvas canvas, RectF oval, float startAngle, float sweepAngle,
              float phaseAngle, float amplitudeScale, Paint paint) {
        float cx = oval.centerX(), cy = oval.centerY();
        float radius = Math.min(oval.width(), oval.height()) / 2f;
        if (!Float.isFinite(cx) || !Float.isFinite(cy) || !Float.isFinite(radius) || radius <= 0
                || !Float.isFinite(startAngle) || !Float.isFinite(sweepAngle)
                || !Float.isFinite(phaseAngle) || !Float.isFinite(amplitudeScale) || sweepAngle == 0) {
            return;
        }
        float sweep = Math.min(360f, Math.abs(sweepAngle));
        float amplitude = Math.max(0f, Math.min(1f, amplitudeScale));
        if (amplitude == 0f) {
            canvas.drawArc(cx - radius, cy - radius, cx + radius, cy + radius,
                    startAngle, Math.copySign(sweep, sweepAngle), false, paint);
            return;
        }
        if (!oval.equals(lastOval) || amplitude != lastAmplitude) {
            lastOval.set(oval);
            lastAmplitude = amplitude;
            for (int i = 0; i < STEPS; i++) {
                float r = radius * (1f + 0.05f * amplitude * WAVE[i]);
                x[i] = cx + r * COS[i];
                y[i] = cy + r * SIN[i];
            }
        }

        float phase = phaseAngle % 360f;
        if (phase < 0) phase += 360f;
        float from = phase;
        if (sweepAngle < 0) from -= sweep;
        if (from < 0) from += 360f;
        float first = from * STEPS / 360f;
        float end = first + sweep * STEPS / 360f;
        segment.rewind();
        appendPoint(first, true);
        for (int i = (int) first + 1; i < end; i++) {
            appendPoint(i, false);
        }
        appendPoint(end, false);

        // Repeated coordinates at the circle seam and rLineTo(0, 0) can create
        // unstable stroke tangents on hardware canvases. Only real edges reach Skia.
        Paint.Join join = paint.getStrokeJoin();
        int saved = canvas.save();
        try {
            paint.setStrokeJoin(Paint.Join.ROUND);
            canvas.rotate(startAngle - phase, cx, cy);
            canvas.drawPath(segment, paint);
        } finally {
            paint.setStrokeJoin(join);
            canvas.restoreToCount(saved);
        }
    }

    private void appendPoint(float position, boolean move) {
        int index = (int) position;
        float fraction = position - index;
        int a = index % STEPS, b = (a + 1) % STEPS;
        float px = x[a] + (x[b] - x[a]) * fraction;
        float py = y[a] + (y[b] - y[a]) * fraction;
        float dx = px - lastX, dy = py - lastY;
        if (move) {
            segment.moveTo(px, py);
        } else if (dx * dx + dy * dy >= MIN_EDGE_SQUARED) {
            segment.lineTo(px, py);
        } else {
            return;
        }
        lastX = px;
        lastY = py;
    }
}
