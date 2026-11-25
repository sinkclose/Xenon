package org.telegram.ui.Components;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.view.Choreographer;
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
    public interface Backdrop {
        void drawPopupBackdrop(Canvas canvas);
        /** -1 for a live scene; a stable version lets frozen preview captures be reused. */
        default long popupBackdropVersion() { return -1; }
    }

    public final View content;
    private final View menuSurface;
    private final View host;
    private final java.util.List<View> portalButtons;
    private final RectF throughSource = new RectF();
    private final boolean morphFromAnchor;
    private final NavigationHeaderButtons.MenuSource headerSource;
    private final Runnable dismiss;
    private final ViewGroup.LayoutParams previousLayoutParams;
    private final IdentityHashMap<View, Drawable> backgrounds = new IdentityHashMap<>();
    private final RectF source = new RectF(), target = new RectF();
    private final float[] geometry = new float[9];
    private final GlasmioMenuMotion motion = new GlasmioMenuMotion();
    private final Paint fallbackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final int tint;
    private final int[] windowLocation = new int[2];
    private final int[] surfaceLocation = new int[2];
    private long lastFrameNanos, closingStartedNanos;
    private Runnable closeCompletion;
    private final Choreographer.FrameCallback frameCallback = this::advanceFrame;
    private int menuWidth, menuHeight, targetScreenX, targetScreenY;
    private boolean released, glassFailed;
    private boolean translatingTouch;
    private boolean backdropDirty = true;
    private final ViewTreeObserver.OnPreDrawListener backdropListener = () -> {
        backdropDirty = true;
        invalidate();
        return true;
    };
    private Glass glass;
    private final IosPopupInteraction interaction;
    private final IosPopupGestureBridge gestureBridge;
    private boolean closing;
    private float dragScale = 1, dragScaleX = 1;
    public static boolean isCapturingBackdrop() {
        return Build.VERSION.SDK_INT >= 33 && GlasmioPopupRenderer.isCapturing();
    }

    public static boolean canAnimate(View anchor) {
        if (anchor == null || !anchor.isAttachedToWindow() || anchor.getWidth() <= 0 || anchor.getHeight() <= 0) return false;
        View root = anchor.getRootView();
        return anchor != root && anchor.getWidth() <= root.getWidth()
                && anchor.getHeight() < root.getHeight() * .65f
                && (anchor.getWidth() < root.getWidth() * .9f || anchor.getHeight() <= AndroidUtilities.dp(96));
    }

    public IosPopupMorph(View content, View anchor, int x, int y, Runnable dismiss, RectF sourceBounds, View menuSurface) {
        super(content.getContext());
        this.content = content;
        this.menuSurface = menuSurface != null ? menuSurface : content;
        this.host = anchor.getRootView();
        View gestureAnchor = anchor;
        if (sourceBounds == null) anchor = selectionAnchor(anchor);
        if (sourceBounds == null) {
            View button = anchor;
            while (button.getParent() instanceof View && !(button instanceof org.telegram.ui.ActionBar.ActionBarMenuItem)) {
                button = (View) button.getParent();
            }
            if (button instanceof org.telegram.ui.ActionBar.ActionBarMenuItem) anchor = button;
        }
        headerSource = sourceBounds == null && Build.VERSION.SDK_INT >= 33 ? NavigationHeaderButtons.acquireMenuSource(anchor) : null;
        morphFromAnchor = sourceBounds == null && (headerSource != null || canAnimate(anchor));
        portalButtons = headerSource != null || sourceBounds != null || !morphFromAnchor ? java.util.Collections.emptyList()
                : anchor.getParent() instanceof org.telegram.ui.ActionBar.ActionBarMenu
                ? ((org.telegram.ui.ActionBar.ActionBarMenu) anchor.getParent()).getPopupSourceButtons(anchor)
                : java.util.Collections.singletonList(anchor);
        interaction = new IosPopupInteraction(this.menuSurface, this::postInvalidateOnAnimation, true, sourceBounds == null);
        this.dismiss = dismiss;
        if (sourceBounds == null && canAnimate(gestureAnchor)) IosPopupOpener.install(gestureAnchor);
        previousLayoutParams = content.getLayoutParams();
        menuWidth = content.getMeasuredWidth();
        menuHeight = content.getMeasuredHeight();
        targetScreenX = x;
        targetScreenY = y;
        boolean avatarButton = anchor instanceof BackupImageView
                && anchor.getParent() instanceof ChatAvatarContainer;
        if (avatarButton) ((ChatAvatarContainer) anchor.getParent()).prepareAvatarPopupAnchor();
        int[] point = new int[2];
        anchor.getLocationOnScreen(point);
        source.set(point[0], point[1], point[0] + anchor.getWidth(), point[1] + anchor.getHeight());
        if (sourceBounds != null) {
            source.set(messageSourceCircle(sourceBounds));
        } else if (anchor instanceof BackupImageView && !avatarButton) {
            android.graphics.RectF image = ((BackupImageView) anchor).getImageReceiver().getDrawRegion();
            if (image != null && !image.isEmpty()) {
                source.set(point[0]+image.left, point[1]+image.top, point[0]+image.right, point[1]+image.bottom);
            }
        }
        if (sourceBounds == null && (!(anchor instanceof BackupImageView) || avatarButton)) {
            android.graphics.Matrix matrix = new android.graphics.Matrix();
            anchor.transformMatrixToGlobal(matrix);
            float visualHeight = anchor instanceof AnimatedTextView
                    ? Math.min(anchor.getHeight(), ((AnimatedTextView) anchor).getTextHeight() + AndroidUtilities.dp(6))
                    : anchor.getHeight();
            RectF visual = new RectF(0, (anchor.getHeight() - visualHeight) / 2,
                    anchor.getWidth(), (anchor.getHeight() + visualHeight) / 2);
            matrix.mapRect(visual);
            int[] rootScreen = new int[2];
            int[] rootWindow = new int[2];
            anchor.getRootView().getLocationOnScreen(rootScreen);
            anchor.getRootView().getLocationInWindow(rootWindow);
            visual.offset(rootScreen[0] - rootWindow[0], rootScreen[1] - rootWindow[1]);
            source.set(visual);
        }
        throughSource.set(source);
        if (headerSource != null) {
            targetScreenX += Math.round(headerSource.through.centerX() - throughSource.centerX());
            targetScreenY += Math.round(headerSource.through.centerY() - throughSource.centerY());
            source.set(headerSource.bounds);
            throughSource.set(headerSource.through);
        }
        if (portalButtons.size() > 1) {
            for (View button : portalButtons) {
                button.getLocationOnScreen(point);
                source.union(point[0], point[1], point[0] + button.getWidth(), point[1] + button.getHeight());
            }
        } else if (portalButtons.size() == 1 && !(anchor instanceof android.widget.TextView)
                && !(anchor instanceof AnimatedTextView) && !(anchor instanceof org.telegram.ui.ActionBar.SimpleTextView)) {
            float centerX = source.centerX();
            float centerY = source.centerY();
            float halfWidth = Math.min(AndroidUtilities.dp(22), source.width() * .5f);
            float halfHeight = Math.min(AndroidUtilities.dp(22), source.height() * .5f);
            source.set(centerX-halfWidth, centerY-halfHeight, centerX+halfWidth, centerY+halfHeight);
            throughSource.set(source);
        }
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
        host.getViewTreeObserver().addOnPreDrawListener(backdropListener);
        gestureBridge = new IosPopupGestureBridge(gestureAnchor, this::forwardTouch);
    }

    /** Selection menus originate at the current caption, like Glasmio's measured local anchor. */
    private static View selectionAnchor(View view) {
        View value = null;
        if (view instanceof org.telegram.ui.Cells.TextSettingsCell) {
            value = ((org.telegram.ui.Cells.TextSettingsCell) view).getValueTextView();
        } else if (view instanceof org.telegram.ui.Cells.TextDetailSettingsCell) {
            value = ((org.telegram.ui.Cells.TextDetailSettingsCell) view).getValueTextView();
        } else if (view instanceof org.telegram.ui.Cells.TextCell) {
            value = ((org.telegram.ui.Cells.TextCell) view).getValueTextView();
        }
        return value != null && value.isShown() && value.getWidth() > 0 ? value : view;
    }

    public static void prepareRows(View view) {
        if(view instanceof org.telegram.ui.ActionBar.ActionBarMenuSubItem)
            ((org.telegram.ui.ActionBar.ActionBarMenuSubItem)view).setIosPopupStyle(true);
        if(view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++) prepareRows(group.getChildAt(i));
        }
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
        // iOS padding is real foreground padding; it is part of the physical glass bounds.
        target.set(x,y,x+menuWidth,y+menuHeight);
    }

    public void open() {
        startMotion(true, null);
    }

    public void close(Runnable onEnd) {
        if (headerSource != null && motion.initialized) {
            headerSource.updateBounds();
            getLocationOnScreen(windowLocation);
            RectF live = new RectF(headerSource.bounds);
            RectF through = new RectF(headerSource.through);
            live.offset(-windowLocation[0], -windowLocation[1]);
            through.offset(-windowLocation[0], -windowLocation[1]);
            motion.updateSource(live, through);
        }
        startMotion(false, onEnd);
    }

    private void startMotion(boolean expanded, Runnable onEnd) {
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        closing = !expanded;
        closingStartedNanos = closing ? System.nanoTime() : 0;
        setAlpha(1);
        closeCompletion = onEnd;
        lastFrameNanos = System.nanoTime();
        if (motion.initialized) motion.retarget(expanded);
        if (closing && (!motion.initialized || Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled())) {
            finishMotion();
            return;
        }
        invalidate();
        Choreographer.getInstance().postFrameCallback(frameCallback);
    }

    private void advanceFrame(long frameTimeNanos) {
        if (released) return;
        float seconds = Math.max(0, frameTimeNanos - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = frameTimeNanos;
        boolean animate = Build.VERSION.SDK_INT < 26 || ValueAnimator.areAnimatorsEnabled();
        boolean settled = motion.initialized && motion.advanceBy(animate ? seconds : 10);
        if (closing) setAlpha(closingOpacity((frameTimeNanos - closingStartedNanos) / 1_000_000_000f));
        if (closing && getAlpha() <= 0 || settled && !closing) {
            finishMotion();
        } else {
            invalidate();
            Choreographer.getInstance().postFrameCallback(frameCallback);
        }
    }

    private static float closingOpacity(float seconds) {
        float t = Math.max(0, Math.min(1, (seconds - .20f) / .16f));
        return 1 - t * t * (3 - 2 * t);
    }

    private void finishMotion() {
        Runnable completion = closeCompletion;
        closeCompletion = null;
        if (completion != null) completion.run();
        else invalidate();
    }

    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (closing || !motion.initialized) return true;
        float mappedX = target.centerX() + (interaction.untransform(event.getX(),target,true) - geometry[0]) / dragScaleX;
        float mappedY = target.centerY() + (interaction.untransform(event.getY(),target,false) - geometry[1]) / dragScale;
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
        boolean handled = (translatingTouch && interaction.touch(event, x, y, false))
                || super.dispatchTouchEvent(translated != null ? translated : event);
        if (translated != null) translated.recycle();
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) translatingTouch = false;
        return handled;
    }

    public boolean forwardTouch(MotionEvent event) {
        if (closing || released || !motion.initialized) return true;
        getLocationOnScreen(windowLocation);
        menuSurface.getLocationOnScreen(surfaceLocation);
        float x = target.centerX() + (interaction.untransform(event.getRawX()-windowLocation[0],target,true) - geometry[0]) / dragScaleX
                + windowLocation[0] - surfaceLocation[0];
        float y = target.centerY() + (interaction.untransform(event.getRawY()-windowLocation[1],target,false) - geometry[1]) / dragScale
                + windowLocation[1] - surfaceLocation[1];
        return interaction.touch(event, x, y, true);
    }

    private static void setMenuTarget(RectF target, View surface, float x, float y) {
        float surfaceWidth = surface.getWidth();
        float surfaceHeight = surface.getHeight();
        if (surface instanceof ActionBarPopupWindow.ActionBarPopupWindowLayout) {
            // The swipe-back container also measures hidden submenu pages.
            ActionBarPopupWindow.ActionBarPopupWindowLayout layout = (ActionBarPopupWindow.ActionBarPopupWindowLayout) surface;
            surfaceWidth *= layout.getBackScaleX();
            surfaceHeight *= layout.getBackScaleY();
        }
        target.set(x, y, x + surfaceWidth, y + surfaceHeight);
    }

    @Override protected void dispatchDraw(Canvas canvas) {
        if (released || target.isEmpty() || Build.VERSION.SDK_INT >= 33 && GlasmioPopupRenderer.isCapturing()) return;
        if (menuSurface != content) super.dispatchDraw(canvas);
        getLocationOnScreen(windowLocation);
        menuSurface.getLocationOnScreen(surfaceLocation);
        float surfaceX=surfaceLocation[0]-windowLocation[0], surfaceY=surfaceLocation[1]-windowLocation[1];
        setMenuTarget(target, menuSurface, surfaceX, surfaceY);
        RectF localSource=new RectF(source);
        localSource.offset(-windowLocation[0],-windowLocation[1]);
        if (!motion.initialized) {
            if(morphFromAnchor) {
                RectF through=new RectF(headerSource==null?throughSource:headerSource.through); through.offset(-windowLocation[0],-windowLocation[1]);
                motion.initialize(localSource,target,AndroidUtilities.dp(34),AndroidUtilities.density,through);
            }
            else motion.initializeSeparate(target,localSource.centerY()>target.centerY(),AndroidUtilities.density);
            motion.retarget(!closing);
            if (Build.VERSION.SDK_INT >= 26 && !ValueAnimator.areAnimatorsEnabled()) motion.advanceBy(10);
        } else motion.updateTarget(target,AndroidUtilities.dp(34));
        setGeometry(motion,geometry);
        float zoom = glassFailed || Build.VERSION.SDK_INT < 33 || !canvas.isHardwareAccelerated() ? 1 : motion.contentZoom;
        dragScaleX = geometry[2] / target.width() * zoom;
        dragScale = geometry[3] / target.height() * zoom;
        if (Build.VERSION.SDK_INT >= 33 && canvas.isHardwareAccelerated() && !glassFailed) {
            try {
                if (glass == null) glass=new Glass(this::postInvalidateOnAnimation);
                if (glass.preparePortalShaders()) {
                    if (backdropDirty) {
                        backdropDirty = !glass.captureBackdrop(host, windowLocation);
                        if (backdropDirty) postInvalidateOnAnimation();
                    }
                    int save=canvas.save(); interaction.transform(canvas,target);
                    try { glass.draw(canvas,menuSurface,surfaceX,surfaceY,motion,target,tint,interaction); } finally { canvas.restoreToCount(save); }
                    return;
                }
            } catch (RuntimeException e) {
                FileLog.e(e); if (glass != null) glass.release(); glass=null; glassFailed=true;
            }
        }
        dragScaleX = geometry[2] / target.width();
        dragScale = geometry[3] / target.height();
        drawFallback(canvas,menuSurface,surfaceX,surfaceY,motion,target,tint,fallbackPaint);
    }

    private static void setGeometry(GlasmioMenuMotion motion,float[] g) {
        g[0]=motion.bounds.centerX(); g[1]=motion.bounds.centerY();
        g[2]=motion.bounds.width(); g[3]=motion.bounds.height(); g[4]=0;
        for(int i=5;i<9;i++) g[i]=Math.min(motion.radiusX,motion.radiusY);
    }

    private static void drawFallback(Canvas canvas,View content,float x,float y,GlasmioMenuMotion f,RectF target,int tint,Paint paint) {
        int save=canvas.saveLayerAlpha(f.bounds,Math.round(255*f.contentProgress));
        canvas.clipRect(f.bounds);
        paint.setColor(Theme.multAlpha(tint, org.telegram.messenger.SharedConfig.chatBlurEnabled() ? 1f : .92f)); canvas.drawRoundRect(f.bounds,f.radiusX,f.radiusY,paint);
        canvas.translate(f.bounds.centerX(),f.bounds.centerY());
        canvas.scale(f.bounds.width()/target.width(),f.bounds.height()/target.height());
        canvas.translate(x-target.centerX(),y-target.centerY());
        drawMenuContent(content,canvas); canvas.restoreToCount(save);
    }

    public void release() {
        if (released) return;
        released = true;
        Choreographer.getInstance().removeFrameCallback(frameCallback);
        closeCompletion = null;
        if (host.getViewTreeObserver().isAlive()) host.getViewTreeObserver().removeOnPreDrawListener(backdropListener);
        interaction.release();
        gestureBridge.release();
        if (glass != null) glass.release();
        if (headerSource != null) headerSource.release();
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

    static void drawMenuContent(View content, Canvas canvas) {
        ActionBarPopupWindow.ActionBarPopupWindowLayout layout = content instanceof ActionBarPopupWindow.ActionBarPopupWindowLayout
                ? (ActionBarPopupWindow.ActionBarPopupWindowLayout) content : null;
        if (layout != null) layout.setIosSurfaceRecording(true);
        try { content.draw(canvas); }
        finally { if (layout != null) layout.setIosSurfaceRecording(false); }
    }

    private static RectF messageSourceCircle(RectF bounds) {
        float r = AndroidUtilities.dp(20);
        return new RectF(bounds.centerX() - r, bounds.bottom - 2 * r, bounds.centerX() + r, bounds.bottom);
    }

    public static void closeEmbedded(View view) {
        if (view instanceof ActionBarPopupWindow.ActionBarPopupWindowLayout)
            ((ActionBarPopupWindow.ActionBarPopupWindowLayout) view).closeIosMessageSurface();
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) closeEmbedded(group.getChildAt(i));
        }
    }

    public static boolean embeddedClosed(View view) {
        if (view instanceof ActionBarPopupWindow.ActionBarPopupWindowLayout
                && !((ActionBarPopupWindow.ActionBarPopupWindowLayout)view).isIosMessageSurfaceClosed()) return false;
        if (view instanceof ViewGroup) {
            ViewGroup group=(ViewGroup)view;
            for(int i=0;i<group.getChildCount();i++) if(!embeddedClosed(group.getChildAt(i))) return false;
        }
        return true;
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
        private final RectF source, target=new RectF();
        private final int[] location=new int[2];
        private final float[] geometry=new float[9];
        private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private final IdentityHashMap<View,Drawable> backgrounds=new IdentityHashMap<>();
        private final int tint;
        private final GlasmioMenuMotion motion=new GlasmioMenuMotion();
        private final IosPopupInteraction interaction;
        private final IosPopupGestureBridge gestureBridge;
        private Glass glass;
        private View backdropHost;
        private long previousFrame;
        private boolean closing,released,failed,settled;
        private long closeStartedNanos;

        public Embedded(ActionBarPopupWindow.ActionBarPopupWindowLayout content,View sourceView,RectF screenBounds,int tint) {
            this.content = content;
            source = screenBounds.isEmpty() ? new RectF() : messageSourceCircle(screenBounds);
            this.tint = tint;
            interaction=new IosPopupInteraction(content,content::postInvalidateOnAnimation, true, false);
            stripMenuSurfaces(content,content,backgrounds);
            gestureBridge=new IosPopupGestureBridge(sourceView,this::forwardTouch);
        }
        public void setBackdropHost(View host) { backdropHost = host; }

        private View backdropHost() {
            if (backdropHost != null) return backdropHost;
            for (View view = content; view != null;
                 view = view.getParent() instanceof View ? (View) view.getParent() : null) {
                if (view instanceof Backdrop) return view;
            }
            return content.getRootView();
        }

        public void draw(Canvas canvas) {
            if(released || Build.VERSION.SDK_INT>=33 && GlasmioPopupRenderer.isCapturing() || content.getWidth()==0 || content.getHeight()==0) return;
            content.getLocationOnScreen(location);
            setMenuTarget(target, content, 0, 0);
            long now=System.nanoTime();
            if(!motion.initialized) {
                // Preview menus use Glasmio's compact scale/fade, without a source lobe.
                motion.initializeSeparate(target, false, AndroidUtilities.density);
                motion.retarget(!closing);
            } else motion.updateTarget(target,AndroidUtilities.dp(34));
            boolean animate=Build.VERSION.SDK_INT<26 || ValueAnimator.areAnimatorsEnabled();
            settled=motion.advanceBy(animate ? (previousFrame==0 ? 0 : (now-previousFrame)/1_000_000_000f) : 10);
            if (closing) {
                content.setAlpha(closingOpacity((now - closeStartedNanos) / 1_000_000_000f));
                settled = content.getAlpha() <= 0;
            }
            previousFrame=now; setGeometry(motion,geometry);
            if(!settled) content.postInvalidateOnAnimation();
            if(Build.VERSION.SDK_INT>=33 && canvas.isHardwareAccelerated() && !failed) {
                try {
                    if(glass==null) glass=new Glass(content::postInvalidateOnAnimation);
                    if (glass.preparePortalShaders()) {
                        glass.captureBackdrop(backdropHost(), location, true);
                        int save=canvas.save(); interaction.transform(canvas,target);
                        try { glass.draw(canvas,content,0,0,motion,target,tint,interaction); } finally { canvas.restoreToCount(save); }
                        return;
                    }
                } catch(RuntimeException e) { FileLog.e(e); if(glass!=null) glass.release(); glass=null; failed=true; }
            }
            drawFallback(canvas,content,0,0,motion,target,tint,paint);
        }
        public boolean touch(MotionEvent event) {
            if (closing) return true;
            if (!motion.initialized) return false;
            float x=target.centerX()+(interaction.untransform(event.getX(),target,true)-geometry[0])*target.width()/Math.max(1,geometry[2]*foregroundZoom());
            float y=target.centerY()+(interaction.untransform(event.getY(),target,false)-geometry[1])*target.height()/Math.max(1,geometry[3]*foregroundZoom());
            return interaction.touch(event,x,y,false);
        }
        public boolean forwardTouch(MotionEvent event) {
            if (closing || released || !motion.initialized) return true;
            content.getLocationOnScreen(location);
            float x=target.centerX()+(interaction.untransform(event.getRawX()-location[0],target,true)-geometry[0])*target.width()/Math.max(1,geometry[2]*foregroundZoom());
            float y=target.centerY()+(interaction.untransform(event.getRawY()-location[1],target,false)-geometry[1])*target.height()/Math.max(1,geometry[3]*foregroundZoom());
            return interaction.touch(event,x,y,true);
        }
        private float foregroundZoom() {
            return glass != null && !failed && glass.hasPortalShaders() ? motion.contentZoom : 1f;
        }

        public void close() {
            if(closing || released) return;
            closing=true; settled=false; closeStartedNanos=previousFrame=System.nanoTime();
            if(motion.initialized) motion.retarget(false);
            content.invalidate();
        }
        public boolean isClosed() { return closing && (settled || !motion.initialized
                    || System.nanoTime() - closeStartedNanos >= 360_000_000L); }
        public void release() {
            if(released) return; released=true;
            interaction.release(); gestureBridge.release();
            if(glass!=null) glass.release(); content.setAlpha(1); restoreMenuSurfaces(backgrounds);
        }
    }

    @RequiresApi(33)
    private static final class Glass extends GlasmioPopupRenderer {
        Glass(Runnable onOpticsReady) {
            super(onOpticsReady);
        }
    }
}
