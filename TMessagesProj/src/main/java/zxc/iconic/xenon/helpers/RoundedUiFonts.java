package zxc.iconic.xenon.helpers;

import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextPaint;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import org.telegram.ui.Cells.ChatActionCell;
import org.telegram.ui.Cells.ChatMessageCell;
import org.telegram.ui.Components.TypefaceSpan;
import org.telegram.ui.Components.AnimatedTextView;

import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/** Rounded typography for chat controls and menus; message content keeps its own font. */
public final class RoundedUiFonts {
    private static final WeakHashMap<View, Boolean> popupRoots = new WeakHashMap<>();
    private static final WeakHashMap<View, Boolean> observed = new WeakHashMap<>();
    private static final ConcurrentHashMap<Class<?>, List<Field>> fields = new ConcurrentHashMap<>();

    private RoundedUiFonts() {}

    public static TextPaint newTextPaint(int flags) {
        TextPaint paint = ClientFonts.newTextPaint(flags);
        if (TypefaceHelper.supportsRoundedTypeface()) paint.setTypeface(TypefaceHelper.createRoundedTypeface(400, false));
        return paint;
    }

    public static CharSequence viaBot(String via, String username) {
        SpannableStringBuilder text = new SpannableStringBuilder(" " + via + " " + username);
        text.setSpan(new TypefaceSpan(TypefaceHelper.createRoundedTypeface(700, false)),
                text.length() - username.length(), text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    public static void observePopup(View root) {
        if (root == null) return;
        popupRoots.put(root, true);
        observe(root);
    }

    public static void observe(View root) {
        if (root == null || observed.containsKey(root)) return;
        observed.put(root, true);
        WeakReference<View> reference = new WeakReference<>(root);
        ViewTreeObserver.OnGlobalLayoutListener listener = () -> {
            View current = reference.get();
            if (current != null) apply(current);
        };
        root.getViewTreeObserver().addOnGlobalLayoutListener(listener);
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View view) { apply(view); }
            public void onViewDetachedFromWindow(View view) {
                if (view.getViewTreeObserver().isAlive()) {
                    view.getViewTreeObserver().removeOnGlobalLayoutListener(listener);
                }
                observed.remove(view);
                view.removeOnAttachStateChangeListener(this);
            }
        });
        apply(root);
    }

    private static Typeface rounded(Typeface current, boolean popup) {
        if ((!popup && TypefaceHelper.isCurrentRoundedTypeface(current))
                || current != null && (current.equals(Typeface.MONOSPACE)
                || current.equals(Typeface.create("monospace", current.getStyle())))) return current;
        int weight = current == null ? 400 : Build.VERSION.SDK_INT >= 28
                ? current.getWeight() : current.isBold() ? 700 : 400;
        return TypefaceHelper.createRoundedTypeface(popup ? Math.max(500, weight) : weight, current != null && current.isItalic());
    }

    private static void apply(View view) {
        apply(view, popupRoots.containsKey(view));
    }

    private static void apply(View view, boolean popup) {
        if (!TypefaceHelper.supportsRoundedTypeface() || view instanceof ClientFonts.PreserveTypeface
                || view instanceof ChatMessageCell || view instanceof ChatActionCell) return;
        boolean changed = false;
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            Typeface face = rounded(text.getTypeface(), popup);
            if (face != text.getTypeface()) { text.setTypeface(face); changed = true; }
        }
        if (view instanceof AnimatedTextView) {
            AnimatedTextView text = (AnimatedTextView) view;
            Typeface face = rounded(text.getPaint().getTypeface(), popup);
            if (face != text.getPaint().getTypeface()) { text.setTypeface(face); changed = true; }
        }
        // Inspect only app-owned TextPaint fields, never Android internals.
        for (Class<?> type = view.getClass(); type != null && (type.getName().startsWith("org.telegram.")
                || type.getName().startsWith("zxc.iconic.")); type = type.getSuperclass()) {
            for (Field field : fields.computeIfAbsent(type, key -> {
                ArrayList<Field> result = new ArrayList<>();
                for (Field candidate : key.getDeclaredFields()) {
                    if (Modifier.isStatic(candidate.getModifiers()) || !(TextPaint.class.isAssignableFrom(candidate.getType())
                            || AnimatedTextView.AnimatedTextDrawable.class.isAssignableFrom(candidate.getType()))) continue;
                    try { candidate.setAccessible(true); result.add(candidate); } catch (RuntimeException ignored) {}
                }
                return result;
            })) {
                try {
                    Object value = field.get(view);
                    if (value instanceof AnimatedTextView.AnimatedTextDrawable) {
                        AnimatedTextView.AnimatedTextDrawable drawable = (AnimatedTextView.AnimatedTextDrawable) value;
                        Typeface face = rounded(drawable.getPaint().getTypeface(), popup);
                        if (face != drawable.getPaint().getTypeface()) { drawable.setTypeface(face); changed = true; }
                        continue;
                    }
                    Paint paint = (Paint) value;
                    if (paint == null) continue;
                    Typeface face = rounded(paint.getTypeface(), popup);
                    if (face != paint.getTypeface()) { paint.setTypeface(face); changed = true; }
                } catch (IllegalAccessException ignored) {}
            }
        }
        if (changed) { view.requestLayout(); view.invalidate(); }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) apply(group.getChildAt(i), popup);
        }
    }
}
