package zxc.iconic.xenon.osu;

import android.graphics.*;
import android.graphics.drawable.Drawable;
import org.telegram.messenger.*;

/** Official osu-resources Menu/logo.png, scaled for document controls. */
public final class OsuFileDrawable extends Drawable {
    private static Bitmap bitmap;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    public OsuFileDrawable() {
        if (bitmap == null) {
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 4;
            bitmap = BitmapFactory.decodeResource(ApplicationLoader.applicationContext.getResources(), R.drawable.ic_osu_file, options);
        }
    }
    @Override public void draw(Canvas canvas) { if (bitmap != null) canvas.drawBitmap(bitmap, null, getBounds(), paint); }
    @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
    @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
    @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    @Override public int getIntrinsicWidth() { return AndroidUtilities.dp(30); }
    @Override public int getIntrinsicHeight() { return AndroidUtilities.dp(30); }
}
