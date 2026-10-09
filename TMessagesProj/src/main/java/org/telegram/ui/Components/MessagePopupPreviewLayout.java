package org.telegram.ui.Components;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Picture;
import android.graphics.Rect;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.animation.PathInterpolator;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.OverScroller;

/** A message and its menu in one viewport, rendered in the popup window above the chat blur. */
public class MessagePopupPreviewLayout extends FrameLayout {
    public interface Source {
        boolean getBounds(RectF bounds);
        boolean contains(float screenX, float screenY);
        void getViewport(RectF bounds);
        void draw(Canvas canvas);
    }

    private final Source source;
    private final View menu, placeholder;
    private final ScrollView scroll;
    private final LinearLayout column;
    private final Runnable dismiss;
    private final RectF sourceBounds = new RectF(), viewport = new RectF(), drawnBounds = new RectF();
    private final Rect hitRect = new Rect();
    private final int[] location = new int[2], targetLocation = new int[2];
    private float preferredX, preferredY;
    private ValueAnimator animator;
    private float progress, sourceY;
    private long lastFrame;
    private boolean sourceInitialized, closing, overflow, drawingMessage, opening;
    private Runnable onReady;
    private boolean outsideDown, dragged;
    private float downX, downY;
    private Picture transitionMessage;
    private Runnable afterClose;
    private boolean transitionClosing;
    private ValueAnimator overscrollAnimator;
    private float overscrollOffset;

    public MessagePopupPreviewLayout(Context context, View menu, Source source,
                                     float preferredX, float preferredY, Runnable dismiss) {
        super(context);
        this.menu = menu;
        this.source = source;
        this.preferredX = preferredX;
        this.preferredY = preferredY;
        this.dismiss = dismiss;
        setClipChildren(false);
        scroll = new ScrollView(context) {
            private final OverScroller flingScroller = new OverScroller(context);
            private int lastFlingY;

            @Override
            public void fling(int velocityY) {
                if (!overflow || progress != 1f || closing) return;
                if (overscrollOffset != 0f) {
                    releaseOverscroll();
                    return;
                }
                lastFlingY = getScrollY();
                // Let inertia continue past the edge so its remaining velocity can
                // become a bounce, instead of being discarded by ScrollView's bounds.
                flingScroller.fling(0, lastFlingY, 0, velocityY, 0, 0,
                        Integer.MIN_VALUE / 2, Integer.MAX_VALUE / 2);
                postInvalidateOnAnimation();
            }

            @Override
            public void computeScroll() {
                super.computeScroll();
                if (closing) {
                    flingScroller.abortAnimation();
                    return;
                }
                if (!flingScroller.computeScrollOffset()) return;
                int currentY = flingScroller.getCurrY();
                int delta = currentY - lastFlingY;
                lastFlingY = currentY;
                int range = MessagePopupPreviewGeometry.openingScroll(column.getHeight(),
                        getHeight() - getPaddingTop() - getPaddingBottom());
                int nextY = getScrollY() + delta;
                int clampedY = Math.max(0, Math.min(nextY, range));
                scrollTo(0, clampedY);
                if (nextY != clampedY) {
                    float velocity = Math.copySign(flingScroller.getCurrVelocity(), delta);
                    flingScroller.abortAnimation();
                    animateFlingOverscroll(velocity);
                } else {
                    postInvalidateOnAnimation();
                }
            }

            @Override
            protected void onDetachedFromWindow() {
                flingScroller.abortAnimation();
                super.onDetachedFromWindow();
            }
            @Override
            public void requestChildFocus(View child, View focused) {
                int scrollY = getScrollY();
                super.requestChildFocus(child, focused);
                // Menu focus must not move the message. ScrollView also remembers the
                // focused child for its next layout; onLayout restores this offset too.
                scrollTo(0, scrollY);
            }

            @Override
            protected void onSizeChanged(int w, int h, int oldw, int oldh) {
                int scrollY = getScrollY();
                super.onSizeChanged(w, h, oldw, oldh);
                scrollTo(0, scrollY);
            }

            @Override
            protected void onLayout(boolean changed, int l, int t, int r, int b) {
                int scrollY = getScrollY();
                super.onLayout(changed, l, t, r, b);
                // Preserve the reading position. The opening offset is applied after layout.
                scrollTo(0, drawingMessage ? scrollY : 0);
            }

            @Override
            public boolean requestChildRectangleOnScreen(View child, Rect rectangle, boolean immediate) {
                // Keep scrolling explicit, including after the opening animation.
                // Focusing a menu action must not replace the user's reading position.
                return false;
            }

            @Override
            public boolean onInterceptTouchEvent(MotionEvent event) {
                if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
                    boolean wasFlinging = !flingScroller.isFinished();
                    flingScroller.abortAnimation();
                    if (wasFlinging) return true;
                }
                return overflow && super.onInterceptTouchEvent(event);
            }

            @Override
            public boolean onTouchEvent(MotionEvent event) {
                return overflow && super.onTouchEvent(event);
            }

            @Override
            protected boolean overScrollBy(int deltaX, int deltaY, int scrollX, int scrollY,
                                           int scrollRangeX, int scrollRangeY,
                                           int maxOverScrollX, int maxOverScrollY, boolean isTouchEvent) {
                if (overflow && progress == 1f && !closing) {
                    if (isTouchEvent && overscrollOffset != 0f) {
                        cancelOverscrollAnimation();
                        float next = MessagePopupPreviewGeometry.overscroll(overscrollOffset, -deltaY, dp(96));
                        if (next * overscrollOffset > 0f) {
                            setOverscrollOffset(next);
                            return false;
                        }
                        setOverscrollOffset(0f);
                    }
                    int clampedY = Math.max(0, Math.min(scrollY + deltaY, scrollRangeY));
                    int excess = scrollY + deltaY - clampedY;
                    if (excess != 0 && (isTouchEvent || overscrollAnimator == null)) {
                        if (isTouchEvent) cancelOverscrollAnimation();
                        setOverscrollOffset(MessagePopupPreviewGeometry.overscroll(overscrollOffset, -excess, dp(96)));
                        if (!isTouchEvent) releaseOverscroll();
                    }
                }
                // Keep the actual scroll position clamped; translate message and menu together.
                return super.overScrollBy(deltaX, deltaY, scrollX, scrollY, scrollRangeX, scrollRangeY, 0, 0, isTouchEvent);
            }
        };
        scroll.setVerticalScrollBarEnabled(false);
        // Focus-driven smoothScrollBy would keep moving on later computeScroll frames,
        // after the offset has been restored above. Touch flings remain available.
        scroll.setSmoothScrollingEnabled(false);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        scroll.setClipToPadding(false);
        column = new LinearLayout(context);
        column.setOrientation(LinearLayout.VERTICAL);
        placeholder = new View(context);
        placeholder.setClickable(true);
        column.addView(placeholder, new LinearLayout.LayoutParams(1, 1));
        column.addView(menu, new LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT));
        scroll.addView(column, new ScrollView.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT));
        addView(scroll, new FrameLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        menu.setAlpha(0f);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    public void setAnchor(float x, float y) {
        preferredX = x;
        preferredY = y;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        getLocationOnScreen(location);
        source.getBounds(sourceBounds);
        source.getViewport(viewport);
        int top = Math.max(0, Math.round(viewport.top - location[1]));
        int bottom = Math.min(height, Math.round(viewport.bottom - location[1]));
        int available = Math.max(1, bottom - top);
        int margin = dp(8), gap = dp(8);
        LinearLayout.LayoutParams menuParams = (LinearLayout.LayoutParams) menu.getLayoutParams();
        menuParams.height = LayoutParams.WRAP_CONTENT;
        menu.measure(MeasureSpec.makeMeasureSpec(Math.max(1, width - 2 * margin), MeasureSpec.AT_MOST),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED));
        int previewWidth = Math.min(width, Math.max(1, Math.round(sourceBounds.width())));
        int previewHeight = Math.max(1, Math.round(sourceBounds.height()));
        int menuHeight = menu.getMeasuredHeight() + dp(12);
        overflow = MessagePopupPreviewGeometry.needsScroll(available, previewHeight, menuHeight, gap);
        int menuLeft = Math.round(Math.max(margin, Math.min(preferredX, width - margin - menu.getMeasuredWidth())));
        int previewLeft = Math.round(sourceBounds.left - location[0]);
        int contentTop = MessagePopupPreviewGeometry.contentTop(available, previewHeight, menuHeight, gap, preferredY - top);
        column.setPadding(0, contentTop, 0, dp(12));
        LinearLayout.LayoutParams previewParams = (LinearLayout.LayoutParams) placeholder.getLayoutParams();
        previewParams.width = previewWidth;
        previewParams.height = previewHeight;
        previewParams.leftMargin = previewLeft;
        menuParams.width = menu.getMeasuredWidth();
        // Keep the entire measured menu in the outer scroller, including its last action.
        menuParams.height = menu.getMeasuredHeight();
        menuParams.leftMargin = menuLeft;
        menuParams.topMargin = gap;
        FrameLayout.LayoutParams scrollParams = (FrameLayout.LayoutParams) scroll.getLayoutParams();
        scrollParams.topMargin = top;
        scrollParams.height = available;
        super.onMeasure(widthSpec, heightSpec);
    }

    public boolean isDrawingMessage() {
        return drawingMessage;
    }

    public void open(Runnable onReady) {
        this.onReady = onReady;
        opening = true;
        invalidate();
    }

    public void close(Runnable afterReturn) {
        if (closing) return;
        closing = true;
        releaseOverscroll();
        afterClose = afterReturn;
        scroll.setEnabled(false);
        animateTo(0f, afterReturn);
    }

    /** Freeze the last visible position before the chat starts moving underneath the window. */
    public void closeForTransition(Runnable afterReturn) {
        if (transitionClosing) return;
        transitionClosing = true;
        cancelOverscrollAnimation();
        if (!closing) afterClose = afterReturn;
        closing = true;
        opening = false;
        scroll.setEnabled(false);
        if (drawingMessage && !drawnBounds.isEmpty()) {
            transitionMessage = new Picture();
            Canvas recording = transitionMessage.beginRecording(
                    Math.max(1, (int) Math.ceil(sourceBounds.width())),
                    Math.max(1, (int) Math.ceil(sourceBounds.height())));
            recording.translate(-sourceBounds.left, -sourceBounds.top);
            source.draw(recording);
            transitionMessage.endRecording();
        }
        if (animator != null) {
            animator.removeAllListeners();
            animator.cancel();
        }
        animator = ValueAnimator.ofFloat(getAlpha(), 0f);
        animator.setDuration(300);
        animator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        animator.addUpdateListener(animation -> setAlpha((float) animation.getAnimatedValue()));
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                animator = null;
                if (afterClose != null) afterClose.run();
            }
        });
        animator.start();
    }

    private void animateTo(float target, Runnable done) {
        if (animator != null) {
            animator.removeAllListeners();
            animator.cancel();
        }
        animator = ValueAnimator.ofFloat(progress, target);
        animator.setDuration(320);
        animator.setInterpolator(new PathInterpolator(.22f, 0f, .18f, 1f));
        animator.addUpdateListener(animation -> {
            progress = (float) animation.getAnimatedValue();
            menu.setAlpha(progress);
            invalidate();
        });
        animator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                animator = null;
                if (done != null) done.run();
            }
        });
        animator.start();
    }

    private void scrollToOpeningPosition() {
        scroll.scrollTo(0, MessagePopupPreviewGeometry.openingScroll(column.getHeight(), scroll.getHeight()));
    }

    private void setOverscrollOffset(float offset) {
        overscrollOffset = offset;
        column.setTranslationY(offset);
        invalidate();
    }

    private void cancelOverscrollAnimation() {
        if (overscrollAnimator == null) return;
        overscrollAnimator.removeAllListeners();
        overscrollAnimator.cancel();
        overscrollAnimator = null;
    }

    private void releaseOverscroll() {
        if (overscrollOffset == 0f || transitionClosing) return;
        cancelOverscrollAnimation();
        overscrollAnimator = ValueAnimator.ofFloat(overscrollOffset, 0f);
        overscrollAnimator.setDuration(700);
        overscrollAnimator.setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT);
        overscrollAnimator.addUpdateListener(animation -> setOverscrollOffset((float) animation.getAnimatedValue()));
        overscrollAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                overscrollAnimator = null;
                setOverscrollOffset(0f);
            }
        });
        overscrollAnimator.start();
    }

    private void animateFlingOverscroll(float velocity) {
        if (closing || transitionClosing) return;
        cancelOverscrollAnimation();
        float start = overscrollOffset;
        overscrollAnimator = ValueAnimator.ofFloat(0f, 1f);
        overscrollAnimator.setDuration(700);
        overscrollAnimator.setInterpolator(new LinearInterpolator());
        overscrollAnimator.addUpdateListener(animation -> {
            float time = (float) animation.getAnimatedValue();
            setOverscrollOffset(MessagePopupPreviewGeometry.flingSpring(time, start, velocity, dp(96)));
        });
        overscrollAnimator.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                overscrollAnimator = null;
                setOverscrollOffset(0f);
            }
        });
        overscrollAnimator.start();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        if (opening && !closing) {
            opening = false;
            scrollToOpeningPosition();
            drawingMessage = true;
            if (onReady != null) {
                onReady.run();
                onReady = null;
            }
            // Start only after layout: the first preview frame exactly matches the live cell.
            animateTo(1f, null);
        }
        super.dispatchDraw(canvas);
        if (transitionClosing) {
            if (transitionMessage != null) {
                int saved = canvas.save();
                canvas.translate(drawnBounds.left, drawnBounds.top);
                canvas.drawPicture(transitionMessage);
                canvas.restoreToCount(saved);
            }
            return;
        }
        getLocationOnScreen(location);
        source.getViewport(viewport);
        boolean attached = source.getBounds(sourceBounds);
        if (!attached) {
            // A recycled/offscreen message must never fly back to an obsolete cached position.
            if (!closing) dismiss.run();
            return;
        }
        long now = SystemClock.uptimeMillis();
        float follow = sourceInitialized ? MessagePopupPreviewGeometry.followFraction(now - lastFrame, progress, closing) : 1f;
        sourceY += (sourceBounds.top - sourceY) * follow;
        sourceInitialized = true;
        lastFrame = now;
        placeholder.getLocationOnScreen(targetLocation);
        float x = MessagePopupPreviewGeometry.horizontalPosition(sourceBounds.left, location[0]);
        float targetY = MessagePopupPreviewGeometry.destinationTop(viewport.top, targetLocation[1], scroll.getScrollY());
        float y = MessagePopupPreviewGeometry.position(sourceY, targetY, progress) - location[1];
        drawnBounds.set(x, y, x + sourceBounds.width(), y + sourceBounds.height());
        int saved = canvas.save();
        // The inset viewport positions the scroller and menu; it must not cut the
        // message at the status/action bar or bottom controls. Draw into the full window.
        canvas.translate(x - sourceBounds.left, y - sourceBounds.top);
        source.draw(canvas);
        canvas.restoreToCount(saved);
        // Resolve both the live chat position and the scroll destination on every frame.
        postInvalidateOnAnimation();
        int top = Math.max(0, Math.round(viewport.top - location[1]));
        int bottom = Math.min(getHeight(), Math.round(viewport.bottom - location[1]));
        if (scroll.getTop() != top || scroll.getHeight() != Math.max(1, bottom - top)
                || placeholder.getHeight() != Math.round(sourceBounds.height())) {
            requestLayout();
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent event) {
        if (closing) return true;
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            cancelOverscrollAnimation();
            source.getViewport(viewport);
            boolean onMessage = drawnBounds.contains(event.getX(), event.getY())
                    && source.contains(event.getX() - drawnBounds.left + sourceBounds.left,
                            event.getY() - drawnBounds.top + sourceBounds.top);
            outsideDown = !onMessage && !containsMenu(event.getRawX(), event.getRawY());
            downX = event.getX();
            downY = event.getY();
            dragged = false;
            if (outsideDown && !overflow) {
                dismiss.run();
                return true;
            }
        } else if (event.getActionMasked() == MotionEvent.ACTION_MOVE) {
            int slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
            if (Math.hypot(event.getX() - downX, event.getY() - downY) > slop) dragged = true;
        } else if (event.getActionMasked() == MotionEvent.ACTION_UP) {
            releaseOverscroll();
            if (outsideDown && !dragged) {
                outsideDown = false;
                dismiss.run();
                return true;
            }
        } else if (event.getActionMasked() == MotionEvent.ACTION_CANCEL) {
            releaseOverscroll();
            outsideDown = false;
        }
        // Keep the gesture even in the gaps, so a swipe can scroll and a tap can dismiss.
        return super.dispatchTouchEvent(event) || outsideDown;
    }

    private boolean containsMenu(float x, float y) {
        int count = menu instanceof ViewGroup ? ((ViewGroup) menu).getChildCount() : 1;
        for (int i = 0; i < count; i++) {
            View child = menu instanceof ViewGroup ? ((ViewGroup) menu).getChildAt(i) : menu;
            if (child.getVisibility() != VISIBLE) continue;
            child.getLocationOnScreen(targetLocation);
            hitRect.set(targetLocation[0], targetLocation[1], targetLocation[0] + child.getWidth(), targetLocation[1] + child.getHeight());
            if (hitRect.contains((int) x, (int) y) && viewport.contains(x, y)) return true;
        }
        return false;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP) dismiss.run();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    public void dispose() {
        cancelOverscrollAnimation();
        setOverscrollOffset(0f);
        opening = false;
        drawingMessage = false;
        onReady = null;
        afterClose = null;
        transitionMessage = null;
        if (animator != null) {
            animator.removeAllListeners();
            animator.cancel();
            animator = null;
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        dispose();
        super.onDetachedFromWindow();
    }
}
