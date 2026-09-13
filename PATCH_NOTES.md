# XMusic 2.2 — Full stabilization patch

## Done
- MediaController state is observable by Compose.
- Safe controller connection failure handling and listener cleanup.
- Now Playing reflects play/pause/title changes.
- Playback writes the selected track to local history.
- Added local Favorites + History library.
- Added favorite controls to search results.
- Search/resolver input validation, duplicate filtering, bounded timeouts, and cleaner error paths.
- Resolver remains isolated behind `YouTubeRepository` so it can be replaced later.
- DSP limiter is transparent below 0 dBFS instead of continuously compressing normal audio.
- Existing Media3, EQ, crossover, analyzer, and service architecture preserved.

## Not falsely claimed as complete
- A production-grade YouTube extractor is not implemented by this patch.
- Download/offline playback is not implemented yet.
- Full playlist synchronization, lyrics provider integration, and advanced library database are not implemented yet.
- Automated Android build could not be executed in this environment because no Gradle executable/wrapper is present.


## Kotlin compile fix
- Replaced ambiguous positional Compose `Slider`/`Switch` calls with named arguments and explicit lambda parameters in `MainActivity.kt`.
- Fixes Kotlin 2.x overload-resolution / unresolved `it` errors reported around EQ and Crossover controls.


## Runtime hardening follow-up
- MediaController/MediaSession connection is now lazy instead of happening during Activity startup.
- Custom PCM-16 DSP explicitly disables Media3 float output to avoid an unsupported PCM_FLOAT format reaching XMusicAudioProcessor.
