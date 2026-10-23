package zxc.iconic.xenon.osu;

import android.content.Context;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.TextView;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.BackupImageView;
import androidx.appcompat.content.res.AppCompatResources;

/** Attached rows load small cached thumbnails; archives are fetched only on selection. */
public final class OsuBeatmapSearchCell extends FrameLayout {
    private final TextView title,detail;
    private final BackupImageView artwork;
    public OsuBeatmapSearchCell(Context context,Theme.ResourcesProvider resources,boolean dark) {
        super(context);
        title=text(context,15,dark?0xffffffff:Theme.getColor(Theme.key_windowBackgroundWhiteBlackText,resources));
        detail=text(context,12,dark?0xffbbbbbb:Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3,resources));
        artwork=new BackupImageView(context);artwork.setAspectFit(false);artwork.setRoundRadius(0);
        boolean rtl=LocaleController.isRTL;
        addView(artwork,LayoutHelper.createFrame(42,42,(rtl?Gravity.RIGHT:Gravity.LEFT)|Gravity.TOP,rtl?0:8,10,rtl?8:0,0));
        addView(title,LayoutHelper.createFrame(-1,24,Gravity.TOP|Gravity.LEFT,rtl?16:60,8,rtl?60:16,0));
        addView(detail,LayoutHelper.createFrame(-1,22,Gravity.TOP|Gravity.LEFT,rtl?16:60,33,rtl?60:16,0));
    }
    private static TextView text(Context context,int size,int color){TextView view=new TextView(context);view.setTextSize(TypedValue.COMPLEX_UNIT_DIP,size);view.setTextColor(color);view.setSingleLine(true);view.setEllipsize(TextUtils.TruncateAt.END);view.setGravity(LocaleController.isRTL?Gravity.RIGHT:Gravity.LEFT);return view;}
    public void bind(OsuBeatmapSearch.Result result,String status,boolean retry) {
        android.graphics.drawable.Drawable logo=AppCompatResources.getDrawable(getContext(),R.drawable.osu_search_badge);
        if(result==null)artwork.setImageDrawable(logo);
        else artwork.setImage(result.thumbnailUrl(),"42_42",logo);
        if(result==null){title.setText(status);detail.setText(LocaleController.getString(retry?R.string.OsuSearchRetry:R.string.OsuSearchHint));}
        else {title.setText(result.heading());detail.setText(result.creator+(result.stars().isEmpty()?"":" · "+result.stars()));}
        setContentDescription(title.getText()+", "+detail.getText());
    }
    @Override protected void onMeasure(int width,int height){super.onMeasure(width,MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(64),MeasureSpec.EXACTLY));}
}
