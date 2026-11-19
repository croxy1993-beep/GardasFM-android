package com.gardas.fm

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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldColors
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar

const val QUEUE_ROUTE = "queue"

// ====================================================================== ORTAK PARÇALAR
@Composable
fun fieldColors(): TextFieldColors = TextFieldDefaults.colors(
    focusedContainerColor = Color.Transparent,
    unfocusedContainerColor = Color.Transparent,
    focusedIndicatorColor = Color.Transparent,
    unfocusedIndicatorColor = Color.Transparent,
    focusedTextColor = TextC,
    unfocusedTextColor = TextC,
    cursorColor = Accent,
)

/** Başlık paneli: büyük başlık, alt yazı ve (isteğe bağlı) arama kutusu. */
@Composable
fun PageHeader(
    title: String,
    subtitle: String,
    modifier: Modifier = Modifier,
    hPad: androidx.compose.ui.unit.Dp = 14.dp,
    content: @Composable () -> Unit = {},
) {
    Column(
        modifier
            .padding(horizontal = hPad, vertical = 6.dp)
            .fillMaxWidth()
            .neonGlass(22.dp, 0.58f, strong = true)
            .padding(horizontal = 18.dp, vertical = 14.dp)
    ) {
        T(title, 30.sp, bold = true, serif = true)
        T(subtitle, 13.sp, Soft, maxLines = 2)
        content()
    }
}

/** "Şarkı ya da sanatçı ara..." kutusu ve pembe "Ara" düğmesi (PC 5.0 ile aynı). */
@Composable
fun SearchPill(initial: String, onSubmit: (String) -> Unit) {
    val keyboard = LocalSoftwareKeyboardController.current
    var text by remember { mutableStateOf(initial) }
    val shape = RoundedCornerShape(26.dp)
    Row(
        Modifier
            .padding(top = 10.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(Panel.copy(alpha = 0.85f))
            .border(1.dp, Color.White.copy(alpha = 0.18f), shape),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextField(
            value = text,
            onValueChange = { text = it },
            placeholder = { T("Şarkı ya da sanatçı ara...", 14.sp, Muted) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                onSubmit(text)
                keyboard?.hide()
            }),
            colors = fieldColors(),
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = {
                onSubmit(text)
                keyboard?.hide()
            },
            colors = ButtonDefaults.buttonColors(containerColor = Accent),
            modifier = Modifier.padding(end = 6.dp),
        ) { T("Ara", 14.sp, Color.White, bold = true) }
    }
}

@Composable
fun ChipRow(content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        content = content,
    )
}

fun routeTitle(route: String): String = when {
    route.startsWith(PL_PREFIX) -> route.removePrefix(PL_PREFIX)
    route.startsWith("tag:") -> "#  " + route.removePrefix("tag:")
    route == QUEUE_ROUTE -> "Sıradakiler"
    else -> route
}

fun routeTagline(route: String): String = when {
    route.startsWith(PL_PREFIX) -> "Kendi listen"
    route.startsWith("tag:") -> "Etiketli şarkılar"
    route == DOWNLOADS -> "İnternetsiz de çalar"
    route == QUEUE_ROUTE -> "Sıradaki şarkılar"
    else -> TAGLINES[route] ?: ""
}

@Composable
fun rememberSongsFor(route: String): List<Song> {
    val favs by Engine.favorites.collectAsState()
    val rec by Engine.recent.collectAsState()
    val dls by Downloads.items.collectAsState()
    val pls by Engine.playlists.collectAsState()
    val tg by Engine.tagged.collectAsState()
    return when {
        route == FAV -> favs
        route == RECENT -> rec
        route == DOWNLOADS -> dls.map { it.song }
        route.startsWith(PL_PREFIX) -> pls.firstOrNull { it.name == route.removePrefix(PL_PREFIX) }?.songs ?: emptyList()
        route.startsWith("tag:") -> tg.filter { route.removePrefix("tag:") in it.second }.map { it.first }
        else -> CATALOG[route] ?: emptyList()
    }
}

// ====================================================================== ANA SAYFA
@Composable
fun HomeScreen(onOpen: (String) -> Unit, onSearch: (String) -> Unit) {
    val favs by Engine.favorites.collectAsState()
    val rec by Engine.recent.collectAsState()
    val dls by Downloads.items.collectAsState()
    val restored by Engine.restored.collectAsState()
    val queue by Engine.queue.collectAsState()
    val idx by Engine.index.collectAsState()
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val greet = when {
        hour in 5..11 -> "Günaydın"
        hour in 12..17 -> "İyi günler"
        hour in 18..22 -> "İyi akşamlar"
        else -> "İyi geceler"
    }
    val stats = remember(rec) { Store.weeklyStats() }

    // (anahtar, başlık, alt yazı, vurgulu mu)
    val cards = ArrayList<List<Any>>()
    cards.add(listOf("shuffle_all", "Karışık çal", "Tüm katalogdan rastgele", true))
    cards.add(listOf("today", "Bugünün karışığı", "Her gün yeni 30 şarkı", true))
    if (restored && queue.getOrNull(idx) != null) {
        cards.add(listOf("resume", "Kaldığın yerden devam", queue[idx].title, true))
    }
    Engine.moods.forEach { cards.add(listOf("mood:" + it[0], it[0], it[1], false)) }
    CATALOG.keys.forEach { cards.add(listOf(it, it, "${CATALOG[it]?.size ?: 0} şarkı  ·  ${TAGLINES[it] ?: ""}", false)) }
    cards.add(listOf(FAV, FAV, "${favs.size} şarkı", false))
    cards.add(listOf(DOWNLOADS, DOWNLOADS, "${dls.size} şarkı  ·  internetsiz çalar", false))
    cards.add(listOf(RECENT, RECENT, "${rec.size} şarkı", false))

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(start = 14.dp, end = 14.dp, bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            PageHeader("Gardaş FM", "$greet, ne dinliyoruz?", hPad = 0.dp) {
                SearchPill("") { onSearch(it) }
            }
        }
        if (stats.first > 0) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .neonGlass(16.dp, 0.5f)
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    T("Bu hafta", 12.sp, Gold, bold = true)
                    Spacer(Modifier.width(10.dp))
                    T("${stats.first} şarkı dinledin" + (stats.second?.let { "  ·  en çok: $it" } ?: ""), 13.sp, Soft, maxLines = 2)
                }
            }
        }
        gridItemsIndexed(cards) { i, c ->
            val key = c[0] as String
            val accent = c[3] as Boolean
            Column(
                Modifier
                    .appear(minOf(i, 8) * 60)
                    .fillMaxWidth()
                    .neonGlass(18.dp, if (accent) 0.72f else 0.55f, strong = accent)
                    .clickable {
                        when {
                            key == "shuffle_all" -> Engine.playAllShuffled()
                            key == "today" -> Engine.playList(Engine.todaysMix(), 0)
                            key == "resume" -> Engine.resumeSession()
                            key.startsWith("mood:") -> Engine.playMood(key.removePrefix("mood:"))
                            else -> onOpen(key)
                        }
                    }
                    .padding(16.dp)
            ) {
                T(c[1] as String, 18.sp, if (accent) Gold else TextC, bold = true, serif = true)
                Spacer(Modifier.height(4.dp))
                T(c[2] as String, 12.sp, Muted, maxLines = 2)
            }
        }
    }
}

// ====================================================================== LİSTE SAYFASI
@Composable
fun ViewScreen(route: String, onSearch: (String) -> Unit, onDeleted: () -> Unit) {
    val songs = rememberSongsFor(route)
    val total = songs.sumOf { it.durationSec }
    val sub = buildString {
        append(routeTagline(route))
        append("  •  ${songs.size} şarkı")
        if (total > 0) append("  •  ${fmtTotal(total)}")
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader(routeTitle(route), sub) { SearchPill("") { onSearch(it) } }
        ChipRow {
            item { Chip("Çal", primary = true, icon = Icons.Filled.PlayArrow) { Engine.playList(songs, 0) } }
            item { Chip("Karışık çal", icon = Icons.Filled.Shuffle) { Engine.playShuffled(songs) } }
            if (route == DOWNLOADS) {
                item {
                    Chip("Hepsini sil", icon = Icons.Filled.Close) {
                        songs.toList().forEach { Downloads.delete(it) }
                        Engine.say("İndirilenler silindi")
                    }
                }
            } else {
                item {
                    Chip("Hepsini indir", icon = Icons.Filled.Download) {
                        val todo = songs.filter { !it.isLocal && !Downloads.has(it) }.take(50)
                        todo.forEach { Downloads.download(it) }
                        Engine.say(if (todo.isEmpty()) "İndirilecek şarkı yok" else "${todo.size} şarkı indiriliyor")
                    }
                }
            }
            if (route.startsWith(PL_PREFIX)) {
                item {
                    Chip("Listeyi sil", icon = Icons.Filled.Close) {
                        Engine.deletePlaylist(route.removePrefix(PL_PREFIX))
                        onDeleted()
                    }
                }
            }
        }
        if (songs.isEmpty()) {
            val msg = when {
                route == FAV -> "Henüz favorin yok. Bir şarkının ⋮ menüsünden favorile."
                route == DOWNLOADS -> "İndirilmiş şarkı yok. Şarkının ⋮ menüsünden İndir'e dokun."
                route.startsWith(PL_PREFIX) -> "Liste boş. Şarkıların ⋮ menüsünden 'Listeye ekle'ye dokun."
                else -> "Burada henüz bir şey yok."
            }
            T(msg, 14.sp, Muted, modifier = Modifier.padding(24.dp), maxLines = 3)
        } else {
            SongList(
                songs,
                Modifier.weight(1f),
                inPlaylist = if (route.startsWith(PL_PREFIX)) route.removePrefix(PL_PREFIX) else null,
            )
        }
    }
}

// ====================================================================== ŞARKI LİSTESİ
@Composable
fun SongList(songs: List<Song>, modifier: Modifier = Modifier, inPlaylist: String? = null) {
    val queue by Engine.queue.collectAsState()
    val idx by Engine.index.collectAsState()
    val favs by Engine.favorites.collectAsState()
    val dls by Downloads.items.collectAsState()
    val prog by Downloads.progress.collectAsState()
    val curKey = queue.getOrNull(idx)?.key
    val favKeys = remember(favs) { favs.map { it.key }.toHashSet() }
    val dlKeys = remember(dls) { dls.map { it.song.key }.toHashSet() }
    Box(
        modifier
            .padding(horizontal = 14.dp, vertical = 4.dp)
            .fillMaxWidth()
            .neonGlass(20.dp, 0.55f)
    ) {
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
            itemsIndexed(songs, key = { i, s -> "$i|${s.key}" }) { i, song ->
                SongRow(
                    i = i,
                    song = song,
                    isCur = song.key == curKey,
                    isFav = song.key in favKeys,
                    isDl = song.key in dlKeys,
                    pct = prog[song.key],
                    onPlay = { Engine.playList(songs, i) },
                    inPlaylist = inPlaylist,
                )
            }
        }
    }
}

@Composable
fun SongRow(
    i: Int,
    song: Song,
    isCur: Boolean,
    isFav: Boolean,
    isDl: Boolean,
    pct: Int?,
    onPlay: () -> Unit,
    inPlaylist: String?,
) {
    var menu by remember { mutableStateOf(false) }
    var dialog by remember { mutableStateOf("") }      // "", "picker", "tags"
    val shape = RoundedCornerShape(14.dp)
    val curBorder = Brush.linearGradient(listOf(Accent, NeonBlue.copy(alpha = 0.7f)))
    Row(
        Modifier
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .fillMaxWidth()
            .clip(shape)
            .background(if (isCur) Accent.copy(alpha = 0.14f) else Color.Transparent)
            .then(if (isCur) Modifier.border(1.5.dp, curBorder, shape) else Modifier)
            .clickable(onClick = onPlay)
            .padding(start = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.width(30.dp), contentAlignment = Alignment.Center) {
            if (isCur) Icon(Icons.Filled.PlayArrow, null, tint = Accent, modifier = Modifier.size(20.dp))
            else T("${i + 1}", 12.sp, Muted)
        }
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isFav) {
                    Icon(Icons.Filled.Favorite, null, tint = Accent, modifier = Modifier.size(12.dp))
                    Spacer(Modifier.width(4.dp))
                }
                T(song.title, 15.sp, if (isCur) Accent else TextC, bold = isCur)
            }
            T(song.artist.ifBlank { "—" }, 12.sp, Muted)
        }
        Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(horizontal = 4.dp)) {
            if (song.durationSec > 0) T(fmtSec(song.durationSec), 12.sp, Soft)
            when {
                pct != null -> T("%$pct", 10.sp, Gold, bold = true)
                isDl -> T("↓ İndirildi", 10.sp, Gold)
                song.isLocal -> T("Yerel", 10.sp, Muted)
                song.url != null -> T("YouTube", 10.sp, Muted)
                else -> T("Katalog", 10.sp, Muted)
            }
        }
        Box {
            IconButton(onClick = { menu = true }) {
                Icon(Icons.Filled.MoreVert, contentDescription = "Menü", tint = Muted)
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { T("Sıradaki olarak çal", 14.sp) }, onClick = { menu = false; Engine.playNext(song) })
                DropdownMenuItem(text = { T("Sıraya ekle", 14.sp) }, onClick = { menu = false; Engine.addToQueue(song) })
                DropdownMenuItem(text = { T("Listeye ekle...", 14.sp) }, onClick = { menu = false; dialog = "picker" })
                DropdownMenuItem(
                    text = { T(if (isFav) "Favoriden çıkar" else "Favorilere ekle", 14.sp) },
                    onClick = { menu = false; Engine.toggleFavorite(song) },
                )
                if (!song.isLocal) {
                    DropdownMenuItem(
                        text = { T(if (isDl) "İndirmeyi sil" else "İndir (internetsiz dinle)", 14.sp) },
                        onClick = { menu = false; if (isDl) Downloads.delete(song) else Downloads.download(song) },
                    )
                }
                DropdownMenuItem(text = { T("Etiketler...", 14.sp) }, onClick = { menu = false; dialog = "tags" })
                if (inPlaylist != null) {
                    DropdownMenuItem(
                        text = { T("Listeden çıkar", 14.sp) },
                        onClick = { menu = false; Engine.removeFromPlaylist(inPlaylist, song) },
                    )
                }
            }
        }
    }
    if (dialog == "picker") PlaylistPicker(song) { dialog = "" }
    if (dialog == "tags") TagDialog(song) { dialog = "" }
}

// ====================================================================== DİYALOGLAR
@Composable
fun PlaylistPicker(song: Song, onDismiss: () -> Unit) {
    val lists by Engine.playlists.collectAsState()
    var newName by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { T("Listeye ekle", 18.sp, Gold, bold = true, serif = true) },
        text = {
            Column {
                LazyColumn(Modifier.heightIn(max = 220.dp)) {
                    items(lists) { pl ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { Engine.addToPlaylist(pl.name, song); onDismiss() }
                                .padding(vertical = 12.dp),
                        ) {
                            T(pl.name, 15.sp, modifier = Modifier.weight(1f))
                            T("${pl.songs.size}", 12.sp, Muted)
                        }
                    }
                }
                TextField(
                    value = newName,
                    onValueChange = { newName = it },
                    placeholder = { T("Yeni liste adı", 14.sp, Muted) },
                    singleLine = true,
                    colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        if (Engine.createPlaylist(newName)) {
                            Engine.addToPlaylist(newName.trim(), song)
                            onDismiss()
                        } else {
                            Engine.say("Bu isimde liste var ya da isim boş")
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                    modifier = Modifier.padding(top = 8.dp),
                ) { T("Oluştur ve ekle", 14.sp, Color.White, bold = true) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { T("Kapat", 14.sp) } },
    )
}

@Composable
fun NewPlaylistDialog(onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var link by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { T("Yeni liste / İçe aktar", 18.sp, Gold, bold = true, serif = true) },
        text = {
            Column {
                T("Boş liste oluştur", 12.sp, Soft)
                TextField(
                    value = name, onValueChange = { name = it }, singleLine = true, colors = fieldColors(),
                    placeholder = { T("Liste adı", 14.sp, Muted) }, modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        if (Engine.createPlaylist(name)) onDismiss() else Engine.say("Bu isimde liste var ya da isim boş")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent),
                ) { T("Oluştur", 14.sp, Color.White, bold = true) }
                Spacer(Modifier.height(14.dp))
                T("YouTube çalma listesi bağlantısı", 12.sp, Soft)
                TextField(
                    value = link, onValueChange = { link = it }, singleLine = true, colors = fieldColors(),
                    placeholder = { T("https://youtube.com/playlist?list=...", 14.sp, Muted) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Button(
                    onClick = {
                        if (link.isNotBlank()) {
                            Engine.importYoutubePlaylist(link)
                            onDismiss()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = ChipBg),
                ) { T("İçe aktar", 14.sp) }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { T("Kapat", 14.sp) } },
    )
}

@Composable
fun TagDialog(song: Song, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf(Engine.tagsFor(song).joinToString(", ")) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Panel,
        title = { T("Etiketler", 18.sp, Gold, bold = true, serif = true) },
        text = {
            Column {
                T("Virgülle ayır (örnek: araba, spor, gece). 'sakin', 'hareketli', 'gece', 'çalışma' etiketleri ruh hali karışıklarına girer.", 12.sp, Soft, maxLines = 4)
                TextField(
                    value = text, onValueChange = { text = it }, singleLine = true, colors = fieldColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { Engine.setTags(song, text.split(",")); onDismiss() }) { T("Kaydet", 14.sp, Accent, bold = true) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { T("Kapat", 14.sp) } },
    )
}

// ====================================================================== SIRADAKİLER
@Composable
fun QueueScreen() {
    val queue by Engine.queue.collectAsState()
    val idx by Engine.index.collectAsState()
    Column(Modifier.fillMaxSize()) {
        PageHeader("Sıradakiler", "${queue.size} şarkı  •  çalan şarkı vurgulu") {}
        if (queue.isEmpty()) {
            T("Kuyruk boş. Bir şarkıyı çalmaya başla ya da ⋮ menüsünden 'Sıraya ekle'.", 14.sp, Muted, modifier = Modifier.padding(24.dp), maxLines = 3)
        } else {
            Box(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp, vertical = 4.dp)
                    .fillMaxWidth()
                    .neonGlass(20.dp, 0.55f)
            ) {
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(vertical = 6.dp)) {
                    itemsIndexed(queue, key = { i, s -> "$i|${s.key}" }) { i, song ->
                        val isCur = i == idx
                        val shape = RoundedCornerShape(14.dp)
                        Row(
                            Modifier
                                .padding(horizontal = 8.dp, vertical = 2.dp)
                                .fillMaxWidth()
                                .clip(shape)
                                .background(if (isCur) Accent.copy(alpha = 0.14f) else Color.Transparent)
                                .then(if (isCur) Modifier.border(1.5.dp, Accent, shape) else Modifier)
                                .clickable { Engine.playIndex(i) }
                                .padding(start = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                                T(song.title, 15.sp, if (isCur) Accent else TextC, bold = isCur)
                                T(song.artist.ifBlank { "—" }, 12.sp, Muted)
                            }
                            IconButton(onClick = { Engine.moveInQueue(i, -1) }) { Icon(Icons.Filled.ArrowUpward, null, tint = Muted) }
                            IconButton(onClick = { Engine.moveInQueue(i, 1) }) { Icon(Icons.Filled.ArrowDownward, null, tint = Muted) }
                            IconButton(onClick = { Engine.removeFromQueue(i) }) { Icon(Icons.Filled.Close, null, tint = Muted) }
                        }
                    }
                }
            }
        }
    }
}

// ====================================================================== ARAMA
object SearchState {
    val lastQuery = MutableStateFlow("")
    val local = MutableStateFlow<List<Song>>(emptyList())
    val online = MutableStateFlow<List<Song>>(emptyList())
    val busy = MutableStateFlow(false)
    val done = MutableStateFlow(false)
    val error = MutableStateFlow("")
    val scope = MutableStateFlow("all")
    val history = MutableStateFlow<List<String>>(emptyList())
    private var job: Job? = null

    fun loadHistory() {
        history.value = Store.getList("searches")
    }

    fun run(q0: String) {
        val q = q0.trim()
        if (q.isEmpty()) return
        lastQuery.value = q
        history.value = (listOf(q) + history.value.filter { !it.equals(q, ignoreCase = true) }).take(8)
        Store.putList("searches", history.value)
        val ql = fold(q)       // Türkçe harfe duyarlı: İ/ı/ş/ğ... fark etmez
        local.value = CATALOG.values.flatten().filter { fold("${it.artist} ${it.title}").contains(ql) }
        online.value = emptyList()
        busy.value = true
        done.value = false
        error.value = ""
        job?.cancel()
        job = Engine.scope.launch {
            try {
                online.value = Extractor.search(q)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                error.value = if (Engine.isOnline()) "İnternetten sonuç alınamadı: ${e.message?.take(60) ?: "hata"}" else "İnternet yok, sadece katalogda arandı"
            }
            busy.value = false
            done.value = true
        }
    }
}

@Composable
fun SearchScreen() {
    val local by SearchState.local.collectAsState()
    val online by SearchState.online.collectAsState()
    val busy by SearchState.busy.collectAsState()
    val done by SearchState.done.collectAsState()
    val error by SearchState.error.collectAsState()
    val scope by SearchState.scope.collectAsState()
    val history by SearchState.history.collectAsState()
    val last by SearchState.lastQuery.collectAsState()
    remember { SearchState.loadHistory() }

    val results = when (scope) {
        "local" -> local
        "online" -> online
        else -> local + online
    }
    val sub = when {
        busy -> "İnternette aranıyor..."
        done && results.isEmpty() -> "Sonuç yok. Başka bir şey dene."
        done -> "${results.size} sonuç"
        else -> "Katalog + YouTube"
    }
    Column(Modifier.fillMaxSize()) {
        PageHeader("Ara", sub) { SearchPill(last) { SearchState.run(it) } }
        ChipRow {
            items(listOf("all" to "Hepsi", "local" to "Katalog", "online" to "YouTube")) { (key, label) ->
                Chip(label, selected = scope == key) { SearchState.scope.value = key }
            }
        }
        if (history.isNotEmpty()) {
            T("SON ARAMALAR", 11.sp, Muted, bold = true, modifier = Modifier.padding(horizontal = 20.dp))
            ChipRow {
                items(history) { q -> Chip(q) { SearchState.run(q) } }
            }
        }
        if (error.isNotEmpty()) T(error, 12.sp, Gold, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp), maxLines = 2)
        SongList(results, Modifier.weight(1f))
    }
}
