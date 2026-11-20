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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
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
import com.arflix.tv.data.modes.StreamingModes
import kotlinx.coroutines.delay
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * "Novidades e populares" no estilo da Netflix para TV: abas no alto (Novidades, Top 10 séries, Top 10 filmes,
 * Em breve) e, embaixo, a lista grande — imagem, logo/título, data de estreia, sinopse; Top 10 com o número.
 */
@Composable
internal fun NetflixNewPopularScreen(
    onOpen: (MediaType, Int) -> Unit,
    onPlay: (MediaType, Int) -> Unit,
    onBack: () -> Unit,
    viewModel: NetflixNewPopularViewModel = hiltViewModel()
) {
    LaunchedEffect(Unit) { viewModel.load(StreamingModes.NETFLIX) }
    val tabs by viewModel.tabs.collectAsStateWithLifecycle()
    var tabIndex by rememberSaveable { mutableIntStateOf(0) }
    var inTabs by rememberSaveable { mutableStateOf(true) }
    var itemIndex by rememberSaveable { mutableIntStateOf(0) }
    val tab = tabs[tabIndex.coerceIn(0, tabs.size - 1)]
    val items = tab.items

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(10) { if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect; delay(100) } }
    val listState = rememberLazyListState()
    LaunchedEffect(itemIndex, tabIndex) { if (items.isNotEmpty()) listState.animateScrollToItem(itemIndex.coerceAtMost(items.size - 1)) }

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                if (inTabs) when (ev.key) {
                    Key.DirectionLeft -> { tabIndex = (tabIndex - 1).coerceAtLeast(0); itemIndex = 0; true }
                    Key.DirectionRight -> { tabIndex = (tabIndex + 1).coerceAtMost(tabs.size - 1); itemIndex = 0; true }
                    Key.DirectionDown, Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { if (items.isNotEmpty()) inTabs = false; true }
                    Key.Back -> { onBack(); true }
                    else -> false
                } else when (ev.key) {
                    Key.DirectionUp -> { if (itemIndex == 0) inTabs = true else itemIndex--; true }
                    Key.DirectionDown -> { itemIndex = (itemIndex + 1).coerceAtMost(items.size - 1); true }
                    Key.DirectionLeft, Key.DirectionRight -> true
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { items.getOrNull(itemIndex)?.let { onOpen(it.mediaType, it.id) }; true }
                    Key.MediaPlay, Key.MediaPlayPause -> { items.getOrNull(itemIndex)?.let { if (!tab.upcoming) onPlay(it.mediaType, it.id) }; true }
                    Key.Back -> { inTabs = true; true }
                    else -> false
                }
            }
    ) {
        Column(Modifier.fillMaxSize().padding(top = 28.dp)) {
            Text("Novidades e populares", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 56.dp))
            Spacer(Modifier.height(12.dp))
            Row(Modifier.padding(start = 52.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                tabs.forEachIndexed { i, t ->
                    val selected = i == tabIndex
                    val focused = selected && inTabs
                    Box(
                        Modifier
                            .background(
                                when { focused -> Color.White; selected -> Color.White.copy(alpha = 0.2f); else -> Color.Transparent },
                                RoundedCornerShape(50)
                            )
                            .border(1.dp, if (selected) Color.Transparent else Color.White.copy(alpha = 0.35f), RoundedCornerShape(50))
                            .padding(horizontal = 16.dp, vertical = 6.dp)
                    ) {
                        Text(t.label, color = if (focused) Color.Black else Color.White, fontSize = 14.sp,
                            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            when {
                tab.loading && items.isEmpty() -> Text("Carregando…", color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp,
                    modifier = Modifier.padding(start = 56.dp))
                items.isEmpty() -> Text("Nada por aqui agora.", color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp,
                    modifier = Modifier.padding(start = 56.dp))
                else -> LazyColumn(state = listState, userScrollEnabled = false, contentPadding = PaddingValues(bottom = 300.dp)) {
                    itemsIndexed(items, key = { _, it -> "${it.mediaType}_${it.id}" }) { i, item ->
                        NewPopularItem(item, rank = if (tab.ranked) i + 1 else null, upcoming = tab.upcoming,
                            focused = !inTabs && i == itemIndex)
                    }
                }
            }
        }
    }
}

private val dateFmt = DateTimeFormatter.ofPattern("d 'de' MMMM", Locale("pt", "BR"))

@Composable
private fun NewPopularItem(item: MediaItem, rank: Int?, upcoming: Boolean, focused: Boolean) {
    val scale by animateFloatAsState(if (focused) 1.02f else 1f, tween(140), label = "np")
    val date = item.releaseDate?.let { runCatching { LocalDate.parse(it.take(10)) }.getOrNull() }
    Row(
        Modifier
            .fillMaxWidth()
            .scale(scale)
            .padding(start = 48.dp, end = 48.dp, top = 6.dp, bottom = 6.dp)
            .background(if (focused) Color.White.copy(alpha = 0.10f) else Color.Transparent, RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        when {
            rank != null -> Box(Modifier.width(72.dp), contentAlignment = Alignment.Center) {
                Text(rank.toString(), color = Color(0xFF8C8C8C), fontSize = 64.sp, fontWeight = FontWeight.Black,
                    style = TextStyle(drawStyle = Stroke(width = 3f)))
            }
            upcoming && date != null -> Column(Modifier.width(72.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(date.format(DateTimeFormatter.ofPattern("MMM", Locale("pt", "BR"))).uppercase().trimEnd('.'),
                    color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Text(date.dayOfMonth.toString(), color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black)
            }
            else -> Spacer(Modifier.width(8.dp))
        }
        Box(
            Modifier.size(width = 288.dp, height = 162.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFF232323))
                .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(6.dp))
        ) {
            AsyncImage(model = item.backdrop ?: item.image, contentDescription = item.title, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))))
        }
        Spacer(Modifier.width(20.dp))
        Column(Modifier.weight(1f)) {
            if (upcoming) {
                Text(if (date != null) "Estreia em ${date.format(dateFmt)}" else "Em breve na Netflix",
                    color = Color(0xFFE50914), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
            } else if (!upcoming && item.year.isNotBlank()) {
                Text(if (item.mediaType == MediaType.TV) "Série · ${item.year}" else "Filme · ${item.year}",
                    color = Color.White.copy(alpha = 0.6f), fontSize = 12.sp)
                Spacer(Modifier.height(4.dp))
            }
            Text(item.title, color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (item.overview.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(item.overview, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, lineHeight = 18.sp,
                    maxLines = if (focused) 4 else 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 520.dp))
            }
        }
    }
}
