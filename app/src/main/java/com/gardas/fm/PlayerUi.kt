package com.gardas.fm

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.DownloadDone
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay

/** Oynatıcının (konum, süre) bilgisini okur. Sadece çalarken sık günceller (pil ve akıcılık için). */
@Composable
fun rememberPlayerPosition(): State<Pair<Long, Long>> = produceState(0L to 0L) {
    while (true) {
        val p = Engine.player
        value = p.currentPosition to p.duration.coerceAtLeast(0L)
        delay(if (Engine.playing.value) 300L else 800L)
    }
}

// ====================================================================== MİNİ ÇALAR (alt çubuk)
@Composable
fun MiniPlayer(onOpen: () -> Unit) {
    val queue by Engine.queue.collectAsState()
    val idx by Engine.index.collectAsState()
    val playing by Engine.playing.collectAsState()
    val loading by Engine.loading.collectAsState()
    val cover by Engine.coverId.collectAsState()
    val msg by Engine.message.collectAsState()
    val pos = rememberPlayerPosition()
    val song = queue.getOrNull(idx) ?: return

    Column(
        Modifier
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .fillMaxWidth()
            .neonGlass(22.dp, 0.80f, strong = true)
            .clickable(onClick = onOpen)
    ) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Cover(cover, Modifier.size(46.dp))
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    T(song.title, 15.sp, bold = true, modifier = Modifier.weight(1f, fill = false))
                    EqBars(playing && !loading, Modifier.padding(start = 8.dp))
                }
                T(if (loading) "Bağlanıyor..." else song.artist.ifBlank { msg }, 12.sp, Muted)
            }
            IconButton(onClick = { Engine.prev() }) {
                Icon(Icons.Filled.SkipPrevious, contentDescription = "Önceki", tint = TextC)
            }
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Accent).clickable { Engine.togglePlay() },
                contentAlignment = Alignment.Center,
            ) {
                if (loading) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Icon(if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, contentDescription = "Oynat/duraklat", tint = Color.White)
                }
            }
            IconButton(onClick = { Engine.next() }) {
                Icon(Icons.Filled.SkipNext, contentDescription = "Sonraki", tint = TextC)
            }
        }
        LinearProgressIndicator(
            progress = {
                val (p, d) = pos.value          // durum sadece burada okunur: ilerleme çubuğu dışında yeniden çizim olmaz
                if (d > 0) (p.toFloat() / d).coerceIn(0f, 1f) else 0f
            },
            modifier = Modifier.fillMaxWidth().height(2.dp),
            color = Accent,
            trackColor = Color.Transparent,
        )
    }
}

// ====================================================================== ŞİMDİ ÇALIYOR (tam ekran)
@Composable
fun NowPlaying(onClose: () -> Unit, onOpenQueue: () -> Unit) {
    BackHandler(onBack = onClose)
    val queue by Engine.queue.collectAsState()
    val idx by Engine.index.collectAsState()
    val playing by Engine.playing.collectAsState()
    val loading by Engine.loading.collectAsState()
    val cover by Engine.coverId.collectAsState()
    val msg by Engine.message.collectAsState()
    val shuffle by Engine.shuffle.collectAsState()
    val repeat by Engine.repeatMode.collectAsState()
    val favs by Engine.favorites.collectAsState()
    val dls by Downloads.items.collectAsState()
    val sleep by Engine.sleepMin.collectAsState()
    val speed by Engine.speed.collectAsState()
    val eq by Engine.eqPreset.collectAsState()
    val lyr by Engine.lyrics.collectAsState()
    val pos = rememberPlayerPosition()
    val song = queue.getOrNull(idx)
    val isFav = song != null && favs.any { it.key == song.key }
    val isDl = song != null && dls.any { it.song.key == song.key }
    var lyricsMode by remember { mutableStateOf(false) }
    val cfg = LocalConfiguration.current
    val side = minOf(cfg.screenWidthDp - 96, (cfg.screenHeightDp * 0.27f).toInt()).coerceAtLeast(110).dp
    val ring = Brush.linearGradient(listOf(Accent, NeonBlue.copy(alpha = 0.8f)))

    Box(Modifier.fillMaxSize().background(Color(0xFF060A1C).copy(alpha = 0.94f))) {
        Column(
            Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) {
                    Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Kapat", tint = TextC, modifier = Modifier.size(32.dp))
                }
                T("ŞİMDİ ÇALIYOR", 12.sp, Accent, bold = true, modifier = Modifier.weight(1f), align = TextAlign.Center)
                Chip("Söz modu", selected = lyricsMode) { lyricsMode = !lyricsMode }
            }
            if (!lyricsMode) {
                Spacer(Modifier.height(4.dp))
                PulseRings(playing, Modifier.size(side + 44.dp)) {
                    Box(Modifier.border(2.dp, ring, RoundedCornerShape(22.dp)).padding(3.dp)) {
                        Cover(cover, Modifier.size(side), radius = 19.dp)
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    T(song?.title ?: "Bir şarkı seç", 21.sp, bold = true, serif = true, maxLines = 2)
                    T(if (loading) "Bağlanıyor..." else (song?.artist?.ifBlank { null } ?: msg), 14.sp, Soft, maxLines = 2)
                }
                if (song != null) {
                    IconButton(onClick = { Engine.toggleFavorite(song) }) {
                        Icon(
                            if (isFav) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = "Favori",
                            tint = if (isFav) Accent else Muted,
                        )
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
            Box(Modifier.weight(1f).fillMaxWidth().neonGlass(18.dp, 0.55f)) {
                LyricsPanel(lyr, pos, lyricsMode)
            }
            SeekBar(pos)
            Row(
                Modifier.fillMaxWidth().padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { Engine.toggleShuffle() }) {
                    Icon(Icons.Filled.Shuffle, contentDescription = "Karışık", tint = if (shuffle) Gold else Muted)
                }
                IconButton(onClick = { Engine.prev() }, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Önceki", tint = TextC, modifier = Modifier.size(34.dp))
                }
                Box(
                    Modifier.size(66.dp).clip(CircleShape).background(Accent).clickable { Engine.togglePlay() },
                    contentAlignment = Alignment.Center,
                ) {
                    if (loading) {
                        CircularProgressIndicator(Modifier.size(30.dp), color = Color.White, strokeWidth = 3.dp)
                    } else {
                        Icon(
                            if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = "Oynat/duraklat",
                            tint = Color.White,
                            modifier = Modifier.size(38.dp),
                        )
                    }
                }
                IconButton(onClick = { Engine.next() }, modifier = Modifier.size(52.dp)) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Sonraki", tint = TextC, modifier = Modifier.size(34.dp))
                }
                IconButton(onClick = { Engine.cycleRepeat() }) {
                    Icon(
                        if (repeat == 2) Icons.Filled.RepeatOne else Icons.Filled.Repeat,
                        contentDescription = "Tekrar",
                        tint = if (repeat > 0) Gold else Muted,
                    )
                }
            }
            LazyRow(
                contentPadding = PaddingValues(vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { Chip("EQ: ${Engine.eqNames[eq]}", selected = eq > 0) { Engine.cycleEq() } }
                item { Chip("Hız ${speed}x", selected = speed != 1f) { Engine.cycleSpeed() } }
                item { Chip(if (sleep > 0) "Uyku $sleep dk" else "Uyku kapalı", selected = sleep > 0) { Engine.cycleSleep() } }
                item { Chip("Kuyruk") { onOpenQueue() } }
                if (song != null && !song.isLocal) {
                    item {
                        Chip(
                            if (isDl) "İndirildi" else "İndir",
                            selected = isDl,
                            icon = if (isDl) Icons.Filled.DownloadDone else Icons.Filled.Download,
                        ) { if (isDl) Downloads.delete(song) else Downloads.download(song) }
                    }
                }
            }
            Spacer(Modifier.height(6.dp))
        }
    }
}

@Composable
private fun SeekBar(pos: State<Pair<Long, Long>>) {
    var drag by remember { mutableStateOf<Float?>(null) }
    val (p, total) = pos.value
    Slider(
        value = drag ?: if (total > 0) (p.toFloat() / total).coerceIn(0f, 1f) else 0f,
        onValueChange = { drag = it },
        onValueChangeFinished = {
            drag?.let { Engine.seekFraction(it) }
            drag = null
        },
        colors = SliderDefaults.colors(thumbColor = Gold, activeTrackColor = Accent, inactiveTrackColor = ChipBg),
    )
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
        val shown = drag?.let { (it * total).toLong() } ?: p
        T(fmtSec(shown / 1000), 12.sp, Muted, modifier = Modifier.weight(1f))
        T(fmtSec(total / 1000), 12.sp, Muted)
    }
}

@Composable
private fun LyricsPanel(lyr: Lyrics, pos: State<Pair<Long, Long>>, big: Boolean) {
    if (lyr.synced.isNotEmpty()) {
        val cur by remember(lyr) {
            derivedStateOf {
                val t = pos.value.first / 1000.0 + 0.15
                lyr.synced.indexOfLast { it.sec <= t }
            }
        }
        val state = rememberLazyListState()
        LaunchedEffect(cur) {
            if (cur >= 0) state.animateScrollToItem((cur - 2).coerceAtLeast(0))
        }
        LazyColumn(state = state, contentPadding = PaddingValues(16.dp), modifier = Modifier.fillMaxSize()) {
            itemsIndexed(lyr.synced) { i, line ->
                val on = i == cur
                T(
                    line.text.ifBlank { "♪" },
                    if (big) (if (on) 24.sp else 20.sp) else (if (on) 18.sp else 16.sp),
                    if (on) Gold else Soft,
                    bold = on,
                    maxLines = 4,
                    align = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp),
                )
            }
        }
    } else {
        val text = lyr.plain.ifBlank { lyr.status.ifBlank { "Şarkı sözleri burada görünecek." } }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            T(text, if (big) 19.sp else 16.sp, Soft, maxLines = Int.MAX_VALUE, align = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}
