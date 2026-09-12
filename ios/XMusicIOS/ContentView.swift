import SwiftUI

struct ContentView: View {
    @EnvironmentObject var audio: AudioEngine
    @State private var tab = 0
    let tabs = ["Music", "EQ", "Crossover", "Settings"]

    var body: some View {
        ZStack {
            Color(.sRGB, white: 0.035, opacity: 1).ignoresSafeArea()
            Group {
                switch tab { case 0: MusicView(); case 1: EQView(); case 2: CrossoverView(); default: SettingsView() }
            }
        }
        .preferredColorScheme(.dark)
        .safeAreaInset(edge: .bottom) {
            HStack { ForEach(0..<tabs.count, id: \.self) { i in
                Button { tab = i } label: { VStack(spacing: 4) { Image(systemName: ["music.note","slider.vertical.3","waveform.path.ecg","gearshape"][i]); Text(tabs[i]).font(.caption2) } .frame(maxWidth: .infinity) }
                .foregroundStyle(tab == i ? Color.cyan : .secondary)
            }}.padding(.vertical, 10).background(.ultraThinMaterial)
        }
    }
}

struct MusicView: View {
    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Text("XMusic").font(.largeTitle.bold())
            Text("Your Music. Your Sound. • Free • No Ads").foregroundStyle(.secondary)
            RoundedRectangle(cornerRadius: 22).fill(Color.white.opacity(0.06)).frame(height: 180).overlay(VStack(spacing: 12){ Image(systemName:"music.note.list").font(.system(size:48)); Text("Music Library"); Text("Pilih file musik dari Files untuk mulai memutar.").font(.caption).foregroundStyle(.secondary) })
            Spacer()
        }.padding(20)
    }
}

struct EQView: View {
    @EnvironmentObject var audio: AudioEngine
    let labels = ["20","25","31","40","50","63","80","100","125","160","200","250","315","400","500","630","800","1k","1.25k","1.6k","2k","2.5k","3.15k","4k","5k","6.3k","8k","10k","12.5k","16k","20k"]
    var body: some View {
        VStack(alignment:.leading) {
            HStack { VStack(alignment:.leading){Text("31-Band EQ").font(.title.bold());Text("Real-time AVAudioEngine • ±12 dB").font(.caption).foregroundStyle(.secondary)};Spacer();Button("Reset"){audio.resetEQ()} }
            ScrollView(.horizontal) { HStack(alignment:.center, spacing: 6) { ForEach(audio.gains.indices, id:\.self) { i in VStack { Slider(value: Binding(get:{Double(audio.gains[i])}, set:{audio.setGain(i,Float($0))}), in:-12...12).frame(width:24,height:230).rotationEffect(.degrees(-90)); Text(labels[i]).font(.system(size:8)).frame(width:34) } } } .padding(.vertical, 30) }
            Spacer()
        }.padding(18)
    }
}

struct CrossoverView: View {
    @State private var enabled = false
    @State private var low = 80.0
    @State private var high = 2500.0
    @State private var slope = 24.0
    var body: some View {
        Form { Section("Crossover") { Toggle("Enable 3-band processing", isOn:$enabled); Text("Low / Mid: \(Int(low)) Hz"); Slider(value:$low,in:40...500); Text("Mid / High: \(Int(high)) Hz"); Slider(value:$high,in:500...10000); Text("Slope: \(Int(slope)) dB/oct"); Picker("Slope",selection:$slope){ForEach([12.0,24,36,48],id:\.self){Text("\(Int($0)) dB/oct").tag($0)}} } Section("Routing"){Text("Output remains stereo. Separate physical LOW/MID/HIGH outputs depend on the audio route/device.").foregroundStyle(.secondary)} }.scrollContentBackground(.hidden).background(Color(.sRGB,white:0.035))
    }
}

struct SettingsView: View { var body: some View { Form { Section("Audio"){Text("Output Device — System");Text("Background Playback — Enabled")} Section("Theme"){Text("Custom themes can be enabled for Supporters.")} Section("About"){Text("XMusic 2.0.0");Text("Free • No Ads")} }.scrollContentBackground(.hidden).background(Color(.sRGB,white:0.035)) } }
