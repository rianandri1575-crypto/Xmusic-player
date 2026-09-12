import SwiftUI

@main
struct XMusicIOSApp: App {
    @StateObject private var audio = AudioEngine()
    var body: some Scene {
        WindowGroup { ContentView().environmentObject(audio) }
    }
}
