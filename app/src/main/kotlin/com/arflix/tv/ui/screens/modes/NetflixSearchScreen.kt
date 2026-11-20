package com.arflix.tv.ui.screens.modes

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
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
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.key.utf16CodePoint
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.ui.screens.search.SearchViewModel
import kotlinx.coroutines.delay

/** Teclas do teclado da Netflix na TV: espaço e apagar em cima, letras e números em 6 colunas. */
private const val KB_COLS = 6
private val KB_KEYS: List<String> = listOf("␣", "⌫") + ('a'..'z').map { it.toString() } + (1..9).map { it.toString() } + listOf("0")

/**
 * Busca no layout da Netflix para TV: teclado à esquerda, resultados em grade à direita.
 * Também aceita digitação por teclado físico/controle com teclado.
 */
@Composable
internal fun NetflixSearchScreen(
    onOpen: (MediaType, Int) -> Unit,
    onBack: () -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf(state.query) }
    var inResults by rememberSaveable { mutableStateOf(false) }
    // Linha 0 = [espaço, apagar]; depois as teclas em 6 colunas.
    var keyIndex by rememberSaveable { mutableIntStateOf(2) }
    var resultIndex by rememberSaveable { mutableIntStateOf(0) }
    val results = state.results.filter { it.mediaType == MediaType.MOVIE || it.mediaType == MediaType.TV }
    val cols = 3

    fun setQuery(q: String) { query = q; viewModel.updateQuery(q) }
    fun press(k: String) = when (k) {
        "␣" -> setQuery("$query ")
        "⌫" -> setQuery(query.dropLast(1))
        else -> setQuery(query + k)
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(10) { if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect; delay(100) } }
    val gridState = rememberLazyGridState()
    LaunchedEffect(resultIndex, inResults) { if (inResults && results.isNotEmpty()) gridState.animateScrollToItem(resultIndex.coerceAtMost(results.size - 1)) }

    // Teclas: índice 0 e 1 ficam na linha de cima (cada uma ocupa 3 colunas).
    fun rowOf(i: Int) = if (i < 2) 0 else 1 + (i - 2) / KB_COLS
    fun colOf(i: Int) = if (i < 2) i * 3 else (i - 2) % KB_COLS

    Box(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                // Teclado físico: letras/números vão direto para a busca.
                val cp = ev.utf16CodePoint
                if (cp in 32..126 && ev.key != Key.DirectionCenter && ev.key != Key.Enter) {
                    val c = cp.toChar().lowercaseChar()
                    if (c.isLetterOrDigit() || c == ' ') { setQuery(query + c); return@onPreviewKeyEvent true }
                }
                if (ev.key == Key.Backspace || ev.key == Key.Delete) { press("⌫"); return@onPreviewKeyEvent true }
                if (!inResults) when (ev.key) {
                    Key.DirectionLeft -> {
                        if (keyIndex == 1) keyIndex = 0 else if (keyIndex >= 2 && colOf(keyIndex) > 0) keyIndex--
                        true
                    }
                    Key.DirectionRight -> {
                        when {
                            keyIndex == 0 -> keyIndex = 1
                            keyIndex == 1 || (keyIndex >= 2 && colOf(keyIndex) == KB_COLS - 1) || keyIndex == KB_KEYS.size - 1 ->
                                if (results.isNotEmpty()) { inResults = true; resultIndex = resultIndex.coerceAtMost(results.size - 1) }
                            else -> keyIndex++
                        }
                        true
                    }
                    Key.DirectionUp -> {
                        keyIndex = when {
                            keyIndex < 2 -> keyIndex
                            rowOf(keyIndex) == 1 -> if (colOf(keyIndex) < 3) 0 else 1
                            else -> keyIndex - KB_COLS
                        }
                        true
                    }
                    Key.DirectionDown -> {
                        keyIndex = when {
                            keyIndex == 0 -> 2
                            keyIndex == 1 -> 5
                            keyIndex + KB_COLS < KB_KEYS.size -> keyIndex + KB_COLS
                            else -> keyIndex
                        }
                        true
                    }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { press(KB_KEYS[keyIndex]); true }
                    Key.Back -> { if (query.isNotEmpty()) press("⌫") else onBack(); true }
                    else -> false
                } else when (ev.key) {
                    Key.DirectionLeft -> { if (resultIndex % cols == 0) inResults = false else resultIndex--; true }
                    Key.DirectionRight -> { resultIndex = (resultIndex + 1).coerceAtMost(results.size - 1); true }
                    Key.DirectionUp -> { if (resultIndex >= cols) resultIndex -= cols; true }
                    Key.DirectionDown -> { resultIndex = (resultIndex + cols).coerceAtMost(results.size - 1); true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { results.getOrNull(resultIndex)?.let { onOpen(it.mediaType, it.id) }; true }
                    Key.Back -> { inResults = false; true }
                    else -> false
                }
            }
    ) {
        Row(Modifier.fillMaxSize().padding(start = 64.dp, top = 40.dp, end = 32.dp)) {
            // Teclado
            Column(Modifier.width(300.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(query.ifEmpty { "Buscar" }, color = if (query.isEmpty()) Color.White.copy(alpha = 0.5f) else Color.White,
                        fontSize = 20.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.3f)))
                Spacer(Modifier.height(14.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    KbKey("espaço", focused = !inResults && keyIndex == 0, wide = true)
                    KbKey("apagar", focused = !inResults && keyIndex == 1, wide = true)
                }
                Spacer(Modifier.height(4.dp))
                KB_KEYS.drop(2).chunked(KB_COLS).forEachIndexed { r, rowKeys ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        rowKeys.forEachIndexed { c, k -> KbKey(k, focused = !inResults && keyIndex == 2 + r * KB_COLS + c) }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
            Spacer(Modifier.width(36.dp))
            // Resultados
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        query.isBlank() -> "Digite o nome de um filme, série ou pessoa"
                        state.isLoading && results.isEmpty() -> "Buscando…"
                        results.isEmpty() -> "Nada encontrado para \"$query\""
                        else -> "Filmes e séries"
                    },
                    color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp, fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(10.dp))
                LazyVerticalGrid(
                    columns = GridCells.Fixed(cols), state = gridState, userScrollEnabled = false,
                    horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    itemsIndexed(results, key = { _, it -> "${it.mediaType}_${it.id}" }) { i, item ->
                        SearchCard(item, state.cardLogoUrls["${item.mediaType}_${item.id}"], focused = inResults && i == resultIndex)
                    }
                }
            }
        }
    }
}

@Composable
private fun KbKey(label: String, focused: Boolean, wide: Boolean = false) {
    Box(
        Modifier
            .size(width = if (wide) 146.dp else 46.dp, height = 40.dp)
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.08f), RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (focused) Color.Black else Color.White, fontSize = if (wide) 14.sp else 17.sp,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SearchCard(item: MediaItem, logo: String?, focused: Boolean) {
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(140), label = "search")
    Box(
        Modifier
            .scale(scale)
            .fillMaxWidth()
            .height(112.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF232323))
            .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(4.dp))
    ) {
        AsyncImage(model = item.backdrop ?: item.image, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
        if (!logo.isNullOrBlank()) {
            AsyncImage(model = logo, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 16.dp, vertical = 6.dp).height(32.dp).fillMaxWidth())
        } else Text(item.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
    }
}
