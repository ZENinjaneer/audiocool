# AudioCool features in detail

Everything the app does and how it behaves. For the short version, see the [README](../README.md).

- [Recording and notes](#recording-and-notes)
- [One timeline](#one-timeline)
- [Waveform scrubber](#waveform-scrubber)
- [Photos and the text in them](#photos-and-the-text-in-them)
- [Live transcription](#live-transcription)
- [Summaries](#summaries)
- [Folders](#folders)
- [Search](#search)
- [Lock screen](#lock-screen)
- [Spoken notes](#spoken-notes)
- [AudioCool Desktop](#audiocool-desktop)
- [Backup and sharing](#backup-and-sharing)
- [The app's log](#the-apps-log)

## Recording and notes

- Each note is linked to the moment in the recording when you started typing it. Tap a note to play
  from just before that moment (3 seconds by default, never reaching back past the previous note).
- Notes typed while replaying link to the playback position. ★ marks the current moment without
  typing anything.
- Recording runs in a foreground service, so it continues with the screen off or in another app.
- Audio is recorded as AAC in ADTS framing (`.aac`), which stays playable even if the app is killed
  mid-recording, then converted to `.m4a` without re-encoding, so long recordings open and seek instantly.

## One timeline

A session shows everything in the order it happened: what was said, in paragraphs; each note right
after the paragraph it was written during; photos as chapters; ★ marks.

- Switch between **Everything**, **Notes + context** (only what was said around each note, the rest
  folded into "2:15 of talk", which you can tap open) and **Notes only**.
- While it plays, the paragraph playing lights up, the latest photo opens up and each note grows as
  playback passes it; the timeline follows along.
- Tap anything to play from there (notes from a few seconds before).

## Waveform scrubber

- Drag along the recording's waveform to move through it, and the timeline follows your finger.
- Notes, marks and photos sit above it as dots and thumbnails. Tap one, or press and slide along
  them, for a quick preview (the note, the photo, or for a ★ what was being said), outlined in the
  timeline too, with **Play from** to go there.
- ±10 s and speed (0.75× to 2×) as well.
- Recordings keep their loudness as they're made; older ones are measured once.

## Photos and the text in them

- **Photos:** tap the camera in the note box to photograph a slide. The photo becomes a note linked
  to that moment; tap it to see it full screen and play the recording from when it was taken. The
  first time, Android asks to let AudioCool use the camera.
- **From the gallery** (⋮ › Add photos from gallery): photos taken during a recording, e.g. with the
  camera app, land at the moment they were taken. Photos are saved upright at up to 2560 px.
- **Text in photos:** the text in every photo is read on the phone (ML Kit) and shown with it on the
  timeline ("Text in photo": a few lines, tap for all of it; long-press to copy it). It's searchable,
  and shared notes quote it under each photo.
- **Titles:** a session still named after its start time takes its title from its first photo that
  has one: the biggest text, joined across lines, skipping signs, web addresses, slide numbers and
  anything the reader isn't sure of. A name you give a session is never changed.
- **Thumbnails and gallery view:** a session's first photo becomes its thumbnail (long-press another
  photo to use it instead). The grid button on the main screen switches between the list and a
  gallery of thumbnails with each session's name underneath; search results show them too.

## Live transcription

- While you record, each phrase is transcribed on the phone a moment after it's spoken, and nothing is
  uploaded. It uses [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) and NVIDIA Parakeet 0.6B
  (English), downloaded once (about 660 MB). The app offers the download on the main screen and
  while recording; a recording already going is transcribed from its start once it arrives.
- What was said reads as paragraphs on the timeline; tap one to play from there. Older recordings
  can be transcribed (or re-transcribed) in the background.
- **Noisy and distant speech:** the app finds speech in a noise-filtered copy of the audio (GTCRN),
  which catches far more of it in quiet, echoey or noisy rooms, but Parakeet always hears the
  original recording: every test of feeding it filtered audio made it worse. The recording itself
  is never altered. See [the accuracy numbers](DEVELOPMENT.md#transcription-accuracy).

## Summaries

Google's Gemma 4 E2B (2.6 GB, downloaded once; ⋮ › Summaries) runs on the phone through LiteRT-LM,
in a background process of its own.

- A session is cut into chapters: each slide photo and what was said until the next one (or a few
  minutes of talk). Each chapter is summarized in two or three sentences as soon as it's complete,
  during a recording too, and the summary sits on the timeline under its slide.
- When the whole session is done, it gets a summary at the top with key points and action items
  (taken from the talk and your notes), and a title if it still has its date-and-time name.
- It runs at low priority with two CPU threads while recording, so live transcription keeps up, and
  waits while recordings are transcribed.
- If the engine fails on a phone, only its process stops; the app tries again without speculative
  decoding, then turns summaries off. The GPU is an experimental option.

## Folders

- Long-press a session (or use ⋮ in it) and pick **Move to folder**, where you can also make a new folder.
- Once there's a folder, the main screen has a row of chips: **All**, each folder, and **+** for a new
  one. A folder's chip shows just its sessions, with how many there are and a menu to rename or
  delete the folder. Deleting a folder keeps its sessions, outside any folder.
- A session started while a folder is shown goes in it, and the app opens on the folder you last chose.
- In **All**, each session's line says which folder it's in. Moving sessions doesn't change their order.
- A session's folder is saved with it, so backups and the desktop keep it.

## Search

- Find words in folder names, session names, summaries (with their key points and action items),
  notes, slide text and transcripts: across everything from the main screen, or within one session
  with its 🔍 button.
- Searching while a folder is shown keeps to that folder; the **In …** chip's ✕ searches everything.
- Filters under the search box narrow it to some kinds of result: folders, sessions, summaries, notes,
  slides or what was said. Each shows how many it found; with none picked, everything shows.
- Tapping a result goes to it. From the main screen, a session's name opens the session, and anything
  else opens its session and plays from that moment. Within a session, the timeline scrolls to it and
  outlines it for a moment; a part's summary plays the part from its start.
- A session's search is kept, so 🔍 brings its results back for trying the next match.

## Lock screen

- While recording, the notification works without unlocking: ★ Mark, Pause/Resume and Stop.
- Tapping the notification opens a capture screen over the lock screen with the camera, spoken notes,
  mark, pause and stop. It never shows your notes.
- To *start* without unlocking, turn on the Record button (⋮ › Lock screen controls): a quiet
  notification that's there while you're not recording. Tap it to record with the camera ready, or
  press its Record button to just record.
- There's also a Record tile for quick settings, which starts and stops recording; from the lock
  screen it asks you to unlock first.

## Spoken notes

- Hold the mic button next to the note box and say the note. It's transcribed on the phone and linked
  to the moment you started speaking. A quick tap listens hands-free until the next tap.
- With a headset plugged in or paired, notes are heard through the headset's mic while the phone's
  own mic keeps recording the room.
- If both a headset and the phone mic are available, the recording screen lets you choose which one
  records the room, e.g. a clip-on mic near the speaker.
- Some phones can't record from two mics at once. The app notices, keeps the recording safe, and
  takes spoken notes through the phone's mic instead.

## AudioCool Desktop

Pair with [AudioCool Desktop](../desktop/README.md) on the same Wi-Fi and send a session to be
transcribed by bigger models on your PC's GPU; the transcript comes back to the phone.

## Backup and sharing

- **Backup:** pick a folder (⋮ › Backup & restore) and every session (audio, notes, transcript) is
  copied there automatically. The copy survives uninstalling; *Restore from a backup* brings it back.
- **Share** sends the notes as Markdown (with the summary and transcript) plus the audio files and photos.

## The app's log

⋮ › Share the app's log sends one text file with crash reports (version, phone, full stack) and the
app's own log, which logcat keeps in rotating files as the app runs (2 MB at most). After a crash, the
next launch offers to share it. No notes or recordings are in it.
