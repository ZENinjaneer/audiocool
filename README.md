# AudioCool

Android app that records audio while you type notes. Each note is linked to the moment in the
recording when you started typing it; tap a note to play from just before that moment.

- Recording runs in a foreground service, so it continues with the screen off or in another app.
- Notes typed while replaying link to the playback position. ★ marks the current moment.
- Share sends the notes as Markdown plus the audio files.
- Audio is recorded as AAC in ADTS framing (`.aac`), which stays playable even if the app is killed
  mid-recording, then converted to `.m4a` without re-encoding so long recordings open and seek instantly.

## Install on your phone

On the phone, open the [latest release](https://github.com/ZENinjaneer/audiocool/releases/latest),
tap the `.apk` under **Assets**, then open the download. Android asks you to allow installs from
your browser the first time. On Samsung phones, Auto Blocker (Settings › Security and privacy) must
be off while installing. A new version installs over the old one and keeps your recordings.

## Build

Needs JDK 17+ and the Android SDK (platform 35, build-tools 35.0.0).

```sh
export JAVA_HOME=~/.jdks/amazon-corretto-21.0.12.12.1-linux-x64 ANDROID_HOME=~/Android/Sdk
./gradlew testDebugUnitTest assembleRelease
# -> app/build/outputs/apk/release/app-release.apk
```

The release build is signed with `keystore/audiocool-release.jks` (passwords in
`keystore.properties`; both are git-ignored). Keep a backup: an update must be signed with the
same key to install over the existing app, and uninstalling deletes the app's recordings.
