package org.telegram.ui.Components.blur3;

import android.graphics.Canvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.RuntimeShader;
import androidx.annotation.RequiresApi;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import zxc.iconic.xenon.NekoConfig;
import java.util.Arrays;

/** Glasmio face/highlight transfer on Xenon's cached iOS 26 backdrop pipeline.
 * Source: NEORUAA/Glasmio 1a0652509071c36fedb35300abdfd12316cc3c54 (Apache-2.0).
 * Two cached Gaussian planes approximate native mip widths; AGSL retains native face composition.
 */
@RequiresApi(33)
public class GlasmioGlassRenderer {
    private final RenderNode atlas = new RenderNode("GlasmioMaterial");
    // Clips the material to the sharp region so the shader skips the packed blur planes.
    private final RenderNode output = new RenderNode("GlasmioOutput");
    // The material is transparent beyond its visible shapes and shadow guard.
    private final android.graphics.RectF visibleBounds = new android.graphics.RectF();
    private final android.graphics.Rect outputClip = new android.graphics.Rect();
    private final android.graphics.Rect nextOutputClip = new android.graphics.Rect();
    private float shadowGuard;
    private final GlasmioBlurPlanes planes = new GlasmioBlurPlanes();
    private int previousPlanesGeneration = -1;
    protected final RuntimeShader material;
    private final boolean unionRenderer;
    private boolean clearControlLens;

    /** Local bottom-bar optics, independent of the global material/refraction sliders. */
    public void setClearControlLens(boolean enabled) {
        if (clearControlLens == enabled) return;
        clearControlLens = enabled;
        materialDirty = true;
    }
    private GlasmioPressEffect pressEffect;
    private final android.graphics.RectF pressBounds = new android.graphics.RectF();
    private float pressX, pressY, pressProgress, dissipation;
    protected boolean materialDirty = true;
    private boolean pressDirty = true;
    private final float[][] unionState = new float[7][];
    private int previousTintColor;
    private float previousTintAlpha = Float.NaN;
    private final float[] geometry = new float[15];
    private final float[] previousGeometry = new float[15];
    private RenderNode previousSource;
    private int previousSourceW, previousSourceH, previousSourceLeft, previousSourceTop;
    private int previousRevision = -1;

    public GlasmioGlassRenderer() { this(0); }
    public GlasmioGlassRenderer(int shapeCount) {
        this(shapeCount,shapeCount == 0 ? R.raw.glasmio_fast_material : R.raw.glasmio_fast_union);
    }
    protected GlasmioGlassRenderer(int shapeCount,int shaderResource) {
        unionRenderer = shapeCount > 0;
        material = new RuntimeShader(AndroidUtilities.readRes(shaderResource)
                .replace("const int SHAPE_COUNT = 8;", "const int SHAPE_COUNT = " + shapeCount + ";"));
        material.setColorUniform("colorSpaceProbe", android.graphics.Color.valueOf(.5f, .25f, .75f, 1f));
    }

    public void setTint(int color) {
        float alpha = Math.max(0f, Math.min(1f, NekoConfig.getGlassTintPercent() / 100f))
                * .6f * ((color >>> 24) / 255f);
        if (color == previousTintColor && alpha == previousTintAlpha) return;
        material.setFloatUniform("glassTint", ((color >> 16) & 255) / 255f * alpha,
                ((color >> 8) & 255) / 255f * alpha, (color & 255) / 255f * alpha, alpha);
        previousTintColor = color; previousTintAlpha = alpha;
        materialDirty = true;
    }

    public void setUnion(float[] shapes, float[] scales, float[] corners, float[] opacity, float[] merges, float[] glows, float[] tints) {
        unionUniform(0, "shapes", shapes);
        unionUniform(1, "shapeScales", scales);
        unionUniform(2, "unionCorners", corners);
        unionUniform(3, "opacity", opacity);
        unionUniform(4, "merges", merges);
        unionUniform(5, "unionGlows", glows);
        unionUniform(6, "unionTints", tints);
        if (materialDirty) {
            float left = Float.POSITIVE_INFINITY, top = left, right = Float.NEGATIVE_INFINITY, bottom = right;
            for (int i = 0; i < opacity.length; i++) {
                if (opacity[i] <= 0f) continue;
                int j = i * 4;
                left = Math.min(left, shapes[j] - shapes[j + 2]);
                top = Math.min(top, shapes[j + 1] - shapes[j + 3]);
                right = Math.max(right, shapes[j] + shapes[j + 2]);
                bottom = Math.max(bottom, shapes[j + 1] + shapes[j + 3]);
            }
            if (!Float.isFinite(left)) left = top = right = bottom = 0f;
            setVisibleBounds(left, top, right, bottom);
        }
    }

    private void unionUniform(int index, String name, float[] values) {
        if (Arrays.equals(unionState[index], values)) return;
        if (unionState[index] == null) unionState[index] = values.clone();
        else System.arraycopy(values, 0, unionState[index], 0, values.length);
        material.setFloatUniform(name, values);
        materialDirty = true;
    }

    public void setPress(float progress, float dissipation, float x, float y,
                         float left, float top, float right, float bottom) {
        if (pressProgress == progress && this.dissipation == dissipation && pressX == x && pressY == y
                && pressBounds.left == left && pressBounds.top == top && pressBounds.right == right && pressBounds.bottom == bottom) return;
        pressProgress = progress; this.dissipation = dissipation; pressX = x; pressY = y;
        pressBounds.set(left, top, right, bottom);
        pressDirty = true;
    }

    /** Cache source transport and blur effects; source display-list updates propagate through it. */
    public RenderNode update(RenderNode source, float left, float top, float width, float height,
                             float rLT, float rRT, float rRB, float rLB) {
        float density = AndroidUtilities.density;
        boolean dark = Theme.isCurrentThemeDark();
        float extent = Math.max(1f, Math.min(width, height) / density);
        float captureScale = Math.max(.25f, Math.min(.5f, 50f / extent));
        float radius = clearControlLens ? 0f : NekoConfig.getIos27GlassBlurRadiusDp();
        float mainRadius = radius * density * captureScale;
        float fillRadius = mainRadius * 1.6f;
        RenderNode packed = planes.update(source,
                GlasmioBlurProfile.sigmaPixels(radius, density, captureScale),
                GlasmioBlurProfile.sigmaPixels(radius * 1.6f, density, captureScale),
                !clearControlLens);
        geometry[0]=left; geometry[1]=top; geometry[2]=width; geometry[3]=height;
        geometry[4]=rLT; geometry[5]=rRT; geometry[6]=rRB; geometry[7]=rLB;
        geometry[8]=density; geometry[9]=dark ? 1f : 0f;
        geometry[10]=source.getWidth(); geometry[11]=source.getHeight();
        geometry[12]=NekoConfig.ios27GlassBlur; geometry[13]=NekoConfig.ios27GlassRefraction;
        geometry[14]=clearControlLens ? 1f : 0f;
        boolean geometryChanged = !Arrays.equals(geometry, previousGeometry)
                || previousRevision != NekoConfig.getIos27GlassRevision();
        boolean topologyChanged = !hasDisplayList() || previousSource != source
                || previousPlanesGeneration != planes.generation()
                || previousSourceW != source.getWidth() || previousSourceH != source.getHeight()
                || previousSourceLeft != source.getLeft() || previousSourceTop != source.getTop();
        if (!topologyChanged && !geometryChanged && !materialDirty && !pressDirty) {
            updateOutputClip(source.getWidth(), source.getHeight());
            return output;
        }
        if (topologyChanged) {
            atlas.setPosition(0, 0, packed.getWidth(), packed.getHeight());
            Canvas c = atlas.beginRecording();
            c.drawRenderNode(packed);
            atlas.endRecording();
            output.setPosition(source.getLeft(), source.getTop(),
                    source.getLeft()+source.getWidth(), source.getTop()+source.getHeight());
            c = output.beginRecording();
            c.drawRenderNode(atlas);
            output.endRecording();
            previousPlanesGeneration = planes.generation();
            previousSource=source; previousSourceW=source.getWidth(); previousSourceH=source.getHeight();
            previousSourceLeft=source.getLeft(); previousSourceTop=source.getTop();
        }
        if (geometryChanged || topologyChanged) {
            material.setFloatUniform("size",width,height);
            material.setFloatUniform("cornerRadii",rLT,rRT,rRB,rLB);
            material.setFloatUniform("glassOrigin",left,top);
            if (!unionRenderer) configureVisibleBounds(left,top,left+width,top+height);
            material.setFloatUniform("inputSize",(float)source.getWidth(),(float)source.getHeight());
            material.setFloatUniform("outputSize",(float)source.getWidth(),(float)source.getHeight());
            material.setFloatUniform("textureScale",1f);
            material.setFloatUniform("density",density);
            material.setFloatUniform("darkMode",dark?1f:0f);
            material.setFloatUniform("pointScale",density);
            material.setFloatUniform("mainPlane",planes.x(0),planes.y(0),(float)planes.width(0),(float)planes.height(0));
            material.setFloatUniform("fillPlane",planes.x(1),planes.y(1),(float)planes.width(1),(float)planes.height(1));
            material.setFloatUniform("planeScales",planes.scale(0),planes.scale(1));
            material.setFloatUniform("planeVariance",GlasmioBlurProfile.variance(GlasmioBlurProfile.lod(mainRadius)),
                    GlasmioBlurProfile.variance(GlasmioBlurProfile.lod(fillRadius)));
            material.setFloatUniform("nativeAtlasRadii",mainRadius,fillRadius);
            int blurPercent = NekoConfig.getGlassAlphaPercent();
            material.setFloatUniform("recipeOpacity", clearControlLens || blurPercent == 0 ? -1f : blurPercent / 100f);
            material.setFloatUniform("chromaticDispersion", (clearControlLens ? .65f : NekoConfig.getGlassDispersion()) * 6f * density);
            material.setFloatUniform("lightSpread", NekoConfig.ios27GlassGlare);
            material.setFloatUniform("solidGlare", NekoConfig.ios27GlassGlareMode == NekoConfig.GLASS_GLARE_SOLID ? 1f : 0f);
            material.setFloatUniform("nativeAtlasFillWeights",clearControlLens ? 0f : dark?.9f:0f,
                    clearControlLens ? 0f : dark?0f:.9f,clearControlLens ? 0f : .5f);
            float sizeProgress=Math.max(0f,Math.min(1f,(extent-64f)/96f));
            material.setFloatUniform("nativeAtlasBlurParameters",-extent*.5f,.8f*sizeProgress,.4f*sizeProgress);
            float shadowExtent = clearControlLens ? 0f : 4f+20f*sizeProgress;
            shadowGuard = Math.max(2f * shadowExtent, 2f) * density;
            material.setFloatUniform("shadowProfile",shadowExtent,
                    clearControlLens ? 0f : .04f+.56f*sizeProgress);
            float refraction=(clearControlLens ? .5f : NekoConfig.ios27GlassRefraction)/.7f;
            material.setFloatUniform("nativeAtlasLensParameters",
                    1f/Math.max(.001f,(clearControlLens ? 8f : Math.min(extent*.25f,20f))*density),
                    clearControlLens ? -16f*density : -Math.min(extent*.5f,60f)*density*refraction,
                    1f/Math.max(.001f,Math.max(16f,extent*.2f)*density),
                    Math.max(16f,extent*.25f)*density*refraction);
            material.setFloatUniform("nativeAtlasOuterOpacity",clearControlLens ? 0f : .6f);
            material.setFloatUniform("materialOpacity",1f);
            material.setFloatUniform("face",clearControlLens ? 1f : dark?1.3f:1.2f,
                    clearControlLens ? 0f : dark?.125f:.4f,clearControlLens ? 1f : dark?1.125f:1.03f,1f);
            material.setFloatUniform("faceFill",1f,1f,1f,clearControlLens ? 0f : dark?0f:.2f);
            material.setFloatUniform("luminanceRollOff",clearControlLens ? 0f : dark?(extent>128f?.65f:.4f):0f);
            material.setFloatUniform("fillCount",0f);
            material.setFloatUniform("edgeOpacity",NekoConfig.getIos27GlassLightIntensity()/.25f);
            material.setFloatUniform("topHighlight",NekoConfig.getIos27GlassLightIntensity()/.25f);
            material.setFloatUniform("lightAngle",(float)Math.toRadians(NekoConfig.ios27GlassGlareAngle));
            configureVibrancy(material);
            System.arraycopy(geometry,0,previousGeometry,0,geometry.length);
            previousRevision=NekoConfig.getIos27GlassRevision();
        }
        RenderEffect completed=RenderEffect.createRuntimeShaderEffect(material,"content");
        if (android.os.Build.VERSION.SDK_INT<36 && pressProgress>0f && !pressBounds.isEmpty()) {
            if (pressEffect==null) pressEffect=new GlasmioPressEffect();
            completed=RenderEffect.createChainEffect(pressEffect.effect(pressBounds,pressX,pressY,pressProgress,dissipation),completed);
        }
        atlas.setRenderEffect(completed);
        materialDirty=pressDirty=false;
        updateOutputClip(source.getWidth(), source.getHeight());
        return output;
    }

    protected void configureVisibleBounds(float left,float top,float right,float bottom) {
        setVisibleBounds(left,top,right,bottom);
    }

    protected final void setVisibleBounds(float left,float top,float right,float bottom) {
        material.setFloatUniform("visibleBounds",left,top,right,bottom);
        visibleBounds.set(left,top,right,bottom);
    }

    /** Matches the shader's early-out bounds; the material is transparent outside it. */
    private void updateOutputClip(int width, int height) {
        float bottomGuard = shadowGuard + 8f * AndroidUtilities.density;
        nextOutputClip.set(Math.max(0, (int) Math.floor(visibleBounds.left - shadowGuard) - 1),
                Math.max(0, (int) Math.floor(visibleBounds.top - shadowGuard) - 1),
                Math.min(width, (int) Math.ceil(visibleBounds.right + shadowGuard) + 1),
                Math.min(height, (int) Math.ceil(visibleBounds.bottom + bottomGuard) + 1));
        if (nextOutputClip.isEmpty()) nextOutputClip.set(0, 0, 1, 1);
        if (nextOutputClip.equals(outputClip)) return;
        outputClip.set(nextOutputClip);
        output.setClipRect(outputClip);
    }

    /** Region of the returned node that can contain material pixels, in its local coordinates. */
    public android.graphics.Rect getOutputClip() {
        return outputClip;
    }

    public static void configureVibrancy(RuntimeShader shader) {
        shader.setFloatUniform("nativeVibrantRow0", 1.1201999187f, -.1894000024f, -.0189999994f, 0f);
        shader.setFloatUniform("nativeVibrantRow1", -.0562999994f, .9870999455f, -.0190999992f, 0f);
        shader.setFloatUniform("nativeVibrantRow2", -.0562999994f, -.1893000007f, 1.1574000120f, 0f);
        shader.setFloatUniform("nativeVibrantRow3", 0f, 0f, 0f, 1f);
        shader.setFloatUniform("nativeVibrantBias", .1471000016f, .1471000016f, .1471000016f, 0f);
        shader.setFloatUniform("nativeVibrantClamp", 1.2f, 0f);
    }

    public boolean hasDisplayList() {
        return previousSource != null && previousSource.hasDisplayList() && planes.hasDisplayList() && atlas.hasDisplayList() && output.hasDisplayList();
    }

    public void release() {
        atlas.discardDisplayList(); output.discardDisplayList(); planes.release(); previousSource=null; materialDirty=true;
    }
}
