package com.arflix.tv.ui.screens.modes

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.arflix.tv.data.model.MediaType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Teclas do teclado da Netflix na TV: espaço e apagar em cima, letras e números em 6 colunas. */
private const val KB_COLS = 6
private val KB_KEYS: List<String> = listOf("␣", "⌫") + ('a'..'z').map { it.toString() } + (1..9).map { it.toString() } + listOf("0")
private const val COLS = 3

private enum class Zone { KEYS, TERMS, GRID }

/** Linha da grade: título de um bloco ou até 3 capas. */
private sealed class NSLine {
    data class Header(val title: String, val key: String) : NSLine()
    data class Cards(val section: Int, val start: Int, val items: List<NSItem>) : NSLine()
}

/**
 * Busca no layout da Netflix para TV, com a lógica do plugin do PC:
 * teclado | categorias (viram sugestões enquanto digita) | capas.
 * Sem texto: feed infinito "Recomendados para você" em blocos. Com texto: resultados que carregam mais ao descer.
 */
@Composable
internal fun NetflixSearchScreen(
    onOpen: (MediaType, Int) -> Unit,
    onBack: () -> Unit,
    viewModel: NetflixSearchViewModel = hiltViewModel(),
    extras: NetflixExtrasViewModel = hiltViewModel()
) {
    val sections by viewModel.sections.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val loadingMore by viewModel.loadingMore.collectAsStateWithLifecycle()
    val ended by viewModel.ended.collectAsStateWithLifecycle()
    val noResults by viewModel.noResults.collectAsStateWithLifecycle()
    val offline by viewModel.offline.collectAsStateWithLifecycle()
    val termsLabel by viewModel.termsLabel.collectAsStateWithLifecycle()
    val terms by viewModel.terms.collectAsStateWithLifecycle()
    val logos by viewModel.logos.collectAsStateWithLifecycle()

    // Sementes do feed: o que a pessoa assistiu por último (senão, a Minha lista).
    LaunchedEffect(Unit) {
        extras.refresh()
        val resume = withTimeoutOrNull(3_000) { extras.resume.first { it.isNotEmpty() } }.orEmpty().map { it.item }
        val seeds = resume.ifEmpty { extras.myList.value }
            .filter { it.mediaType == MediaType.MOVIE || it.mediaType == MediaType.TV }
            .map { NSSeed(it.mediaType, it.id, it.title) }
        viewModel.start(seeds)
    }

    var query by rememberSaveable { mutableStateOf("") }
    var zone by rememberSaveable { mutableStateOf(Zone.KEYS) }
    // Linha 0 = [espaço, apagar]; depois as teclas em 6 colunas.
    var keyIndex by rememberSaveable { mutableIntStateOf(2) }
    var termIndex by rememberSaveable { mutableIntStateOf(0) }
    var secIndex by rememberSaveable { mutableIntStateOf(0) }
    var itemIndex by rememberSaveable { mutableIntStateOf(0) }

    val lines = remember(sections) {
        buildList {
            sections.forEachIndexed { s, sec ->
                add(NSLine.Header(sec.title, sec.key))
                sec.items.chunked(COLS).forEachIndexed { r, chunk -> add(NSLine.Cards(s, r * COLS, chunk)) }
            }
        }
    }
    fun lineOf(s: Int, i: Int) = lines.indexOfFirst { it is NSLine.Cards && it.section == s && i >= it.start && i < it.start + COLS }
    val hasItems = sections.any { it.items.isNotEmpty() }

    val listState = rememberLazyListState()
    // Lista mudou (nova busca): volta para o começo.
    LaunchedEffect(sections.firstOrNull()?.key, sections.firstOrNull()?.title) {
        secIndex = 0; itemIndex = 0
        if (zone == Zone.GRID && !hasItems) zone = Zone.KEYS
        runCatching { listState.scrollToItem(0) }
    }
    LaunchedEffect(terms) { termIndex = termIndex.coerceIn(0, (terms.size - 1).coerceAtLeast(0)); if (zone == Zone.TERMS && terms.isEmpty()) zone = Zone.KEYS }

    fun setQuery(q: String) { query = q; viewModel.setQuery(q) }
    fun press(k: String) = when (k) {
        "␣" -> if (query.isNotEmpty() && !query.endsWith(" ")) setQuery("$query ") else Unit
        "⌫" -> setQuery(query.dropLast(1))
        else -> setQuery(query + k)
    }
    fun enterGrid() {
        if (!hasItems) return
        zone = Zone.GRID
        secIndex = secIndex.coerceIn(0, sections.size - 1)
        itemIndex = itemIndex.coerceIn(0, (sections[secIndex].items.size - 1).coerceAtLeast(0))
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(10) { if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect; delay(100) } }
    val focusLine = if (zone == Zone.GRID) lineOf(secIndex, itemIndex) else -1
    LaunchedEffect(focusLine) {
        if (focusLine < 0) return@LaunchedEffect
        val target = if (lines.getOrNull(focusLine - 1) is NSLine.Header) focusLine - 1 else focusLine
        listState.animateScrollToItem(target)
    }
    // Rolagem infinita: carrega mais quando o foco chega perto do fim (ou quando a lista ainda é curta).
    LaunchedEffect(focusLine, lines.size, loading) {
        if (!loading && (lines.size < 8 || (focusLine >= 0 && focusLine >= lines.size - 4))) viewModel.loadMore()
    }

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
                when (zone) {
                    Zone.KEYS -> when (ev.key) {
                        Key.DirectionLeft -> { if (keyIndex == 1) keyIndex = 0 else if (keyIndex >= 2 && colOf(keyIndex) > 0) keyIndex--; true }
                        Key.DirectionRight -> {
                            when {
                                keyIndex == 0 -> keyIndex = 1
                                keyIndex == 1 || (keyIndex >= 2 && colOf(keyIndex) == KB_COLS - 1) || keyIndex == KB_KEYS.size - 1 ->
                                    if (terms.isNotEmpty()) zone = Zone.TERMS else enterGrid()
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
                    }
                    Zone.TERMS -> when (ev.key) {
                        Key.DirectionUp -> { if (termIndex > 0) termIndex--; true }
                        Key.DirectionDown -> { if (termIndex < terms.size - 1) termIndex++; true }
                        Key.DirectionLeft -> { zone = Zone.KEYS; true }
                        Key.DirectionRight -> { enterGrid(); true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { terms.getOrNull(termIndex)?.let { setQuery(it) }; true }
                        Key.Back -> { zone = Zone.KEYS; true }
                        else -> false
                    }
                    Zone.GRID -> {
                        val items = sections.getOrNull(secIndex)?.items.orEmpty()
                        when (ev.key) {
                            Key.DirectionLeft -> {
                                if (itemIndex % COLS == 0) zone = if (terms.isNotEmpty()) Zone.TERMS else Zone.KEYS else itemIndex--
                                true
                            }
                            Key.DirectionRight -> { if (itemIndex % COLS < COLS - 1 && itemIndex + 1 < items.size) itemIndex++; true }
                            Key.DirectionUp -> {
                                if (itemIndex >= COLS) itemIndex -= COLS
                                else if (secIndex > 0) {
                                    val prev = sections[secIndex - 1].items
                                    val lastRow = (prev.size - 1) / COLS * COLS
                                    secIndex--; itemIndex = (lastRow + itemIndex % COLS).coerceAtMost(prev.size - 1)
                                }
                                true
                            }
                            Key.DirectionDown -> {
                                val col = itemIndex % COLS
                                when {
                                    itemIndex + COLS < items.size -> itemIndex += COLS
                                    (itemIndex / COLS) < (items.size - 1) / COLS -> itemIndex = items.size - 1
                                    secIndex < sections.size - 1 -> {
                                        secIndex++; itemIndex = col.coerceAtMost(sections[secIndex].items.size - 1)
                                    }
                                    else -> viewModel.loadMore()
                                }
                                true
                            }
                            Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { items.getOrNull(itemIndex)?.let { onOpen(it.type, it.id) }; true }
                            Key.Back -> { zone = Zone.KEYS; true }
                            else -> false
                        }
                    }
                }
            }
    ) {
        Row(Modifier.fillMaxSize().padding(start = 44.dp, top = 32.dp, end = 24.dp)) {
            // Teclado
            Column(Modifier.width(250.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(query.ifEmpty { "Buscar" }, color = if (query.isEmpty()) Color.White.copy(alpha = 0.5f) else Color.White,
                        fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = 0.3f)))
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    KbKey("espaço", focused = zone == Zone.KEYS && keyIndex == 0, wide = true)
                    KbKey("apagar", focused = zone == Zone.KEYS && keyIndex == 1, wide = true)
                }
                Spacer(Modifier.height(4.dp))
                KB_KEYS.drop(2).chunked(KB_COLS).forEachIndexed { r, rowKeys ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        rowKeys.forEachIndexed { c, k -> KbKey(k, focused = zone == Zone.KEYS && keyIndex == 2 + r * KB_COLS + c) }
                    }
                    Spacer(Modifier.height(4.dp))
                }
            }
            Spacer(Modifier.width(18.dp))
            // Categorias / sugestões
            Column(Modifier.width(150.dp)) {
                if (termsLabel.isNotEmpty()) {
                    Text(termsLabel, color = Color.White.copy(alpha = 0.5f), fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                }
                val termsState = rememberLazyListState()
                LaunchedEffect(termIndex, zone) { if (zone == Zone.TERMS) termsState.animateScrollToItem((termIndex - 3).coerceAtLeast(0)) }
                LazyColumn(state = termsState, userScrollEnabled = false) {
                    itemsIndexed(terms, key = { _, t -> t }) { i, t -> TermItem(t, query, focused = zone == Zone.TERMS && i == termIndex) }
                }
            }
            Spacer(Modifier.width(18.dp))
            // Capas
            Box(Modifier.weight(1f)) {
                when {
                    offline -> Message("Sem conexão com a internet. Tente de novo em instantes.")
                    noResults != null -> Column {
                        Text("Sua busca por \"$noResults\" não encontrou resultados na Netflix.", color = Color.White, fontSize = 15.sp)
                        Spacer(Modifier.height(10.dp))
                        Text("Sugestões:", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                        listOf("Tente palavras diferentes", "Procure por um filme, série, ator ou diretor", "Escolha uma das categorias ao lado")
                            .forEach { Text("• $it", color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp) }
                    }
                    loading && !hasItems -> Column {
                        Text(if (query.isBlank()) "Recomendados para você" else "Resultados para \"${query.trim()}\"",
                            color = Color.White.copy(alpha = 0.7f), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(10.dp))
                        repeat(3) { GhostRow(); Spacer(Modifier.height(10.dp)) }
                    }
                    else -> LazyColumn(state = listState, userScrollEnabled = false, contentPadding = PaddingValues(top = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        itemsIndexed(lines, key = { i, l -> if (l is NSLine.Header) "h_${l.key}" else "c_$i" }) { _, line ->
                            when (line) {
                                is NSLine.Header -> Text(line.title, color = Color.White.copy(alpha = 0.85f), fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.padding(top = 6.dp))
                                is NSLine.Cards -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                    for (c in 0 until COLS) {
                                        val item = line.items.getOrNull(c)
                                        Box(Modifier.weight(1f)) {
                                            if (item != null) SearchCard(item, logos[item.key],
                                                focused = zone == Zone.GRID && secIndex == line.section && itemIndex == line.start + c)
                                        }
                                    }
                                }
                            }
                        }
                        if (loadingMore) item(key = "more") { GhostRow() }
                        if (ended) item(key = "end") {
                            Text("Você viu tudo por aqui.", color = Color.White.copy(alpha = 0.5f), fontSize = 13.sp,
                                textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(vertical = 18.dp))
                        }
                        item(key = "pad") { Spacer(Modifier.height(200.dp)) }
                    }
                }
            }
        }
    }
}

@Composable
private fun Message(text: String) = Text(text, color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp)

@Composable
private fun GhostRow() {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        repeat(COLS) { Box(Modifier.weight(1f).aspectRatio(16f / 9f).clip(RoundedCornerShape(4.dp)).background(Color(0xFF1C1C1C))) }
    }
}

@Composable
private fun TermItem(term: String, query: String, focused: Boolean) {
    // Destaca a parte que você já digitou.
    val q = nfNorm(query)
    val at = if (q.isNotEmpty()) nfNorm(term).indexOf(q) else -1
    val text = buildAnnotatedString {
        if (at >= 0 && at + q.length <= term.length) {
            append(term.substring(0, at))
            withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = if (focused) Color.Black else Color.White)) { append(term.substring(at, at + q.length)) }
            append(term.substring(at + q.length))
        } else append(term)
    }
    Box(
        Modifier.fillMaxWidth().padding(vertical = 1.dp)
            .background(if (focused) Color.White else Color.Transparent, RoundedCornerShape(3.dp))
            .padding(horizontal = 8.dp, vertical = 5.dp)
    ) {
        Text(text, color = if (focused) Color.Black else Color.White.copy(alpha = 0.7f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun KbKey(label: String, focused: Boolean, wide: Boolean = false) {
    Box(
        Modifier
            .size(width = if (wide) 121.dp else 38.dp, height = 34.dp)
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.08f), RoundedCornerShape(3.dp)),
        contentAlignment = Alignment.Center
    ) {
        Text(label, color = if (focused) Color.Black else Color.White, fontSize = if (wide) 13.sp else 15.sp,
            fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun SearchCard(item: NSItem, logo: String?, focused: Boolean) {
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(140), label = "search")
    Box(
        Modifier
            .scale(scale)
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF232323))
            .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(4.dp))
    ) {
        AsyncImage(model = item.backdrop, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
        if (!logo.isNullOrBlank()) {
            AsyncImage(model = logo, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 14.dp, vertical = 5.dp).height(26.dp).fillMaxWidth())
        } else Text(item.title, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(7.dp))
    }
}
