package zxc.iconic.xenon.osu;

import android.content.Context;
import android.graphics.*;
import android.view.*;
import android.widget.*;
import org.telegram.messenger.*;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import java.util.Locale;

/** Same byte label and smoothed three-dp progress strip used by the updater download button. */
final class OsuWatchButton extends FrameLayout {
    private final TextView label;
    private final ImageView spinner;
    private final org.telegram.ui.Components.CircularProgressDrawable spinnerDrawable;
    private final Paint progressPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean busy;
    private float progress,displayed;
    private long lastFrame;
    OsuWatchButton(Context context,Theme.ResourcesProvider resources){
        super(context);setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(24),Theme.getColor(Theme.key_featuredStickers_addButton,resources)));
        setClipToOutline(true);setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        int text=Theme.getColor(Theme.key_featuredStickers_buttonText,resources);progressPaint.setColor(text);
        label=new TextView(context);label.setTextColor(text);label.setTextSize(14);label.setTypeface(AndroidUtilities.bold());label.setGravity(Gravity.CENTER);label.setSingleLine(true);addView(label,LayoutHelper.createFrame(-1,-1));
        spinner=new ImageView(context);spinnerDrawable=new org.telegram.ui.Components.CircularProgressDrawable(AndroidUtilities.dp(20),AndroidUtilities.dp(2.25f),text);spinner.setImageDrawable(spinnerDrawable);addView(spinner,LayoutHelper.createFrame(24,24,Gravity.CENTER,0,0,0,0));
        update(false,false,0,0,false);
    }
    void update(boolean busy,boolean downloading,long bytes,long total,boolean failed){
        this.busy=busy;spinnerDrawable.setVisible(busy,false);spinner.setVisibility(busy?VISIBLE:GONE);label.setPadding(0,0,0,0);label.setTranslationX(busy?AndroidUtilities.dp(15):0);
        if(downloading){String size=String.format(Locale.US,"%.1f MB",bytes/1048576.0);if(total>0)size+=String.format(Locale.US," / %.1f MB",total/1048576.0);label.setText(LocaleController.getString(R.string.Downloading)+"… "+size);progress=total>0?Math.min(1,bytes/(float)total):0;}
        else{label.setText(LocaleController.getString(busy?R.string.OsuReplayPreparing:failed?R.string.Retry:R.string.OsuReplayWatch));progress=0;}
        spinner.setTranslationX(-label.getPaint().measureText(label.getText().toString())/2-AndroidUtilities.dp(7));
        if(!busy)displayed=0;setContentDescription(label.getText());invalidate();
    }
    @Override protected void dispatchDraw(Canvas canvas){
        super.dispatchDraw(canvas);long now=android.os.SystemClock.elapsedRealtime();
        if(lastFrame==0||progress<displayed)displayed=progress;else displayed+=(progress-displayed)*(1-(float)Math.exp(-(now-lastFrame)/80.0));lastFrame=now;
        canvas.drawRect(0,getHeight()-AndroidUtilities.dp(3),getWidth()*displayed,getHeight(),progressPaint);
        if(busy)postInvalidateOnAnimation();
    }
    @Override protected void onDetachedFromWindow(){super.onDetachedFromWindow();spinnerDrawable.setVisible(false,false);lastFrame=0;}
}
