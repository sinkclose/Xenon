package org.telegram.ui.Components;

import android.content.Context;
import android.graphics.Canvas;
import android.view.View;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.ui.ActionBar.Theme;

/** A download arrow inside MediaActionDrawable's animated progress arc. */
public class UpdateDownloadIcon extends View {
    private final MediaActionDrawable ring = new MediaActionDrawable();
    private final MediaActionDrawable arrow = new MediaActionDrawable();
    private final ApplicationLoader updater = ApplicationLoader.applicationLoaderInstance;
    private final Runnable visibilityChanged;
    private final Runnable listener = this::update;
    public UpdateDownloadIcon(Context context, Runnable visibilityChanged) {
        super(context);
        this.visibilityChanged = visibilityChanged;
        ring.setIcon(MediaActionDrawable.ICON_EMPTY, false);
        arrow.setIcon(MediaActionDrawable.ICON_DOWNLOAD, false);
        ring.setDelegate(this::invalidate);
        arrow.setDelegate(this::invalidate);
    }
    private void update() {
        ring.setProgress(updater.getDownloadingUpdateProgress(), updater.getDownloadBytesDownloaded() > 0);
        visibilityChanged.run();
        invalidate();
    }
    @Override protected void onDraw(Canvas canvas) {
        int color = Theme.getColor(Theme.key_actionBarDefaultIcon);
        ring.setColor(color);
        arrow.setColor(color);
        int cx = getWidth() / 2, cy = getHeight() / 2;
        int r = AndroidUtilities.dp(17);
        ring.setBounds(cx - r, cy - r, cx + r, cy + r);
        ring.draw(canvas);
        r = AndroidUtilities.dp(10);
        arrow.setBounds(cx - r, cy - r, cx + r, cy + r);
        arrow.draw(canvas);
    }
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        updater.addUpdateDownloadListener(listener);
        update();
    }
    @Override protected void onDetachedFromWindow() {
        updater.removeUpdateDownloadListener(listener);
        super.onDetachedFromWindow();
    }
}
