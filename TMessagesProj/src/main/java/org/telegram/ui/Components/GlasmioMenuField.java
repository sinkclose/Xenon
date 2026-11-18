package org.telegram.ui.Components;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.ColorSpace;
import android.graphics.RectF;
import android.graphics.RuntimeShader;
import android.graphics.Shader;
import android.util.Half;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Portable LiquidMenuContour rawSample + LiquidMenuGaussianField + field texture.
 * NEORUAA/Glasmio, Apache-2.0, revision 1a0652509071c36fedb35300abdfd12316cc3c54.
 * Signed distance and normals travel as unpremultiplied linear FP16 data.
 */
@androidx.annotation.RequiresApi(33)
public final class GlasmioMenuField {
    private final float[] weights = new float[17], a = new float[3], b = new float[3];
    private float[] raw = new float[0], horizontal = new float[0], rgba = new float[0];
    private BitmapShader texture;
    private final BitmapShader empty = new BitmapShader(Bitmap.createBitmap(1,1,Bitmap.Config.ARGB_8888),Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
    private ByteBuffer upload;
    private float originX, originY, step = 1;
    private int width = 1, height = 1;
    private final RectF exterior = new RectF();
    private final float[] key = new float[14], previous = new float[14];
    private boolean valid;
    private int generation;

    public int generation() { return generation; }
    private boolean useNative = true;

    @androidx.annotation.Keep
    private native boolean buildNative(float[] geometry, float x, float y, float spacing,
                                       int columns, int rows, float[] weights, int radius, float[] result);
    @androidx.annotation.Keep
    private native boolean packNative(float[] values, int columns, int rows, int rowBytes, ByteBuffer target);

    public void update(GlasmioMenuMotion f, float offsetX, float offsetY) {
        RectF p=f.bounds,s=f.sourceLobe;
        key[0]=p.left-offsetX; key[1]=p.top-offsetY; key[2]=p.width(); key[3]=p.height(); key[4]=f.radiusX; key[5]=f.radiusY;
        key[6]=s.left-offsetX; key[7]=s.top-offsetY; key[8]=s.width(); key[9]=s.height(); key[10]=key[11]=f.sourceRadius;
        key[12]=f.smoothing; key[13]=f.separate?0:1;
        if (valid && java.util.Arrays.equals(key,previous)) return;
        System.arraycopy(key,0,previous,0,key.length); valid=true; generation++;
        float aspect=Math.max(f.radiusX,f.radiusY)/Math.max(.01f,Math.min(f.radiusX,f.radiusY));
        float guard=f.smoothing*.25f*Math.max(1,aspect)+.001f;
        exterior.set(Math.min(key[0],key[6])-guard, Math.min(key[1],key[7])-guard,
                Math.max(key[0]+key[2],key[6]+key[8])+guard,Math.max(key[1]+key[3],key[7]+key[9])+guard);
        float sigma=Math.max(0,f.smoothing);
        if (sigma<=.5f) {
            texture=null;
            // Keep working storage for a reversal instead of allocating it again.
            return;
        }
        step=Math.max(2,sigma/4);
        float local=sigma/step,sum=0;
        int radius=Math.min(16,(int)Math.ceil(local*4));
        for(int i=0;i<=radius;i++) { weights[i]=(float)Math.exp(-i*i/(2*local*local)); sum+=weights[i]*(i==0?1:2); }
        for(int i=0;i<=radius;i++) weights[i]/=sum;
        while(radius>0 && weights[radius]<.002f) radius--;
        if(radius==0) { texture=null; return; }
        sum=weights[0]; for(int i=1;i<=radius;i++) sum+=weights[i]*2;
        for(int i=0;i<=radius;i++) weights[i]/=sum;
        originX=exterior.left-step; originY=exterior.top-step;
        width=Math.max(2,(int)Math.ceil(exterior.width()/step)+1)+2;
        height=Math.max(2,(int)Math.ceil(exterior.height()/step)+1)+2;
        if (rgba.length < width * height * 4) rgba = new float[width * height * 4];
        boolean built = false;
        if (useNative) {
            try {
                built = buildNative(key, originX, originY, step, width, height, weights, radius, rgba);
            } catch (UnsatisfiedLinkError ignored) {
                useNative = false;
            }
        }
        if (!built) buildInJava(radius, sigma);
        Bitmap bitmap=Bitmap.createBitmap(width,height,Bitmap.Config.RGBA_F16,false,ColorSpace.get(ColorSpace.Named.LINEAR_EXTENDED_SRGB));
        bitmap.setPremultiplied(false);
        if(upload==null || upload.capacity()<bitmap.getByteCount()) upload=ByteBuffer.allocateDirect(bitmap.getByteCount()).order(ByteOrder.nativeOrder());
        upload.clear();
        if (!useNative || !packNative(rgba, width, height, bitmap.getRowBytes(), upload)) {
            for (int row = 0; row < height; row++) {
                upload.position(row * bitmap.getRowBytes());
                for (int i = row * width * 4; i < (row + 1) * width * 4; i++) {
                    upload.putShort(Half.toHalf(rgba[i]));
                }
            }
        }
        upload.rewind(); bitmap.copyPixelsFromBuffer(upload);
        texture=new BitmapShader(bitmap,Shader.TileMode.CLAMP,Shader.TileMode.CLAMP);
        texture.setFilterMode(BitmapShader.FILTER_MODE_LINEAR);
    }

    private void buildInJava(int radius, float sigma) {
        int pw=width+radius*2, ph=height+radius*2;
        if(raw.length<pw*ph*3) raw=new float[pw*ph*3];
        if(horizontal.length<width*ph*3) horizontal=new float[width*ph*3];
        Rounded primary=new Rounded(key,0), secondary=new Rounded(key,6);
        for(int row=0;row<ph;row++) for(int col=0;col<pw;col++) {
            float x=originX+(col-radius)*step,y=originY+(row-radius)*step;
            primary.sample(x,y,a); secondary.sample(x,y,b);
            int o=(row*pw+col)*3;
            if(Math.abs(a[0]-b[0])>=sigma) {
                float[] nearest=a[0]<=b[0]?a:b;
                System.arraycopy(nearest,0,raw,o,3); continue;
            }
            float blend=sigma*clamp(.5f-.5f*(a[1]*b[1]+a[2]*b[2]));
            float weight=blend<=.000001f ? (a[0]<=b[0]?0:1) : clamp(.5f+.5f*(a[0]-b[0])/blend);
            raw[o]=a[0]+(b[0]-a[0])*weight-blend*weight*(1-weight);
            raw[o+1]=a[1]+(b[1]-a[1])*weight; raw[o+2]=a[2]+(b[2]-a[2])*weight;
        }
        for(int row=0;row<ph;row++) for(int col=0;col<width;col++) {
            int c=(row*pw+col+radius)*3,o=(row*width+col)*3;
            for(int j=0;j<3;j++) {
                float v=raw[c+j]*weights[0];
                for(int i=1;i<=radius;i++) v+=(raw[c-i*3+j]+raw[c+i*3+j])*weights[i];
                horizontal[o+j]=v;
            }
        }
        for(int row=0;row<height;row++) for(int col=0;col<width;col++) {
            int c=((row+radius)*width+col)*3,o=(row*width+col)*4;
            for(int j=0;j<3;j++) {
                float v=horizontal[c+j]*weights[0];
                for(int i=1;i<=radius;i++) v+=(horizontal[c-i*width*3+j]+horizontal[c+i*width*3+j])*weights[i];
                rgba[o+j]=v;
            }
            float len=(float)Math.sqrt(rgba[o+1]*rgba[o+1]+rgba[o+2]*rgba[o+2]);
            rgba[o+1]=len>.000001f?rgba[o+1]/len:0; rgba[o+2]=len>.000001f?rgba[o+2]/len:0; rgba[o+3]=1;
        }
    }

    public void bind(RuntimeShader shader) {
        shader.setInputBuffer("smoothedField",texture!=null?texture:empty);
        shader.setFloatUniform("smoothedFieldEnabled",texture!=null?1:0);
        shader.setFloatUniform("fieldCoordinateScale",1/step,1/step);
        shader.setFloatUniform("fieldCoordinateOffset",-originX/step+.5f,-originY/step+.5f);
        shader.setFloatUniform("fieldDistanceScale",1);
        shader.setFloatUniform("fieldGradientScale",1,1);
        shader.setFloatUniform("fieldExtent",(float)width,(float)height);
    }
    private static float clamp(float v) { return Math.max(0,Math.min(1,v)); }

    /** RoundedMenuField's continuous corners, without clamping the overrounding radii. */
    private static final class Rounded {
        final float cx,cy,r,ix,iy,hw,hh,expanded,kx,ky,base;
        Rounded(float[] g,int o) {
            cx=g[o]+g[o+2]/2; cy=g[o+1]+g[o+3]/2;
            float rx=Math.max(.01f,g[o+4]),ry=Math.max(.01f,g[o+5]); r=Math.min(rx,ry);
            ix=r/rx; iy=r/ry; hw=g[o+2]/2*ix; hh=g[o+3]/2*iy; expanded=r*1.5286649465560913f;
            kx=clamp(2.8915570351735034f-1.4457785175867517f*hw*2/expanded);
            ky=clamp(2.8915570351735034f-1.4457785175867517f*hh*2/expanded);
            base=r*(1.5286649465560913f-.5286649465560913f*Math.max(kx,ky));
        }
        void sample(float x,float y,float[] out) {
            float lx=(x-cx)*ix,ly=(y-cy)*iy,qx=Math.abs(lx)-hw,qy=Math.abs(ly)-hh;
            float ax=qx+base,ay=qy+base,interior=Math.min(Math.max(ax,ay),0);
            if(kx==1 && ky==1) { float dx=Math.max(ax,0),dy=Math.max(ay,0); out[0]=interior+(float)Math.sqrt(dx*dx+dy*dy)-r; }
            else {
                float ux=(qx+expanded)/expanded,uy=(qy+expanded)/expanded,zx=Math.max(ux,0),zy=Math.max(uy,0),largest=Math.max(zx,zy);
                float t=largest>0?Math.min(zx,zy)/largest:0,len=(float)Math.sqrt(zx*zx+zy*zy);
                float p=((((-.9260540008544922f*t+3.1560099124908447f)*t-3.6412200927734375f)*t+1.268030047416687f)*t+.2685309946537018f)*t*t;
                float continuous=len+1-1/(1-p*Math.min(len,1));
                float xx=Math.max(1.5286649465560913f*ux-.5286649465560913f,0),yy=Math.max(1.5286649465560913f*uy-.5286649465560913f,0);
                float correction=.3458344340324402f-continuous+.6541655659675598f*(float)Math.sqrt(xx*xx+yy*yy);
                float w=clamp((zx<zy?1:-1)*(t-1)+.5f);
                out[0]=interior+expanded*(continuous-1+correction*(kx+(ky-kx)*w));
            }
            float nx=Math.max(ax,0),ny=Math.max(ay,0),len=(float)Math.sqrt(nx*nx+ny*ny);
            if(len>.000001f) { nx/=len; ny/=len; } else if(ax>ay) { nx=1; ny=0; } else { nx=0; ny=1; }
            if(lx<0) nx=-nx; if(ly<0) ny=-ny;
            float ox=lx,oy=ly*hw/Math.max(hh,.000001f),ol=(float)Math.sqrt(ox*ox+oy*oy);
            if(ol>.000001f) { ox/=ol; oy/=ol; } else { ox=nx; oy=ny; }
            nx+=ox; ny+=oy; len=(float)Math.sqrt(nx*nx+ny*ny);
            out[1]=len>.000001f?nx/len*ix:0; out[2]=len>.000001f?ny/len*iy:0;
        }
    }
}
