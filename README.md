# XMusic 2.2 — YouTube-first Android music player

XMusic is an independent Android/Kotlin music-player project focused on YouTube discovery, background playback, and real-time PCM DSP.

## Current architecture
- Jetpack Compose UI.
- Media3 `MediaSessionService` + `MediaController` for background/system playback controls.
- Replaceable Piped-compatible discovery/resolver layer with multiple fallback instances.
- 31-band peaking EQ and configurable 3-band crossover in the actual Media3 audio sink.
- Real-time analyzer.
- Local favorites and playback history stored on-device.
- Android 8+ baseline (`minSdk 26`).

## Engineering policy
The project follows a minimal-change development approach: preserve working components, isolate replaceable infrastructure, and avoid broad rewrites unless testing shows they are necessary.

## Important limitations
- Public third-party resolver instances can become unavailable or change behavior. The resolver is deliberately isolated so it can be replaced without rewriting the player.
- This source package does not claim to bypass service protections or provide unauthorized access to protected content. Use online sources in accordance with applicable rights and service terms.
- The current patch does not pretend that a stereo crossover creates separate physical speaker outputs.

## Build
Open `android/` in Android Studio with a current Android SDK and compatible Gradle environment. A Gradle wrapper is not included in this source archive, so the machine building it must provide a compatible Gradle setup.
