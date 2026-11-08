@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.umer.lifeai

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

val Green = Color(0xFF4ADE80); val Bg = Color(0xFF0B0F14); val CardC = Color(0xFF141B23); val Mut = Color(0xFF8B9BAB)
val Amber = Color(0xFFFBBF24); val Red = Color(0xFFF87171); val Blue = Color(0xFF7DD3FC); val Card2 = Color(0xFF1B2530)

fun prioColor(p: Int) = when (p) { 3 -> Red; 1 -> Blue; else -> Amber }

fun fmtTs(ts: String, pattern: String = "d MMM, h:mm a"): String = runCatching {
    OffsetDateTime.parse(ts).atZoneSameInstant(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern(pattern))
}.getOrDefault(ts)

fun fmtMin(m: Long): String = if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
fun fmtSec(s: Long): String = fmtMin(s / 60)

@Composable fun Panel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardC).padding(16.dp), content = content)

@Composable fun Title(t: String, sub: String? = null) {
    Text(t, fontSize = 24.sp, fontWeight = FontWeight.Bold)
    if (sub != null) Text(sub, color = Mut, fontSize = 13.sp)
}

/** Collapsible section. */
@Composable fun Expand(title: String, open0: Boolean = false, content: @Composable ColumnScope.() -> Unit) {
    var open by remember { mutableStateOf(open0) }
    Panel {
        Row(Modifier.fillMaxWidth().clickable { open = !open }, verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
            Icon(if (open) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null, tint = Mut)
        }
        if (open) { Spacer(Modifier.height(8.dp)); content() }
    }
}

/** Returns a function that asks for camera/mic/notification permission and then runs the action. */
@Composable fun rememberPermRunner(): (() -> Unit) -> Unit {
    var after by remember { mutableStateOf<(() -> Unit)?>(null) }
    val l = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r[Manifest.permission.CAMERA] != false) after?.invoke(); after = null
    }
    return { run -> after = run; l.launch(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS)) }
}

// ───────── shared building blocks (one visual language for every screen) ─────────

@Composable fun SectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) =
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), fontSize = 17.sp, fontWeight = FontWeight.Bold)
        if (action != null) Text(action, color = Green, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable { onAction() }.padding(6.dp))
    }

@Composable fun Pill(text: String, color: Color = Green, filled: Boolean = false) =
    Text(text, color = if (filled) Color(0xFF04130A) else color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
        modifier = Modifier.clip(RoundedCornerShape(50)).background(if (filled) color else color.copy(alpha = 0.14f)).padding(horizontal = 9.dp, vertical = 3.dp))

@Composable fun EmptyState(emoji: String, title: String, body: String, cta: String? = null, onCta: () -> Unit = {}) =
    Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(CardC).padding(22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(emoji, fontSize = 34.sp); Spacer(Modifier.height(6.dp))
        Text(title, fontWeight = FontWeight.SemiBold, fontSize = 16.sp, textAlign = TextAlign.Center)
        Text(body, color = Mut, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 4.dp))
        if (cta != null) { Spacer(Modifier.height(12.dp)); Button(onClick = onCta) { Text(cta) } }
    }

/** Circular progress with a centre label. [progress] < 0 = "nothing to measure" (shows a dash, never a fake 0 %). */
@Composable fun ProgressRing(progress: Float, size: Dp = 84.dp, center: String, sub: String? = null, color: Color = Green) =
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize().padding(6.dp)) {
            val w = this.size.minDimension * 0.13f
            drawArc(Card2, 0f, 360f, false, Offset(w / 2, w / 2), Size(this.size.width - w, this.size.height - w), style = Stroke(w))
            if (progress >= 0f) drawArc(color, -90f, 360f * progress.coerceIn(0f, 1f), false, Offset(w / 2, w / 2), Size(this.size.width - w, this.size.height - w), style = Stroke(w, cap = StrokeCap.Round))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(center, fontWeight = FontWeight.Bold, fontSize = 17.sp)
            if (sub != null) Text(sub, color = Mut, fontSize = 10.sp)
        }
    }

@Composable fun StatTile(value: String, label: String, modifier: Modifier = Modifier, color: Color = Color.White) =
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(CardC).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color); Text(label, color = Mut, fontSize = 11.sp)
    }

fun dayLabel(d: java.time.LocalDate): String {
    val t = java.time.LocalDate.now()
    return when (d) { t -> "Today"; t.minusDays(1) -> "Yesterday"; t.plusDays(1) -> "Tomorrow"; else -> d.format(DateTimeFormatter.ofPattern("EEE, d MMM")) }
}
fun actLabel(a: String) = a.replace('_', ' ').replaceFirstChar { it.uppercase() }
fun actEmoji(a: String) = when (a) { "eating" -> "🍽"; "exercising" -> "🏃"; "studying" -> "📚"; "working" -> "💻"; "resting" -> "😌"; "sitting" -> "🪑"; "lying_down" -> "🛏"
    "scrolling_phone" -> "📱"; "entertainment" -> "🎬"; "socialising" -> "🗣"; "commuting" -> "🚗"; "household" -> "🧹"; "budgeting" -> "💰"; "sleeping" -> "😴"; "grooming" -> "🪞"; else -> "•" }
fun clean(s: String?): String? = s?.takeIf { it.isNotBlank() && it != "null" }
