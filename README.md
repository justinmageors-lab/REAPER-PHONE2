# REAPER PHONE — Android starter project (Build 001)

This is an Android Studio starter project for REAPER PHONE.

## Included in Build 001
- Compose-based dark DAW-style interface
- Microphone recording to app-private WAV files (PCM WAV writer via AudioRecord)
- Import audio files using Android's document picker
- Track list with selection
- Playback of imported and recorded WAV/MP3 files
- Local app storage for recordings

## Not included yet
This is a first functional recording/playback foundation, not a REAPER-equivalent DAW yet.
Synchronized multitrack playback/mixing, waveform editing, USB multi-channel routing,
MIDI, automation, plugin hosting, and built-in effects are planned subsequent milestones.
The current recorder captures mono PCM 16-bit at 44.1 kHz using Android AudioRecord.
USB interface input selection depends on Android device/driver support and is not yet implemented.

## Build
1. Install Android Studio (current stable) and Android SDK Platform 35.
2. Open this folder in Android Studio.
3. Let Gradle sync.
4. Connect a Samsung Galaxy phone with USB debugging enabled.
5. Press Run.

To build an APK: **Build > Build Bundle(s) / APK(s) > Build APK(s)**.

## Permissions
The app requests microphone permission when recording. Imported audio is opened through
Android's Storage Access Framework, so broad storage permission is not required.

## Package
`com.reaperphone.daw`

## Audio architecture roadmap
- Replace/augment the initial Android AudioRecord/MediaPlayer proof of concept with a native
  C++ Oboe engine using a single output stream and an internal mixer.
- Keep the UI and project model separate from the real-time audio callback.
- Add device enumeration and USB input/output routing.
- Add non-destructive clip editing, gain/pan, effects graph, MIDI and project serialization.
