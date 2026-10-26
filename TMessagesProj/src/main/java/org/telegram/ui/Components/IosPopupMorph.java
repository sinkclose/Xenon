package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.ActionBarPopupWindow;
import org.telegram.ui.ActionBar.Theme;

import java.util.IdentityHashMap;
import java.util.Map;

/** The popup's actual window content: one persistent glass surface and live, background-free rows. */
public final class IosPopupMorph extends FrameLayout {
    public final View content;
    private final View menuSurface;
    private final View host;
    private final Runnable dismiss;
    private final ViewGroup.LayoutParams previousLayoutParams;
    private final IdentityHashMap<View, Drawable> backgrounds = new IdentityHashMap<>();
    private final RectF source = new RectF(), target = new RectF(), lens = new RectF();
    private final float[] geometry = new float[9], radii = new float[8];
    private final Path clip = new Path();
    private final Paint fallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int tint;
    private final int[] windowLocation = new int[2];
    private final int[] surfaceLocation = new int[2];
    private float sourceRadius, progress;
    private int menuWidth, menuHeight, targetScreenX, targetScreenY;
    private boolean released, glassFailed, backgroundDirty = true;
    private boolean translatingTouch;
    private Glass glass;
    private final IosPopupInteraction interaction;
    private final IosPopupGestureBridge gestureBridge;
    private final float[] closingStart = new float[9];
    private boolean closing;
    private float closingFrom, closingOpacity = 1, closingDragScale = 1, closingDragScaleX = 1, dragScale = 1, dragScaleX = 1;
    private final ViewTreeObserver.OnPreDrawListener hostDrawListener = () -> {
        backgroundDirty = true;
        postInvalidateOnAnimation();
        return true;
    };

    public static boolean canAnimate(View anchor) {
        if (anchor == null || !anchor.isAttachedToWindow() || anchor.getWidth() <= 0 || anchor.getHeight() <= 0) return false;
        View root = anchor.getRootView();
        return anchor.getWidth() < root.getWidth() * .9f && anchor.getHeight() < root.getHeight() * .65f;
    }

    public IosPopupMorph(View content, View anchor, int x, int y, Runnable dismiss, RectF sourceBounds, View menuSurface) {
        super(content.getContext());
        this.content = content;
        this.menuSurface = menuSurface != null ? menuSurface : content;
        this.host = anchor.getRootView();
        interaction = new IosPopupInteraction(this.menuSurface, this::postInvalidateOnAnimation);
        this.dismiss = dismiss;
        if (sourceBounds == null && canAnimate(anchor)) IosPopupOpener.install(anchor);
        previousLayoutParams = content.getLayoutParams();
        menuWidth = content.getMeasuredWidth();
        menuHeight = content.getMeasuredHeight();
        targetScreenX = x;
        targetScreenY = y;
        int[] point = new int[2];
        anchor.getLocationOnScreen(point);
        source.set(point[0], point[1], point[0] + anchor.getWidth(), point[1] + anchor.getHeight());
        if (sourceBounds != null) source.set(messageSourceCircle(sourceBounds));
        sourceRadius = Math.min(source.width(), source.height()) / 2;
        if (anchor.getBackground() != null) {
            Outline outline = new Outline();
            anchor.getBackground().getOutline(outline);
            if (!outline.isEmpty() && outline.getRadius() > 0) sourceRadius = outline.getRadius();
        }
        if (sourceBounds != null) sourceRadius = source.width() / 2;
        tint = Theme.getColor(Theme.key_actionBarDefaultSubmenuBackground);
        stripMenuSurfaces(content, content, backgrounds);
        if (this.menuSurface != content && this.menuSurface instanceof ActionBarPopupWindow.ActionBarPopupWindowLayout) {
            ((ActionBarPopupWindow.ActionBarPopupWindowLayout) this.menuSurface).setIosExternalSurface(true);
        }
        content.setAlpha(1);
        content.setTranslationY(0);
        content.setScaleX(1);
        content.setScaleY(1);
        addView(content, new FrameLayout.LayoutParams(menuWidth, menuHeight));
        setClipChildren(false);
        setClipToPadding(false);
        setWillNotDraw(false);
        host.getViewTreeObserver().addOnPreDrawListener(hostDrawListener);
        gestureBridge = new IosPopupGestureBridge(anchor, this::forwardTouch);
    }

    private static void stripMenuSurfaces(View view, View root, IdentityHashMap<View, Drawable> backgrounds) {
        Drawable original = view.getBackground();
        if (view.isClickable() && (original instanceof android.graphics.drawable.RippleDrawable
                || original instanceof android.graphics.drawable.StateListDrawable)) {
            backgrounds.put(view, original);
            view.setBackground(null);
        }
        if (view instanceof org.telegram.ui.ActionBar.ActionBarMenuSubItem) {
            ((org.telegram.ui.ActionBar.ActionBarMenuSubItem) view).setIosPopupStyle(true);
        } else if (view instanceof android.widget.TextView && view.isClickable()) {
            if (!backgrounds.containsKey(view)) backgrounds.put(view, view.getBackground());
            view.setBackground(null);
        }
        if (view == root || view instanceof ActionBarPopupWindow.ActionBarPopupWindowLayout
                || view instanceof ActionBarPopupWindow.GapView || view instanceof PopupSwipeBackLayout) {
            if (!backgrounds.containsKey(view)) backgrounds.put(view, view.getBackground());
            view.setBackground(null);
        }
        if (view instanceof ActionBarPopupWindow.ActionBarPopupWindowLayout) {
            ((ActionBarPopupWindow.ActionBarPopupWindowLayout) view).setIosSurfaceContent(true);
        }
        if (view instanceof ActionBarPopupWindow.GapView) {
            ((ActionBarPopupWindow.GapView) view).setIosSurfaceContent(true);
        }
        if (view instanceof PopupSwipeBackLayout) ((PopupSwipeBackLayout) view).setIosSurfaceContent(true);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) stripMenuSurfaces(group.getChildAt(i), root, backgrounds);
        }
    }

    public void updateTarget(int screenX, int screenY, int width, int height) {
        if (width > 0) menuWidth = width;
        if (height > 0) menuHeight = height;
        android.graphics.Rect visible = new android.graphics.Rect();
        host.getWindowVisibleDisplayFrame(visible);
        targetScreenX = IosPopupMorphGeometry.clampMenuPosition(screenX, menuWidth, visible.left, visible.right, AndroidUtilities.dp(6));
        targetScreenY = IosPopupMorphGeometry.clampMenuPosition(screenY, menuHeight, visible.top, visible.bottom, AndroidUtilities.dp(6));
        requestLayout();
        invalidate();
    }

    @Override protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.getSize(heightMeasureSpec));
        content.measure(MeasureSpec.makeMeasureSpec(menuWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(menuHeight, MeasureSpec.EXACTLY));
    }

    @Override protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        getLocationOnScreen(windowLocation);
        int x = targetScreenX - windowLocation[0], y = targetScreenY - windowLocation[1];
        content.layout(x, y, x + menuWidth, y + menuHeight);
        // The old nine-patch shadow padding is not part of the new glass surface.
        target.set(x + content.getPaddingLeft(), y + content.getPaddingTop(),
                x + menuWidth - content.getPaddingRight(), y + menuHeight - content.getPaddingBottom());
    }

    public AnimatorSet opening() {
        return animateProgress(1, IosPopupMorphGeometry.OPEN_DURATION, null);
    }

    public AnimatorSet closing(Runnable onEnd) {
        closingOpacity = IosPopupMorphGeometry.surfaceOpacity(progress, false);
        closing = true;
        closingFrom = Math.max(.0001f, progress);
        closingDragScale = dragScale;
        closingDragScaleX = dragScaleX;
        System.arraycopy(geometry, 0, closingStart, 0, geometry.length);
        return animateProgress(0, IosPopupMorphGeometry.CLOSE_DURATION, onEnd);
    }

    private AnimatorSet animateProgress(float end, long duration, Runnable onEnd) {
        ValueAnimator frames = ValueAnimator.ofFloat(progress, end);
        frames.setDuration(Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled() ? 0 : duration);
        frames.setInterpolator(t -> t);
        frames.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        AnimatorSet animation = new AnimatorSet();
        animation.playTogether(frames);
        animation.addListener(new AnimatorListenerAdapter() {
            private boolean cancelled;
            @Override public void onAnimationCancel(Animator a) { cancelled = true; }
            @Override public void onAnimationEnd(Animator a) {
                if (!cancelled) {
                    progress = end;
                    invalidate();
                    if (onEnd != null) onEnd.run();
                }
            }
        });
        return animation;
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (closing) return true;
        float mappedX = target.centerX() + (event.getX() - geometry[0]) / dragScaleX;
        float mappedY = target.centerY() + (event.getY() - geometry[1]) / dragScale;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            translatingTouch = target.contains(mappedX, mappedY);
            boolean onOtherContent = menuSurface != content && event.getX() >= content.getX()
                    && event.getX() <= content.getX() + content.getWidth() && event.getY() >= content.getY()
                    && event.getY() <= content.getY() + content.getHeight();
            if (!translatingTouch && !onOtherContent) {
                dismiss.run();
                return true;
            }
        }
        MotionEvent translated = translatingTouch ? MotionEvent.obtain(event) : null;
        if (translated != null) translated.offsetLocation(mappedX - event.getX(), mappedY - event.getY());
        menuSurface.getLocationOnScreen(surfaceLocation);
        float x = mappedX + windowLocation[0] - surfaceLocation[0];
        float y = mappedY + windowLocation[1] - surfaceLocation[1];
        boolean handled = interaction.touch(event, x, y, false)
                || super.dispatchTouchEvent(translated != null ? translated : event);
        if (translated != null) translated.recycle();
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) translatingTouch = false;
        return handled;
    }

    public boolean forwardTouch(MotionEvent event) {
        if (closing || released) return true;
        getLocationOnScreen(windowLocation);
        menuSurface.getLocationOnScreen(surfaceLocation);
        float x = target.centerX() + (event.getRawX() - windowLocation[0] - geometry[0]) / dragScaleX
                + windowLocation[0] - surfaceLocation[0];
        float y = target.centerY() + (event.getRawY() - windowLocation[1] - geometry[1]) / dragScale
                + windowLocation[1] - surfaceLocation[1];
        return interaction.touch(event, x, y, true);
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        if (released || target.isEmpty()) return;
        if (menuSurface != content) super.dispatchDraw(canvas);
        getLocationOnScreen(windowLocation);
        menuSurface.getLocationOnScreen(surfaceLocation);
        float surfaceX = surfaceLocation[0] - windowLocation[0], surfaceY = surfaceLocation[1] - windowLocation[1];
        float width = menuSurface.getWidth(), height = menuSurface.getHeight();
        if (menuSurface instanceof ActionBarPopupWindow.ActionBarPopupWindowLayout) {
            ActionBarPopupWindow.ActionBarPopupWindowLayout layout = (ActionBarPopupWindow.ActionBarPopupWindowLayout) menuSurface;
            width *= layout.getBackScaleX();
            height *= layout.getBackScaleY();
        }
        target.set(surfaceX + menuSurface.getPaddingLeft(), surfaceY + menuSurface.getPaddingTop(),
                surfaceX + width - menuSurface.getPaddingRight(), surfaceY + height - menuSurface.getPaddingBottom());
        IosPopupMorphGeometry.evaluate(progress, source.centerX() - windowLocation[0], source.centerY() - windowLocation[1],
                source.width(), source.height(), target.centerX(), target.centerY(), target.width(), target.height(),
                sourceRadius, AndroidUtilities.dp(26), geometry);
        if (closing) {
            IosPopupMorphGeometry.evaluateClosing(1 - progress / closingFrom, closingStart,
                    source.centerX() - windowLocation[0], source.centerY() - windowLocation[1],
                    source.width(), source.height(), sourceRadius, geometry);
        }
        float pull = closing ? 0 : interaction.pull();
        float baseWidth = geometry[2];
        dragScale = IosPopupMorphGeometry.applyDrag(pull, interaction.pivotX(), interaction.pivotY(), geometry);
        dragScaleX = geometry[2] / baseWidth;
        if (closing) {
            dragScale = 1 + (closingDragScale - 1) * progress / closingFrom;
            dragScaleX = 1 + (closingDragScaleX - 1) * progress / closingFrom;
        }
        float w = geometry[2], h = geometry[3];
        lens.set(-w / 2, -h / 2, w / 2, h / 2);
        for (int i = 0; i < 4; i++) radii[2*i] = radii[2*i + 1] = geometry[5 + i];
        clip.rewind();
        clip.addRoundRect(lens, radii, Path.Direction.CW);
        float phase = closing ? 1 - progress / closingFrom : progress;
        int alpha = Math.round(255 * IosPopupMorphGeometry.surfaceOpacity(phase, closing) * (closing ? closingOpacity : 1));
        int save = alpha < 255 ? canvas.saveLayerAlpha(geometry[0] - w / 2 - 1, geometry[1] - h / 2 - 1,
                geometry[0] + w / 2 + 1, geometry[1] + h / 2 + 1, alpha) : canvas.save();
        canvas.translate(geometry[0], geometry[1]);
        canvas.clipPath(clip);
        boolean drawn = false;
        if (Build.VERSION.SDK_INT >= 33 && canvas.isHardwareAccelerated() && !glassFailed) {
            int glassSave = canvas.save();
            try {
                if (glass == null) glass = new Glass(this);
                if (backgroundDirty) {
                    glass.captureBackdrop(host, windowLocation);
                    backgroundDirty = false;
                }
                glass.draw(canvas, menuSurface, surfaceX, surfaceY, geometry, target, tint, progress, dragScaleX, dragScale);
                drawn = true;
            } catch (RuntimeException e) {
                FileLog.e(e);
                if (glass != null) glass.release();
                glass = null;
                glassFailed = true;
            } finally { canvas.restoreToCount(glassSave); }
        }
        if (!drawn) {
            fallbackPaint.setColor(tint);
            fallbackPaint.setAlpha(220);
            canvas.drawRect(lens, fallbackPaint);
            int rows = canvas.save();
            canvas.translate(surfaceX - target.centerX(), surfaceY - target.centerY());
            canvas.scale(dragScaleX, dragScale, target.centerX() - surfaceX, target.centerY() - surfaceY);
            drawMenuContent(menuSurface, canvas);
            canvas.restoreToCount(rows);
        }
        interaction.drawFeedback(canvas, target.centerX() - surfaceX, target.centerY() - surfaceY, dragScaleX, dragScale);
        canvas.restoreToCount(save);
    }

    public void release() {
        if (released) return;
        released = true;
        interaction.release();
        gestureBridge.release();
        if (host.getViewTreeObserver().isAlive()) host.getViewTreeObserver().removeOnPreDrawListener(hostDrawListener);
        if (glass != null) glass.release();
        removeView(content);
        content.setLayoutParams(previousLayoutParams != null ? previousLayoutParams
                : new FrameLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        if (menuSurface instanceof ActionBarPopupWindow.ActionBarPopupWindowLayout) {
            ((ActionBarPopupWindow.ActionBarPopupWindowLayout) menuSurface).setIosExternalSurface(false);
        }
        restoreMenuSurfaces(backgrounds);
    }

    private static void restoreMenuSurfaces(IdentityHashMap<View, Drawable> backgrounds) {
        for (Map.Entry<View, Drawable> entry : backgrounds.entrySet()) {
            View view = entry.getKey();
            view.setBackground(entry.getValue());
            if (view instanceof ActionBarPopupWindow.ActionBarPopupWindowLayout) {
                ((ActionBarPopupWindow.ActionBarPopupWindowLayout) view).setIosSurfaceContent(false);
            }
            if (view instanceof ActionBarPopupWindow.GapView) ((ActionBarPopupWindow.GapView) view).setIosSurfaceContent(false);
            if (view instanceof PopupSwipeBackLayout) ((PopupSwipeBackLayout) view).setIosSurfaceContent(false);
            restoreRowStyles(view);
        }
        backgrounds.clear();
    }

    private static void restoreRowStyles(View view) {
        if (view instanceof org.telegram.ui.ActionBar.ActionBarMenuSubItem)
            ((org.telegram.ui.ActionBar.ActionBarMenuSubItem) view).setIosPopupStyle(false);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) restoreRowStyles(group.getChildAt(i));
        }
    }

    private static void drawMenuContent(View content, Canvas canvas) {
        ActionBarPopupWindow.ActionBarPopupWindowLayout layout = content instanceof ActionBarPopupWindow.ActionBarPopupWindowLayout
                ? (ActionBarPopupWindow.ActionBarPopupWindowLayout) content : null;
        if (layout != null) layout.setIosSurfaceRecording(true);
        try { content.draw(canvas); }
        finally { if (layout != null) layout.setIosSurfaceRecording(false); }
    }

    private static RectF messageSourceCircle(RectF bounds) {
        float r = AndroidUtilities.dp(20);
        return new RectF(bounds.centerX() - r, bounds.bottom - r, bounds.centerX() + r, bounds.bottom + r);
    }

    public static void closeEmbedded(View view) {
        if (view instanceof ActionBarPopupWindow.ActionBarPopupWindowLayout)
            ((ActionBarPopupWindow.ActionBarPopupWindowLayout) view).closeIosMessageSurface();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) closeEmbedded(group.getChildAt(i));
        }
    }

    public static boolean forwardEmbedded(View view, MotionEvent event) {
        if (view instanceof ActionBarPopupWindow.ActionBarPopupWindowLayout
                && ((ActionBarPopupWindow.ActionBarPopupWindowLayout) view).forwardIosMessageTouch(event)) return true;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                if (forwardEmbedded(group.getChildAt(i), event)) return true;
        }
        return false;
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        release();
    }

    /** A menu inside the existing message-preview viewport; message/reaction views keep their own layout. */
    public static final class Embedded {
        private final ActionBarPopupWindow.ActionBarPopupWindowLayout content;
        private final View host;
        private final RectF source, target = new RectF(), lens = new RectF();
        private final float[] geometry = new float[9], radii = new float[8];
        private final int[] location = new int[2];
        private final Path clip = new Path();
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final IdentityHashMap<View, Drawable> backgrounds = new IdentityHashMap<>();
        private final int tint;
        private ValueAnimator animator;
        private float progress;
        private boolean started, released, failed;
        private Glass glass;
        private final IosPopupInteraction interaction;
        private final IosPopupGestureBridge gestureBridge;
        private float dragScale = 1, dragScaleX = 1;
        private boolean closing;
        private float closingFrom;
        private float closingOpacity = 1;
        private float closingDragScale = 1, closingDragScaleX = 1;
        private final float[] closingStart = new float[9];

        public Embedded(ActionBarPopupWindow.ActionBarPopupWindowLayout content, View sourceView, RectF screenBounds, int tint) {
            this.content = content;
            interaction = new IosPopupInteraction(content, content::postInvalidateOnAnimation);
            host = sourceView.getRootView();
            source = messageSourceCircle(screenBounds);
            this.tint = tint;
            stripMenuSurfaces(content, content, backgrounds);
            gestureBridge = new IosPopupGestureBridge(sourceView, this::forwardTouch);
        }

        public void draw(Canvas canvas) {
            if (released || content.getWidth() == 0 || content.getHeight() == 0) return;
            if (!started) {
                started = true;
                animator = ValueAnimator.ofFloat(0, 1);
                animator.setDuration(Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled() ? 0 : IosPopupMorphGeometry.OPEN_DURATION);
                animator.setInterpolator(t -> t);
                animator.addUpdateListener(a -> { progress = (float) a.getAnimatedValue(); content.invalidate(); });
                animator.start();
            }
            content.getLocationOnScreen(location);
            target.set(content.getPaddingLeft(), content.getPaddingTop(),
                    content.getWidth() * content.getBackScaleX() - content.getPaddingRight(),
                    content.getHeight() * content.getBackScaleY() - content.getPaddingBottom());
            IosPopupMorphGeometry.evaluate(progress, source.centerX() - location[0], source.centerY() - location[1],
                    source.width(), source.height(), target.centerX(), target.centerY(), target.width(), target.height(),
                    source.width() / 2, AndroidUtilities.dp(26), geometry);
            if (closing) IosPopupMorphGeometry.evaluateClosing(1 - progress / closingFrom, closingStart,
                    source.centerX() - location[0], source.centerY() - location[1], source.width(), source.height(),
                    source.width() / 2, geometry);
            float pull = closing ? 0 : interaction.pull();
            float baseWidth = geometry[2];
            dragScale = IosPopupMorphGeometry.applyDrag(pull, interaction.pivotX(), interaction.pivotY(), geometry);
            dragScaleX = geometry[2] / baseWidth;
            if (closing) {
                dragScale = 1 + (closingDragScale - 1) * progress / closingFrom;
                dragScaleX = 1 + (closingDragScaleX - 1) * progress / closingFrom;
            }
            float w = geometry[2], h = geometry[3];
            lens.set(-w / 2, -h / 2, w / 2, h / 2);
            for (int i = 0; i < 4; i++) radii[2*i] = radii[2*i + 1] = geometry[5 + i];
            clip.rewind();
            clip.addRoundRect(lens, radii, Path.Direction.CW);
            float phase = closing ? 1 - progress / closingFrom : progress;
            int alpha = Math.round(255 * IosPopupMorphGeometry.surfaceOpacity(phase, closing) * (closing ? closingOpacity : 1));
            int save = alpha < 255 ? canvas.saveLayerAlpha(geometry[0] - w / 2 - 1, geometry[1] - h / 2 - 1,
                    geometry[0] + w / 2 + 1, geometry[1] + h / 2 + 1, alpha) : canvas.save();
            canvas.translate(geometry[0], geometry[1]);
            canvas.clipPath(clip);
            boolean drawn = false;
            if (Build.VERSION.SDK_INT >= 33 && canvas.isHardwareAccelerated() && !failed) {
                int glassSave = canvas.save();
                try {
                    if (glass == null) glass = new Glass(content);
                    glass.captureBackdrop(host, location);
                    glass.draw(canvas, content, 0, 0, geometry, target, tint, progress, dragScaleX, dragScale);
                    drawn = true;
                } catch (RuntimeException e) {
                    FileLog.e(e);
                    if (glass != null) glass.release();
                    glass = null;
                    failed = true;
                } finally { canvas.restoreToCount(glassSave); }
            }
            if (!drawn) {
                paint.setColor(tint);
                paint.setAlpha(220);
                canvas.drawRect(lens, paint);
                int rows = canvas.save();
                canvas.translate(-target.centerX(), -target.centerY());
                canvas.scale(dragScaleX, dragScale, target.centerX(), target.centerY());
                drawMenuContent(content, canvas);
                canvas.restoreToCount(rows);
            }
            interaction.drawFeedback(canvas, target.centerX(), target.centerY(), dragScaleX, dragScale);
            canvas.restoreToCount(save);
        }

        public boolean touch(MotionEvent event) {
            if (closing) return true;
            float x = target.centerX() + (event.getX() - geometry[0]) / dragScaleX;
            float y = target.centerY() + (event.getY() - geometry[1]) / dragScale;
            return interaction.touch(event, x, y, false);
        }

        public boolean forwardTouch(MotionEvent event) {
            if (closing || released) return true;
            content.getLocationOnScreen(location);
            return interaction.touch(event, target.centerX() + (event.getRawX() - location[0] - geometry[0]) / dragScaleX,
                    target.centerY() + (event.getRawY() - location[1] - geometry[1]) / dragScale, true);
        }

        public void close() {
            if (closing || released) return;
            if (animator != null) animator.cancel();
            started = true;
            closingOpacity = IosPopupMorphGeometry.surfaceOpacity(progress, false);
            closing = true;
            closingFrom = Math.max(.0001f, progress);
            closingDragScale = dragScale;
            closingDragScaleX = dragScaleX;
            System.arraycopy(geometry, 0, closingStart, 0, geometry.length);
            animator = ValueAnimator.ofFloat(progress, 0);
            animator.setDuration(Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()
                    ? 0 : IosPopupMorphGeometry.CLOSE_DURATION);
            animator.setInterpolator(t -> t);
            animator.addUpdateListener(a -> { progress = (float) a.getAnimatedValue(); content.invalidate(); });
            animator.start();
        }

        public void release() {
            if (released) return;
            released = true;
            interaction.release();
            gestureBridge.release();
            if (animator != null) animator.cancel();
            if (glass != null) glass.release();
            restoreMenuSurfaces(backgrounds);
        }
    }

    @RequiresApi(33)
    private static final class Glass {
        private final RenderNode backdrop = new RenderNode("PopupBackdrop");
        private final RenderNode frosted = new RenderNode("PopupFrostedBackdrop");
        private final RenderNode surface = new RenderNode("PopupGlassAndRows");
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final int[] location = new int[2];

        Glass(View owner) {
            frosted.setRenderEffect(RenderEffect.createBlurEffect(AndroidUtilities.dp(10), AndroidUtilities.dp(10), Shader.TileMode.CLAMP));
        }

        void captureBackdrop(View host, int[] windowLocation) {
            host.getLocationOnScreen(location);
            backdrop.setPosition(0, 0, host.getWidth(), host.getHeight());
            Canvas recording = backdrop.beginRecording();
            try { host.draw(recording); } finally { backdrop.endRecording(); }
            location[0] -= windowLocation[0];
            location[1] -= windowLocation[1];
        }

        private static void toWindow(Canvas canvas, float[] g, int width, int height) {
            canvas.translate(width / 2f, height / 2f);
            canvas.translate(-g[0], -g[1]);
        }

        void draw(Canvas canvas, View content, float contentX, float contentY, float[] g, RectF target, int tint, float progress, float dragScaleX, float dragScale) {
            int w = Math.max(1, (int) Math.ceil(g[2])), h = Math.max(1, (int) Math.ceil(g[3]));
            frosted.setPosition(0, 0, w, h);
            Canvas recording = frosted.beginRecording();
            try {
                toWindow(recording, g, w, h);
                recording.translate(location[0], location[1]);
                recording.drawRenderNode(backdrop);
            } finally { frosted.endRecording(); }
            surface.setPosition(0, 0, w, h);
            recording = surface.beginRecording();
            try {
                recording.drawRenderNode(frosted);
                paint.setColor(tint);
                paint.setAlpha(170);
                recording.drawRect(0, 0, w, h, paint);
                toWindow(recording, g, w, h);
                recording.translate(contentX + IosPopupMorphGeometry.centeredContentOffset(g[0], target.centerX()),
                        contentY + IosPopupMorphGeometry.centeredContentOffset(g[1], target.centerY()));
                recording.scale(dragScaleX, dragScale, target.centerX() - contentX, target.centerY() - contentY);
                // Only live rows enter this recording. Every legacy popup surface is suppressed.
                drawMenuContent(content, recording);
            } finally { surface.endRecording(); }
            canvas.translate(-w / 2f, -h / 2f);
            canvas.drawRenderNode(surface);
        }

        void release() {
            surface.setRenderEffect(null);
            surface.discardDisplayList();
            frosted.setRenderEffect(null);
            frosted.discardDisplayList();
            backdrop.discardDisplayList();
        }
    }
}
