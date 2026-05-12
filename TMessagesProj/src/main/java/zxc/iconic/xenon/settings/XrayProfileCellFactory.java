package zxc.iconic.xenon.settings;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.graphics.drawable.Drawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.Components.RecyclerListView;
import org.telegram.ui.Components.UItem;
import org.telegram.ui.Components.UniversalAdapter;
import org.telegram.ui.Components.UniversalRecyclerView;

/**
 * Two-line proxy profile row in strict Telegram style:
 * {@code [title] / [endpoint • active mark]} with right-side overflow icon.
 *
 * Mirrors the layout of {@code TextDetailProxyCell} from Telegram's
 * native {@code ProxyListActivity}, minus selection-mode animations.
 *
 * Row click -> set profile active (handled by the containing activity).
 * Overflow click -> invoke {@link UItem#object} as {@link Runnable} if present.
 */
public class XrayProfileCellFactory extends UItem.UItemFactory<XrayProfileCellFactory.Cell> {

    static {
        setup(new XrayProfileCellFactory());
    }

    @Override
    public Cell createView(Context context, RecyclerListView listView, int currentAccount, int classGuid, Theme.ResourcesProvider resourcesProvider) {
        return new Cell(context, resourcesProvider);
    }

    @Override
    public void bindView(View view, UItem item, boolean divider, UniversalAdapter adapter, UniversalRecyclerView listView) {
        Cell cell = (Cell) view;
        cell.setDivider(divider);
        cell.setText(item.text);
        cell.setValue(item.subtext);
        cell.setChecked(item.checked);
        cell.setEnabled(item.enabled);
        Runnable overflow = item.object instanceof Runnable ? (Runnable) item.object : null;
        cell.setOnOverflowClick(overflow);
    }

    /**
     * Factory helper: build a row UItem for the profiles list.
     *
     * @param id        unique row id (profile-index offset)
     * @param name      profile display name
     * @param endpoint  compact endpoint summary (e.g. "vless • host:443")
     * @param isActive  whether this profile is currently selected as active
     * @param overflow  callback invoked when user taps the trailing overflow icon
     */
    public static UItem of(int id, CharSequence name, CharSequence endpoint, boolean isActive, Runnable overflow) {
        UItem item = UItem.ofFactory(XrayProfileCellFactory.class);
        item.id = id;
        item.text = name;
        item.subtext = endpoint;
        item.checked = isActive;
        item.object = overflow;
        return item;
    }

    public static class Cell extends FrameLayout {

        private final Theme.ResourcesProvider resourcesProvider;
        private final TextView titleView;
        private final TextView valueView;
        private final ImageView overflowView;
        private Drawable checkDrawable;
        private boolean needDivider;
        private Runnable overflowClick;

        public Cell(@NonNull Context context, Theme.ResourcesProvider resourcesProvider) {
            super(context);
            this.resourcesProvider = resourcesProvider;

            int startPad = LocaleController.isRTL ? 56 : 21;
            int endPad = LocaleController.isRTL ? 21 : 56;

            titleView = new TextView(context);
            titleView.setTextColor(Theme.getColor(Theme.key_windowBackgroundWhiteBlackText, resourcesProvider));
            titleView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 16);
            titleView.setLines(1);
            titleView.setMaxLines(1);
            titleView.setSingleLine(true);
            titleView.setEllipsize(TextUtils.TruncateAt.END);
            titleView.setGravity((LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.CENTER_VERTICAL);
            addView(titleView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP,
                    startPad, 10, endPad, 0));

            valueView = new TextView(context);
            valueView.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 13);
            valueView.setGravity(LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT);
            valueView.setLines(1);
            valueView.setMaxLines(1);
            valueView.setSingleLine(true);
            valueView.setCompoundDrawablePadding(AndroidUtilities.dp(6));
            valueView.setEllipsize(TextUtils.TruncateAt.END);
            valueView.setPadding(0, 0, 0, 0);
            addView(valueView, LayoutHelper.createFrame(LayoutHelper.WRAP_CONTENT, LayoutHelper.WRAP_CONTENT,
                    (LocaleController.isRTL ? Gravity.RIGHT : Gravity.LEFT) | Gravity.TOP,
                    startPad, 35, endPad, 0));

            overflowView = new ImageView(context);
            overflowView.setImageResource(R.drawable.ic_ab_other);
            overflowView.setColorFilter(new PorterDuffColorFilter(
                    Theme.getColor(Theme.key_windowBackgroundWhiteGrayText3, resourcesProvider), PorterDuff.Mode.MULTIPLY));
            overflowView.setScaleType(ImageView.ScaleType.CENTER);
            overflowView.setBackground(Theme.createSelectorDrawable(
                    Theme.getColor(Theme.key_listSelector, resourcesProvider), Theme.RIPPLE_MASK_CIRCLE_20DP));
            overflowView.setContentDescription(LocaleController.getString(R.string.AccDescrMoreOptions));
            overflowView.setOnClickListener(v -> {
                if (overflowClick != null) {
                    overflowClick.run();
                }
            });
            addView(overflowView, LayoutHelper.createFrame(48, 48,
                    (LocaleController.isRTL ? Gravity.LEFT : Gravity.RIGHT) | Gravity.TOP,
                    8, 8, 8, 0));

            setWillNotDraw(false);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(
                    MeasureSpec.makeMeasureSpec(MeasureSpec.getSize(widthMeasureSpec), MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(AndroidUtilities.dp(64) + 1, MeasureSpec.EXACTLY));
        }

        public void setText(CharSequence text) {
            titleView.setText(text);
        }

        public void setValue(CharSequence value) {
            valueView.setText(value);
        }

        public void setChecked(boolean checked) {
            int colorKey = checked ? Theme.key_windowBackgroundWhiteBlueText6 : Theme.key_windowBackgroundWhiteGrayText2;
            int color = Theme.getColor(colorKey, resourcesProvider);
            valueView.setTextColor(color);
            if (checked) {
                if (checkDrawable == null) {
                    checkDrawable = getResources().getDrawable(R.drawable.proxy_check).mutate();
                }
                checkDrawable.setColorFilter(new PorterDuffColorFilter(color, PorterDuff.Mode.MULTIPLY));
                if (LocaleController.isRTL) {
                    valueView.setCompoundDrawablesWithIntrinsicBounds(null, null, checkDrawable, null);
                } else {
                    valueView.setCompoundDrawablesWithIntrinsicBounds(checkDrawable, null, null, null);
                }
            } else {
                valueView.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
            }
        }

        public void setOnOverflowClick(Runnable callback) {
            overflowClick = callback;
        }

        public void setDivider(boolean divider) {
            if (needDivider != divider) {
                needDivider = divider;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (needDivider) {
                canvas.drawLine(
                        LocaleController.isRTL ? 0 : AndroidUtilities.dp(20),
                        getMeasuredHeight() - 1,
                        getMeasuredWidth() - (LocaleController.isRTL ? AndroidUtilities.dp(20) : 0),
                        getMeasuredHeight() - 1,
                        Theme.dividerPaint);
            }
        }
    }
}
