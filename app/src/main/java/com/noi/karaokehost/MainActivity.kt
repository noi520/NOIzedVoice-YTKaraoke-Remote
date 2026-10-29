package com.noi.karaokehost

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

private val Cyan = Color(0xFF22D3EE)
private val Violet = Color(0xFF8B5CF6)
private val Red = Color(0xFFEF4444)
private val TextMain = Color(0xFFE6F6FA)
private val TextDim = Color(0xFF9FC3CE)

/** Glass look: translucent fill + thin light border. */
private fun Modifier.glass(
    shape: Shape = RoundedCornerShape(18.dp),
    tint: Color = Color.White.copy(alpha = 0.06f),
    border: Color = Color.White.copy(alpha = 0.14f),
    borderBrush: Brush? = null
): Modifier = this.clip(shape).background(tint).border(1.dp, borderBrush ?: SolidColor(border), shape)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = 0xFF050A10.toInt()
        window.navigationBarColor = 0xFF050A10.toInt()
        Engine.init(application)
        ContextCompat.startForegroundService(this, Intent(this, HostService::class.java))
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, background = Color(0xFF050A10), surface = Color(0xFF0A1420))) {
                var showIntro by rememberSaveable { mutableStateOf(true) }
                if (showIntro) IntroScreen { showIntro = false } else App()
            }
        }
    }
}

@Composable
fun App(vm: MainViewModel = viewModel()) {
    var tab by rememberSaveable { mutableStateOf(if (vm.paired) 0 else 2) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(vm.message) {
        vm.message?.let { snackbar.showSnackbar(it); vm.message = null }
    }
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF050A10), Color(0xFF09131E), Color(0xFF060A12))))) {
        Box(Modifier.size(340.dp).offset((-110).dp, (-90).dp).background(Brush.radialGradient(listOf(Cyan.copy(alpha = 0.20f), Color.Transparent))))
        Box(Modifier.size(340.dp).align(Alignment.BottomEnd).offset(110.dp, 90.dp).background(Brush.radialGradient(listOf(Violet.copy(alpha = 0.18f), Color.Transparent))))
        Scaffold(
            containerColor = Color.Transparent,
            snackbarHost = { SnackbarHost(snackbar) },
            topBar = { Column { Header(vm); AdStrip(vm) } },
            bottomBar = { BottomNav(tab) { tab = it } }
        ) { pad ->
            Box(Modifier.padding(pad).fillMaxSize()) {
                when (tab) {
                    0 -> NowScreen(vm)
                    1 -> SearchScreen(vm)
                    else -> SetupScreen(vm)
                }
            }
        }
    }
}

@Composable
fun Header(vm: MainViewModel) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        Image(painterResource(R.drawable.logo_small), null, Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)))
        Spacer(Modifier.width(8.dp))
        Text(
            "NOIzedVoice",
            style = TextStyle(brush = Brush.horizontalGradient(listOf(Cyan, Violet)), fontSize = 17.sp, fontWeight = FontWeight.Bold),
            modifier = Modifier.weight(1f), maxLines = 1
        )
        Pill(
            if (vm.tvConnected) "TV connected" else if (vm.paired) "TV connecting..." else "TV not paired",
            dot = if (vm.tvConnected) Color(0xFF34D399) else Color(0xFFF59E0B)
        )
        Spacer(Modifier.width(6.dp))
        Pill("Guests: ${vm.guests}")
    }
}

@Composable
fun Pill(text: String, dot: Color? = null) {
    Row(Modifier.glass(RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (dot != null) {
            Box(Modifier.size(6.dp).background(dot, CircleShape))
            Spacer(Modifier.width(5.dp))
        }
        Text(text, fontSize = 10.sp, color = TextMain, maxLines = 1)
    }
}

/** Lalabas lang kapag may ad. Active ang Skip Ad kapag skippable na. */
@Composable
fun AdStrip(vm: MainViewModel) {
    AnimatedVisibility(visible = vm.adPlaying) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 6.dp).fillMaxWidth()
                .glass(RoundedCornerShape(16.dp), Red.copy(alpha = 0.12f), Red.copy(alpha = 0.5f))
                .padding(start = 14.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Ad playing", color = Color(0xFFFCA5A5), modifier = Modifier.weight(1f))
            Button(
                onClick = { vm.skipAd() },
                enabled = vm.adSkippable,
                colors = ButtonDefaults.buttonColors(
                    containerColor = Red, contentColor = Color.White,
                    disabledContainerColor = Red.copy(alpha = 0.35f), disabledContentColor = Color.White.copy(alpha = 0.7f)
                )
            ) { Text(if (vm.adSkippable) "Skip Ad" else "Skip Ad (wait...)") }
        }
    }
}

@Composable
fun BottomNav(tab: Int, onTab: (Int) -> Unit) {
    val colors = NavigationBarItemDefaults.colors(
        selectedIconColor = Cyan, selectedTextColor = Cyan, indicatorColor = Cyan.copy(alpha = 0.16f),
        unselectedIconColor = TextDim, unselectedTextColor = TextDim
    )
    NavigationBar(containerColor = Color.White.copy(alpha = 0.05f), tonalElevation = 0.dp) {
        NavigationBarItem(tab == 0, { onTab(0) }, icon = { Text("♫", fontSize = 20.sp) }, label = { Text("Now") }, colors = colors)
        NavigationBarItem(tab == 1, { onTab(1) }, icon = { Icon(Icons.Default.Search, null) }, label = { Text("Search") }, colors = colors)
        NavigationBarItem(tab == 2, { onTab(2) }, icon = { Icon(Icons.Default.Settings, null) }, label = { Text("Setup") }, colors = colors)
    }
}

// ---------- glyphs (walang extra icon library) ----------
@Composable
fun PauseGlyph(color: Color, dp: Dp = 26.dp) = Canvas(Modifier.size(dp)) {
    val w = size.width; val h = size.height
    drawRoundRect(color, Offset(w * 0.18f, h * 0.1f), Size(w * 0.24f, h * 0.8f), CornerRadius(w * 0.06f))
    drawRoundRect(color, Offset(w * 0.58f, h * 0.1f), Size(w * 0.24f, h * 0.8f), CornerRadius(w * 0.06f))
}

@Composable
fun PlayGlyph(color: Color, dp: Dp = 26.dp) = Canvas(Modifier.size(dp)) {
    val w = size.width; val h = size.height
    val p = Path().apply { moveTo(w * 0.22f, h * 0.1f); lineTo(w * 0.9f, h * 0.5f); lineTo(w * 0.22f, h * 0.9f); close() }
    drawPath(p, color)
}

@Composable
fun SkipGlyph(color: Color, dp: Dp = 22.dp) = Canvas(Modifier.size(dp)) {
    val w = size.width; val h = size.height
    val p = Path().apply { moveTo(w * 0.1f, h * 0.15f); lineTo(w * 0.68f, h * 0.5f); lineTo(w * 0.1f, h * 0.85f); close() }
    drawPath(p, color)
    drawRoundRect(color, Offset(w * 0.74f, h * 0.15f), Size(w * 0.15f, h * 0.7f), CornerRadius(w * 0.04f))
}

@Composable
fun CircleBtn(diameter: Dp, enabled: Boolean, ring: Color, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(diameter).clip(CircleShape).background(Color.White.copy(alpha = 0.07f))
            .border(1.dp, ring, CircleShape).alpha(if (enabled) 1f else 0.4f)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center
    ) { content() }
}

@Composable
fun SmallIconBtn(icon: androidx.compose.ui.graphics.vector.ImageVector, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick = onClick, enabled = enabled, modifier = Modifier.size(32.dp)) {
        Icon(icon, null, tint = TextDim.copy(alpha = if (enabled) 1f else 0.25f), modifier = Modifier.size(20.dp))
    }
}

// ---------- NOW ----------
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun NowScreen(vm: MainViewModel) {
    var confirmClear by remember { mutableStateOf(false) }
    val np = vm.nowPlaying
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        // malaking video area
        Box(
            Modifier.fillMaxWidth().aspectRatio(16f / 9f).glass(
                RoundedCornerShape(20.dp), Color.White.copy(alpha = 0.05f),
                borderBrush = Brush.linearGradient(listOf(Cyan.copy(alpha = 0.6f), Violet.copy(alpha = 0.6f)))
            ),
            contentAlignment = Alignment.Center
        ) {
            if (np == null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Image(painterResource(R.drawable.logo_small), null, Modifier.size(72.dp).clip(RoundedCornerShape(20.dp)))
                    Spacer(Modifier.height(8.dp))
                    Text("NOIzedVoice", style = TextStyle(brush = Brush.horizontalGradient(listOf(Cyan, Violet)), fontSize = 18.sp, fontWeight = FontWeight.Bold))
                    Text("Your voice. Your sound.", fontSize = 12.sp, color = TextDim)
                }
            } else {
                AsyncImage("https://i.ytimg.com/vi/${np.videoId}/hqdefault.jpg", null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(np?.title ?: "Nothing playing", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, color = TextMain)
        Text(
            when {
                np == null -> "Add a song from the Search tab"
                vm.adPlaying -> "Ad playing"
                vm.playback == "paused" -> "Paused"
                else -> "Playing  ${fmtDuration(np.duration)}"
            },
            fontSize = 12.sp, color = if (vm.adPlaying) Color(0xFFF87171) else TextDim
        )
        // controls
        Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
            CircleBtn(46.dp, true, if (vm.locked) Cyan else Color.White.copy(alpha = 0.18f), { vm.toggleLock() }) {
                Icon(Icons.Default.Lock, "Lock queue", tint = if (vm.locked) Cyan else TextDim)
            }
            Box(contentAlignment = Alignment.Center) {
                Box(Modifier.size(92.dp).background(Brush.radialGradient(listOf(Cyan.copy(alpha = 0.35f), Color.Transparent)), CircleShape))
                CircleBtn(68.dp, np != null, Cyan, { vm.togglePause() }) {
                    if (vm.playback == "paused") PlayGlyph(Cyan) else PauseGlyph(Cyan)
                }
            }
            CircleBtn(46.dp, np != null, Color.White.copy(alpha = 0.18f), { vm.skip() }) { SkipGlyph(TextMain) }
        }
        // queue
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Up next (${vm.queue.size})", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
                if (vm.queue.isNotEmpty()) Text("Long-press a song to play it next", fontSize = 10.sp, color = TextDim)
            }
            TextButton(onClick = { confirmClear = true }, enabled = vm.queue.isNotEmpty()) { Text("Clear all") }
        }
        if (vm.queue.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("The queue is empty. Add songs from Search.", color = TextDim, fontSize = 13.sp)
            }
        } else {
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                itemsIndexed(vm.queue, key = { _, s -> s.videoId }) { i, s ->
                    Row(
                        Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp))
                            .combinedClickable(onClick = {}, onLongClick = { vm.playNext(s) })
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("${i + 1}", color = Cyan, fontWeight = FontWeight.Bold, modifier = Modifier.width(18.dp), textAlign = TextAlign.Center)
                        AsyncImage(s.thumbnail, null, Modifier.width(64.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                        Column(Modifier.weight(1f)) {
                            Text(s.title, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = TextMain)
                            Text(fmtDuration(s.duration), fontSize = 11.sp, color = TextDim)
                        }
                        SmallIconBtn(Icons.Default.KeyboardArrowUp, i > 0) { vm.move(s, i) }
                        SmallIconBtn(Icons.Default.KeyboardArrowDown, i < vm.queue.size - 1) { vm.move(s, i + 2) }
                        SmallIconBtn(Icons.Default.Delete) { vm.remove(s) }
                    }
                }
            }
        }
    }
    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text("Clear the queue?") },
            text = { Text("This removes all songs from the queue.") },
            confirmButton = { TextButton(onClick = { vm.clearQueue(); confirmClear = false }) { Text("Yes, clear") } },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } }
        )
    }
}

// ---------- SEARCH ----------
@Composable
fun SearchScreen(vm: MainViewModel) {
    var q by remember { mutableStateOf("") }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                q, { q = it }, Modifier.weight(1f), singleLine = true, shape = RoundedCornerShape(16.dp),
                placeholder = { Text("Song or artist") },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { vm.search(q) })
            )
            Button(onClick = { vm.search(q) }, enabled = !vm.searching) { Text(if (vm.searching) "..." else "Search") }
        }
        Text("\"karaoke\" is added to your search automatically.", fontSize = 11.sp, color = TextDim, modifier = Modifier.padding(vertical = 6.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 8.dp)) {
            items(vm.results, key = { it.videoId }) { s ->
                val inQueue = vm.queue.any { it.videoId == s.videoId } || vm.nowPlaying?.videoId == s.videoId
                Row(
                    Modifier.fillMaxWidth().glass(RoundedCornerShape(16.dp)).padding(8.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    AsyncImage(s.thumbnail, null, Modifier.width(88.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(8.dp)), contentScale = ContentScale.Crop)
                    Column(Modifier.weight(1f)) {
                        Text(s.title, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, color = TextMain)
                        Text(fmtDuration(s.duration), fontSize = 11.sp, color = TextDim)
                    }
                    Button(onClick = { vm.add(s) }, enabled = !inQueue, contentPadding = PaddingValues(horizontal = 14.dp)) {
                        Text(if (inQueue) "Added" else "Add")
                    }
                }
            }
        }
    }
}

// ---------- SETUP ----------
@Composable
fun SetupScreen(vm: MainViewModel) {
    var code by remember { mutableStateOf("") }
    LaunchedEffect(Unit) { vm.loadQr() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.fillMaxWidth().glass().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("TV pairing", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextMain)
            Text("On the TV: open YouTube > Settings > Link with TV code. The phone and the TV must be on the same WiFi.", fontSize = 12.sp, color = TextDim)
            OutlinedTextField(code, { code = it }, Modifier.fillMaxWidth(), singleLine = true, shape = RoundedCornerShape(16.dp), label = { Text("TV code") })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.pair(code.trim()) }, enabled = code.isNotBlank()) { Text("Pair") }
                OutlinedButton(onClick = { vm.unpair() }, enabled = vm.paired) { Text("Disconnect") }
            }
            Text(
                if (vm.tvConnected) "TV: connected" else if (vm.paired) "TV: paired, connecting..." else "TV: not paired",
                fontSize = 12.sp, color = TextDim
            )
        }
        Column(Modifier.fillMaxWidth().glass().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Guest QR", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = TextMain, modifier = Modifier.fillMaxWidth())
            Text("Ask your guests to scan this. They must be on the same WiFi (or your phone's hotspot).", fontSize = 12.sp, color = TextDim)
            vm.qrBitmap?.let {
                Image(it.asImageBitmap(), "Guest QR", Modifier.size(240.dp).clip(RoundedCornerShape(12.dp)), contentScale = ContentScale.Fit)
                Text(vm.qrUrl, fontSize = 11.sp, color = TextDim)
            }
            OutlinedButton(onClick = { vm.loadQr() }) { Text("Refresh QR (if the WiFi changed)") }
        }
        Spacer(Modifier.height(8.dp))
    }
}

// ---------- INTRO ----------
@Composable
fun IntroScreen(onDone: () -> Unit) {
    var shown by remember { mutableStateOf(false) }
    val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(500), label = "intro")
    LaunchedEffect(Unit) {
        shown = true
        delay(2000)
        onDone()
    }
    Column(
        Modifier.fillMaxSize().background(Color.Black).alpha(alpha),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Image(painterResource(R.drawable.intro_art), null, Modifier.fillMaxWidth(), contentScale = ContentScale.FillWidth)
        }
        Image(painterResource(R.drawable.papanoi_logo), "Papa Noi Labs", Modifier.height(120.dp).padding(bottom = 16.dp), contentScale = ContentScale.Fit)
    }
}
