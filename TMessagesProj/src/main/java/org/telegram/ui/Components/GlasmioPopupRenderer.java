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
import org.telegram.ui.Components.blur3.GlassShaderCache;
import org.telegram.ui.Components.blur3.Ios27GlassMaterial;
import zxc.iconic.xenon.NekoConfig;

/** RenderNode adapter of Glasmio LiquidMenuMorphSurface's material and shared foreground portal.
 * Apache-2.0, revision 1a0652509071c36fedb35300abdfd12316cc3c54.
 */
@RequiresApi(33)
class GlasmioPopupRenderer {
    private final RenderNode backdrop=new RenderNode("MenuBackdrop"), sampled=new RenderNode("MenuSample"),
            rows=new RenderNode("MenuRows"), portal=new RenderNode("MenuPortal"),
            materialMask=new RenderNode("MenuMaterialMask"), materialSurface=new RenderNode("MenuMaterial"),
            filteredSample=new RenderNode("MenuBlurredSample");
    private final GlasmioMenuField field=new GlasmioMenuField();
    private RuntimeShader portalShader;
    private RuntimeShader materialMaskShader;
    private RuntimeShader highlight26, optics26;
    private RuntimeShader optics27;
    private final float[] ios27Lens = new float[4];
    private final Runnable onOpticsReady;
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

    static boolean isCapturing() { return captureDepth > 0; }

    GlasmioPopupRenderer(Runnable onOpticsReady) {
        this.onOpticsReady = onOpticsReady;
    }

    boolean hasPortalShaders() {
        return portalShader != null && materialMaskShader != null;
    }

    boolean preparePortalShaders() {
        if (portalShader == null) portalShader = GlassShaderCache.acquireMenuPortal(onOpticsReady);
        if (materialMaskShader == null) materialMaskShader = GlassShaderCache.acquireMenuPortal(onOpticsReady);
        return hasPortalShaders();
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
        boolean ios27 = glassEnabled && NekoConfig.ios27LiquidGlass;
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
        if (glassEnabled) {
            if (ios27 && optics27 == null) optics27 = GlassShaderCache.acquireMenuRefraction(true, onOpticsReady);
            if (!ios27 && optics26 == null) optics26 = GlassShaderCache.acquireMenuRefraction(false, onOpticsReady);
        }
        RuntimeShader optics = ios27 ? optics27 : optics26;
        boolean optical = glassEnabled && optics != null;
        float blur = glassEnabled ? AndroidUtilities.dpf2(NekoConfig.getGlassBlurRadiusDp())
                : blurEnabled ? AndroidUtilities.dpf2(NekoConfig.blurStrength * 4f / 3f) : 0f;
        float blurOpacity = ios27 ? NekoConfig.getIos27GlassBlurOpacity() : 1f;
        boolean blurPlane = optical && ios27 && blur > .01f;
        if (blur > .01f) prepareFilteredSample(w, h, blur, blurPlane ? 1f : blurOpacity);
        materialSurface.setPosition(0, 0, blurPlane ? Ios27GlassMaterial.packedBackdropWidth(w) : w, h);
        c = materialSurface.beginRecording();
        if (blurEnabled || glassEnabled) {
            if (blurPlane || blur <= .01f || blurOpacity < 1f) c.drawRenderNode(sampled);
            if (blurPlane) {
                int filtered = c.save();
                c.translate(w + 2, 0);
                c.scale(.5f, .5f);
                c.drawRenderNode(filteredSample);
                c.restoreToCount(filtered);
            } else if (blur > .01f) {
                c.drawRenderNode(filteredSample);
            }
        }
        float tintStrength = NekoConfig.getGlassTintPercent() * .006f;
        if (!glassEnabled || !ios27 || !optical) {
            paint.setShader(null);
            paint.setColor(tint);
            paint.setAlpha(glassEnabled ? Math.round(255f * tintStrength) : blurEnabled ? 194 : 235);
            c.drawRect(0, 0, w, h, paint);
        }
        materialSurface.endRecording();
        if (optical) {
            bind(optics, f, left, top);
            optics.setFloatUniform("resolution", (float) w, (float) h);
            optics.setFloatUniform("size", b.width(), b.height());
            optics.setFloatUniform("radius", f.radiusX, f.radiusX, f.radiusX, f.radiusX);
            optics.setFloatUniform("refractionHeight", AndroidUtilities.dpf2(16) * NekoConfig.getGlassRefraction());
            optics.setFloatUniform("refractionAmount", -AndroidUtilities.dpf2(32) * NekoConfig.getGlassRefraction());
            optics.setFloatUniform("depthEffect", 0f);
            optics.setFloatUniform("chromaticAberration", NekoConfig.getGlassDispersion());
            if (ios27) {
                Ios27GlassMaterial.renderLens(b.width(), b.height(), AndroidUtilities.density,
                        NekoConfig.ios27GlassRefraction, false, ios27Lens, 0);
                optics.setFloatUniform("backdropBlur", blurPlane ? 1f : 0f);
                optics.setFloatUniform("blurMix", NekoConfig.ios27GlassAlpha == 0 ? -1f : blurOpacity);
                optics.setFloatUniform("lensParams", ios27Lens);
                optics.setFloatUniform("darkMode", org.telegram.ui.ActionBar.Theme.isCurrentThemeDark() ? 1f : 0f);
                optics.setFloatUniform("pixelScale", AndroidUtilities.density);
                optics.setFloatUniform("lightAngle", (float) Math.toRadians(NekoConfig.getGlassGlareAngle()));
                optics.setFloatUniform("lightIntensity", NekoConfig.getIos27GlassLightIntensity());
                optics.setFloatUniform("lightFalloff", NekoConfig.ios27GlassGlare);
                optics.setFloatUniform("solidGlare", NekoConfig.ios27GlassGlareMode == NekoConfig.GLASS_GLARE_SOLID ? 1f : 0f);
                optics.setFloatUniform("clearControl", 0f);
                float alpha = android.graphics.Color.alpha(tint) / 255f * tintStrength;
                optics.setFloatUniform("glassTint", android.graphics.Color.red(tint) / 255f * alpha,
                        android.graphics.Color.green(tint) / 255f * alpha, android.graphics.Color.blue(tint) / 255f * alpha, alpha);
            }
        }
        materialSurface.setRenderEffect(optical ? RenderEffect.createRuntimeShaderEffect(optics, "img") : null);
        materialMask.setPosition(0, 0, w, h);
        c = materialMask.beginRecording();
        c.drawRenderNode(materialSurface);
        materialMask.endRecording();
        bind(materialMaskShader, f, left, top);
        materialMaskShader.setFloatUniform("contentOrigin", 0f, 0f);
        materialMaskShader.setFloatUniform("drawingBounds", 0f, 0f, (float) w, (float) h);
        materialMaskShader.setFloatUniform("preserveCoverage", optical && ios27 ? 1f : 0f);
        materialMaskShader.setFloatUniform("height", glassEnabled ? f.lensHeight : 0f);
        materialMaskShader.setFloatUniform("amount", glassEnabled ? -f.lensAmount : 0f);
        materialMask.setRenderEffect(RenderEffect.createRuntimeShaderEffect(materialMaskShader, "content"));
        int materialSave = canvas.save();
        canvas.translate(left, top);
        canvas.drawRenderNode(materialMask);
        canvas.restoreToCount(materialSave);
        if (glassEnabled && !ios27 && highlight26 == null) {
            highlight26 = GlassShaderCache.acquireMenuHighlight26(onOpticsReady);
        }
        if (glassEnabled && !ios27 && highlight26 != null) {
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

    private void prepareFilteredSample(int width, int height, float blur, float opacity) {
        if (!filteredSample.hasDisplayList() || filteredSample.getWidth() != width
                || filteredSample.getHeight() != height) {
            filteredSample.setPosition(0, 0, width, height);
            Canvas canvas = filteredSample.beginRecording();
            canvas.drawRenderNode(sampled);
            filteredSample.endRecording();
        }
        if (sampledBlur != blur) {
            filteredSample.setRenderEffect(RenderEffect.createBlurEffect(blur, blur, Shader.TileMode.CLAMP));
            sampledBlur = blur;
        }
        filteredSample.setAlpha(opacity);
    }

    void release() {
        for(RenderNode node:new RenderNode[]{backdrop,sampled,filteredSample,rows,portal,materialMask,materialSurface}) {
            node.setRenderEffect(null); node.discardDisplayList();
        }
    }
}
