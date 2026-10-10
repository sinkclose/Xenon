package zxc.iconic.xenon.settings;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.AndroidUtilities.dpf2;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RenderNode;
import android.graphics.Shader;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.NotificationCenter;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.MotionBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawable;
import org.telegram.ui.Components.blur3.drawable.BlurredBackgroundDrawableRenderNode;
import org.telegram.ui.Components.blur3.drawable.color.impl.BlurredBackgroundProviderImpl;
import org.telegram.ui.Components.blur3.source.BlurredBackgroundSourceRenderNode;

import zxc.iconic.xenon.NekoConfig;

/**
 * Live preview of a glass surface on top of the current chat wallpaper.
 * The wallpaper is drawn full-bleed (center-cropped) and recorded into the
 * backing {@link BlurredBackgroundSourceRenderNode}. The source uses the same
 * blur settings as the runtime chat path.
 * Sliders call {@link #invalidateGlass()} and force a redraw.
 */
@SuppressLint("ViewConstructor")
public class GlassPreviewCell extends View implements NotificationCenter.NotificationCenterDelegate {

    private final Theme.ResourcesProvider resourcesProvider;

    // Keep wallpaper rendering on the hardware canvas: some wallpaper shaders
    // cannot be captured reliably into a software bitmap.
    private final RenderNode wallpaperNode = new RenderNode("GlassPreviewWallpaper");
    @Nullable private final BlurredBackgroundSourceRenderNode renderNodeSource;
    @Nullable private BlurredBackgroundDrawable glassDrawable;

    private final android.graphics.RectF bubbleRect = new android.graphics.RectF();
    private final float cornerRadius;
    @Nullable private Drawable capturedWallpaper;
    private boolean wallpaperDirty = true;

    @RequiresApi(api = 33)
    public GlassPreviewCell(Context context, Theme.ResourcesProvider resourcesProvider) {
        super(context);
        this.resourcesProvider = resourcesProvider;
        renderNodeSource = new BlurredBackgroundSourceRenderNode(null);
        cornerRadius = dp(20);
        setWillNotDraw(false);
        rebuildGlass();
    }

    // ---------------------------------------------------------------------------
    // Glass drawable lifecycle
    // ---------------------------------------------------------------------------

    private void rebuildGlass() {
        if (renderNodeSource == null) return;
        if (glassDrawable != null) glassDrawable.setCallback(null);
        glassDrawable = renderNodeSource.createDrawable();
        glassDrawable.setCallback(this);
        if (glassDrawable instanceof BlurredBackgroundDrawableRenderNode) {
            ((BlurredBackgroundDrawableRenderNode) glassDrawable).setLiquidGlassEffectAllowed();
        }
        glassDrawable.setColorProvider(BlurredBackgroundProviderImpl.chatTitlePill(resourcesProvider));
        glassDrawable.setBounds(
                (int) bubbleRect.left, (int) bubbleRect.top,
                (int) bubbleRect.right, (int) bubbleRect.bottom);
        glassDrawable.setRadius(cornerRadius);
    }

    /** Call whenever any glass parameter slider changes. */
    public void invalidateGlass() {
        if (!(glassDrawable instanceof BlurredBackgroundDrawableRenderNode)) {
            rebuildGlass();
            refreshRenderNodeBlur();
            invalidate();
            return;
        }
        // Refresh the cached backgroundColor (tint) before redrawing, otherwise
        // the B/W toggle and tint slider would not visibly update the preview.
        glassDrawable.updateColors();
        // Uniforms update in place; the drawable detects renderer/mode changes on draw.
        refreshRenderNodeBlur();
        invalidate();
    }

    /**
     * Match the source preparation used by the real chat pipeline.
     * Advanced glass uses the blur slider; standard liquid glass keeps the
     * legacy frosted backing blur.
     */
    private void refreshRenderNodeBlur() {
        if (renderNodeSource == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;
        // Advanced glass: apply the blur-slider value so the preview matches the chat.
        // Standard liquid glass: frosted backing blur (8 dp).
        renderNodeSource.setGlassBlur(dpf2(8f));
        if (glassDrawable instanceof BlurredBackgroundDrawableRenderNode) {
            ((BlurredBackgroundDrawableRenderNode) glassDrawable).invalidateDisplayList();
        }
    }

    // ---------------------------------------------------------------------------
    // Layout
    // ---------------------------------------------------------------------------

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        final float hm = dp(21);
        final float bh = dp(56);
        final float cy = getMeasuredHeight() / 2f;
        bubbleRect.set(hm, cy - bh / 2f, getMeasuredWidth() - hm, cy + bh / 2f);
        if (glassDrawable != null) {
            glassDrawable.setBounds(
                    (int) bubbleRect.left, (int) bubbleRect.top,
                    (int) bubbleRect.right, (int) bubbleRect.bottom);
            glassDrawable.setRadius(cornerRadius);
        }
        captureWallpaper();
    }

    // ---------------------------------------------------------------------------
    // Wallpaper capture
    // ---------------------------------------------------------------------------

    /**
     * Records the wallpaper on a hardware canvas and shares that recording
     * with the visible background and the blurred glass source.
     */
    private void captureWallpaper() {
        if (getMeasuredWidth() <= 0 || getMeasuredHeight() <= 0 || renderNodeSource == null) return;

        final Drawable currentWallpaper = Theme.getCachedWallpaperNonBlocking();
        // Keep the last image while a new wallpaper is loading. On first load,
        // record a theme-colored fallback so the glass source is never empty.
        final Drawable wallpaper = currentWallpaper != null ? currentWallpaper : capturedWallpaper;
        final Drawable drawable = wallpaper != null ? wallpaper
                : new ColorDrawable(Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider));
        final Canvas wallpaperCanvas = wallpaperNode.beginRecording(getMeasuredWidth(), getMeasuredHeight());
        try {
            drawWallpaperDrawable(wallpaperCanvas, drawable, getMeasuredWidth(), getMeasuredHeight());
            drawPreviewStripes(wallpaperCanvas);
        } finally {
            wallpaperNode.endRecording();
        }
        wallpaperNode.setPosition(0, 0, getMeasuredWidth(), getMeasuredHeight());
        capturedWallpaper = wallpaper;
        wallpaperDirty = false;

        // Use exactly the same recording for the visible wallpaper and the glass.
        final Canvas c = renderNodeSource.beginRecording(getMeasuredWidth(), getMeasuredHeight());
        try {
            c.drawRenderNode(wallpaperNode);
        } finally {
            renderNodeSource.endRecording();
        }
        refreshRenderNodeBlur();

        if (glassDrawable instanceof BlurredBackgroundDrawableRenderNode) {
            ((BlurredBackgroundDrawableRenderNode) glassDrawable).invalidateDisplayList();
        }
        invalidate();
    }

    // ---------------------------------------------------------------------------
    // Drawing
    // ---------------------------------------------------------------------------

    @Override
    protected void onDraw(@NonNull Canvas canvas) {
        super.onDraw(canvas);
        final Drawable wallpaper = Theme.getCachedWallpaperNonBlocking();
        if (wallpaperDirty || !wallpaperNode.hasDisplayList()
                || (wallpaper != null && wallpaper != capturedWallpaper)) {
            captureWallpaper();
        }
        drawWallpaper(canvas);
        drawBubble(canvas);
        if (Theme.wallpaperLoadTask != null) postInvalidateOnAnimation();
    }

    private void drawWallpaper(@NonNull Canvas canvas) {
        if (canvas.isHardwareAccelerated() && wallpaperNode.hasDisplayList()) {
            canvas.drawRenderNode(wallpaperNode);
        } else {
            final Drawable drawable = capturedWallpaper != null ? capturedWallpaper
                    : new ColorDrawable(Theme.getColor(Theme.key_windowBackgroundGray, resourcesProvider));
            drawWallpaperDrawable(canvas, drawable, getMeasuredWidth(), getMeasuredHeight());
            drawPreviewStripes(canvas);
        }
    }

    private void drawPreviewStripes(@NonNull Canvas canvas) {
        if (bubbleRect.isEmpty()) return;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        paint.setStrokeWidth(dp(12));
        final int[] colors = {
                0xFF757575, 0xFF9E9E9E, 0xFFBDBDBD,
                0xFF9E9E9E, 0xFF757575, 0xFFFFFFFF
        };
        final float[] angles = { -30, -15, 0, 15, 30, 45 };
        final float cx = getMeasuredWidth() / 2f, cy = getMeasuredHeight() / 2f;
        final float len = (float) Math.hypot(getMeasuredWidth(), getMeasuredHeight());
        final int save = canvas.save();
        try {
            canvas.clipRect(bubbleRect);
            for (int i = 0; i < colors.length; i++) {
                paint.setColor(colors[i]);
                final int stripeSave = canvas.save();
                canvas.rotate(angles[i], cx, cy);
                canvas.drawLine(cx - len / 2f, cy, cx + len / 2f, cy, paint);
                canvas.restoreToCount(stripeSave);
            }
        } finally {
            canvas.restoreToCount(save);
        }
    }

    private void drawBubble(@NonNull Canvas canvas) {
        if (glassDrawable == null || bubbleRect.isEmpty()) return;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            p.setColor(Theme.multAlpha(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider), 0.5f));
            canvas.drawRoundRect(bubbleRect, cornerRadius, cornerRadius, p);
            return;
        }
        glassDrawable.setBounds(
                (int) bubbleRect.left, (int) bubbleRect.top,
                (int) bubbleRect.right, (int) bubbleRect.bottom);
        glassDrawable.draw(canvas);
    }

    // ---------------------------------------------------------------------------
    // Wallpaper drawing
    // ---------------------------------------------------------------------------

    private static void drawWallpaperDrawable(@NonNull Canvas c, @NonNull Drawable d, int w, int h) {
        // Theme owns this drawable and other views share it. Restore its state
        // after capture rather than leaving preview bounds/alpha on the wallpaper.
        final android.graphics.Rect bounds = new android.graphics.Rect(d.getBounds());
        final int alpha = d.getAlpha();
        final int save = c.save();
        try {
            c.clipRect(0, 0, w, h);
            // An opaque fallback also covers wallpapers that are not ready yet.
            c.drawColor(Theme.getColor(Theme.key_windowBackgroundGray));
            d.setAlpha(255);
            if (d instanceof BitmapDrawable bmd && bmd.getTileModeX() == Shader.TileMode.REPEAT) {
                final float scale = 2f / AndroidUtilities.density;
                c.scale(scale, scale);
                d.setBounds(0, 0, (int) Math.ceil(w / scale), (int) Math.ceil(h / scale));
            } else if (d instanceof ColorDrawable || d instanceof GradientDrawable || d instanceof MotionBackgroundDrawable) {
                d.setBounds(0, 0, w, h);
            } else {
                final int dw = d.getIntrinsicWidth(), dh = d.getIntrinsicHeight();
                if (dw > 0 && dh > 0) {
                    final float sc = Math.max((float) w / dw, (float) h / dh);
                    final int sw = Math.round(dw * sc), sh = Math.round(dh * sc);
                    d.setBounds((w - sw) / 2, (h - sh) / 2, (w + sw) / 2, (h + sh) / 2);
                } else {
                    d.setBounds(0, 0, w, h);
                }
            }
            d.draw(c);
        } finally {
            d.setBounds(bounds);
            d.setAlpha(alpha);
            c.restoreToCount(save);
        }
    }

    // ---------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------

    @Override
    protected boolean verifyDrawable(@NonNull Drawable who) {
        return who == glassDrawable || super.verifyDrawable(who);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        NotificationCenter.getGlobalInstance().addObserver(this, NotificationCenter.didSetNewWallpapper);
        wallpaperDirty = true;
        invalidateGlass();
    }

    @Override
    public void didReceivedNotification(int id, int account, Object... args) {
        if (id == NotificationCenter.didSetNewWallpapper) {
            wallpaperDirty = true;
            invalidate();
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        NotificationCenter.getGlobalInstance().removeObserver(this, NotificationCenter.didSetNewWallpapper);
        super.onDetachedFromWindow();
    }

    public static int heightDp()  { return 140; }
    public static int heightPx()  { return dp(heightDp()); }
}
