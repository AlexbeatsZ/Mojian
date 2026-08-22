# Ink Lab Android Prototype

`Ink Lab` is a clean-room tablet test bench for studying Notein-like handwriting behavior. It does not read or modify Notein data and is not a notebook application yet.

## Safety boundary

- release application ID: `com.meta.inklab`;
- debug package installed by this project: `com.meta.inklab.debug`;
- no Internet permission;
- no external-storage permission;
- session-only strokes;
- stylus/eraser input draws, while finger/palm input on the paper is consumed without creating ink.

## Build and lint

Use the repository-local Gradle wrapper:

```powershell
cd C:\Users\Meta\Documents\ChatGPT\Notein\android
.\gradlew.bat :app:assembleDebug :app:lintDebug
```

The APK is written to `app\build\outputs\apk\debug\app-debug.apk`.

## Install and launch

Normal Android devices can use `adb install -r`. The tested Xiaomi HyperOS build rejects the ADB install channel with `INSTALL_FAILED_USER_RESTRICTED`; copy the APK to Downloads, open it in Xiaomi File Manager, and choose the installer's `单次安装授权`. Do not enable a persistent global unknown-source setting.

Launch component:

```text
com.meta.inklab.debug/com.meta.inklab.MainActivity
```

## Stylus test sequence

The current build intentionally exposes one clean-room fountain-pen stroke. Its three continuous
controls are independent:

- width: 0.1–2.0 mm, default 0.4 mm;
- pressure correction: 0–400%, default 200%;
- ink-flow/speed correction: 0–200%, default 100%.

The percentage boundary follows the values persisted by Notein: the displayed percentage is
divided by 100 before it enters the brush configuration (`200% -> 2.0`, `100% -> 1.0`). The wider
lab ranges are experimental; they are not evidence that Notein's own UI exposes those maxima.

Write the same short Chinese phrase while changing one parameter at a time. Repeat once with
`预测 开` and once with `预测 关`. Judge these separately:

- nib-to-wet-ink alignment during fast curves;
- visible tail snap or double image when lifting;
- start/end taper and horizontal/vertical contrast;
- pressure controllability at slow speed;
- palm/finger rejection.

Test unplugged. This tablet has shown charging-related touch/stylus noise, which is raw input noise
rather than evidence about the brush model.

The amber `input age` value measures how old the delivered input event is when handled. It is not an end-to-end pen latency measurement.
