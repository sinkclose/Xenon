"""Exercise the production backdrop controller without a device or Android SDK."""
import os
from pathlib import Path
import re
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class BackdropBlurTest(unittest.TestCase):
    def test_native_fallback_overlap_and_lifecycle(self):
        source = (ROOT / 'TMessagesProj/src/main/java/zxc/iconic/xenon/helpers/WindowBackdropBlur.java').read_text(encoding='utf-8')
        source = re.sub(r'^(?:package |import |@RequiresApi).*\n', '', source, flags=re.M)
        source = source.replace('public final class WindowBackdropBlur', 'static final class WindowBackdropBlur')
        harness = r'''
import java.util.*;
import java.util.function.*;
import java.util.concurrent.Executor;
public class BackdropHarness {
    static void check(boolean b, String message) { if (!b) throw new AssertionError(message); }
    static class Animator {}
    static class AnimatorListenerAdapter { public void onAnimationEnd(Animator a) {} }
    static class ValueAnimator extends Animator {
        static ValueAnimator last; float target; Consumer<ValueAnimator> update; AnimatorListenerAdapter listener;
        static ValueAnimator ofFloat(float a, float b) { last = new ValueAnimator(); last.target = b; return last; }
        void setDuration(long d) {} void setInterpolator(Object i) {} void start() {}
        void addUpdateListener(Consumer<ValueAnimator> c) { update = c; }
        void addListener(AnimatorListenerAdapter c) { listener = c; }
        Object getAnimatedValue() { return target; }
        void cancel() { if (listener != null) listener.onAnimationEnd(this); }
        void removeAllUpdateListeners() { update = null; } void removeAllListeners() { listener = null; }
        void finish() { if (update != null) update.accept(this); if (listener != null) listener.onAnimationEnd(this); }
    }
    static class CubicBezierInterpolator { static final Object EASE_OUT_QUINT = new Object(); }
    static class Shader { enum TileMode { CLAMP } }
    static class RenderEffect {
        float radius;
        static RenderEffect createBlurEffect(float x, float y, Shader.TileMode m) { var e = new RenderEffect(); e.radius = x; return e; }
    }
    static class Context {
        WindowManager manager = new WindowManager();
        <T> T getSystemService(Class<T> c) { return c.cast(manager); }
        Executor getMainExecutor() { return Runnable::run; }
    }
    static class View {
        interface OnAttachStateChangeListener { void onViewAttachedToWindow(View v); void onViewDetachedFromWindow(View v); }
        Context context; boolean attached = true; Object params = new WindowManager.LayoutParams();
        List<OnAttachStateChangeListener> listeners = new ArrayList<>(); RenderEffect effect; View root = this;
        View(Context c) { context = c; }
        Context getContext() { return context; } boolean isAttachedToWindow() { return attached; }
        void addOnAttachStateChangeListener(OnAttachStateChangeListener l) { listeners.add(l); }
        void removeOnAttachStateChangeListener(OnAttachStateChangeListener l) { listeners.remove(l); }
        Object getLayoutParams() { return params; } View getRootView() { return root; }
        void setRenderEffect(RenderEffect e) { effect = e; }
        void detach() { attached = false; for (var l : new ArrayList<>(listeners)) l.onViewDetachedFromWindow(this); }
        void attach() { attached = true; for (var l : new ArrayList<>(listeners)) l.onViewAttachedToWindow(this); }
    }
    static class Window {
        View decor; WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        Window(Context c) { decor = new View(c); }
        View getDecorView() { return decor; } WindowManager.LayoutParams getAttributes() { return params; }
        void setAttributes(WindowManager.LayoutParams p) { params = p; }
    }
    static class WindowManager {
        static class LayoutParams {
            static final int FLAG_BLUR_BEHIND = 4; int flags, blur;
            int getBlurBehindRadius() { return blur; } void setBlurBehindRadius(int r) { blur = r; }
        }
        boolean enabled = true; List<Consumer<Boolean>> listeners = new ArrayList<>();
        boolean isCrossWindowBlurEnabled() { return enabled; }
        void updateViewLayout(View v, LayoutParams p) { v.params = p; }
        void addCrossWindowBlurEnabledListener(Executor e, Consumer<Boolean> c) { listeners.add(c); e.execute(() -> c.accept(enabled)); }
        void removeCrossWindowBlurEnabledListener(Consumer<Boolean> c) { listeners.remove(c); }
        void enable(boolean v) { enabled = v; for (var c : new ArrayList<>(listeners)) c.accept(v); }
    }
    public static void main(String[] args) {
        Context c = new Context(); View bg = new View(c); Window sheet = new Window(c); sheet.params.flags = 128;
        WindowBackdropBlur blur = new WindowBackdropBlur(sheet, bg); blur.setRadius(40);
        check(sheet.params.blur == 40 && sheet.params.flags == 132, "native blur preserves flags");
        check(bg.effect == null, "no duplicate fallback"); c.manager.enable(false);
        check(sheet.params.blur == 0 && sheet.params.flags == 128 && bg.effect.radius == 40, "live native to fallback");
        View popup = new View(c); WindowBackdropBlur other = new WindowBackdropBlur(popup, bg);
        other.setRadius(60); blur.setRadius(20); check(bg.effect.radius == 60, "overlapping fallback uses maximum");
        other.release(); other.release(); other.setRadius(90); check(bg.effect.radius == 20, "popup release preserves sheet");
        c.manager.enable(true); check(bg.effect == null && sheet.params.blur == 20, "fallback to native");
        blur.setRadius(500); check(sheet.params.blur == 150, "radius bounded");
        blur.animateTo(0, 200, true); ValueAnimator stale = ValueAnimator.last;
        blur.animateTo(35, 200, false); stale.finish(); ValueAnimator.last.finish();
        check(sheet.params.blur == 35, "cancelled dismissal cannot release new animation");
        sheet.decor.detach(); check(c.manager.listeners.isEmpty() && sheet.params.blur == 0, "detach cleanup");
        c.manager.enable(false); check(bg.effect == null, "late system callback has no effect");
        Window original = new Window(c); original.params.flags = 4; original.params.blur = 7;
        c.manager.enable(true); WindowBackdropBlur saved = new WindowBackdropBlur(original, bg);
        saved.setRadius(40); saved.release(); check(original.params.flags == 4 && original.params.blur == 7, "restore original blur");
        View pending = new View(c); pending.attached = false;
        WindowBackdropBlur waiting = new WindowBackdropBlur(pending, bg); waiting.setRadius(25);
        check(((WindowManager.LayoutParams)pending.params).blur == 0, "wait for popup attach");
        pending.attach(); check(((WindowManager.LayoutParams)pending.params).blur == 25, "apply on attach");
        waiting.animateTo(0, 100, true); ValueAnimator.last.finish();
        check(c.manager.listeners.isEmpty() && WindowBackdropBlur.FALLBACKS.isEmpty(), "animated release cleanup");
    }
'''
        java_home = os.environ.get('JAVA_HOME')
        java = str(Path(java_home) / 'bin/java.exe') if java_home else 'java'
        javac = str(Path(java_home) / 'bin/javac.exe') if java_home else 'javac'
        with tempfile.TemporaryDirectory() as folder:
            path = Path(folder) / 'BackdropHarness.java'
            path.write_text(harness + source + '\n}', encoding='utf-8')
            result = subprocess.run([javac, str(path)], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
            result = subprocess.run([java, '-cp', folder, 'BackdropHarness'], capture_output=True, text=True)
            self.assertEqual(result.returncode, 0, result.stderr)
