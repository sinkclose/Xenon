package zxc.iconic.xenon.helpers;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.text.style.MetricAffectingSpan;
import android.text.style.TextAppearanceSpan;
import android.view.View;

import androidx.core.content.ContextCompat;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.TextStyleSpan;
import org.telegram.ui.Components.spoilers.SpoilerEffect;

import java.util.List;
import java.util.WeakHashMap;

import zxc.iconic.xenon.NekoConfig;

public class SpoilerHelper {

    private static final class State {
        int baseColor;
        float prevLeft = Float.NaN;
        float prevRight = Float.NaN;
        float nextLeft = Float.NaN;
        float nextRight = Float.NaN;
    }

    private static final WeakHashMap<SpoilerEffect, State> states = new WeakHashMap<>();
    private static final Paint solidPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private static final Path tempPath = new Path();
    private static final RectF tempRect = new RectF();
    private static final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private static Drawable eyeDrawable;

    private static State stateOf(SpoilerEffect e) {
        State s = states.get(e);
        if (s == null) {
            s = new State();
            states.put(e, s);
        }
        return s;
    }

    private static int getMediaSpoilerMode() {
        return NekoConfig.mediaSpoilerMode;
    }

    private static int getTextSpoilerMode() {
        return NekoConfig.textSpoilerMode;
    }

    public static boolean drawSolidIfOverridden(Canvas canvas, SpoilerEffect effect, View parent, int lastColor, int mAlpha) {
        int mode = getTextSpoilerMode();
        if (mode == NekoConfig.TEXT_SPOILER_DEFAULT) return false;
        Rect bounds = effect.getBounds();
        if (bounds.isEmpty()) return true;

        if (mode == NekoConfig.TEXT_SPOILER_EPSTEIN) {
            solidPaint.setColor(Color.BLACK);
            solidPaint.setAlpha(mAlpha);
            canvas.drawRect(bounds, solidPaint);
            return true;
        }

        State state = stateOf(effect);
        if (effect.getRippleProgress() < 0) state.baseColor = lastColor;
        float alphaScale = isOutgoingBubble(parent) ? 0.45f : 0.25f;
        solidPaint.setColor(state.baseColor);
        solidPaint.setAlpha((int) (0xFF * alphaScale));

        float r = AndroidUtilities.dp(4);
        float tlR = state.prevLeft <= bounds.left ? 0 : r;
        float trR = state.prevRight >= bounds.right ? 0 : r;
        float blR = state.nextLeft <= bounds.left ? 0 : r;
        float brR = state.nextRight >= bounds.right ? 0 : r;

        tempPath.rewind();
        tempRect.set(bounds);
        tempPath.addRoundRect(tempRect, new float[]{tlR, tlR, trR, trR, brR, brR, blR, blR}, Path.Direction.CW);
        canvas.drawPath(tempPath, solidPaint);

        if (state.prevLeft < bounds.left)
            drawFillet(canvas, bounds.left, bounds.top, -1, 1, r);
        if (state.prevRight > bounds.right)
            drawFillet(canvas, bounds.right, bounds.top, 1, 1, r);
        if (state.nextLeft < bounds.left)
            drawFillet(canvas, bounds.left, bounds.bottom, -1, -1, r);
        if (state.nextRight > bounds.right)
            drawFillet(canvas, bounds.right, bounds.bottom, 1, -1, r);

        return true;
    }

    public static boolean drawMediaSpoilerIfOverridden(Canvas canvas, ChatMessageCell cell) {
        int mode = getMediaSpoilerMode();
        if (mode == NekoConfig.MEDIA_SPOILER_TELEGRAM) return false;

        var photoImage = cell.getPhotoImage();
        float left = photoImage.getImageX();
        float top = photoImage.getImageY();
        float right = photoImage.getImageX2();
        float bottom = photoImage.getImageY2();
        float clampedAlpha = Math.max(0, Math.min(1, photoImage.getAlpha()));

        solidPaint.setColor(Color.BLACK);
        solidPaint.setAlpha((int) (110 * clampedAlpha));
        canvas.drawRect(left, top, right, bottom, solidPaint);

        var msg = cell.getMessageObject();
        boolean isSelfDestruct = msg != null && msg.needDrawBluredPreview();
        if (isSelfDestruct) return true;

        int loaderColor = Theme.getColor(Theme.key_chat_mediaLoaderPhoto, cell.getResourcesProvider());
        int iconColor = Theme.getColor(Theme.key_chat_mediaLoaderPhotoIcon, cell.getResourcesProvider());
        float cx = (left + right) / 2f;
        float cy = (top + bottom) / 2f;
        float pad = AndroidUtilities.dp(8);

        if (mode == NekoConfig.MEDIA_SPOILER_CIRCLE) {
            float radius = AndroidUtilities.dp(22);
            if (right - left >= radius * 2 + pad && bottom - top >= radius * 2 + pad) {
                solidPaint.setColor(iconColor);
                solidPaint.setAlpha((int) (24 * clampedAlpha));
                canvas.drawCircle(cx, cy, radius, solidPaint);
                Drawable eye = getEyeDrawable();
                float s = AndroidUtilities.dp(24);
                eye.setBounds((int) (cx - s / 2f), (int) (cy - s / 2f), (int) (cx + s / 2f), (int) (cy + s / 2f));
                eye.setColorFilter(new PorterDuffColorFilter(iconColor | 0xFF000000, PorterDuff.Mode.SRC_IN));
                eye.setAlpha((int) (255 * clampedAlpha));
                eye.draw(canvas);
            }
            return true;
        }

        String label = LocaleController.getString(R.string.MediaSpoilerLabel).toUpperCase();
        float padH = AndroidUtilities.dp(16);
        float padV = AndroidUtilities.dp(7);
        labelPaint.setTextSize(AndroidUtilities.dp(12));
        labelPaint.setTypeface(Typeface.DEFAULT_BOLD);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = labelPaint.getFontMetrics();
        float textW = labelPaint.measureText(label);
        float textH = fm.descent - fm.ascent;
        float pillW = textW + padH * 2;
        float pillH = textH + padV * 2;
        if (right - left >= pillW + pad && bottom - top >= pillH + pad) {
            solidPaint.setColor(loaderColor);
            solidPaint.setAlpha((int) (Color.alpha(loaderColor) * clampedAlpha));
            tempRect.set(cx - pillW / 2f, cy - pillH / 2f, cx + pillW / 2f, cy + pillH / 2f);
            canvas.drawRoundRect(tempRect, pillH / 2f, pillH / 2f, solidPaint);
            labelPaint.setColor(iconColor);
            labelPaint.setAlpha((int) (Color.alpha(iconColor) * clampedAlpha));
            canvas.drawText(label, cx, cy - (fm.ascent + fm.descent) / 2f, labelPaint);
        }
        return true;
    }

    public static void linkNeighbors(List<SpoilerEffect> spoilers) {
        for (int i = 0; i < spoilers.size(); i++) {
            SpoilerEffect a = spoilers.get(i);
            Rect ab = a.getBounds();
            if (ab.isEmpty()) continue;
            boolean merged;
            do {
                merged = false;
                for (int j = 0; j < spoilers.size(); j++) {
                    if (i == j) continue;
                    SpoilerEffect b = spoilers.get(j);
                    Rect bb = b.getBounds();
                    if (bb.isEmpty()) continue;
                    if (ab.top != bb.top || ab.bottom != bb.bottom) continue;
                    if (ab.right < bb.left || ab.left > bb.right) continue;
                    a.setBounds(Math.min(ab.left, bb.left), ab.top, Math.max(ab.right, bb.right), ab.bottom);
                    b.setBounds(0, 0, 0, 0);
                    merged = true;
                }
            } while (merged);
        }

        float snap = AndroidUtilities.dp(4);
        for (int i = 0; i < spoilers.size(); i++) {
            SpoilerEffect a = spoilers.get(i);
            Rect ab = a.getBounds();
            if (ab.isEmpty()) continue;
            for (int j = 0; j < spoilers.size(); j++) {
                if (i == j) continue;
                SpoilerEffect b = spoilers.get(j);
                Rect bb = b.getBounds();
                if (bb.isEmpty()) continue;
                if (Math.abs(ab.bottom - bb.top) > 1 && Math.abs(bb.bottom - ab.top) > 1) continue;
                if (ab.left >= bb.right || ab.right <= bb.left) continue;
                float dl = Math.abs(ab.left - bb.left);
                if (dl > 0.001f && dl <= snap) {
                    int nl = Math.max(ab.left, bb.left);
                    a.setBounds(nl, ab.top, ab.right, ab.bottom);
                    b.setBounds(nl, bb.top, bb.right, bb.bottom);
                }
                float dr = Math.abs(ab.right - bb.right);
                if (dr > 0.001f && dr <= snap) {
                    int nr = Math.min(ab.right, bb.right);
                    a.setBounds(ab.left, ab.top, nr, ab.bottom);
                    b.setBounds(bb.left, bb.top, nr, bb.bottom);
                }
            }
        }

        for (SpoilerEffect s : spoilers) {
            Rect sb = s.getBounds();
            if (sb.isEmpty()) continue;
            State st = stateOf(s);
            st.prevLeft = Float.NaN;
            st.prevRight = Float.NaN;
            st.nextLeft = Float.NaN;
            st.nextRight = Float.NaN;
        }

        for (int i = 0; i < spoilers.size(); i++) {
            SpoilerEffect a = spoilers.get(i);
            Rect ab = a.getBounds();
            if (ab.isEmpty()) continue;
            State ast = stateOf(a);
            for (int j = 0; j < spoilers.size(); j++) {
                if (i == j) continue;
                SpoilerEffect b = spoilers.get(j);
                Rect bb = b.getBounds();
                if (bb.isEmpty()) continue;
                if (ab.left >= bb.right || ab.right <= bb.left) continue;
                if (Math.abs(ab.bottom - bb.top) <= 1) {
                    if (Float.isNaN(ast.nextLeft)) ast.nextLeft = bb.left;
                    else ast.nextLeft = Math.min(ast.nextLeft, bb.left);
                    if (Float.isNaN(ast.nextRight)) ast.nextRight = bb.right;
                    else ast.nextRight = Math.max(ast.nextRight, bb.right);
                }
                if (Math.abs(bb.bottom - ab.top) <= 1) {
                    if (Float.isNaN(ast.prevLeft)) ast.prevLeft = bb.left;
                    else ast.prevLeft = Math.min(ast.prevLeft, bb.left);
                    if (Float.isNaN(ast.prevRight)) ast.prevRight = bb.right;
                    else ast.prevRight = Math.max(ast.prevRight, bb.right);
                }
            }
        }
    }

    private static boolean isOutgoingBubble(View view) {
        View current = view;
        while (current != null) {
            if (current instanceof ChatMessageCell) {
                var msg = ((ChatMessageCell) current).getMessageObject();
                return msg != null && msg.isOutOwner();
            }
            current = (View) current.getParent();
        }
        return false;
    }

    private static void drawFillet(Canvas canvas, float cx, float cy, int dx, int dy, float r) {
        tempPath.rewind();
        tempPath.moveTo(cx, cy + dy * r);
        float ox = cx + dx * r;
        float oy = cy + dy * r;
        tempRect.set(ox - r, oy - r, ox + r, oy + r);
        tempPath.arcTo(tempRect, dx < 0 ? 0 : 180, 90 * dx * dy);
        tempPath.lineTo(cx, cy);
        tempPath.close();
        canvas.drawPath(tempPath, solidPaint);
    }

    private static Drawable getEyeDrawable() {
        if (eyeDrawable == null) {
            eyeDrawable = ContextCompat.getDrawable(ApplicationLoader.applicationContext, R.drawable.menu_hide_gift).mutate();
        }
        return eyeDrawable;
    }

    public static class TransparentMetricSpan extends MetricAffectingSpan {
        private final TextStyleSpan source;

        public TransparentMetricSpan(TextStyleSpan source) {
            this.source = source;
        }

        @Override
        public void updateMeasureState(TextPaint p) {
            source.updateMeasureState(p);
        }

        @Override
        public void updateDrawState(TextPaint p) {
            source.updateDrawState(p);
            p.setColor(Color.TRANSPARENT);
            p.setAlpha(0);
        }
    }
}
