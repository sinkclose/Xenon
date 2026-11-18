package org.telegram.ui.Components.blur3;

import android.graphics.RuntimeShader;
import org.telegram.messenger.R;

/** The existing iOS 27 material, evaluated on Glasmio's two-lobe menu field. */
@androidx.annotation.RequiresApi(33)
public final class GlasmioMenuGlassRenderer extends GlasmioGlassRenderer {
    private final android.graphics.RectF visible=new android.graphics.RectF();
    private org.telegram.ui.Components.GlasmioMenuField previousField;
    private int previousFieldGeneration = -1;
    public GlasmioMenuGlassRenderer() { super(0,R.raw.glasmio_menu_material); }
    @Override protected void configureVisibleBounds(float left,float top,float right,float bottom) {
        material.setFloatUniform("visibleBounds",visible.left,visible.top,visible.right,visible.bottom);
    }
    public void setMenuField(org.telegram.ui.Components.GlasmioMenuMotion frame,
                             org.telegram.ui.Components.GlasmioMenuField field, float left, float top) {
        if (previousField == field && previousFieldGeneration == field.generation()) return;
        previousField = field;
        previousFieldGeneration = field.generation();
        field.bind(material);
        android.graphics.RectF p=frame.bounds, s=frame.sourceLobe;
        visible.set(p); if(!frame.separate) visible.union(s); visible.offset(-left,-top);
        material.setFloatUniform("menuOrigin",p.left-left,p.top-top);
        material.setFloatUniform("center",p.centerX()-left,p.centerY()-top);
        material.setFloatUniform("halfSize",p.width()/2,p.height()/2);
        material.setFloatUniform("menuRadii",frame.radiusX,frame.radiusY);
        material.setFloatUniform("secondaryEnabled",frame.separate?0f:1f);
        material.setFloatUniform("secondaryCenter",s.centerX()-left,s.centerY()-top);
        material.setFloatUniform("secondaryHalfSize",s.width()/2,s.height()/2);
        material.setFloatUniform("secondaryRadii",frame.sourceRadius,frame.sourceRadius);
        material.setFloatUniform("smoothing",frame.smoothing);
        materialDirty=true;
    }

}
