package org.telegram.ui.Components;

import android.view.View;
import android.view.MotionEvent;
import android.view.ViewConfiguration;
import android.view.Window;
import android.app.Activity;
import androidx.appcompat.view.WindowCallbackWrapper;
import org.telegram.messenger.AndroidUtilities;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;

/** Register popup buttons when their click action is installed, before their first use. */
public final class IosPopupOpener {
    private IosPopupOpener() { }
    private static final WeakHashMap<Window, WeakReference<DragCallback>> callbacks = new WeakHashMap<>();

    public static View.OnClickListener listener(View button, View.OnClickListener click) {
        return listener(button, () -> true, click);
    }

    public static View.OnClickListener listener(View button, java.util.function.BooleanSupplier enabled, View.OnClickListener click) {
        if (button != null) {
            if (button.isAttachedToWindow()) registerDrag(button, enabled);
            else button.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
                @Override public void onViewAttachedToWindow(View v) {
                    v.removeOnAttachStateChangeListener(this);
                    registerDrag(v, enabled);
                }
                @Override public void onViewDetachedFromWindow(View v) { }
            });
        }
        if (button != null && !button.isLongClickable()) {
            button.setOnLongClickListener(v -> {
                if (!v.isEnabled() || !enabled.getAsBoolean()) return false;
                // Calling the actual registered action keeps permissions, disabled
                // branches and dynamically populated menus identical to a tap.
                v.performClick();
                return true;
            });
        }
        return click;
    }

    public static void install(View button) { listener(button, null); }

    private static void registerDrag(View button, BooleanSupplier enabled) {
        Window window = findWindow(button);
        if (window == null || window.getCallback() == null) return;
        registerWindow(window);
        DragCallback callback = callbacks.get(window).get();
        // Late anchor discovery must not replace a previously registered guard.
        if (!callback.buttons.containsKey(button)) callback.buttons.put(button, enabled);
    }

    public static Window findWindow(View source) {
        for (Window window : callbacks.keySet()) {
            if (window.getDecorView() == source.getRootView()) return window;
        }
        Activity activity = AndroidUtilities.findActivity(source.getContext());
        return activity == null ? null : activity.getWindow();
    }

    public static void registerWindow(Window window) {
        if (window == null || window.getCallback() == null) return;
        WeakReference<DragCallback> reference = callbacks.get(window);
        DragCallback callback = reference == null ? null : reference.get();
        if (callback == null) {
            callback = new DragCallback(window.getCallback(), window);
            callbacks.put(window, new WeakReference<>(callback));
            window.setCallback(callback);
        }
    }

    /** Observe the window without replacing any button's existing touch listener. */
    private static final class DragCallback extends WindowCallbackWrapper {
        final WeakHashMap<View, BooleanSupplier> buttons = new WeakHashMap<>();
        final WeakReference<Window> window;
        final int[] location = new int[2];
        View candidate;
        float downX, downY;

        DragCallback(Window.Callback previous, Window window) {
            super(previous);
            this.window = new WeakReference<>(window);
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            if (action == MotionEvent.ACTION_DOWN) {
                candidate = null;
                {
                    downX = event.getRawX();
                    downY = event.getRawY();
                    for (View button : buttons.keySet()) {
                        if (!button.isShown() || !button.isEnabled() || !button.hasOnClickListeners()) continue;
                        button.getLocationOnScreen(location);
                        if (downX >= location[0] && downX < location[0] + button.getWidth()
                                && downY >= location[1] && downY < location[1] + button.getHeight()) {
                            candidate = button;
                            break;
                        }
                    }
                }
            } else if (action == MotionEvent.ACTION_MOVE && candidate != null) {
                View button = candidate;
                float dx = event.getRawX() - downX, dy = event.getRawY() - downY;
                int slop = ViewConfiguration.get(button.getContext()).getScaledTouchSlop();
                button.getLocationOnScreen(location);
                if (Math.abs(dx) > slop && Math.abs(dx) > Math.abs(dy)) candidate = null;
                else if (Math.abs(dy) > slop && (event.getRawY() < location[1]
                        || event.getRawY() >= location[1] + button.getHeight())) {
                    candidate = null;
                    BooleanSupplier enabled = buttons.get(button);
                    if (button.isEnabled()
                            && enabled != null && enabled.getAsBoolean()) {
                        MotionEvent cancel = MotionEvent.obtain(event);
                        cancel.setAction(MotionEvent.ACTION_CANCEL);
                        try { super.dispatchTouchEvent(cancel); } finally { cancel.recycle(); }
                        button.cancelLongPress();
                        if (button.getParent() != null) button.getParent().requestDisallowInterceptTouchEvent(true);
                        button.performClick();
                        Window current = window.get();
                        if (current != null && current.getCallback() != this) {
                            current.getCallback().dispatchTouchEvent(event);
                        }
                        return true;
                    }
                }
            } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL
                    || action == MotionEvent.ACTION_POINTER_DOWN) candidate = null;
            return super.dispatchTouchEvent(event);
        }
    }
}
