package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Build;
import android.os.SystemClock;
import android.text.Editable;
import android.text.Layout;
import android.text.TextPaint;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.animation.PathInterpolator;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Iterator;

import zxc.iconic.xenon.NekoConfig;

public class TextAnimationEditText extends EditTextCaption {

    private static class CharAnim {
        int index;
        int endIndex;   // exclusive end — covers full grapheme (surrogate pair, ZWJ seq)
        long startTime;
        long duration;
        long blurDuration;
        ForegroundColorSpan span;
        boolean done;        // animation finished, waiting for span removal
        boolean removalPosted;
    }

    private static class DeletedCharAnim {
        String ch;
        float x, y;
        long startTime;
        long duration;
    }

    private static final PathInterpolator bezier = new PathInterpolator(0.47f, 0f, 0f, 1f);

    private final ArrayList<CharAnim> charAnims = new ArrayList<>();
    private final ArrayList<DeletedCharAnim> deletedCharAnims = new ArrayList<>();
    private final Paint animPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float animCursorX = -1;
    private int animCursorLine = -1;
    private int cursorColor = 0xff54a1db;

    private float targetCursorX = -1;
    private float targetCursorY = -1;
    private float targetCursorHeight = -1;
    private float animCursorY = -1;
    private float animCursorHeight = -1;
    private long lastFrameTime = 0;
    private float cursorMotion = 0;

    private boolean spaceJumpActive;
    private boolean cursorSuppressed;
    private float suppressedCursorWidth;
    private long spaceJumpTime;
    private boolean enterDiveActive;
    private long enterDiveTime;
    private boolean backspacePulseActive;
    private long backspacePulseTime;

    private static Field mShowCursorField;
    private Object editorObj;

    public TextAnimationEditText(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context, resourcesProvider);
        animPaint.setStyle(Paint.Style.FILL);
        cursorPaint.setStyle(Paint.Style.FILL);
        addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                if (!NekoConfig.textAnimationEnabled) return;
                if (count > 0) {
                    if (after < count) {
                        backspacePulseActive = true;
                        backspacePulseTime = SystemClock.elapsedRealtime();
                    }
                    if (start == 0 && count == s.length()) {
                        charAnims.clear();
                        deletedCharAnims.clear();
                        return;
                    }

                    Layout layout = getLayout();
                    if (layout == null) return;
                    long now = System.currentTimeMillis();
                    int maxCount = Math.min(count, 50);
                    for (int i = start; i < start + maxCount && i < s.length(); ) {
                        // s.length() as limit — never cut a grapheme cluster at the anim cap
                        int cLen = graphemeClusterLength(s, i, s.length());
                        DeletedCharAnim anim = new DeletedCharAnim();
                        anim.ch = s.subSequence(i, Math.min(i + cLen, s.length())).toString();
                        anim.x = layout.getPrimaryHorizontal(i);
                        int line = layout.getLineForOffset(i);
                        anim.y = layout.getLineBaseline(line);
                        anim.startTime = now;
                        anim.duration = Math.max(50, NekoConfig.textAnimFadeDuration);
                        deletedCharAnims.add(anim);
                        i += cLen;
                    }
                    invalidate();
                }
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (!NekoConfig.textAnimationEnabled) return;
                if (count > 0) {
                    for (int i = start; i < start + count && i < s.length(); i++) {
                        char c = s.charAt(i);
                        if (c == ' ') {
                            spaceJumpActive = true;
                            spaceJumpTime = SystemClock.elapsedRealtime();
                        } else if (c == '\n') {
                            enterDiveActive = true;
                            enterDiveTime = SystemClock.elapsedRealtime();
                        }
                    }
                }
                if (s.length() == 0) {
                    charAnims.clear();
                    deletedCharAnims.clear();
                    return;
                }
                Iterator<CharAnim> it = charAnims.iterator();
                while (it.hasNext()) {
                    CharAnim anim = it.next();
                    if (anim.index >= start && anim.index < start + before) {
                        it.remove();
                        continue; // already removed — don't call it.remove() again below
                    } else if (anim.index >= start) {
                        int shift = count - before;
                        anim.index += shift;
                        anim.endIndex += shift;
                    }
                    if (anim.index >= s.length()) {
                        it.remove();
                    }
                }
                if (count > 0 && s instanceof Editable) {
                    Editable editable = (Editable) s;
                    long now = System.currentTimeMillis();
                    for (int i = start; i < start + count; ) {
                        if (i >= s.length()) break;
                        // Determine grapheme cluster length: handle surrogate pairs and
                        // ZWJ sequences so emoji don't split into two \uFFFD replacement chars.
                        // s.length() as limit — an inserted char may merge with a following
                        // combining mark/emoji, and clusters must never be split.
                        int clusterLen = graphemeClusterLength(s, i, s.length());
                        int spanEnd = Math.min(i + clusterLen, s.length());
                        CharAnim anim = new CharAnim();
                        anim.index = i;
                        anim.endIndex = spanEnd;
                        anim.startTime = now;
                        anim.duration = Math.max(50, NekoConfig.textAnimFadeDuration);
                        anim.blurDuration = Math.max(50, NekoConfig.textAnimBlurDuration);
                        charAnims.add(anim);
                        ForegroundColorSpan span = new ForegroundColorSpan(Color.TRANSPARENT);
                        anim.span = span;
                        editable.setSpan(span, i, spanEnd, Editable.SPAN_EXCLUSIVE_EXCLUSIVE);
                        i += clusterLen;
                    }
                    invalidate();
                }
            }

            @Override
            public void afterTextChanged(Editable s) {
                Iterator<CharAnim> it = charAnims.iterator();
                while (it.hasNext()) {
                    CharAnim anim = it.next();
                    if (anim.index >= s.length() || anim.endIndex > s.length()) {
                        if (anim.span != null && s.getSpanStart(anim.span) >= 0) {
                            s.removeSpan(anim.span);
                        }
                        it.remove();
                    }
                }
            }
        });
    }

    @Override
    public void setCursorColor(int color) {
        super.setCursorColor(color);
        cursorColor = color;
    }

    private int getCorrectedOffset(int offset) {
        CharSequence s = getText();
        if (s == null || s.length() == 0) return offset;
        Layout layout = getLayout();
        if (layout == null) return offset;
        int line = layout.getLineForOffset(offset);
        int lineStart = layout.getLineStart(line);
        int lineEnd = layout.getLineEnd(line);
        int current = lineStart;
        while (current < lineEnd) {
            int len = graphemeClusterLength(s, current, lineEnd);
            if (offset > current && offset <= current + len) {
                return current + len;
            }
            current += len;
        }
        return offset;
    }

    @Override
    protected void onSelectionChanged(int selStart, int selEnd) {
        super.onSelectionChanged(selStart, selEnd);
        if (!NekoConfig.textAnimationEnabled || NekoConfig.textAnimCursorSpeed <= 0) return;
        Layout layout = getLayout();
        if (layout == null) return;

        int correctedOffset = getCorrectedOffset(selStart);
        float newX = layout.getPrimaryHorizontal(correctedOffset);
        int newLine = layout.getLineForOffset(correctedOffset);

        float lineTop = layout.getLineTop(newLine);
        float lineBottom = layout.getLineBottom(newLine);
        float newY = lineTop;
        float newHeight = lineBottom - lineTop;

        animCursorLine = newLine;
        lastFrameTime = SystemClock.elapsedRealtime();

        if (animCursorX < 0) {
            animCursorX = newX;
            animCursorY = newY;
            animCursorHeight = newHeight;
            targetCursorX = newX;
            targetCursorY = newY;
            targetCursorHeight = newHeight;
            invalidate();
            return;
        }

        targetCursorX = newX;
        targetCursorY = newY;
        targetCursorHeight = newHeight;
        invalidate();
    }

    private boolean isCursorBlinkVisible() {
        try {
            if (mShowCursorField == null) {
                Field mEditorField = TextView.class.getDeclaredField("mEditor");
                mEditorField.setAccessible(true);
                editorObj = mEditorField.get(this);
                mShowCursorField = editorObj.getClass().getDeclaredField("mShowCursor");
                mShowCursorField.setAccessible(true);
            }
            if (mShowCursorField != null && editorObj != null) {
                long mShowCursor = mShowCursorField.getLong(editorObj);
                return (SystemClock.uptimeMillis() - mShowCursor) % (2 * 500) < 500 && isFocused();
            }
        } catch (Exception e) {}
        return true;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        boolean smoothCursor = NekoConfig.textAnimationEnabled && NekoConfig.textAnimCursorSpeed > 0;
        float origWidth = getCursorWidth();
        if (smoothCursor) {
            // Only touch the real cursor when the state actually changes — calling
            // setCursorWidth every frame keeps invalidating and makes it flicker.
            if (!cursorSuppressed && origWidth > 0) {
                cursorSuppressed = true;
                suppressedCursorWidth = origWidth;
                setCursorWidth(0);
            }
        } else if (cursorSuppressed) {
            cursorSuppressed = false;
            setCursorWidth(suppressedCursorWidth);
        }
        super.onDraw(canvas);
        if (smoothCursor) {
            drawAnimatedCursor(canvas);
        }
        drawCharAnimations(canvas);
    }

    private void drawAnimatedCursor(Canvas canvas) {
        if (getSelectionStart() != getSelectionEnd()) return;
        Layout layout = getLayout();
        if (layout == null) return;

        long now = SystemClock.elapsedRealtime();
        if (lastFrameTime == 0) {
            lastFrameTime = now;
        }
        long dt = now - lastFrameTime;
        lastFrameTime = now;
        if (dt > 100) {
            dt = 16;
        }
        float deltaTimeFactor = dt / 16.0f;

        if (targetCursorX < 0) {
            return;
        }

        float cursorSpeed = NekoConfig.textAnimCursorSpeed;
        float factor = Math.min(1.0f, (cursorSpeed / 100.0f) * deltaTimeFactor * 0.22f);

        float dx = targetCursorX - animCursorX;
        float dy = targetCursorY - animCursorY;
        float dh = targetCursorHeight - animCursorHeight;

        boolean needsInvalidate = false;

        if (Math.abs(dx) > 0.01f || Math.abs(dy) > 0.01f || Math.abs(dh) > 0.01f) {
            animCursorX += dx * factor;
            animCursorY += dy * factor;
            animCursorHeight += dh * factor;
            needsInvalidate = true;
        } else {
            animCursorX = targetCursorX;
            animCursorY = targetCursorY;
            animCursorHeight = targetCursorHeight;
        }

        // Update motion
        float targetMotion = (float) Math.sqrt(dx * dx + dy * dy);
        cursorMotion += (targetMotion - cursorMotion) * Math.min(1.0f, 0.32f * deltaTimeFactor);
        if (cursorMotion > 0.01f) {
            needsInvalidate = true;
        } else {
            cursorMotion = 0;
        }

        // Triggers
        float spaceJumpWidth = 0;
        if (spaceJumpActive) {
            long spaceTimeElapsed = now - spaceJumpTime;
            if (spaceTimeElapsed < 300) {
                float progress = spaceTimeElapsed / 300.0f;
                float spaceFactor = (float) Math.sin(progress * Math.PI);
                spaceJumpWidth = spaceFactor * AndroidUtilities.dp(8);
                needsInvalidate = true;
            } else {
                spaceJumpActive = false;
            }
        }

        float enterDiveOffset = 0;
        if (enterDiveActive) {
            long enterTimeElapsed = now - enterDiveTime;
            if (enterTimeElapsed < 400) {
                float progress = enterTimeElapsed / 400.0f;
                float enterFactor = (float) Math.sin(progress * Math.PI);
                enterDiveOffset = enterFactor * AndroidUtilities.dp(12);
                needsInvalidate = true;
            } else {
                enterDiveActive = false;
            }
        }

        float backspaceSqueezeX = 0;
        float backspaceStretchY = 0;
        if (backspacePulseActive) {
            long backspaceTimeElapsed = now - backspacePulseTime;
            if (backspaceTimeElapsed < 300) {
                float progress = backspaceTimeElapsed / 300.0f;
                float backspaceFactor = (float) Math.sin(progress * Math.PI);
                backspaceSqueezeX = -backspaceFactor * AndroidUtilities.dp(1.0f);
                backspaceStretchY = backspaceFactor * AndroidUtilities.dp(4.0f);
                needsInvalidate = true;
            } else {
                backspacePulseActive = false;
            }
        }

        if (needsInvalidate) {
            invalidate();
        }

        if (!isCursorBlinkVisible()) return;

        // Motion stretch
        float stretchX = 0;
        float stretchY = 0;
        if (cursorSpeed > 0) {
            stretchX = Math.abs(dx) * 0.25f;
            float maxStretchX = AndroidUtilities.dp(12);
            if (stretchX > maxStretchX) {
                stretchX = maxStretchX;
            }

            stretchY = Math.abs(dy) * 0.2f;
            float maxStretchY = animCursorHeight * 0.3f;
            if (stretchY > maxStretchY) {
                stretchY = maxStretchY;
            }
        }

        canvas.save();
        int voffsetCursor = 0;
        if ((getGravity() & Gravity.VERTICAL_GRAVITY_MASK) != Gravity.TOP) {
            voffsetCursor = getTotalPaddingTop() - getExtendedPaddingTop();
        }
        canvas.translate(getPaddingLeft(), getExtendedPaddingTop() + voffsetCursor);
        cursorPaint.setColor(cursorColor);

        float baseWidth = AndroidUtilities.dp(2);
        float left = animCursorX;
        float right = animCursorX + baseWidth;

        if (dx > 0) {
            left -= stretchX;
        } else if (dx < 0) {
            right += stretchX;
        }

        float horizontalPulse = spaceJumpWidth + backspaceSqueezeX;
        left -= horizontalPulse / 2.0f;
        right += horizontalPulse / 2.0f;

        float top = animCursorY;
        float bottom = animCursorY + animCursorHeight;

        if (dy > 0) {
            top -= stretchY;
        } else if (dy < 0) {
            bottom += stretchY;
        }

        float verticalShrink = stretchX * 0.2f;
        top += verticalShrink;
        bottom -= verticalShrink;

        top -= backspaceStretchY / 2.0f;
        bottom += backspaceStretchY / 2.0f;

        top += enterDiveOffset;
        bottom += enterDiveOffset;

        if (top > bottom - AndroidUtilities.dp(4)) {
            float centerY = (top + bottom) / 2.0f;
            top = centerY - AndroidUtilities.dp(2);
            bottom = centerY + AndroidUtilities.dp(2);
        }

        RectF cursorRect = new RectF(left, top, right, bottom);
        float rx = (right - left) / 2.0f;
        float ry = rx;
        canvas.drawRoundRect(cursorRect, rx, ry, cursorPaint);
        canvas.restore();
    }

    private void drawCharAnimations(Canvas canvas) {
        if (!NekoConfig.textAnimationEnabled || (charAnims.isEmpty() && deletedCharAnims.isEmpty())) return;
        Layout layout = getLayout();
        if (layout == null) return;

        long now = System.currentTimeMillis();

        canvas.save();
        int voffsetText = 0;
        if ((getGravity() & Gravity.VERTICAL_GRAVITY_MASK) != Gravity.TOP) {
            voffsetText = getTotalPaddingTop() - getExtendedPaddingTop();
        }

        int scrollX = getScrollX();
        int scrollY = getScrollY();
        canvas.clipRect(
                scrollX + getPaddingLeft(),
                scrollY + getExtendedPaddingTop(),
                scrollX + getWidth() - getPaddingRight(),
                scrollY + getHeight() - getExtendedPaddingBottom()
        );

        canvas.translate(getPaddingLeft(), getExtendedPaddingTop() + voffsetText);

        TextPaint textPaint = getPaint();
        animPaint.setColor(getCurrentTextColor());
        animPaint.setTypeface(getTypeface());
        animPaint.setTextSize(getTextSize());
        animPaint.setLetterSpacing(textPaint.getLetterSpacing());
        animPaint.setFontFeatureSettings(textPaint.getFontFeatureSettings());
        animPaint.setStyle(Paint.Style.FILL);

        int blur = NekoConfig.textAnimBlurStrength;

        // --- 1. APPEARANCE ANIMATION ---
        CharSequence text = getText();
        if (text != null && !charAnims.isEmpty()) {
            Iterator<CharAnim> it = charAnims.iterator();
            while (it.hasNext()) {
                CharAnim anim = it.next();
                if (anim.index >= text.length()) {
                    it.remove();
                    continue;
                }

                long elapsed = now - anim.startTime;
                float linearProgress = Math.min(1f, elapsed / (float) Math.max(1, anim.duration));
                if (linearProgress >= 1) {
                    if (!anim.done) {
                        anim.done = true;
                        anim.removalPosted = true;
                        post(() -> {
                            Editable editable = getText();
                            if (editable != null && anim.span != null && editable.getSpanStart(anim.span) >= 0) {
                                editable.removeSpan(anim.span);
                            }
                            charAnims.remove(anim);
                        });
                    }

                    // Keep drawing at full alpha until the transparent span is
                    // actually removed, otherwise the char blinks invisible for a frame.
                    float x = layout.getPrimaryHorizontal(anim.index);
                    int line = layout.getLineForOffset(anim.index);
                    float y = layout.getLineBaseline(line);
                    int safeEnd = Math.min(anim.endIndex, text.length());
                    String ch = text.subSequence(anim.index, safeEnd).toString();
                    animPaint.setAlpha(255);
                    animPaint.setMaskFilter(null);
                    canvas.drawText(ch, x, y, animPaint);
                    continue;
                }

                float progress = bezier.getInterpolation(linearProgress);

                float x = layout.getPrimaryHorizontal(anim.index);
                int line = layout.getLineForOffset(anim.index);
                float y = layout.getLineBaseline(line);
                String ch = text.subSequence(anim.index, Math.min(anim.endIndex, text.length())).toString();

                int alpha = (int) (progress * 255);
                animPaint.setAlpha(alpha);
                animPaint.setMaskFilter(null);

                // Skip blur for multi-char clusters (emoji surrogate pairs, ZWJ seqs).
                // BlurMaskFilter has no effect on bitmap glyphs and just wastes time.
                final boolean isSingleScalarChar = (anim.endIndex - anim.index) == 1;
                if (blur > 0 && isSingleScalarChar && Build.VERSION.SDK_INT >= 26) {
                    float blurLinear = Math.min(1f, elapsed / (float) Math.max(1, anim.blurDuration));
                    float blurProgress = bezier.getInterpolation(blurLinear);
                    float blurRadius = blur * (1 - blurProgress);
                    if (blurRadius > 0.5f) {
                        animPaint.setMaskFilter(new BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL));
                    }
                }

                canvas.drawText(ch, x, y, animPaint);
            }
        }

        // --- 2. DISAPPEARANCE ANIMATION (Reverse blur) ---
        if (!deletedCharAnims.isEmpty()) {
            Iterator<DeletedCharAnim> delIt = deletedCharAnims.iterator();
            while (delIt.hasNext()) {
                DeletedCharAnim anim = delIt.next();

                long elapsed = now - anim.startTime;
                float linearProgress = Math.min(1f, elapsed / (float) Math.max(1, anim.duration));
                float progress = bezier.getInterpolation(linearProgress);

                if (progress >= 1) {
                    delIt.remove();
                    continue;
                }

                int alpha = (int) ((1 - progress) * 255);
                animPaint.setAlpha(alpha);
                animPaint.setMaskFilter(null);

                if (blur > 0 && Build.VERSION.SDK_INT >= 26) {
                    float blurRadius = blur * progress;
                    if (blurRadius > 0.5f) {
                        animPaint.setMaskFilter(new BlurMaskFilter(blurRadius, BlurMaskFilter.Blur.NORMAL));
                    }
                }

                canvas.drawText(anim.ch, anim.x, anim.y, animPaint);
            }
        }

        animPaint.setMaskFilter(null);
        canvas.restore();

        if (!charAnims.isEmpty() || !deletedCharAnims.isEmpty()) {
            invalidate();
        }
    }

    /**
     * Returns the number of {@code char} values that form one grapheme cluster
     * starting at {@code pos} in {@code s}. The whole cluster is animated and
     * drawn as a single unit — splitting it would render pieces that the shaper
     * would never produce on their own (half a flag, a lone skin tone, etc.).
     * Covers:
     * <ul>
     *   <li>Surrogate pairs (basic emoji, e.g. \uD83D\uDE00 = \uD83D + \uDE00)</li>
     *   <li>Regional-indicator pairs (flags 🇺🇸 — two RI code points = ONE glyph)</li>
     *   <li>Emoji skin-tone modifiers (U+1F3FB..U+1F3FF, e.g. 👍🏽)</li>
     *   <li>Variation selectors (\uFE00..\uFE0F) and Combining Enclosing Keycap (\u20E3)</li>
     *   <li>ZWJ sequences (e.g. family emoji: base + \u200D + another emoji ...)</li>
     *   <li>Tag sequences (U+E0020..U+E007F, e.g. England flag 🏴󠁧󠁢󠁥󠁮󠁧󠁿)</li>
     *   <li>General combining marks (accents, Indic matras, Zalgo text)</li>
     * </ul>
     * All other characters return 1.
     *
     * @param limit soft upper bound (exclusive) on how many chars we may consume;
     *              cluster integrity is allowed to cross it so a cluster is never split
     */
    private static int graphemeClusterLength(CharSequence s, int pos, int limit) {
        int textLen = s.length();
        if (pos >= textLen) return 1;
        int firstCp = Character.codePointAt(s, pos);
        int len = Character.charCount(firstCp);

        // Regional-indicator pair: consume exactly two RIs so a flag is one unit.
        if (firstCp >= 0x1F1E6 && firstCp <= 0x1F1FF) {
            int next = pos + len;
            if (next < textLen) {
                int secondCp = Character.codePointAt(s, next);
                if (secondCp >= 0x1F1E6 && secondCp <= 0x1F1FF) {
                    len += Character.charCount(secondCp);
                }
            }
            return len;
        }

        while (pos + len < textLen && pos + len < limit) {
            int cp = Character.codePointAt(s, pos + len);
            if (cp == 0x200D) {
                // Zero-Width Joiner: absorb ZWJ + following code point
                if (pos + len + 1 >= textLen) break;
                len += 1 + Character.charCount(Character.codePointAt(s, pos + len + 1));
            } else if (cp >= 0xFE00 && cp <= 0xFE0F) {
                // Variation selectors (emoji/text presentation)
                len += 1;
            } else if (cp == 0x20E3) {
                // Combining enclosing keycap (e.g. 1️⃣)
                len += 1;
            } else if (cp >= 0x1F3FB && cp <= 0x1F3FF) {
                // Emoji skin-tone modifier (Fitzpatrick)
                len += Character.charCount(cp);
            } else if (cp >= 0xE0020 && cp <= 0xE007F) {
                // Tag characters (subdivision flag sequences end with U+E007F)
                len += 1;
            } else {
                int type = Character.getType(cp);
                if (type == Character.NON_SPACING_MARK
                        || type == Character.COMBINING_SPACING_MARK
                        || type == Character.ENCLOSING_MARK) {
                    // Combining marks must stay glued to their base glyph
                    len += Character.charCount(cp);
                } else {
                    break;
                }
            }
        }
        return len;
    }
}
