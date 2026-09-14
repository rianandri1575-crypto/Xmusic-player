package com.xmusic.player

import android.Manifest
import android.content.ComponentName
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.xmusic.player.audio.AnalyzerState
import com.xmusic.player.audio.CrossoverConfig
import com.xmusic.player.audio.CrossoverState
import com.xmusic.player.audio.EqualizerState
import com.xmusic.player.data.Prefs
import com.xmusic.player.data.YouTubeRepository
import com.xmusic.player.player.XMusicPlaybackService
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

// ── Palette "Obsidian & Gold" ───────────────────────────────
private val XBg = Color(0xFF0A0B0E)
private val XPanel = Color(0xFF12141A)
private val XPanelHi = Color(0xFF1A1D26)
private val XLine = Color(0xFF272B36)
private val XGold = Color(0xFFD6B26B)
private val XGoldDim = Color(0xFF8A7140)
private val XText = Color(0xFFEDEBE4)
private val XMuted = Color(0xFF8E93A0)
private val XDanger = Color(0xFFC97862)

class MainActivity : ComponentActivity() {
    private var controller by mutableStateOf<MediaController?>(null)
    private var controllerListener: androidx.media3.common.Player.Listener? = null
    private var controllerPending = false
    private var resolving by mutableStateOf<String?>(null)
    private var favTick by mutableStateOf(0)
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }
    private val localAudioPicker =
        registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
            if (uris.isNotEmpty()) playLocalUris(uris)
        }
    private var nowPlayingTitle by mutableStateOf("Belum ada lagu")
    private var nowPlayingArtist by mutableStateOf("")
    private var nowPlayingPlaying by mutableStateOf(false)
    private var nowPlayingBitmap by mutableStateOf<Bitmap?>(null)
    private lateinit var prefs: Prefs
    private val repo = YouTubeRepository()
    private val executor = Executors.newSingleThreadExecutor()
    private var results by mutableStateOf<List<YouTubeRepository.Video>>(emptyList())
    private var busy by mutableStateOf(false)
    private var message by mutableStateOf("")
    private var error by mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        prefs = Prefs(this)
        // Restore persisted DSP state before any audio flows.
        EqualizerState.setAll(prefs.loadEq())
        CrossoverState.update(prefs.loadCrossover())
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
                            val meta = player.currentMediaItem?.mediaMetadata
                            nowPlayingTitle = meta?.title?.toString() ?: "Belum ada lagu"
                            nowPlayingArtist = meta?.artist?.toString() ?: ""
                            nowPlayingPlaying = player.isPlaying
                        }
                        override fun onPlayerError(err: PlaybackException) {
                            error = "Gagal memutar audio ini. Coba lagu lain."
                        }
                    }
                    controllerListener = listener
                    c.addListener(listener)
                    val meta = c.currentMediaItem?.mediaMetadata
                    nowPlayingTitle = meta?.title?.toString() ?: "Belum ada lagu"
                    nowPlayingArtist = meta?.artist?.toString() ?: ""
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
    private fun playLocalUris(uris: List<android.net.Uri>) {
        resolveControllerOrPrepare {
            // Play first picked file for simplicity; queue could be extended.
            val first = uris.first()
            val item = MediaItem.Builder()
                .setMediaId(first.toString())
                .setUri(first)
                .setMediaMetadata(MediaMetadata.Builder()
                    .setTitle("Local: " + (first.lastPathSegment ?: "File"))
                    .build())
                .build()
            controller?.setMediaItem(item)
            controller?.prepare()
            controller?.play()
            nowPlayingTitle = "Local: ${first.lastPathSegment ?: "File"}"
            nowPlayingArtist = "Perangkat"
            nowPlayingPlaying = true
        }
    }

    private fun resolveControllerOrPrepare(onReady: (MediaController) -> Unit) {
        controller?.let(onReady) ?: run {
            ensureController(onReady)
        }
    }

    private fun play(video: YouTubeRepository.Video) {
        resolving = video.id
        error = null
        loadArtwork(video.thumbnail)
        executor.execute {
            try {
                val stream = repo.resolveAudio(video.id)
                runOnUiThread {
                    ensureController { c ->
                        val mime = normalizePlayMime(stream.mimeType)
                        if (mime == null) {
                            resolving = null
                            error = "Format audio tidak didukung. Coba lagu lain."
                            return@ensureController
                        }
                        val item = MediaItem.Builder()
                            .setMediaId(video.id)
                            .setUri(stream.url)
                            .setMimeType(mime)
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
                        nowPlayingBitmap = null
                        loadArtwork(video.thumbnail)
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

    private fun normalizePlayMime(raw: String): String? = when {
        raw.startsWith("audio/webm") -> MimeTypes.AUDIO_WEBM
        raw.startsWith("audio/mp4") -> MimeTypes.AUDIO_MP4
        raw.startsWith("audio/mpeg") || raw.startsWith("audio/mp3") -> MimeTypes.AUDIO_MPEG
        raw.startsWith("audio/ogg") || raw.startsWith("audio/opus") -> MimeTypes.AUDIO_OPUS
        raw.startsWith("audio/") -> MimeTypes.AUDIO_UNKNOWN
        raw.startsWith("video/mp4") -> MimeTypes.VIDEO_MP4
        raw.startsWith("video/webm") -> MimeTypes.VIDEO_WEBM
        raw == "application/x-mpegURL" || raw == "application/vnd.apple.mpegurl" -> MimeTypes.APPLICATION_M3U8
        raw.startsWith("application/dash") -> MimeTypes.APPLICATION_MPD
        else -> null
    }

    private fun loadArtwork(url: String?) {
        if (url.isNullOrBlank()) return
        executor.execute {
            val bmp = runCatching {
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 6000
                    readTimeout = 6000
                    instanceFollowRedirects = true
                }
                try {
                    if (c.responseCode !in 200..299) return@runCatching null
                    c.inputStream.use { BitmapFactory.decodeStream(it) }
                } finally { c.disconnect() }
            }.getOrNull()
            runOnUiThread { if (bmp != null) nowPlayingBitmap = bmp }
        }
    }
// ── Root UI ────────────────────────────────────────────────
    @Composable
    private fun XMusicApp() {
        var tab by remember { mutableIntStateOf(0) }
        var query by remember { mutableStateOf("") }
        val nav = listOf("Home", "Library", "Audio", "Analyzer", "Settings")
        MaterialTheme(colorScheme = darkColorScheme(
            primary = XGold,
            onPrimary = XBg,
            background = XBg,
            surface = XPanel,
            surfaceVariant = XPanelHi,
            onSurface = XText,
            onSurfaceVariant = XMuted,
            outline = XLine,
            secondary = XGoldDim,
            error = XDanger
        )) {
            Scaffold(
                containerColor = XBg,
                bottomBar = {
                    Column {
                        MiniPlayer()
                        HorizontalDivider(color = XLine)
                        NavigationBar(containerColor = Color(0xFF0B0C10), tonalElevation = 0.dp) {
                            nav.forEachIndexed { i, label ->
                                NavigationBarItem(
                                    selected = tab == i,
                                    onClick = { tab = i },
                                    icon = { Icon(navIcon(i), label) },
                                    label = { Text(label, maxLines = 1) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = XGold,
                                        selectedTextColor = XGold,
                                        indicatorColor = Color(0xFF1E2410),
                                        unselectedIconColor = XMuted,
                                        unselectedTextColor = XMuted
                                    )
                                )
                            }
                        }
                    }
                }
            ) { padding ->
                Box(Modifier.fillMaxSize().padding(padding)) {
                    when (tab) {
                        0 -> HomePage(Modifier.fillMaxSize(), query, { query = it }, { search(query) })
                        1 -> LibraryPage(Modifier.fillMaxSize())
                        2 -> AudioPage(Modifier.fillMaxSize())
                        3 -> AnalyzerPage(Modifier.fillMaxSize())
                        else -> SettingsPage(Modifier.fillMaxSize())
                    }
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
                Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold, color = XText)
                subtitle?.let { Text(it, color = XMuted, style = MaterialTheme.typography.bodyMedium) }
            }
            Box(Modifier.size(42.dp).clip(CircleShape).border(1.dp, XGoldDim, CircleShape), contentAlignment = Alignment.Center) {
                Text("X", fontWeight = FontWeight.Bold, color = XGold, style = MaterialTheme.typography.titleLarge)
            }
        }
    }

    @Composable private fun HomePage(modifier: Modifier, query: String, onQuery: (String) -> Unit, onSearch: () -> Unit) {
        Column(modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Header("XMusic", "Streaming musik online · gratis")
            Spacer(Modifier.height(18.dp))
            NowPlayingHero()
            OutlinedTextField(
                value = query,
                onValueChange = onQuery,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                placeholder = { Text("Cari lagu, artis, atau album", color = XMuted) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = XMuted) },
                trailingIcon = {
                    if (query.isNotBlank()) IconButton(onClick = onSearch, enabled = !busy) {
                        Icon(Icons.Default.ArrowForward, "Cari", tint = XGold)
                    }
                },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = XGold, unfocusedBorderColor = XLine)
            )
            Spacer(Modifier.height(14.dp))
            error?.let { ErrorCard(it) }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth(), color = XGold)
            if (message.isNotBlank()) Text(message, color = XMuted, modifier = Modifier.padding(vertical = 8.dp))
            if (results.isEmpty() && !busy && error == null) WelcomeCard()
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(top = 10.dp, bottom = 8.dp)) {
                items(results, key = { it.id }) { video -> SongCard(video) }
            }
        }
    }
    @Composable private fun WelcomeCard() {
        Card(colors = CardDefaults.cardColors(containerColor = XPanel), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(Modifier.size(64.dp).clip(CircleShape).border(1.dp, XGoldDim, CircleShape), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.MusicNote, null, tint = XGold, modifier = Modifier.size(30.dp))
                }
                Spacer(Modifier.height(14.dp))
                Text("Temukan musik favoritmu", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = XText)
                Spacer(Modifier.height(6.dp))
                Text("Streaming langsung dari YouTube tanpa iklan.", color = XMuted, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AssistChip(onClick = { search("Alan Walker") }, label = { Text("Popular") })
                    AssistChip(onClick = { search("lofi") }, label = { Text("Lofi") })
                    AssistChip(onClick = { search("DJ Wukong") }, label = { Text("DJ") })
                }
            }
        }
    }

    @Composable private fun ErrorCard(text: String) {
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF201417)), shape = RoundedCornerShape(14.dp), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.CloudOff, null, tint = XDanger)
                Spacer(Modifier.width(10.dp))
                Text(text, color = Color(0xFFE0A99B), modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
    // ── Now-playing hero ───────────────────────────────────
    @Composable private fun NowPlayingHero() {
        if (nowPlayingTitle == "Belum ada lagu") return
        val c = controller
        Card(
            colors = CardDefaults.cardColors(containerColor = XPanel),
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(nowPlayingBitmap, 64.dp, 16.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text("SEDANG DIPUTAR", color = XGoldDim, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(2.dp))
                    Text(nowPlayingTitle, color = XText, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    if (nowPlayingArtist.isNotBlank()) Text(nowPlayingArtist, color = XMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                IconButton(onClick = { if (nowPlayingPlaying) c?.pause() else c?.play() }, enabled = c != null) {
                    Icon(if (nowPlayingPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle, "Putar", tint = XGold, modifier = Modifier.size(40.dp))
                }
            }
        }
        Spacer(Modifier.height(14.dp))
    }

    @Composable private fun Artwork(bmp: Bitmap?, size: Dp, radius: Dp) {
        if (bmp != null) {
            Image(bmp.asImageBitmap(), null, modifier = Modifier.size(size).clip(RoundedCornerShape(radius)), contentScale = ContentScale.Crop)
        } else {
            Box(Modifier.size(size).clip(RoundedCornerShape(radius)).background(XPanelHi).border(1.dp, XLine, RoundedCornerShape(radius)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.MusicNote, null, tint = XGoldDim, modifier = Modifier.size(size * 0.45f))
            }
        }
    }

    @Composable private fun SongCard(video: YouTubeRepository.Video) {
        val tick = favTick
        val isFav = remember(tick) { prefs.isFavorite(video.id) }
        Card(
            Modifier.fillMaxWidth().clickable { play(video) },
            colors = CardDefaults.cardColors(containerColor = XPanel),
            shape = RoundedCornerShape(16.dp)
        ) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(null, 52.dp, 12.dp)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(video.title, color = XText, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(2.dp))
                    Text(video.uploader, color = XMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                }
                if (resolving == video.id) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = XGold)
                } else {
                    IconButton(onClick = { prefs.toggleFavorite(video); favTick++ }) {
                        Icon(if (isFav) Icons.Default.Favorite else Icons.Default.FavoriteBorder, "Favorit", tint = if (isFav) XGold else XMuted, modifier = Modifier.size(22.dp))
                    }
                    IconButton(onClick = { play(video) }) {
                        Icon(Icons.Default.PlayArrow, "Putar", tint = XText)
                    }
                }
            }
        }
    }

    @Composable private fun MiniPlayer() {
        val c = controller
        if (nowPlayingTitle == "Belum ada lagu") return
        Surface(color = XPanel, tonalElevation = 0.dp) {
            Column {
                Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Artwork(nowPlayingBitmap, 44.dp, 10.dp)
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(nowPlayingTitle, color = XText, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium, style = MaterialTheme.typography.bodyMedium)
                        if (nowPlayingArtist.isNotBlank()) Text(nowPlayingArtist, color = XMuted, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                    IconButton(onClick = { if (nowPlayingPlaying) c?.pause() else c?.play() }, enabled = c != null) {
                        Icon(if (nowPlayingPlaying) Icons.Default.Pause else Icons.Default.PlayArrow, "Putar", tint = XGold)
                    }
                    IconButton(onClick = { c?.seekToNextMediaItem() }, enabled = c != null) {
                        Icon(Icons.Default.SkipNext, "Berikutnya", tint = XMuted)
                    }
                }
                PlayerProgress()
            }
        }
    }

    @Composable private fun PlayerProgress() {
        val c = controller
        var pos by remember { mutableStateOf(0L) }
        var dur by remember { mutableStateOf(0L) }
        LaunchedEffect(c, nowPlayingPlaying) {
            while (true) {
                pos = c?.currentPosition ?: 0L
                dur = c?.duration?.takeIf { it > 0 } ?: 0L
                delay(500)
            }
        }
        val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        Box(Modifier.fillMaxWidth().height(2.dp).background(XLine)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(XGold))
        }
    }

    // ── Library ────────────────────────────────────────
    @Composable private fun LibraryPage(modifier: Modifier) {
        var mode by remember { mutableIntStateOf(0) }
        val tick = favTick
        val list = remember(mode, tick) { if (mode == 0) prefs.favorites() else prefs.history() }
        Column(modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Header("Library", "Koleksi musikmu")
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = mode == 0, onClick = { mode = 0 }, label = { Text("Favorit") },
                    leadingIcon = { Icon(Icons.Default.Favorite, null, Modifier.size(18.dp)) })
                FilterChip(selected = mode == 1, onClick = { mode = 1 }, label = { Text("Riwayat") },
                    leadingIcon = { Icon(Icons.Default.History, null, Modifier.size(18.dp)) })
            }
            Spacer(Modifier.height(6.dp))
            Button(onClick = { localAudioPicker.launch("audio/*") }, colors = ButtonDefaults.buttonColors(containerColor = XPanelHi, contentColor = XGold)) {
                Icon(Icons.Default.Folder, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Pilih lagu lokal", style = MaterialTheme.typography.labelMedium)
            }
            Spacer(Modifier.height(12.dp))
            if (list.isEmpty()) {
                Text(if (mode == 0) "Belum ada favorit. Ketuk ikon hati pada lagu." else "Belum ada riwayat.", color = XMuted)
            }
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(vertical = 4.dp)) {
                items(list, key = { it.id }) { video -> SongCard(video) }
            }
        }
    }

    // ── Audio (EQ + Crossover) ─────────────────────────
    @Composable private fun AudioPage(modifier: Modifier) {
        var section by remember { mutableIntStateOf(0) }
        Column(modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Header("Audio", "Kontrol suara presisi")
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = section == 0, onClick = { section = 0 }, label = { Text("Equalizer") })
                FilterChip(selected = section == 1, onClick = { section = 1 }, label = { Text("Crossover") })
            }
            Spacer(Modifier.height(10.dp))
            if (section == 0) EqContent(Modifier.weight(1f)) else CrossoverContent(Modifier.weight(1f))
        }
    }

    @Composable private fun EqContent(modifier: Modifier) {
        val freqs = remember { listOf(20, 25, 31, 40, 50, 63, 80, 100, 125, 160, 200, 250, 315, 400, 500, 630, 800, 1000, 1250, 1600, 2000, 2500, 3150, 4000, 5000, 6300, 8000, 10000, 12500, 16000, 20000) }
        val gains by EqualizerState.gains.collectAsState()
        Column(modifier) {
            Card(colors = CardDefaults.cardColors(containerColor = XPanel), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Equalizer 31-band", color = XText, fontWeight = FontWeight.SemiBold)
                            Text("±12 dB per pita · tersimpan otomatis", color = XMuted, style = MaterialTheme.typography.bodySmall)
                        }
                        TextButton(onClick = { applyPreset("Flat") }) { Text("Reset", color = XGold) }
                    }
                    Spacer(Modifier.height(10.dp))
                    EqCurve(gains, freqs)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("Flat", "Bass", "Vocal", "Treble").forEach { preset ->
                            AssistChip(onClick = { applyPreset(preset) }, label = { Text(preset) })
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                items(freqs.size) { i ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(freqLabel(freqs[i].toDouble()), Modifier.width(46.dp), color = XMuted, style = MaterialTheme.typography.bodySmall)
                        Slider(value = gains[i], onValueChange = { EqualizerState.set(i, it); prefs.saveEq(EqualizerState.gains.value) },
                            valueRange = -12f..12f, modifier = Modifier.weight(1f),
                            colors = SliderDefaults.colors(thumbColor = XGold, activeTrackColor = XGold, inactiveTrackColor = XLine))
                        Text(String.format("%+.0f", gains[i]), Modifier.width(40.dp), color = XText, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }

    @Composable private fun EqCurve(gains: FloatArray, freqs: List<Int>) {
        Canvas(Modifier.fillMaxWidth().height(110.dp).clip(RoundedCornerShape(12.dp)).background(XBg).padding(4.dp)) {
            val w = size.width; val h = size.height
            val midY = h / 2f; val pxPerDb = (h / 2f - 8f) / 12f
            val xs = freqs.mapIndexed { idx, hz ->
                val logMin = kotlin.math.log10(20.0); val logMax = kotlin.math.log10(20000.0)
                val t = ((kotlin.math.log10(hz.toDouble()) - logMin) / (logMax - logMin)).toFloat()
                idx to (t * w)
            }.toMap()
            drawLine(XLine, Offset(0f, midY), Offset(w, midY), 1f)
            val path = Path()
            freqs.forEachIndexed { idx, _ ->
                val x = xs[idx] ?: 0f
                val y = (midY - gains.getOrElse(idx) { 0f } * pxPerDb).coerceIn(0f, h)
                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, XGold, style = Stroke(2.5f, cap = StrokeCap.Round))
            freqs.forEachIndexed { idx, _ ->
                val x = xs[idx] ?: 0f
                val y = (midY - gains.getOrElse(idx) { 0f } * pxPerDb).coerceIn(0f, h)
                drawCircle(XGold, 3f, Offset(x, y))
            }
        }
    }

    private fun applyPreset(name: String) {
        val a = FloatArray(31)
        when (name) {
            "Bass" -> { a[0] = 6f; a[1] = 5f; a[2] = 4f; a[3] = 3f; a[4] = 2f }
            "Vocal" -> { a[13] = 2f; a[14] = 3f; a[15] = 3f; a[16] = 2f; a[17] = 2f }
            "Treble" -> { a[23] = 2f; a[24] = 3f; a[25] = 4f; a[26] = 5f; a[27] = 6f; a[28] = 5f }
        }
        EqualizerState.setAll(a); prefs.saveEq(a)
    }

    @Composable private fun CrossoverContent(modifier: Modifier) {
        val current by CrossoverState.config.collectAsState()
        fun save(c: CrossoverConfig) { CrossoverState.update(c); prefs.saveCrossover(c) }
        Column(modifier.verticalScroll(rememberScrollState())) {
            Card(colors = CardDefaults.cardColors(containerColor = XPanel), shape = RoundedCornerShape(18.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Crossover 3-band", color = XText, fontWeight = FontWeight.SemiBold)
                            Text("Low / Mid / High · Linkwitz-Riley", color = XMuted, style = MaterialTheme.typography.bodySmall)
                        }
                        Switch(checked = current.enabled, onCheckedChange = { save(current.copy(enabled = it)) },
                            colors = SwitchDefaults.colors(checkedThumbColor = XGold, checkedTrackColor = XGoldDim))
                    }
                    Spacer(Modifier.height(8.dp))
                    CrossoverBands(current)
                    Spacer(Modifier.height(14.dp))
                    Text("Low / Mid: ${current.lowHz.toInt()} Hz", color = XText, style = MaterialTheme.typography.bodyMedium)
                    Slider(value = current.lowHz, onValueChange = { save(current.copy(lowHz = it.coerceAtMost(current.highHz - 50f))) },
                        valueRange = 40f..500f, colors = SliderDefaults.colors(thumbColor = XGold, activeTrackColor = XGold, inactiveTrackColor = XLine))
                    Text("Mid / High: ${current.highHz.toInt()} Hz", color = XText, style = MaterialTheme.typography.bodyMedium)
                    Slider(value = current.highHz, onValueChange = { save(current.copy(highHz = it.coerceAtLeast(current.lowHz + 50f))) },
                        valueRange = 500f..10000f, colors = SliderDefaults.colors(thumbColor = XGold, activeTrackColor = XGold, inactiveTrackColor = XLine))
                    Spacer(Modifier.height(6.dp))
                    Text("Slope: ${current.slopeDb} dB/oct", color = XMuted, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(12, 24, 36, 48).forEach {
                            FilterChip(selected = current.slopeDb == it, onClick = { save(current.copy(slopeDb = it)) }, label = { Text("$it") })
                        }
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            XoverGainCard("Low", current.lowGainDb, { save(current.copy(lowGainDb = it)) })
            XoverGainCard("Mid", current.midGainDb, { save(current.copy(midGainDb = it)) })
            XoverGainCard("High", current.highGainDb, { save(current.copy(highGainDb = it)) })
        }
    }

    @Composable private fun CrossoverBands(c: CrossoverConfig) {
        val lo = ((kotlin.math.log10(c.lowHz.toDouble()) - kotlin.math.log10(20.0)) / (kotlin.math.log10(20000.0) - kotlin.math.log10(20.0))).toFloat().coerceIn(0f, 1f)
        val hi = ((kotlin.math.log10(c.highHz.toDouble()) - kotlin.math.log10(20.0)) / (kotlin.math.log10(20000.0) - kotlin.math.log10(20.0))).toFloat().coerceIn(0f, 1f)
        Column {
            Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(6.dp)).background(XBg)) {
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.fillMaxHeight().weight(lo.coerceAtLeast(0.01f)).background(XGoldDim))
                    Box(Modifier.fillMaxHeight().weight((hi - lo).coerceAtLeast(0.01f)).background(XGold))
                    Box(Modifier.fillMaxHeight().weight((1f - hi).coerceAtLeast(0.01f)).background(XPanelHi))
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("LOW", color = XMuted, style = MaterialTheme.typography.labelSmall)
                Text("MID", color = XMuted, style = MaterialTheme.typography.labelSmall)
                Text("HIGH", color = XMuted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }

    @Composable private fun XoverGainCard(name: String, gain: Float, onGain: (Float) -> Unit) {
        Card(colors = CardDefaults.cardColors(containerColor = XPanel), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(name, color = XText, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(52.dp))
                Slider(value = gain, onValueChange = onGain, valueRange = -12f..12f, modifier = Modifier.weight(1f),
                    colors = SliderDefaults.colors(thumbColor = XGold, activeTrackColor = XGold, inactiveTrackColor = XLine))
                Text(String.format("%+.0f", gain), color = XMuted, modifier = Modifier.width(40.dp), style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    @Composable private fun AnalyzerPage(modifier: Modifier) {
        val bins by AnalyzerState.levels.collectAsState()
        val l by AnalyzerState.peakL.collectAsState()
        val r by AnalyzerState.peakR.collectAsState()
        Column(modifier.padding(horizontal = 18.dp, vertical = 14.dp)) {
            Header("Analyzer", "Level sinyal real-time")
            Spacer(Modifier.height(16.dp))
            Card(colors = CardDefaults.cardColors(containerColor = XPanel), shape = RoundedCornerShape(20.dp), modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    Row(Modifier.fillMaxWidth().height(220.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                        bins.forEach { v ->
                            Box(Modifier.weight(1f).fillMaxHeight(v.coerceIn(0.02f, 1f)).background(XGold, RoundedCornerShape(3.dp)))
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                    HorizontalDivider(color = XLine)
                    Spacer(Modifier.height(10.dp))
                    LevelRow("L", l)
                    LevelRow("R", r)
                }
            }
        }
    }

    @Composable private fun LevelRow(ch: String, db: Float) {
        val frac = ((db + 60f) / 60f).coerceIn(0f, 1f)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(ch, color = XMuted, modifier = Modifier.width(18.dp), style = MaterialTheme.typography.bodySmall)
            Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(4.dp)).background(XBg)) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(frac).background(XGoldDim))
            }
            Spacer(Modifier.width(8.dp))
            Text(String.format("%.1f dB", db), color = XMuted, style = MaterialTheme.typography.bodySmall)
        }
        Spacer(Modifier.height(6.dp))
    }

    @Composable private fun SettingsPage(modifier: Modifier) {
        Column(modifier.padding(horizontal = 18.dp, vertical = 14.dp).verticalScroll(rememberScrollState())) {
            Header("Settings", "Preferensi XMusic")
            Spacer(Modifier.height(16.dp))
            Text("PEMUTARAN", color = XGoldDim, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            SettingsCard(Icons.Default.PlayCircle, "Streaming online", "Audio YouTube langsung, gratis, tanpa iklan di aplikasi")
            SettingsCard(Icons.Default.Notifications, "Notifikasi media", "Kontrol putar dari notifikasi (Android 13+ meminta izin)")
            Spacer(Modifier.height(14.dp))
            Text("AUDIO", color = XGoldDim, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            SettingsCard(Icons.Default.Tune, "Equalizer 31-band", "Presisi tiap pita, tersimpan otomatis")
            SettingsCard(Icons.Default.GraphicEq, "Crossover 3-band", "Low / Mid / High, slope 12-48 dB per oktaf")
            Spacer(Modifier.height(14.dp))
            Text("TENTANG", color = XGoldDim, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            SettingsCard(Icons.Default.Security, "Privasi", "Tanpa akun, favorit dan riwayat tersimpan lokal")
            SettingsCard(Icons.Default.Info, "XMusic 2.3", "Obsidian Gold, Android 8.0 sampai 16")
        }
    }

    @Composable private fun SettingsCard(icon: ImageVector, title: String, body: String) {
        Card(colors = CardDefaults.cardColors(containerColor = XPanel), shape = RoundedCornerShape(16.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp)) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).clip(CircleShape).background(XPanelHi), contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = XGold, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column {
                    Text(title, color = XText, fontWeight = FontWeight.SemiBold)
                    Text(body, color = XMuted, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }

    private fun freqLabel(hz: Double) =
        if (hz >= 1000) { val k = hz / 1000; if (k % 1 == 0.0) "${k.toInt()}k" else k.toString() + "k" } else hz.toInt().toString()
}