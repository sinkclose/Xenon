package org.telegram.ui.Components;

import android.view.MotionEvent;
import android.view.View;
import android.view.Window;

import androidx.appcompat.view.WindowCallbackWrapper;


/** Forward only the already-started source gesture; preserve the source's own touch listener. */
final class IosPopupGestureBridge {
    interface Handler { boolean touch(MotionEvent event); }
    private final Window window;
    private final Callback callback;

    IosPopupGestureBridge(View source, Handler handler) {
        source.cancelLongPress();
        window = IosPopupOpener.findWindow(source);
        callback = window == null || window.getCallback() == null ? null : new Callback(window.getCallback(), source, handler);
        if (callback != null) window.setCallback(callback);
    }

    void release() {
        if (callback == null) return;
        callback.active = false;
        callback.source.setPressed(false);
        callback.source.cancelLongPress();
        if (window.getCallback() == callback) {
            Window.Callback previous = callback.previous;
            while (previous instanceof Callback && !((Callback) previous).active) previous = ((Callback) previous).previous;
            window.setCallback(previous);
        }
    }

    private static final class Callback extends WindowCallbackWrapper {
        final Window.Callback previous;
        final View source;
        final Handler handler;
        boolean active = true, continuing = true;

        Callback(Window.Callback previous, View source, Handler handler) {
            super(previous);
            this.previous = previous;
            this.source = source;
            this.handler = handler;
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            int action = event.getActionMasked();
            // A fresh DOWN belongs to normal window dispatch. Only MOVE/UP from
            // the hold that opened the popup may bypass the source view.
            if (action == MotionEvent.ACTION_DOWN) continuing = false;
            if (active && continuing) {
                boolean handled = handler.touch(event);
                if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    continuing = false;
                    source.setPressed(false);
                    source.cancelLongPress();
                }
                if (handled) return true;
            }
            return super.dispatchTouchEvent(event);
        }
    }
}
