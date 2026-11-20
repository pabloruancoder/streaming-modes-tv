package com.arflix.tv.ui.screens.modes

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
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
import com.arflix.tv.data.model.MediaType
import kotlinx.coroutines.delay

/** "Minha lista" no estilo da Netflix para TV: grade de capas. */
@Composable
internal fun NetflixMyListScreen(
    onOpen: (MediaType, Int) -> Unit,
    onBack: () -> Unit,
    extras: NetflixExtrasViewModel = hiltViewModel()
) {
    LaunchedEffect(Unit) { extras.refresh() }
    val items by extras.myList.collectAsStateWithLifecycle()
    var index by rememberSaveable { mutableIntStateOf(0) }
    val cols = 4
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(10) { if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect; delay(100) } }
    val grid = rememberLazyGridState()
    LaunchedEffect(index) { if (items.isNotEmpty()) grid.animateScrollToItem(index.coerceAtMost(items.size - 1)) }

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                val last = (items.size - 1).coerceAtLeast(0)
                when (ev.key) {
                    Key.DirectionLeft -> { if (index % cols > 0) index--; true }
                    Key.DirectionRight -> { index = (index + 1).coerceAtMost(last); true }
                    Key.DirectionUp -> { if (index >= cols) index -= cols; true }
                    Key.DirectionDown -> { index = (index + cols).coerceAtMost(last); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { items.getOrNull(index)?.let { onOpen(it.mediaType, it.id) }; true }
                    Key.Back -> { onBack(); true }
                    else -> false
                }
            }
    ) {
        Column(Modifier.fillMaxSize().padding(start = 64.dp, top = 40.dp, end = 48.dp)) {
            Text("Minha lista", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(16.dp))
            if (items.isEmpty()) {
                Text("Você ainda não adicionou nada. Use \"Minha lista\" na página de um título.",
                    color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp)
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(cols), state = grid, userScrollEnabled = false,
                horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                itemsIndexed(items, key = { _, it -> "${it.mediaType}_${it.id}" }) { i, item ->
                    val focused = i == index
                    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(140), label = "ml")
                    Box(
                        Modifier.scale(scale).fillMaxWidth().height(110.dp).clip(RoundedCornerShape(4.dp))
                            .background(Color(0xFF232323))
                            .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(4.dp))
                    ) {
                        AsyncImage(model = item.backdrop ?: item.image, contentDescription = item.title,
                            contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
                        Text(item.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1,
                            overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
                    }
                }
            }
        }
    }
}
