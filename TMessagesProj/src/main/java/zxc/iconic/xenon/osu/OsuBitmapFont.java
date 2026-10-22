package zxc.iconic.xenon.osu;

import android.graphics.*;
import android.text.TextPaint;
import org.telegram.messenger.ApplicationLoader;
import java.io.*;
import java.nio.*;
import java.util.*;

/** Draws the original lazer BMF glyph atlases without substituting client typefaces. */
final class OsuBitmapFont {
    private static final Map<String, OsuBitmapFont> CACHE = new HashMap<>();
    private final Map<Integer, int[]> glyphs = new HashMap<>();
    private final Map<Long, Short> kernings = new HashMap<>();
    private final PorterDuffXfermode additive=new PorterDuffXfermode(PorterDuff.Mode.ADD);
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final TextPaint fallback = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final Rect src = new Rect();
    private final RectF dst = new RectF();
    private Bitmap atlas;
    private int size = 100, baseline = 84;
    static synchronized OsuBitmapFont get(String name) {
        OsuBitmapFont font = CACHE.get(name);
        if (font == null) { font = new OsuBitmapFont(name); CACHE.put(name,font); }
        return font;
    }
    private OsuBitmapFont(String name) {
        try {
            byte[] bytes;
            try (InputStream in = ApplicationLoader.applicationContext.getAssets().open("osu/fonts/"+name+".bin"); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] block = new byte[8192]; for (int n;(n=in.read(block))>=0;) out.write(block,0,n); bytes=out.toByteArray();
            }
            ByteBuffer b=ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN); b.position(4);
            while (b.remaining()>=5) {
                int type=b.get()&255,length=b.getInt(),end=b.position()+length;
                if (length<0||end>b.limit()) throw new IOException("Invalid BMF font");
                if(type==1) size=Math.abs(b.getShort());
                if(type==2) { b.getShort(); baseline=b.getShort()&65535; }
                if(type==4) while(b.position()+20<=end) {
                    int id=b.getInt(); int[] g=new int[]{b.getShort()&65535,b.getShort()&65535,b.getShort()&65535,b.getShort()&65535,b.getShort(),b.getShort(),b.getShort()};
                    b.get(); b.get(); glyphs.put(id,g);
                }
                if(type==5) while(b.position()+10<=end) { int a=b.getInt(),c=b.getInt(); kernings.put(((long)a<<32)|(c&0xffffffffL),b.getShort()); }
                b.position(end);
            }
            try(InputStream in=ApplicationLoader.applicationContext.getAssets().open("osu/fonts/"+name+"_0.png")) { atlas=BitmapFactory.decodeStream(in); }
        } catch(IOException e) { org.telegram.messenger.FileLog.e(e); }
        // Lazer also falls back to Noto for glyphs absent in Torus (e.g. CJK).
        fallback.setTypeface(Typeface.create("sans-serif",Typeface.NORMAL));
    }
    private float advance(int cp,float pixels) {
        int[] g=glyphs.get(cp);
        if(g!=null) return g[6]*pixels/size;
        fallback.setTextSize(pixels); return fallback.measureText(new String(Character.toChars(cp)));
    }
    float width(String text,float pixels,float spacing) {
        float width=0;int previous=-1;
        for(int offset=0;offset<text.length();) {
            int cp=text.codePointAt(offset); offset+=Character.charCount(cp);
            if(previous>=0) width+=kernings.getOrDefault(((long)previous<<32)|(cp&0xffffffffL),(short)0)*pixels/size+spacing;
            width+=advance(cp,pixels);previous=cp;
        } return width;
    }
    void centeredAdditive(Canvas c,String text,float x,float y,float pixels,int colour,float maxWidth,float spacing){
        paint.setXfermode(additive);fallback.setXfermode(additive);
        centered(c,text,x,y,pixels,colour,maxWidth,spacing);paint.setXfermode(null);fallback.setXfermode(null);
    }
    void centered(Canvas c,String text,float x,float y,float pixels,int colour,float maxWidth,float spacing){
        float min=Float.POSITIVE_INFINITY,max=Float.NEGATIVE_INFINITY;
        for(int i=0;i<text.length();i++){int[] g=glyphs.get((int)text.charAt(i));if(g!=null){min=Math.min(min,g[5]-baseline);max=Math.max(max,g[5]-baseline+g[3]);}}
        float fit=Math.min(1,maxWidth/Math.max(1,width(text,pixels,spacing)));
        draw(c,text,x,y-(Float.isFinite(min)?(min+max)*.5f*pixels/size*fit:0),pixels,colour,maxWidth,spacing);
    }
    void draw(Canvas canvas,String text,float center,float y,float pixels,int color,float maxWidth,float spacing) {
        if(text==null||text.isEmpty()||((color>>>24)==0))return;
        float width=width(text,pixels,spacing);
        // Fit numeric values rather than losing their trailing digits to ellipsis.
        if(width>maxWidth) { float ratio=maxWidth/width; pixels*=ratio;spacing*=ratio;width=maxWidth; }
        paint.setColorFilter(new PorterDuffColorFilter(color|0xff000000,PorterDuff.Mode.SRC_IN));paint.setAlpha(color>>>24);
        fallback.setTextSize(pixels);fallback.setColor(color);
        float x=center-width/2,scale=pixels/size;int previous=-1;
        for(int offset=0;offset<text.length();) {
            int cp=text.codePointAt(offset);offset+=Character.charCount(cp);
            if(previous>=0)x+=kernings.getOrDefault(((long)previous<<32)|(cp&0xffffffffL),(short)0)*scale+spacing;
            int[] g=glyphs.get(cp);
            if(g!=null&&atlas!=null) {
                src.set(g[0],g[1],g[0]+g[2],g[1]+g[3]);
                dst.set(x+g[4]*scale,y+(g[5]-baseline)*scale,x+(g[4]+g[2])*scale,y+(g[5]-baseline+g[3])*scale);
                canvas.drawBitmap(atlas,src,dst,paint);
            } else canvas.drawText(new String(Character.toChars(cp)),x,y,fallback);
            x+=advance(cp,pixels);previous=cp;
        }
    }
}
