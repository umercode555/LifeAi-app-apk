@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class, androidx.compose.foundation.ExperimentalFoundationApi::class)
package com.umer.lifeai

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

private fun dayOf(j: JSONObject): LocalDate = runCatching { OffsetDateTime.parse(j.getString("captured_at")).atZoneSameInstant(ZoneId.systemDefault()).toLocalDate() }.getOrDefault(LocalDate.now())
private fun shownOnPurpose(j: JSONObject) = j.optString("source") in setOf("chat", "manual", "shake")

/** Memories: every photo, grouped by day, filterable by what you were doing. Tap to swipe through full-screen. */
@Composable
fun PhotosScreen() {
    var all by remember { mutableStateOf<List<JSONObject>?>(null) }; var err by remember { mutableStateOf<String?>(null) }
    var filter by remember { mutableStateOf("all") }
    var viewer by remember { mutableStateOf<Pair<List<JSONObject>, Int>?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { val a = Sb.get("images?storage_path=not.is.null&order=captured_at.desc&limit=600"); all = List(a.length()) { a.getJSONObject(it) }; err = null }.onFailure { err = it.message }
            delay(30_000)
        }
    }
    val list = all
    val top = remember(list) { list.orEmpty().mapNotNull { clean(it.optString("activity")) }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(6).map { it.key } }
    val shown = list.orEmpty().filter { when (filter) { "all" -> true; "me" -> shownOnPurpose(it); else -> it.optString("activity") == filter } }
    val byDay = shown.groupBy { dayOf(it) }.toSortedMap(compareByDescending { it })

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp)) {
            Text("Memories", fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text(if (list == null) "Loading…" else "${shown.size} photos your AI has taken or been shown", color = Mut, fontSize = 13.sp)
            err?.let { Text(it, color = Red, fontSize = 12.sp) }
            Row(Modifier.padding(vertical = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(filter == "all", { filter = "all" }, { Text("All") })
                if (list.orEmpty().any { shownOnPurpose(it) }) FilterChip(filter == "me", { filter = "me" }, { Text("📷 Shown to AI") })
                top.forEach { a -> FilterChip(filter == a, { filter = a }, { Text("${actEmoji(a)} ${actLabel(a)}") }) }
            }
        }
        if (list != null && shown.isEmpty()) Box(Modifier.padding(16.dp)) { EmptyState("🖼", "No photos yet", "Turn on Watching on the Today tab, or tap “Show me” in Coach. Your photos will be grouped by day here.") }
        else LazyVerticalGrid(GridCells.Fixed(3), Modifier.fillMaxSize(), contentPadding = PaddingValues(start = 12.dp, end = 12.dp, bottom = 16.dp), horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            byDay.forEach { (day, photos) ->
                item(span = { GridItemSpan(maxLineSpan) }) {
                    Row(Modifier.padding(top = 14.dp, bottom = 4.dp, start = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(dayLabel(day), fontWeight = FontWeight.Bold, fontSize = 15.sp); Spacer(Modifier.width(8.dp)); Text("${photos.size}", color = Mut, fontSize = 13.sp)
                    }
                }
                items(photos.size) { i ->
                    val j = photos[i]; val p = clean(j.optString("storage_path")); val act = clean(j.optString("activity"))
                    Box(Modifier.aspectRatio(1f).clip(RoundedCornerShape(8.dp)).background(Card2).clickable { viewer = shown to shown.indexOf(j).coerceAtLeast(0) }) {
                        if (p != null) AsyncImage(Sb.publicUrl(p), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) else Icon(Icons.Default.BrokenImage, null, Modifier.align(Alignment.Center), tint = Mut)
                        Text(fmtTs(j.optString("captured_at"), "h:mm a"), Modifier.align(Alignment.BottomStart).background(Color(0x99000000), RoundedCornerShape(topEnd = 8.dp)).padding(horizontal = 5.dp, vertical = 1.dp), fontSize = 10.sp)
                        if (act != null) Text(actEmoji(act), Modifier.align(Alignment.TopEnd).padding(4.dp), fontSize = 14.sp)
                    }
                }
            }
        }
    }
    viewer?.let { (l, i) -> PhotoViewer(l, i) { viewer = null } }
}

/** Full-screen swipeable viewer with the AI's caption. Shared by Memories and the Calendar. */
@Composable
fun PhotoViewer(photos: List<JSONObject>, start: Int, onClose: () -> Unit) {
    if (photos.isEmpty()) { onClose(); return }
    Dialog(onClose, DialogProperties(usePlatformDefaultWidth = false)) {
        val pager = rememberPagerState(initialPage = start.coerceIn(0, photos.size - 1)) { photos.size }
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                val j = photos[page]; val p = clean(j.optString("storage_path"))
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                        if (p != null) AsyncImage(Sb.publicUrl(p), null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) else Icon(Icons.Default.BrokenImage, null, tint = Mut)
                    }
                    Column(Modifier.fillMaxWidth().background(CardC).padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(fmtTs(j.optString("captured_at"), "EEE d MMM · h:mm a"), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                            clean(j.optString("activity"))?.let { Pill("${actEmoji(it)} ${actLabel(it)}") }
                        }
                        Text(clean(j.optString("interpretation")) ?: clean(j.optString("error")) ?: "Not analysed yet", fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp))
                        val tags = j.optJSONArray("tags")?.let { a -> List(a.length()) { a.optString(it) } }.orEmpty().filter { it.isNotBlank() }
                        if (tags.isNotEmpty()) Row(Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) { tags.forEach { Pill(it, Blue) } }
                        Text("${j.optString("camera")} camera · ${j.optString("source")}", color = Mut, fontSize = 11.sp, modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
            IconButton(onClose, Modifier.align(Alignment.TopStart).statusBarsPadding().padding(8.dp)) { Icon(Icons.Default.Close, "Close", tint = Color.White) }
            Text("${pager.currentPage + 1} / ${photos.size}", Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(16.dp), color = Color.White, fontSize = 13.sp)
        }
    }
}
