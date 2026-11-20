package com.arflix.tv.ui.screens.modes

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import kotlinx.coroutines.delay

/**
 * "Minha Netflix" (substitui a "Minha lista" no app da Netflix para TV desde maio/2025): reúne o que você está
 * assistindo e a sua lista, em fileiras.
 */
@Composable
internal fun NetflixMyListScreen(
    onOpen: (MediaType, Int) -> Unit,
    onBack: () -> Unit,
    extras: NetflixExtrasViewModel = hiltViewModel()
) {
    LaunchedEffect(Unit) { extras.refresh() }
    val resume by extras.resume.collectAsStateWithLifecycle()
    val myList by extras.myList.collectAsStateWithLifecycle()
    val rows = buildList {
        if (resume.isNotEmpty()) add(Triple("Continuar assistindo", resume.map { it.item }, resume.associate { "${it.item.mediaType}_${it.item.id}" to it.progress }))
        add(Triple("Minha lista", myList, emptyMap<String, Int>()))
    }
    var rowIndex by rememberSaveable { mutableIntStateOf(0) }
    val cols = remember { mutableStateMapOf<Int, Int>() }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(10) { if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect; delay(100) } }

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                val r = rowIndex.coerceIn(0, rows.size - 1)
                val items = rows[r].second
                val c = (cols[r] ?: 0).coerceIn(0, (items.size - 1).coerceAtLeast(0))
                when (ev.key) {
                    Key.DirectionLeft -> { if (c > 0) cols[r] = c - 1; true }
                    Key.DirectionRight -> { cols[r] = (c + 1).coerceAtMost((items.size - 1).coerceAtLeast(0)); true }
                    Key.DirectionUp -> { rowIndex = (r - 1).coerceAtLeast(0); true }
                    Key.DirectionDown -> { rowIndex = (r + 1).coerceAtMost(rows.size - 1); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { items.getOrNull(c)?.let { onOpen(it.mediaType, it.id) }; true }
                    Key.Back -> { onBack(); true }
                    else -> false
                }
            }
    ) {
        Column(Modifier.fillMaxSize().padding(top = 36.dp)) {
            Row(Modifier.padding(start = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(40.dp).background(Color(0xFF2B6CE0), RoundedCornerShape(6.dp)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Default.Person, contentDescription = null, tint = Color.White, modifier = Modifier.size(28.dp))
                }
                Spacer(Modifier.width(14.dp))
                Text("Minha Netflix", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            }
            Spacer(Modifier.height(24.dp))
            rows.forEachIndexed { r, (title, items, progress) ->
                Text(title, color = Color.White.copy(alpha = if (r == rowIndex) 1f else 0.75f), fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 56.dp, bottom = 6.dp))
                if (items.isEmpty()) {
                    Text("Use \"Minha lista\" na página de um título para guardar aqui.", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp,
                        modifier = Modifier.padding(start = 56.dp, bottom = 20.dp))
                } else {
                    val state = rememberLazyListState()
                    val c = cols[r] ?: 0
                    LaunchedEffect(c) { state.animateScrollToItem(c) }
                    LazyRow(state = state, userScrollEnabled = false, contentPadding = PaddingValues(start = 56.dp, end = 400.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 20.dp)) {
                        itemsIndexed(items, key = { _, it -> "${it.mediaType}_${it.id}" }) { i, item ->
                            MyNetflixCard(item, focused = r == rowIndex && i == c, progress = progress["${item.mediaType}_${item.id}"])
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MyNetflixCard(item: MediaItem, focused: Boolean, progress: Int?) {
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(140), label = "mn")
    Box(
        Modifier.scale(scale).size(width = 176.dp, height = 99.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF232323))
            .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(4.dp))
    ) {
        AsyncImage(model = item.backdrop ?: item.image, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
        Text(item.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 8.dp, bottom = 8.dp))
        if (progress != null && progress > 0) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0xFF5A5A5A))) {
                Box(Modifier.fillMaxWidth(progress / 100f).height(3.dp).background(Color(0xFFE50914)))
            }
        }
    }
}
