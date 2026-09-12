# XMusic 2.0 — no ads, local player + real PCM DSP

XMusic is an Android/Kotlin music-player foundation focused on a clean, ad-free experience and real audio processing.

## Included
- Android Kotlin + Jetpack Compose UI
- Media3 background playback / MediaSession
- Local audio picker with persisted URI permission
- Queue of selected tracks and previous/play/next controls
- 31-band peaking EQ from 20 Hz to 20 kHz, ±12 dB
- Real PCM biquad processing and limiter
- Real 3-band crossover/recombine DSP (Low/Mid/High) with adjustable crossover points, band gain and slope presets
- Supporter-only theme concept; test switch is intentionally marked as a local test hook

## Important status
This package is a **source project**, not an APK. An Android SDK/Gradle environment is required to build it.

The crossover genuinely processes and recombines the stereo PCM signal. It does **not** claim to create separate physical speaker outputs; that requires OS/device/USB routing support.

The project does not include a YouTube extractor/downloader. Online-source integration must be designed around applicable rights, service terms and platform policies.

## Build
Open the project folder in Android Studio with a current Android SDK, or use a machine/Termux environment with Gradle + Android SDK installed.
