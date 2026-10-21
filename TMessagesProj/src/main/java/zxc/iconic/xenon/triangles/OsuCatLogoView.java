package zxc.iconic.xenon.triangles;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.os.SystemClock;
import android.view.Choreographer;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import org.telegram.messenger.MediaController;
import org.telegram.messenger.MessageObject;
import zxc.iconic.xenon.osu.OsuArchive;
import zxc.iconic.xenon.osu.OsuAudioData;
import zxc.iconic.xenon.osu.OsuBeatClock;
import zxc.iconic.xenon.osu.OsuBeatmapPlayer;
import zxc.iconic.xenon.osu.OsuLogoSounds;

import androidx.appcompat.content.res.AppCompatResources;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.R;

/** Android adaptation of ppy/osu OsuLogo, Triangles and TrianglesV2 (MIT). */
public final class OsuCatLogoView extends View {
    private final OsuLogoModel model;
    private final OsuLogoMusicMotion musicMotion = new OsuLogoMusicMotion();
    private final OsuLogoVisualisation visualisation = new OsuLogoVisualisation();
    private final float[] audioSpectrum = new float[200], barLines = new float[OsuLogoVisualisation.MAX_LINE_FLOATS];
    private final Paint barPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private boolean musicReactive;
    private OsuArchive.Track mapTrack;
    private final OsuBeatClock mapClock = new OsuBeatClock();
    private float logoSizeDp = 90;
    private int lastMapSeek;
    private float lastMapRate;
    private long lastMusicSequence;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Paint additivePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path(), drawPath = new Path();
    private final Path[] outline = {new Path(), new Path(), new Path()};
    private final Rect visibleRect = new Rect();
    private final RectF overlayBounds = new RectF(0, 0, 512, 512);
    private final Shader background, triangles;
    private Bitmap overlay;
    private boolean screenActive, framePosted;
    private long lastFrame, scaleStart, flashStart = -1;
    private float scaleFrom = 1, scaleTarget = 1;
    private boolean returning;
    private final OsuLogoModel.ReleaseGesture releaseGesture = new OsuLogoModel.ReleaseGesture();
    private final int touchSlop;
    private boolean suppressTouchClickFlash;
    private final Runnable allowClickFlash = () -> suppressTouchClickFlash = false;
    private final Choreographer.FrameCallback frameCallback = this::frame;
    private ViewTreeObserver visibilityObserver;
    private final ViewTreeObserver.OnPreDrawListener visibilityListener = () -> {
        // Resume/attach can happen before layout or while the page is still translated offscreen.
        // Watch the completed layout even when Android reuses this view's cached display list.
        updateAnimationVisibility();
        return true;
    };

    public OsuCatLogoView(Context context, boolean legacy) {
        super(context);
        model = new OsuLogoModel(legacy, System.nanoTime());
        touchSlop = ViewConfiguration.get(context).getScaledTouchSlop();
        clip.addCircle(model.diameter / 2, model.diameter / 2, model.diameter / 2, Path.Direction.CW);
        background = new LinearGradient(0, 0, 0, model.diameter,
                legacy ? 0xffe967a1 : 0xffff66ab, legacy ? 0xffe967a1 : 0xffcc5289, Shader.TileMode.CLAMP);
        triangles = new LinearGradient(0, 0, 0, model.diameter, 0xffff66ab, 0xffb6346f, Shader.TileMode.CLAMP);
        additivePaint.setColor(Color.WHITE);
        additivePaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        barPaint.setColor(Color.WHITE);
        barPaint.setAlpha(26); // Logo visualizer .5 alpha * source bar colour .2 alpha.
        barPaint.setStrokeWidth(model.diameter * (float) Math.sin(Math.PI / OsuLogoVisualisation.BARS));
        barPaint.setStrokeCap(Paint.Cap.BUTT);
        barPaint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        setClickable(true);
        setFocusable(true);
        setContentDescription(LocaleController.getString(legacy ? R.string.AppIconOsuOld : R.string.AppIconOsu));
        float half = 150, h = 300 * 0.866f;
        float[] px = {0, half, -half}, py = {-h * 2 / 3, h / 3, h / 3};
        // TriangleBorder.fs: Thickness=0.009 in normalized triangle coordinates.
        float inner = 1 - 0.009f * 0.866f * (float) Math.sqrt(12);
        for (int i = 0; i < 3; i++) {
            int n = (i + 1) % 3;
            outline[i].moveTo(px[i], py[i]);
            outline[i].lineTo(px[n], py[n]);
            outline[i].lineTo(px[n] * inner, py[n] * inner);
            outline[i].lineTo(px[i] * inner, py[i] * inner);
            outline[i].close();
        }
    }

    public boolean isLegacy() { return model.legacy; }
    public void setLogoSizeDp(float size) { logoSizeDp = size; overlay = null; invalidate(); }

    public void setMusicReactive(boolean enabled) {
        if (musicReactive == enabled) return;
        if (musicReactive) LogoAudioVisualizer.setConsumerVisible(false);
        musicReactive = enabled;
        if (OsuBeatmapPlayer.current() == null) { musicMotion.reset(); visualisation.reset(); }
        lastMusicSequence = enabled ? LogoAudioVisualizer.beatSequence : 0;
        updateAnimationVisibility();
        invalidate();
    }

    public void setScreenActive(boolean active) {
        screenActive = active;
        if (!active) {
            mapClock.reset(); mapTrack = null;
            musicMotion.reset(); visualisation.reset();
            stopFrames();
            scaleFrom = scaleTarget = 1;
            flashStart = -1;
            releaseGesture.cancel();
            removeCallbacks(allowClickFlash);
            suppressTouchClickFlash = false;
            super.setPressed(false);
        }
        updateAnimationVisibility();
        invalidate();
    }

    /** Called by the settings list on scroll, including when this header re-enters the viewport. */
    public void updateAnimationVisibility() {
        boolean visible = screenActive && isAttachedToWindow() && isShown()
                && getWindowVisibility() == VISIBLE && getGlobalVisibleRect(visibleRect);
        OsuLogoSounds.setVisible(this, visible);
        if (musicReactive) {
            LogoAudioVisualizer.setConsumerVisible(visible && OsuBeatmapPlayer.current() == null);
            if (!visible) { musicMotion.reset(); visualisation.reset(); }
        }
        if (!visible) { stopFrames(); return; }
        if (!framePosted) {
            framePosted = true;
            Choreographer.getInstance().postFrameCallback(frameCallback);
        }
    }

    private void stopFrames() {
        if (framePosted) Choreographer.getInstance().removeFrameCallback(frameCallback);
        framePosted = false;
        lastFrame = 0;
    }

    private void frame(long nanos) {
        framePosted = false;
        if (!screenActive || !isAttachedToWindow() || !isShown() || getWindowVisibility() != VISIBLE
                || !getGlobalVisibleRect(visibleRect)) { updateAnimationVisibility(); return; }
        float seconds = lastFrame == 0 ? 0 : Math.min(0.1f, Math.max(0, (nanos - lastFrame) / 1_000_000_000f));
        OsuArchive.Track currentMap = OsuBeatmapPlayer.current();
        if (mapTrack != currentMap) {
            mapTrack = currentMap; mapClock.reset(); musicMotion.reset(); visualisation.reset();
            if (mapTrack != null) OsuBeatmapPlayer.ensureAudio(mapTrack);
        }
        if (mapTrack != null) {
            MediaController player = MediaController.getInstance();
            MessageObject message = player.getPlayingMessageObject();
            long now = SystemClock.uptimeMillis(), position = player.getProgressMs(message);
            boolean playing = position >= 0 && !player.isMessagePaused() && !player.isDownloadingCurrentMessage();
            float rate = Math.max(0.1f, player.getPlaybackSpeed(true));
            if (lastMapSeek != OsuBeatmapPlayer.seekGeneration || lastMapRate != rate) mapClock.reset();
            lastMapSeek = OsuBeatmapPlayer.seekGeneration; lastMapRate = rate;
            mapClock.update(mapTrack.map, Math.max(0, position), seconds * 1000, rate, playing);
            OsuLogoSounds.mapFrame(mapTrack);
            if (mapClock.jumped) { musicMotion.reset(); visualisation.reset(); }
            OsuAudioData decoded = OsuBeatmapPlayer.audio(mapTrack);
            float amplitude = decoded == null ? 0 : decoded.sample(Math.max(0, position), audioSpectrum);
            if (decoded == null || !playing) java.util.Arrays.fill(audioSpectrum, 0);
            if (mapClock.pulse) musicMotion.onBeat(now - Math.round((position + 60 * rate - mapClock.upcoming.beatTime) / rate),
                    amplitude, (float) (mapClock.upcoming.beatLength / rate), mapClock.upcoming.kiai);
            musicMotion.update(now, seconds * 1000, amplitude, playing, true, mapClock.state.kiai);
            visualisation.update(seconds * 1000, audioSpectrum, playing, true, mapClock.state.kiai);
            model.advance(seconds, musicMotion.velocity);
        } else if (musicReactive && LogoAudioVisualizer.isRequested()) {
            long now = SystemClock.uptimeMillis();
            boolean playing = LogoAudioVisualizer.soundTime > 0 && now - LogoAudioVisualizer.soundTime < 300;
            OsuLogoSounds.systemFrame(playing);
            float amplitude = now - LogoAudioVisualizer.sampleTime < 250 ? LogoAudioVisualizer.amplitude : 0;
            long sequence = LogoAudioVisualizer.beatSequence;
            if (sequence != lastMusicSequence) {
                lastMusicSequence = sequence;
                if (playing && now - LogoAudioVisualizer.beatTime < 250) {
                    musicMotion.onBeat(LogoAudioVisualizer.beatTime, LogoAudioVisualizer.beatAmplitude, LogoAudioVisualizer.beatLength);
                    OsuLogoSounds.systemBeat(sequence, LogoAudioVisualizer.beatTime, model.legacy);
                }
            }
            musicMotion.update(now, seconds * 1000, amplitude, playing);
            LogoAudioVisualizer.copySpectrum(audioSpectrum);
            visualisation.update(seconds * 1000, audioSpectrum, playing);
            model.advance(seconds, musicMotion.velocity);
        } else { OsuLogoSounds.systemFrame(false); model.advance(seconds); }
        lastFrame = nanos;
        invalidate();
        updateAnimationVisibility();
    }

    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        visibilityObserver = getViewTreeObserver();
        visibilityObserver.addOnPreDrawListener(visibilityListener);
        post(this::updateAnimationVisibility);
    }

    @Override protected void onDetachedFromWindow() {
        OsuLogoSounds.setVisible(this, false);
        stopFrames();
        if (musicReactive) LogoAudioVisualizer.setConsumerVisible(false);
        musicMotion.reset();
        visualisation.reset();
        if (visibilityObserver != null && visibilityObserver.isAlive()) {
            visibilityObserver.removeOnPreDrawListener(visibilityListener);
        }
        visibilityObserver = null;
        releaseGesture.cancel();
        removeCallbacks(allowClickFlash);
        suppressTouchClickFlash = false;
        super.onDetachedFromWindow();
    }

    @Override protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        // View's constructor may call this before the frame callback is initialized.
        if (frameCallback != null) updateAnimationVisibility();
    }

    private float currentScale(long now) {
        if (scaleFrom == scaleTarget) return scaleTarget;
        float t = (now - scaleStart) / (float) (returning ? OsuLogoModel.RELEASE_MS : OsuLogoModel.PRESS_MS);
        return scaleFrom + (scaleTarget - scaleFrom) * (returning ? OsuLogoModel.release(t) : OsuLogoModel.press(t));
    }

    @Override public void setPressed(boolean pressed) {
        if (pressed == isPressed()) return;
        long now = SystemClock.uptimeMillis();
        scaleFrom = currentScale(now);
        scaleTarget = pressed ? 0.9f : 1;
        scaleStart = now;
        returning = !pressed;
        super.setPressed(pressed);
        invalidate();
    }

    @Override public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            if (!insideLogo(event)) return false;
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
            removeCallbacks(allowClickFlash);
            suppressTouchClickFlash = false;
            releaseGesture.down();
            setPressed(true);
        } else if (action == MotionEvent.ACTION_MOVE) {
            if (event.getX() < -touchSlop || event.getX() > getWidth() + touchSlop
                    || event.getY() < -touchSlop || event.getY() > getHeight() + touchSlop) releaseGesture.cancel();
        } else if (action == MotionEvent.ACTION_UP) {
            if (releaseGesture.up(screenActive && insideLogo(event))) startFlash();
            // View may post performClick after UP; it must not restart the same release flash.
            suppressTouchClickFlash = true;
        } else if (action == MotionEvent.ACTION_CANCEL) {
            releaseGesture.cancel();
        }
        boolean handled = super.onTouchEvent(event);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) setPressed(false);
        if ((action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) && getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
        if (action == MotionEvent.ACTION_UP) post(allowClickFlash);
        return handled;
    }

    private boolean insideLogo(MotionEvent event) {
        float dx = event.getX() - getWidth() / 2f, dy = event.getY() - getHeight() / 2f;
        float radius = Math.min(AndroidUtilities.dp(logoSizeDp), Math.min(getWidth(), getHeight())) * 0.48f;
        return dx * dx + dy * dy <= radius * radius;
    }

    private void startFlash() {
        flashStart = SystemClock.uptimeMillis();
        invalidate();
        updateAnimationVisibility();
    }

    @Override public boolean performClick() {
        if (!suppressTouchClickFlash) startFlash();
        boolean sound = OsuLogoSounds.toggle();
        setSelected(sound);
        announceForAccessibility(LocaleController.getString(sound ? R.string.OsuLogoBeatSoundsOn : R.string.OsuLogoBeatSoundsOff));
        super.performClick();
        return true;
    }

    @Override protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        overlay = null;
    }

    private float outerRadius() { return model.legacy ? 245.76f : 242.5f; }
    private float innerRadius() { return model.legacy ? 219.2f : 217.5f; }

    /** Rasterize the static white ring, cat and soft shadows once, never on animation frames. */
    private void prepareOverlay(int size) {
        overlay = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(overlay);
        c.scale(size / OsuLogoModel.SIZE, size / OsuLogoModel.SIZE);
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        Path ring = new Path();
        ring.setFillType(Path.FillType.EVEN_ODD);
        ring.addCircle(256, 256, outerRadius(), Path.Direction.CW);
        ring.addCircle(256, 256, innerRadius(), Path.Direction.CW);
        p.setColor(Color.WHITE);
        p.setShadowLayer(7, 0, 2, 0x66000000);
        c.drawPath(ring, p);
        int save = c.save();
        Path mask = new Path();
        mask.addCircle(256, 256, outerRadius(), Path.Direction.CW);
        c.clipPath(mask);
        // Reuse the actual cat artwork from the launcher's vector, without its baked background.
        Drawable cat = AppCompatResources.getDrawable(getContext(), R.drawable.ic_osu_header_cat);
        // Overlap the ring slightly so separately antialiased edges cannot expose a pink seam.
        float catScale = (innerRadius() + 2) / 33;
        c.translate(256 - 37 * catScale, 256 - 37 * catScale);
        c.scale(catScale, catScale);
        // The cat joins the white ring as one flat silhouette, without a separate shadow.
        cat.setBounds(0, 0, 74, 74);
        cat.draw(c);
        c.restoreToCount(save);
    }

    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int size = Math.min(AndroidUtilities.dp(logoSizeDp), Math.min(getWidth(), getHeight()));
        if (size <= 0) return;
        if (overlay == null) prepareOverlay(size);
        long now = SystemClock.uptimeMillis();
        int root = canvas.save();
        canvas.translate((getWidth() - size) / 2f, (getHeight() - size) / 2f);
        canvas.scale(size / OsuLogoModel.SIZE, size / OsuLogoModel.SIZE);
        float scale = currentScale(now);
        canvas.scale(scale, scale, 256, 256);
        if (musicReactive || mapTrack != null) {
            float alpha = musicMotion.rippleAlpha(now);
            if (alpha > 0.001f) {
                int ripple = canvas.save();
                float rippleScale = musicMotion.rippleScale(now);
                canvas.scale(rippleScale, rippleScale, 256, 256);
                additivePaint.setAlpha(Math.round(alpha * 255));
                canvas.drawBitmap(overlay, null, overlayBounds, additivePaint);
                canvas.restoreToCount(ripple);
            }
            float musicScale = musicMotion.scale(now);
            canvas.scale(musicScale, musicScale, 256, 256);
            // One batched line draw replaces up to 1000 individual rotated quads.
            int lineCount = visualisation.writeLines(model.diameter / 2, barLines);
            barPaint.setAlpha(Math.round(26 * musicMotion.spectrumAlpha(now)));
            if (lineCount > 0) canvas.drawLines(barLines, 0, lineCount, barPaint);
        }
        int content = canvas.save();
        float inset = (OsuLogoModel.SIZE - model.diameter) / 2;
        canvas.translate(inset, inset);
        canvas.clipPath(clip);
        paint.setColor(Color.WHITE);
        paint.setAlpha(255);
        paint.setShader(background);
        canvas.drawRect(0, 0, model.diameter, model.diameter, paint);
        for (int i = 0; i < model.count; i++) {
            float side = model.side[i], h = side * 0.866f;
            if (model.legacy) {
                paint.setShader(null);
                paint.setColor(model.colour[i]);
                drawPath.rewind();
                drawPath.moveTo(model.x[i], model.y[i]);
                drawPath.lineTo(model.x[i] + side / 2, model.y[i] + h);
                drawPath.lineTo(model.x[i] - side / 2, model.y[i] + h);
                drawPath.close();
                canvas.drawPath(drawPath, paint);
            } else {
                paint.setShader(triangles);
                for (Path edge : outline) {
                    drawPath.set(edge);
                    drawPath.offset(model.x[i], model.y[i] + h * 2 / 3);
                    canvas.drawPath(drawPath, paint);
                }
            }
        }
        float flash = flashStart < 0 ? 0 : OsuLogoModel.flash((now - flashStart) / (float) OsuLogoModel.FLASH_MS);
        if (mapTrack != null) flash = Math.max(flash, musicMotion.kiaiFlash(now));
        if (flash > 0) {
            // The clipped background is opaque, so ADD affects only the logo, below its white artwork.
            additivePaint.setAlpha(Math.round(flash * 255));
            canvas.drawRect(0, 0, model.diameter, model.diameter, additivePaint);
        }
        canvas.restoreToCount(content);
        paint.setShader(null);
        paint.setAlpha(255);
        canvas.drawBitmap(overlay, null, overlayBounds, paint);
        canvas.restoreToCount(root);
        updateAnimationVisibility();
    }
}
