package com.xmusic.player

import android.Manifest
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
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

private val XBlack = Color(0xFF07080C)
private val XSurface = Color(0xFF11141B)
private val XSurface2 = Color(0xFF171B24)
private val XMuted = Color(0xFF9AA2B2)
private val XAccent = Color(0xFF00D8FF)

class MainActivity : ComponentActivity() {
    private var controller by mutableStateOf<MediaController?>(null)
    private var controllerListener: androidx.media3.common.Player.Listener? = null
    private var controllerPending = false
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private var nowPlayingTitle by mutableStateOf("Belum ada lagu")
    private var nowPlayingArtist by mutableStateOf("")
    private var nowPlayingPlaying by mutableStateOf(false)
    private lateinit var prefs: Prefs
    private val repo = YouTubeRepository()
    private val executor = Executors.newSingleThreadExecutor()
    private var results by mutableStateOf<List<YouTubeRepository.Video>>(emptyList())
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf("")
    private var error by mutableStateOf<String?>(null)
    private var resolving by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = Prefs(this)
        // Android 13+ (API 33): media notification requires a runtime permission.
        if (Build.VERSION.SDK_INT >= 33) {
            Handler(Looper.getMainLooper()).post {
                if (!isFinishing && !isDestroyed) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            }
        }
        setContent { XMusicApp() }
    }

    override fun onDestroy() {
        controllerPending = false
        controller?.let { c -> controllerListener?.let(c::removeListener); c.release() }
        controller = null
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun search(q: String) {
        if (q.isBlank() || busy) return
        busy = true
        error = null
        message = ""
        executor.execute {
            try {
                val data = repo.search(q)
                runOnUiThread {
                    results = data
                    busy = false
                    message = if (data.isEmpty()) "Tidak ada lagu yang ditemukan" else "${data.size} lagu ditemukan"
                }
            } catch (_: Exception) {
                runOnUiThread {
                    busy = false
                    results = emptyList()
                    message = ""
                    error = "Tidak dapat terhubung ke server musik. Coba lagi sebentar."
                }
            }
        }
    }

    private fun ensureController(onReady: (MediaController) -> Unit) {
        controller?.let(onReady) ?: run {
            // Guard against duplicate async builds (double-tap on play).
            if (controllerPending) return
            controllerPending = true
            val token = SessionToken(this, ComponentName(this, XMusicPlaybackService::class.java))
            val future = MediaController.Builder(this, token).buildAsync()
            future.addListener({
                controllerPending = false
                if (isDestroyed) return@addListener
                try {
                    val c = future.get()
                    controller = c
                    val listener = object : androidx.media3.common.Player.Listener {
                        override fun onEvents(player: androidx.media3.common.Player, events: androidx.media3.common.Player.Events) {
                            nowPlayingTitle = player.currentMediaItem?.mediaMetadata?.title?.toString() ?: "Belum ada lagu"
                            nowPlayingArtist = player.currentMediaItem?.mediaMetadata?.artist?.toString() ?: ""
                            nowPlayingPlaying = player.isPlaying
                        }
                    }
                    controllerListener = listener
                    c.addListener(listener)
                    nowPlayingTitle = c.currentMediaItem?.mediaMetadata?.title?.toString() ?: "Belum ada lagu"
                    nowPlayingArtist = c.currentMediaItem?.mediaMetadata?.artist?.toString() ?: ""
                    nowPlayingPlaying = c.isPlaying
                    onReady(c)
                } catch (_: Exception) {
                    controller = null
                    resolving = null
                    error = "Player belum siap. Coba putar lagi."
                }
            }, ContextCompat.getMainExecutor(this))
        }
    }

    private fun play(video: YouTubeRepository.Video) {
        resolving = video.id
        error = null
        executor.execute {
            try {
                val stream = repo.resolveAudio(video.id)
                runOnUiThread {
                    ensureController { c ->
                        val item = MediaItem.Builder()
                            .setMediaId(video.id)
                            .setUri(stream.url)
                            .setMimeType(if (stream.mimeType.contains("webm")) MimeTypes.AUDIO_WEBM else stream.mimeType)
                            .setMediaMetadata(MediaMetadata.Builder()
                                .setTitle(video.title)
                                .setArtist(video.uploader)
                                .setArtworkUri(video.thumbnail.takeIf { it.isNotBlank() }?.let(android.net.Uri::parse))
                                .build())
                            .build()
                        c.setMediaItem(item)
                        c.prepare()
                        c.play()
                        prefs.addHistory(video)
                        nowPlayingTitle = video.title
                        nowPlayingArtist = video.uploader
                        nowPlayingPlaying = true
                        resolving = null
                    }
                }
            } catch (_: Exception) {
                runOnUiThread {
                    resolving = null
                    error = "Audio lagu ini tidak tersedia. Coba lagu lain."
                }
            }
        }
    }

    @Composable
    private fun XMusicApp() {
        var tab by remember { mutableIntStateOf(0) }
        var query by remember { mutableStateOf("") }
        val nav = listOf("Home", "Library", "Audio", "Analyzer", "Settings")
        MaterialTheme(colorScheme = darkColorScheme(
            primary = XAccent,
            onPrimary = Color.Black,
            background = XBlack,
            surface = XSurface,
            surfaceVariant = XSurface2,
            onSurface = Color(0xFFE8EBF2),
            onSurfaceVariant = XMuted
        )) {
            Scaffold(
                containerColor = XBlack,
                bottomBar = {
                    Column {
                        MiniPlayer()
                        NavigationBar(containerColor = Color(0xFF0C0E13), tonalElevation = 0.dp) {
                            nav.forEachIndexed { i, label ->
                                NavigationBarItem(
                                    selected = tab == i,
                                    onClick = { tab = i },
                                    icon = { Icon(navIcon(i), label) },
                                    label = { Text(label) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = XAccent,
                                        selectedTextColor = XAccent,
                                        indicatorColor = Color(0xFF17313A),
                                        unselectedIconColor = XMuted,
                                        unselectedTextColor = XMuted
                                    )
                                )
                            }
                        }
                    }
                }
            ) { padding ->
                when (tab) {
                    0 -> HomePage(Modifier.padding(padding), query, { query = it }, { search(query) })
                    1 -> LibraryPage(Modifier.padding(padding))
                    2 -> AudioPage(Modifier.padding(padding))
                    3 -> AnalyzerPage(Modifier.padding(padding))
                    else -> SettingsPage(Modifier.padding(padding))
                }
            }
        }
    }

    private fun navIcon(i: Int) = when (i) {
        0 -> Icons.Default.Home
        1 -> Icons.Default.Favorite
        2 -> Icons.Default.Tune
        3 -> Icons.Default.GraphicEq
        else -> Icons.Default.Settings
    }

    @Composable private fun Header(title: String, subtitle: String? = null) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
                subtitle?.let { Text(it, color = XMuted, style = MaterialTheme.typography.bodyMedium) }
            }
            Box(Modifier.size(42.dp).clip(CircleShape).background(Brush.linearGradient(listOf(XAccent, Color(0xFF6C5CE7)))), contentAlignment = Alignment.Center) {
                Text("X", fontWeight = FontWeight.Black, color = Color.Black, style = MaterialTheme.typography.titleLarge)
            }
        }
    }

    @Composable private fun HomePage(modifier: Modifier, query: String, onQuery: (String) -> Unit, onSearch: () -> Unit) {
        Column(modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp)) {
            Header("XMusic", "Your music, your way")
            Spacer(Modifier.height(20.dp))
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(18.dp),
                placeholder = { Text("Cari lagu, artis, atau album") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (query.isNotBlank()) IconButton(onClick = onSearch, enabled = !busy) {
                        Icon(Icons.Default.ArrowForward, "Cari")
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = XAccent, unfocusedBorderColor = Color(0xFF2B303B))
            )
            Spacer(Modifier.height(14.dp))
            error?.let { ErrorCard(it) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = XAccent)
            if (message.isNotBlank()) Text(message, color = XMuted, modifier = Modifier.padding(vertical = 8.dp))
            if (results.isEmpty() && !busy && error == null) {
                WelcomeCard()
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
                items(results, key = { it.id }) { video -> SongCard(video) }
            }
        }
    }

    @Composable private fun WelcomeCard() {
        Card(colors = CardDefaults.cardColors(containerColor = XSurface), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(20.dp)) {
                Text("Temukan musik favoritmu", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Text("Cari lagu dari YouTube dan nikmati pemutaran di background.", color = XMuted)
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { search("Alan Walker") }, label = { Text("Popular") })
                    AssistChip(onClick = { search("lofi") }, label = { Text("Lofi") })
                    AssistChip(onClick = { search("DJ Wukong") }, label = { Text("DJ") })
                }
            }
        }
    }

    @Composable private fun ErrorCard(text: String) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF24171A)), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudOff, null, tint = Color(0xFFFF8A8A))
                Spacer(Modifier.width(10.dp))
                Text(text, color = Color(0xFFFFC4C4), modifier = Modifier.weight(1f))
            }
        }
    }

    @Composable private fun SongCard(video: YouTubeRepository.Video) {
        Card(
            Modifier.fillMaxWidth().clickable { play(video) },
            colors = CardDefaults.cardColors(containerColor = XSurface),
            shape = RoundedCornerShape(18.dp)
        ) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(62.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Color(0xFF1A5868), Color(0xFF25213C)))), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.MusicNote, null, tint = XAccent, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(video.title, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Text(video.uploader, color = XMuted, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (resolving == video.id) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = XAccent)
                } else {
                    IconButton(onClick = { prefs.toggleFavorite(video) }) {
                        Icon(if (prefs.isFavorite(video.id)) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favorit", tint = if (prefs.isFavorite(video.id)) XAccent else XMuted)
                    }
                    IconButton(onClick = { play(video) }) { Icon(Icons.Default.PlayArrow, "Putar") }
                }
            }
        }
    }

    @Composable private fun MiniPlayer() {
        val c = controller
        if (nowPlayingTitle == "Belum ada lagu") return
        Surface(color = XSurface2, tonalElevation = 0.dp) {
            Row(Modifier.fillMaxWidth().height(68.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(Brush.linearGradient(listOf(XAccent, Color(0xFF6C5CE7)))), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.MusicNote, null, tint = Color.Black)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(nowPlayingTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
                    if (nowPlayingArtist.isNotBlank()) Text(nowPlayingArtist, color = XMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { if (nowPlayingPlaying) c?.pause() else c?.play() }, enabled = c != null) {
                    Icon(if (nowPlayingPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Putar")
                }
                IconButton(onClick = { c?.seekToNextMediaItem() }, enabled = c != null) { Icon(Icons.Default.SkipNext, "Berikutnya") }
            }
        }
    }

    @Composable private fun LibraryPage(modifier: Modifier) {
        var mode by remember { mutableIntStateOf(0) }
        var list by remember { mutableStateOf(prefs.favorites()) }
        Column(modifier.fillMaxSize().padding(18.dp)) {
            Header("Library", "Koleksi musikmu")
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == 0, onClick = { mode = 0; list = prefs.favorites() }, label = { Text("Favorit") }, leadingIcon = { Icon(Icons.Default.Favorite, null) })
                FilterChip(selected = mode == 1, onClick = { mode = 1; list = prefs.history() }, label = { Text("Riwayat") }, leadingIcon = { Icon(Icons.Default.History, null) })
            }
            Spacer(Modifier.height(12.dp))
            if (list.isEmpty()) Text(if (mode == 0) "Belum ada favorit." else "Belum ada riwayat.", color = XMuted)
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(list, key = { it.id }) { video -> SongCard(video) }
            }
        }
    }

    @Composable private fun AudioPage(modifier: Modifier) {
        var section by remember { mutableIntStateOf(0) }
        Column(modifier.fillMaxSize().padding(18.dp)) {
            Header("Audio", "Kontrol suara XMusic")
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = section == 0, onClick = { section = 0 }, label = { Text("EQ") })
                FilterChip(selected = section == 1, onClick = { section = 1 }, label = { Text("Crossover") })
            }
            Spacer(Modifier.height(8.dp))
            if (section == 0) EqContent(Modifier.weight(1f)) else CrossoverContent(Modifier.weight(1f))
        }
    }

    @Composable private fun EqContent(modifier: Modifier) {
        LaunchedEffect(Unit) { runCatching { EqualizerState.setAll(prefs.loadEq()) } }
        val freqs = remember { listOf(20,25,31,40,50,63,80,100,125,160,200,250,315,400,500,630,800,1000,1250,1600,2000,2500,3150,4000,5000,6300,8000,10000,12500,16000,20000) }
        val gains by EqualizerState.gains.collectAsState()
        Column(modifier) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf("Flat","Bass","Vocal","Treble").forEach { preset -> AssistChip(onClick = { applyPreset(preset) }, label = { Text(preset) }) } }
            Spacer(Modifier.height(6.dp))
            LazyColumn { items(freqs.size) { i ->
                Row(Modifier.fillMaxWidth().padding(vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(freqLabel(freqs[i].toDouble()), Modifier.width(48.dp), color = XMuted)
                    Slider(value = gains[i], onValueChange = { EqualizerState.set(i, it); prefs.saveEq(EqualizerState.gains.value) }, valueRange = -12f..12f, modifier = Modifier.weight(1f))
                    Text(String.format("%+.0f", gains[i]), Modifier.width(38.dp))
                }
            } }
        }
    }

    private fun applyPreset(name: String) {
        val a = FloatArray(31)
        when (name) {
            "Bass" -> { a[0]=6f; a[1]=5f; a[2]=4f; a[3]=3f; a[4]=2f }
            "Vocal" -> { a[13]=2f; a[14]=3f; a[15]=3f; a[16]=2f; a[17]=2f }
            "Treble" -> { a[23]=2f; a[24]=3f; a[25]=4f; a[26]=5f; a[27]=6f; a[28]=5f }
        }
        EqualizerState.setAll(a); prefs.saveEq(a)
    }

    @Composable private fun CrossoverContent(modifier: Modifier) {
        val current by CrossoverState.config.collectAsState()
        Column(modifier) {
            Card(colors = CardDefaults.cardColors(containerColor = XSurface), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("3-band crossover", fontWeight = FontWeight.SemiBold)
                            Text("Low / Mid / High", color = XMuted)
                        }
                        Switch(checked = current.enabled, onCheckedChange = { CrossoverState.update(current.copy(enabled = it)) })
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("Low / Mid: ${current.lowHz.toInt()} Hz")
                    Slider(value = current.lowHz, onValueChange = { CrossoverState.update(current.copy(lowHz = it.coerceAtMost(current.highHz - 50f))) }, valueRange = 40f..500f)
                    Text("Mid / High: ${current.highHz.toInt()} Hz")
                    Slider(value = current.highHz, onValueChange = { CrossoverState.update(current.copy(highHz = it.coerceAtLeast(current.lowHz + 50f))) }, valueRange = 500f..10000f)
                    Text("Slope: ${current.slopeDb} dB/oct", color = XMuted)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { listOf(12,24,36,48).forEach { FilterChip(selected = current.slopeDb == it, onClick = { CrossoverState.update(current.copy(slopeDb = it)) }, label = { Text("$it") }) } }
                }
            }
        }
    }

    @Composable private fun AnalyzerPage(modifier: Modifier) {
        val bins by AnalyzerState.levels.collectAsState(); val l by AnalyzerState.peakL.collectAsState(); val r by AnalyzerState.peakR.collectAsState()
        Column(modifier.fillMaxSize().padding(18.dp)) {
            Header("Analyzer", "Real-time signal level")
            Spacer(Modifier.height(18.dp))
            Card(colors = CardDefaults.cardColors(containerColor = XSurface), shape = RoundedCornerShape(22.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth().height(220.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        bins.forEach { v -> Box(Modifier.weight(1f).fillMaxHeight(v.coerceIn(0f,1f)).background(XAccent, RoundedCornerShape(3.dp))) }
                    }
                    Spacer(Modifier.height(16.dp))
                    Text("L  ${String.format("%.1f", l)} dB", color = XMuted)
                    Text("R  ${String.format("%.1f", r)} dB", color = XMuted)
                }
            }
        }
    }

    @Composable private fun SettingsPage(modifier: Modifier) {
        Column(modifier.fillMaxSize().padding(18.dp)) {
            Header("Settings", "Preferensi XMusic")
            Spacer(Modifier.height(16.dp))
            SettingsCard(Icons.Default.Headphones, "Playback", "YouTube audio • background playback • MediaSession")
            SettingsCard(Icons.Default.Block, "No Ads", "XMusic tidak menambahkan iklan ke antarmuka aplikasi")
            SettingsCard(Icons.Default.Tune, "Audio", "31-band EQ dan crossover tersedia di menu Audio")
            SettingsCard(Icons.Default.Security, "Privasi", "XMusic tidak membutuhkan akun untuk pencarian dasar")
            Spacer(Modifier.height(10.dp))
            Text("XMusic 2.2", color = XMuted, style = MaterialTheme.typography.bodySmall)
        }
    }

    @Composable private fun SettingsCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, body: String) {
        Card(colors = CardDefaults.cardColors(containerColor = XSurface), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = XAccent)
                Spacer(Modifier.width(14.dp))
                Column { Text(title, fontWeight = FontWeight.SemiBold); Text(body, color = XMuted, style = MaterialTheme.typography.bodySmall) }
            }
        }
    }

    private fun freqLabel(hz: Double) = if (hz >= 1000) { val k = hz / 1000; if (k % 1 == 0.0) "${k.toInt()}k" else "${k}k" } else hz.toInt().toString()
}
