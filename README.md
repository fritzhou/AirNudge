# AirNudge

**Control without touching.**

AirNudge is an Android utility for local, camera-based hand gesture control. The
repository currently contains **all six planned phases**: the Android foundation, a
fresh-frame CameraX pipeline, local MediaPipe hand tracking, initial gesture
recognition, generic Android accessibility actions, customization, and Air Cursor.

## Phase 1 features

- Android 7.0 (API 24) minimum support
- Front-camera preview controlled by a clear on/off switch
- Runtime camera permission request and accessibility settings shortcut/status
- CameraX `ImageAnalysis` with `STRATEGY_KEEP_ONLY_LATEST`
- No fixed camera frame rate or analysis resolution
- Optional camera/analyzer FPS and frame-age metrics
- Camera resources released when the activity stops or control is disabled
- On-device-only privacy messaging

## Phase 2 features

- Local MediaPipe Hand Landmarker in live-stream mode, limited to one hand
- Hand presence, confidence, all 21 landmarks, index tip, thumb tip, and palm
  center reporting
- Open palm, closed fist, pinch, and four-direction swipe recognition
- Short motion history with distance, dominance, confidence, and directional
  consistency checks
- Pose confirmation, cooldown, and one-shot pose latching to reduce accidental
  and duplicate detections
- Inference time, detector FPS, gesture confirmation time, and landmark metrics

The official float16 Hand Landmarker model is downloaded by the Gradle
`preBuild` dependency and packaged as an app asset. Camera images and landmarks
remain on the device.

## Phase 3 features

- A focused `AirNudgeAccessibilityService` that does not contain camera or
  recognition logic
- Generic upward, downward, left, and right screen swipes
- Closed fist mapped to Android Back
- Pinch mapped to a temporary center-screen tap, ready for the later Air Cursor
- A camera foreground service with a visible notification and Stop action, so
  recognition can continue while another app is open
- End-to-end timing from the beginning of a confirmed motion through action
  dispatch

AirNudge does not inspect screen content and contains no app-specific TikTok,
Facebook, Instagram, YouTube, browser, or Gallery automation.

## Phase 4 features

- Local gesture-to-action mappings for all seven recognized gestures
- Low, Balanced, and High recognition sensitivity
- Configurable 250–1200 ms gesture cooldown
- Optional Air Cursor driven by an index-finger pointing pose
- Cursor sensitivity, smoothing, two-pixel jitter dead zone, and pointer size
- Pinch-to-tap at the current Air Cursor position, with a center-screen fallback
- Settings stored locally in `SharedPreferences`

Air Cursor uses a simple accessibility overlay. It only appears while Air Cursor
is enabled and a pointing pose is visible, and it never reads screen content.

## Phase 5 performance and reliability

- Frames are rejected before bitmap conversion whenever inference is already in
  progress, so detector work cannot queue.
- The detector reuses its RGBA bitmap and padded-row buffer instead of allocating
  both for every processed frame.
- Analysis rate is based on measured inference duration. It remains responsive
  while a hand is present, drops to 10 analyses/second after two seconds without
  a hand, and caps at roughly 7 analyses/second during severe thermal pressure.
- Camera analysis stops completely while the screen is off and resumes when the
  screen turns on.
- Developer metrics and five-second Logcat profiles report camera/detector FPS,
  inference and frame age, early dropped frames, actual analysis resolution,
  process CPU time, used heap, battery current, battery temperature, and Android
  thermal status.
- Large one-frame landmark jumps are treated as tracking discontinuities instead
  of swipes.

AirNudge continues to let CameraX choose the analysis resolution. No automatic
resolution change was added without physical-device evidence that it improves
latency or reliability.

## Build

Install Android SDK Platform 35, set `ANDROID_HOME` (or add `sdk.dir` to an
untracked `local.properties` file), then run:

```bash
./gradlew assembleDebug
```

Open the project in Android Studio and run it on a physical Android device with
a front camera to validate camera lifecycle behavior.

## Manual check

1. Launch AirNudge and grant camera permission.
2. Open **Accessibility settings** and enable AirNudge.
3. Turn **Gesture control** on and confirm the front-camera preview and persistent
   notification appear.
4. Show one hand and confirm that the status reports **Hand detected**.
5. Perform each supported pose and swipe, checking that one movement produces
   one displayed gesture.
6. Enable **Developer metrics** and confirm that inference, detector FPS,
   landmarks, and frame age update without an accumulating delay.
7. Open another app and confirm that swipes, Back, and the temporary center tap
   produce standard Android input.
8. Use the notification Stop action and confirm that the camera shuts down.
9. Open **Gesture settings**, change a gesture mapping, and confirm the new action
   is used without restarting gesture control.
10. Enable Air Cursor, point with only the index finger, and tune sensitivity,
    smoothing, and pointer size. Pinch and confirm the tap occurs at the pointer.

Camera frames are analyzed in memory, closed immediately, and never recorded or
uploaded.

## Device profiling protocol

Run the same 20-minute scenario on a low-end, mid-range, and high-end device:

1. Reset battery statistics with `adb shell dumpsys batterystats --reset`.
2. Start AirNudge, enable Developer metrics, and capture logs with
   `adb logcat -s GestureControlService`.
3. Measure five minutes with no hand, five minutes of gestures, five minutes with
   Air Cursor, and five minutes inside a target app.
4. Capture `adb shell dumpsys meminfo com.airnudge.app`,
   `adb shell top -b -n 1 -p $(adb shell pidof com.airnudge.app)`, and
   `adb shell dumpsys batterystats com.airnudge.app` after each interval.
5. Compare gesture latency, false detections, memory growth, battery temperature,
   current draw, dropped frames, and thermal status rather than FPS alone.

No physical Android devices are attached to this repository environment, so
device-specific numbers must be collected before changing resolution or thermal
thresholds further.

## Phase 6 product completion

- First-launch onboarding explains local processing and links directly to camera
  and accessibility setup.
- A gesture tutorial documents every supported pose, swipe, and Air Cursor.
- Optional three-step calibration records natural Swipe Up, Swipe Down, and Pinch
  measurements, derives bounded thresholds, and persists them locally.
- Explicit camera and MediaPipe errors replace generic failure states.
- The foreground service remembers an intentional enabled state and uses sticky
  recovery after an unexpected process restart; an explicit Stop always clears it.
- Calibration and the main controls retain state across screen rotation.
- Release builds enable R8 shrinking, resource shrinking, cleartext blocking, and
  MediaPipe keep rules. User data is excluded from Android backup.

App-specific profiles were intentionally not added because global controls have
not yet completed the required physical-device stability matrix.

## Release build

Create an unsigned optimized APK with:

```bash
./gradlew clean assembleRelease
```

The output is `app/build/outputs/apk/release/app-release-unsigned.apk`. Configure
your own private signing key in Android Studio or CI; signing credentials are not
stored in this repository. The first build requires network access for Android,
MediaPipe dependencies, and the official hand model.

### Publishing the APK to GitHub Releases

The `Build and publish APK` workflow tests the app, builds a signed release APK,
verifies its signature, creates a SHA-256 checksum, and uploads both files to the
matching GitHub Release. Configure these repository secrets first:

- `AIRNUDGE_KEYSTORE_BASE64`
- `AIRNUDGE_KEYSTORE_PASSWORD`
- `AIRNUDGE_KEY_ALIAS`
- `AIRNUDGE_KEY_PASSWORD`

Then push a version tag such as `v1.0.0`, or run the workflow manually with that
tag. The signing key must be retained for every future AirNudge update.
