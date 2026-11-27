# Nina Pocket

Nina ([nina-live](https://github.com/ibnHatab/nina-live)) as an Android app: her voice (fsttm-live's
dialogue on Gemini Live), her rules and tasks (pi) running on the device, her own screen (her face and the
conversation) in a WebView, and the device's own actions (the radio in the app's player, alarms and timers
through the Clock app). No wake word: she listens while she is in front.

| variant | CPU | Android | for |
|---|---|---|---|
| `arm32` | armeabi-v7a | 7.1+ (minSdk 25, target 25) | the Fire 7 (2017) on LineageOS 14.1 |
| `arm64` | arm64-v8a | 8+ (minSdk 26, target 34) | newer phones |

The app knows no persona: fsttm-live's `pkg/mobile` is the bridge (the mic in, her voice out, the device's
actions, the start page with her keys), nina-live's `mobile/` registers Nina into it. Her files (the
catalog with its built image, her character, her config, her face from acasa) are inside the APK and copied
into the app's private files on the first start and after an update. Her keys (Gemini; OpenRouter
optional) are typed on the start page and stay on the device.

## Build

No Android Studio or Gradle: the SDK's build tools, the NDK and gomobile.

    ./build.sh arm32            # or arm64; --install puts it on the adb device
    # → out/nina-pocket-<variant>.apk

The repos beside this one: `fsttm-live`, `nina-live`, `acasa`, `perotheia-packages/metis-lang` (REPO_ROOT).
CI (`.github/workflows/build.yml`) builds both variants from them; refs are inputs of a manual run.
Signing: `scripts/make-signing-key.sh` (once, by the owner) makes the release key in `~/.config/nina/` and
sets the CI secrets `KEYSTORE_B64`, `KEYSTORE_PASS`, `KEY_ALIAS`; without them a branch build signs with a
throwaway key. A release: push a tag `v*` (e.g. `git tag v0.1.0 && git push --tags`): both APKs are built,
signed with the release key, and attached to a GitHub release.
## Measured (Fire 7 2017 "austin", LineageOS 14.1, MT8127, armeabi-v7a, 1 GB; 2026-11-27)

Nina's dialogue running (Gemini Live, pi on Gemini Flash), turns spoken from Pascal's speakers (Paradee):

| What | Value |
|---|---|
| Cold start to the activity displayed | 976 ms (`am start -W`) |
| First audio after a turn | 569, 583, 588, 614, 990 ms (5 turns) |
| Memory | 75 MB PSS in all (Dalvik 2.5 MB; Go heap 3–17 MB), 114 MB RSS |
| CPU, the app | median 8 %, max 26 % of one core while she speaks (2 cores online) |
| CPU, audioserver | median 17 % (the voice-communication capture: the platform's echo canceller) |
| WebView frames | 101 frames, 27 % janky, 50th 11 ms, 90th 26 ms, 99th 40 ms (the panel's bubble updated per word) |
| Thermal zone 0 | 39 °C |

The jank is the conversation bubble re-rendered on every streamed word; batching those updates (one
render per animation frame) is the first thing to try if the panel feels slow.

### Her face (acasa's nina-card, `shell.html?remote`), same day

At rest, the app's CPU and the WebView's frame time, by setting:

| Setting | CPU at rest | Frame 50th |
|---|---|---|
| parallax shader 30/15 fps, blur over her face (the card's defaults) | 29–40 % | 31 ms |
| CSS sway instead of the shader (`parallax=0`) | 37–59 % | 48 ms |
| shader 20/8 fps (`fps=20,8`) | 19–32 % | 32 ms |
| + no blur (`lite`) | 17–25 % | 15 ms |
| + 0.7 resolution (`scale=0.7`): **Nina's setting** | 14–22 % | 15 ms |

During a reply at Nina's setting: 41–55 % CPU, 7 % janky frames, 50th 12 ms (the plain panel before the
batching: 27 % janky). The blur (`backdrop-filter`) was half of every frame: each new frame of her face
re-blurs what lies under the bubbles.
