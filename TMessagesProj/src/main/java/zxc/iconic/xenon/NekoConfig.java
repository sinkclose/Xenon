package zxc.iconic.xenon;

import android.app.Activity;
import android.content.SharedPreferences;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.ToNumberPolicy;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.ApplicationLoader;
import org.telegram.messenger.BuildConfig;
import org.telegram.messenger.FileLog;
import org.telegram.ui.ActionBar.Theme;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;

import app.nekogram.translator.DeepLTranslator;
import zxc.iconic.xenon.helpers.AnalyticsHelper;
import zxc.iconic.xenon.helpers.CloudSettingsHelper;
import zxc.iconic.xenon.helpers.LensHelper;
import zxc.iconic.xenon.translator.Translator;
import zxc.iconic.xenon.translator.TranslatorApps;

public class NekoConfig {
    //TODO: refactor

    public static final int TITLE_TYPE_TEXT = 0;
    public static final int TITLE_TYPE_ICON = 1;
    public static final int TITLE_TYPE_MIX = 2;

    public static final int ID_TYPE_HIDDEN = 0;
    public static final int ID_TYPE_API = 1;
    public static final int ID_TYPE_BOTAPI = 2;

    public static final int TRANS_TYPE_NEKO = 0;
    public static final int TRANS_TYPE_TG = 1;
    public static final int TRANS_TYPE_EXTERNAL = 2;

    public static final int DOUBLE_TAP_ACTION_NONE = 0;
    public static final int DOUBLE_TAP_ACTION_REACTION = 1;
    public static final int DOUBLE_TAP_ACTION_TRANSLATE = 2;
    public static final int DOUBLE_TAP_ACTION_REPLY = 3;
    public static final int DOUBLE_TAP_ACTION_SAVE = 4;
    public static final int DOUBLE_TAP_ACTION_REPEAT = 5;
    public static final int DOUBLE_TAP_ACTION_EDIT = 6;

    public static final int TABLET_AUTO = 0;
    public static final int TABLET_ENABLE = 1;
    public static final int TABLET_DISABLE = 2;

    public static final int BOOST_NONE = 0;
    public static final int BOOST_AVERAGE = 1;
    public static final int BOOST_EXTREME = 2;

    public static final int TRANSCRIBE_AUTO = 0;
    public static final int TRANSCRIBE_PREMIUM = 1;
    public static final int TRANSCRIBE_WORKERSAI = 2;

    public static final int CAMERA_FRONT = 0;
    public static final int CAMERA_REAR = 1;
    public static final int CAMERA_ASK = 2;

    public static final int TEXT_SPOILER_DEFAULT = 0;
    public static final int TEXT_SPOILER_SIMPLE = 1;
    public static final int TEXT_SPOILER_EPSTEIN = 2;

    public static final int MEDIA_SPOILER_TELEGRAM = 0;
    public static final int MEDIA_SPOILER_PILL = 1;
    public static final int MEDIA_SPOILER_CIRCLE = 2;

    private static final Object sync = new Object();
    public static boolean preferIPv6 = false;

    public static boolean useSystemEmoji = false;
    public static boolean ignoreBlocked = false;
    public static boolean hideKeyboardOnChatScroll = false;
    public static boolean hideAllTab = false;
    public static boolean confirmAVMessage = false;
    public static boolean askBeforeCall = true;
    public static boolean disableNumberRounding = false;
    public static boolean disableGreetingSticker = false;
    public static boolean autoTranslate = true;
    public static boolean showRPCError = false;
    public static boolean enableSaveDeletedMessages = false;
    public static float stickerSize = 14.0f;
    public static String translationProvider = Translator.PROVIDER_GOOGLE;
    public static String translationTarget = "app";
    public static int tabsTitleType = TITLE_TYPE_MIX;
    public static int idType = ID_TYPE_API;
    public static int maxRecentStickers = 20;
    public static int transType = TRANS_TYPE_NEKO;
    public static int doubleTapInAction = DOUBLE_TAP_ACTION_REACTION;
    public static int doubleTapOutAction = DOUBLE_TAP_ACTION_REACTION;
    public static int downloadSpeedBoost = BOOST_NONE;
    public static Set<String> restrictedLanguages;
    public static String externalTranslationProvider;
    public static int transcribeProvider = TRANSCRIBE_PREMIUM;
    public static String cfAccountID = "";
    public static String cfApiToken = "";
    public static int cameraInVideoMessages = CAMERA_FRONT;
    public static int textSpoilerMode = TEXT_SPOILER_DEFAULT;
    public static int mediaSpoilerMode = MEDIA_SPOILER_TELEGRAM;
    public static boolean spoilerExtendToLineEnd = false;

    public static boolean showAddToSavedMessages = true;
    public static boolean showAddToSavedMessagesInGroups = false;
    public static boolean showSetReminder = false;
    public static boolean showReport = false;
    public static boolean showPrPr = false;
    public static boolean showDeleteDownloadedFile = false;
    public static boolean showMessageDetails = false;
    public static boolean showTranslate = true;
    public static boolean showRepeat = true;
    public static boolean showNoQuoteForward = false;
    public static boolean showCopyPhoto = false;
    public static boolean showQrCode = false;
    public static boolean showOpenIn = false;

    public static int tabletMode = TABLET_AUTO;
    public static boolean openArchiveOnPull = false;
    public static int nameOrder = 1;
    public static boolean disableAppBarShadow = false;
    public static boolean hideRecordButton = false;
    public static boolean mediaPreview = true;
    public static boolean autoPauseVideo = true;
    public static boolean disableProximityEvents = false;
    public static boolean useCamera2Api = false;
    public static boolean voiceEnhancements = false;
    public static boolean disableInstantCamera = false;
    public static boolean hideCameraInMediaPicker = false;
    public static boolean tryToOpenAllLinksInIV = false;
    public static boolean formatTimeWithSeconds = false;
    public static boolean accentAsNotificationColor = false;
    public static boolean silenceNonContacts = false;
    public static boolean disableJumpToNextChannel = false;
    public static boolean autoDownloadUpdate = false;
    public static boolean autoCheckUpdate = true;
    public static boolean disableVoiceMessageAutoPlay = false;
    public static boolean unmuteVideosWithVolumeButtons = true;
    public static boolean disableMarkdownByDefault = false;
    public static boolean hideTimeOnSticker = false;
    public static boolean showOriginal = true;
    public static boolean newMarkdownParser = true;
    public static boolean markdownParseLinks = true;
    public static boolean hideStories = false;
    public static boolean quickForward = false;
    public static boolean reducedColors = false;
    public static boolean ignoreContentRestriction = false;
    public static boolean showTimeHint = false;
    public static boolean preferOriginalQuality = false;
    public static boolean forceFontWeightFallback = false;
    public static boolean minimizedStickerCreator = false;
    public static boolean hideChannelBottomButtons = false;
    public static boolean keepFormatting = true;
    public static boolean predictiveBackAnimation = false;
    public static boolean bottomFilterTabs = false;
    public static boolean strokeOnViews = true;
    public static boolean disableGooeyAvatarAnimation = false;
    public static int gooeyAvatarOffset = 0;
    public static boolean showMainTabs = true;
    public static boolean showMainTabsTitle = true;
    public static boolean dynamicTabSize = false;
    public static boolean telegaDetectorEnabled = false;
    public static boolean disableTypingIndicator = false;
    public static boolean ghostModeEnabled = false;
    public static boolean bypassBlocking = false;
    public static boolean pluginsEnabled = false;
    /**
     * When true, {@code PluginManager.hasScope} short-circuits to true for every
     * scope, letting a plugin call any API regardless of its declared
     * {@code plugin_scopes}. This does NOT weaken the Lua sandbox — {@code io},
     * {@code os.execute} etc. stay blocked, so the session token and phone number
     * remain unreachable. Intended for trusted plugins that need full access.
     */
    public static boolean pluginGodMode = false;
    public static boolean hidePhoneNumber = false;
    public static boolean removeAds = false;
    public static boolean textAnimationEnabled = false;
    public static int textAnimCursorSpeed = 80;
    public static int textAnimFadeDuration = 300;
    public static int textAnimBlurStrength = 20;
    public static int textAnimBlurDuration = 350;
    public static int alternativeTransitionSpeed = 300;
    public static String alternativeTransitionEase = "0.37,0.01,0.1,1";
    public static boolean material3Switches = false;
    public static boolean m3SectionsStyle = false;
    public static boolean material3ChatHeaders = false;
    public static boolean materialSliders = false;
    public static boolean centerChatHeader = false;
    public static boolean biggerAvatar = false;
    public static boolean blurredFadeView = false;
    public static int blurredFadeBlurStrength = 20;
    public static int blurredFadePixelation = 1;
    public static boolean blurredFadeDimming = false;
    public static int blurredFadeDimStrength = 50;
    public static boolean progressiveFadeBlur = false;
    public static int progressiveFadeBlurMaxRadius = 20;
    public static final int AVATAR_PLACEMENT_LEFT = 0;
    public static final int AVATAR_PLACEMENT_CENTER = 1;
    public static final int AVATAR_PLACEMENT_RIGHT = 2;
    public static int avatarPlacement = AVATAR_PLACEMENT_LEFT;
    public static final int ANIMATION_STYLE_DEFAULT = 0;
    public static final int ANIMATION_STYLE_IOS = 1;
    public static final int ANIMATION_STYLE_AOSP = 2;
    public static final int ANIMATION_STYLE_AOSP_ALT = 3;
    public static final int ANIMATION_STYLE_FADE = 4;
    public static int openAnimationStyle = ANIMATION_STYLE_DEFAULT;
    public static int closeAnimationStyle = ANIMATION_STYLE_DEFAULT;
    public static int predictiveBackAnimationStyle = ANIMATION_STYLE_DEFAULT;
    public static int predictiveBackIntensity = 0;
    public static int fadeDuration = 300;
    public static boolean removeChatDelay = false;
    public static boolean showOnlineDotsInChat = false;
    public static boolean optimizedPushService = false;
    private static final String XRAY_DEFAULT_CHECK_URL = "https://www.gstatic.com/generate_204";

    public static boolean xrayAppProxyEnabled = false;
    public static boolean xrayVpnMode = false;
    public static int xrayAppProxyLocalPort = 10808;
    public static String xrayAppProxyConfigJson = "";
    public static String xrayAppProxyCheckUrl = XRAY_DEFAULT_CHECK_URL;

    public static final int DEFAULT_ADVANCED_GLASS_ALPHA = 100;
    public static final int DEFAULT_ADVANCED_GLASS_BLUR = 3;

    public static final int DEFAULT_BLUR_STRENGTH = 30;
    public static int blurStrength = DEFAULT_BLUR_STRENGTH;
    public static final boolean DEFAULT_ADVANCED_GLASS_WALLPAPER_BLUR = true;
    public static final float DEFAULT_ADVANCED_GLASS_DISPERSION = 1.0f;
    public static final float DEFAULT_ADVANCED_GLASS_FRESNEL = 1.0f;
    public static final float DEFAULT_ADVANCED_GLASS_GLARE = 1.0f;
    public static final int DEFAULT_ADVANCED_GLASS_TINT_PERCENT = 20;
    public static final boolean DEFAULT_ADVANCED_GLASS_TINT_BLACK_WHITE = false;
    public static final boolean DEFAULT_GLASS_BOTTOM_SHEET = false;

    public static final int GLASS_GLARE_FULL = 0;
    public static final int GLASS_GLARE_SOLID = 1;
    public static final int GLASS_GLARE_DISABLE = 2;

    public static float liquidGlassIntensity = 0.75f;
    public static int liquidGlassThickness = 11;
    public static boolean useAdvancedLiquidGlass = false;
    
    // Advanced liquid glass parameters (separate from standard)
    public static int advancedGlassAlpha = DEFAULT_ADVANCED_GLASS_ALPHA;
    public static int advancedGlassBlur = DEFAULT_ADVANCED_GLASS_BLUR;
    public static boolean advancedGlassWallpaperBlur = DEFAULT_ADVANCED_GLASS_WALLPAPER_BLUR;
    public static float advancedGlassDispersion = DEFAULT_ADVANCED_GLASS_DISPERSION;
    public static float advancedGlassFresnel = DEFAULT_ADVANCED_GLASS_FRESNEL;
    public static float advancedGlassGlare = DEFAULT_ADVANCED_GLASS_GLARE;
    public static int advancedGlassTintPercent = DEFAULT_ADVANCED_GLASS_TINT_PERCENT;
    public static boolean advancedGlassTintBlackWhite = DEFAULT_ADVANCED_GLASS_TINT_BLACK_WHITE;
    public static boolean glassBottomSheet = DEFAULT_GLASS_BOTTOM_SHEET;
    public static int glassGlareMode = GLASS_GLARE_FULL;

    public static boolean forceBlurLiquidGlass = false;
    public static boolean blurOverlay = false;
    public static boolean blurPopupInChat = false;
    public static int blurOverlayRadius = 10;
    public static boolean blurOverlayRefresh = true;
    public static int blurOverlayRefreshInterval = 2;
    public static int blurAnimationDuration = 500;
    public static boolean blurSmoothly = false;
    public static boolean disableBlurBs = false;
    public static int blurPixelation = 0;
    public static boolean replaceDialogsWithSheet = false;
    public static boolean material3Dialogs = false;
    public static boolean keepUnreadChatsOnTop = false;
    public static boolean keepUnreadArchivedOnTop = false;
    public static boolean roundedBulletin = false;
    public static boolean nonIslandTabBars = false;
    public static boolean nonIslandGlobalSearch = false;
    public static boolean nonIslandChatElements = false;
    public static boolean hideFadeView = false;
    public static boolean disableGlassGlare = true;
    public static boolean disableScrimBlur = false;
    public static boolean material3BottomNavigationBar = false;
    public static boolean md3PlayerSeekBar = false;
    public static boolean md3Folders = false;
    public static int avatarShape = 0;
    public static boolean avatarShapeInChatList = true;
    public static boolean avatarShapeInChatMessages = true;
    public static boolean rotateAvatarShape = false;
    public static int avatarShapeRotationSpeed = 60;
    public static boolean avatarShapeSquareBase = false;
    public static boolean wavyEnabled = true;
    public static boolean holdToOpenPopup = false;
    public static float popupHoldTime = 0.5f;

    public static int userMcc = 0;

    private static final SharedPreferences.OnSharedPreferenceChangeListener listener = (preferences, key) -> {
        var map = new HashMap<String, String>(1);
        map.put("key", key);
        AnalyticsHelper.trackEvent("neko_config_changed", map);

        CloudSettingsHelper.getInstance().doAutoSync();
    };
    private static boolean configLoaded;

    static {
        loadConfig(false);
    }

    public static void loadConfig(boolean force) {
        synchronized (sync) {
            if (configLoaded && !force) {
                return;
            }
            userMcc = ApplicationLoader.applicationContext.getResources().getConfiguration().mcc;

            SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
            preferIPv6 = preferences.getBoolean("preferIPv6", false);
            ignoreBlocked = preferences.getBoolean("ignoreBlocked2", false);
            tabletMode = preferences.getInt("tabletMode", TABLET_AUTO);
            nameOrder = preferences.getInt("nameOrder", 1);
            showAddToSavedMessages = preferences.getBoolean("showAddToSavedMessages", true);
            showAddToSavedMessagesInGroups = preferences.getBoolean("showAddToSavedMessagesInGroups", false);
            showSetReminder = preferences.getBoolean("showSetReminder", false);
            showReport = preferences.getBoolean("showReport", false);
            showPrPr = preferences.getBoolean("showPrPr", false);
            showDeleteDownloadedFile = preferences.getBoolean("showDeleteDownloadedFile", false);
            showMessageDetails = preferences.getBoolean("showMessageDetails", false);
            showTranslate = preferences.getBoolean("showTranslate", true);
            showRepeat = preferences.getBoolean("showRepeat", true);
            stickerSize = preferences.getFloat("stickerSize", 14.0f);
            translationProvider = preferences.getString("translationProvider2", Translator.PROVIDER_GOOGLE);
            openArchiveOnPull = preferences.getBoolean("openArchiveOnPull", false);
            hideKeyboardOnChatScroll = preferences.getBoolean("hideKeyboardOnChatScroll", false);
            useSystemEmoji = preferences.getBoolean("useSystemEmoji", false);
            hideAllTab = preferences.getBoolean("hideAllTab", false);
            tabsTitleType = preferences.getInt("tabsTitleType2", TITLE_TYPE_MIX);
            confirmAVMessage = preferences.getBoolean("confirmAVMessage", false);
            askBeforeCall = preferences.getBoolean("askBeforeCall", true);
            forceBlurLiquidGlass = preferences.getBoolean("forceBlurLiquidGlass", false);
            blurOverlay = preferences.getBoolean("blurOverlay", false);
            blurPopupInChat = preferences.getBoolean("blurPopupInChat", false);
            blurOverlayRadius = preferences.getInt("blurOverlayRadius", 10);
            blurOverlayRefresh = preferences.getBoolean("blurOverlayRefresh", true);
            blurOverlayRefreshInterval = preferences.getInt("blurOverlayRefreshInterval", 2);
            blurAnimationDuration = preferences.getInt("blurAnimationDuration", 500);
            blurSmoothly = preferences.getBoolean("blurSmoothly", false);
            disableBlurBs = preferences.getBoolean("disableBlurBs", false);
            blurPixelation = preferences.getInt("blurPixelation", 0);
            replaceDialogsWithSheet = preferences.getBoolean("replaceDialogsWithSheet", false);
            material3Dialogs = preferences.getBoolean("material3Dialogs", false);
            keepUnreadChatsOnTop = preferences.getBoolean("keepUnreadChatsOnTop", false);
            keepUnreadArchivedOnTop = preferences.getBoolean("keepUnreadArchivedOnTop", false);
            disableNumberRounding = preferences.getBoolean("disableNumberRounding", false);
            disableAppBarShadow = preferences.getBoolean("disableAppBarShadow", false);
            hideRecordButton = preferences.getBoolean("hideRecordButton", false);
            mediaPreview = preferences.getBoolean("mediaPreview", true);
            idType = preferences.getInt("idType", ID_TYPE_API);
            autoPauseVideo = preferences.getBoolean("autoPauseVideo", true);
            disableProximityEvents = preferences.getBoolean("disableProximityEvents", false);
            useCamera2Api = preferences.getBoolean("useCamera2Api", false);
            voiceEnhancements = preferences.getBoolean("voiceEnhancements", false);
            disableInstantCamera = preferences.getBoolean("disableInstantCamera", false);
            hideCameraInMediaPicker = preferences.getBoolean("hideCameraInMediaPicker", false);
            tryToOpenAllLinksInIV = preferences.getBoolean("tryToOpenAllLinksInIV", false);
            formatTimeWithSeconds = preferences.getBoolean("formatTimeWithSeconds", false);
            accentAsNotificationColor = preferences.getBoolean("accentAsNotificationColor", false);
            silenceNonContacts = preferences.getBoolean("silenceNonContacts", false);
            showNoQuoteForward = preferences.getBoolean("showNoQuoteForward", false);
            translationTarget = preferences.getString("translationTarget", "app");
            maxRecentStickers = preferences.getInt("maxRecentStickers", 20);
            disableJumpToNextChannel = preferences.getBoolean("disableJumpToNextChannel", false);
            autoDownloadUpdate = preferences.getBoolean("autoDownloadUpdate", false);
            autoCheckUpdate = preferences.getBoolean("autoCheckUpdate", true);
            disableGreetingSticker = preferences.getBoolean("disableGreetingSticker", false);
            autoTranslate = preferences.getBoolean("autoTranslate", true);
            disableVoiceMessageAutoPlay = preferences.getBoolean("disableVoiceMessageAutoPlay", false);
            unmuteVideosWithVolumeButtons = preferences.getBoolean("unmuteVideosWithVolumeButtons", true);
            transType = preferences.getInt("transType", TRANS_TYPE_NEKO);
            showCopyPhoto = preferences.getBoolean("showCopyPhoto", false);
            doubleTapInAction = preferences.getInt("doubleTapAction", DOUBLE_TAP_ACTION_REACTION);
            doubleTapOutAction = preferences.getInt("doubleTapOutAction", doubleTapInAction);
            restrictedLanguages = preferences.getStringSet("restrictedLanguages", null);
            disableMarkdownByDefault = preferences.getBoolean("disableMarkdownByDefault", false);
            showRPCError = preferences.getBoolean("showRPCError", false);
            enableSaveDeletedMessages = preferences.getBoolean("enableSaveDeletedMessages", false);
            hideTimeOnSticker = preferences.getBoolean("hideTimeOnSticker", false);
            showOriginal = preferences.getBoolean("showOriginal", true);
            newMarkdownParser = preferences.getBoolean("newMarkdownParser", true);
            markdownParseLinks = preferences.getBoolean("markdownParseLinks", true);
            downloadSpeedBoost = preferences.getInt("downloadSpeedBoost2", BOOST_NONE);
            showQrCode = preferences.getBoolean("showQrCode", false);
            showOpenIn = preferences.getBoolean("showOpenIn", false);
            hideStories = preferences.getBoolean("hideStories", false);
            quickForward = preferences.getBoolean("quickForward", false);
            reducedColors = preferences.getBoolean("reducedColors", false);
            ignoreContentRestriction = preferences.getBoolean("ignoreContentRestriction", false);
            externalTranslationProvider = preferences.getString("externalTranslationProvider", "");
            TranslatorApps.loadTranslatorAppsAsync();
            showTimeHint = preferences.getBoolean("showTimeHint", false);
            transcribeProvider = preferences.getInt("transcribeProvider", TRANSCRIBE_PREMIUM);
            cfAccountID = preferences.getString("cfAccountID", "");
            cfApiToken = preferences.getString("cfApiToken", "");
            if (transcribeProvider == 3) {
                transcribeProvider = TRANSCRIBE_PREMIUM;
            }
            preferOriginalQuality = preferences.getBoolean("preferOriginalQuality", false);
            forceFontWeightFallback = preferences.getBoolean("forceFontWeightFallback", false);
            minimizedStickerCreator = preferences.getBoolean("minimizedStickerCreator", false);
            hideChannelBottomButtons = preferences.getBoolean("hideChannelBottomButtons", false);
            keepFormatting = preferences.getBoolean("keepFormatting", true);
            predictiveBackAnimation = preferences.getBoolean("predictiveBackAnimation", false);
            bottomFilterTabs = preferences.getBoolean("bottomFilterTabs", false);
            strokeOnViews = preferences.getBoolean("strokeOnViews", true);
            disableGooeyAvatarAnimation = preferences.getBoolean("disableGooeyAvatarAnimation", false);
            gooeyAvatarOffset = preferences.getInt("gooeyAvatarOffset", 0);
            showMainTabs = preferences.getBoolean("showMainTabs", true);
            showMainTabsTitle = preferences.getBoolean("showMainTabsTitle", true);
            dynamicTabSize = preferences.getBoolean("dynamicTabSize", false);
            telegaDetectorEnabled = preferences.getBoolean("telegaDetectorEnabled", false);
            disableTypingIndicator = preferences.getBoolean("disableTypingIndicator", false);
            ghostModeEnabled = preferences.getBoolean("ghostModeEnabled", false);
            bypassBlocking = preferences.getBoolean("bypassBlocking", false);
            pluginsEnabled = preferences.getBoolean("pluginsEnabled", false);
            pluginGodMode = preferences.getBoolean("pluginGodMode", false);
            hidePhoneNumber = preferences.getBoolean("hidePhoneNumber", false);
            xrayAppProxyEnabled = preferences.getBoolean("xrayAppProxyEnabled", false);
            xrayVpnMode = preferences.getBoolean("xrayVpnMode", false);
            xrayAppProxyLocalPort = preferences.getInt("xrayAppProxyLocalPort", 10808);
            xrayAppProxyConfigJson = preferences.getString("xrayAppProxyConfigJson", "");
            xrayAppProxyCheckUrl = normalizeXrayCheckUrl(preferences.getString("xrayAppProxyCheckUrl", XRAY_DEFAULT_CHECK_URL));
            liquidGlassIntensity = preferences.getFloat("liquidGlassIntensity", 0.75f);
            liquidGlassThickness = preferences.getInt("liquidGlassThickness", 11);
            useAdvancedLiquidGlass = preferences.getBoolean("useAdvancedLiquidGlass", false);
            advancedGlassAlpha = preferences.getInt("advancedGlassAlpha", DEFAULT_ADVANCED_GLASS_ALPHA);
            advancedGlassBlur = preferences.getInt("advancedGlassBlur", DEFAULT_ADVANCED_GLASS_BLUR);
            blurStrength = preferences.getInt("blurStrength", DEFAULT_BLUR_STRENGTH);
            advancedGlassWallpaperBlur = preferences.getBoolean("advancedGlassWallpaperBlur", DEFAULT_ADVANCED_GLASS_WALLPAPER_BLUR);
            advancedGlassDispersion = preferences.getFloat("advancedGlassDispersion", DEFAULT_ADVANCED_GLASS_DISPERSION);
            advancedGlassFresnel = preferences.getFloat("advancedGlassFresnel", DEFAULT_ADVANCED_GLASS_FRESNEL);
            advancedGlassGlare = preferences.getFloat("advancedGlassGlare", DEFAULT_ADVANCED_GLASS_GLARE);
            advancedGlassTintPercent = preferences.getInt("advancedGlassTintPercent", DEFAULT_ADVANCED_GLASS_TINT_PERCENT);
            advancedGlassTintBlackWhite = preferences.getBoolean("advancedGlassTintBlackWhite", DEFAULT_ADVANCED_GLASS_TINT_BLACK_WHITE);
            glassBottomSheet = preferences.getBoolean("glassBottomSheet", DEFAULT_GLASS_BOTTOM_SHEET);
            cameraInVideoMessages = preferences.getInt("cameraInVideoMessages", CAMERA_FRONT);
            textSpoilerMode = preferences.getInt("textSpoilerMode", TEXT_SPOILER_DEFAULT);
            mediaSpoilerMode = preferences.getInt("mediaSpoilerMode", MEDIA_SPOILER_TELEGRAM);
            spoilerExtendToLineEnd = preferences.getBoolean("spoilerExtendToLineEnd", false);
            removeAds = preferences.getBoolean("removeAds", false);
            textAnimationEnabled = preferences.getBoolean("textAnimationEnabled", false);
            textAnimCursorSpeed = preferences.getInt("textAnimCursorSpeed", 80);
            textAnimFadeDuration = preferences.getInt("textAnimFadeDuration", 300);
            textAnimBlurStrength = preferences.getInt("textAnimBlurStrength", 20);
            textAnimBlurDuration = preferences.getInt("textAnimBlurDuration", 350);
            alternativeTransitionSpeed = preferences.getInt("alternativeTransitionSpeed", 300);
            alternativeTransitionEase = preferences.getString("alternativeTransitionEase", "0.37,0.01,0.1,1");
            material3Switches = preferences.getBoolean("material3Switches", false);
            m3SectionsStyle = preferences.getBoolean("m3SectionsStyle", false);
            material3ChatHeaders = preferences.getBoolean("material3ChatHeaders", false);
            materialSliders = preferences.getBoolean("materialSliders", false);
            centerChatHeader = preferences.getBoolean("centerChatHeader", false);
            avatarPlacement = preferences.getInt("avatarPlacement", AVATAR_PLACEMENT_LEFT);
            biggerAvatar = preferences.getBoolean("biggerAvatar", false);
            blurredFadeView = preferences.getBoolean("blurredFadeView", false);
            blurredFadeBlurStrength = preferences.getInt("blurredFadeBlurStrength", 20);
            blurredFadePixelation = preferences.getInt("blurredFadePixelation", 1);
            blurredFadeDimming = preferences.getBoolean("blurredFadeDimming", false);
            blurredFadeDimStrength = preferences.getInt("blurredFadeDimStrength", 50);
            progressiveFadeBlur = preferences.getBoolean("progressiveFadeBlur", false);
            progressiveFadeBlurMaxRadius = preferences.getInt("progressiveFadeBlurMaxRadius", 20);
            openAnimationStyle = preferences.getInt("openAnimationStyle", ANIMATION_STYLE_DEFAULT);
            closeAnimationStyle = preferences.getInt("closeAnimationStyle", ANIMATION_STYLE_DEFAULT);
            predictiveBackAnimationStyle = preferences.getInt("predictiveBackAnimationStyle", ANIMATION_STYLE_DEFAULT);
            predictiveBackIntensity = preferences.getInt("predictiveBackIntensity", 0);
            fadeDuration = preferences.getInt("fadeDuration", 300);
            predictiveBackAnimation = predictiveBackIntensity > 0;
            removeChatDelay = preferences.getBoolean("removeChatDelay", false);
            showOnlineDotsInChat = preferences.getBoolean("showOnlineDotsInChat", false);
            optimizedPushService = preferences.getBoolean("optimizedPushService", false);
            roundedBulletin = preferences.getBoolean("roundedBulletin", false);
            nonIslandTabBars = preferences.getBoolean("nonIslandTabBars", false);
            nonIslandGlobalSearch = preferences.getBoolean("nonIslandGlobalSearch", false);
            nonIslandChatElements = preferences.getBoolean("nonIslandChatElements", false);
            hideFadeView = preferences.getBoolean("hideFadeView", false);
            disableGlassGlare = preferences.getBoolean("disableGlassGlare", true);
            // The Telegram stroke glare is always disabled in favor of the shader glare.
            disableGlassGlare = true;
            if (preferences.contains("glassGlareMode")) {
                glassGlareMode = preferences.getInt("glassGlareMode", GLASS_GLARE_FULL);
            } else {
                // The shader glare is the only glare and is on by default.
                glassGlareMode = GLASS_GLARE_FULL;
            }
            disableScrimBlur = preferences.getBoolean("disableScrimBlur", false);
            material3BottomNavigationBar = preferences.getBoolean("material3BottomNavigationBar", false);
            md3PlayerSeekBar = preferences.getBoolean("md3PlayerSeekBar", false);
            md3Folders = preferences.getBoolean("md3Folders", false);
            avatarShape = preferences.getInt("avatarShape", 0);
            avatarShapeInChatList = preferences.getBoolean("avatarShapeInChatList", true);
            avatarShapeInChatMessages = preferences.getBoolean("avatarShapeInChatMessages", true);
            rotateAvatarShape = preferences.getBoolean("rotateAvatarShape", false);
            avatarShapeRotationSpeed = preferences.getInt("avatarShapeRotationSpeed", 60);
            avatarShapeSquareBase = preferences.getBoolean("avatarShapeSquareBase", false);
            wavyEnabled = preferences.getBoolean("wavyEnabled", true);
            holdToOpenPopup = preferences.getBoolean("holdToOpenPopup", false);
            popupHoldTime = preferences.getFloat("popupHoldTime", 0.5f);

            LensHelper.checkLensSupportAsync();
            preferences.registerOnSharedPreferenceChangeListener(listener);

            if (!configLoaded) {
                var map = new HashMap<String, String>();
                map.put("buildType", BuildConfig.BUILD_TYPE);
                map.put("mcc", String.valueOf(userMcc));
                AnalyticsHelper.trackEvent("load_config", map);
            }
            configLoaded = true;
        }
    }

    private static Gson gson;

    public static String exportConfigs() {
        if (gson == null) {
            gson = new GsonBuilder()
                    .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
                    .create();
        }
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        return gson.toJson(preferences.getAll());
    }

    public static void importConfigs(String config) {
        if (gson == null) {
            gson = new GsonBuilder()
                    .setObjectToNumberStrategy(ToNumberPolicy.LONG_OR_DOUBLE)
                    .create();
        }
        //noinspection unchecked
        Map<String, ?> map = gson.fromJson(config, Map.class);
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        preferences.unregisterOnSharedPreferenceChangeListener(listener);
        var editor = preferences.edit();
        editor.clear();
        map.forEach((BiConsumer<String, Object>) (s, o) -> {
            try {
                if (o instanceof Integer) {
                    editor.putInt(s, (Integer) o);
                } else if (o instanceof String) {
                    editor.putString(s, (String) o);
                } else if (o instanceof Boolean) {
                    editor.putBoolean(s, (Boolean) o);
                } else if (o instanceof Long) {
                    if ("stickerSize".equals(s)) {
                        editor.putFloat(s, ((Long) o).floatValue());
                    } else {
                        editor.putInt(s, ((Long) o).intValue());
                    }
                } else if (o instanceof Float) {
                    editor.putFloat(s, (Float) o);
                } else if (o instanceof Double) {
                    editor.putFloat(s, ((Double) o).floatValue());
                } else if (o instanceof ArrayList) {
                    //noinspection unchecked
                    editor.putStringSet(s, new HashSet<>((ArrayList<String>) o));
                } else {
                    FileLog.e("error putting " + s + " " + o.getClass().getName());
                }
            } catch (Exception e) {
                FileLog.e("error putting " + s, e);
            }
        });
        editor.apply();
        loadConfig(true);
    }

    public static void setCameraInVideoMessages(int camera) {
        cameraInVideoMessages = camera;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("cameraInVideoMessages", cameraInVideoMessages);
        editor.apply();
    }

    public static void setTextSpoilerMode(int mode) {
        textSpoilerMode = mode;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("textSpoilerMode", textSpoilerMode);
        editor.apply();
    }

    public static void setMediaSpoilerMode(int mode) {
        mediaSpoilerMode = mode;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("mediaSpoilerMode", mediaSpoilerMode);
        editor.apply();
    }

    public static void toggleSpoilerExtendToLineEnd() {
        spoilerExtendToLineEnd = !spoilerExtendToLineEnd;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("spoilerExtendToLineEnd", spoilerExtendToLineEnd);
        editor.apply();
    }

    public static void setTranscribeProvider(int provider) {
        transcribeProvider = provider;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("transcribeProvider", transcribeProvider);
        editor.apply();
    }

    public static void setCfAccountID(String accountID) {
        cfAccountID = accountID;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putString("cfAccountID", cfAccountID);
        editor.apply();
    }

    public static void setCfApiToken(String apiToken) {
        cfApiToken = apiToken;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putString("cfApiToken", cfApiToken);
        editor.apply();
    }

    public static void setExternalTranslationProvider(String provider) {
        externalTranslationProvider = provider;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putString("externalTranslationProvider", externalTranslationProvider);
        editor.apply();
    }

    public static void setNewMarkdownParser(boolean newParser) {
        newMarkdownParser = newParser;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("newMarkdownParser", newMarkdownParser);
        editor.apply();
    }

    public static void saveRestrictedLanguages(Set<String> languages) {
        restrictedLanguages = languages;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putStringSet("restrictedLanguages", languages);
        editor.apply();
    }

    public static void setDoubleTapInAction(int action) {
        doubleTapInAction = action;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("doubleTapAction", doubleTapInAction);
        editor.apply();
    }

    public static void setDoubleTapOutAction(int action) {
        doubleTapOutAction = action;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("doubleTapOutAction", doubleTapOutAction);
        editor.apply();
    }

    public static void setTransType(int type) {
        transType = type;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("transType", transType);
        editor.apply();
    }

    public static void setDownloadSpeedBoost(int boost) {
        downloadSpeedBoost = boost;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("downloadSpeedBoost2", boost);
        editor.apply();
    }

    public static void setBottomFilterTabs(boolean bottom) {
        bottomFilterTabs = bottom;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("bottomFilterTabs", bottomFilterTabs);
        editor.apply();
    }

    public static void toggleDisableGooeyAvatarAnimation() {
        disableGooeyAvatarAnimation = !disableGooeyAvatarAnimation;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("disableGooeyAvatarAnimation", disableGooeyAvatarAnimation);
        editor.apply();
    }

    public static void setGooeyAvatarOffset(int value) {
        gooeyAvatarOffset = value;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("gooeyAvatarOffset", gooeyAvatarOffset);
        editor.apply();
    }

    public static void setAlternativeTransitionSpeed(int value) {
        alternativeTransitionSpeed = value;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("alternativeTransitionSpeed", alternativeTransitionSpeed);
        editor.apply();
    }

    public static void setAlternativeTransitionEase(String value) {
        alternativeTransitionEase = value;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putString("alternativeTransitionEase", alternativeTransitionEase);
        editor.apply();
    }

    public static void setFadeDuration(int value) {
        fadeDuration = value;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("fadeDuration", fadeDuration);
        editor.apply();
    }

    public static void toggleMaterial3Switches() {
        material3Switches = !material3Switches;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("material3Switches", material3Switches);
        editor.apply();
    }

    public static void toggleM3SectionsStyle() {
        m3SectionsStyle = !m3SectionsStyle;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("m3SectionsStyle", m3SectionsStyle);
        editor.apply();
    }

    public static void toggleMaterial3ChatHeaders() {
        material3ChatHeaders = !material3ChatHeaders;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("material3ChatHeaders", material3ChatHeaders);
        editor.apply();
    }

    public static void toggleMaterialSliders() {
        materialSliders = !materialSliders;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("materialSliders", materialSliders);
        editor.apply();
    }

    public static void toggleCenterChatHeader() {
        centerChatHeader = !centerChatHeader;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("centerChatHeader", centerChatHeader);
        editor.apply();
    }

    public static void toggleBiggerAvatar() {
        biggerAvatar = !biggerAvatar;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("biggerAvatar", biggerAvatar);
        editor.apply();
    }

    public static void toggleBlurredFadeView() {
        blurredFadeView = !blurredFadeView;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("blurredFadeView", blurredFadeView);
        editor.apply();
    }

    public static void setBlurredFadeBlurStrength(int value) {
        blurredFadeBlurStrength = Math.max(0, Math.min(40, value));
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("blurredFadeBlurStrength", blurredFadeBlurStrength);
        editor.apply();
    }

    public static void setBlurredFadePixelation(int value) {
        blurredFadePixelation = Math.max(1, Math.min(16, value));
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("blurredFadePixelation", blurredFadePixelation);
        editor.apply();
    }

    public static void toggleBlurredFadeDimming() {
        blurredFadeDimming = !blurredFadeDimming;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("blurredFadeDimming", blurredFadeDimming);
        editor.apply();
    }

    public static void setBlurredFadeDimStrength(int value) {
        blurredFadeDimStrength = Math.max(0, Math.min(100, value));
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("blurredFadeDimStrength", blurredFadeDimStrength);
        editor.apply();
    }

    public static void toggleProgressiveFadeBlur() {
        progressiveFadeBlur = !progressiveFadeBlur;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("progressiveFadeBlur", progressiveFadeBlur);
        editor.apply();
    }

    public static void setProgressiveFadeBlurMaxRadius(int value) {
        progressiveFadeBlurMaxRadius = Math.max(0, Math.min(40, value));
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("progressiveFadeBlurMaxRadius", progressiveFadeBlurMaxRadius);
        editor.apply();
    }

    public static void setAvatarPlacement(int placement) {
        avatarPlacement = placement;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("avatarPlacement", avatarPlacement);
        editor.apply();
    }

    public static void setOpenAnimationStyle(int style) {
        openAnimationStyle = style;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("openAnimationStyle", openAnimationStyle);
        editor.apply();
    }

    public static void setCloseAnimationStyle(int style) {
        closeAnimationStyle = style;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("closeAnimationStyle", closeAnimationStyle);
        editor.apply();
    }

    public static void setPredictiveBackAnimationStyle(int style) {
        predictiveBackAnimationStyle = style;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("predictiveBackAnimationStyle", predictiveBackAnimationStyle);
        editor.apply();
    }

    public static void setPredictiveBackIntensity(int value) {
        predictiveBackIntensity = value;
        predictiveBackAnimation = value > 0;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("predictiveBackIntensity", predictiveBackIntensity);
        editor.putBoolean("predictiveBackAnimation", predictiveBackAnimation);
        editor.apply();
    }

    public static void setShowMainTabs(boolean show) {
        showMainTabs = show;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("showMainTabs", showMainTabs);
        editor.apply();
    }

    public static void setShowMainTabsTitle(boolean show) {
        showMainTabsTitle = show;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("showMainTabsTitle", showMainTabsTitle);
        editor.apply();
    }

    public static void toggleDynamicTabSize() {
        dynamicTabSize = !dynamicTabSize;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("dynamicTabSize", dynamicTabSize);
        editor.apply();
    }

    public static void setTelegaDetectorEnabled(boolean enabled) {
        telegaDetectorEnabled = enabled;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("telegaDetectorEnabled", telegaDetectorEnabled);
        editor.apply();
    }

    public static void toggleDisableTypingIndicator() {
        disableTypingIndicator = !disableTypingIndicator;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("disableTypingIndicator", disableTypingIndicator);
        editor.apply();
    }

    public static void toggleGhostMode() {
        ghostModeEnabled = !ghostModeEnabled;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("ghostModeEnabled", ghostModeEnabled);
        editor.apply();
    }

    public static void setBypassBlocking(boolean enabled) {
        bypassBlocking = enabled;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("bypassBlocking", bypassBlocking);
        editor.apply();
    }

    public static void toggleBypassBlocking() {
        setBypassBlocking(!bypassBlocking);
    }

    public static void togglePluginsEnabled() {
        pluginsEnabled = !pluginsEnabled;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("pluginsEnabled", pluginsEnabled);
        editor.apply();
        // Apply immediately: load/unload the plugin engine so the change is
        // visible without a restart.
        zxc.iconic.xenon.plugins.PluginManager.getInstance().onEnabledChanged();
    }

    public static void togglePluginGodMode() {
        pluginGodMode = !pluginGodMode;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("pluginGodMode", pluginGodMode);
        editor.apply();
        // Scope grants are evaluated live, so a toggle takes effect on the next
        // API call without reloading the engine.
    }

    public static void toggleKeepFormatting() {
        keepFormatting = !keepFormatting;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("keepFormatting", keepFormatting);
        editor.apply();
    }

    public static void toggleHideChannelBottomButtons() {
        hideChannelBottomButtons = !hideChannelBottomButtons;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("hideChannelBottomButtons", hideChannelBottomButtons);
        editor.apply();
    }

    public static void toggleMinimizedStickerCreator() {
        minimizedStickerCreator = !minimizedStickerCreator;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("minimizedStickerCreator", minimizedStickerCreator);
        editor.apply();
    }

    public static void toggleForceFontWeightFallback() {
        forceFontWeightFallback = !forceFontWeightFallback;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("forceFontWeightFallback", forceFontWeightFallback);
        editor.apply();
    }

    public static void togglePreferOriginalQuality() {
        preferOriginalQuality = !preferOriginalQuality;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("preferOriginalQuality", preferOriginalQuality);
        editor.apply();
    }

    public static void toggleShowTimeHint() {
        showTimeHint = !showTimeHint;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("showTimeHint", showTimeHint);
        editor.apply();
    }

    public static void toggleIgnoreContentRestriction() {
        ignoreContentRestriction = !ignoreContentRestriction;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("ignoreContentRestriction", ignoreContentRestriction);
        editor.apply();
    }

    public static void toggleReducedColors() {
        reducedColors = !reducedColors;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("reducedColors", reducedColors);
        editor.apply();
    }

    public static void toggleQuickForward() {
        quickForward = !quickForward;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("quickForward", quickForward);
        editor.apply();
    }

    public static void toggleHideStories() {
        hideStories = !hideStories;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("hideStories", hideStories);
        editor.apply();
    }

    public static void toggleShowQrCode() {
        showQrCode = !showQrCode;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("showQrCode", showQrCode);
        editor.apply();
    }

    public static void toggleShowOpenIn() {
        showOpenIn = !showOpenIn;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("showOpenIn", showOpenIn);
        editor.apply();
    }

    public static void toggleShowOriginal() {
        showOriginal = !showOriginal;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("showOriginal", showOriginal);
        editor.apply();
    }

    public static void toggleMarkdownParseLinks() {
        markdownParseLinks = !markdownParseLinks;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("markdownParseLinks", markdownParseLinks);
        editor.apply();
    }

    public static void toggleDisableMarkdownByDefault() {
        disableMarkdownByDefault = !disableMarkdownByDefault;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("disableMarkdownByDefault", disableMarkdownByDefault);
        editor.apply();
    }

    public static void toggleHideTimeOnSticker() {
        hideTimeOnSticker = !hideTimeOnSticker;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("hideTimeOnSticker", hideTimeOnSticker);
        editor.apply();
    }

    public static void toggleShowRPCError() {
        showRPCError = !showRPCError;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("showRPCError", showRPCError);
        editor.apply();
    }

    public static void toggleEnableSaveDeletedMessages() {
        enableSaveDeletedMessages = !enableSaveDeletedMessages;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("enableSaveDeletedMessages", enableSaveDeletedMessages);
        editor.apply();
    }

    public static void toggleShowAddToSavedMessages() {
        showAddToSavedMessages = !showAddToSavedMessages;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("showAddToSavedMessages", showAddToSavedMessages);
        editor.apply();
    }

    public static void toggleShowAddToSavedMessagesInGroups() {
        showAddToSavedMessagesInGroups = !showAddToSavedMessagesInGroups;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("showAddToSavedMessagesInGroups", showAddToSavedMessagesInGroups);
        editor.apply();
    }

    public static void toggleShowSetReminder() {
        showSetReminder = !showSetReminder;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("showSetReminder", showSetReminder);
        editor.apply();
    }

    public static void toggleShowReport() {
        showReport = !showReport;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("showReport", showReport);
        editor.apply();
    }

    public static void toggleShowPrPr() {
        showPrPr = !showPrPr;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("showPrPr", showPrPr);
        editor.apply();
    }

    public static void toggleShowDeleteDownloadedFile() {
        showDeleteDownloadedFile = !showDeleteDownloadedFile;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("showDeleteDownloadedFile", showDeleteDownloadedFile);
        editor.apply();
    }

    public static void toggleShowMessageDetails() {
        showMessageDetails = !showMessageDetails;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("showMessageDetails", showMessageDetails);
        editor.apply();
    }

    public static void toggleShowRepeat() {
        showRepeat = !showRepeat;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("showRepeat", showRepeat);
        editor.apply();
    }

    public static void toggleIPv6() {
        preferIPv6 = !preferIPv6;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("preferIPv6", preferIPv6);
        editor.apply();
    }

    public static void toggleIgnoreBlocked() {
        ignoreBlocked = !ignoreBlocked;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("ignoreBlocked2", ignoreBlocked);
        editor.apply();
    }

    public static void setTabletMode(int mode) {
        tabletMode = mode;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("tabletMode", tabletMode);
        editor.apply();
    }

    public static void setNameOrder(int order) {
        nameOrder = order;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("nameOrder", nameOrder);
        editor.apply();
    }

    public static void toggleShowTranslate() {
        showTranslate = !showTranslate;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("showTranslate", showTranslate);
        editor.apply();
    }

    public static void setStickerSize(float size) {
        stickerSize = size;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putFloat("stickerSize", stickerSize);
        editor.apply();
    }

    public static void setTranslationProvider(String provider) {
        translationProvider = provider;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putString("translationProvider2", translationProvider);
        editor.apply();
    }

    public static void setTranslationTarget(String target) {
        translationTarget = target;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putString("translationTarget", translationTarget);
        editor.apply();
    }

    public static void toggleOpenArchiveOnPull() {
        openArchiveOnPull = !openArchiveOnPull;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("openArchiveOnPull", openArchiveOnPull);
        editor.apply();
    }

    public static void toggleHideKeyboardOnChatScroll() {
        hideKeyboardOnChatScroll = !hideKeyboardOnChatScroll;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("hideKeyboardOnChatScroll", hideKeyboardOnChatScroll);
        editor.apply();
    }

    public static void toggleUseSystemEmoji() {
        useSystemEmoji = !useSystemEmoji;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("useSystemEmoji", useSystemEmoji);
        editor.apply();
    }

    public static void toggleHideAllTab() {
        hideAllTab = !hideAllTab;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("hideAllTab", hideAllTab);
        editor.apply();
    }

    public static void setTabsTitleType(int type) {
        tabsTitleType = type;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("tabsTitleType2", tabsTitleType);
        editor.apply();
    }

    public static void toggleConfirmAVMessage() {
        confirmAVMessage = !confirmAVMessage;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("confirmAVMessage", confirmAVMessage);
        editor.apply();
    }

    public static void toggleAskBeforeCall() {
        askBeforeCall = !askBeforeCall;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("askBeforeCall", askBeforeCall);
        editor.apply();
    }

    public static void toggleDisableNumberRounding() {
        disableNumberRounding = !disableNumberRounding;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("disableNumberRounding", disableNumberRounding);
        editor.apply();
    }

    public static void toggleDisableGreetingSticker() {
        disableGreetingSticker = !disableGreetingSticker;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("disableGreetingSticker", disableGreetingSticker);
        editor.apply();
    }

    public static void toggleDisableAppBarShadow() {
        disableAppBarShadow = !disableAppBarShadow;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("disableAppBarShadow", disableAppBarShadow);
        editor.apply();
    }

    public static void toggleHideRecordButton() {
        hideRecordButton = !hideRecordButton;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("hideRecordButton", hideRecordButton);
        editor.apply();
    }

    public static void toggleForceBlurLiquidGlass() {
        forceBlurLiquidGlass = !forceBlurLiquidGlass;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("forceBlurLiquidGlass", forceBlurLiquidGlass);
        editor.apply();
    }

    public static void toggleBlurOverlay() {
        blurOverlay = !blurOverlay;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("blurOverlay", blurOverlay);
        editor.apply();
    }

    public static void toggleBlurPopupInChat() {
        blurPopupInChat = !blurPopupInChat;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("blurPopupInChat", blurPopupInChat);
        editor.apply();
    }

    public static void setBlurOverlayRadius(int value) {
        blurOverlayRadius = value;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("blurOverlayRadius", blurOverlayRadius);
        editor.apply();
    }

    public static void toggleBlurOverlayRefresh() {
        blurOverlayRefresh = !blurOverlayRefresh;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("blurOverlayRefresh", blurOverlayRefresh);
        editor.apply();
    }

    public static void setBlurOverlayRefreshInterval(int value) {
        blurOverlayRefreshInterval = value;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("blurOverlayRefreshInterval", blurOverlayRefreshInterval);
        editor.apply();
    }

    public static void toggleBlurSmoothly() {
        blurSmoothly = !blurSmoothly;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("blurSmoothly", blurSmoothly);
        editor.apply();
    }

    public static void toggleDisableBlurBs() {
        disableBlurBs = !disableBlurBs;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("disableBlurBs", disableBlurBs);
        editor.apply();
    }

    public static void setBlurAnimationDuration(int value) {
        blurAnimationDuration = value;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("blurAnimationDuration", blurAnimationDuration);
        editor.apply();
    }

    public static void setBlurPixelation(int value) {
        blurPixelation = value;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("blurPixelation", blurPixelation);
        editor.apply();
    }

    public static void toggleReplaceDialogsWithSheet() {
        replaceDialogsWithSheet = !replaceDialogsWithSheet;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("replaceDialogsWithSheet", replaceDialogsWithSheet);
        editor.apply();
    }

    public static void toggleMaterial3Dialogs() {
        material3Dialogs = !material3Dialogs;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("material3Dialogs", material3Dialogs);
        editor.apply();
    }

    public static void toggleKeepUnreadChatsOnTop() {
        keepUnreadChatsOnTop = !keepUnreadChatsOnTop;
        if (!keepUnreadChatsOnTop) {
            keepUnreadArchivedOnTop = false;
        }
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("keepUnreadChatsOnTop", keepUnreadChatsOnTop);
        editor.putBoolean("keepUnreadArchivedOnTop", keepUnreadArchivedOnTop);
        editor.apply();
    }

    public static void toggleKeepUnreadArchivedOnTop() {
        keepUnreadArchivedOnTop = !keepUnreadArchivedOnTop;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("keepUnreadArchivedOnTop", keepUnreadArchivedOnTop);
        editor.apply();
    }

    public static void toggleMediaPreview() {
        mediaPreview = !mediaPreview;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("mediaPreview", mediaPreview);
        editor.apply();
    }

    public static void setIdType(int type) {
        idType = type;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("idType", idType);
        editor.apply();
    }

    public static void toggleAutoPauseVideo() {
        autoPauseVideo = !autoPauseVideo;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("autoPauseVideo", autoPauseVideo);
        editor.apply();
    }

    public static void toggleDisableProximityEvents() {
        disableProximityEvents = !disableProximityEvents;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("disableProximityEvents", disableProximityEvents);
        editor.apply();
    }

    public static void toggleUseCamera2Api() {
        useCamera2Api = !useCamera2Api;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("useCamera2Api", useCamera2Api);
        editor.apply();
    }

    public static void toggleVoiceEnhancements() {
        voiceEnhancements = !voiceEnhancements;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("voiceEnhancements", voiceEnhancements);
        editor.apply();
    }

    public static void toggleDisabledInstantCamera() {
        disableInstantCamera = !disableInstantCamera;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("disableInstantCamera", disableInstantCamera);
        editor.apply();
    }

    public static void toggleTryToOpenAllLinksInIV() {
        tryToOpenAllLinksInIV = !tryToOpenAllLinksInIV;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("tryToOpenAllLinksInIV", tryToOpenAllLinksInIV);
        editor.apply();
    }

    public static void toggleFormatTimeWithSeconds() {
        formatTimeWithSeconds = !formatTimeWithSeconds;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("formatTimeWithSeconds", formatTimeWithSeconds);
        editor.apply();
    }

    public static void toggleAccentAsNotificationColor() {
        accentAsNotificationColor = !accentAsNotificationColor;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("accentAsNotificationColor", accentAsNotificationColor);
        editor.apply();
    }

    public static void toggleSilenceNonContacts() {
        silenceNonContacts = !silenceNonContacts;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("silenceNonContacts", silenceNonContacts);
        editor.apply();
    }

    public static void toggleDisableJumpToNextChannel() {
        disableJumpToNextChannel = !disableJumpToNextChannel;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("disableJumpToNextChannel", disableJumpToNextChannel);
        editor.apply();
    }

    public static void toggleAutoDownloadUpdate() {
        autoDownloadUpdate = !autoDownloadUpdate;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("autoDownloadUpdate", autoDownloadUpdate);
        editor.apply();
    }

    public static void toggleAutoCheckUpdate() {
        autoCheckUpdate = !autoCheckUpdate;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("autoCheckUpdate", autoCheckUpdate);
        editor.apply();
    }

    public static void toggleShowNoQuoteForward() {
        showNoQuoteForward = !showNoQuoteForward;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("showNoQuoteForward", showNoQuoteForward);
        editor.apply();
    }

    public static void toggleShowCopyPhoto() {
        showCopyPhoto = !showCopyPhoto;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("showCopyPhoto", showCopyPhoto);
        editor.apply();
    }

    public static void toggleAutoTranslate() {
        autoTranslate = !autoTranslate;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("autoTranslate", autoTranslate);
        editor.apply();
    }

    public static void toggleDisableVoiceMessageAutoPlay() {
        disableVoiceMessageAutoPlay = !disableVoiceMessageAutoPlay;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("disableVoiceMessageAutoPlay", disableVoiceMessageAutoPlay);
        editor.apply();
    }

    public static void toggleUnmuteVideosWithVolumeButtons() {
        unmuteVideosWithVolumeButtons = !unmuteVideosWithVolumeButtons;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("unmuteVideosWithVolumeButtons", unmuteVideosWithVolumeButtons);
        editor.apply();
    }

    public static void setMaxRecentStickers(int size) {
        maxRecentStickers = size;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("maxRecentStickers", maxRecentStickers);
        editor.apply();
    }

    public static void toggleHidePhoneNumber() {
        hidePhoneNumber = !hidePhoneNumber;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("hidePhoneNumber", hidePhoneNumber);
        editor.apply();
    }

    public static void setXrayAppProxyEnabled(boolean enabled) {
        xrayAppProxyEnabled = enabled;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("xrayAppProxyEnabled", enabled);
        editor.apply();
    }

    public static void setXrayVpnMode(boolean enabled) {
        xrayVpnMode = enabled;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("xrayVpnMode", enabled);
        editor.apply();
    }

    public static void setXrayAppProxyLocalPort(int localPort) {
        xrayAppProxyLocalPort = localPort;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("xrayAppProxyLocalPort", xrayAppProxyLocalPort);
        editor.apply();
    }

    public static void setXrayAppProxyConfigJson(String configJson) {
        xrayAppProxyConfigJson = configJson == null ? "" : configJson;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putString("xrayAppProxyConfigJson", xrayAppProxyConfigJson);
        editor.apply();
    }

    public static void setXrayAppProxyCheckUrl(String checkUrl) {
        xrayAppProxyCheckUrl = normalizeXrayCheckUrl(checkUrl);
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putString("xrayAppProxyCheckUrl", xrayAppProxyCheckUrl);
        editor.apply();
    }

    private static String normalizeXrayCheckUrl(String checkUrl) {
        if (checkUrl == null) {
            return XRAY_DEFAULT_CHECK_URL;
        }
        String value = checkUrl.trim();
        return value.isEmpty() ? XRAY_DEFAULT_CHECK_URL : value;
    }

    public static void setLiquidGlassIntensity(float intensity) {
        liquidGlassIntensity = intensity;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putFloat("liquidGlassIntensity", liquidGlassIntensity);
        editor.apply();
    }

    public static void setLiquidGlassThickness(int thickness) {
        liquidGlassThickness = thickness;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("liquidGlassThickness", liquidGlassThickness);
        editor.apply();
    }

    public static void toggleUseAdvancedLiquidGlass() {
        useAdvancedLiquidGlass = !useAdvancedLiquidGlass;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("useAdvancedLiquidGlass", useAdvancedLiquidGlass);
        editor.apply();
    }

    public static void setAdvancedGlassAlpha(int value) {
        advancedGlassAlpha = Math.max(0, Math.min(100, value));
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("advancedGlassAlpha", advancedGlassAlpha);
        editor.apply();
    }

    public static void setBlurStrength(int value) {
        blurStrength = Math.max(0, Math.min(100, value));
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("blurStrength", blurStrength);
        editor.apply();
    }

    public static void toggleAdvancedGlassWallpaperBlur() {
        advancedGlassWallpaperBlur = !advancedGlassWallpaperBlur;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("advancedGlassWallpaperBlur", advancedGlassWallpaperBlur);
        editor.apply();
    }

    public static void setAdvancedGlassDispersion(float value) {
        advancedGlassDispersion = value;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putFloat("advancedGlassDispersion", advancedGlassDispersion);
        editor.apply();
    }

    public static void setAdvancedGlassFresnel(float value) {
        advancedGlassFresnel = value;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putFloat("advancedGlassFresnel", advancedGlassFresnel);
        editor.apply();
    }

    public static void setAdvancedGlassGlare(float value) {
        advancedGlassGlare = value;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putFloat("advancedGlassGlare", advancedGlassGlare);
        editor.apply();
    }

    public static void setAdvancedGlassTintPercent(int value) {
        advancedGlassTintPercent = Math.max(0, Math.min(100, value));
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("advancedGlassTintPercent", advancedGlassTintPercent);
        editor.apply();
    }

    public static void toggleAdvancedGlassTintBlackWhite() {
        advancedGlassTintBlackWhite = !advancedGlassTintBlackWhite;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("advancedGlassTintBlackWhite", advancedGlassTintBlackWhite);
        editor.putBoolean("glassBottomSheet", glassBottomSheet);
        editor.apply();
    }

    public static void toggleGlassBottomSheet() {
        glassBottomSheet = !glassBottomSheet;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("glassBottomSheet", glassBottomSheet);
        editor.apply();
    }

    public static void setGlassGlareMode(int mode) {
        glassGlareMode = mode;
        // The Telegram stroke glare is always disabled; the shader glare
        // (advancedGlassGlare) is the only glare and is controlled here.
        if (mode == GLASS_GLARE_SOLID) {
            advancedGlassGlare = 0.1f;
        } else if (mode == GLASS_GLARE_DISABLE) {
            advancedGlassGlare = 0f;
        } else if (mode == GLASS_GLARE_FULL) {
            if (advancedGlassGlare < 0.1f) {
                advancedGlassGlare = DEFAULT_ADVANCED_GLASS_GLARE;
            }
        }
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("glassGlareMode", glassGlareMode);
        editor.putFloat("advancedGlassGlare", advancedGlassGlare);
        editor.apply();
    }

    public static void resetAdvancedGlassToDefaults() {
        advancedGlassAlpha = DEFAULT_ADVANCED_GLASS_ALPHA;
        advancedGlassBlur = DEFAULT_ADVANCED_GLASS_BLUR;
        blurStrength = DEFAULT_BLUR_STRENGTH;
        advancedGlassWallpaperBlur = DEFAULT_ADVANCED_GLASS_WALLPAPER_BLUR;
        advancedGlassDispersion = DEFAULT_ADVANCED_GLASS_DISPERSION;
        advancedGlassFresnel = DEFAULT_ADVANCED_GLASS_FRESNEL;
        advancedGlassGlare = DEFAULT_ADVANCED_GLASS_GLARE;
        advancedGlassTintPercent = DEFAULT_ADVANCED_GLASS_TINT_PERCENT;
        advancedGlassTintBlackWhite = DEFAULT_ADVANCED_GLASS_TINT_BLACK_WHITE;
        glassBottomSheet = DEFAULT_GLASS_BOTTOM_SHEET;
        glassGlareMode = GLASS_GLARE_FULL;
        disableGlassGlare = true;
        strokeOnViews = true;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("advancedGlassAlpha", advancedGlassAlpha);
        editor.putInt("advancedGlassBlur", advancedGlassBlur);
        editor.putInt("blurStrength", blurStrength);
        editor.putBoolean("advancedGlassWallpaperBlur", advancedGlassWallpaperBlur);
        editor.putFloat("advancedGlassDispersion", advancedGlassDispersion);
        editor.putFloat("advancedGlassFresnel", advancedGlassFresnel);
        editor.putFloat("advancedGlassGlare", advancedGlassGlare);
        editor.putInt("advancedGlassTintPercent", advancedGlassTintPercent);
        editor.putBoolean("advancedGlassTintBlackWhite", advancedGlassTintBlackWhite);
        editor.putBoolean("glassBottomSheet", glassBottomSheet);
        editor.putInt("glassGlareMode", glassGlareMode);
        editor.putBoolean("disableGlassGlare", disableGlassGlare);
        editor.putBoolean("strokeOnViews", strokeOnViews);
        editor.apply();
    }

    public static void toggleRemoveAds() {
        removeAds = !removeAds;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("removeAds", removeAds);
        editor.apply();
    }

    public static void toggleTextAnimation() {
        textAnimationEnabled = !textAnimationEnabled;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("textAnimationEnabled", textAnimationEnabled);
        editor.apply();
    }

    public static void toggleRemoveChatDelay() {
        removeChatDelay = !removeChatDelay;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("removeChatDelay", removeChatDelay);
        editor.apply();
    }

    public static void toggleRoundedBulletin() {
        roundedBulletin = !roundedBulletin;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("roundedBulletin", roundedBulletin);
        editor.apply();
    }

    public static void toggleOptimizedPushService() {
        optimizedPushService = !optimizedPushService;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("optimizedPushService", optimizedPushService);
        editor.apply();
        try {
            ApplicationLoader.startPushService();
        } catch (Exception ignore) {
        }
    }

    public static void toggleNonIslandTabBars() {
        nonIslandTabBars = !nonIslandTabBars;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("nonIslandTabBars", nonIslandTabBars);
        editor.apply();
    }

    public static void toggleNonIslandGlobalSearch() {
        nonIslandGlobalSearch = !nonIslandGlobalSearch;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("nonIslandGlobalSearch", nonIslandGlobalSearch);
        editor.apply();
    }

    public static void toggleNonIslandChatElements() {
        nonIslandChatElements = !nonIslandChatElements;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("nonIslandChatElements", nonIslandChatElements);
        editor.apply();
    }

    public static void toggleHideFadeView() {
        hideFadeView = !hideFadeView;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("hideFadeView", hideFadeView);
        editor.apply();
    }

    public static void toggleDisableScrimBlur() {
        disableScrimBlur = !disableScrimBlur;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("disableScrimBlur", disableScrimBlur);
        editor.apply();
    }

    public static void toggleMaterial3BottomNavigationBar() {
        material3BottomNavigationBar = !material3BottomNavigationBar;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("material3BottomNavigationBar", material3BottomNavigationBar);
        editor.apply();
    }

    public static void toggleMd3PlayerSeekBar() {
        md3PlayerSeekBar = !md3PlayerSeekBar;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("md3PlayerSeekBar", md3PlayerSeekBar);
        editor.apply();
    }

    public static void toggleMd3Folders() {
        md3Folders = !md3Folders;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("md3Folders", md3Folders);
        editor.apply();
    }

    public static void setAvatarShape(int value) {
        avatarShape = value;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("avatarShape", avatarShape);
        editor.apply();
    }

    public static void toggleAvatarShapeInChatList() {
        avatarShapeInChatList = !avatarShapeInChatList;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("avatarShapeInChatList", avatarShapeInChatList);
        editor.apply();
    }

    public static void toggleAvatarShapeInChatMessages() {
        avatarShapeInChatMessages = !avatarShapeInChatMessages;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("avatarShapeInChatMessages", avatarShapeInChatMessages);
        editor.apply();
    }

    public static void toggleRotateAvatarShape() {
        rotateAvatarShape = !rotateAvatarShape;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("rotateAvatarShape", rotateAvatarShape);
        editor.apply();
    }

    public static void setAvatarShapeRotationSpeed(int value) {
        avatarShapeRotationSpeed = value;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("avatarShapeRotationSpeed", avatarShapeRotationSpeed);
        editor.apply();
    }

    public static void toggleAvatarShapeSquareBase() {
        avatarShapeSquareBase = !avatarShapeSquareBase;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("avatarShapeSquareBase", avatarShapeSquareBase);
        editor.apply();
    }

    public static void toggleShowOnlineDotsInChat() {
        showOnlineDotsInChat = !showOnlineDotsInChat;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("showOnlineDotsInChat", showOnlineDotsInChat);
        editor.apply();
    }

    public static void toggleHideCameraInMediaPicker() {
        hideCameraInMediaPicker = !hideCameraInMediaPicker;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("hideCameraInMediaPicker", hideCameraInMediaPicker);
        editor.apply();
    }

    public static void setTextAnimCursorSpeed(int value) {
        textAnimCursorSpeed = value;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("textAnimCursorSpeed", textAnimCursorSpeed);
        editor.apply();
    }

    public static void setTextAnimFadeDuration(int value) {
        textAnimFadeDuration = value;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("textAnimFadeDuration", textAnimFadeDuration);
        editor.apply();
    }

    public static void setTextAnimBlurStrength(int value) {
        textAnimBlurStrength = value;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("textAnimBlurStrength", textAnimBlurStrength);
        editor.apply();
    }

    public static void setTextAnimBlurDuration(int value) {
        textAnimBlurDuration = value;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putInt("textAnimBlurDuration", textAnimBlurDuration);
        editor.apply();
    }

    public static void toggleWavyEnabled() {
        wavyEnabled = !wavyEnabled;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("wavyEnabled", wavyEnabled);
        editor.apply();
    }

    public static void toggleHoldToOpenPopup() {
        holdToOpenPopup = !holdToOpenPopup;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putBoolean("holdToOpenPopup", holdToOpenPopup);
        editor.apply();
    }

    public static void setPopupHoldTime(float value) {
        popupHoldTime = value;
        SharedPreferences preferences = ApplicationLoader.applicationContext.getSharedPreferences("nekoconfig", Activity.MODE_PRIVATE);
        SharedPreferences.Editor editor = preferences.edit();
        editor.putFloat("popupHoldTime", popupHoldTime);
        editor.apply();
    }

    public static int getNotificationColor() {
        if (accentAsNotificationColor) {
            int color = 0;
            if (Theme.getActiveTheme().hasAccentColors()) {
                color = Theme.getActiveTheme().getAccentColor(Theme.getActiveTheme().currentAccentId);
            }
            if (color == 0) {
                color = Theme.getColor(Theme.key_actionBarDefault) | 0xff000000;
            }
            float brightness = AndroidUtilities.computePerceivedBrightness(color);
            if (brightness >= 0.721f || brightness <= 0.279f) {
                color = Theme.getColor(Theme.key_windowBackgroundWhiteBlueHeader) | 0xff000000;
            }
            return color;
        } else {
            return 0xff11acfa;
        }
    }

    public static String getChannelLabel() {
        return "";
    }

    public static String getChannelName() {
        return "";
    }
}
