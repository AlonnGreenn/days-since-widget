# Days Since

A tiny Android home-screen widget (1×1) that counts whole days since a date.

- Each widget has its own label and date.
- Tap the widget → **Reset to today**, **Change date**, or **Rename**.
- Updates itself just after midnight; follows wallpaper colors on Android 12+.
- No internet, no ads, no dependencies. Only permission: re-arm the midnight refresh after reboot.

## Install (from your phone)

1. Open `https://github.com/AlonnGreenn/days-since-widget/releases/latest/download/days-since.apk`.
2. Open the downloaded file. If Android asks, allow your browser to "install unknown apps".
3. If Play Protect warns about an unrecognised app, choose **More details → Install anyway**.
4. Long-press an empty spot on the home screen → **Widgets** → **Days Since** → drag it out → type a label → **Start today** or **Pick date**.

Updates: install the newer APK the same way; it replaces the old one and keeps your counters.

## Build

Every push to `main` builds a signed APK with GitHub Actions and publishes it as a release.
The signing key (`app/release.jks`, password in `app/signing.properties`) is committed on
purpose so every build can install as an update over the previous one.

Locally: `gradle :app:assembleRelease` with Gradle 8.9+ and the Android SDK (platform 35).
