import AVFoundation
import Combine

final class AudioEngine: ObservableObject {
    let engine = AVAudioEngine()
    let player = AVAudioPlayerNode()
    private let eq = AVAudioUnitEQ(numberOfBands: 31)
    @Published var gains = Array(repeating: Float(0), count: 31)
    @Published var isPlaying = false
    private let frequencies: [Float] = [20,25,31.5,40,50,63,80,100,125,160,200,250,315,400,500,630,800,1000,1250,1600,2000,2500,3150,4000,5000,6300,8000,10000,12500,16000,20000]

    init() {
        AVAudioSession.sharedInstance().setCategory(.playback, mode: .moviePlayback, options: [])
        for (i, band) in eq.bands.enumerated() {
            band.filterType = .parametric
            band.frequency = frequencies[i]
            band.bandwidth = 1.0
            band.gain = 0
            band.bypass = false
        }
        engine.attach(player); engine.attach(eq)
        engine.connect(player, to: eq, format: nil)
        engine.connect(eq, to: engine.mainMixerNode, format: nil)
        try? engine.start()
    }

    func setGain(_ index: Int, _ value: Float) {
        guard gains.indices.contains(index) else { return }
        gains[index] = max(-12, min(12, value))
        eq.bands[index].gain = gains[index]
    }

    func resetEQ() { for i in gains.indices { setGain(i, 0) } }
}
