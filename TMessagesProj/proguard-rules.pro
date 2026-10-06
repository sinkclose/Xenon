-keepnames @com.google.android.gms.common.annotation.KeepName class *
-keepclassmembernames class * {
    @com.google.android.gms.common.annotation.KeepName *;
}

-keep @interface androidx.annotation.Keep
-keep @androidx.annotation.Keep class * { *; }
-keepclasseswithmembers class * { @androidx.annotation.Keep *; }

-keep class org.webrtc.* { *; }
-keep class org.webrtc.audio.* { *; }
-keep class org.webrtc.voiceengine.* { *; }
-keep class org.telegram.messenger.voip.* { *; }
-keep class org.telegram.messenger.AnimatedFileDrawableStream { <methods>; }
-keep class org.telegram.SQLite.SQLiteException { <methods>; }
-keep class org.telegram.tgnet.ConnectionsManager { <methods>; }
-keep class org.telegram.tgnet.NativeByteBuffer { *; }
-keep class org.telegram.tgnet.RequestTimeDelegate { *; }
-keep class org.telegram.ui.Stories.recorder.FfmpegAudioWaveformLoader { *; }
-keep class androidx.mediarouter.app.MediaRouteButton { *; }
-keepclassmembers class ** {
    @android.webkit.JavascriptInterface <methods>;
}

# https://developers.google.com/ml-kit/known-issues#android_issues
-keep class com.google.mlkit.nl.languageid.internal.LanguageIdentificationJni { *; }

# Huawei Services
-keep class com.huawei.hianalytics.**{ *; }
-keep class com.huawei.updatesdk.**{ *; }
-keep class com.huawei.hms.**{ *; }

# Don't warn about checkerframework and Kotlin annotations
-dontwarn org.checkerframework.**
-dontwarn javax.annotation.**

-keep class io.nano.tex.** {*;}

# JLatexMath: macro/atom classes are loaded reflectively by Class.forName
-keep class org.scilab.forge.jlatexmath.** { *; }
-dontwarn org.scilab.forge.jlatexmath.**

# Use -keep to explicitly keep any other classes shrinking would remove
#-dontoptimize
#-dontobfuscate

-keepnames class ** extends org.telegram.ui.ActionBar.BaseFragment
-keepclassmembernames,allowshrinking class org.telegram.ui.* { <fields>; }
-keepclassmembernames,allowshrinking class org.telegram.ui.Cells.* { <fields>; }
-keepclassmembernames,allowshrinking class org.telegram.ui.Components.* { <fields>; }
-keepclassmembernames,allowshrinking class zxc.iconic.xenon.MessageDetailsActivity$TextDetailSimpleCell { <fields>; }
-keepclassmembernames,allowshrinking class zxc.iconic.xenon.settings.AccountCell { <fields>; }
-keepclassmembernames,allowshrinking class zxc.iconic.xenon.settings.EmojiSetCell { <fields>; }
-keepclassmembernames,allowshrinking class zxc.iconic.xenon.settings.NekoChatSettingsActivity$StickerSizeCell { <fields>; }

-keepclassmembernames class androidx.core.widget.NestedScrollView {
    private android.widget.OverScroller mScroller;
    private void abortAnimatedScroll();
}

-keepclasseswithmembernames,includedescriptorclasses class * {
    native <methods>;
}

-keepclassmembers enum * {
     public static **[] values();
     public static ** valueOf(java.lang.String);
}

-keepnames class androidx.recyclerview.widget.RecyclerView
-keepclassmembers class androidx.recyclerview.widget.RecyclerView {
    public void suppressLayout(boolean);
    public boolean isLayoutSuppressed();
}

-dontobfuscate
-allowaccessmodification
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

-dontwarn android.support.annotation.*
-dontwarn androidx.compose.**

# Plugin system: Lua scripts access ANY Telegram class via luajava reflection.
# Without this, R8 strips methods/fields that appear unused from Java but are
# called from Lua via bindClass/bindClassMember/field access.
-keep class org.telegram.** { *; }

# luaj-jse: the Lua platform loads its standard libraries (Bit32Lib, BaseLib,
# MathLib, etc. and their inner classes like Bit32LibV) via reflection from
# JsePlatform.standardGlobals(). R8 sees no static references to those classes
# and strips them, causing NoClassDefFoundError at runtime when a plugin runs.
# Keep the entire luaj package so reflection-based loading keeps working.
-keep class org.luaj.vm2.** { *; }
-keepclassmembers class org.luaj.vm2.** { *; }
-dontwarn org.luaj.vm2.**

# R8: Missing classes when minifying release build
-dontwarn com.google.android.gms.internal.location.**
-keep class com.google.android.gms.internal.location.** { *; }
-dontwarn android.support.v4.app.NotificationCompat$Builder
-keep class android.support.v4.app.NotificationCompat$Builder { *; }
-dontwarn javax.script.**
-keep class javax.script.** { *; }
-dontwarn org.apache.commons.text.**
-keep class org.apache.commons.text.** { *; }

# Preserve Xenon reflection and Gson configuration during release minification.
-keepattributes Signature,InnerClasses,EnclosingMethod
-keepclasseswithmembers,allowobfuscation class * {
    @com.google.gson.annotations.SerializedName <fields>;
}
-keep class ru.noties.jlatexmath.** { *; }
-dontoptimize
