# SDR2HDR

SDR2HDR 是一个 Android 应用实验项目，用于把普通 SDR 照片转换并导出为带
gain map 的 Ultra HDR JPEG。目标场景包括手机相册浏览、社交媒体发布测试，
以及研究 Android Ultra HDR / HDR gain map 的兼容性。

English: SDR2HDR is an Android MVP for turning an SDR gallery image into an
Ultra HDR JPEG with a gain map.

## Search Keywords

SDR2HDR, SDR to HDR, SDR 转 HDR, Ultra HDR Android, HDR 照片转换,
gain map, Android Gainmap, Ultra HDR JPEG, 小红书 HDR 图片, HDR photo converter.

## Download

The latest APK is published on the GitHub Releases page:

https://github.com/dcysbb/SDR2HDR/releases

## Features

The current version focuses on the core mobile workflow:

- Pick an SDR image from the phone gallery.
- Generate a grayscale gain map from likely highlights.
- Attach the gain map with Android's `android.graphics.Gainmap` API on Android 14+.
- Save the result to `Pictures/SDR2HDR`.
- Share the exported JPEG through the Android share sheet.
- Generate a built-in HDR test image for device compatibility checks.
- Verify after saving whether the exported JPEG still contains a gain map.

## Requirements

- Android Studio with Android SDK 36.
- JDK 17. The local Android Studio JBR works.
- A real Android 14+ device is required to verify Ultra HDR display.
- Android 16 / API 36 devices can use the explicit SDR-to-HDR gain map direction.

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

Some OEM gallery apps may require private HDR or XDR metadata before they show
an HDR badge. A file can contain a standard Ultra HDR gain map and still render
as SDR in a gallery app that does not support that standard path.
