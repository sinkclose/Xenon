package zxc.iconic.xenon.osu;

import android.graphics.*;

/** Font Awesome Free 5.15.4 chevron-right, used by lazer's SpriteIcon.
 * Icon: CC BY 4.0, Fonticons, Inc. https://fontawesome.com/license/free */
final class OsuLazerChevron {
    private final Path shape=new Path();
    OsuLazerChevron(){
        shape.moveTo(285.476f,272.971f);shape.lineTo(91.132f,467.314f);
        shape.cubicTo(81.759f,476.687f,66.563f,476.687f,57.191f,467.314f);
        shape.lineTo(34.524f,444.647f);shape.cubicTo(25.167f,435.290f,25.149f,420.125f,34.484f,410.746f);
        shape.lineTo(188.505f,256);shape.lineTo(34.484f,101.255f);
        shape.cubicTo(25.149f,91.876f,25.167f,76.711f,34.524f,67.354f);
        shape.lineTo(57.191f,44.687f);shape.cubicTo(66.564f,35.314f,81.760f,35.314f,91.132f,44.687f);
        shape.lineTo(285.475f,239.030f);shape.cubicTo(294.848f,248.402f,294.848f,263.598f,285.476f,272.971f);shape.close();
    }
    void draw(Canvas c,Paint paint,float x,float y,float size,boolean left){
        c.save();c.translate(x,y);c.scale((left?-1:1)*size/512,size/512);c.translate(-160,-256);c.drawPath(shape,paint);c.restore();
    }
}
