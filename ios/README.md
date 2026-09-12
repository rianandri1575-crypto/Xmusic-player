# XMusic iOS

Native SwiftUI/AVAudioEngine source for XMusic 2.0.

Included:
- SwiftUI navigation
- 31-band AVAudioUnitEQ, ±12 dB
- Crossover UI foundation and routing note
- Dark modern UI
- No ads

To make an iOS app target, open Xcode on macOS, create an iOS App target named `XMusicIOS`, then add the files in `XMusicIOS/`. Set the deployment target to iOS 16+ and enable Background Modes > Audio, AirPlay, and Picture in Picture for background playback. A signed `.ipa` requires Xcode/macOS and an Apple signing identity.
