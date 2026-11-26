# Motion Anchor

Motion Anchor draws motion markers over Android apps during a car, bus, or train ride. Start it manually from the app or Quick Settings, or optionally arm trip detection for the current session.

- Use dots, rings, diamonds, stars, an artificial horizon, or your own image.
- Adjust marker color (from a color palette or a hex code), placement, size, opacity, density, motion sensitivity, and frame rate (the display's own refresh rates, common rates such as 24, 30, 45, 50, 72, 90, 100, or 144 fps up to the display maximum, or a custom value). The image picker copies your image into private app storage.
- Optionally enable forward and backward marker motion so the field scrolls down while driving forward and up while reversing.
- Choose English, Russian, Polish, or the system language.
- Pick any interface color from a palette, and choose a light, dark, or OLED black interface or follow Android's theme.
- The optional full-screen service (full build only) can draw over system bars without reading screen content.
- The optional trip mode uses GPS speed on the device after you grant precise location access, with Wi-Fi and cell positions as a fallback where GPS has no fix. Arm it from the app. The overlay appears after sustained travel, remains briefly at stops, and turns off when movement stays below the threshold. The foreground notification ends the session. With "Work in background" on, trip detection keeps running after the app closes and starts again after a reboot or an app update; with "Allow all the time" location access it restarts by itself, otherwise a notification resumes it with one tap. Stopping the session turns background mode off.

## Android compatibility

Motion Anchor targets Android 16 (API 36) with a minimum of Android 4.1 (API 16). On Android 6 and later, grant display-over-apps permission for the standard overlay. Full-screen accessibility requires the full build and Android 5.1 (API 22). Quick Settings requires Android 7.0 (API 24). Trip detection needs usable GPS. On devices without a gyroscope or software motion sensors, the app uses the accelerometer when available. Android and individual apps can limit overlays on protected screens.

## Build

Set `ANDROID_HOME` to an Android SDK with platform 36 and use JDK 17 or later. Run `./gradlew :app:assembleStandardDebug :app:testStandardDebugUnitTest :app:lintStandardDebug`. On Windows, use `gradlew.bat`. The debug APK is at `app/build/outputs/apk/standard/debug/app-standard-debug.apk`.

There are two flavors with the same application ID:

- `standard` leaves out the accessibility service. Play Protect and OEM scanners flag sideloaded apps that combine an accessibility service with overlays and location, so this is the build to install.
- `full` adds the accessibility service for drawing over system bars. Expect Play Protect warnings when sideloading it, and on Android 13 and later allow restricted settings in the app info screen before enabling the service.

On Windows, `build-apk.bat` runs the unit tests, builds both release APKs, and writes `signed\motion-anchor.apk` and `signed\motion-anchor-full.apk`. On the first run it creates a release key in `release.jks` and its passwords in `keystore.properties`. Both files are ignored by Git; back them up, because Android installs updates only when they are signed with the same key. To sign with an existing key, write `keystore.properties` with `storeFile`, `storePassword`, `keyAlias`, and `keyPassword` before running the script. Gradle uses the same file for `assembleStandardRelease` and `assembleFullRelease`.

If Android shows "App not installed", uninstall any earlier Motion Anchor build first. Builds signed with a different key, such as the old debug-signed APKs, cannot be updated in place.

## Support

If you find Motion Anchor helpful, you can support its development:

<a href="https://buymeacoffee.com/vadson" target="_blank"><img src="https://cdn.buymeacoffee.com/buttons/v2/default-yellow.png" alt="Buy Me A Coffee" height="48"></a>

## Credits and license

Motion Anchor is a modified fork of [David Ventura’s motion-sickness-app](https://github.com/DavidVentura/motion-sickness-app). The fork adds its own Android settings interface, compatibility handling, custom markers, and optional session controls. See [LICENSE](LICENSE) for the GPL-3.0-only license.
