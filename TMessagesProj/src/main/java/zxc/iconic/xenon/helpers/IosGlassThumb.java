package zxc.iconic.xenon.helpers;

import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.RectF;
import android.graphics.Path;
import android.graphics.RenderNode;
import android.graphics.RenderEffect;
import android.graphics.Shader;
import android.graphics.Paint;
import android.graphics.BlendMode;
import android.view.View;

import androidx.annotation.RequiresApi;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.blur3.LiquidGlassEffect;

@RequiresApi(33)
public final class IosGlassThumb {
    public interface BackdropPainter { void draw(Canvas canvas); }
    // Local profile: refraction 50, animated blur 10 -> 0, chromatic 65, tint 0.
    private static final float REFRACTION = .50f, CHROMATIC = .65f;
    private final RenderNode background = new RenderNode("IosControlGlass");
    private final RenderNode backdrop = new RenderNode("IosControlBackdrop");
    private final RenderNode innerShadow = new RenderNode("IosSliderInnerShadow");
    private final Paint shadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float lastInnerBlur = -1f;
    private float lastBlur = -1f;
    private final Path clip = new Path();
    private final View owner;
    private LiquidGlassEffect effect;

    public IosGlassThumb(View owner, Theme.ResourcesProvider resourcesProvider) {
        this.owner = owner;
        rebuildEffect();
    }

    private void rebuildEffect() {
        effect = new LiquidGlassEffect(background, REFRACTION, CHROMATIC, owner::postInvalidateOnAnimation);
    }

    public void release() {
        background.setRenderEffect(null);
        background.discardDisplayList();
        backdrop.setRenderEffect(null);
        backdrop.discardDisplayList();
        innerShadow.setRenderEffect(null);
        innerShadow.discardDisplayList();
    }

    public void drawInnerShadow(Canvas canvas, RectF thumb, float progress) {
        int width = Math.max(1, (int) Math.ceil(thumb.width()));
        int height = Math.max(1, (int) Math.ceil(thumb.height()));
        float blur = 4f * AndroidUtilities.density * progress;
        innerShadow.setPosition(0, 0, width, height);
        Canvas recording = innerShadow.beginRecording(width, height);
        try {
            recording.drawColor(0x26000000);
            shadowPaint.setColor(Color.BLACK);
            shadowPaint.setBlendMode(BlendMode.DST_OUT);
            // Reference InnerShadow offsets the capsule down by its animated radius.
            recording.drawRoundRect(0, blur, width, height + blur, height / 2f, height / 2f, shadowPaint);
        } finally {
            innerShadow.endRecording();
        }
        if (blur != lastInnerBlur) {
            innerShadow.setRenderEffect(blur > .001f
                    ? RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.DECAL) : null);
            lastInnerBlur = blur;
        }
        innerShadow.setAlpha(progress);
        clip.reset();
        clip.addRoundRect(thumb, thumb.height() / 2f, thumb.height() / 2f, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        canvas.translate(thumb.left, thumb.top);
        canvas.drawRenderNode(innerShadow);
        canvas.restore();
    }

    public void draw(Canvas canvas, RectF thumb, float pressProgress, float scaleX, float scaleY,
              Theme.ResourcesProvider resourcesProvider, BackdropPainter painter) {
        int width = Math.max(1, (int) Math.ceil(thumb.width()));
        int height = Math.max(1, (int) Math.ceil(thumb.height()));
        background.setPosition(0, 0, width, height);
        backdrop.setPosition(0, 0, width, height);
        Canvas recording = backdrop.beginRecording(width, height);
        try {
            // The unscaled row surface plus a separately scaled track, as in rememberCombinedBackdrop.
            recording.drawColor(Theme.getColor(Theme.key_windowBackgroundWhite, resourcesProvider));
            recording.translate(-thumb.left, -thumb.top);
            recording.scale(scaleX, scaleY, thumb.centerX(), thumb.centerY());
            painter.draw(recording);
        } finally {
            backdrop.endRecording();
        }
        float blur = AndroidUtilities.density * IosSwitchDynamics.glassBlurRadiusDp(pressProgress);
        if (blur != lastBlur) {
            backdrop.setRenderEffect(blur > .001f
                    ? RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP) : null);
            lastBlur = blur;
        }
        // Blur the backdrop before refraction, preserving the original RGB without a tint.
        Canvas glassRecording = background.beginRecording(width, height);
        try {
            glassRecording.drawRenderNode(backdrop);
        } finally {
            background.endRecording();
        }
        if (!effect.isConfigurationCurrent()) rebuildEffect();
        effect.ensureShaders();
        float radius = thumb.height() / 2f;
        effect.update(0, 0, width, height, radius, radius, radius, radius, 0, 0, 1.5f, Color.TRANSPARENT);
        clip.reset();
        clip.addRoundRect(thumb, radius, radius, Path.Direction.CW);
        canvas.save();
        canvas.clipPath(clip);
        canvas.translate(thumb.left, thumb.top);
        canvas.drawRenderNode(background);
        effect.drawHighlight(canvas, pressProgress);
        canvas.restore();
    }
}
