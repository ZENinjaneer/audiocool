# AudioCool

Android app that records audio while you type notes. Each note is linked to the moment in the
recording when you started typing it; tap a note to play from just before that moment.

- Recording runs in a foreground service, so it continues with the screen off or in another app.
- Notes typed while replaying link to the playback position. ★ marks the current moment.
- **Transcription, live:** while you record, each phrase is transcribed on the phone a moment after
  it's spoken (nothing is uploaded), using [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) and
  NVIDIA Parakeet 0.6B (English), downloaded once (about 660 MB). Each line is timestamped; tap it to
  play from there. Older recordings can be transcribed (or re-transcribed) in the background.
- **Desktop transcription:** pair with AudioCool Desktop (`desktop/`) on the same Wi-Fi and send a
  session to be transcribed by bigger models on your PC's GPU; the transcript comes back to the phone.
- **Search:** find words across all notes and transcripts from the main screen, or within one
  session's transcript. Tapping a result plays from that moment.
- **Backup:** pick a folder (⋮ › Backup & restore) and every session (audio, notes, transcript) is
  copied there automatically. The copy survives uninstalling; *Restore from a backup* brings it back.
- Share sends the notes as Markdown (with the transcript) plus the audio files.
- Audio is recorded as AAC in ADTS framing (`.aac`), which stays playable even if the app is killed
  mid-recording, then converted to `.m4a` without re-encoding so long recordings open and seek instantly.

## Install on your phone

On the phone, open the [latest release](https://github.com/ZENinjaneer/audiocool/releases/latest),
tap the `.apk` under **Assets**, then open the download. Android asks you to allow installs from
your browser the first time. On Samsung phones, Auto Blocker (Settings › Security and privacy) must
be off while installing. A new version installs over the old one and keeps your recordings.

## Build

Needs JDK 17+ and the Android SDK (platform 35, build-tools 35.0.0). The first build downloads the
sherpa-onnx Android library from its GitHub release into `app/libs/` and checks its SHA-256.

```sh
export JAVA_HOME=~/.jdks/amazon-corretto-21.0.12.12.1-linux-x64 ANDROID_HOME=~/Android/Sdk
./gradlew testDebugUnitTest assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

To also run the real speech engine in the unit tests (Linux x86_64), point `-PsherpaHostDir` at a
directory with `lib/` (sherpa-onnx's `linux-x64-jni` libraries), `models/` (the Parakeet model with
its `test_wavs/`) and `silero_vad.onnx`. `ModelBenchmarkHostTest` compares models on a test set
(word error rate and speed) when given `-PasrBenchDir` and `-PasrBenchModels`.

The release build is signed with `keystore/audiocool-release.jks` (passwords in
`keystore.properties`; both are git-ignored). Keep a backup: an update must be signed with the
same key to install over the existing app.

## Credits

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0), fetched at build time.
- [Silero VAD](https://github.com/snakers4/silero-vad) model (MIT), in `app/src/main/assets/`.
- [NVIDIA Parakeet](https://huggingface.co/nvidia) speech model, converted for sherpa-onnx, downloaded
  by the app from Hugging Face on first use.
