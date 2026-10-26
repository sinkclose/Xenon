/*
 * This is the source code of Telegram for Android v. 5.x.x.
 * It is licensed under GNU GPL v. 2 or later.
 * You should have received a copy of the license in this archive (see LICENSE).
 *
 * Copyright Nikolai Kudashov, 2013-2018.
 */

package org.telegram.ui.ActionBar;

import static org.telegram.messenger.AndroidUtilities.dp;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Outline;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;

import androidx.annotation.Keep;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.AnimationNotificationsLocker;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.R;
import org.telegram.messenger.UserConfig;
import org.telegram.messenger.utils.ViewOutlineProviderImpl;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.IosPopupMorph;
import org.telegram.ui.Components.IosPopupMorphGeometry;
import org.telegram.ui.Components.PopupSwipeBackLayout;
import org.telegram.ui.Components.PopupMenuSurface;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;

public class ActionBarPopupWindow extends PopupWindow {

    private static Method layoutInScreenMethod;
    private static final Field superListenerField;
    private static DecelerateInterpolator decelerateInterpolator = new DecelerateInterpolator();
    private AnimatorSet windowAnimatorSet;
    private boolean animationEnabled = true;
    private int dismissAnimationDuration = 150;
    private boolean isClosingAnimated;
    private int currentAccount = UserConfig.selectedAccount;
    private boolean pauseNotifications;
    private long outEmptyTime = -1;
    private boolean scaleOut;
    private java.lang.ref.WeakReference<View> animationSource;
    private RectF animationSourceBounds;
    private View animationSurface;
    private IosPopupMorph iosSurface;
    private PopupMenuSurface nativeSurface;
    private View nativeContent;
    private View iosContent;
    private int iosSavedWidth, iosSavedHeight, iosSavedStyle;
    private float iosSavedElevation;
    private Drawable iosSavedBackground;
    private boolean iosSavedClipping, iosSavedInScreen;

    /** The actual button, which can differ from the PopupWindow positioning anchor. */
    public void setAnimationSource(View source) {
        animationSource = source == null ? null : new java.lang.ref.WeakReference<>(source);
        animationSourceBounds = null;
        animationSurface = null;
    }

    public void setAnimationSource(View source, RectF screenBounds, View menuSurface) {
        setAnimationSource(source);
        animationSourceBounds = new RectF(screenBounds);
        animationSurface = menuSurface;
    }

    private View morphSource(View fallback) {
        View source = animationSource == null ? null : animationSource.get();
        return source != null ? source : fallback;
    }

    private boolean useIosMorph(View source) {
        return zxc.iconic.xenon.NekoConfig.iosPopups && source != null && source.isAttachedToWindow()
                && (animationSourceBounds != null || IosPopupMorph.canAnimate(source));
    }

    /** Continue the gesture owned by the source button after its popup window opens. */
    public boolean forwardIosTouch(MotionEvent event) {
        return iosSurface != null ? iosSurface.forwardTouch(event)
                : nativeSurface != null && nativeSurface.forwardTouch(event);
    }

    @Override public View getContentView() {
        return iosContent != null ? iosContent : nativeContent != null ? nativeContent : super.getContentView();
    }

    private void prepareNativeSurface(View source) {
        if (nativeSurface != null || isShowing()) return;
        // The full-screen message preview animates its menu separately from the message.
        if (super.getContentView() instanceof org.telegram.ui.Components.MessagePopupPreviewLayout) {
            setAnimationStyle(0);
            return;
        }
        setAnimationStyle(animationEnabled || getAnimationStyle() != 0 ? R.style.PopupContextAnimation : 0);
        nativeContent = super.getContentView();
        nativeContent.setAlpha(1);
        nativeContent.setScaleX(1);
        nativeContent.setScaleY(1);
        nativeContent.setTranslationY(0);
        resetMenuAnimation(nativeContent);
        nativeSurface = new PopupMenuSurface(nativeContent, source);
        super.setContentView(nativeSurface);
    }

    private void restoreNativeContent() {
        if (nativeSurface == null) return;
        nativeSurface.release();
        nativeSurface = null;
        super.setContentView(nativeContent);
        nativeContent = null;
    }

    private static void resetMenuAnimation(View view) {
        if (view instanceof ActionBarPopupWindowLayout) {
            ActionBarPopupWindowLayout menu = (ActionBarPopupWindowLayout) view;
            menu.setAlpha(1);
            menu.setScaleX(1);
            menu.setScaleY(1);
            menu.setTranslationY(0);
            menu.startAnimationPending = false;
            menu.positions.clear();
            menu.setBackScaleY(1);
            menu.setBackAlpha(255);
            for (int i = 0; i < menu.getItemsCount(); i++) {
                View item = menu.getItemAt(i);
                item.setAlpha(item.isEnabled() ? 1f : .5f);
                item.setTranslationY(0);
            }
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) resetMenuAnimation(group.getChildAt(i));
        }
    }

    private void measureIosContent(View source) {
        View content = getContentView();
        if (content.getMeasuredWidth() == 0 || content.getMeasuredHeight() == 0) {
            View root = source.getRootView();
            content.measure(View.MeasureSpec.makeMeasureSpec(Math.max(1, root.getWidth() - dp(24)), View.MeasureSpec.AT_MOST),
                    View.MeasureSpec.makeMeasureSpec(Math.max(1, root.getHeight() - dp(24)), View.MeasureSpec.AT_MOST));
        }
    }

    private boolean showIosSurface(View parent, View source, int screenX, int screenY) {
        if (!useIosMorph(source) || isShowing()) return false;
        measureIosContent(source);
        View root = source.getRootView();
        Rect visible = new Rect();
        root.getWindowVisibleDisplayFrame(visible);
        screenX = IosPopupMorphGeometry.clampMenuPosition(screenX, getContentView().getMeasuredWidth(), visible.left, visible.right, dp(6));
        screenY = IosPopupMorphGeometry.clampMenuPosition(screenY, getContentView().getMeasuredHeight(), visible.top, visible.bottom, dp(6));
        iosSavedWidth = super.getWidth();
        iosSavedHeight = super.getHeight();
        iosSavedStyle = getAnimationStyle();
        iosSavedElevation = getElevation();
        iosSavedBackground = getBackground();
        iosSavedClipping = isClippingEnabled();
        iosSavedInScreen = Build.VERSION.SDK_INT >= 29 && isLaidOutInScreen();
        iosContent = super.getContentView();
        try {
            iosSurface = new IosPopupMorph(iosContent, source, screenX, screenY, this::dismiss, animationSourceBounds, animationSurface);
            super.setContentView(iosSurface);
            setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            setElevation(0);
            setAnimationStyle(0);
            setClippingEnabled(false);
            if (Build.VERSION.SDK_INT >= 29) setIsLaidOutInScreen(true);
            super.setWidth(root.getWidth());
            super.setHeight(root.getHeight());
            int[] point = new int[2];
            root.getLocationOnScreen(point);
            // One native window owns both the travelling lens and the final interactive menu.
            super.showAtLocation(parent, Gravity.TOP | Gravity.LEFT,
                    Build.VERSION.SDK_INT >= 29 ? point[0] : 0,
                    Build.VERSION.SDK_INT >= 29 ? point[1] : 0);
            windowAnimatorSet = iosSurface.opening();
        } catch (RuntimeException | OutOfMemoryError e) {
            FileLog.e(e);
            if (isShowing()) super.dismiss();
            restoreIosContent();
            return false;
        }
        windowAnimatorSet.addListener(new AnimatorListenerAdapter() {
            @Override public void onAnimationEnd(Animator animation) {
                if (windowAnimatorSet == animation) windowAnimatorSet = null;
            }
        });
        windowAnimatorSet.start();
        return true;
    }

    private void restoreIosContent() {
        if (iosSurface != null) iosSurface.release();
        iosSurface = null;
        if (iosContent != null) {
            super.setContentView(iosContent);
            iosContent = null;
            super.setWidth(iosSavedWidth);
            super.setHeight(iosSavedHeight);
            setAnimationStyle(iosSavedStyle);
            setElevation(iosSavedElevation);
            setBackgroundDrawable(iosSavedBackground);
            setClippingEnabled(iosSavedClipping);
            if (Build.VERSION.SDK_INT >= 29) setIsLaidOutInScreen(iosSavedInScreen);
        }
    }

    private void dismissIosSurface(boolean animated) {
        if (isClosingAnimated && animated) return;
        if (windowAnimatorSet != null) {
            windowAnimatorSet.cancel();
            windowAnimatorSet = null;
        }
        Runnable finish = () -> {
            windowAnimatorSet = null;
            isClosingAnimated = false;
            try { ActionBarPopupWindow.super.dismiss(); }
            finally {
                restoreIosContent();
                unregisterListener();
                if (pauseNotifications) notificationsLocker.unlock();
            }
        };
        if (animated && animationEnabled) {
            isClosingAnimated = true;
            windowAnimatorSet = iosSurface.closing(finish);
            if (pauseNotifications) notificationsLocker.lock();
            windowAnimatorSet.start();
        } else finish.run();
    }

    static {
        Field f = null;
        try {
            f = PopupWindow.class.getDeclaredField("mOnScrollChangedListener");
            f.setAccessible(true);
        } catch (NoSuchFieldException e) {
            /* ignored */
        }
        superListenerField = f;
    }

    private static final ViewTreeObserver.OnScrollChangedListener NOP = () -> {
        /* do nothing */
    };

    private ViewTreeObserver.OnScrollChangedListener mSuperScrollListener;
    private ViewTreeObserver mViewTreeObserver;
    private AnimationNotificationsLocker notificationsLocker = new AnimationNotificationsLocker();

    public void setScaleOut(boolean b) {
        scaleOut = b;
    }

    public interface OnDispatchKeyEventListener {
        void onDispatchKeyEvent(KeyEvent keyEvent);
    }

    public static class ActionBarPopupWindowLayout extends FrameLayout {
        public final static int FLAG_USE_SWIPEBACK = 1;
        public final static int FLAG_SHOWN_FROM_BOTTOM = 2;
        public final static int FLAG_DONT_USE_SCROLLVIEW = 4;
        public boolean updateAnimation;
        public boolean clipChildren;
        public boolean swipeBackGravityRight;
        public boolean swipeBackGravityBottom;

        private OnDispatchKeyEventListener mOnDispatchKeyEventListener;
        private float backScaleX = 1;
        private float backScaleY = 1;
        private boolean startAnimationPending = false;
        private int backAlpha = 255;
        private int lastStartedChild = 0;
        public boolean shownFromBottom;
        private boolean animationEnabled = true;
        private ArrayList<AnimatorSet> itemAnimators;
        private HashMap<View, Integer> positions = new HashMap<>();
        private int gapStartY = -1000000;
        private int gapEndY = -1000000;
        private final Rect bgPaddings = new Rect();
        private onSizeChangedListener onSizeChangedListener;
        private float reactionsEnterProgress = 1f;
        private boolean iosSurfaceContent;
        private boolean iosExternalSurface, iosSurfaceRecording;
        private IosPopupMorph.Embedded iosEmbeddedSurface;

        public void setIosExternalSurface(boolean external) {
            iosExternalSurface = external;
            invalidate();
        }

        public void setIosSurfaceRecording(boolean recording) { iosSurfaceRecording = recording; }

        public void closeIosMessageSurface() {
            if (iosEmbeddedSurface != null) iosEmbeddedSurface.close();
        }

        public boolean forwardIosMessageTouch(MotionEvent event) {
            return iosEmbeddedSurface != null && iosEmbeddedSurface.forwardTouch(event);
        }

        @Override public boolean dispatchTouchEvent(MotionEvent event) {
            return iosEmbeddedSurface != null && iosEmbeddedSurface.touch(event) || super.dispatchTouchEvent(event);
        }

        public void enableIosMessageSurface(View sourceView, RectF screenBounds) {
            if (iosEmbeddedSurface != null) iosEmbeddedSurface.release();
            iosEmbeddedSurface = new IosPopupMorph.Embedded(this, sourceView, screenBounds,
                    getThemedColor(Theme.key_actionBarDefaultSubmenuBackground));
        }

        @Override public void draw(Canvas canvas) {
            if (!iosSurfaceRecording && iosExternalSurface) return;
            if (!iosSurfaceRecording && iosEmbeddedSurface != null) iosEmbeddedSurface.draw(canvas);
            else super.draw(canvas);
        }

        @Override protected void onDetachedFromWindow() {
            if (iosEmbeddedSurface != null) {
                iosEmbeddedSurface.release();
                iosEmbeddedSurface = null;
            }
            super.onDetachedFromWindow();
        }

        public void setIosSurfaceContent(boolean enabled) {
            iosSurfaceContent = enabled;
            invalidate();
        }

        private PopupSwipeBackLayout swipeBackLayout;
        @Nullable
        private ScrollView scrollView;
        protected LinearLayout linearLayout;

        private int backgroundColor = Color.WHITE;
        protected Drawable backgroundDrawable;
        private Drawable regularBackgroundDrawable;
        private boolean materialPanelStyle;
        private final Drawable materialTransparentBackground = new ColorDrawable(Color.TRANSPARENT);
        private final IdentityHashMap<View, Drawable> materialRowBackgrounds = new IdentityHashMap<>();

        private boolean fitItems;
        private final Theme.ResourcesProvider resourcesProvider;
        private View topView;
        protected ActionBarPopupWindow window;

        public int subtractBackgroundHeight;
        Rect rect;

        public Rect getPadding() {
            return bgPaddings;
        }

        public ActionBarPopupWindowLayout(Context context) {
            this(context, null);
        }

        public ActionBarPopupWindowLayout(Context context, Theme.ResourcesProvider resourcesProvider) {
            this(context, R.drawable.popup_fixed_alert2, resourcesProvider);
        }

        public ActionBarPopupWindowLayout(Context context, int resId, Theme.ResourcesProvider resourcesProvider) {
            this(context, resId, resourcesProvider, 0);
        }

        public ActionBarPopupWindowLayout(Context context, int resId, Theme.ResourcesProvider resourcesProvider, int flags) {
            super(context);
            this.resourcesProvider = resourcesProvider;

            if (resId != 0) {
                backgroundDrawable = getResources().getDrawable(resId).mutate();
                setPadding(dp(8), dp(8), dp(8), dp(8));
            }
            if (backgroundDrawable != null) {
                backgroundDrawable.getPadding(bgPaddings);
                setBackgroundColor(getThemedColor(Theme.key_actionBarDefaultSubmenuBackground));
            }


            regularBackgroundDrawable = backgroundDrawable;
            refreshPanelStyle();

            setWillNotDraw(false);

            if ((flags & FLAG_SHOWN_FROM_BOTTOM) > 0) {
                shownFromBottom = true;
            }

            if ((flags & FLAG_USE_SWIPEBACK) > 0) {
                swipeBackLayout = new PopupSwipeBackLayout(context, resourcesProvider);
                addView(swipeBackLayout, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            }

            if ((flags & FLAG_DONT_USE_SCROLLVIEW) == 0) {
                try {
                    scrollView = new ScrollView(context);
                    scrollView.getViewTreeObserver().addOnScrollChangedListener(new ViewTreeObserver.OnScrollChangedListener() {
                        @Override
                        public void onScrollChanged() {
                            invalidate();
                        }
                    });
                    scrollView.setVerticalScrollBarEnabled(false);
                    if (swipeBackLayout != null) {
                        swipeBackLayout.addView(scrollView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, shownFromBottom ? Gravity.BOTTOM : Gravity.TOP));
                    } else {
                        addView(scrollView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
                    }
                } catch (Throwable e) {
                    FileLog.e(e);
                }
            }

            linearLayout = new LinearLayout(context) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    if (fitItems) {
                        int maxWidth = 0;
                        int fixWidth = 0;
                        gapStartY = -1000000;
                        gapEndY = -1000000;
                        ArrayList<View> viewsToFix = null;
                        for (int a = 0, N = getChildCount(); a < N; a++) {
                            View view = getChildAt(a);
                            if (view.getVisibility() == GONE) {
                                continue;
                            }
                            Object tag = view.getTag(R.id.width_tag);
                            Object tag2 = view.getTag(R.id.object_tag);
                            Object fitToWidth = view.getTag(R.id.fit_width_tag);
                            if (tag != null) {
                                view.getLayoutParams().width = LayoutHelper.WRAP_CONTENT;
                            }
                            measureChildWithMargins(view, widthMeasureSpec, 0, heightMeasureSpec, 0);
                            if (fitToWidth != null) {

                            } else if (!(tag instanceof Integer) && tag2 == null) {
                                maxWidth = Math.max(maxWidth, view.getMeasuredWidth());
                                continue;
                            } else if (tag instanceof Integer) {
                                fixWidth = Math.max((Integer) tag, view.getMeasuredWidth());
                                gapStartY = view.getMeasuredHeight();
                                gapEndY = gapStartY + dp(6);
                            }
                            if (viewsToFix == null) {
                                viewsToFix = new ArrayList<>();
                            }
                            viewsToFix.add(view);
                        }
                        if (viewsToFix != null) {
                            for (int a = 0, N = viewsToFix.size(); a < N; a++) {
                                viewsToFix.get(a).getLayoutParams().width = Math.max(maxWidth, fixWidth);
                            }
                        }
                    }
                    super.onMeasure(widthMeasureSpec, heightMeasureSpec);
                }

                @Override
                protected boolean drawChild(Canvas canvas, View child, long drawingTime) {
                    if (child instanceof GapView && backgroundDrawable != null) {
                        return false;
                    }
                    return super.drawChild(canvas, child, drawingTime);
                }
            };
            linearLayout.setOrientation(LinearLayout.VERTICAL);
            if (scrollView != null) {
                scrollView.addView(linearLayout, new ScrollView.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            } else if (swipeBackLayout != null) {
                swipeBackLayout.addView(linearLayout, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, shownFromBottom ? Gravity.BOTTOM : Gravity.TOP));
            } else {
                addView(linearLayout, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT));
            }
        }

        @Nullable
        public PopupSwipeBackLayout getSwipeBack() {
            return swipeBackLayout;
        }

        public int addViewToSwipeBack(View v) {
            swipeBackLayout.addView(v, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT, shownFromBottom ? Gravity.BOTTOM : Gravity.TOP));
            return swipeBackLayout.getChildCount() - 1;
        }

        public void setFitItems(boolean value) {
            fitItems = value;
        }

        public void setShownFromBottom(boolean value) {
            shownFromBottom = value;
        }

        public void setDispatchKeyEventListener(OnDispatchKeyEventListener listener) {
            mOnDispatchKeyEventListener = listener;
        }

        public int getBackgroundColor() {
            return backgroundColor;
        }

        private void refreshPanelStyle() {
            boolean material = zxc.iconic.xenon.NekoConfig.material3Popups;
            if (material) {
                materialPanelStyle = true;
                backgroundDrawable = materialTransparentBackground;
                // Keep menu placement and touch padding, without a panel behind the rows.
                if (regularBackgroundDrawable != null) bgPaddings.set(dp(8), dp(8), dp(8), dp(8));
            } else if (materialPanelStyle) {
                materialPanelStyle = false;
                backgroundDrawable = regularBackgroundDrawable;
                if (backgroundDrawable != null) {
                    backgroundDrawable.setColorFilter(new PorterDuffColorFilter(backgroundColor, PorterDuff.Mode.MULTIPLY));
                    backgroundDrawable.getPadding(bgPaddings);
                }
            }
        }

        public void setBackgroundColor(int color) {
            if (zxc.iconic.xenon.NekoConfig.material3Popups) {
                backgroundColor = color;
                return;
            }
            if (backgroundColor != color && backgroundDrawable != null) {
                backgroundDrawable.setColorFilter(new PorterDuffColorFilter(backgroundColor = color, PorterDuff.Mode.MULTIPLY));
            }
        }

        @Keep
        public void setBackAlpha(int value) {
            if (backAlpha != value) {
                invalidate();
            }
            backAlpha = value;
        }

        @Keep
        public int getBackAlpha() {
            return backAlpha;
        }

        @Keep
        public void setBackScaleX(float value) {
            if (backScaleX != value) {
                backScaleX = value;
                invalidate();
                if (onSizeChangedListener != null) {
                    onSizeChangedListener.onSizeChanged();
                }
            }
        }

        @Keep
        public void setBackScaleY(float value) {
            if (backScaleY != value) {
                backScaleY = value;
                if (animationEnabled && updateAnimation) {
                    int height = getMeasuredHeight() - dp(16);
                    if (shownFromBottom) {
                        for (int a = lastStartedChild; a >= 0; a--) {
                            View child = getItemAt(a);
                            if (child == null || child.getVisibility() != VISIBLE || child instanceof GapView) {
                                continue;
                            }
                            Integer position = positions.get(child);
                            if (position != null && height - (position * dp(48) + dp(32)) > value * height) {
                                break;
                            }
                            lastStartedChild = a - 1;
                            startChildAnimation(child);
                        }
                    } else {
                        int count = getItemsCount();
                        int h = 0;
                        for (int a = 0; a < count; a++) {
                            View child = getItemAt(a);
                            if (child.getVisibility() != VISIBLE) {
                                continue;
                            }
                            h += child.getMeasuredHeight();
                            if (a < lastStartedChild) {
                                continue;
                            }
                            Integer position = positions.get(child);
                            if (position != null && h - dp(24) > value * height) {
                                break;
                            }
                            lastStartedChild = a + 1;
                            startChildAnimation(child);
                        }
                    }
                }
                invalidate();
                if (onSizeChangedListener != null) {
                    onSizeChangedListener.onSizeChanged();
                }
            }
        }

        @Override
        public void setBackgroundDrawable(Drawable drawable) {
            backgroundColor = Color.WHITE;
            regularBackgroundDrawable = drawable;
            backgroundDrawable = drawable;
            if (backgroundDrawable != null) backgroundDrawable.getPadding(bgPaddings);
            refreshPanelStyle();
        }

        private void startChildAnimation(View child) {
            if (animationEnabled) {
                AnimatorSet animatorSet = new AnimatorSet();
                animatorSet.playTogether(
                        ObjectAnimator.ofFloat(child, View.ALPHA, 0f, child.isEnabled() ? 1f : 0.5f),
                        ObjectAnimator.ofFloat(child, View.TRANSLATION_Y, dp(shownFromBottom ? 6 : -6), 0));
                animatorSet.setDuration(180);
                animatorSet.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        itemAnimators.remove(animatorSet);

                        if (child instanceof ActionBarMenuSubItem) {
                            ((ActionBarMenuSubItem) child).onItemShown();
                        }
                    }
                });
                animatorSet.setInterpolator(decelerateInterpolator);
                animatorSet.start();
                if (itemAnimators == null) {
                    itemAnimators = new ArrayList<>();
                }
                itemAnimators.add(animatorSet);
            }
        }

        public void setAnimationEnabled(boolean value) {
            animationEnabled = value;
        }

        @Override
        public void addView(View child) {
            linearLayout.addView(child);
        }

        public void addView(View child, LinearLayout.LayoutParams layoutParams) {
            linearLayout.addView(child, layoutParams);
        }

        public int getViewsCount() {
            return linearLayout.getChildCount();
        }

        public int precalculateHeight() {
            int MOST_SPEC = View.MeasureSpec.makeMeasureSpec(dp(1000), View.MeasureSpec.AT_MOST);
            linearLayout.measure(MOST_SPEC, MOST_SPEC);
            return linearLayout.getMeasuredHeight();
        }

        public void removeInnerViews() {
            linearLayout.removeAllViews();
        }

        public float getBackScaleX() {
            return backScaleX;
        }

        public float getBackScaleY() {
            return backScaleY;
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            if (mOnDispatchKeyEventListener != null) {
                mOnDispatchKeyEventListener.onDispatchKeyEvent(event);
            }
            return super.dispatchKeyEvent(event);
        }

        Path path;

        @Override
        protected void dispatchDraw(Canvas canvas) {
            if (swipeBackGravityRight) {
                setTranslationX(getMeasuredWidth() * (1f - backScaleX));
                if (topView != null) {
                    topView.setTranslationX(getMeasuredWidth() * (1f - backScaleX));
                    topView.setAlpha(1f - swipeBackLayout.transitionProgress);
                    float h = topView.getMeasuredHeight() - dp(16);
                    float yOffset = -h * swipeBackLayout.transitionProgress;
                    topView.setTranslationY(yOffset);
                    setTranslationY(yOffset);
                }
            }
            if (swipeBackGravityBottom) {
                setTranslationY(getMeasuredHeight() * (1f - backScaleY));
            }
            if (backgroundDrawable != null && !iosSurfaceContent) {
                int start = gapStartY - (scrollView == null ? 0 : scrollView.getScrollY());
                int end = gapEndY - (scrollView == null ? 0 : scrollView.getScrollY());
                boolean hasGap = false;
                for (int i = 0; i < linearLayout.getChildCount(); i++) {
                    if (linearLayout.getChildAt(i) instanceof GapView && linearLayout.getChildAt(i).getVisibility() == View.VISIBLE) {
                        hasGap = true;
                        break;
                    }
                }
                for (int a = 0; a < 2; a++) {
                    if (a == 1 && start < -dp(16)) {
                        break;
                    }
                    int saveCount = canvas.getSaveCount();
                    boolean applyAlpha = true;
                    if (hasGap && backAlpha != 255) {
                        canvas.saveLayerAlpha(0, bgPaddings.top, getMeasuredWidth(), getMeasuredHeight(), backAlpha, Canvas.ALL_SAVE_FLAG);
                        applyAlpha = false;
                    }  else if (gapStartY != -1000000) {
                        canvas.save();
                        canvas.clipRect(0, bgPaddings.top, getMeasuredWidth(), getMeasuredHeight());
                    }
                    backgroundDrawable.setAlpha(applyAlpha ? backAlpha : 255);
                    if (shownFromBottom) {
                        final int height = getMeasuredHeight();
                        AndroidUtilities.rectTmp2.set(0, (int) (height * (1.0f - backScaleY)), (int) (getMeasuredWidth() * backScaleX), height);
                    } else {
                        if (start > -dp(16)) {
                            int h = (int) (getMeasuredHeight() * backScaleY);
                            if (a == 0) {
                                if (swipeBackLayout != null && swipeBackLayout.stickToRight) {
                                    AndroidUtilities.rectTmp2.set(getMeasuredWidth() - (int) (getMeasuredWidth() * backScaleX), (scrollView == null ? 0 : -scrollView.getScrollY()) + (gapStartY != -1000000 ? dp(1) : 0), getMeasuredWidth(), (gapStartY != -1000000 ? Math.min(h, start + dp(16)) : h) - subtractBackgroundHeight);
                                } else {
                                    AndroidUtilities.rectTmp2.set(0, (scrollView == null ? 0 : -scrollView.getScrollY()) + (gapStartY != -1000000 ? dp(1) : 0), (int) (getMeasuredWidth() * backScaleX), (gapStartY != -1000000 ? Math.min(h, start + dp(16)) : h) - subtractBackgroundHeight);
                                }
                            } else {
                                if (h < end) {
                                    if (gapStartY != -1000000) {
                                        canvas.restore();
                                    }
                                    continue;
                                }
                                if (swipeBackLayout != null && swipeBackLayout.stickToRight) {
                                    AndroidUtilities.rectTmp2.set(getMeasuredWidth() - (int) (getMeasuredWidth() * backScaleX), end, getMeasuredWidth(), h - subtractBackgroundHeight);
                                } else {
                                    AndroidUtilities.rectTmp2.set(0, end, (int) (getMeasuredWidth() * backScaleX), h - subtractBackgroundHeight);
                                }
                            }
                        } else {
                            if (swipeBackLayout != null && swipeBackLayout.stickToRight) {
                                AndroidUtilities.rectTmp2.set(getMeasuredWidth() - (int) (getMeasuredWidth() * backScaleX), (gapStartY < 0 ? 0 : -dp(16)), getMeasuredWidth(), (int) (getMeasuredHeight() * backScaleY) - subtractBackgroundHeight);
                            } else {
                                AndroidUtilities.rectTmp2.set(0, (gapStartY < 0 ? 0 : -dp(16)), (int) (getMeasuredWidth() * backScaleX), (int) (getMeasuredHeight() * backScaleY) - subtractBackgroundHeight);
                            }
                        }
                    }
                    if (reactionsEnterProgress != 1f) {
                        if (rect == null) {
                            rect = new Rect();
                        }
                        rect.set(AndroidUtilities.rectTmp2.right, AndroidUtilities.rectTmp2.top, AndroidUtilities.rectTmp2.right, AndroidUtilities.rectTmp2.top);
                        AndroidUtilities.lerp(rect, AndroidUtilities.rectTmp2, reactionsEnterProgress, AndroidUtilities.rectTmp2);
                    }
                    backgroundDrawable.setBounds(AndroidUtilities.rectTmp2);
                    backgroundDrawable.draw(canvas);
                    if (clipChildren) {
                        AndroidUtilities.rectTmp2.left += bgPaddings.left;
                        AndroidUtilities.rectTmp2.top += bgPaddings.top;
                        AndroidUtilities.rectTmp2.right -= bgPaddings.right;
                        AndroidUtilities.rectTmp2.bottom -= bgPaddings.bottom;
                        canvas.clipRect(AndroidUtilities.rectTmp2);
                    }
                    if (hasGap) {
                        canvas.save();
                        AndroidUtilities.rectTmp.set(backgroundDrawable.getBounds());
                        AndroidUtilities.rectTmp.inset(dp(8), dp(8));
                        if (path == null) {
                            path = new Path();
                        } else {
                            path.rewind();
                        }
                        path.addRoundRect(AndroidUtilities.rectTmp, dp(12), dp(12), Path.Direction.CW);
                        canvas.clipPath(path);
                        for (int i = 0; i < linearLayout.getChildCount(); i++) {
                            if (linearLayout.getChildAt(i) instanceof GapView && linearLayout.getChildAt(i).getVisibility() == View.VISIBLE) {
                                canvas.save();
                                float x = 0, y = 0;
                                GapView child = (GapView) linearLayout.getChildAt(i);
                                View view = child;
                                while (view != this) {
                                    x += view.getX();
                                    y += view.getY();
                                    view = (View) view.getParent();
                                    if (view == null) {
                                        break;
                                    }
                                }
                                canvas.translate(x, y * (scrollView == null ? 1f : scrollView.getScaleY()) - (scrollView == null ? 0 : scrollView.getScrollY()));
                                child.draw(canvas);
                                canvas.restore();
                            }
                        }
                        canvas.restore();
                    }
                    canvas.restoreToCount(saveCount);
                }
            }
            if (reactionsEnterProgress != 1f && !iosSurfaceContent) {
                canvas.saveLayerAlpha((float) AndroidUtilities.rectTmp2.left, (float) AndroidUtilities.rectTmp2.top, AndroidUtilities.rectTmp2.right, AndroidUtilities.rectTmp2.bottom, (int) (255 * reactionsEnterProgress), Canvas.ALL_SAVE_FLAG);
                float scale = 0.5f + reactionsEnterProgress * 0.5f;
                canvas.scale(scale, scale, AndroidUtilities.rectTmp2.right, AndroidUtilities.rectTmp2.top);
                super.dispatchDraw(canvas);
                canvas.restore();
            } else {
                super.dispatchDraw(canvas);
            }
        }

        public Drawable getBackgroundDrawable() {
            return backgroundDrawable;
        }

        public int getItemsCount() {
            return linearLayout.getChildCount();
        }

        public View getItemAt(int index) {
            return linearLayout.getChildAt(index);
        }

        public void scrollToTop() {
            if (scrollView != null) {
                scrollView.scrollTo(0, 0);
            }
        }

        public void setupRadialSelectors(int color) {
            int count = linearLayout.getChildCount();
            for (int a = 0; a < count; a++) {
                View child = linearLayout.getChildAt(a);
                child.setBackground(Theme.createRadSelectorDrawable(color, a == 0 ? 6 : 0, a == count - 1 ? 6 : 0));
            }
        }

        private static boolean isPopupRow(View view) {
            return view != null && !(view instanceof GapView)
                    && !(view instanceof androidx.recyclerview.widget.RecyclerView)
                    && (view.getClass() != View.class || view.hasOnClickListeners());
        }

        public void updateRadialSelectors() {
            int count = linearLayout.getChildCount();
            View previous = null;
            boolean material = zxc.iconic.xenon.NekoConfig.material3Popups;
            for (int i = 0; i < count; i++) {
                View child = linearLayout.getChildAt(i);
                if (child.getVisibility() != View.VISIBLE) continue;
                View next = null;
                for (int j = i + 1; j < count; j++) {
                    if (linearLayout.getChildAt(j).getVisibility() == View.VISIBLE) {
                        next = linearLayout.getChildAt(j);
                        break;
                    }
                }
                boolean top = !isPopupRow(previous), bottom = !isPopupRow(next);
                if (child instanceof ActionBarMenuSubItem) {
                    ((ActionBarMenuSubItem) child).refreshPopupStyle();
                    ((ActionBarMenuSubItem) child).updateSelectorBackground(top, bottom);
                } else if (material && isPopupRow(child)) {
                    Drawable background = child.getBackground();
                    if (!materialRowBackgrounds.containsKey(child)) materialRowBackgrounds.put(child, background);
                    if (background instanceof org.telegram.ui.Components.MaterialPopupRowDrawable) {
                        ((org.telegram.ui.Components.MaterialPopupRowDrawable) background).setCorners(top, bottom);
                    } else {
                        child.setBackground(new org.telegram.ui.Components.MaterialPopupRowDrawable(
                                getThemedColor(Theme.key_windowBackgroundWhite), top, bottom));
                    }
                } else if (!material && materialRowBackgrounds.containsKey(child)) {
                    child.setBackground(materialRowBackgrounds.remove(child));
                }
                previous = child;
            }
        }

        protected int getThemedColor(int key) {
            return Theme.getColor(key, resourcesProvider);
        }

        public void setOnSizeChangedListener(ActionBarPopupWindow.onSizeChangedListener onSizeChangedListener) {
            this.onSizeChangedListener = onSizeChangedListener;
        }

        public int getVisibleHeight() {
            return (int) (getMeasuredHeight() * backScaleY);
        }

        public void setTopView(View topView) {
            this.topView = topView;
        }

        public void setSwipeBackForegroundColor(int color) {
            getSwipeBack().setForegroundColor(color);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            refreshPanelStyle();
            updateRadialSelectors();
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            if (swipeBackLayout != null) {
                swipeBackLayout.invalidateTransforms(!startAnimationPending);
            }
        }

        public void setParentWindow(ActionBarPopupWindow popupWindow) {
            window = popupWindow;
        }

        public void setReactionsTransitionProgress(float transitionEnterProgress) {
            this.reactionsEnterProgress = transitionEnterProgress;
            invalidate();
        }
    }

    public ActionBarPopupWindow() {
        super();
        init();
    }

    public ActionBarPopupWindow(Context context) {
        super(context);
        init();
    }

    public ActionBarPopupWindow(int width, int height) {
        super(width, height);
        init();
    }

    public ActionBarPopupWindow(View contentView) {
        super(contentView);
        init();
    }

    public ActionBarPopupWindow(View contentView, int width, int height, boolean focusable) {
        super(contentView, width, height, focusable);
        init();
    }

    public ActionBarPopupWindow(View contentView, int width, int height) {
        super(contentView, width, height);
        init();
    }

    public void setAnimationEnabled(boolean value) {
        animationEnabled = value;
    }

    @SuppressWarnings("PrivateAPI")
    public void setLayoutInScreen(boolean value) {
        try {
            if (layoutInScreenMethod == null) {
                layoutInScreenMethod = PopupWindow.class.getDeclaredMethod("setLayoutInScreenEnabled", boolean.class);
                layoutInScreenMethod.setAccessible(true);
            }
            layoutInScreenMethod.invoke(this, true);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    private void init() {
        View contentView = getContentView();
        if (contentView instanceof ActionBarPopupWindowLayout && ((ActionBarPopupWindowLayout) contentView).getSwipeBack() != null) {
            setTouchInterceptor((v, e) -> {
                if (iosSurface != null) return false;
                if (e.getAction() == MotionEvent.ACTION_DOWN) {
                    Drawable backgroundDrawable = ((ActionBarPopupWindowLayout) contentView).getBackgroundDrawable();
                    AndroidUtilities.rectTmp.set(backgroundDrawable.getBounds());
                    AndroidUtilities.rectTmp.offset(contentView.getX(), contentView.getY());
                    if (!AndroidUtilities.rectTmp.contains(e.getX(), e.getY())) {
                        dismiss();
                        return true;
                    }
                }
                return false;
            });
        }
        if (superListenerField != null) {
            try {
                mSuperScrollListener = (ViewTreeObserver.OnScrollChangedListener) superListenerField.get(this);
                superListenerField.set(this, NOP);
            } catch (Exception e) {
                mSuperScrollListener = null;
            }
        }
    }

    public void setDismissAnimationDuration(int value) {
        dismissAnimationDuration = value;
    }

    private void unregisterListener() {
        if (mSuperScrollListener != null && mViewTreeObserver != null) {
            if (mViewTreeObserver.isAlive()) {
                mViewTreeObserver.removeOnScrollChangedListener(mSuperScrollListener);
            }
            mViewTreeObserver = null;
        }
    }

    private void registerListener(View anchor) {
        if (mSuperScrollListener != null) {
            ViewTreeObserver vto = (anchor.getWindowToken() != null) ? anchor.getViewTreeObserver() : null;
            if (vto != mViewTreeObserver) {
                if (mViewTreeObserver != null && mViewTreeObserver.isAlive()) {
                    mViewTreeObserver.removeOnScrollChangedListener(mSuperScrollListener);
                }
                if ((mViewTreeObserver = vto) != null) {
                    vto.addOnScrollChangedListener(mSuperScrollListener);
                }
            }
        }
    }

    public void dimBehind() {
        dimBehind(0.2f);
    }

    public void dimBehind(float amount) {
        View container = getContentView().getRootView();
        Context context = getContentView().getContext();
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        WindowManager.LayoutParams p = (WindowManager.LayoutParams) container.getLayoutParams();
        p.flags |= WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        p.dimAmount = amount;
        wm.updateViewLayout(container, p);
    }

    public void setFocusableFlag(boolean enable) {
        View container = getContentView().getRootView();
        Context context = getContentView().getContext();
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        WindowManager.LayoutParams p = (WindowManager.LayoutParams) container.getLayoutParams();

        if (p != null) {
            if (enable) {
                p.flags |= WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
            } else {
                p.flags &= ~WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM;
            }
            wm.updateViewLayout(container, p);
        }
    }

    private void dismissDim() {
        View container = getContentView().getRootView();
        Context context = getContentView().getContext();
        WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);

        if (container.getLayoutParams() == null || !(container.getLayoutParams() instanceof WindowManager.LayoutParams)) {
            return;
        }
        WindowManager.LayoutParams p = (WindowManager.LayoutParams) container.getLayoutParams();
        try {
            if ((p.flags & WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0) {
                p.flags &= ~WindowManager.LayoutParams.FLAG_DIM_BEHIND;
                p.dimAmount = 0.0f;
                wm.updateViewLayout(container, p);
            }
        } catch (Exception e) {

        }
    }

    @Override
    public void showAsDropDown(View anchor, int xoff, int yoff) {
        try {
            View source = morphSource(anchor);
            if (useIosMorph(source)) {
                measureIosContent(source);
                int[] position = iosDropdownPosition(anchor, xoff, yoff);
                if (showIosSurface(anchor, source, position[0], position[1])) {
                    registerListener(anchor);
                    return;
                }
            }
            prepareNativeSurface(source);
            super.showAsDropDown(anchor, xoff, yoff);
            registerListener(anchor);
        } catch (Exception e) {
            FileLog.e(e);
        }
    }

    @Override public void showAsDropDown(View anchor, int xoff, int yoff, int gravity) {
        View source = morphSource(anchor);
        if (useIosMorph(source)) {
            showAsDropDown(anchor, xoff, yoff);
            return;
        }
        prepareNativeSurface(source);
        super.showAsDropDown(anchor, xoff, yoff, gravity);
        registerListener(anchor);
    }

    /** Match the menu opened by the three-dot button in DialogsActivity. */
    public static AnimatorSet startAnimation(ActionBarPopupWindowLayout content) {
        boolean nativeWindowAnimation = false;
        for (android.view.ViewParent parent = content.getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof PopupMenuSurface) { nativeWindowAnimation = true; break; }
        }
        if (content.iosSurfaceContent || nativeWindowAnimation) {
            AnimatorSet animation = new AnimatorSet();
            animation.start();
            return animation;
        }
        return startMenuAnimation(content);
    }

    public static AnimatorSet startMenuAnimation(View content) {
        resetMenuAnimation(content);
        content.setPivotX(content.getMeasuredWidth());
        content.setPivotY(content.getMeasuredHeight());
        ObjectAnimator scaleX = ObjectAnimator.ofFloat(content, View.SCALE_X, .95f, 1f);
        ObjectAnimator scaleY = ObjectAnimator.ofFloat(content, View.SCALE_Y, .95f, 1f);
        scaleX.setInterpolator(new android.view.animation.OvershootInterpolator());
        scaleY.setInterpolator(new android.view.animation.OvershootInterpolator());
        scaleX.setDuration(200);
        scaleY.setDuration(200);
        ObjectAnimator alpha = ObjectAnimator.ofFloat(content, View.ALPHA, 0f, 1f);
        alpha.setDuration(150);
        AnimatorSet animation = new AnimatorSet();
        animation.playTogether(scaleX, scaleY, alpha);
        animation.start();
        return animation;
    }

    public void startAnimation() {
        // The native window runs PopupContextAnimation once, for all menu styles.
        // Keep the old call sites, without layering the former height reveal over it.
        if (iosSurface == null) resetMenuAnimation(getContentView());
    }

    @Override
    public void update(View anchor, int xoff, int yoff, int width, int height) {
        if (iosSurface != null) {
            int[] position = iosDropdownPosition(anchor, xoff, yoff);
            iosSurface.updateTarget(position[0], position[1], width, height);
            return;
        }
        super.update(anchor, xoff, yoff, width, height);
        registerListener(anchor);
    }

    @Override
    public void update(View anchor, int width, int height) {
        if (iosSurface != null) {
            iosSurface.updateTarget(iosContent.getLeft() + iosSurfaceScreenX(), iosContent.getTop() + iosSurfaceScreenY(), width, height);
            return;
        }
        super.update(anchor, width, height);
        registerListener(anchor);
    }

    @Override
    public void showAtLocation(View parent, int gravity, int x, int y) {
        View source = morphSource(parent);
        if (useIosMorph(source)) {
            measureIosContent(source);
            int[] location = new int[2];
            View root = parent.getRootView();
            root.getLocationOnScreen(location);
            int horizontal = Gravity.getAbsoluteGravity(gravity, parent.getLayoutDirection()) & Gravity.HORIZONTAL_GRAVITY_MASK;
            int vertical = gravity & Gravity.VERTICAL_GRAVITY_MASK;
            int left = horizontal == Gravity.RIGHT ? root.getWidth() - x - getContentView().getMeasuredWidth()
                    : horizontal == Gravity.CENTER_HORIZONTAL ? (root.getWidth() - getContentView().getMeasuredWidth()) / 2 + x : x;
            int top = vertical == Gravity.BOTTOM ? root.getHeight() - y - getContentView().getMeasuredHeight()
                    : vertical == Gravity.CENTER_VERTICAL ? (root.getHeight() - getContentView().getMeasuredHeight()) / 2 + y : y;
            if (showIosSurface(parent, source, location[0] + left, location[1] + top)) {
                unregisterListener();
                return;
            }
        }
        prepareNativeSurface(source);
        super.showAtLocation(parent, gravity, x, y);
        unregisterListener();
    }

    private int[] iosDropdownPosition(View anchor, int xoff, int yoff) {
        int[] point = new int[2];
        anchor.getLocationOnScreen(point);
        int width = getContentView().getMeasuredWidth(), height = getContentView().getMeasuredHeight();
        int x = point[0] + xoff, y = point[1] + (getOverlapAnchor() ? 0 : anchor.getHeight()) + yoff;
        Rect visible = new Rect();
        anchor.getWindowVisibleDisplayFrame(visible);
        x = Math.max(visible.left, Math.min(x, visible.right - width));
        if (y + height > visible.bottom && point[1] - height + yoff >= visible.top) y = point[1] - height + yoff;
        y = Math.max(visible.top, Math.min(y, visible.bottom - height));
        return new int[] {x, y};
    }

    private int iosSurfaceScreenX() {
        int[] p = new int[2];
        iosSurface.getLocationOnScreen(p);
        return p[0];
    }

    private int iosSurfaceScreenY() {
        int[] p = new int[2];
        iosSurface.getLocationOnScreen(p);
        return p[1];
    }

    @Override public void update(int x, int y, int width, int height) {
        if (iosSurface != null) {
            View source = animationSource == null ? null : animationSource.get();
            int[] point = new int[2];
            if (source != null) source.getRootView().getLocationOnScreen(point);
            iosSurface.updateTarget(point[0] + x, point[1] + y, width, height);
            return;
        }
        super.update(x, y, width, height);
    }

    @Override
    public void dismiss() {
        dismiss(true);
    }

    public void setPauseNotifications(boolean value) {
        pauseNotifications = value;
    }

    public void dismiss(boolean animated) {
        if (iosSurface != null) {
            dismissIosSurface(animated);
            return;
        }
        setFocusable(false);
        dismissDim();
        if (windowAnimatorSet != null) {
            if (animated && isClosingAnimated) {
                return;
            }
            windowAnimatorSet.cancel();
            windowAnimatorSet = null;
        }
        isClosingAnimated = false;
        if (animationEnabled && animated) {
            isClosingAnimated = true;
            ViewGroup viewGroup = (ViewGroup) getContentView();
            ActionBarPopupWindowLayout content = null;
            for (int i = 0; i < viewGroup.getChildCount(); i++) {
                if (viewGroup.getChildAt(i) instanceof ActionBarPopupWindowLayout) {
                    content = (ActionBarPopupWindowLayout) viewGroup.getChildAt(i);
                }
            }
            if (content != null) {
                if (content.itemAnimators != null && !content.itemAnimators.isEmpty()) {
                    for (int a = 0, N = content.itemAnimators.size(); a < N; a++) {
                        AnimatorSet animatorSet = content.itemAnimators.get(a);
                        animatorSet.removeAllListeners();
                        animatorSet.cancel();
                    }
                    content.itemAnimators.clear();
                }
            }
            windowAnimatorSet = new AnimatorSet();
            if (outEmptyTime > 0) {
                windowAnimatorSet.playTogether(ValueAnimator.ofFloat(0, 1f));
                windowAnimatorSet.setDuration(outEmptyTime);
            } else if (scaleOut) {
                windowAnimatorSet.playTogether(
                        ObjectAnimator.ofFloat(viewGroup, View.SCALE_Y, 0.8f),
                        ObjectAnimator.ofFloat(viewGroup, View.SCALE_X, 0.8f),
                        ObjectAnimator.ofFloat(viewGroup, View.ALPHA, 0.0f));
                windowAnimatorSet.setDuration(dismissAnimationDuration);
            } else {
                windowAnimatorSet.playTogether(
                        ObjectAnimator.ofFloat(viewGroup, View.TRANSLATION_Y, dp((content != null && content.shownFromBottom) ? 5 : -5)),
                        ObjectAnimator.ofFloat(viewGroup, View.ALPHA, 0.0f));
                windowAnimatorSet.setDuration(dismissAnimationDuration);
            }

            windowAnimatorSet.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    windowAnimatorSet = null;
                    isClosingAnimated = false;
                    setFocusable(false);
                    try {
                        ActionBarPopupWindow.super.dismiss();
                        restoreNativeContent();
                    } catch (Exception ignore) {

                    }
                    unregisterListener();
                    if (pauseNotifications) {
                        notificationsLocker.unlock();
                    }
                }
            });
            if (pauseNotifications) {
                notificationsLocker.lock();
            }
            windowAnimatorSet.start();
        } else {
            try {
                super.dismiss();
                restoreNativeContent();
            } catch (Exception ignore) {

            }
            unregisterListener();
        }
    }

    public void setEmptyOutAnimation(long time) {
        outEmptyTime = time;
    }

    public interface onSizeChangedListener {
        void onSizeChanged();
    }

    public static class GapView extends FrameLayout {

        Drawable shadowDrawable;
        private boolean iosSurfaceContent;

        public void setIosSurfaceContent(boolean enabled) {
            iosSurfaceContent = enabled;
            invalidate();
        }

        public GapView(Context context, Theme.ResourcesProvider resourcesProvider) {
            this(context, resourcesProvider, Theme.key_actionBarDefaultSubmenuSeparator);
        }

        public GapView(Context context, int color, int shadowColor) {
            super(context);
            this.shadowDrawable = Theme.getThemedDrawable(getContext(), R.drawable.greydivider, shadowColor);
            setBackgroundColor(color);
        }

        public GapView(Context context, Theme.ResourcesProvider resourcesProvider, int colorKey) {
            this(context, Theme.getColor(colorKey, resourcesProvider), Theme.getColor(Theme.key_windowBackgroundGrayShadow, resourcesProvider));
        }

        @Override public void setBackgroundColor(int color) {
            super.setBackgroundColor(zxc.iconic.xenon.NekoConfig.material3Popups ? Color.TRANSPARENT : color);
        }

        public void setColor(int color) {
            setBackgroundColor(color);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (shadowDrawable != null && !iosSurfaceContent && !zxc.iconic.xenon.NekoConfig.material3Popups) {
                shadowDrawable.setBounds(0, 0, getWidth(), getHeight());
                shadowDrawable.draw(canvas);
            }
        }
    }
}
