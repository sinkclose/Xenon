package org.telegram.ui.Components;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.util.SparseIntArray;

import androidx.core.graphics.ColorUtils;

import org.telegram.messenger.ImageReceiver;
import org.telegram.ui.ActionBar.Theme;

import java.util.Objects;

/** A profile-local palette; never writes to the application's theme or shared paints. */
public final class ProfileAvatarColors implements Theme.ResourcesProvider {
    private Theme.ResourcesProvider base;
    private final SparseIntArray roles = new SparseIntArray();
    private final SparseIntArray replacements = new SparseIntArray();
    private final SparseIntArray cachedBase = new SparseIntArray();
    private final SparseIntArray cachedColors = new SparseIntArray();
    private boolean ready;
    private String imageKey;
    private float hue, saturation;

    public ProfileAvatarColors(Theme.ResourcesProvider base) {
        this.base = base;
        addRole(1, Theme.key_windowBackgroundWhite, Theme.key_windowBackgroundGray,
                Theme.key_actionBarDefault, Theme.key_graySection, Theme.key_divider,
                Theme.key_actionBarDefaultSubmenuBackground, Theme.key_dialogBackground,
                Theme.key_chat_tagAdmin, Theme.key_chat_tagCreator);
        addRole(2, Theme.key_windowBackgroundWhiteBlueText, Theme.key_windowBackgroundWhiteBlueText2,
                Theme.key_windowBackgroundWhiteBlueHeader, Theme.key_windowBackgroundWhiteBlueIcon,
                Theme.key_windowBackgroundWhiteLinkText, Theme.key_profile_status,
                Theme.key_profile_creatorIcon, Theme.key_profile_verifiedBackground,
                Theme.key_profile_tabSelectedText, Theme.key_profile_tabSelectedLine,
                Theme.key_chat_messageLinkIn, Theme.key_telegram_color, Theme.key_telegram_color_text,
                Theme.key_chats_verifiedBackground, Theme.key_featuredStickers_addButton,
                Theme.key_windowBackgroundWhiteBlueText3, Theme.key_windowBackgroundWhiteBlueText4,
                Theme.key_windowBackgroundWhiteBlueText5, Theme.key_windowBackgroundWhiteBlueText6,
                Theme.key_windowBackgroundWhiteBlueText7, Theme.key_windowBackgroundWhiteBlueButton,
                Theme.key_windowBackgroundWhiteValueText, Theme.key_chat_status);
        addRole(3, Theme.key_windowBackgroundWhiteBlackText, Theme.key_windowBackgroundWhiteGrayText,
                Theme.key_windowBackgroundWhiteGrayText2, Theme.key_windowBackgroundWhiteGrayText3,
                Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_profile_title,
                Theme.key_actionBarDefaultTitle, Theme.key_actionBarDefaultSubtitle,
                Theme.key_actionBarDefaultIcon, Theme.key_avatar_subtitleInProfileBlue,
                Theme.key_profile_tabText, Theme.key_stickers_menu,
                Theme.key_voipgroup_mutedIconUnscrolled, Theme.key_chat_inAdminText,
                Theme.key_actionBarActionModeDefaultIcon, Theme.key_sharedMedia_startStopLoadIcon,
                Theme.key_windowBackgroundWhiteGrayText4, Theme.key_graySectionText);
        addRole(2, Theme.key_actionBarDefaultIcon, Theme.key_actionBarActionModeDefaultIcon,
                Theme.key_avatar_actionBarIconBlue,
                Theme.key_windowBackgroundWhiteGrayIcon, Theme.key_stickers_menu,
                Theme.key_sharedMedia_startStopLoadIcon, Theme.key_profile_actionBackground);
    }

    public Theme.ResourcesProvider getBase() { return base; }

    public void setBase(Theme.ResourcesProvider provider) {
        base = provider;
        cachedBase.clear();
        cachedColors.clear();
    }

    private void addRole(int role, int... keys) {
        for (int key : keys) roles.put(key, role);
    }

    public boolean isReady() {
        return ready;
    }

    public int replace(int color) {
        return replacements.get(color, color);
    }

    public boolean update(ImageReceiver receiver, boolean hasPhoto) {
        Bitmap bitmap = receiver == null ? null : receiver.getBitmap();
        String key = receiver == null ? null : receiver.getImageKey();
        // Sample once per photo, not each video frame or bitmap generation.
        if (hasPhoto && (bitmap == null || bitmap.isRecycled() || ready && Objects.equals(key, imageKey))) return false;
        if (!hasPhoto && !ready) return false;
        float[] hsv = hasPhoto ? extract(bitmap) : null;
        if (hasPhoto && hsv == null) return false;
        int[] previous = new int[roles.size()];
        for (int i = 0; i < previous.length; i++) previous[i] = getColor(roles.keyAt(i));
        ready = hasPhoto;
        imageKey = key;
        if (hsv != null) {
            hue = hsv[0];
            saturation = hsv[1];
        }
        cachedBase.clear();
        cachedColors.clear();
        replacements.clear();
        for (int i = 0; i < previous.length; i++) {
            int themeKey = roles.keyAt(i);
            int color = getColor(themeKey);
            replacements.put(previous[i], color);
            replacements.put(Theme.getColor(themeKey, base), color);
        }
        return true;
    }

    private static float[] extract(Bitmap bitmap) {
        Bitmap sample = null;
        try {
            sample = Bitmap.createScaledBitmap(bitmap, 24, 24, true);
            if (Build.VERSION.SDK_INT >= 26 && sample.getConfig() == Bitmap.Config.HARDWARE) {
                Bitmap readable = sample.copy(Bitmap.Config.ARGB_8888, false);
                if (sample != bitmap) sample.recycle();
                sample = readable;
            }
            float[] hsv = new float[3];
            float[] weights = new float[24];
            float[] saturations = new float[24];
            for (int y = 0; y < sample.getHeight(); y++) {
                for (int x = 0; x < sample.getWidth(); x++) {
                    int color = sample.getPixel(x, y);
                    Color.colorToHSV(color, hsv);
                    // Favor chromatic midtones over white/black borders.
                    float weight = Color.alpha(color) / 255f * (0.05f + hsv[1] * hsv[1])
                            * (0.1f + hsv[2] * (1f - hsv[2]));
                    int bucket = Math.min(23, (int) (hsv[0] / 15f));
                    weights[bucket] += weight;
                    saturations[bucket] += hsv[1] * weight;
                }
            }
            int dominant = 0;
            for (int i = 1; i < weights.length; i++) if (weights[i] > weights[dominant]) dominant = i;
            return new float[]{dominant * 15f + 7.5f,
                    weights[dominant] > 0 ? saturations[dominant] / weights[dominant] : 0};
        } catch (RuntimeException ignored) {
            return null;
        } finally {
            if (sample != null && sample != bitmap) sample.recycle();
        }
    }

    @Override
    public int getColor(int key) {
        int original = Theme.getColor(key, base);
        int role = roles.get(key);
        if (!ready || role == 0) return original;
        if (cachedBase.indexOfKey(key) >= 0 && cachedBase.get(key) == original) return cachedColors.get(key);
        float[] hsl = new float[3];
        ColorUtils.colorToHSL(original, hsl);
        hsl[0] = hue;
        hsl[1] = Math.min(saturation, role == 1 ? 0.18f : role == 3 ? 0.12f : 0.65f);
        int adapted = ColorUtils.HSLToColor(hsl);
        if (role == 2) {
            int surface = getColor(Theme.key_windowBackgroundWhite) | 0xff000000;
            boolean dark = ColorUtils.calculateLuminance(surface) < 0.5;
            for (int i = 0; i < 100 && ColorUtils.calculateContrast(adapted, surface) < 4.5; i++) {
                hsl[2] = Math.max(0f, Math.min(1f, hsl[2] + (dark ? 0.01f : -0.01f)));
                adapted = ColorUtils.HSLToColor(hsl);
            }
        }
        adapted = ColorUtils.setAlphaComponent(adapted, Color.alpha(original));
        cachedBase.put(key, original);
        cachedColors.put(key, adapted);
        return adapted;
    }

    @Override public Drawable getDrawable(String key) { return base == null ? null : base.getDrawable(key); }
    @Override public void setAnimatedColor(int key, int color) {
        if (base != null) base.setAnimatedColor(key, color);
        cachedBase.clear();
        cachedColors.clear();
    }
    @Override public Paint getPaint(String key) { return base == null ? Theme.getThemePaint(key) : base.getPaint(key); }
    @Override public boolean isDark() { return base == null ? Theme.isCurrentThemeDark() : base.isDark(); }
    @Override public boolean hasGradientService() { return base != null && base.hasGradientService(); }
    @Override public ColorFilter getAnimatedEmojiColorFilter() { return base == null ? Theme.chat_animatedEmojiTextColorFilter : base.getAnimatedEmojiColorFilter(); }
    @Override public void applyServiceShaderMatrix(int w, int h, float x, float y) {
        if (base == null) Theme.applyServiceShaderMatrix(w, h, x, y);
        else base.applyServiceShaderMatrix(w, h, x, y);
    }
}
