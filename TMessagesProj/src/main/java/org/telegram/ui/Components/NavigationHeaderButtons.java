package org.telegram.ui.Components;

import android.animation.ValueAnimator;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.os.Build;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewParent;
import androidx.annotation.RequiresApi;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LiteMode;
import org.telegram.messenger.SharedConfig;
import org.telegram.ui.ActionBar.ActionBar;
import org.telegram.ui.ActionBar.ActionBarLayout;
import org.telegram.ui.ActionBar.ActionBarMenu;
import org.telegram.ui.ActionBar.ActionBarMenuItem;
import org.telegram.ui.ActionBar.BaseFragment;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.MainTabsActivity;
import org.telegram.ui.Components.blur3.BlurredBackgroundDrawableViewFactory;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.LiquidGlassInputGroup;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;
import zxc.iconic.xenon.NekoConfig;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** View/RenderNode adapter of Glasmio's retained HeaderGroup presentation.
 * Motion: GlasmioNavigationScaffold.kt + HeaderMotion.kt, Apache-2.0, 1a065250.
 * Pages supply live actions. Chat headers also supply their laid-out geometry and title.
 */
@RequiresApi(31)
public final class NavigationHeaderButtons {
    private final ActionBarLayout host;
    private final RenderNode content = new RenderNode("NavigationWithoutHeaderButtons");
    private final BlurredBackgroundSourceRenderNode source = new BlurredBackgroundSourceRenderNode(null);
    private final BlurredBackgroundDrawableViewFactory factory = new BlurredBackgroundDrawableViewFactory(source);
    private final ArrayList<ActionBar> managed = new ArrayList<>();
    private final ArrayList<BaseFragment> pages = new ArrayList<>();
    private final ArrayList<View> leadingActions = new ArrayList<>();
    private final ArrayList<View> trailingActions = new ArrayList<>();
    private static final float FOREGROUND_BLUR_STEP_DP = .5f;
    private final RenderEffect[] foregroundBlurs = new RenderEffect[21];
    private final Group[] groups = new Group[2];
    private final BlurredBackgroundDrawable[] members = new BlurredBackgroundDrawable[2];
    private final RectF[] bounds = {new RectF(), new RectF()};
    private final float[] alpha = new float[2];
    private final float[] surfaceAlpha = new float[2];
    private final ArrayList<ChatTitle> chatTitles = new ArrayList<>();
    private final LiquidTouchEffect[] effects = new LiquidTouchEffect[2];
    private final Paint plain = new Paint(Paint.ANTI_ALIAS_FLAG);
    private LiquidGlassInputGroup union;
    private ActionBar currentBar;
    private Icon touched;
    private Group touchedGroup;
    private long lastFrame;
    private float frameDelta;
    private ChatTitle activeChatTitle;
    private boolean chatTitlesMoving;
    private float dialogsTitleAlpha = 1f;
    private boolean recording, enabled, animate, nativeChatControls;

    private final class ChatTitle {
        final ActionBar bar;
        final RenderNode pixels = new RenderNode("NavigationChatTitle");
        final RenderNode surface = new RenderNode("NavigationChatTitleSurface");
        final Matrix barTransform = new Matrix();
        final float[] transformValues = new float[9];
        final RectF titleBounds = new RectF();
        final RectF foregroundBounds = new RectF();
        final RectF childBounds = new RectF();
        final HeaderButtonSpring visibility = new HeaderButtonSpring(0);
        final HeaderButtonPulse pulse = new HeaderButtonPulse();
        float x, y;
        int blurStep = -1;
        boolean initialized;
        BlurredBackgroundDrawable glass;

        ChatTitle(ActionBar bar) { this.bar = bar; }

        void setVisible(boolean visible) {
            float target = visible ? 1f : 0f;
            if (visibility.target != target && initialized) pulse.trigger();
            visibility.target = target;
            initialized = true;
        }

        boolean advance(float dt, boolean animate) {
            return visibility.advance(dt, animate) | pulse.advance(dt, animate);
        }

        void record() {
            x = layoutCoordinate(bar, true);
            y = layoutCoordinate(bar, false);
            barTransform.set(bar.getMatrix());
            bar.getChatNavigationTitleBounds(titleBounds);
            bar.getChatNavigationForegroundBounds(foregroundBounds, childBounds);
            if (foregroundBounds.isEmpty()) {
                pixels.discardDisplayList();
            } else {
                int pad = AndroidUtilities.dp(24);
                pixels.setPosition((int) Math.floor(foregroundBounds.left) - pad,
                        (int) Math.floor(foregroundBounds.top) - pad,
                        (int) Math.ceil(foregroundBounds.right) + pad,
                        (int) Math.ceil(foregroundBounds.bottom) + pad);
                recordForeground();
            }
        }

        private void recordForeground() {
            Canvas canvas = pixels.beginRecording();
            try {
                canvas.translate(-pixels.getLeft(), -pixels.getTop());
                bar.drawChatNavigationTitle(canvas);
            } finally {
                pixels.endRecording();
            }
        }

        void draw(Canvas canvas) {
            if (visibility.value <= .001f) return;
            float progress = clamp(visibility.value);
            int step = foregroundBlurStep(visibility.value);
            if (blurStep != step) {
                pixels.setRenderEffect(foregroundBlurEffect(step));
                blurStep = step;
            }
            surface.setAlpha(progress);
            pixels.setAlpha(progress * progress);
            int save = canvas.save();
            canvas.translate(x, y);
            canvas.concat(barTransform);
            float scale = HeaderButtonGroupMotion.presenceScale(visibility.value, animate);
            float centerX = titleBounds.centerX(), centerY = titleBounds.centerY();
            float shellScaleX = animate ? scale * pulse.scale(titleBounds.width(), AndroidUtilities.dp(16), true) : 1f;
            float shellScaleY = animate ? scale * pulse.scale(titleBounds.height(), AndroidUtilities.dp(16), false) : 1f;
            float glassScaleX = 1f + .85f * (shellScaleX - 1f);
            float glassScaleY = 1f + .85f * (shellScaleY - 1f);
            int glassSave = canvas.save();
            canvas.scale(glassScaleX, glassScaleY, centerX, centerY);
            if (glass == null) glass = bar.createChatNavigationTitleSurface(factory);
            barTransform.getValues(transformValues);
            float barScaleX = transformValues[Matrix.MSCALE_X], barScaleY = transformValues[Matrix.MSCALE_Y];
            float sourceX = x + transformValues[Matrix.MTRANS_X] + barScaleX * (1f - glassScaleX) * centerX;
            float sourceY = y + transformValues[Matrix.MTRANS_Y] + barScaleY * (1f - glassScaleY) * centerY;
            int guard = AndroidUtilities.dp(24);
            surface.setPosition((int) Math.floor(titleBounds.left) - guard, (int) Math.floor(titleBounds.top) - guard,
                    (int) Math.ceil(titleBounds.right) + guard, (int) Math.ceil(titleBounds.bottom) + guard);
            Canvas background = surface.beginRecording();
            try {
                background.translate(-surface.getLeft(), -surface.getTop());
                bar.drawChatNavigationTitleSurface(background, SharedConfig.chatBlurEnabled() ? glass : null, titleBounds,
                        barScaleX * glassScaleX, barScaleY * glassScaleY, sourceX, sourceY);
            } finally {
                surface.endRecording();
            }
            canvas.drawRenderNode(surface);
            canvas.restoreToCount(glassSave);
            float foregroundScale = HeaderButtonGroupMotion.foregroundScale(visibility.value, animate);
            canvas.scale(shellScaleX * foregroundScale, shellScaleY * foregroundScale, centerX, centerY);
            if (pixels.hasDisplayList()) canvas.drawRenderNode(pixels);
            canvas.restoreToCount(save);
        }

        void release() {
            pixels.discardDisplayList();
            surface.discardDisplayList();
        }
    }

    private final class Icon {
        final RenderNode pixels = new RenderNode("RetainedHeaderForeground");
        View action;
        ActionBar bar;
        float lastBlur = -1;
        final HeaderButtonGroupMotion.Layer motion;
        final LiquidPressAnimationSuppressor pressless = new LiquidPressAnimationSuppressor();
        Icon(HeaderButtonGroupMotion.Layer motion) { this.motion = motion; }

        void record() {
            // Exiting layers keep their last recorded pixels, without retaining a page.
            if (action == null) return;
            pressless.sync(action, true);
            int pad = AndroidUtilities.dp(32), height = AndroidUtilities.dp(44);
            pixels.setPosition(0, 0, Math.max(1, Math.round(motion.width) + 2 * pad), height + 2 * pad);
            Canvas canvas = pixels.beginRecording();
            try {
                canvas.translate(pad + (motion.width - action.getWidth()) / 2f, pad + (height - action.getHeight()) / 2f);
                bar.drawNavigationButton(canvas, action);
            } finally { pixels.endRecording(); }
        }

        void draw(Canvas canvas, float groupAlpha) { draw(canvas, groupAlpha, 0); }
        void draw(Canvas canvas, float groupAlpha, float popupBlur) {
            record();
            if (!pixels.hasDisplayList()) return;
            float p = motion.visibility.value;
            int step = foregroundBlurStep(p);
            float blur = Math.max(popupBlur, AndroidUtilities.dpf2(step * FOREGROUND_BLUR_STEP_DP));
            if (lastBlur != blur) {
                RenderEffect blurEffect = popupBlur == blur && popupBlur > .01f
                        ? RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.DECAL) : foregroundBlurEffect(step);
                pixels.setRenderEffect(blurEffect);
                lastBlur = blur;
            }
            pixels.setAlpha(groupAlpha * clamp(p));
            int save = canvas.save();
            canvas.translate(motion.x.value, 0);
            float scale = HeaderButtonGroupMotion.foregroundScale(p, animate);
            canvas.scale(scale, scale, motion.width / 2, AndroidUtilities.dp(22));
            canvas.translate(-AndroidUtilities.dp(32), -AndroidUtilities.dp(32));
            canvas.drawRenderNode(pixels);
            canvas.restoreToCount(save);
        }

        void release() { pressless.restore(); action = null; bar = null; pixels.setRenderEffect(null); pixels.discardDisplayList(); }
    }

    private static final java.util.WeakHashMap<ActionBarLayout,java.lang.ref.WeakReference<NavigationHeaderButtons>> owners=new java.util.WeakHashMap<>();

    /** Retained foreground and physical bounds of the whole shared capsule, plus its tapped member. */
    public static final class MenuSource {
        public final RectF bounds=new RectF(), through=new RectF();
        private final NavigationHeaderButtons owner;
        private final Group group;
        private final int index;
        private final Icon icon;
        private boolean released;
        private MenuSource(NavigationHeaderButtons owner,Group group,int index,Icon icon) {
            this.owner=owner; this.group=group; this.index=index; this.icon=icon;
            bounds.set(owner.bounds[index]);
            float width=group.motion.width.value,height=AndroidUtilities.dp(44);
            float cx=group.x+width/2+(owner.animate?group.motion.kick.value:0)
                    +(icon.motion.x.value+icon.motion.width/2-width/2)*group.sx;
            through.set(cx-icon.motion.width*group.sx/2,group.y+height/2-height*group.sy/2,
                    cx+icon.motion.width*group.sx/2,group.y+height/2+height*group.sy/2);
            group.press.mapBounds(through,through);
            int[] point=new int[2]; owner.host.getLocationOnScreen(point);
            bounds.offset(point[0],point[1]); through.offset(point[0],point[1]);
        }
        public void updateBounds() {
            bounds.set(owner.bounds[index]);
            float width=group.motion.width.value,height=AndroidUtilities.dp(44);
            float cx=group.x+width/2+(owner.animate?group.motion.kick.value:0)
                    +(icon.motion.x.value+icon.motion.width/2-width/2)*group.sx;
            through.set(cx-icon.motion.width*group.sx/2,group.y+height/2-height*group.sy/2,
                    cx+icon.motion.width*group.sx/2,group.y+height/2+height*group.sy/2);
            group.press.mapBounds(through,through);
            int[] point=new int[2]; owner.host.getLocationOnScreen(point);
            bounds.offset(point[0],point[1]); through.offset(point[0],point[1]);
        }
        public void release() {
            if(released) return; released=true;
            if(group.menuSource==this) group.menuSource=null;
            owner.host.invalidate();
        }
    }

    public static MenuSource acquireMenuSource(View action) {
        for(java.lang.ref.WeakReference<NavigationHeaderButtons> reference:new java.util.ArrayList<>(owners.values())) {
            NavigationHeaderButtons owner=reference.get();
            if(owner==null || !owner.enabled || !modeEnabled()) continue;
            for(int i=0;i<owner.groups.length;i++) {
                Group group=owner.groups[i];
                if(group.menuSource!=null) continue;
                for(Icon icon:group.icons.values()) if(icon.action==action) {
                    MenuSource result=new MenuSource(owner,group,i,icon);
                    group.menuSource=result;
                    MotionEvent cancel=MotionEvent.obtain(0,SystemClock.uptimeMillis(),MotionEvent.ACTION_CANCEL,0,0,0);
                    group.press.onTouchEvent(cancel,false); cancel.recycle();
                    owner.host.invalidate(); return result;
                }
            }
        }
        return null;
    }

    private final class Group {
        MenuSource menuSource;
        final HeaderButtonGroupMotion motion = new HeaderButtonGroupMotion();
        final HeaderButtonSpring inset = new HeaderButtonSpring(0);
        final HeaderButtonSpring surfacePresence = new HeaderButtonSpring(1);
        final LinkedHashMap<String, Icon> icons = new LinkedHashMap<>();
        final LiquidTouchEffect press = new LiquidTouchEffect(host);
        final BlurredBackgroundDrawable surface = factory.create(host).setRadius(AndroidUtilities.dp(22)).setPadding(0);
        final boolean leading;
        ActionBar colorBar;
        boolean positioned;
        float x, y, sx = 1, sy = 1;
        Group(boolean leading) { this.leading = leading; }

        float actionWidth(View action) {
            return !leading && ((ActionBarMenuItem) action).getIconView() == null
                    ? Math.max(AndroidUtilities.dp(44), action.getWidth()) : AndroidUtilities.dp(44);
        }

        void retarget(ArrayList<View> actions, ActionBar bar) {
            boolean chat = bar != null && bar.isChatHeader();
            LinkedHashMap<String, Float> widths = new LinkedHashMap<>();
            LinkedHashMap<String, View> views = new LinkedHashMap<>();
            for (View action : actions) {
                String visual = leading ? "back:" + ((android.widget.ImageView) action).getDrawable().getClass().getName()
                        : String.valueOf(((ActionBarMenuItem) action).getNavigationIconKey());
                if (chat && bar.isChatNavigationAvatar(action)) visual = "chat:avatar";
                String key = visual;
                int occurrence = 1;
                while (views.containsKey(key)) key = visual + ":" + occurrence++;
                widths.put(key, actionWidth(action));
                views.put(key, action);
            }
            float gap = AndroidUtilities.dp(8);
            if (chat && actions.size() > 1) {
                float first = actionCenter(actions.get(0), bar, true);
                float last = actionCenter(actions.get(actions.size() - 1), bar, true);
                float span = 0;
                for (float width : widths.values()) span += width;
                span -= (actionWidth(actions.get(0)) + actionWidth(actions.get(actions.size() - 1))) / 2f;
                gap = (last - first - span) / (actions.size() - 1);
            }
            motion.retarget(widths, gap);
            if (!actions.isEmpty()) {
                View edge = actions.get(leading ? 0 : actions.size() - 1);
                float targetInset = AndroidUtilities.dp(12);
                if (chat) {
                    float center = layoutCoordinate(bar, true) + actionCenter(edge, bar, true);
                    targetInset = (leading ? center : host.getWidth() - center) - actionWidth(edge) / 2f;
                }
                inset.target = targetInset;
                surfacePresence.target = !chat || bar.hasChatNavigationSurface(leading) ? 1 : 0;
                if (!positioned) {
                    inset.snap(inset.target);
                    surfacePresence.snap(surfacePresence.target);
                    positioned = true;
                }
            }
            for (Map.Entry<String, HeaderButtonGroupMotion.Layer> entry : motion.layers.entrySet()) {
                Icon icon = icons.get(entry.getKey());
                if (icon == null) { icon = new Icon(entry.getValue()); icons.put(entry.getKey(), icon); }
                View next = views.get(entry.getKey());
                if (touched == icon && icon.action != next) cancelTouch();
                if (icon.action != next) icon.pressless.restore();
                icon.action = next;
                icon.bar = next == null ? null : bar;
            }
            if (bar != null && colorBar != bar) {
                colorBar = bar;
                surface.setColorProvider(chat ? BlurredBackgroundProviderImpl.topPanelChatActivity(bar.getResourcesProvider())
                        : BlurredBackgroundProviderImpl.topPanel(bar.getResourcesProvider()));
            }
        }

        boolean advance(float dt) {
            boolean moving = motion.advance(dt, animate) | inset.advance(dt, animate) | surfacePresence.advance(dt, animate);
            for (Iterator<Map.Entry<String, Icon>> it = icons.entrySet().iterator(); it.hasNext();) {
                Map.Entry<String, Icon> entry = it.next();
                Icon icon = entry.getValue();
                if (icon.motion.visibility.target == 0 && icon.motion.visibility.settled()) {
                    icon.release();
                    motion.layers.remove(entry.getKey());
                    it.remove();
                }
            }
            if (!moving && motion.disposeIfGone()) {
                press.reset();
                positioned = false;
                colorBar = null;
                surface.setColorProvider(BlurredBackgroundProviderImpl.topPanel(null));
            }
            return moving;
        }

        void geometry(int index) {
            float width = Math.max(0, motion.width.value), height = AndroidUtilities.dp(44);
            x = leading ? inset.value : host.getWidth() - inset.value - width;
            sx = motion.shellScale(height, AndroidUtilities.dp(16), true, animate);
            sy = motion.shellScale(height, AndroidUtilities.dp(16), false, animate);
            float kick = animate ? motion.kick.value : 0;
            float cx = x + width / 2 + kick, cy = y + height / 2;
            bounds[index].set(cx - width * sx / 2, cy - height * sy / 2, cx + width * sx / 2, cy + height * sy / 2);
            press.setBounds(bounds[index].left, bounds[index].top, bounds[index].right, bounds[index].bottom);
            surface.setBounds(Math.round(x), Math.round(y), Math.round(x + width), Math.round(y + height));
            surface.updateColors();
            surface.setAlpha(255);
            press.mapBounds(bounds[index], bounds[index]);
            LiquidTouchEffect.updateBackground(surface, press, 0, 0);
            alpha[index] = clamp(motion.presence.value);
            surfaceAlpha[index] = alpha[index] * clamp(surfacePresence.value);
        }

        void drawIcons(Canvas canvas, int index) {
            float foregroundAlpha = alpha[index];
            if (foregroundAlpha <= .001f) return;
            int save = canvas.save();
            press.begin(canvas);
            float width = motion.width.value, height = AndroidUtilities.dp(44);
            canvas.translate(x + (animate ? motion.kick.value : 0), y);
            canvas.scale(sx, sy, width / 2, height / 2);
            for (Icon icon : icons.values()) icon.draw(canvas, foregroundAlpha);
            canvas.restoreToCount(save);
        }

        float localX(float screenX) {
            return (screenX - x - motion.width.value / 2 - (animate ? motion.kick.value : 0)) / sx + motion.width.value / 2;
        }
        float localY(float screenY) { return (screenY - y - AndroidUtilities.dp(22)) / sy + AndroidUtilities.dp(22); }
    }

    public NavigationHeaderButtons(ActionBarLayout host) {
        owners.put(host,new java.lang.ref.WeakReference<>(this));
        this.host = host;
        factory.setLiquidGlassEffectAllowed(true);
        for (int i = 0; i < 2; i++) {
            groups[i] = new Group(i == 0);
            members[i] = groups[i].surface;
            effects[i] = groups[i].press;
        }
    }

    public static boolean modeEnabled() {
        return NekoConfig.fadeViewOtherActivities() && !NekoConfig.materialActivityHeaders;
    }
    private int foregroundBlurStep(float visibility) {
        return Math.round(HeaderButtonGroupMotion.foregroundBlur(visibility, 1f, animate) / FOREGROUND_BLUR_STEP_DP);
    }

    private RenderEffect foregroundBlurEffect(int step) {
        if (step == 0) return null;
        if (foregroundBlurs[step] == null) {
            float radius = AndroidUtilities.dpf2(step * FOREGROUND_BLUR_STEP_DP);
            foregroundBlurs[step] = RenderEffect.createBlurEffect(radius, radius, Shader.TileMode.DECAL);
        }
        return foregroundBlurs[step];
    }

    private static float clamp(float x) { return Math.max(0, Math.min(1, x)); }

    private boolean eligible(ActionBar bar) {
        if (bar == null || bar.getVisibility() != View.VISIBLE
                || bar.isSearchFieldVisible() || bar.isActionModeShowed()) return false;
        ViewParent parent = bar.getParent();
        while (parent != null && parent != host) {
            if (parent instanceof ActionBarLayout) return false;
            parent = parent.getParent();
        }
        return parent == host;
    }

    private float layoutCoordinate(View view, boolean horizontal) {
        float coordinate = 0;
        while (view != host && view.getParent() instanceof View) {
            View parent = (View) view.getParent();
            // The navigation container itself may be moving during the page transition.
            coordinate += horizontal ? view.getLeft() - parent.getScrollX() : view.getTop() - parent.getScrollY();
            view = parent;
        }
        return coordinate;
    }

    private static float actionCenter(View action, ActionBar bar, boolean horizontal) {
        float center = (horizontal ? action.getWidth() : action.getHeight()) / 2f;
        for (View view = action; view != bar; view = (View) view.getParent()) {
            center += horizontal ? view.getX() : view.getY();
        }
        return center;
    }

    /** Also called by a newly attached tab's ActionBar before it records any surfaces. */
    public void register(ActionBar bar) {
        if (enabled && eligible(bar) && !managed.contains(bar)) {
            managed.add(bar);
            bar.setNavigationButtonsOwned(true);
            bar.setDialogsNavigationTitleAlpha(dialogsTitleAlpha);
        }
    }

    public Canvas begin(Canvas destination, boolean allowed) {
        if (recording || !destination.isHardwareAccelerated()) return destination;
        if (!allowed || !modeEnabled()) { release(); return destination; }
        if (!enabled) owners.put(host, new java.lang.ref.WeakReference<>(this));
        enabled = true;
        animate = SharedConfig.animationsEnabled() && ValueAnimator.areAnimatorsEnabled();
        for (int i = managed.size() - 1; i >= 0; i--) {
            ActionBar bar = managed.get(i);
            if (!eligible(bar)) {
                bar.setNavigationButtonsOwned(false);
                bar.setDialogsNavigationTitleAlpha(1f);
                managed.remove(i);
            }
        }
        pages.clear();
        for (BaseFragment fragment : host.getFragmentStack()) {
            if (fragment instanceof MainTabsActivity) ((MainTabsActivity) fragment).collectNavigationHeaderFragments(pages);
            else pages.add(fragment);
        }
        for (BaseFragment page : pages) register(page.getActionBar());
        pages.clear();
        BaseFragment fragment = host.getNavigationHeaderFragment();
        ActionBar bar = fragment == null ? null : fragment.getActionBar();
        currentBar = eligible(bar) ? bar : null;
        nativeChatControls = bar != null && bar.isChatHeader() && bar.isShown() && currentBar == null;
        leadingActions.clear();
        trailingActions.clear();
        if (currentBar != null && currentBar.getWidth() > 0) {
            register(currentBar);
            View back = currentBar.getBackButton();
            if (visible(back) && ((android.widget.ImageView) back).getDrawable() != null) leadingActions.add(back);
            ActionBarMenu menu = currentBar.createMenu();
            if (menu.getVisibility() == View.VISIBLE) {
                for (int i = 0; i < menu.getChildCount(); i++) {
                    View child = menu.getChildAt(i);
                    if (child instanceof ActionBarMenuItem && visible(child)) trailingActions.add(child);
                }
            }
            float top = layoutCoordinate(currentBar, false);
            float y = top + (currentBar.getOccupyStatusBar() ? AndroidUtilities.statusBarHeight : 0)
                    + (ActionBar.getCurrentActionBarHeight() - AndroidUtilities.dp(44)) / 2f;
            groups[0].y = groups[1].y = y;
            if (currentBar.isChatHeader()) {
                trailingActions.sort((a, b) -> Float.compare(actionCenter(a, currentBar, true), actionCenter(b, currentBar, true)));
                top += currentBar.getTranslationY() - AndroidUtilities.dp(22);
                if (!leadingActions.isEmpty()) groups[0].y = top + actionCenter(leadingActions.get(0), currentBar, false);
                if (!trailingActions.isEmpty()) groups[1].y = top + actionCenter(trailingActions.get(trailingActions.size() - 1), currentBar, false);
            }
        }
        groups[0].retarget(leadingActions, currentBar);
        groups[1].retarget(trailingActions, currentBar);
        long now = SystemClock.uptimeMillis();
        frameDelta = lastFrame == 0 ? 0 : Math.min(1, (now - lastFrame) / 1000f);
        lastFrame = now;
        updateTitleMotion();
        content.setPosition(0, 0, host.getWidth(), host.getHeight());
        recording = true;
        return content.beginRecording();
    }

    private boolean visible(View view) { return view != null && view.getVisibility() == View.VISIBLE && view.getWidth() > 0 && view.getAlpha() > 0; }

    private void updateTitleMotion() {
        ActionBar bar = currentBar != null && currentBar.isChatHeader() && currentBar.getWidth() > 0 ? currentBar : null;
        activeChatTitle = null;
        for (ChatTitle title : chatTitles) {
            if (title.bar == bar) activeChatTitle = title;
        }
        if (bar != null && activeChatTitle == null) {
            activeChatTitle = new ChatTitle(bar);
            chatTitles.add(activeChatTitle);
        }
        float chatVisibility = 0f;
        chatTitlesMoving = false;
        for (Iterator<ChatTitle> it = chatTitles.iterator(); it.hasNext();) {
            ChatTitle title = it.next();
            title.setVisible(title == activeChatTitle);
            // Search and selection draw the native header while they own the toolbar.
            if (!title.bar.areNavigationButtonsOwned() && title.bar.isShown()
                    && (title.bar.isSearchFieldVisible() || title.bar.isActionModeShowed())) {
                title.visibility.snap(0);
            }
            chatTitlesMoving |= title.advance(frameDelta, animate);
            if (title.visibility.target == 0 && title.visibility.settled()) {
                title.release();
                it.remove();
            } else {
                chatVisibility += title.visibility.value;
            }
        }
        float progress = clamp(chatVisibility);
        dialogsTitleAlpha = 1f - progress * progress;
        for (ActionBar managedBar : managed) managedBar.setDialogsNavigationTitleAlpha(dialogsTitleAlpha);
    }

    public void end(Canvas destination) {
        if (!recording) return;
        recording = false;
        content.endRecording();
        destination.drawRenderNode(content);
        boolean moving = groups[0].advance(frameDelta) | groups[1].advance(frameDelta);
        boolean present = false;
        for (int i = 0; i < 2; i++) { groups[i].geometry(i); present |= surfaceAlpha[i] > .001f; }
        if (SharedConfig.chatBlurEnabled() && (present && !nativeChatControls || !chatTitles.isEmpty())) {
            Canvas backdrop = source.beginRecording(host.getWidth(), host.getHeight());
            try {
                backdrop.drawColor(Theme.getColor(Theme.key_windowBackgroundGray));
                // The same display list includes both pages' current clipping, transforms and alpha.
                backdrop.drawRenderNode(content);
            } finally { source.endRecording(); }
            if (LiteMode.isEnabled(LiteMode.FLAG_LIQUID_GLASS)) source.setGlassBlur(AndroidUtilities.dp(8));
            else source.setPlainBlur(AndroidUtilities.dpf2(NekoConfig.blurStrength * 4f / 3f));
        }
        if (present && !nativeChatControls) {
            if (SharedConfig.chatBlurEnabled() && Build.VERSION.SDK_INT >= 33 && NekoConfig.liquidChatElements) {
                if (union == null) { union = new LiquidGlassInputGroup(2); union.setPairMergeDistance(0, 1, AndroidUtilities.dp(12)); }
                // Group presence is opacity, never a shrinking mask (unlike composer controls).
                union.draw(destination, members, bounds, surfaceAlpha, effects, null, null, false);
            } else for (int i = 0; i < 2; i++) {
                if (surfaceAlpha[i] <= .001f) continue;
                if (!SharedConfig.chatBlurEnabled()) {
                    Group group = groups[i];
                    int save = destination.save();
                    group.press.begin(destination);
                    destination.translate(group.x + (animate ? group.motion.kick.value : 0), group.y);
                    destination.scale(group.sx, group.sy, group.motion.width.value / 2, AndroidUtilities.dp(22));
                    plain.setColor(Theme.getColor(Theme.key_windowBackgroundWhite));
                    plain.setAlpha(Math.round(255 * surfaceAlpha[i]));
                    destination.drawRoundRect(0, 0, group.motion.width.value, AndroidUtilities.dp(44),
                            AndroidUtilities.dp(22), AndroidUtilities.dp(22), plain);
                    destination.restoreToCount(save);
                } else {
                    int save = destination.save();
                    Group group = groups[i];
                    float centerX = group.x + group.motion.width.value / 2f, centerY = group.y + AndroidUtilities.dp(22);
                    float kick = animate ? group.motion.kick.value : 0f;
                    destination.translate(kick, 0);
                    destination.scale(group.sx, group.sy, centerX, centerY);
                    group.surface.setSourceTransform(group.sx, group.sy,
                            kick + (1f - group.sx) * centerX, (1f - group.sy) * centerY);
                    group.surface.setAlpha(Math.round(255 * surfaceAlpha[i]));
                    group.surface.updateDisplayList();
                    group.surface.draw(destination);
                    destination.restoreToCount(save);
                }
            }
        }
        if (!nativeChatControls) {
            for (int i = 0; i < 2; i++) groups[i].drawIcons(destination, i);
        }
        if (activeChatTitle != null) activeChatTitle.record();
        for (ChatTitle title : chatTitles) title.draw(destination);
        if (moving || chatTitlesMoving) host.postInvalidateOnAnimation();
        else lastFrame = 0;
    }

    public boolean dispatchTouchEvent(MotionEvent event, boolean blocked) {
        if (blocked || !enabled || !modeEnabled()) { cancelTouch(); return false; }
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
            cancelTouch();
            for (int i = 0; i < 2 && touched == null; i++) {
                Group group = groups[i];
                if (alpha[i] < .5f) continue;
                float x = group.localX(event.getX()), y = group.localY(event.getY());
                if (y < 0 || y > AndroidUtilities.dp(44)) continue;
                for (Icon icon : group.icons.values()) {
                    if (icon.action != null && icon.action.isEnabled() && !icon.bar.isChatNavigationAvatar(icon.action)
                            && x >= icon.motion.x.value && x <= icon.motion.x.value + icon.motion.width) {
                        touched = icon;
                        touchedGroup = group;
                        break;
                    }
                }
            }
        }
        if (touched == null) return false;
        touchedGroup.press.onTouchEvent(event, NekoConfig.liquidChatElements);
        MotionEvent local = MotionEvent.obtain(event);
        local.setLocation(touchedGroup.localX(event.getX()) - touched.motion.x.value + (touched.action.getWidth() - touched.motion.width) / 2,
                touchedGroup.localY(event.getY()) + (touched.action.getHeight() - AndroidUtilities.dp(44)) / 2);
        touched.action.dispatchTouchEvent(local);
        local.recycle();
        if (event.getActionMasked() == MotionEvent.ACTION_UP || event.getActionMasked() == MotionEvent.ACTION_CANCEL) { touched = null; touchedGroup = null; }
        return true;
    }

    private void cancelTouch() {
        if (touched != null && touched.action != null) {
            MotionEvent cancel = MotionEvent.obtain(SystemClock.uptimeMillis(), SystemClock.uptimeMillis(), MotionEvent.ACTION_CANCEL, 0, 0, 0);
            touched.action.dispatchTouchEvent(cancel);
            touchedGroup.press.onTouchEvent(cancel, false);
            cancel.recycle();
        }
        touched = null;
        touchedGroup = null;
    }

    public void release() {
        if (!enabled) return;
        enabled = false;
        owners.remove(host);
        for(Group group:groups) if(group.menuSource!=null) group.menuSource.release();
        cancelTouch();
        for (ActionBar bar : managed) {
            bar.setNavigationButtonsOwned(false);
            bar.setDialogsNavigationTitleAlpha(1f);
        }
        managed.clear();
        leadingActions.clear();
        trailingActions.clear();
        currentBar = null;
        activeChatTitle = null;
        dialogsTitleAlpha = 1f;
        for (ChatTitle title : chatTitles) title.release();
        chatTitles.clear();
        lastFrame = 0;
        content.discardDisplayList();
        if (union != null) { union.release(); union = null; }
        for (int i = 0; i < 2; i++) {
            Group group = groups[i];
            for (Icon icon : group.icons.values()) icon.release();
            group.icons.clear();
            group.press.reset();
            groups[i] = new Group(i == 0);
            members[i] = groups[i].surface;
            effects[i] = groups[i].press;
            alpha[i] = 0;
        }
    }
}
