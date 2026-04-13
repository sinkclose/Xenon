# Xenon

Xenon is a third-party Telegram client for Android. This codebase is **based on [Nekogram](https://github.com/Nekogram/Nekogram)** (which in turn follows Telegram for Android). 

## Documentation

- [Telegram API](https://core.telegram.org/api)
- [MTProto](https://core.telegram.org/mtproto)

## Build instructions

### 1. Get the source

Clone this repository and open the **project root** in Android Studio (use **Open**, not *Import*).

### 2. JDK and Android SDK

Use a recent Android Studio bundle so you have a compatible JDK and SDK. The project targets current Android Gradle Plugin requirements from the repo.

### 3. Signing (release)

Create or reuse a keystore for signing release builds. In the project root, add a `local.properties` file (or edit the existing one) and set:

- `storeFile` — path to your `.jks` / keystore file  
- `storePassword`, `keyAlias`, `keyPassword` — matching the keystore  

Debug builds can use the debug keystore if these are omitted.

### 4. API credentials and optional services

In `local.properties`, configure at least:

- `apiId` and `apiHash` — from [my.telegram.org](https://my.telegram.org) (required for any Telegram client)  
- `sentryDsn` — optional; leave empty if you do not use Sentry error reporting  

Gradle injects these into `BuildConfig`. The small class `zxc.iconic.xenon.Extra` only exposes those values (`APP_ID`, `APP_HASH`, `SENTRY_DSN`) plus helpers like `isDirectApp()` — you do not edit it by hand for normal builds.

### 5. Firebase (push)

If you use Firebase Cloud Messaging:

1. In [Firebase Console](https://console.firebase.google.com/), create Android app(s) with application ID **`zxc.iconic.xenon`** (and **`zxc.iconic.xenon.beta`** for the debug suffix build, if you use FCM there).  
2. Enable Cloud Messaging and download `google-services.json` into the **`TMessagesProj`** module directory.

### 6. Compile

From the project root:

```bash
./gradlew :TMessagesProj_App:assembleRelease
```

For a debug APK (package id will be `zxc.iconic.xenon.beta`):

```bash
./gradlew :TMessagesProj_App:assembleDebug
```

Output APK names follow the pattern configured in `TMessagesProj_App/build.gradle` (e.g. `Xenon-<version>-…`).

## Localization

Base UI strings follow [Telegram for Android on translations.telegram.org](https://translations.telegram.org/). Fork-specific strings live in `strings_neko.xml` resources.

## License

See the `LICENSE` file in this repository.
