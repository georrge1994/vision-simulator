# Myopia Simulator

Android camera preview with combinable myopia, astigmatism and haze effects, optional ARCore scene depth, and 20 interface languages. Application ID: `ru.georgiy.vision`.

Russian name: «Симулятор близорукости».

Original source is released under the [MIT License](LICENSE); dependencies retain their [own terms](THIRD_PARTY_NOTICES.md). Maintained by [Georgiy (georrge1994)](https://github.com/georrge1994). Report bugs or ask privacy questions through [GitHub Issues](https://github.com/georrge1994/vision-simulator/issues); do not include passwords, signing keys, or other private information in an issue.

## Open in Android Studio

Open this directory in Android Studio. Sync with JDK 17 or newer and install Android SDK 36. Gradle is pinned by the wrapper. To build unsigned release artifacts on Windows:

```powershell
.\gradlew.bat :app:assembleRelease :app:bundleRelease
```

## Sign on Windows

In Android Studio, use **Build → Generate Signed App Bundle or APK** and select the actual keystore and key alias. Enter passwords in the signing dialog. An `.arguments` file is not necessarily a keystore. If it is an argument file from a previous build, identify the real keystore and alias locally without sharing or committing the file.

Alternatively, after rebuilding the source, copy the unsigned output into the local ignored `release/` directory and run the signing script in an interactive terminal:

```powershell
New-Item -ItemType Directory -Path release -Force | Out-Null
Copy-Item app\build\outputs\apk\release\app-release-unsigned.apk release\vision-simulator-1.4-unsigned.apk
Copy-Item app\build\outputs\bundle\release\app-release.aab release\vision-simulator-1.4-unsigned.aab
.\tools\sign-release.ps1 -KeyStore 'C:\private\upload-key.jks' -Alias 'your-upload-alias'
```

The paths and alias in the example are placeholders. Keep the actual keystore outside the source directory. The script uses JDK signing tools and Android Build Tools 36.0.0 and prompts for passwords locally. Never put passwords in a command, source file, GitHub issue, or build log. Google Play takes the signed `vision-simulator-1.4-release.aab`. The signed APK is for direct installation. These artifacts and all local signing files are excluded from Git.

Use the same upload key for subsequent Play releases. An install signed with a different certificate can coexist only under a different application ID. Back up any settings you need before removing a prior sideloaded build that uses a different signing certificate.

## Store materials

`store/` contains English and Russian listings, the store icon, the feature graphic, [the privacy policy](store/privacy-policy.html), [Data safety notes](store/data-safety-notes.md), and genuine [phone screenshots](store/screenshots) captured from the app running on an Android emulator. The published [privacy policy](https://georrge1994.github.io/vision-simulator/store/privacy-policy.html) is available over HTTPS. Contact the developer at [Zhore-Chebotarev@mail.ru](mailto:Zhore-Chebotarev@mail.ru) for private privacy questions or through [GitHub Issues](https://github.com/georrge1994/vision-simulator/issues) for public questions.

The app shows a privacy notice before its first camera/AR session; the notice remains available through the privacy button. Camera frames are processed locally. On compatible devices with Google Play Services for AR installed, ARCore supplies the viewfinder, including manual distance and ordinary-camera modes. While an AR session runs, ARCore requires collection of identifiers, app activity/API usage, and diagnostics by Google. Choosing a manual distance does not disable this collection. The ordinary camera fallback works without ARCore. Complete Data safety using [Google’s ARCore disclosure](https://developers.google.com/ar/develop/play-safety-label), rather than declaring that the whole app collects no data.
