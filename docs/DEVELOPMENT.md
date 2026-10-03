# Developing AudioCool

- [Build](#build)
- [Tests](#tests)
- [README screenshots](#readme-screenshots)
- [Transcription accuracy](#transcription-accuracy)
- [Releases and signing](#releases-and-signing)

## Build

Needs JDK 17+ and the Android SDK (platform 35, build-tools 35.0.0). The first build downloads the
sherpa-onnx Android library from its GitHub release into `app/libs/` and checks its SHA-256.

```sh
export JAVA_HOME=~/.jdks/amazon-corretto-21.0.12.12.1-linux-x64 ANDROID_HOME=~/Android/Sdk
./gradlew testDebugUnitTest assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

The repo also holds [AudioCool Desktop](../desktop/README.md) (`desktop/`, Python) and AudioCool
Updater (`updater/`, a small Android app that installs the latest release).

## Tests

`./gradlew testDebugUnitTest` runs the unit and UI tests on the JVM with Robolectric. The UI tests
save screenshots of what they check to `app/build/screenshots/`.

Some tests use real engines or data, and are skipped unless pointed at them:

- **The speech engine** (Linux x86_64): `-PsherpaHostDir=<dir>`, a directory with `lib/`
  (sherpa-onnx's `linux-x64-jni` libraries), `models/` (the Parakeet model with its `test_wavs/`),
  `silero_vad.onnx` and `gtcrn_simple.onnx`.
- **Model comparison:** `ModelBenchmarkHostTest` compares models on a test set (word error rate and
  speed) when given `-PasrBenchDir` and `-PasrBenchModels`; `-PasrBenchDenoiser=<gtcrn_simple.onnx>`
  looks for speech in denoised audio, as the app does.
- **Desktop sync:** `DesktopEndToEndHostTest` runs the phone's desktop sync against a running
  AudioCool Desktop when given `-PdesktopUrl=http://localhost:8765 -PdesktopToken=<pairing code>`.

## README screenshots

The screenshots in the README are drawn by `ReadmeScreenshots`, from a demo library (a lecture on
sleep with its slides, notes, transcript and summaries, and a few other sessions). To redo them:

```sh
./gradlew :app:testDebugUnitTest --tests '*ReadmeScreenshots*' -PreadmeScreenshots=docs/screenshots
```

## Transcription accuracy

Word error rate (lower is better) of Parakeet on long recordings, through the app's whole pipeline:

| Recording | Speech found in the raw audio | Speech found in a denoised copy |
|---|---|---|
| TED talk, clean | 4.1% | 3.9% |
| Same talk, simulated lecture hall (quiet, echoey) | 16.1% | 6.0% |
| Same talk, simulated classroom | 4.3% | 4.5% |
| Synthetic lecture, distant and quiet with chatter | 27.5% | 15.9% |

On 63 minutes of real meetings (AMI) recorded by a table mic, Parakeet made 28.3% errors on the
original audio and 29.9% to 39.7% on denoised versions of it; the speakers' headset mics gave
10.5%. Distance matters far more than any filter: a mic near the speaker helps most.

## Who said what: accuracy

`DiarizationBenchHostTest` runs the real speaker models on the host (`-PsherpaHostDir`) over meetings
with known speakers (`-PdiarBenchDir`: `eval/<meeting>.wav` plus `.json` turns, and the models). It
was used on three ~7-minute AMI meetings (EN2002a-c, 3 or 4 people, fast back-and-forth), built from
the test set's utterances laid end to end, once from the headset mics and once from the single
distant mic (like a phone on the table).

- Voiceprint models (how alike two stretches of the same person are, against two different people,
  through the distant mic): 3D-Speaker ERes2Net told people apart best (median similarity 0.34 for the
  same person, 0.15 for others); CAM++ didn't at all (0.44 and 0.43); WeSpeaker ResNet34 and NeMo
  TitaNet small were in between.
- sherpa-onnx's own clustering split each person into many (dozens at its default threshold), so the
  app joins clusters that sound alike afterwards (`WhoSaidWhat`). With ERes2Net, clustering at 1.1 and
  joining at 0.45: 22% diarization error and the right number of people on the headset meetings, 42%
  and within one person on the distant mic.
- Long recordings go a chunk at a time (10 minutes); in 3-minute chunks the error rose to 24% (headset)
  and 49% (distant mic), so voices carry across chunks well enough.
- A person's voice in one meeting against the same person in another: median similarity 0.85 (headset)
  and 0.76 (distant mic), against 0.11 and 0.20 for other people; known voices need 0.6.
- Phrases (the transcript's) clustered by voice did badly (49 to 78%): in a fast exchange one phrase
  often holds several people. The app cuts phrases at speaker changes using the word timings instead.
- sherpa-onnx 1.13.8's `processWithCallback` throws `NoSuchMethodError` from Kotlin; use `process`.

## Releases and signing

- Each version is a GitHub release tagged `vX.Y.Z` whose APK is always named `AudioCool.apk`, so
  `releases/latest/download/AudioCool.apk` serves the newest one. AudioCool Updater installs from there.
- The release build is signed with `keystore/audiocool-release.jks` (passwords in
  `keystore.properties`; both are git-ignored). Keep a backup: an update must be signed with the
  same key to install over the existing app.
