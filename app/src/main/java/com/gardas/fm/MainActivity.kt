package com.gardas.fm

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(AndroidColor.TRANSPARENT),
        )
        setContent {
            val theme by Prefs.theme.collectAsState()
            key(theme) { GardasTheme { AppRoot() } }      // tema değişince arayüz yeni renklerle yeniden kurulur
        }
    }
}

@Composable
fun AppRoot() {
    var route by rememberSaveable { mutableStateOf("home") }
    var showPlayer by rememberSaveable { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showNewList by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    // Android 13+: bildirimdeki çalma kontrolleri için izin iste
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        Updater.check(ctx, false)          // açılışta sessizce yeni sürüm var mı bak
    }

    // kısa mesajlar (Toast)
    val notice by Engine.notice.collectAsState()
    LaunchedEffect(notice.id) {
        if (notice.text.isNotEmpty()) Toast.makeText(ctx, notice.text, Toast.LENGTH_SHORT).show()
    }

    BackHandler(enabled = drawerState.isOpen) { scope.launch { drawerState.close() } }
    BackHandler(enabled = route != "home" && !showPlayer && !drawerState.isOpen) { route = "home" }

    fun go(r: String) {
        route = r
        scope.launch { drawerState.close() }
    }

    fun search(q: String) {
        SearchState.run(q)
        route = "search"
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            DrawerContent(
                route = route,
                onNav = { go(it) },
                onNewList = { scope.launch { drawerState.close() }; showNewList = true },
                onSettings = { scope.launch { drawerState.close() }; showSettings = true },
            )
        },
    ) {
        Box(Modifier.fillMaxSize()) {
            Background()
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = { scope.launch { drawerState.open() } }) {
                        Icon(Icons.Filled.Menu, contentDescription = "Menü", tint = TextC)
                    }
                    T("GARDAŞ FM", 20.sp, Accent, bold = true, serif = true, modifier = Modifier.weight(1f))
                    IconButton(onClick = { route = "search" }) {
                        Icon(Icons.Filled.Search, contentDescription = "Ara", tint = TextC)
                    }
                }
                Box(Modifier.weight(1f)) {
                    val page: @Composable (String) -> Unit = { r ->
                        when {
                            r == "home" -> HomeScreen(onOpen = { route = it }, onSearch = { search(it) })
                            r == "search" -> SearchScreen()
                            r == QUEUE_ROUTE -> QueueScreen()
                            else -> ViewScreen(r, onSearch = { search(it) }, onDeleted = { route = "home" })
                        }
                    }
                    if (Prefs.anim.value == 2) page(route) else Crossfade(targetState = route, label = "page") { page(it) }
                }
                MiniPlayer { showPlayer = true }
            }
            if (showPlayer) {
                NowPlaying(
                    onClose = { showPlayer = false },
                    onOpenQueue = { showPlayer = false; route = QUEUE_ROUTE },
                )
            }
            if (showSettings) SettingsDialog { showSettings = false }
            if (showNewList) NewPlaylistDialog { showNewList = false }
            UpdateDialog()
        }
    }
}

// ====================================================================== KENAR MENÜ (PC'deki sol çubuk)
@Composable
private fun DrawerContent(route: String, onNav: (String) -> Unit, onNewList: () -> Unit, onSettings: () -> Unit) {
    val pls by Engine.playlists.collectAsState()
    val tg by Engine.tagged.collectAsState()
    val tags = remember(tg) { Engine.allTags() }
    ModalDrawerSheet(
        drawerContainerColor = Panel.copy(alpha = 0.97f),
        modifier = Modifier.width(300.dp),
    ) {
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 14.dp, vertical = 18.dp)) {
            T("GARDAŞ FM", 26.sp, Accent, bold = true, serif = true)
            T("gece yayını açık", 12.sp, Muted)
            Spacer(Modifier.height(14.dp))
            DrawerItem("Ana sayfa", route == "home") { onNav("home") }
            DrawerItem("Arama", route == "search") { onNav("search") }
            Spacer(Modifier.height(8.dp))
            CATALOG.keys.forEach { DrawerItem(it, route == it) { onNav(it) } }
            Spacer(Modifier.height(8.dp))
            DrawerItem("♥  $FAV", route == FAV) { onNav(FAV) }
            pls.forEach { DrawerItem(it.name, route == PL_PREFIX + it.name) { onNav(PL_PREFIX + it.name) } }
            DrawerItem("+  Yeni liste / İçe aktar", false) { onNewList() }
            Spacer(Modifier.height(8.dp))
            DrawerItem(DOWNLOADS, route == DOWNLOADS) { onNav(DOWNLOADS) }
            DrawerItem("Sıradakiler", route == QUEUE_ROUTE) { onNav(QUEUE_ROUTE) }
            DrawerItem(RECENT, route == RECENT) { onNav(RECENT) }
            if (tags.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                tags.take(12).forEach { (t, n) -> DrawerItem("#  $t  ($n)", route == "tag:$t") { onNav("tag:$t") } }
            }
            Spacer(Modifier.height(16.dp))
            DrawerItem("Ayarlar ve görünüm", false) { onSettings() }
        }
    }
}

/** Seçili öğe: vurgu renginde neon çerçeve (PC 5.0'daki gibi). */
@Composable
private fun DrawerItem(label: String, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(shape)
            .background(if (selected) Accent.copy(alpha = 0.14f) else Color.Transparent)
            .then(if (selected) Modifier.border(1.5.dp, Accent, shape) else Modifier)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 11.dp),
    ) {
        T(label, 15.sp, if (selected) Gold else TextC, bold = selected)
    }
}

// ====================================================================== ARKA PLAN
private fun softBlur(src: Bitmap, level: Int): Bitmap {
    if (level <= 0) return src
    val f = 1 + level
    return Bitmap.createScaledBitmap(src, maxOf(8, src.width / f), maxOf(8, src.height / f), true)
}

@Composable
fun Background() {
    val path by Prefs.bgPath.collectAsState()
    val dim by Prefs.dim.collectAsState()
    val blur by Prefs.blur.collectAsState()
    val level = (blur / 2f).toInt()
    // bulanıklık bir kere hazırlanır (sürekli bulanıklık efekti çizmek telefonu yavaşlatır)
    val bmp by produceState<ImageBitmap?>(null, path, level) {
        value = if (path.isEmpty()) null else withContext(Dispatchers.IO) {
            decodeScaled(path, 1080)?.let { softBlur(it, level).asImageBitmap() }
        }
    }
    val image = bmp
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            filterQuality = FilterQuality.Medium,
            modifier = Modifier.fillMaxSize(),
        )
    } else {
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(listOf(Color(0xFF0E1430), Color(0xFF080C1E), Color(0xFF05070F)))
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(listOf(Accent.copy(alpha = 0.22f), Color.Transparent), radius = 1000f)
            )
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.radialGradient(
                    listOf(NeonBlue.copy(alpha = 0.16f), Color.Transparent),
                    center = androidx.compose.ui.geometry.Offset(900f, 2000f),
                    radius = 1100f,
                )
            )
        )
    }
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = dim)))
}

// ====================================================================== GÜNCELLEME PENCERESİ
@Composable
private fun UpdateDialog() {
    val ctx = LocalContext.current
    val upd by Updater.available.collectAsState()
    val prog by Updater.progress.collectAsState()
    val info = upd ?: return
    AlertDialog(
        onDismissRequest = { if (prog < 0) Updater.available.value = null },
        containerColor = Panel,
        title = { T("Yeni sürüm: ${info.tag}", 18.sp, Gold, bold = true, serif = true) },
        text = {
            Column {
                T(info.notes.ifBlank { "Yeni özellikler ve düzeltmeler var." }, 13.sp, Soft, maxLines = 12)
                if (prog >= 0) {
                    Spacer(Modifier.height(12.dp))
                    LinearProgressIndicator(progress = { prog / 100f }, modifier = Modifier.fillMaxWidth(), color = Accent)
                    T("%$prog indiriliyor...", 12.sp, Muted)
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { Updater.downloadAndInstall(ctx, info) },
                enabled = prog < 0,
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
            ) { T(if (prog >= 0) "İniyor..." else "Güncelle", 14.sp, Color.White, bold = true) }
        },
        dismissButton = {
            TextButton(onClick = { Updater.available.value = null }, enabled = prog < 0) { T("Sonra", 14.sp) }
        },
    )
}

// ====================================================================== AYARLAR
@Composable
fun SettingsDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val dim by Prefs.dim.collectAsState()
    val blur by Prefs.blur.collectAsState()
    val theme by Prefs.theme.collectAsState()
    val anim by Prefs.anim.collectAsState()
    val checking by Updater.checking.collectAsState()
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) Prefs.setBackground(ctx, uri)
    }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) Engine.importFolder(ctx, uri)
    }
    val colors = SliderDefaults.colors(thumbColor = Gold, activeTrackColor = Accent, inactiveTrackColor = ChipBg)
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Column(
            Modifier
                .padding(20.dp)
                .fillMaxWidth()
                .neonGlass(24.dp, 0.96f, strong = true)
                .verticalScroll(rememberScrollState())
                .padding(20.dp)
        ) {
            T("Ayarlar ve görünüm", 21.sp, Gold, bold = true, serif = true)
            Spacer(Modifier.height(12.dp))
            Button(
                onClick = { picker.launch("image/*") },
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                modifier = Modifier.fillMaxWidth(),
            ) { T("Arka plan fotoğrafı seç...", 15.sp, Color.White, bold = true) }
            OutlinedButton(onClick = { Prefs.clearBackground() }, modifier = Modifier.fillMaxWidth()) {
                T("Fotoğrafı kaldır", 14.sp)
            }
            Spacer(Modifier.height(10.dp))
            T("Karartma (yazılar okunsun diye)", 13.sp, Soft)
            Slider(value = dim, onValueChange = { Prefs.setDim(it) }, valueRange = 0f..0.85f, colors = colors)
            T("Bulanıklık", 13.sp, Soft)
            Slider(value = blur, onValueChange = { Prefs.setBlur(it) }, valueRange = 0f..24f, colors = colors)

            Spacer(Modifier.height(8.dp))
            T("Tema", 13.sp, Soft)
            LazyRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                itemsIndexed(Themes.names) { i, name -> Chip(name, selected = theme == i) { Prefs.setTheme(i) } }
            }
            T("Animasyonlar", 13.sp, Soft)
            LazyRow(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 6.dp)) {
                itemsIndexed(Themes.animNames) { i, name -> Chip(name, selected = anim == i) { Prefs.setAnim(i) } }
            }

            Spacer(Modifier.height(10.dp))
            OutlinedButton(onClick = { folder.launch(null) }, modifier = Modifier.fillMaxWidth()) {
                T("Telefondaki bir klasörden müzik ekle", 14.sp)
            }
            Spacer(Modifier.height(6.dp))
            OutlinedButton(
                onClick = { Updater.check(ctx, true) },
                enabled = !checking,
                modifier = Modifier.fillMaxWidth(),
            ) { T(if (checking) "Denetleniyor..." else "Güncellemeleri denetle", 14.sp) }
            T("Sürüm ${Updater.currentName(ctx)}", 11.sp, Muted, modifier = Modifier.padding(top = 6.dp))
            Spacer(Modifier.height(10.dp))
            Button(
                onClick = onClose,
                colors = ButtonDefaults.buttonColors(containerColor = ChipBg),
                modifier = Modifier.align(Alignment.End),
            ) { T("Tamam", 14.sp) }
        }
    }
}
