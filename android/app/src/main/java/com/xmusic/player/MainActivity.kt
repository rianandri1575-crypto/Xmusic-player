package com.xmusic.player

import android.content.ComponentName
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.xmusic.player.audio.AnalyzerState
import com.xmusic.player.audio.CrossoverState
import com.xmusic.player.audio.EqualizerState
import com.xmusic.player.data.Prefs
import com.xmusic.player.data.YouTubeRepository
import com.xmusic.player.player.XMusicPlaybackService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {
    private var controller by mutableStateOf<MediaController?>(null)
    private var controllerListener: androidx.media3.common.Player.Listener? = null
    private var nowPlayingTitle by mutableStateOf("Belum ada lagu")
    private var nowPlayingPlaying by mutableStateOf(false)
    private lateinit var prefs: Prefs
    private val repo = YouTubeRepository()
    private val executor = Executors.newSingleThreadExecutor()
    private var results by mutableStateOf<List<YouTubeRepository.Video>>(emptyList())
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf("")
    private var resolving by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        EqualizerState.setAll(prefs.loadEq())
        val token = SessionToken(this, ComponentName(this, XMusicPlaybackService::class.java))
        val future = MediaController.Builder(this, token).buildAsync()
        future.addListener({
            try {
                val c = future.get()
                controller = c
                val listener = object : androidx.media3.common.Player.Listener {
                    override fun onEvents(player: androidx.media3.common.Player, events: androidx.media3.common.Player.Events) {
                        nowPlayingTitle = player.currentMediaItem?.mediaMetadata?.title?.toString() ?: "Belum ada lagu"
                        nowPlayingPlaying = player.isPlaying
                    }
                }
                controllerListener = listener
                c.addListener(listener)
                nowPlayingTitle = c.currentMediaItem?.mediaMetadata?.title?.toString() ?: "Belum ada lagu"
                nowPlayingPlaying = c.isPlaying
            } catch (e: Exception) {
                controller = null
                message = "Player gagal disiapkan: ${e.message ?: "koneksi service gagal"}"
            }
        }, mainExecutor)
        setContent { XMusicApp() }
    }

    override fun onDestroy() {
        controller?.let { c -> controllerListener?.let(c::removeListener); c.release() }
        controller = null
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun search(q: String) {
        if (q.isBlank()) return
        busy = true; message = "Mencari di YouTube…"
        executor.execute {
            try {
                val data = repo.search(q)
                runOnUiThread { results = data; busy = false; message = "${data.size} hasil" }
            } catch (e: Exception) {
                runOnUiThread { busy = false; message = "Pencarian gagal: ${e.message ?: "server tidak tersedia"}" }
            }
        }
    }

    private fun play(video: YouTubeRepository.Video) {
        resolving = video.id; message = "Menyiapkan audio…"
        executor.execute {
            try {
                val stream = repo.resolveAudio(video.id)
                runOnUiThread {
                    val c = controller
                    if (c == null) { resolving = null; message = "Player belum siap"; return@runOnUiThread }
                    val item = MediaItem.Builder()
                        .setMediaId(video.id)
                        .setUri(stream.url)
                        .setMimeType(if (stream.mimeType.contains("webm")) MimeTypes.AUDIO_WEBM else stream.mimeType)
                        .setMediaMetadata(
                            MediaMetadata.Builder()
                                .setTitle(video.title)
                                .setArtist(video.uploader)
                                .setArtworkUri(video.thumbnail.takeIf { it.isNotBlank() }?.let(android.net.Uri::parse))
                                .build()
                        ).build()
                    c.setMediaItem(item)
                    c.prepare()
                    c.play()
                    prefs.addHistory(video)
                    nowPlayingTitle = video.title
                    nowPlayingPlaying = true
                    resolving = null
                    message = "Memutar"
                }
            } catch (e: Exception) {
                runOnUiThread { resolving = null; message = "Audio gagal dimuat: ${e.message ?: "resolver error"}" }
            }
        }
    }

    @Composable
    private fun XMusicApp() {
        var tab by remember { mutableIntStateOf(0) }
        var query by remember { mutableStateOf("") }
        val accent = Color(prefs.accent.toULong())
        val nav = listOf("YouTube", "Library", "EQ", "Crossover", "Analyzer", "Settings")
        MaterialTheme(colorScheme = darkColorScheme(primary = accent, background = Color(0xFF07080B), surface = Color(0xFF11131A))) {
            Scaffold(
                containerColor = Color(0xFF07080B),
                bottomBar = { NavigationBar(containerColor = Color(0xFF0E1016)) {
                    nav.forEachIndexed { i, label ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            icon = { Icon(when (i) { 0 -> Icons.Default.Search; 1 -> Icons.Default.Favorite; 2 -> Icons.Default.Equalizer; 3 -> Icons.Default.Tune; 4 -> Icons.Default.GraphicEq; else -> Icons.Default.Settings }, null) },
                            label = { Text(label) }
                        )
                    }
                }}
            ) { padding ->
                when (tab) {
                    0 -> YouTubePage(Modifier.padding(padding), query, { query = it }, { search(query) })
                    1 -> LibraryPage(Modifier.padding(padding))
                    2 -> EqPage(Modifier.padding(padding))
                    3 -> CrossoverPage(Modifier.padding(padding))
                    4 -> AnalyzerPage(Modifier.padding(padding))
                    else -> SettingsPage(Modifier.padding(padding))
                }
            }
        }
    }

    @Composable private fun Header(title: String, subtitle: String) {
        Text(title, style = MaterialTheme.typography.headlineLarge)
        Text(subtitle, color = Color(0xFF9AA0AD))
        Spacer(Modifier.height(16.dp))
    }

    @Composable private fun YouTubePage(modifier: Modifier, query: String, onQuery: (String) -> Unit, onSearch: () -> Unit) {
        Column(modifier.fillMaxSize().padding(18.dp)) {
            Header("XMusic", "YouTube Music • No Ads • Background Player")
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = query,
                    onValueChange = onQuery,
                    modifier = Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Cari musik di YouTube…") },
                    leadingIcon = { Icon(Icons.Default.Search, null) }
                )
                Spacer(Modifier.width(8.dp))
                FilledIconButton(onClick = onSearch, enabled = query.isNotBlank() && !busy) { Icon(Icons.Default.Search, "Cari") }
            }
            Spacer(Modifier.height(10.dp))
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotBlank()) Text(message, color = Color(0xFF9AA0AD), modifier = Modifier.padding(vertical = 7.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(results, key = { it.id }) { video ->
                    Card(
                        Modifier.fillMaxWidth().clickable { play(video) },
                        colors = CardDefaults.cardColors(containerColor = Color(0xFF12151C))
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(58.dp).background(Color(0xFF202633), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
                                Icon(Icons.Default.MusicNote, null)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(video.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(video.uploader, color = Color(0xFF9AA0AD), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            if (resolving == video.id) CircularProgressIndicator(Modifier.size(25.dp), strokeWidth = 2.dp)
                            else {
                                IconButton(onClick = { prefs.toggleFavorite(video) }) {
                                    Icon(if (prefs.isFavorite(video.id)) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favorit")
                                }
                                IconButton(onClick = { play(video) }) { Icon(Icons.Default.PlayArrow, "Putar") }
                            }
                        }
                    }
                }
            }
            NowPlaying()
        }
    }

    @Composable private fun NowPlaying() {
        val c = controller
        val title = nowPlayingTitle
        val playing = nowPlayingPlaying
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF151922))) {
            Row(Modifier.padding(8.dp).navigationBarsPadding(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { c?.seekToPreviousMediaItem() }, enabled = c != null) { Icon(Icons.Default.SkipPrevious, "Sebelumnya") }
                FilledIconButton(onClick = { if (playing) c?.pause() else c?.play() }, enabled = c != null) { Icon(if (playing) Icons.Default.Pause else Icons.Default.PlayArrow, "Putar") }
                IconButton(onClick = { c?.seekToNextMediaItem() }, enabled = c != null) { Icon(Icons.Default.SkipNext, "Berikutnya") }
                Text(title, Modifier.weight(1f).padding(horizontal = 8.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }

    @Composable private fun LibraryPage(modifier: Modifier) {
        var mode by remember { mutableIntStateOf(0) }
        var items by remember { mutableStateOf(prefs.favorites()) }
        Column(modifier.fillMaxSize().padding(18.dp)) {
            Header("Library", "Favorit dan riwayat pemutaran")
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == 0, onClick = { mode = 0; items = prefs.favorites() }, label = { Text("Favorit") })
                FilterChip(selected = mode == 1, onClick = { mode = 1; items = prefs.history() }, label = { Text("Riwayat") })
            }
            Spacer(Modifier.height(10.dp))
            if (items.isEmpty()) Text(if (mode == 0) "Belum ada favorit." else "Belum ada riwayat.", color = Color(0xFF9AA0AD))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { it.id }) { video ->
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = Color(0xFF12151C))) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.MusicNote, null)
                            Spacer(Modifier.width(10.dp))
                            Column(Modifier.weight(1f)) {
                                Text(video.title, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(video.uploader, color = Color(0xFF9AA0AD), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            IconButton(onClick = { play(video) }) { Icon(Icons.Default.PlayArrow, "Putar") }
                        }
                    }
                }
            }
            NowPlaying()
        }
    }

    @Composable private fun EqPage(modifier: Modifier) {
        val freqs = remember { listOf(20,25,31,40,50,63,80,100,125,160,200,250,315,400,500,630,800,1000,1250,1600,2000,2500,3150,4000,5000,6300,8000,10000,12500,16000,20000) }
        val gains by EqualizerState.gains.collectAsState()
        Column(modifier.fillMaxSize().padding(18.dp)) {
            Header("31-Band EQ", "Real-time DSP")
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Flat","Bass","Vocal","Treble").forEach { preset -> AssistChip({ applyPreset(preset) }, label={Text(preset)}) } }
            LazyColumn(Modifier.weight(1f)) { items(freqs.size) { i ->
                Row(Modifier.fillMaxWidth().padding(vertical=1.dp), verticalAlignment=Alignment.CenterVertically) {
                    Text(text = freqLabel(freqs[i].toDouble()), modifier = Modifier.width(50.dp))
                    Slider(
                        value = gains[i],
                        onValueChange = { value ->
                            EqualizerState.set(i, value)
                            prefs.saveEq(EqualizerState.gains.value)
                        },
                        valueRange = -12f..12f,
                        modifier = Modifier.weight(1f)
                    )
                    Text(text = String.format("%+.0f", gains[i]), modifier = Modifier.width(38.dp))
                }
            }}
        }
    }

    private fun applyPreset(name: String) {
        val a = FloatArray(31)
        when(name){ "Bass" -> { a[0]=6f;a[1]=5f;a[2]=4f;a[3]=3f;a[4]=2f }; "Vocal" -> { a[13]=2f;a[14]=3f;a[15]=3f;a[16]=2f;a[17]=2f }; "Treble" -> { a[23]=2f;a[24]=3f;a[25]=4f;a[26]=5f;a[27]=6f;a[28]=5f } }
        EqualizerState.setAll(a); prefs.saveEq(a)
    }

    @Composable private fun CrossoverPage(modifier: Modifier) {
        val current by CrossoverState.config.collectAsState()
        Column(modifier.fillMaxSize().padding(18.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Header("Crossover", "Real-time 3-band PCM split/recombine")
                }
                Switch(
                    checked = current.enabled,
                    onCheckedChange = { enabled ->
                        CrossoverState.update(current.copy(enabled = enabled))
                    }
                )
            }
            Text(text = "Low / Mid: ${current.lowHz.toInt()} Hz")
            Slider(
                value = current.lowHz,
                onValueChange = { value ->
                    CrossoverState.update(
                        current.copy(lowHz = value.coerceAtMost(current.highHz - 50f))
                    )
                },
                valueRange = 40f..500f
            )
            Text(text = "Mid / High: ${current.highHz.toInt()} Hz")
            Slider(
                value = current.highHz,
                onValueChange = { value ->
                    CrossoverState.update(
                        current.copy(highHz = value.coerceAtLeast(current.lowHz + 50f))
                    )
                },
                valueRange = 500f..10000f
            )
            Text("Slope: ${current.slopeDb} dB/oct"); Row(horizontalArrangement=Arrangement.spacedBy(6.dp)){ listOf(12,24,36,48).forEach{ FilterChip(current.slopeDb==it,{CrossoverState.update(current.copy(slopeDb=it))},{Text("$it")}) } }
        }
    }

    @Composable private fun AnalyzerPage(modifier: Modifier) {
        val bins by AnalyzerState.levels.collectAsState(); val l by AnalyzerState.peakL.collectAsState(); val r by AnalyzerState.peakR.collectAsState()
        Column(modifier.fillMaxSize().padding(18.dp)) {
            Header("Audio Analyzer","Real-time signal level")
            Row(Modifier.fillMaxWidth().height(220.dp), verticalAlignment=Alignment.Bottom, horizontalArrangement=Arrangement.spacedBy(3.dp)) { bins.forEach { v -> Box(Modifier.weight(1f).fillMaxHeight(v.coerceIn(0f,1f)).background(MaterialTheme.colorScheme.primary, RoundedCornerShape(3.dp))) } }
            Spacer(Modifier.height(15.dp)); Text("L ${String.format("%.1f",l)} dB     R ${String.format("%.1f",r)} dB")
        }
    }

    @Composable private fun SettingsPage(modifier: Modifier) {
        Column(modifier.fillMaxSize().padding(18.dp)) {
            Header("Settings","XMusic")
            Text("Playback", style=MaterialTheme.typography.titleLarge)
            Text("YouTube audio • MediaSession • Background playback", color=Color(0xFF9AA0AD))
            Spacer(Modifier.height(18.dp)); Text("No Ads", style=MaterialTheme.typography.titleLarge)
            Text("XMusic tidak menambahkan iklan ke antarmuka aplikasi.", color=Color(0xFF9AA0AD))
            Spacer(Modifier.height(18.dp)); Text("DSP", style=MaterialTheme.typography.titleLarge)
            Text("31-band EQ + crossover dipasang langsung pada AudioSink player.", color=Color(0xFF9AA0AD))
        }
    }

    private fun freqLabel(hz:Double)=if(hz>=1000){val k=hz/1000; if(k%1==0.0)"${k.toInt()}k" else "${k}k"}else hz.toInt().toString()
}
