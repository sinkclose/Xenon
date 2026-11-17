package org.telegram.ui.Components.blur3;

import android.graphics.Canvas;
import android.graphics.RenderEffect;
import android.graphics.RenderNode;
import android.graphics.Shader;
import androidx.annotation.RequiresApi;

/** Sharp source and two independent cached Gaussian planes; no recursive shader taps. */
@RequiresApi(33)
final class GlasmioBlurPlanes {
    private final RenderNode packed = new RenderNode("GlasmioSourcePlanes");
    private final RenderNode[] blur = new RenderNode[2];
    private final float[] scales = new float[2];
    private final int[] widths = new int[2], heights = new int[2];
    private final float[] origins = new float[4];
    private RenderNode source;
    private int sourceWidth, sourceHeight, sourceLeft, sourceTop;
    private float mainSigma = Float.NaN, fillSigma = Float.NaN;
    private boolean active;
    private int generation;

    RenderNode update(RenderNode input, float main, float fill, boolean enabled) {
        enabled &= main > .001f && fill > .001f;
        boolean changed = !hasDisplayList() || source != input || sourceWidth != input.getWidth()
                || sourceHeight != input.getHeight() || sourceLeft != input.getLeft() || sourceTop != input.getTop()
                || mainSigma != main || fillSigma != fill || active != enabled;
        if (!changed) return packed;
        source = input; sourceWidth = input.getWidth(); sourceHeight = input.getHeight();
        sourceLeft = input.getLeft(); sourceTop = input.getTop();
        mainSigma = main; fillSigma = fill; active = enabled;
        int x = sourceWidth + 2;
        if (enabled) {
            for (int i = 0; i < 2; i++) {
                float sigma = i == 0 ? main : fill;
                float scale = .5f;
                while (scale > 1f / 128f && sigma * scale > 16f) scale *= .5f;
                scales[i] = scale;
                widths[i] = Math.max(1, (int) Math.ceil(sourceWidth * scale));
                heights[i] = Math.max(1, (int) Math.ceil(sourceHeight * scale));
                if (blur[i] == null) {
                    blur[i] = new RenderNode("GlasmioGaussian" + i);
                    blur[i].setUseCompositingLayer(true, null);
                }
                blur[i].setPosition(0, 0, widths[i], heights[i]);
                Canvas c = blur[i].beginRecording();
                c.scale(scale, scale); c.translate(-sourceLeft, -sourceTop);
                c.drawRenderNode(input); blur[i].endRecording();
                blur[i].setRenderEffect(RenderEffect.createBlurEffect(sigma * scale, sigma * scale, Shader.TileMode.CLAMP));
                origins[i * 2] = x; origins[i * 2 + 1] = 0f;
                x += widths[i] + 2;
            }
        } else {
            for (RenderNode node : blur) if (node != null) node.discardDisplayList();
            x = sourceWidth;
        }
        packed.setPosition(0, 0, x, sourceHeight);
        Canvas c = packed.beginRecording();
        c.save(); c.translate(-sourceLeft, -sourceTop); c.drawRenderNode(input); c.restore();
        if (enabled) for (int i = 0; i < 2; i++) {
            c.save(); c.translate(origins[i * 2], origins[i * 2 + 1]);
            c.clipRect(0, 0, widths[i], heights[i]);
            c.drawRenderNode(blur[i]); c.restore();
        }
        packed.endRecording(); generation++;
        return packed;
    }

    int generation() { return generation; }
    float scale(int plane) { return active ? scales[plane] : 1f; }
    float x(int plane) { return active ? origins[plane * 2] : 0f; }
    float y(int plane) { return active ? origins[plane * 2 + 1] : 0f; }
    int width(int plane) { return active ? widths[plane] : Math.max(1, sourceWidth); }
    int height(int plane) { return active ? heights[plane] : Math.max(1, sourceHeight); }

    boolean hasDisplayList() {
        if (source == null || !source.hasDisplayList() || !packed.hasDisplayList()) return false;
        if (active) for (RenderNode node : blur) if (node == null || !node.hasDisplayList()) return false;
        return true;
    }

    void release() {
        packed.discardDisplayList();
        for (RenderNode node : blur) if (node != null) node.discardDisplayList();
        source = null;
    }
}
