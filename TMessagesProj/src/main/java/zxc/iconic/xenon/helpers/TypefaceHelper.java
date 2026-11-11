package zxc.iconic.xenon.helpers;

import static org.telegram.messenger.AndroidUtilities.dp;
import static org.telegram.messenger.LocaleController.getString;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.os.Build;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.LeadingMarginSpan;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.FileLog;
import org.telegram.messenger.LocaleController;
import org.telegram.messenger.R;
import org.telegram.ui.ActionBar.Theme;
import org.telegram.ui.Components.TypefaceSpan;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.util.List;

import zxc.iconic.xenon.NekoConfig;

public class TypefaceHelper {

    private static final String TEST_TEXT;
    private static final int CANVAS_SIZE = 40;
    private static final Paint PAINT = new Paint() {{
        setTextSize(20);
        setAntiAlias(false);
        setSubpixelText(false);
        setFakeBoldText(false);
    }};
    private static final String EMOJI_FONT_AOSP = "NotoColorEmoji.ttf";

    private static Boolean mediumWeightSupported = null;
    private static Boolean italicSupported = null;

    private static Typeface systemEmojiTypeface;
    private static boolean loadSystemEmojiFailed = false;

    static {
        var lang = LocaleController.getInstance().getCurrentLocale().getLanguage();
        if (List.of("zh", "ja", "ko").contains(lang)) {
            TEST_TEXT = "你好";
        } else if (List.of("ar", "fa").contains(lang)) {
            TEST_TEXT = "مرحبا";
        } else if ("iw".equals(lang)) {
            TEST_TEXT = "שלום";
        } else if ("th".equals(lang)) {
            TEST_TEXT = "สวัสดี";
        } else if ("hi".equals(lang)) {
            TEST_TEXT = "नमस्ते";
        } else if (List.of("ru", "uk", "ky", "be", "sr").contains(lang)) {
            TEST_TEXT = "Привет";
        } else {
            TEST_TEXT = "R";
        }
    }

    public static SpannableStringBuilder getTitleText() {
        var builder = new SpannableStringBuilder(getString(R.string.AppName));
        builder.setSpan(new LeadingMarginSpan.Standard(dp(2), 0), 0, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        builder.setSpan(new TypefaceSpan(TypefaceHelper.createRoundedTypeface(550, false), 0, Theme.key_telegram_color_dialogsLogo, null), 0, builder.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return builder;
    }

    public static Typeface getSystemEmojiTypeface() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return null;
        }
        if (!loadSystemEmojiFailed && systemEmojiTypeface == null) {
            var font = getSystemEmojiFontPathLegacy();
            if (font != null) {
                systemEmojiTypeface = Typeface.createFromFile(font);
            }
            if (systemEmojiTypeface == null) {
                loadSystemEmojiFailed = true;
            }
        }
        return systemEmojiTypeface;
    }

    private static File getSystemEmojiFontPathLegacy() {
        try (var br = new BufferedReader(new FileReader("/system/etc/fonts.xml"))) {
            String line;
            var ignored = false;
            while ((line = br.readLine()) != null) {
                var trimmed = line.trim();
                if (trimmed.startsWith("<family") && trimmed.contains("ignore=\"true\"")) {
                    ignored = true;
                } else if (trimmed.startsWith("</family>")) {
                    ignored = false;
                } else if (trimmed.startsWith("<font") && !ignored) {
                    var start = trimmed.indexOf(">");
                    var end = trimmed.indexOf("<", 1);
                    if (start > 0 && end > 0) {
                        var font = trimmed.substring(start + 1, end);
                        if (font.toLowerCase().contains("emoji")) {
                            File file = new File("/system/fonts/" + font);
                            if (file.exists()) {
                                FileLog.d("emoji font file fonts.xml = " + font);
                                return file;
                            }
                        }
                    }
                }
            }
            br.close();

            var fileAOSP = new File("/system/fonts/" + EMOJI_FONT_AOSP);
            if (fileAOSP.exists()) {
                return fileAOSP;
            }
        } catch (Exception e) {
            FileLog.e(e);
        }
        return null;
    }

    public static void clearFontSupportCache() {
        mediumWeightSupported = null;
        italicSupported = null;
    }

    public static Typeface createTypeface(int weight, boolean italic) {
        Typeface custom = ClientFonts.get(weight, italic);
        if (custom != null) return custom;
        return createSystemTypeface(weight, italic);
    }

    private static final java.util.WeakHashMap<Typeface, Integer> roundedGenerations = new java.util.WeakHashMap<>();

    public static boolean isCurrentRoundedTypeface(Typeface typeface) {
        synchronized (roundedVariants) {
            Integer generation = roundedGenerations.get(typeface);
            return generation != null && generation == ClientFonts.generation();
        }
    }

    public static void rememberRoundedTypeface(Typeface typeface) {
        synchronized (roundedVariants) {
            roundedGenerations.put(typeface, ClientFonts.generation());
        }
    }

    private static final android.util.LruCache<Typeface, Boolean> roundedSupport = new android.util.LruCache<>(16);
    private static final android.util.LruCache<String, Typeface> roundedVariants = new android.util.LruCache<>(512);

    /** Probe the selected family, including OEM system replacements, through public APIs. */
    private static Typeface bundledFlex;

    private static Typeface cachedFlexBase;
    private static int flexBaseGeneration = -1;

    private static synchronized Typeface flexBase() {
        int generation = ClientFonts.generation();
        if (cachedFlexBase != null && flexBaseGeneration == generation) return cachedFlexBase;
        Typeface result;
        if (ClientFonts.useGoogleSans()) {
            Typeface system = createSystemTypeface(400, false);
            Paint probe = new Paint();
            probe.setTypeface(system);
            if (Build.VERSION.SDK_INT >= 26 && probe.setFontVariationSettings("'ROND' 100")) {
                result = system;
            } else {
                if (bundledFlex == null) bundledFlex = Typeface.createFromAsset(
                        org.telegram.messenger.ApplicationLoader.applicationContext.getAssets(), "fonts/GoogleSansFlex.ttf");
                result = bundledFlex;
            }
        } else {
            result = createTypeface(400, false);
        }
        cachedFlexBase = result;
        flexBaseGeneration = generation;
        return result;
    }

    public static boolean supportsRoundedTypeface() {
        if (Build.VERSION.SDK_INT < 26) return false;
        Typeface base = flexBase();
        synchronized (roundedSupport) {
            Boolean supported = roundedSupport.get(base);
            if (supported == null) {
                Paint probe = new Paint();
                probe.setTypeface(base);
                supported = probe.setFontVariationSettings("'ROND' 100")
                        && probe.setFontVariationSettings("'wght' 500");
                roundedSupport.put(base, supported);
            }
            return supported;
        }
    }

    public static Typeface createRoundedTypeface(int weight, boolean italic) {
        if (!supportsRoundedTypeface()) return createTypeface(weight, italic);
        String key = ClientFonts.generation() + ":" + weight + ":" + italic;
        synchronized (roundedVariants) {
            Typeface result = roundedVariants.get(key);
            if (result == null) {
                // Variation settings on the primary face do not reach Android's fallback
                // fonts. Give Google Sans its own weight so mixed-script titles stay bold.
                if (Build.VERSION.SDK_INT >= 29 && (ClientFonts.useGoogleSans() || ClientFonts.selectedId().isEmpty())) {
                    result = GoogleSansFallback.create(weight, italic);
                }
                if (result == null) {
                    Paint paint = new Paint();
                    Typeface source = flexBase();
                    paint.setTypeface(Build.VERSION.SDK_INT >= 28 ? Typeface.create(source, weight, italic) : source);
                    paint.setFontVariationSettings("'ROND' 100, 'wght' " + weight);
                    result = paint.getTypeface();
                }
                roundedVariants.put(key, result);
                roundedGenerations.put(result, ClientFonts.generation());
            }
            return result;
        }
    }

    /** Isolated from the legacy path: custom fallback families require Android 10. */
    private static final class GoogleSansFallback {
        private static android.graphics.fonts.Font flexSource;
        private static android.graphics.fonts.Font fallbackSource;

        private static android.graphics.fonts.Font.Builder variantBuilder(android.graphics.fonts.Font source) {
            // Both paths share the original direct buffer instead of copying the asset
            // for every intermediate weight produced by a tab-selection animation.
            return Build.VERSION.SDK_INT >= 31 ? new android.graphics.fonts.Font.Builder(source)
                    : new android.graphics.fonts.Font.Builder(source.getBuffer());
        }

        static Typeface create(int weight, boolean italic) {
            try {
                android.content.res.AssetManager assets = org.telegram.messenger.ApplicationLoader.applicationContext.getAssets();
                if (flexSource == null) {
                    flexSource = new android.graphics.fonts.Font.Builder(assets, "fonts/GoogleSansFlex.ttf").build();
                }
                if (fallbackSource == null) {
                    fallbackSource = new android.graphics.fonts.Font.Builder(assets, "fonts/GoogleSans.ttf").build();
                }
                int slant = italic ? android.graphics.fonts.FontStyle.FONT_SLANT_ITALIC
                        : android.graphics.fonts.FontStyle.FONT_SLANT_UPRIGHT;
                android.graphics.fonts.Font flex = variantBuilder(flexSource)
                        .setWeight(weight).setSlant(slant)
                        .setFontVariationSettings("'ROND' 100, 'wght' " + weight).build();
                android.graphics.fonts.Font fallback = variantBuilder(fallbackSource)
                        .setWeight(weight).setSlant(slant)
                        .setFontVariationSettings("'wght' " + Math.max(400, Math.min(700, weight)) + ", 'opsz' 18, 'GRAD' 0").build();
                return new Typeface.CustomFallbackBuilder(new android.graphics.fonts.FontFamily.Builder(flex).build())
                        .addCustomFallback(new android.graphics.fonts.FontFamily.Builder(fallback).build())
                        .setSystemFallback("sans-serif")
                        .setStyle(new android.graphics.fonts.FontStyle(weight, slant)).build();
            } catch (java.io.IOException | IllegalArgumentException error) {
                FileLog.e(error);
                return null;
            }
        }
    }

    public static Typeface createSystemTypeface(int weight, boolean italic) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return Typeface.create(null, weight, italic);
        }
        var family = switch (weight) {
            case 800 -> "sans-serif-black";
            case 500 -> "sans-serif-medium";
            default -> "sans-serif";
        };
        return Typeface.create(family, italic ? Typeface.ITALIC : Typeface.NORMAL);
    }

    public static boolean isMediumWeightSupported() {
        if (mediumWeightSupported == null) {
            mediumWeightSupported = !NekoConfig.forceFontWeightFallback && testTypeface(createTypeface(500, false));
            FileLog.d("mediumWeightSupported = " + mediumWeightSupported);
        }
        return mediumWeightSupported;
    }

    public static boolean isItalicSupported() {
        if (italicSupported == null) {
            italicSupported = testTypeface(createTypeface(400, true));
            FileLog.d("italicSupported = " + italicSupported);
        }
        return italicSupported;
    }

    private static boolean testTypeface(Typeface typeface) {
        Canvas canvas = new Canvas();

        Bitmap bitmap1 = Bitmap.createBitmap(CANVAS_SIZE * 2, CANVAS_SIZE, Bitmap.Config.ARGB_8888);
        canvas.setBitmap(bitmap1);
        PAINT.setTypeface(null);
        canvas.drawText(TEST_TEXT, 0, CANVAS_SIZE, PAINT);

        Bitmap bitmap2 = Bitmap.createBitmap(CANVAS_SIZE * 2, CANVAS_SIZE, Bitmap.Config.ARGB_8888);
        canvas.setBitmap(bitmap2);
        PAINT.setTypeface(typeface);
        canvas.drawText(TEST_TEXT, 0, CANVAS_SIZE, PAINT);

        boolean supported = !bitmap1.sameAs(bitmap2);
        AndroidUtilities.recycleBitmaps(List.of(bitmap1, bitmap2));
        return supported;
    }
}
