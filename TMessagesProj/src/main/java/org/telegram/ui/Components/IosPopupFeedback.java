package org.telegram.ui.Components;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import org.telegram.messenger.AndroidUtilities;
import androidx.dynamicanimation.animation.FloatValueHolder;
import androidx.dynamicanimation.animation.SpringAnimation;
import androidx.dynamicanimation.animation.SpringForce;
import java.util.IdentityHashMap;

/** GlasmioMenuItem: independent .5/300 press springs and a 15% black Capsule background.
 * NEORUAA/Glasmio IosMenus.kt + InteractiveHighlight.kt, Apache-2.0.
 */
final class IosPopupFeedback {
    private final View root;
    private final Runnable invalidate;
    private final IdentityHashMap<View, Row> rows = new IdentityHashMap<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Rect rect = new Rect();
    private final RectF pill = new RectF();
    private View selected;
    private boolean active;
    private final class Row {
        float press;
        final SpringAnimation spring = new SpringAnimation(new FloatValueHolder());
        Row() {
            spring.setSpring(new SpringForce(0).setDampingRatio(.5f).setStiffness(300f));
            spring.setMinimumVisibleChange(.001f);
            spring.addUpdateListener((a,value,velocity)-> { press=value; invalidate.run(); });
        }
    }
    IosPopupFeedback(View root,Runnable invalidate) { this.root=root; this.invalidate=invalidate; paint.setColor(0xff000000); }
    void touch(MotionEvent event,float x,float y,boolean start) {
        if(start) active=true;
        int action=event.getActionMasked();
        if(action==MotionEvent.ACTION_UP || action==MotionEvent.ACTION_CANCEL || action==MotionEvent.ACTION_POINTER_DOWN) {
            active=false; select(null);
        }
    }
    void select(View view) {
        // A background/swipe container is not a menu row. Never turn a drag
        // over empty padding into a capsule covering the entire surface.
        if(!active || view == root || view != null && view.getHeight() > AndroidUtilities.dp(96)) view=null;
        if(selected==view) return;
        Row old=rows.get(selected);
        if(old!=null) old.spring.animateToFinalPosition(0);
        selected=view;
        if(view!=null) {
            Row row=rows.get(view);
            if(row==null) { row=new Row(); rows.put(view,row); }
            row.spring.animateToFinalPosition(1);
        }
        invalidate.run();
    }
    void draw(Canvas canvas, float visibleWidth) {
        for(java.util.Map.Entry<View,Row> entry:rows.entrySet()) {
            float p=Math.max(0,Math.min(1,entry.getValue().press));
            if(p<=0) continue;
            View view=entry.getKey();
            if (view == root || view.getHeight() > AndroidUtilities.dp(96)) continue;
            view.getDrawingRect(rect);
            if(root instanceof ViewGroup && view!=root) ((ViewGroup)root).offsetDescendantRectToMyCoords(view,rect);
            // Swipe-back reserves space for wider pages. The highlight belongs
            // to the visible panel and must also fit inside the current row.
            float right = Math.min(rect.right, visibleWidth - rect.left);
            if (right <= rect.left) continue;
            pill.set(rect.left, rect.top, right, rect.bottom);
            paint.setAlpha(Math.round(255*.15f*p));
            canvas.drawRoundRect(pill,pill.height()/2,pill.height()/2,paint);
        }
    }
    void release() { for(Row row:rows.values()) row.spring.cancel(); rows.clear(); selected=null; active=false; }
}
