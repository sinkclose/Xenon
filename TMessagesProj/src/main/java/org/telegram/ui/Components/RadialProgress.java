/*
 * This is the source code of Telegram for Android v. 2.0.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.Components;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PathMeasure;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import zxc.iconic.xenon.NekoConfig;

public class RadialProgress {

    private long lastUpdateTime = 0;
    private float radOffset = 0;
    private float currentProgress = 0;
    private float animationProgressStart = 0;
    private long currentProgressTime = 0;
    private float animatedProgressValue = 0;
    private RectF progressRect = new RectF();
    private RectF cicleRect = new RectF();
    private View parent;
    private float animatedAlphaValue = 1.0f;

    private boolean previousCheckDrawable;

    private boolean currentMiniWithRound;
    private boolean previousMiniWithRound;
    private boolean currentWithRound;
    private boolean previousWithRound;
    private Drawable currentMiniDrawable;
    private Drawable previousMiniDrawable;
    private Drawable currentDrawable;
    private Drawable previousDrawable;
    private boolean hideCurrentDrawable;
    private int progressColor = 0xffffffff;
    private Paint progressPaint;
    private Paint miniProgressPaint;
    private Paint miniProgressBackgroundPaint;

    private boolean drawMiniProgress;

    private CheckDrawable checkDrawable;
    private Drawable checkBackgroundDrawable;

    private int diff = AndroidUtilities.dp(4);

    private static DecelerateInterpolator decelerateInterpolator;
    private boolean alphaForPrevious = true;
    private boolean alphaForMiniPrevious = true;

    private Bitmap miniDrawBitmap;
    private Canvas miniDrawCanvas;

    private float overrideAlpha = 1.0f;
    private Paint overridePaint = null;
    private boolean disableUpdate;
    private boolean roundRectProgress;

    private float indetSweep = INDET_MIN_SWEEP;
    private long indetPhaseStartTime;
    private int indetPhase;
    private static final float INDET_MIN_SWEEP = 10;
    private static final float INDET_MAX_SWEEP = 324;
    private static final int INDET_GROW = 0;
    private static final int INDET_MAX = 1;
    private static final int INDET_SHRINK = 2;
    private static final int INDET_PAUSE = 3;
    private static final long INDET_GROW_DURATION = 3000;
    private static final long INDET_MAX_DURATION = 500;
    private static final long INDET_SHRINK_DURATION = 1000;
    private static final long INDET_PAUSE_DURATION = 2500;

    private long kickPhaseStartTime;
    private static final long KICK_INTERVAL = 1750;
    private static final long KICK_DURATION = 300;
    private static final float KICK_SPEED_MULTIPLIER = 3f;

    public float getAnimatedProgress() {
        return animatedProgressValue;
    }

    public void copyParams(RadialProgress radialProgressUpload) {
        currentProgress = radialProgressUpload.currentProgress;
        animatedProgressValue = radialProgressUpload.animatedProgressValue;
        radOffset = radialProgressUpload.radOffset;
        lastUpdateTime = System.currentTimeMillis();
//        currentProgressTime = radialProgressUpload.currentProgressTime;
//        animationProgressStart = radialProgressUpload.animationProgressStart;
        invalidateParent();
    }

    public void disableUpdate(boolean disableUpdate) {
        this.disableUpdate = disableUpdate;
    }

    private class CheckDrawable extends Drawable {

        private Paint paint;
        private float progress;

        public CheckDrawable() {
            paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(AndroidUtilities.dp(3));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(0xffffffff);
        }

        public void resetProgress(boolean animated) {
            progress = animated ? 0.0f : 1.0f;
        }

        public boolean updateAnimation(long dt) {
            if (progress < 1.0f) {
                progress += dt / 700.0f;
                if (progress > 1.0f) {
                    progress = 1.0f;
                }
                return true;
            }
            return false;
        }

        @Override
        public void draw(Canvas canvas) {
            int x = getBounds().centerX() - AndroidUtilities.dp(12);
            int y = getBounds().centerY() - AndroidUtilities.dp(6);
            float p = progress != 1.0f ? decelerateInterpolator.getInterpolation(progress) : 1.0f;
            int endX = (int) (AndroidUtilities.dp(7.0f) - AndroidUtilities.dp(6) * p);
            int endY = (int) (AndroidUtilities.dpf2(13.0f) - AndroidUtilities.dp(6) * p);
            canvas.drawLine(x + AndroidUtilities.dp(7.0f), y + (int) AndroidUtilities.dpf2(13.0f), x + endX, y + endY, paint);
            endX = (int) (AndroidUtilities.dpf2(7.0f) + AndroidUtilities.dp(13) * p);
            endY = (int) (AndroidUtilities.dpf2(13.0f) - AndroidUtilities.dp(13) * p);
            canvas.drawLine(x + (int) AndroidUtilities.dpf2(7.0f), y + (int) AndroidUtilities.dpf2(13.0f), x + endX, y + endY, paint);
        }

        @Override
        public void setAlpha(int alpha) {
            paint.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            paint.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSPARENT;
        }

        @Override
        public int getIntrinsicWidth() {
            return AndroidUtilities.dp(48);
        }

        @Override
        public int getIntrinsicHeight() {
            return AndroidUtilities.dp(48);
        }
    }

    public RadialProgress(View parentView) {
        if (decelerateInterpolator == null) {
            decelerateInterpolator = new DecelerateInterpolator();
        }
        kickPhaseStartTime = System.currentTimeMillis();
        progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        progressPaint.setStyle(Paint.Style.STROKE);
        progressPaint.setStrokeCap(Paint.Cap.ROUND);
        progressPaint.setStrokeWidth(AndroidUtilities.dp(3));

        miniProgressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        miniProgressPaint.setStyle(Paint.Style.STROKE);
        miniProgressPaint.setStrokeCap(Paint.Cap.ROUND);
        miniProgressPaint.setStrokeWidth(AndroidUtilities.dp(2));

        miniProgressBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        parent = parentView;
    }

    public void setStrokeWidth(int width) {
        progressPaint.setStrokeWidth(width);
    }

    public void setProgressRect(int left, int top, int right, int bottom) {
        progressRect.set(left, top, right, bottom);
    }

    public RectF getProgressRect() {
        return progressRect;
    }

    public void setAlphaForPrevious(boolean value) {
        alphaForPrevious = value;
    }

    public void setAlphaForMiniPrevious(boolean value) {
        alphaForMiniPrevious = value;
    }

    private float rotationSpeed = 3000;

    public void setRotationTime(float rotationSpeed) {
        this.rotationSpeed = rotationSpeed;
    }

    private void updateAnimation(boolean progress) {
        if (disableUpdate) {
            return;
        }
        long newTime = System.currentTimeMillis();
        long dt = newTime - lastUpdateTime;
        lastUpdateTime = newTime;
        if (wavyProgress && NekoConfig.wavyEnabled) {
            wavePhaseAngle += (dt * 50.0f) / 1000f;
            wavePhaseAngle %= 360f;

            float targetScale = animatedProgressValue > 0.85f ? 0f : 1f;
            wavyAmplitudeSmooth += (targetScale - wavyAmplitudeSmooth) * Math.min(1f, dt / 80f);

            float progressFade = animatedProgressValue > 0.90f ? Math.max(0f, (1f - animatedProgressValue) / 0.05f) : 1f;
            bgThicknessScale += (progressFade - bgThicknessScale) * Math.min(1f, dt / 50f);
        }
        if (checkBackgroundDrawable != null && (currentDrawable == checkBackgroundDrawable || previousDrawable == checkBackgroundDrawable)) {
            if (checkDrawable.updateAnimation(dt)) {
                invalidateParent();
            }
        }

        if (progress) {
            if (animatedProgressValue != 1) {
                if (NekoConfig.wavyEnabled) {
                    if (currentProgress < 0.05f) {
                        long kickElapsed = newTime - kickPhaseStartTime;
                        if (kickElapsed >= KICK_INTERVAL) {
                            kickPhaseStartTime = newTime;
                            kickElapsed = 0;
                        }
                        float rotSpeed = 360 * dt / rotationSpeed;
                        if (kickElapsed < KICK_DURATION) {
                            rotSpeed *= KICK_SPEED_MULTIPLIER;
                        }
                        radOffset += rotSpeed;
                    } else {
                        radOffset = -90;
                    }
                } else {
                    radOffset += 360 * dt / rotationSpeed;
                }
                float progressDiff = currentProgress - animationProgressStart;
                if (progressDiff > 0) {
                    currentProgressTime += dt;
                    if (currentProgressTime >= 300) {
                        animatedProgressValue = currentProgress;
                        animationProgressStart = currentProgress;
                        currentProgressTime = 0;
                    } else {
                        animatedProgressValue = animationProgressStart + progressDiff * decelerateInterpolator.getInterpolation(currentProgressTime / 300.0f);
                    }
                }
                invalidateParent();
            }

            if (currentProgress < 0.05f) {
                if (indetPhaseStartTime == 0) {
                    indetPhaseStartTime = newTime;
                    kickPhaseStartTime = newTime;
                }
                long elapsed = newTime - indetPhaseStartTime;
                switch (indetPhase) {
                    case INDET_GROW: {
                        float t = Math.min(1f, (float) elapsed / INDET_GROW_DURATION);
                        float smooth = t * t * (3 - 2 * t);
                        indetSweep = INDET_MIN_SWEEP + (INDET_MAX_SWEEP - INDET_MIN_SWEEP) * smooth;
                        if (t >= 1f) {
                            indetPhase = INDET_MAX;
                            indetPhaseStartTime = newTime;
                        }
                        break;
                    }
                    case INDET_MAX: {
                        indetSweep = INDET_MAX_SWEEP;
                        if (elapsed >= INDET_MAX_DURATION) {
                            indetPhase = INDET_SHRINK;
                            indetPhaseStartTime = newTime;
                        }
                        break;
                    }
                    case INDET_SHRINK: {
                        float t = Math.min(1f, (float) elapsed / INDET_SHRINK_DURATION);
                        float smooth = t * t * (3 - 2 * t);
                        indetSweep = INDET_MAX_SWEEP - (INDET_MAX_SWEEP - INDET_MIN_SWEEP) * smooth;
                        if (t >= 1f) {
                            indetPhase = INDET_PAUSE;
                            indetPhaseStartTime = newTime;
                        }
                        break;
                    }
                    case INDET_PAUSE: {
                        if (elapsed >= INDET_PAUSE_DURATION) {
                            indetPhase = INDET_GROW;
                            indetPhaseStartTime = newTime;
                        }
                        break;
                    }
                }
            } else {
                if (indetPhaseStartTime != 0) {
                    indetPhaseStartTime = 0;
                    indetPhase = INDET_GROW;
                    indetSweep = INDET_MIN_SWEEP;
                }
            }

            if (drawMiniProgress) {
                if (animatedProgressValue >= 1 && previousMiniDrawable != null) {
                    animatedAlphaValue -= dt / 200.0f;
                    if (animatedAlphaValue <= 0) {
                        animatedAlphaValue = 0.0f;
                        previousMiniDrawable = null;
                        drawMiniProgress = currentMiniDrawable != null;
                    }
                    invalidateParent();
                }
            } else {
                if (animatedProgressValue >= 1 && previousDrawable != null) {
                    animatedAlphaValue -= dt / 200.0f;
                    if (animatedAlphaValue <= 0) {
                        animatedAlphaValue = 0.0f;
                        previousDrawable = null;
                    }
                    invalidateParent();
                }
            }
        } else {
            if (drawMiniProgress) {
                if (previousMiniDrawable != null) {
                    animatedAlphaValue -= dt / 200.0f;
                    if (animatedAlphaValue <= 0) {
                        animatedAlphaValue = 0.0f;
                        previousMiniDrawable = null;
                        drawMiniProgress = currentMiniDrawable != null;
                    }
                    invalidateParent();
                }
            } else {
                if (previousDrawable != null) {
                    animatedAlphaValue -= dt / 200.0f;
                    if (animatedAlphaValue <= 0) {
                        animatedAlphaValue = 0.0f;
                        previousDrawable = null;
                    }
                    invalidateParent();
                }
            }
        }
    }

    public void setDiff(int value) {
        diff = value;
    }

    public void setProgressColor(int color) {
        progressColor = color;
    }

    public void setMiniProgressBackgroundColor(int color) {
        miniProgressBackgroundPaint.setColor(color);
    }

    public void setHideCurrentDrawable(boolean value) {
        hideCurrentDrawable = value;
    }

    public void setProgress(float value, boolean animated) {
        if (drawMiniProgress) {
            if (value != 1 && animatedAlphaValue != 0 && previousMiniDrawable != null) {
                animatedAlphaValue = 0.0f;
                previousMiniDrawable = null;
                drawMiniProgress = currentMiniDrawable != null;
            }
        } else {
            if (value != 1 && animatedAlphaValue != 0 && previousDrawable != null) {
                animatedAlphaValue = 0.0f;
                previousDrawable = null;
            }
        }
        if (!animated) {
            animatedProgressValue = value;
            animationProgressStart = value;
        } else {
            if (animatedProgressValue > value) {
                animatedProgressValue = value;
            }
            animationProgressStart = animatedProgressValue;
        }
        currentProgress = value;
        currentProgressTime = 0;

        invalidateParent();
    }

    private void invalidateParent() {
        int offset = AndroidUtilities.dp(2);
        parent.invalidate((int) progressRect.left - offset, (int) progressRect.top - offset, (int) progressRect.right + offset * 2, (int) progressRect.bottom + offset * 2);
    }

    public void setCheckBackground(boolean withRound, boolean animated) {
        if (checkDrawable == null) {
            checkDrawable = new CheckDrawable();
            checkBackgroundDrawable = Theme.createCircleDrawableWithIcon(AndroidUtilities.dp(48), checkDrawable, 0);
        }
        Theme.setCombinedDrawableColor(checkBackgroundDrawable, Theme.getColor(Theme.key_chat_mediaLoaderPhoto), false);
        Theme.setCombinedDrawableColor(checkBackgroundDrawable, Theme.getColor(Theme.key_chat_mediaLoaderPhotoIcon), true);
        if (currentDrawable != checkBackgroundDrawable) {
            setBackground(checkBackgroundDrawable, withRound, animated);
            checkDrawable.resetProgress(animated);
        }
    }

    public boolean isDrawCheckDrawable() {
        return currentDrawable == checkBackgroundDrawable;
    }

    public void setBackground(Drawable drawable, boolean withRound, boolean animated) {
        lastUpdateTime = System.currentTimeMillis();
        if (animated && currentDrawable != drawable) {
            previousDrawable = currentDrawable;
            previousWithRound = currentWithRound;
            animatedAlphaValue = 1.0f;
            setProgress(1, animated);
        } else {
            previousDrawable = null;
            previousWithRound = false;
        }
        currentWithRound = withRound;
        currentDrawable = drawable;
        if (!animated) {
            parent.invalidate();
        } else {
            invalidateParent();
        }
    }

    public void setMiniBackground(Drawable drawable, boolean withRound, boolean animated) {
        lastUpdateTime = System.currentTimeMillis();
        if (animated && currentMiniDrawable != drawable) {
            previousMiniDrawable = currentMiniDrawable;
            previousMiniWithRound = currentMiniWithRound;
            animatedAlphaValue = 1.0f;
            setProgress(1, animated);
        } else {
            previousMiniDrawable = null;
            previousMiniWithRound = false;
        }
        currentMiniWithRound = withRound;
        currentMiniDrawable = drawable;
        drawMiniProgress = previousMiniDrawable != null || currentMiniDrawable != null;
        if (drawMiniProgress && miniDrawBitmap == null) {
            try {
                miniDrawBitmap = Bitmap.createBitmap(AndroidUtilities.dp(48), AndroidUtilities.dp(48), Bitmap.Config.ARGB_8888);
                miniDrawCanvas = new Canvas(miniDrawBitmap);
            } catch (Throwable ignore) {

            }
        }
        if (!animated) {
            parent.invalidate();
        } else {
            invalidateParent();
        }
    }

    public boolean swapBackground(Drawable drawable) {
        if (currentDrawable != drawable) {
            currentDrawable = drawable;
            return true;
        }
        return false;
    }

    public boolean swapMiniBackground(Drawable drawable) {
        if (currentMiniDrawable != drawable) {
            currentMiniDrawable = drawable;
            drawMiniProgress = previousMiniDrawable != null || currentMiniDrawable != null;
            return true;
        }
        return false;
    }

    public float getAlpha() {
        return previousDrawable != null || currentDrawable != null ? animatedAlphaValue : 0.0f;
    }

    public void setOverrideAlpha(float alpha) {
        overrideAlpha = alpha;
    }

    public void draw(Canvas canvas) {
        if (drawMiniProgress && currentDrawable != null) {
            if (miniDrawCanvas != null) {
                miniDrawBitmap.eraseColor(0);
            }

            currentDrawable.setAlpha((int) (255 * overrideAlpha));
            if (miniDrawCanvas != null) {
                currentDrawable.setBounds(0, 0, (int) progressRect.width(), (int) progressRect.height());
                currentDrawable.draw(miniDrawCanvas);
            } else {
                currentDrawable.setBounds((int) progressRect.left, (int) progressRect.top, (int) progressRect.right, (int) progressRect.bottom);
                currentDrawable.draw(canvas);
            }

            int offset;
            int size;
            float cx;
            float cy;
            if (Math.abs(progressRect.width() - AndroidUtilities.dp(44)) < AndroidUtilities.density) {
                offset = 0;
                size = 20;
                cx = progressRect.centerX() + AndroidUtilities.dp(16 + offset);
                cy = progressRect.centerY() + AndroidUtilities.dp(16 + offset);
            } else {
                offset = 2;
                size = 22;
                cx = progressRect.centerX() + AndroidUtilities.dp(18);
                cy = progressRect.centerY() + AndroidUtilities.dp(18);
            }
            int halfSize = size / 2;

            float alpha = 1.0f;
            if (previousMiniDrawable != null && alphaForMiniPrevious) {
                alpha = animatedAlphaValue * overrideAlpha;
            }

            if (miniDrawCanvas != null) {
                miniDrawCanvas.drawCircle(AndroidUtilities.dp(18 + size + offset), AndroidUtilities.dp(18 + size + offset), AndroidUtilities.dp(halfSize + 1) * alpha, Theme.checkboxSquare_eraserPaint);
            } else {
                miniProgressBackgroundPaint.setColor(progressColor);
                if (previousMiniDrawable != null && currentMiniDrawable == null) {
                    miniProgressBackgroundPaint.setAlpha((int) (255 * animatedAlphaValue * overrideAlpha));
                } else {
                    miniProgressBackgroundPaint.setAlpha(255);
                }
                canvas.drawCircle(cx, cy, AndroidUtilities.dp(12), miniProgressBackgroundPaint);
            }

            if (miniDrawCanvas != null) {
                canvas.drawBitmap(miniDrawBitmap, (int) progressRect.left, (int) progressRect.top, null);
            }

            if (previousMiniDrawable != null) {
                if (alphaForMiniPrevious) {
                    previousMiniDrawable.setAlpha((int) (255 * animatedAlphaValue * overrideAlpha));
                } else {
                    previousMiniDrawable.setAlpha((int) (255 * overrideAlpha));
                }
                previousMiniDrawable.setBounds((int) (cx - AndroidUtilities.dp(halfSize) * alpha), (int) (cy - AndroidUtilities.dp(halfSize) * alpha), (int) (cx + AndroidUtilities.dp(halfSize) * alpha), (int) (cy + AndroidUtilities.dp(halfSize) * alpha));
                previousMiniDrawable.draw(canvas);
            }

            if (!hideCurrentDrawable && currentMiniDrawable != null) {
                if (previousMiniDrawable != null) {
                    currentMiniDrawable.setAlpha((int) (255 * (1.0f - animatedAlphaValue) * overrideAlpha));
                } else {
                    currentMiniDrawable.setAlpha((int) (255 * overrideAlpha));
                }
                currentMiniDrawable.setBounds((int) (cx - AndroidUtilities.dp(halfSize)), (int) (cy - AndroidUtilities.dp(halfSize)), (int) (cx + AndroidUtilities.dp(halfSize)), (int) (cy + AndroidUtilities.dp(halfSize)));
                currentMiniDrawable.draw(canvas);
            }

            if (currentMiniWithRound || previousMiniWithRound) {
                miniProgressPaint.setColor(progressColor);
                if (previousMiniWithRound) {
                    miniProgressPaint.setAlpha((int) (255 * animatedAlphaValue * overrideAlpha));
                } else {
                    miniProgressPaint.setAlpha((int) (255 * overrideAlpha));
                }
                float miniSweep = currentProgress < 0.02f ? Math.max(4, indetSweep) : Math.max(4, 360 * animatedProgressValue);
                cicleRect.set(cx - AndroidUtilities.dp(halfSize - 2) * alpha, cy - AndroidUtilities.dp(halfSize - 2) * alpha, cx + AndroidUtilities.dp(halfSize - 2) * alpha, cy + AndroidUtilities.dp(halfSize - 2) * alpha);
                canvas.drawArc(cicleRect, -90 + radOffset, miniSweep, false, miniProgressPaint);
                updateAnimation(true);
            } else {
                updateAnimation(false);
            }
        } else {
            if (previousDrawable != null) {
                if (alphaForPrevious) {
                    previousDrawable.setAlpha((int) (255 * animatedAlphaValue * overrideAlpha));
                } else {
                    previousDrawable.setAlpha((int) (255 * overrideAlpha));
                }
                previousDrawable.setBounds((int) progressRect.left, (int) progressRect.top, (int) progressRect.right, (int) progressRect.bottom);
                previousDrawable.draw(canvas);
            }

            if (!hideCurrentDrawable && currentDrawable != null) {
                if (previousDrawable != null) {
                    currentDrawable.setAlpha((int) (255 * (1.0f - animatedAlphaValue) * overrideAlpha));
                } else {
                    currentDrawable.setAlpha((int) (255 * overrideAlpha));
                }
                currentDrawable.setBounds((int) progressRect.left, (int) progressRect.top, (int) progressRect.right, (int) progressRect.bottom);
                currentDrawable.draw(canvas);
            }

            if (currentWithRound || previousWithRound) {
                Paint finalPaint;
                if (overridePaint != null) {
                    finalPaint = overridePaint;
                } else {
                    progressPaint.setColor(progressColor);
                    if (previousWithRound) {
                        progressPaint.setAlpha((int) (255 * animatedAlphaValue * overrideAlpha));
                    } else {
                        progressPaint.setAlpha((int) (255 * overrideAlpha));
                    }
                    finalPaint = progressPaint;
                }
                float mainSweep = currentProgress < 0.02f ? Math.max(4, indetSweep) : Math.max(4, 360 * animatedProgressValue);
                cicleRect.set(progressRect.left + diff, progressRect.top + diff, progressRect.right - diff, progressRect.bottom - diff);
                drawArc(canvas, cicleRect, -90 + radOffset, mainSweep, false, finalPaint);
                updateAnimation(true);
            } else {
                updateAnimation(false);
            }
        }
    }

    private final Path roundProgressRectPath = new Path();
    private final Matrix roundProgressRectMatrix = new Matrix();
    private final PathMeasure roundProgressRectPathMeasure = new PathMeasure();
    private final Path roundRectProgressPath = new Path();

    private boolean wavyProgress;
    private final RectF offOval = new RectF();
    private float wavePhaseAngle;
    private float wavyAmplitudeSmooth = 1f;
    private float bgThicknessScale;
    private final Path wavyProgressPath = new Path();
    private final PathMeasure wavyProgressPathMeasure = new PathMeasure();
    private final Path wavySegmentPath = new Path();
    private RectF wavyLastOval = new RectF();
    private int wavyLastGeneration;
    private float wavyLastAmplitudeSmooth = 1f;

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

        if (Math.abs(sweepAngle) > 0 && Math.abs(sweepAngle) < 360) {
            invalidateParent();
        }
    }

    private void drawArc(Canvas canvas, RectF oval, float startAngle, float sweepAngle, boolean useCenter, Paint paint) {
        if (wavyProgress && NekoConfig.wavyEnabled) {
            float inset = AndroidUtilities.dp(1f);
            RectF insetOval = new RectF(oval);
            insetOval.inset(inset, inset);
            float absSweep = Math.abs(sweepAngle);
            if (absSweep < 360) {
                int alpha = paint.getAlpha();
                paint.setAlpha(alpha * 40 / 100);
                float saveWidth = paint.getStrokeWidth();
                paint.setStrokeWidth(saveWidth * bgThicknessScale);
                float gap = 16;
                float dir = sweepAngle >= 0 ? 1 : -1;
                float bgSweep = 360 - absSweep - 2 * gap;
                if (bgSweep > 0) {
                    canvas.drawArc(insetOval, startAngle + sweepAngle + dir * gap, dir * bgSweep, useCenter, paint);
                }
                paint.setStrokeWidth(saveWidth);
                paint.setAlpha(alpha);
            }
            drawWavyArc(canvas, insetOval, startAngle, sweepAngle, paint);
        } else if (roundRectProgress) {
            float r = oval.height() * 0.32f;
            if (Math.abs(sweepAngle) == 360) {
                canvas.drawRoundRect(oval, r, r, paint);
                return;
            }
            float endAngle = startAngle + sweepAngle;
            float rotateAngle = (((int) (startAngle)) / 90) * 90 + 90;

            float pathAngleStart = -199 + rotateAngle;
            float percentFrom = (startAngle - pathAngleStart) / 360;
            float percentTo = (endAngle - pathAngleStart) / 360;

            roundProgressRectPath.rewind();
            roundProgressRectPath.addRoundRect(oval, r, r, Path.Direction.CW);
            roundProgressRectMatrix.reset();
            roundProgressRectMatrix.postRotate(rotateAngle, oval.centerX(), oval.centerY());
            roundProgressRectPath.transform(roundProgressRectMatrix);

            roundProgressRectPathMeasure.setPath(roundProgressRectPath, false);
            float length = roundProgressRectPathMeasure.getLength();

            roundRectProgressPath.reset();
            roundProgressRectPathMeasure.getSegment(length * percentFrom, length * percentTo, roundRectProgressPath, true);
            roundRectProgressPath.rLineTo(0, 0);
            canvas.drawPath(roundRectProgressPath, paint);
            if (percentTo > 1) {
                drawArc(canvas, oval, startAngle + 90, sweepAngle - 90, useCenter, paint);
            }
        } else {
            if (NekoConfig.wavyEnabled) {
                canvas.drawArc(oval, startAngle, sweepAngle, useCenter, paint);
            } else {
                offOval.set(oval);
                offOval.inset(AndroidUtilities.dp(1f), AndroidUtilities.dp(1f));
                canvas.drawArc(offOval, startAngle, sweepAngle, useCenter, paint);
            }
        }
    }

    public void setPaint(Paint paint) {
        overridePaint = paint;
    }

    public void setRoundRectProgress(boolean roundRectProgress) {
        this.roundRectProgress = roundRectProgress;
    }

    public void setWavyProgress(boolean wavyProgress) {
        this.wavyProgress = wavyProgress;
    }
}
