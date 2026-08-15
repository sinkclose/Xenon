/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

import androidx.annotation.Keep;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

import zxc.iconic.xenon.NekoConfig;

public class RadialProgressView extends View {

    private long lastUpdateTime;
    private float radOffset = -90;
    private float currentCircleLength;
    private boolean risingCircleLength;
    private float currentProgressTime;
    private RectF cicleRect = new RectF();
    private boolean useSelfAlpha;
    private float drawingCircleLenght;

    private int progressColor;

    private DecelerateInterpolator decelerateInterpolator;
    private AccelerateInterpolator accelerateInterpolator;
    private Paint progressPaint;
    private static final float rotationTime = 2000;
    private static final float risingTime = 500;
    private int size;

    private float currentProgress;
    private float progressAnimationStart;
    private int progressTime;
    private float animatedProgress;
    private boolean toCircle;
    private float toCircleProgress;

    private boolean noProgress = true;
    private boolean rotationEnabled = true;
    private long kickPhaseStartTime;
    private long lastAnimationNewTime;
    private final Theme.ResourcesProvider resourcesProvider;

    private final Path wavyProgressPath = new Path();
    private final PathMeasure wavyProgressPathMeasure = new PathMeasure();
    private final Path wavySegmentPath = new Path();
    private RectF wavyLastOval = new RectF();
    private int wavyLastGeneration;
    private float wavePhaseAngle;
    private float wavyAmplitudeSmooth = 1f;
    private float wavyLastAmplitudeSmooth = 1f;
    private float bgThicknessScale;

    public RadialProgressView(Context context) {
        this(context, null);
    }

    public RadialProgressView(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;

        size = AndroidUtilities.dp(40);

        progressColor = getThemedColor(Theme.key_progressCircle);
        decelerateInterpolator = new DecelerateInterpolator();
        accelerateInterpolator = new AccelerateInterpolator();
        progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
        progressPaint.setStrokeWidth(AndroidUtilities.dp(3));
        progressPaint.setColor(progressColor);
    }

    public void setUseSelfAlpha(boolean value) {
        useSelfAlpha = value;
    }

    @Keep
    @Override
    public void setAlpha(float alpha) {
        super.setAlpha(alpha);
        if (useSelfAlpha) {
            Drawable background = getBackground();
            int a = (int) (alpha * 255);
            if (background != null) {
                background.setAlpha(a);
            }
            progressPaint.setAlpha(a);
        }
    }

    public void setNoProgress(boolean value) {
        noProgress = value;
    }

    public void setRotationEnabled(boolean enabled) {
        rotationEnabled = enabled;
    }

    public void setRadOffset(float offset) {
        radOffset = offset;
    }

    public void setProgressImmediately(float value) {
        currentProgress = value;
        animatedProgress = value;
        progressAnimationStart = value;
        progressTime = 0;
    }

    public void setProgress(float value) {
        currentProgress = value;
        if (animatedProgress > value) {
            animatedProgress = value;
        }
        progressAnimationStart = animatedProgress;
        progressTime = 0;
    }

    public void sync(RadialProgressView from) {
        lastUpdateTime = from.lastUpdateTime;
        radOffset = from.radOffset;
        toCircle = from.toCircle;
        toCircleProgress = from.toCircleProgress;
        noProgress = from.noProgress;
        currentCircleLength = from.currentCircleLength;
        drawingCircleLenght = from.drawingCircleLenght;
        currentProgressTime = from.currentProgressTime;
        currentProgress = from.currentProgress;
        progressTime = from.progressTime;
        animatedProgress = from.animatedProgress;
        risingCircleLength = from.risingCircleLength;
        progressAnimationStart = from.progressAnimationStart;
        updateAnimation(17 * 5);
    }

    private void updateAnimation() {
        long newTime = System.currentTimeMillis();
        lastAnimationNewTime = newTime;
        long dt = newTime - lastUpdateTime;
        if (dt > 17) {
            dt = 17;
        }
        lastUpdateTime = newTime;
        updateAnimation(dt);
    }

    private void updateAnimation(long dt) {
        if (!NekoConfig.wavyEnabled) {
            radOffset += 360 * dt / rotationTime;
            int count = (int) (radOffset / 360);
            radOffset -= count * 360;

            if (toCircle && toCircleProgress != 1f) {
                toCircleProgress += 16 / 220f;
                if (toCircleProgress > 1f) {
                    toCircleProgress = 1f;
                }
            } else if (!toCircle && toCircleProgress != 0f) {
                toCircleProgress -= 16 / 400f;
                if (toCircleProgress < 0) {
                    toCircleProgress = 0f;
                }
            }

            if (noProgress) {
                if (toCircleProgress == 0) {
                    currentProgressTime += dt;
                    if (currentProgressTime >= risingTime) {
                        currentProgressTime = risingTime;
                    }
                    if (risingCircleLength) {
                        currentCircleLength = 4 + 266 * accelerateInterpolator.getInterpolation(currentProgressTime / risingTime);
                    } else {
                        currentCircleLength = 4 - 270 * (1.0f - decelerateInterpolator.getInterpolation(currentProgressTime / risingTime));
                    }

                    if (currentProgressTime == risingTime) {
                        if (risingCircleLength) {
                            radOffset += 270;
                            currentCircleLength = -266;
                        }
                        risingCircleLength = !risingCircleLength;
                        currentProgressTime = 0;
                    }
                } else {
                    if (risingCircleLength) {
                        float old = currentCircleLength;
                        currentCircleLength = 4 + 266 * accelerateInterpolator.getInterpolation(currentProgressTime / risingTime);
                        currentCircleLength += 360 * toCircleProgress;
                        float dx = old - currentCircleLength;
                        if (dx > 0) {
                            radOffset += old - currentCircleLength;
                        }
                    } else {
                        float old = currentCircleLength;
                        currentCircleLength = 4 - 270 * (1.0f - decelerateInterpolator.getInterpolation(currentProgressTime / risingTime));
                        currentCircleLength -= 364 * toCircleProgress;
                        float dx = old - currentCircleLength;
                        if (dx > 0) {
                            radOffset += old - currentCircleLength;
                        }
                    }
                }
            } else {
                float progressDiff = currentProgress - progressAnimationStart;
                if (progressDiff > 0) {
                    progressTime += dt;
                    if (progressTime >= 200.0f) {
                        animatedProgress = progressAnimationStart = currentProgress;
                        progressTime = 0;
                    } else {
                        animatedProgress = progressAnimationStart + progressDiff * AndroidUtilities.decelerateInterpolator.getInterpolation(progressTime / 200.0f);
                    }
                }
                currentCircleLength = Math.max(4, 360 * animatedProgress);
            }
            invalidate();
            return;
        }
        if (rotationEnabled) {
            if (noProgress) {
                long kickElapsed = lastAnimationNewTime - kickPhaseStartTime;
                if (kickElapsed < 300) {
                    radOffset += 360 * dt / 500f;
                } else {
                    radOffset += 360 * dt / rotationTime;
                }
                if (kickElapsed > 1000) {
                    kickPhaseStartTime = lastAnimationNewTime + 200;
                }
            } else {
                radOffset += 360 * dt / rotationTime;
            }
            int count = (int) (radOffset / 360);
            radOffset -= count * 360;
        }

        wavePhaseAngle += (dt * 50.0f) / 1000f;
        wavePhaseAngle %= 360f;

        float targetScale;
        if (noProgress) {
            targetScale = 0f;
        } else {
            float absArc = Math.abs(currentCircleLength);
            targetScale = (absArc > 0.85f * 360) ? 0f : 1f;
        }
        wavyAmplitudeSmooth += (targetScale - wavyAmplitudeSmooth) * Math.min(1f, dt / 80f);

        if (toCircle && toCircleProgress != 1f) {
            toCircleProgress += 16 / 220f;
            if (toCircleProgress > 1f) {
                toCircleProgress = 1f;
            }
        } else if (!toCircle && toCircleProgress != 0f) {
            toCircleProgress -= 16 / 400f;
            if (toCircleProgress < 0) {
                toCircleProgress = 0f;
            }
        }

        if (noProgress) {
            if (toCircleProgress == 0) {
                currentProgressTime += dt;
                if (currentProgressTime >= risingTime) {
                    currentProgressTime = risingTime;
                }
                if (risingCircleLength) {
                    currentCircleLength = 4 + 266 * accelerateInterpolator.getInterpolation(currentProgressTime / risingTime);
                } else {
                    currentCircleLength = 4 + 266 * (1.0f - decelerateInterpolator.getInterpolation(currentProgressTime / risingTime));
                }

                if (currentProgressTime == risingTime) {
                    risingCircleLength = !risingCircleLength;
                    currentProgressTime = 0;
                }
            } else {
                if (risingCircleLength) {
                    currentCircleLength = 4 + 266 * accelerateInterpolator.getInterpolation(currentProgressTime / risingTime);
                } else {
                    currentCircleLength = 4 + 266 * (1.0f - decelerateInterpolator.getInterpolation(currentProgressTime / risingTime));
                }
                currentCircleLength += (360 - currentCircleLength) * toCircleProgress;
            }
        } else {
            float progressDiff = currentProgress - progressAnimationStart;
            if (progressDiff > 0) {
                progressTime += dt;
                if (progressTime >= 200.0f) {
                    animatedProgress = progressAnimationStart = currentProgress;
                    progressTime = 0;
                } else {
                    animatedProgress = progressAnimationStart + progressDiff * AndroidUtilities.decelerateInterpolator.getInterpolation(progressTime / 200.0f);
                }
            }
            currentCircleLength = Math.max(4, 360 * animatedProgress);
        }
        invalidate();
    }

    public void setSize(int value) {
        size = value;
        invalidate();
    }

    public void setStrokeWidth(float value) {
        progressPaint.setStrokeWidth(AndroidUtilities.dp(value));
    }

    public void setProgressColor(int color) {
        progressColor = color;
        progressPaint.setColor(progressColor);
    }

    public void toCircle(boolean toCircle, boolean animated) {
        this.toCircle = toCircle;
        if (!animated) {
            toCircleProgress = toCircle ? 1f : 0f;
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (!NekoConfig.wavyEnabled) {
            int x = (getMeasuredWidth() - size) / 2;
            int y = (getMeasuredHeight() - size) / 2;
            cicleRect.set(x, y, x + size, y + size);
            cicleRect.inset(AndroidUtilities.dp(1f), AndroidUtilities.dp(1f));
            canvas.drawArc(cicleRect, radOffset, drawingCircleLenght = currentCircleLength, false, progressPaint);
            updateAnimation();
            return;
        }
        int x = (getMeasuredWidth() - size) / 2;
        int y = (getMeasuredHeight() - size) / 2;
        cicleRect.set(x, y, x + size, y + size);
        float inset = AndroidUtilities.dp(1f);
        RectF insetOval = new RectF(cicleRect);
        insetOval.inset(inset, inset);
        float absSweep = Math.abs(drawingCircleLenght = currentCircleLength);
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

    public void draw(Canvas canvas, float cx, float cy) {
        if (!NekoConfig.wavyEnabled) {
            cicleRect.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy +  size / 2f);
            cicleRect.inset(AndroidUtilities.dp(1f), AndroidUtilities.dp(1f));
            canvas.drawArc(cicleRect, radOffset, drawingCircleLenght = currentCircleLength, false, progressPaint);
            updateAnimation();
            return;
        }
        cicleRect.set(cx - size / 2f, cy - size / 2f, cx + size / 2f, cy +  size / 2f);
        float inset = AndroidUtilities.dp(1f);
        RectF insetOval = new RectF(cicleRect);
        insetOval.inset(inset, inset);
        float absSweep = Math.abs(drawingCircleLenght = currentCircleLength);
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

    public boolean isCircle() {
        return Math.abs(drawingCircleLenght) >= 360;
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

    private int getThemedColor(int key) {
        return Theme.getColor(key, resourcesProvider);
    }
}
