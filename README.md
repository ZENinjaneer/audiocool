# AudioCool

Android app that records audio while you type notes. Each note is linked to the moment in the
recording when you started typing it; tap a note to play from just before that moment.

- Recording runs in a foreground service, so it continues with the screen off or in another app.
- Notes typed while replaying link to the playback position. ★ marks the current moment.
- **Photos:** tap the camera in the note box to photograph a slide. The photo becomes a note linked
  to that moment; tap it to see it full screen and play the recording from when it was taken. Photos
  can also come from the gallery (⋮ › Add photos from gallery): ones taken during a recording, e.g.
  with the camera app, land at the moment they were taken. Photos are saved upright at up to 2560 px.
- **Thumbnails and gallery view:** a session's first photo becomes its thumbnail (long-press another
  photo to use it instead). The grid button on the main screen switches between the list and a
  gallery of thumbnails with each session's name underneath; search results show them too.
- **Spoken notes:** hold the mic button next to the note box and say the note; it's transcribed on
  the phone and linked to the moment you started speaking (a quick tap listens hands-free until the
  next tap). With a headset plugged in or paired, notes are heard through the headset's mic while the
  phone's own mic keeps recording the room. If both a headset and the phone mic are available, the
  recording screen lets you choose which one records the room, e.g. a clip-on mic near the speaker.
  Some phones can't record from two mics at once; the app notices, keeps the recording safe, and
  takes spoken notes through the phone's mic instead.
- **Transcription, live:** while you record, each phrase is transcribed on the phone a moment after
  it's spoken (nothing is uploaded), using [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) and
  NVIDIA Parakeet 0.6B (English), downloaded once (about 660 MB; the app offers it on the main screen
  and while recording, and a recording already going is transcribed from its start once it arrives).
  Each line is timestamped; tap it to play from there. Older recordings can be transcribed (or
  re-transcribed) in the background.
- **Noisy and distant speech:** the app finds speech in a noise-filtered copy of the audio (GTCRN),
  which catches far more of it in quiet, echoey or noisy rooms, but Parakeet always hears the original
  recording: every test of feeding it filtered audio made it worse. The recording itself is never altered.
- **Desktop transcription:** pair with AudioCool Desktop (`desktop/`) on the same Wi-Fi and send a
  session to be transcribed by bigger models on your PC's GPU; the transcript comes back to the phone.
- **Search:** find words across all notes and transcripts from the main screen, or within one
  session's transcript. Tapping a result plays from that moment.
- **Backup:** pick a folder (⋮ › Backup & restore) and every session (audio, notes, transcript) is
  copied there automatically. The copy survives uninstalling; *Restore from a backup* brings it back.
- Share sends the notes as Markdown (with the transcript) plus the audio files and photos.
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
its `test_wavs/`), `silero_vad.onnx` and `gtcrn_simple.onnx`. `ModelBenchmarkHostTest` compares
models on a test set (word error rate and speed) when given `-PasrBenchDir` and `-PasrBenchModels`;
`-PasrBenchDenoiser=<gtcrn_simple.onnx>` looks for speech in denoised audio, as the app does.

### Transcription accuracy

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

The release build is signed with `keystore/audiocool-release.jks` (passwords in
`keystore.properties`; both are git-ignored). Keep a backup: an update must be signed with the
same key to install over the existing app.

## Credits

- [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) (Apache-2.0), fetched at build time.
- [Silero VAD](https://github.com/snakers4/silero-vad) model (MIT), in `app/src/main/assets/`.
- [GTCRN](https://github.com/Xiaobin-Rong/gtcrn) speech enhancement model (MIT), as exported by
  sherpa-onnx, in `app/src/main/assets/`.
- [NVIDIA Parakeet](https://huggingface.co/nvidia/parakeet-unified-en-0.6b) speech model, converted
  for sherpa-onnx, downloaded by the app from Hugging Face on first use. Licensed by NVIDIA
  Corporation under the [NVIDIA Open Model License](https://www.nvidia.com/en-us/agreements/enterprise-software/nvidia-open-model-license/).
