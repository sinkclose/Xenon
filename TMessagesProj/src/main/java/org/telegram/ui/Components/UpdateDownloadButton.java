package org.telegram.ui.Components;

import android.app.Activity;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.graphics.drawable.Drawable;
import android.widget.TextView;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import zxc.iconic.xenon.helpers.ApkInstaller;
import java.io.File;
import java.util.Locale;

/** Observes the application download; closing the sheet does not stop it. */
public class UpdateDownloadButton extends FrameLayout {
    private final ApplicationLoader updater = ApplicationLoader.applicationLoaderInstance;
    private final TextView label;
    private final LoadingIndicatorView spinner;
    private final Paint progressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final AnimatedFloat progress = new AnimatedFloat(this);
    private final Runnable dismiss;
    private final Runnable listener = this::update;
    private boolean wasDownloading;
    private boolean connecting;

    public UpdateDownloadButton(Context context, Runnable dismiss) {
        super(context);
        this.dismiss = dismiss;
        setBackground(Theme.createRoundRectDrawable(AndroidUtilities.dp(24), Theme.getColor(Theme.key_featuredStickers_addButton)));
        setClipToOutline(true);
        setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        int textColor = Theme.getColor(Theme.key_featuredStickers_buttonText);
        progressPaint.setColor(textColor);
        label = new TextView(context);
        label.setTextSize(14);
        label.setTypeface(AndroidUtilities.bold());
        label.setTextColor(textColor);
        label.setGravity(Gravity.CENTER);
        label.setSingleLine(true);
        addView(label, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, LayoutHelper.MATCH_PARENT));
        spinner = new LoadingIndicatorView(context, textColor);
        spinner.setVisibility(View.INVISIBLE);
        addView(spinner, LayoutHelper.createFrame(26, 26, Gravity.CENTER));
        setOnClickListener(v -> {
            File apk = updater.getDownloadedUpdateFile();
            Activity activity = AndroidUtilities.findActivity(context);
            if (apk != null && activity != null) {
                ApkInstaller.installUpdate(activity, apk);
            } else if (!updater.isDownloadingUpdate()) {
                updater.downloadUpdate();
                update();
            }
        });
        update();
    }

    private void update() {
        boolean downloading = updater.isDownloadingUpdate();
        File apk = updater.getDownloadedUpdateFile();
        if (wasDownloading && !downloading && apk != null) {
            dismiss.run();
            return;
        }
        wasDownloading = downloading;
        boolean nextConnecting = downloading && updater.getDownloadBytesDownloaded() == 0;
        if (nextConnecting != connecting) {
            connecting = nextConnecting;
            spinner.animate().cancel();
            if (connecting) {
                if (spinner.getVisibility() != View.VISIBLE) spinner.setAlpha(0);
                spinner.setVisibility(View.VISIBLE);
                spinner.animate().alpha(1).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                        .setDuration(300).start();
            } else {
                spinner.animate().alpha(0).setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT)
                        .setDuration(300).withEndAction(() -> spinner.setVisibility(View.INVISIBLE)).start();
            }
            label.animate().cancel();
            label.animate().translationX(connecting ? AndroidUtilities.dp(15) : 0)
                    .setInterpolator(CubicBezierInterpolator.EASE_OUT_QUINT).setDuration(300).start();
        }
        if (nextConnecting) {
            String text = LocaleController.getString(R.string.Connecting);
            label.setText(text);
            spinner.setTranslationX(-label.getPaint().measureText(text) / 2 - AndroidUtilities.dp(7));
        } else if (downloading) {
            long total = updater.getDownloadTotalSize();
            String size = String.format(Locale.US, "%.1f MB", updater.getDownloadBytesDownloaded() / 1048576.0);
            if (total > 0) size += String.format(Locale.US, " / %.1f MB", total / 1048576.0);
            label.setText(LocaleController.getString(R.string.Downloading) + "... " + size);
        } else {
            label.setText(LocaleController.getString(apk != null ? R.string.NekoUpdate : R.string.AppUpdateDownloadNow));
        }
        setContentDescription(label.getText());
        invalidate();
    }
    @Override protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        float value = progress.set(updater.getDownloadingUpdateProgress());
        canvas.drawRect(0, getHeight() - AndroidUtilities.dp(3), getWidth() * value, getHeight(), progressPaint);
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updater.addUpdateDownloadListener(listener);
        update();
    }
    @Override protected void onDetachedFromWindow() {
        updater.removeUpdateDownloadListener(listener);
        spinner.animate().cancel();
        label.animate().cancel();
        super.onDetachedFromWindow();
    }

    private static class LoadingIndicatorView extends View {
        private final CircularProgressDrawable drawable;

        LoadingIndicatorView(Context context, int color) {
            super(context);
            drawable = new CircularProgressDrawable(AndroidUtilities.dp(22), AndroidUtilities.dp(2), color);
            drawable.setCallback(this);
        }

        @Override protected void onDraw(Canvas canvas) {
            drawable.setBounds(0, 0, getWidth(), getHeight());
            drawable.setAlpha(255);
            drawable.draw(canvas);
        }

        @Override protected boolean verifyDrawable(Drawable who) {
            return who == drawable || super.verifyDrawable(who);
        }

        @Override protected void onVisibilityChanged(View changedView, int visibility) {
            super.onVisibilityChanged(changedView, visibility);
            if (drawable != null) drawable.setVisible(isShown(), false);
        }

        @Override protected void onDetachedFromWindow() {
            drawable.setVisible(false, false);
            unscheduleDrawable(drawable);
            super.onDetachedFromWindow();
        }
    }
}
