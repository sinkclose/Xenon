package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.view.View;
import androidx.annotation.RequiresApi;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.messenger.FileLog;
import org.telegram.ui.Components.blur3.GlasmioMenuGlassRenderer;
import zxc.iconic.xenon.NekoConfig;

/** RenderNode adapter of Glasmio LiquidMenuMorphSurface's material and shared foreground portal.
 * Apache-2.0, revision 1a0652509071c36fedb35300abdfd12316cc3c54.
 */
@RequiresApi(33)
class GlasmioPopupRenderer {
    private final RenderNode backdrop=new RenderNode("MenuBackdrop"), sampled=new RenderNode("MenuSample"),
            rows=new RenderNode("MenuRows"), portal=new RenderNode("MenuPortal"),
            materialMask=new RenderNode("MenuMaterialMask"), material26=new RenderNode("Menu26Material");
    private final GlasmioMenuField field=new GlasmioMenuField();
    private final RuntimeShader portalShader=new RuntimeShader(AndroidUtilities.readRes(R.raw.glasmio_menu_portal));
    private final RuntimeShader materialMaskShader=new RuntimeShader(AndroidUtilities.readRes(R.raw.glasmio_menu_portal));
    private RuntimeShader highlight26, optics26;
    private GlasmioMenuGlassRenderer material27;
    private boolean material27Failed;
    private final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF viewport=new RectF();
    private final int[] location=new int[2];
    private static int captureDepth;
    private int offsetX, offsetY;
    private int sampledOffsetX, sampledOffsetY;
    private final float[] sampleTransform = {1, 1, 0, 0};
    private float backdropScale = 1;
    private long backdropCapturedAt;
    private View capturedHost;
    private long capturedVersion = Long.MIN_VALUE;
    private float sampledBlur = Float.NaN, rowsBlur = Float.NaN;
    private final RectF requiredViewport = new RectF();

    static boolean isCapturing() { return captureDepth>0; }
    GlasmioPopupRenderer(java.util.List<View> buttons, RectF bounds, NavigationHeaderButtons.MenuSource header) {
        // The morph copies only source geometry. The source controls stay live.
    }

    boolean captureBackdrop(View host,int[] windowLocation) {
        return captureBackdrop(host, windowLocation, false);
    }

    boolean captureBackdrop(View host, int[] windowLocation, boolean embedded) {
        if(captureDepth>0) return false;
        if (!org.telegram.messenger.SharedConfig.chatBlurEnabled()
                && !org.telegram.messenger.LiteMode.isEnabled(org.telegram.messenger.LiteMode.FLAG_LIQUID_GLASS)) return true;
        host.getLocationOnScreen(location);
        offsetX=location[0]-windowLocation[0]; offsetY=location[1]-windowLocation[1];
        backdropScale = embedded ? .5f : 1f;
        int width = Math.max(1, (int) Math.ceil(host.getWidth() * backdropScale));
        int height = Math.max(1, (int) Math.ceil(host.getHeight() * backdropScale));
        long now = System.nanoTime();
        long version = host instanceof IosPopupMorph.Backdrop
                ? ((IosPopupMorph.Backdrop) host).popupBackdropVersion() : -1;
        if (backdrop.hasDisplayList() && backdrop.getWidth() == width && backdrop.getHeight() == height
                && capturedHost == host && version >= 0 && capturedVersion == version) return true;
        // Backdrop capture traverses the entire host. Keep it independent of the
        // 60/120 Hz geometry animation, including menus in their own window.
        if (backdrop.hasDisplayList() && backdrop.getWidth() == width
                && backdrop.getHeight() == height && now - backdropCapturedAt < 33_000_000L) return false;
        backdropCapturedAt = now;
        capturedHost = host;
        capturedVersion = version;
        backdrop.setPosition(0, 0, width, height);
        Canvas c = backdrop.beginRecording();
        captureDepth++;
        try {
            c.scale(backdropScale, backdropScale);
            if (host instanceof IosPopupMorph.Backdrop) {
                ((IosPopupMorph.Backdrop) host).drawPopupBackdrop(c);
            } else host.draw(c);
        } finally {
            captureDepth--;
            backdrop.endRecording();
        }
        return true;
    }
    private void bind(RuntimeShader shader,GlasmioMenuMotion f,float left,float top) {
        field.bind(shader);
        shader.setFloatUniform("center",f.bounds.centerX()-left,f.bounds.centerY()-top);
        shader.setFloatUniform("halfSize",f.bounds.width()/2,f.bounds.height()/2);
        shader.setFloatUniform("menuRadii",f.radiusX,f.radiusY);
        shader.setFloatUniform("secondaryEnabled",f.separate?0f:1f);
        shader.setFloatUniform("secondaryCenter",f.sourceLobe.centerX()-left,f.sourceLobe.centerY()-top);
        shader.setFloatUniform("secondaryHalfSize",f.sourceLobe.width()/2,f.sourceLobe.height()/2);
        shader.setFloatUniform("secondaryRadii",f.sourceRadius,f.sourceRadius);
        shader.setFloatUniform("smoothing",f.smoothing);
    }
    void draw(Canvas canvas,View content,float contentX,float contentY,GlasmioMenuMotion f,RectF target,int tint,IosPopupInteraction interaction) {
        int group=f.separate?canvas.saveLayerAlpha(null,Math.round(255*f.contentProgress)):canvas.save();
        float guard=AndroidUtilities.dp(92);
        RectF b=f.bounds,s=f.sourceLobe;
        updateViewport(f, target, guard);
        int left=(int)Math.floor(viewport.left),top=(int)Math.floor(viewport.top),
                w=Math.max(1,(int)Math.ceil(viewport.right)-left),h=Math.max(1,(int)Math.ceil(viewport.bottom)-top);
        field.update(f,left,top);
        boolean blurEnabled = org.telegram.messenger.SharedConfig.chatBlurEnabled();
        boolean glassEnabled = org.telegram.messenger.LiteMode.isEnabled(org.telegram.messenger.LiteMode.FLAG_LIQUID_GLASS);
        boolean ios27 = glassEnabled && NekoConfig.ios27LiquidGlass && !material27Failed;
        int sampleX = offsetX - left, sampleY = offsetY - top;
        Canvas c;
        float scaleX = interaction.scale(target.width(), target.height(), true);
        float scaleY = interaction.scale(target.width(), target.height(), false);
        float originX = interaction.untransform(0, target, true) + left / scaleX - left;
        float originY = interaction.untransform(0, target, false) + top / scaleY - top;
        if (!sampled.hasDisplayList() || sampled.getWidth() != w || sampled.getHeight() != h
                || sampledOffsetX != sampleX || sampledOffsetY != sampleY || sampleTransform[0] != scaleX
                || sampleTransform[1] != scaleY || sampleTransform[2] != originX || sampleTransform[3] != originY) {
            sampled.setPosition(0, 0, w, h);
            c = sampled.beginRecording();
            c.translate(originX, originY);
            c.scale(1 / scaleX, 1 / scaleY);
            c.translate(sampleX, sampleY);
            c.scale(1 / backdropScale, 1 / backdropScale);
            c.drawRenderNode(backdrop);
            sampled.endRecording();
            sampledOffsetX = sampleX;
            sampledOffsetY = sampleY;
            sampleTransform[0] = scaleX;
            sampleTransform[1] = scaleY;
            sampleTransform[2] = originX;
            sampleTransform[3] = originY;
        }
        RenderNode material = null;
        if (ios27) {
            try {
                setSampledBlur(0);
                if (material27 == null) material27 = new GlasmioMenuGlassRenderer();
                material27.setTint(tint);
                material27.setMenuField(f, field, left, top);
                material = material27.update(sampled, b.left-left, b.top-top, b.width(), b.height(),
                        f.radiusX, f.radiusX, f.radiusX, f.radiusX);
            } catch (RuntimeException e) {
                FileLog.e(e);
                if (material27 != null) material27.release();
                material27 = null;
                material27Failed = true;
                ios27 = false;
            }
        }
        if (!glassEnabled) {
            if (material27 != null) { material27.release(); material27 = null; }
            // A disabled lens must not leave refraction, glare or a glass tint behind.
            setSampledBlur(blurEnabled ? AndroidUtilities.dpf2(NekoConfig.blurStrength * 4f / 3f) : 0);
            material26.setPosition(0, 0, w, h);
            c = material26.beginRecording();
            if (blurEnabled) c.drawRenderNode(sampled);
            paint.setShader(null);
            paint.setColor(tint);
            paint.setAlpha(blurEnabled ? 194 : 235);
            c.drawRect(0, 0, w, h, paint);
            material26.endRecording();
            material26.setRenderEffect(null);
            material = material26;
        }
        if (material == null) {
            // iOS 27 never needs the two iOS 26 programs on its opening frame.
            if (optics26 == null) optics26 = new RuntimeShader(AndroidUtilities.readRes(R.raw.glasmio_menu_26));
            if (highlight26 == null) highlight26 = new RuntimeShader(AndroidUtilities.readRes(R.raw.glasmio_menu_highlight26));
            setSampledBlur(NekoConfig.getGlassBlurRadiusDp() * AndroidUtilities.density);
            material26.setPosition(0,0,w,h);
            c=material26.beginRecording(); c.drawRenderNode(sampled);
            paint.setColor(tint);
            paint.setAlpha(Math.round(255*.6f*NekoConfig.getGlassTintPercent()/100f));
            c.drawRect(0,0,w,h,paint); material26.endRecording();
            bind(optics26,f,left,top);
            optics26.setFloatUniform("resolution",(float)w,(float)h);
            optics26.setFloatUniform("size",b.width(),b.height());
            optics26.setFloatUniform("radius",f.radiusX,f.radiusX,f.radiusX,f.radiusX);
            optics26.setFloatUniform("refractionHeight",16*AndroidUtilities.density*NekoConfig.getGlassRefraction());
            optics26.setFloatUniform("refractionAmount",-32*AndroidUtilities.density*NekoConfig.getGlassRefraction());
            optics26.setFloatUniform("depthEffect",0f);
            optics26.setFloatUniform("chromaticAberration",NekoConfig.getGlassDispersion());
            material26.setRenderEffect(RenderEffect.createRuntimeShaderEffect(optics26,"img"));
            material=material26;
        }
        if (ios27 && f.lensHeight <= .01f && !f.separate) {
            int materialSave = canvas.save();
            canvas.translate(left, top);
            canvas.clipRect(0, 0, w, h);
            canvas.drawRenderNode(material);
            canvas.restoreToCount(materialSave);
        } else {
            materialMask.setPosition(0,0,w,h);
            c=materialMask.beginRecording(); c.drawRenderNode(material); materialMask.endRecording();
            bind(materialMaskShader,f,left,top);
            materialMaskShader.setFloatUniform("contentOrigin",0f,0f);
            materialMaskShader.setFloatUniform("drawingBounds",0f,0f,(float)w,(float)h);
            materialMaskShader.setFloatUniform("preserveCoverage", ios27 && !f.separate ? 1f : 0f);
            materialMaskShader.setFloatUniform("height",glassEnabled ? f.lensHeight : 0f);
            materialMaskShader.setFloatUniform("amount",glassEnabled ? -f.lensAmount : 0f);
            materialMask.setRenderEffect(RenderEffect.createRuntimeShaderEffect(materialMaskShader,"content"));
            int materialSave=canvas.save(); canvas.translate(left,top); canvas.drawRenderNode(materialMask); canvas.restoreToCount(materialSave);
        }
        if (glassEnabled && !ios27) {
            bind(highlight26,f,left,top);
            highlight26.setFloatUniform("lightAngle",(float)Math.toRadians(NekoConfig.getGlassGlareAngle()));
            highlight26.setFloatUniform("lightFalloff",Math.max(.1f,NekoConfig.advancedGlassGlare));
            highlight26.setFloatUniform("pixelScale",AndroidUtilities.density);
            paint.setShader(highlight26); paint.setBlendMode(android.graphics.BlendMode.PLUS); paint.setAlpha(42);
            int light=canvas.save(); canvas.translate(left,top); canvas.drawRect(0,0,w,h,paint); canvas.restoreToCount(light);
            paint.setShader(null); paint.setBlendMode(android.graphics.BlendMode.SRC_OVER);
        }
        // The foreground has independent geometry, opacity and blur before the shared portal.
        rows.setPosition(0,0,content.getWidth(),content.getHeight());
        rows.setClipToBounds(false);
        c=rows.beginRecording();
        interaction.drawFeedback(c, target.width());
        IosPopupMorph.drawMenuContent(content,c);
        rows.endRecording(); rows.setAlpha(f.separate?1:f.contentProgress);
        float contentBlur = blurEnabled ? f.contentBlur : 0;
        if (rowsBlur != contentBlur) {
            rows.setRenderEffect(contentBlur>.01f?RenderEffect.createBlurEffect(contentBlur,contentBlur,Shader.TileMode.CLAMP):null);
            rowsBlur = contentBlur;
        }
        // The portal only needs the current silhouette plus the support of its
        // displacement and Gaussian blur. The backdrop atlas remains stable.
        float portalGuard = Math.abs(f.lensAmount) + 4 * f.portalBlur + f.smoothing + 2;
        int portalLeft = Math.max(0, (int) Math.floor(Math.min(b.left, s.left) - left - portalGuard));
        int portalTop = Math.max(0, (int) Math.floor(Math.min(b.top, s.top) - top - portalGuard));
        int portalRight = Math.min(w, (int) Math.ceil(Math.max(b.right, s.right) - left + portalGuard));
        int portalBottom = Math.min(h, (int) Math.ceil(Math.max(b.bottom, s.bottom) - top + portalGuard));
        portal.setPosition(portalLeft, portalTop, Math.max(portalLeft + 1, portalRight),
                Math.max(portalTop + 1, portalBottom));
        c = portal.beginRecording();
        c.translate(-portalLeft, -portalTop);
        int rowSave=c.save();
        c.translate(b.centerX()-left,b.centerY()-top);
        c.scale(b.width()/target.width()*f.contentZoom,b.height()/target.height()*f.contentZoom);
        c.translate(contentX-target.centerX(),contentY-target.centerY());
        c.drawRenderNode(rows); c.restoreToCount(rowSave);
        portal.endRecording();
        bind(portalShader,f,left,top);
        portalShader.setFloatUniform("contentOrigin",(float) portalLeft,(float) portalTop);
        portalShader.setFloatUniform("drawingBounds",0f,0f,(float)w,(float)h);
        portalShader.setFloatUniform("preserveCoverage", 0f);
        portalShader.setFloatUniform("height",glassEnabled ? f.lensHeight : 0f);
        portalShader.setFloatUniform("amount",glassEnabled ? f.lensAmount : 0f);
        RenderEffect warp=RenderEffect.createRuntimeShaderEffect(portalShader,"content");
        portal.setRenderEffect(blurEnabled && f.portalBlur>.01f?RenderEffect.createChainEffect(warp,
                RenderEffect.createBlurEffect(f.portalBlur,f.portalBlur,Shader.TileMode.DECAL)):warp);
        int save=canvas.save(); canvas.translate(left,top); canvas.drawRenderNode(portal); canvas.restoreToCount(save);
        canvas.restoreToCount(group);
    }
    private void updateViewport(GlasmioMenuMotion frame, RectF target, float guard) {
        // Reserve the final menu on the first frame, rather than reallocating
        // every blur/material RenderNode as the opening spring grows.
        requiredViewport.set(target);
        requiredViewport.union(frame.bounds);
        requiredViewport.union(frame.sourceLobe);
        requiredViewport.inset(-guard, -guard);
        if (viewport.isEmpty()) viewport.set(requiredViewport);
        else viewport.union(requiredViewport);
        // Rare overshoot beyond the reserved band grows in chunks, without clipping.
        float quantum = Math.max(1, AndroidUtilities.dp(64));
        viewport.set((float) Math.floor(viewport.left / quantum) * quantum,
                (float) Math.floor(viewport.top / quantum) * quantum,
                (float) Math.ceil(viewport.right / quantum) * quantum,
                (float) Math.ceil(viewport.bottom / quantum) * quantum);
    }

    private void setSampledBlur(float blur) {
        if (sampledBlur == blur) return;
        sampled.setRenderEffect(blur > .01f ? RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP) : null);
        sampledBlur = blur;
    }

    void release() {
        for(RenderNode node:new RenderNode[]{backdrop,sampled,rows,portal,materialMask,material26}) {
            node.setRenderEffect(null); node.discardDisplayList();
        }
        if (material27 != null) material27.release();
    }
}
