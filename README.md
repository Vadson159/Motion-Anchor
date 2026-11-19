# Motion Anchor

Motion Anchor draws motion markers over Android apps during a car, bus, or train ride. Start it manually from the app or Quick Settings, or optionally arm trip detection for the current session.

- Use dots, rings, diamonds, stars, an artificial horizon, or your own image.
- Adjust marker color, placement, size, opacity, density, and motion sensitivity. The image picker copies your image into private app storage.
- Choose English, Russian, Polish, or the system language.
- Choose a light, dark, or OLED black interface, or follow Android's theme.
- The optional full-screen service can draw over system bars without reading screen content.
- The optional trip mode uses GPS speed on the device after you grant precise location access. Arm it from the app. The overlay appears after sustained travel, remains briefly at stops, and turns off when movement stays below the threshold. The foreground notification ends the session.

## Android compatibility

Motion Anchor targets Android 16 (API 36) with a minimum of Android 4.1 (API 16). On Android 6 and later, grant display-over-apps permission for the standard overlay. Full-screen accessibility requires Android 5.1 (API 22). Quick Settings requires Android 7.0 (API 24). Trip detection needs usable GPS. On devices without a gyroscope or software motion sensors, the app uses the accelerometer when available. Android and individual apps can limit overlays on protected screens.

## Build

Set `ANDROID_HOME` to an Android SDK with platform 36 and use JDK 17 or later. Run `./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`. On Windows, use `gradlew.bat`. The debug APK is at `app/build/outputs/apk/debug/app-debug.apk`.

## Credits and license

Motion Anchor is a modified fork of [David Ventura’s motion-sickness-app](https://github.com/DavidVentura/motion-sickness-app). The fork adds its own Android settings interface, compatibility handling, custom markers, and optional session controls. See [LICENSE](LICENSE) for the GPL-3.0-only license.
