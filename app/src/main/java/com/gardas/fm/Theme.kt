package com.gardas.fm

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import kotlinx.coroutines.delay

/** PC 5.0'daki 9 tema: (vurgu rengi, ikinci renk). */
object Themes {
    val names = listOf(
        "Gece İstanbul", "Okyanus", "Orman", "Gün batımı", "Mor sis",
        "Neon Siber", "Kırmızı Kadife", "Gece Mavisi", "Altın Saat",
    )
    val accents = listOf(
        0xFFFF4F8B, 0xFF2EC4FF, 0xFF3DDC97, 0xFFFF7A45, 0xFFA78BFA,
        0xFF00E5FF, 0xFFFF3D57, 0xFF5B8CFF, 0xFFFFB020,
    ).map { Color(it) }
    val golds = listOf(
        0xFFFFC857, 0xFFFFE066, 0xFFFFD166, 0xFFFFD23F, 0xFFF9A8D4,
        0xFFFF4FD8, 0xFFFFB86B, 0xFF7EE0FF, 0xFFFF7A7A,
    ).map { Color(it) }
    val animNames = listOf("Tam", "Hafif", "Kapalı")
}

val Accent: Color get() = Themes.accents[Prefs.theme.value.coerceIn(0, Themes.accents.lastIndex)]
val Gold: Color get() = Themes.golds[Prefs.theme.value.coerceIn(0, Themes.golds.lastIndex)]
val NeonBlue = Color(0xFF4F9BFF)
val TextC = Color(0xFFECE9FF)
val Soft = Color(0xFFCFC9F5)
val Muted = Color(0xFF9AA3C8)
val Panel = Color(0xFF0B1026)
val ChipBg = Color(0xFF1B2347)

@Composable
fun GardasTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Accent,
            secondary = Gold,
            surface = Panel,
            onSurface = TextC,
            onPrimary = Color.White,
        ),
        content = content,
    )
}

/** Neon kenarlıklı cam panel (PC 5.0 görünümü): yarı saydam koyu zemin + vurgu renginden maviye geçen kenar çizgisi. */
fun Modifier.neonGlass(radius: Dp = 22.dp, alpha: Float = 0.6f, strong: Boolean = false): Modifier {
    val shape = RoundedCornerShape(radius)
    val brush = Brush.linearGradient(
        listOf(
            Accent.copy(alpha = if (strong) 0.95f else 0.55f),
            NeonBlue.copy(alpha = if (strong) 0.80f else 0.32f),
        )
    )
    return this
        .clip(shape)
        .background(Panel.copy(alpha = alpha))
        .border(if (strong) 1.6.dp else 1.dp, brush, shape)
}

/** Kısa yazı bileşeni. */
@Composable
fun T(
    text: String,
    size: TextUnit = 14.sp,
    color: Color = TextC,
    bold: Boolean = false,
    serif: Boolean = false,
    modifier: Modifier = Modifier,
    maxLines: Int = 1,
    align: TextAlign? = null,
) {
    Text(
        text = text,
        modifier = modifier,
        color = color,
        fontSize = size,
        fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal,
        fontFamily = if (serif) FontFamily.Serif else FontFamily.Default,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        textAlign = align,
    )
}

/** Eylem düğmesi çipi: primary = dolu vurgu rengi, selected = vurgu kenarlıklı. */
@Composable
fun Chip(
    label: String,
    selected: Boolean = false,
    primary: Boolean = false,
    icon: ImageVector? = null,
    onClick: () -> Unit,
) {
    val shape = RoundedCornerShape(50)
    val fill = when {
        primary -> Accent
        selected -> Accent.copy(alpha = 0.25f)
        else -> Panel.copy(alpha = 0.55f)
    }
    val line = if (primary) Accent else if (selected) Accent else Color.White.copy(alpha = 0.22f)
    Row(
        Modifier
            .clip(shape)
            .background(fill)
            .border(1.dp, line, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.height(16.dp).width(16.dp))
        T(label, 13.sp, if (primary || selected) Color.White else TextC, bold = primary || selected)
    }
}

/** Şarkı kapağı: YouTube küçük resmi (siyah bantlar kırpılır), yoksa nota simgesi. */
@Composable
fun Cover(videoId: String?, modifier: Modifier = Modifier, radius: Dp = 12.dp) {
    Box(
        modifier
            .clip(RoundedCornerShape(radius))
            .background(ChipBg),
        contentAlignment = Alignment.Center,
    ) {
        if (videoId != null) {
            AsyncImage(
                model = "https://i.ytimg.com/vi/$videoId/hqdefault.jpg",
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .graphicsLayer {
                        scaleX = 1.34f
                        scaleY = 1.34f
                    },
            )
        } else {
            Icon(Icons.Filled.MusicNote, contentDescription = null, tint = Accent)
        }
    }
}

fun fmtSec(sec: Long): String {
    val s = sec.coerceAtLeast(0)
    return "${s / 60}:${(s % 60).toString().padStart(2, '0')}"
}

fun fmtTotal(sec: Long): String {
    val m = sec / 60
    val h = m / 60
    return if (h > 0) "$h sa ${m % 60} dk" else "$m dk"
}

// ====================================================================== ANİMASYONLAR (Tam / Hafif / Kapalı)
/** Sayfa girişinde kartlar süzülerek belirir. Animasyon "Kapalı" ise hiçbir şey yapmaz. */
@Composable
fun Modifier.appear(delayMs: Int = 0): Modifier {
    val level = Prefs.anim.value
    if (level == 2) return this
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(if (level == 1) 0L else delayMs.toLong())
        shown = true
    }
    val a by animateFloatAsState(if (shown) 1f else 0f, tween(if (level == 1) 220 else 420), label = "appear")
    return this.graphicsLayer {
        alpha = a
        translationY = (1f - a) * 36f
    }
}

/** Şarkı çalarken dans eden ses çubukları. */
@Composable
fun EqBars(active: Boolean, modifier: Modifier = Modifier, color: Color = Accent) {
    val animate = active && Prefs.anim.value != 2
    val t = rememberInfiniteTransition(label = "eq")
    val durations = listOf(420, 580, 360, 500)
    val hs = durations.map { d ->
        t.animateFloat(0.25f, 1f, infiniteRepeatable(tween(d, easing = LinearEasing), RepeatMode.Reverse), label = "bar")
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        hs.forEach { h ->
            Box(
                Modifier
                    .width(3.dp)
                    .height(if (animate) (16 * h.value).dp else 4.dp)
                    .background(color)
            )
        }
    }
}

/** Kapağın etrafında genişleyip sönen dalga halkaları (çalarken). */
@Composable
fun PulseRings(active: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val animate = active && Prefs.anim.value == 0
    val t = rememberInfiniteTransition(label = "ring")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(2600, easing = LinearEasing)), label = "p")
    Box(modifier, contentAlignment = Alignment.Center) {
        if (animate) {
            Canvas(Modifier.matchParentSize()) {
                val base = minOf(size.width, size.height) / 2
                for (i in 0..1) {
                    val ph = (p + i * 0.5f) % 1f
                    drawCircle(
                        color = Accent.copy(alpha = (1f - ph) * 0.40f),
                        radius = base * (0.80f + 0.20f * ph),
                        center = center,
                        style = Stroke(width = 3f),
                    )
                }
            }
        }
        content()
    }
}
