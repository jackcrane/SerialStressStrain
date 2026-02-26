# Serial Stress Strain

Android app for reading USB serial data and plotting it as a live line graph.

## What It Does

- Detects USB serial devices connected to an Android phone/tablet (USB host mode).
- Requests USB permission and opens a serial port at a configurable baud rate.
- Parses incoming UTF-8 line-based packets and renders live chart data.
- Lets you tune sample window size and optional Y-axis bounds.

## Features

- USB serial support via `usb-serial-for-android`
- Connect/disconnect flow with device attach/detach handling
- Live chart rendering in Jetpack Compose
- Settings screen for:
- Device selection
- Baud rate
- Sample retention window
- Y min / Y max overrides
- Full-screen chart mode after connect
- Hidden settings shortcut: tap chart 5 times to reopen settings

## Data Protocol

The app expects newline-delimited UTF-8 messages in this format:

```text
<packetType>,<x>,<y>
```

Rules currently implemented:

- `packetType = 0`: append point `(x, y)` to the graph
- `packetType = 1`: clear the current graph
- Any malformed line is ignored
- `\r\n` and `\n` line endings are both handled

Examples:

```text
0,0.0,12.4
0,1.0,12.7
1,0,0
0,0.0,10.9
```

## Requirements

- Android Studio with Android SDK installed
- JDK 11 (project `sourceCompatibility`/`targetCompatibility` is Java 11)
- Android device with USB host/OTG support for real serial hardware testing

Project SDK targets:

- `minSdk = 24`
- `targetSdk = 36`
- `compileSdk = 36`

## Getting Started

1. Clone the repository.
2. Open it in Android Studio.
3. Let Gradle sync and download dependencies.
4. Connect an Android device (recommended for USB serial testing).
5. Run the app (`app` configuration).

## Build and Test (CLI)

```bash
./gradlew assembleDebug
./gradlew test
./gradlew connectedAndroidTest
```

Notes:

- `connectedAndroidTest` requires a connected device/emulator.
- USB serial hardware validation requires a physical Android device with OTG.

## App Usage

1. Tap `Refresh` to scan USB serial devices.
2. Select a detected device.
3. Enter baud rate (default: `9600`).
4. Tap `Connect`.
5. Optionally set:
- `Samples to keep` (default `10000`)
- `Y min` and `Y max` (leave blank for auto-range)
6. Tap `Save & Return` to switch to full-screen chart.
7. Tap the chart 5 times to reopen settings.
8. Tap `Disconnect` when done.

## Troubleshooting

- No devices detected:
- Confirm OTG adapter/cable is working.
- Confirm your phone supports USB host mode.
- Reconnect device and tap `Refresh`.
- Permission denied:
- Reconnect and retry `Connect`; Android USB permission must be granted.
- Connected but no graph updates:
- Verify baud rate matches firmware.
- Verify packet format is exactly `type,x,y`.
- Ensure each message ends with a newline.
- Flat/empty graph:
- Clear `Y min`/`Y max` if the bounds are too restrictive.

## Tech Stack

- Kotlin
- Jetpack Compose (Material 3)
- AndroidX Lifecycle/ViewModel
- [`com.github.mik3y:usb-serial-for-android`](https://github.com/mik3y/usb-serial-for-android)

## Code Map

- `app/src/main/java/com/example/serialstressstrain/MainActivity.kt`
- `app/src/main/java/com/example/serialstressstrain/SerialViewModel.kt`
- `app/src/main/java/com/example/serialstressstrain/ui/SerialScreen.kt`
- `app/src/main/java/com/example/serialstressstrain/usb/SerialUsbReceiver.kt`

