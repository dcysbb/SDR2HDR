# SDR2HDRAndroid

An Android MVP for turning an SDR gallery image into an Ultra HDR JPEG.

The first version focuses on the core mobile workflow:

- Pick an SDR image from the phone gallery.
- Generate a grayscale gain map from likely highlights.
- Attach the gain map with Android's `android.graphics.Gainmap` API on Android 14+.
- Save the result to `Pictures/SDR2HDR`.
- Share the exported JPEG through the Android share sheet.

## Requirements

- Android Studio with Android SDK 36.
- JDK 17. The local Android Studio JBR works.
- A real Android 14+ device is required to verify Ultra HDR display.

## Build

From this folder on the current machine:

```powershell
$env:JAVA_HOME='C:\Program Files\Android\Android Studio\jbr'
.\gradlew.bat :app:assembleDebug
```

Or open the folder in Android Studio and run the `app` configuration.

If Android Studio opens without an enabled run button, close the current window and open
`C:\Users\Poozh\Documents\SDR2HDRAndroid` itself, not the `app` subfolder or `Documents`.

## Notes

Android 13 and below can still run the app, but they cannot encode a native `Gainmap` Ultra HDR JPEG through the framework. The app exports a normal SDR JPEG on those systems. A production version should add Google's `libultrahdr` as a native encoder fallback.
