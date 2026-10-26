package zxc.iconic.xenon.helpers;

import android.app.Activity;
import android.app.Application;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.text.TextPaint;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.Theme;

import java.io.File;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;

/** Application typography, using app-owned paints and public TextView APIs (no framework font-map mutation). */
public final class ClientFonts {
    private static Application application;
    private static volatile Typeface family;
    private static volatile String selected = "";
    private static volatile int generation;
    private static final ArrayList<ClientFontFiles.Entry> entries = new ArrayList<>();
    private static final Map<Integer, Typeface> variants = new HashMap<>();
    private static final WeakHashMap<Typeface, Style> customStyles = new WeakHashMap<>();
    private static final WeakHashMap<Paint, FontState> paints = new WeakHashMap<>();
    private static final WeakHashMap<TextView, FontState> labels = new WeakHashMap<>();
    private static final WeakHashMap<View, Integer> visited = new WeakHashMap<>();
    private static final WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener> windows = new WeakHashMap<>();
    private static final WeakHashMap<View, ViewTreeObserver.OnGlobalLayoutListener> dialogWindows = new WeakHashMap<>();
    private static final ConcurrentHashMap<Class<?>, List<Field>> paintFields = new ConcurrentHashMap<>();

    private static final class Style {
        final int weight; final boolean italic;
        Style(int weight, boolean italic) { this.weight = weight; this.italic = italic; }
    }

    private static final class FontState {
        Typeface original, applied;
        FontState(Typeface original) { this.original = original; }
    }

    /** Text that belongs to a document or a font sample keeps its own typeface. */
    public interface PreserveTypeface {}

    public static void applyToView(View view) {
        if (view != null && family != null) applyTree(view, false);
    }

    private ClientFonts() {}

    public static void initialize(Application app) {
        if (application != null) return;
        application = app;
        try {
            JSONArray saved = new JSONArray(preferences().getString("fonts", "[]"));
            for (int i = 0; i < saved.length(); i++) {
                JSONObject item = saved.getJSONObject(i);
                String id = item.getString("id");
                if (!id.matches("[a-f0-9]{64}")) continue;
                ClientFontFiles.Entry entry = new ClientFontFiles.Entry(id, item.getString("name"));
                if (entry.file(directory()).isFile() && find(id) == null) entries.add(entry);
            }
            String id = preferences().getString("selected", "");
            ClientFontFiles.Entry entry = find(id);
            if (entry != null) {
                family = load(entry);
                selected = id;
                generation++;
                AndroidUtilities.clearTypefaceCache();
            }
        } catch (Exception error) {
            family = null;
            selected = "";
            FileLog.e(error);
        }
        app.registerActivityLifecycleCallbacks(new Application.ActivityLifecycleCallbacks() {
            public void onActivityCreated(Activity activity, Bundle state) {
                WeakReference<Activity> reference = new WeakReference<>(activity);
                ViewTreeObserver.OnGlobalLayoutListener listener = () -> {
                    Activity current = reference.get();
                    if (current != null && family != null) applyTree(current.getWindow().getDecorView(), false);
                };
                windows.put(activity, listener);
                activity.getWindow().getDecorView().getViewTreeObserver().addOnGlobalLayoutListener(listener);
            }
            public void onActivityResumed(Activity activity) {
                if (family != null) applyTree(activity.getWindow().getDecorView(), true);
            }
            public void onActivityDestroyed(Activity activity) {
                ViewTreeObserver.OnGlobalLayoutListener listener = windows.remove(activity);
                if (listener != null && activity.getWindow().getDecorView().getViewTreeObserver().isAlive()) {
                    activity.getWindow().getDecorView().getViewTreeObserver().removeOnGlobalLayoutListener(listener);
                }
            }
            public void onActivityStarted(Activity activity) {}
            public void onActivityPaused(Activity activity) {}
            public void onActivityStopped(Activity activity) {}
            public void onActivitySaveInstanceState(Activity activity, Bundle state) {}
        });
    }

    private static android.content.SharedPreferences preferences() {
        return application.getSharedPreferences("client_fonts", Activity.MODE_PRIVATE);
    }

    public static File directory() { return new File(application.getFilesDir(), "client-fonts"); }
    public static int generation() { return generation; }
    public static String selectedId() { return selected; }

    public static void observeWindow(View root) {
        if (dialogWindows.containsKey(root)) return;
        WeakReference<View> reference = new WeakReference<>(root);
        ViewTreeObserver.OnGlobalLayoutListener listener = () -> {
            View current = reference.get();
            if (current != null && family != null) applyTree(current, false);
        };
        dialogWindows.put(root, listener);
        root.getViewTreeObserver().addOnGlobalLayoutListener(listener);
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            public void onViewAttachedToWindow(View view) {}
            public void onViewDetachedFromWindow(View view) {
                ViewTreeObserver.OnGlobalLayoutListener old = dialogWindows.remove(view);
                if (old != null && view.getViewTreeObserver().isAlive()) view.getViewTreeObserver().removeOnGlobalLayoutListener(old);
                view.removeOnAttachStateChangeListener(this);
            }
        });
        if (family != null) applyTree(root, true);
    }

    public static synchronized List<ClientFontFiles.Entry> list() { return new ArrayList<>(entries); }

    private static ClientFontFiles.Entry find(String id) {
        for (ClientFontFiles.Entry entry : entries) if (entry.id.equals(id)) return entry;
        return null;
    }

    public static synchronized void remember(List<ClientFontFiles.Entry> imported) throws IOException {
        ArrayList<ClientFontFiles.Entry> result = new ArrayList<>(entries);
        for (ClientFontFiles.Entry entry : imported) {
            boolean found = false;
            for (ClientFontFiles.Entry current : result) if (current.id.equals(entry.id)) { found = true; break; }
            if (!found) result.add(entry);
        }
        try {
            JSONArray data = new JSONArray();
            for (ClientFontFiles.Entry entry : result) data.put(new JSONObject().put("id", entry.id).put("name", entry.name));
            if (!preferences().edit().putString("fonts", data.toString()).commit()) throw new IOException("Cannot save font library");
            entries.clear(); entries.addAll(result);
        } catch (org.json.JSONException error) { throw new IOException(error); }
    }

    private static Typeface load(ClientFontFiles.Entry entry) throws IOException {
        try {
            Typeface typeface = Typeface.createFromFile(entry.file(directory()));
            if (typeface == null || typeface == Typeface.DEFAULT) throw new IOException("Cannot load font");
            return typeface;
        } catch (RuntimeException error) { throw new IOException("Cannot load font", error); }
    }

    /** Call on the UI thread. Default retains the imported library. */
    public static void select(String id) throws IOException {
        Typeface next = null;
        synchronized (ClientFonts.class) {
            if (!id.isEmpty()) {
                ClientFontFiles.Entry entry = find(id);
                if (entry == null) throw new IOException("Unknown font");
                next = load(entry);
            }
            if (!preferences().edit().putString("selected", id).commit()) throw new IOException("Cannot save selected font");
            family = next;
            selected = id;
            variants.clear();
            generation++;
        }
        AndroidUtilities.clearTypefaceCache();
        TypefaceHelper.clearFontSupportCache();
        refreshThemePaints();
        synchronized (ClientFonts.class) {
            for (Paint paint : new ArrayList<>(paints.keySet())) if (paint != null) applyPaint(paint);
        }
        for (Activity activity : new ArrayList<>(windows.keySet())) {
            if (activity != null) applyTree(activity.getWindow().getDecorView(), true);
        }
        for (View root : new ArrayList<>(dialogWindows.keySet())) if (root != null) applyTree(root, true);
    }

    public static synchronized Typeface get(int weight, boolean italic) {
        if (family == null) return null;
        int key = weight * 2 + (italic ? 1 : 0);
        Typeface result = variants.get(key);
        if (result == null) {
            result = Build.VERSION.SDK_INT >= 28 ? Typeface.create(family, weight, italic)
                    : Typeface.create(family, (weight >= 500 ? Typeface.BOLD : 0) | (italic ? Typeface.ITALIC : 0));
            variants.put(key, result);
            customStyles.put(result, new Style(weight, italic));
        }
        return result;
    }

    private static boolean monospace(Typeface typeface) {
        return typeface != null && (typeface == Typeface.MONOSPACE || typeface.equals(Typeface.MONOSPACE)
                || typeface.equals(Typeface.create("monospace", typeface.getStyle())));
    }

    private static Style style(Typeface typeface) {
        Style custom = customStyles.get(typeface);
        if (custom != null) return custom;
        return new Style(typeface == null ? 400 : Build.VERSION.SDK_INT >= 28 ? typeface.getWeight() : typeface.isBold() ? 700 : 400,
                typeface != null && typeface.isItalic());
    }

    public static synchronized Typeface resolve(Typeface original) {
        if (family == null || monospace(original)) return original;
        Style style = style(original);
        return get(style.weight, style.italic);
    }

    private static Typeface defaultOf(Typeface typeface) {
        Style custom = customStyles.get(typeface);
        if (custom == null) return typeface;
        return Build.VERSION.SDK_INT >= 28 ? Typeface.create(null, custom.weight, custom.italic)
                : Typeface.defaultFromStyle((custom.weight >= 500 ? Typeface.BOLD : 0) | (custom.italic ? Typeface.ITALIC : 0));
    }

    public static TextPaint newTextPaint(int flags) {
        TextPaint paint = new TextPaint(flags);
        if (family != null) applyPaint(paint);
        return paint;
    }

    private static synchronized boolean applyPaint(Paint paint) {
        Typeface current = paint.getTypeface();
        if (monospace(current)) return false;
        FontState state = paints.get(paint);
        if (state == null) { state = new FontState(defaultOf(current)); paints.put(paint, state); }
        else if (current != state.applied) state.original = defaultOf(current);
        Typeface target = resolve(state.original);
        state.applied = target;
        if (current == target) return false;
        paint.setTypeface(target);
        return true;
    }

    private static void refreshThemePaints() {
        try {
            for (Field field : Theme.class.getFields()) {
                if (!Modifier.isStatic(field.getModifiers()) || field.getName().toLowerCase(java.util.Locale.ROOT).matches(".*(emoji|code).*")) continue;
                if (TextPaint.class.isAssignableFrom(field.getType())) {
                    Paint paint = (Paint) field.get(null);
                    if (paint != null) applyPaint(paint);
                } else if (field.getType() == TextPaint[].class) {
                    TextPaint[] array = (TextPaint[]) field.get(null);
                    if (array != null) for (Paint paint : array) if (paint != null) applyPaint(paint);
                }
            }
        } catch (Exception error) { FileLog.e(error); }
    }

    private static void applyTree(View view, boolean force) {
        if (view instanceof PreserveTypeface) return;
        if (view instanceof TextView) {
            TextView label = (TextView) view;
            synchronized (ClientFonts.class) {
                Typeface current = label.getTypeface();
                if (!monospace(current)) {
                    FontState state = labels.get(label);
                    if (state == null) { state = new FontState(defaultOf(current)); labels.put(label, state); }
                    else if (current != state.applied) state.original = defaultOf(current);
                    Typeface target = resolve(state.original);
                    state.applied = target;
                    if (current != target) label.setTypeface(target);
                }
            }
        }
        Integer previous = visited.put(view, generation);
        if (force || previous == null || previous != generation) {
            // Reflection is restricted to our own view classes, never Android framework fields.
            for (Class<?> type = view.getClass(); type != null && (type.getName().startsWith("org.telegram.") || type.getName().startsWith("zxc.iconic.")); type = type.getSuperclass()) {
                List<Field> fields = paintFields.computeIfAbsent(type, key -> {
                    ArrayList<Field> result = new ArrayList<>();
                    for (Field field : key.getDeclaredFields()) {
                        if (!TextPaint.class.isAssignableFrom(field.getType()) || Modifier.isStatic(field.getModifiers())) continue;
                        try { field.setAccessible(true); result.add(field); } catch (RuntimeException ignored) {}
                    }
                    return result;
                });
                for (Field field : fields) {
                    try {
                        Paint paint = (Paint) field.get(view);
                        if (paint != null && (!(view instanceof TextView) || paint != ((TextView) view).getPaint())) applyPaint(paint);
                    } catch (Exception ignored) {}
                }
            }
            view.requestLayout();
            view.invalidate();
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) applyTree(group.getChildAt(i), force);
        }
    }
}
