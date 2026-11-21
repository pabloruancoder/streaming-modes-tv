package com.arflix.tv.ui.screens.modes

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import androidx.compose.ui.graphics.vector.ImageVector
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
import androidx.compose.ui.zIndex
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.arflix.tv.data.model.MediaType
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

private val DRed = Color(0xFFE50914)
private val DGreen = Color(0xFF46D369)

/** Botões da barra de cima: "Surpreenda-me", "Atualizar" e os gêneros ("Todos" = sem filtro). */
private sealed class DChip(val label: String) {
    object Surprise : DChip("Surpreenda-me")
    object Refresh : DChip("Atualizar")
    class Genre(val genre: NSGenre?) : DChip(genre?.label ?: "Todos")
}

private fun rating(v: Double) = String.format(Locale("pt", "BR"), "%.1f", v)

/**
 * Aba "Descobrir" (modo Netflix): para quem não sabe o que assistir. Barra no alto com Surpreenda-me,
 * Atualizar e filtro por gênero; destaque que acompanha a capa focada; fileiras de recomendações
 * (Top 10 da semana, em alta, mais comentados, mais famosos, mais bem avaliados, joias escondidas...).
 */
@Composable
internal fun NetflixDiscoverScreen(
    onOpen: (MediaType, Int) -> Unit,
    onPlay: (MediaType, Int) -> Unit,
    onBack: () -> Unit,
    viewModel: NetflixDiscoverViewModel = hiltViewModel(),
    extras: NetflixExtrasViewModel = hiltViewModel()
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val genre by viewModel.genre.collectAsStateWithLifecycle()
    val logos by viewModel.logos.collectAsStateWithLifecycle()

    // Sementes do "Porque você assistiu": Continuar assistindo (senão, Minha lista).
    LaunchedEffect(Unit) {
        extras.refresh()
        val resume = withTimeoutOrNull(3_000) { extras.resume.first { it.isNotEmpty() } }.orEmpty().map { it.item }
        val seeds = resume.ifEmpty { extras.myList.value }
            .filter { it.mediaType == MediaType.MOVIE || it.mediaType == MediaType.TV }
            .map { NSSeed(it.mediaType, it.id, it.title) }
        viewModel.start(seeds)
    }

    val chips = remember {
        listOf(DChip.Surprise, DChip.Refresh, DChip.Genre(null)) +
            NF_SEARCH_CATEGORIES.mapNotNull { c -> NF_GENRES.firstOrNull { it.label == c } }.map { DChip.Genre(it) }
    }
    var inBar by rememberSaveable { mutableStateOf(false) }
    var chipIndex by rememberSaveable { mutableIntStateOf(2) }
    var rowIndex by rememberSaveable { mutableIntStateOf(0) }
    val cols = remember { mutableStateMapOf<String, Int>() }

    val safeRow = rowIndex.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
    val row = rows.getOrNull(safeRow)
    val col = row?.let { (cols[it.id] ?: 0).coerceIn(0, it.items.size - 1) } ?: 0
    val item = row?.items?.getOrNull(col)

    // Filtro novo ou "Atualizar": volta para o começo.
    LaunchedEffect(rows.firstOrNull()?.id, rows.firstOrNull()?.items?.firstOrNull()?.key) {
        if (rows.isEmpty()) { rowIndex = 0; cols.clear() }
    }
    LaunchedEffect(item?.key) { row?.let { viewModel.wantLogos(it.items.drop(col).take(8)) } }

    fun pressChip() {
        when (val c = chips[chipIndex]) {
            DChip.Surprise -> viewModel.surprise()?.let { onOpen(it.type, it.id) }
            DChip.Refresh -> viewModel.refresh()
            is DChip.Genre -> viewModel.setGenre(c.genre)
        }
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(10) { if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect; delay(100) } }
    val listState = rememberLazyListState()
    LaunchedEffect(safeRow, rows.size) { if (rows.isNotEmpty()) listState.animateScrollToItem(safeRow) }
    val barState = rememberLazyListState()
    LaunchedEffect(chipIndex) { barState.animateScrollToItem((chipIndex - 2).coerceAtLeast(0)) }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                if (inBar) when (ev.key) {
                    Key.DirectionLeft -> { chipIndex = (chipIndex - 1).coerceAtLeast(0); true }
                    Key.DirectionRight -> { chipIndex = (chipIndex + 1).coerceAtMost(chips.size - 1); true }
                    Key.DirectionDown -> { if (rows.isNotEmpty()) inBar = false; true }
                    Key.DirectionUp -> true
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { pressChip(); true }
                    Key.Back -> { onBack(); true }
                    else -> false
                } else when (ev.key) {
                    Key.DirectionUp -> { if (safeRow == 0) inBar = true else rowIndex = safeRow - 1; true }
                    Key.DirectionDown -> { rowIndex = (safeRow + 1).coerceAtMost((rows.size - 1).coerceAtLeast(0)); true }
                    Key.DirectionLeft -> { row?.let { if (col > 0) cols[it.id] = col - 1 }; true }
                    Key.DirectionRight -> { row?.let { cols[it.id] = (col + 1).coerceAtMost(it.items.size - 1) }; true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { item?.let { onOpen(it.type, it.id) }; true }
                    Key.MediaPlay, Key.MediaPlayPause -> { item?.let { onPlay(it.type, it.id) }; true }
                    Key.Back -> { onBack(); true }
                    else -> false
                }
            }
    ) {
        val previewH = maxHeight * 0.6f
        // ---- Destaque ----
        Box(Modifier.fillMaxWidth().height(previewH)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(0.7f).align(Alignment.TopEnd)) {
                item?.backdrop?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black, 0.35f to Color.Black.copy(alpha = 0.55f), 0.7f to Color.Transparent)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.7f), 0.25f to Color.Transparent, 0.6f to Color.Transparent, 1f to Color.Black)))
            }
            Column(Modifier.align(Alignment.TopStart).padding(start = 56.dp, top = 74.dp).widthIn(max = 430.dp)) {
                Text(listOfNotNull("Descobrir", genre?.label, row?.title).joinToString("  ·  "), color = Color.White.copy(alpha = 0.6f),
                    fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(12.dp))
                if (item == null) {
                    Text(if (loading) "Procurando boas recomendações para você…" else "Nada encontrado com esse filtro.",
                        color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp)
                    return@Column
                }
                val logo = logos[item.key]
                if (!logo.isNullOrBlank()) {
                    AsyncImage(model = logo, contentDescription = item.title, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
                        modifier = Modifier.heightIn(max = 68.dp).widthIn(max = 300.dp))
                } else Text(item.title, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black, maxLines = 2, lineHeight = 30.sp)
                Spacer(Modifier.height(10.dp))
                if (row?.ranked == true) Row(verticalAlignment = Alignment.CenterVertically) {
                    Top10Badge()
                    Spacer(Modifier.width(8.dp))
                    Text("Nº ${col + 1} na Netflix esta semana", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.vote > 0 && item.votes >= 20) {
                        Icon(Icons.Default.Star, contentDescription = null, tint = DGreen, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(rating(item.vote), color = DGreen, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(10.dp))
                    }
                    val meta = listOfNotNull(if (item.type == MediaType.MOVIE) "Filme" else "Série", item.year.ifBlank { null }) + item.genres.take(3)
                    Text(meta.joinToString("  •  "), color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                }
                if (item.overview.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(item.overview, color = Color.White.copy(alpha = 0.88f), fontSize = 13.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DHint("Mais informações", Icons.Default.Info)
                    DHint("Assistir", Icons.Default.PlayArrow)
                }
            }
        }

        // ---- Barra: Surpreenda-me, Atualizar e gêneros ----
        LazyRow(
            state = barState,
            userScrollEnabled = false,
            contentPadding = PaddingValues(start = 52.dp, end = 300.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 20.dp)
        ) {
            itemsIndexed(chips, key = { _, c -> c.label }) { i, c ->
                val focused = inBar && i == chipIndex
                val selected = c is DChip.Genre && c.genre?.label == genre?.label
                val bg = when {
                    focused -> Color.White
                    selected -> Color.White.copy(alpha = 0.22f)
                    else -> Color.Transparent
                }
                val fg = if (focused) Color.Black else Color.White.copy(alpha = if (selected) 1f else 0.8f)
                Row(
                    Modifier.background(bg, RoundedCornerShape(50))
                        .border(1.dp, if (focused) Color.White else Color.White.copy(alpha = if (selected) 0.9f else 0.35f), RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val icon = when (c) { DChip.Surprise -> Icons.Default.Shuffle; DChip.Refresh -> Icons.Default.Refresh; else -> null }
                    if (icon != null) {
                        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(c.label, color = fg, fontSize = 13.sp, fontWeight = if (focused || selected) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }

        // ---- Fileiras ----
        LazyColumn(
            state = listState,
            userScrollEnabled = false,
            contentPadding = PaddingValues(bottom = maxHeight),
            modifier = Modifier.fillMaxSize().padding(top = previewH - 4.dp)
        ) {
            if (rows.isEmpty() && loading) {
                items(2) { GhostDRow() }
            }
            itemsIndexed(rows, key = { _, r -> r.id }) { i, r ->
                val focusedRow = !inBar && i == safeRow
                val rowState = rememberLazyListState()
                val c = if (i == safeRow) col else (cols[r.id] ?: 0)
                LaunchedEffect(c) { rowState.animateScrollToItem(c) }
                Column(Modifier.padding(bottom = 16.dp)) {
                    Text(r.title, color = Color.White.copy(alpha = if (focusedRow) 1f else 0.75f), fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 56.dp, bottom = 6.dp))
                    Box {
                        LazyRow(state = rowState, userScrollEnabled = false, contentPadding = PaddingValues(start = 56.dp, end = 400.dp),
                            horizontalArrangement = Arrangement.spacedBy(if (r.ranked) 4.dp else 8.dp)) {
                            itemsIndexed(r.items, key = { _, it -> it.key }) { j, it ->
                                val f = focusedRow && j == c
                                if (r.ranked) DRankCard(j + 1, it, f) else DCard(it, logos[it.key], f, r.rating)
                            }
                        }
                        Box(Modifier.width(56.dp).height(if (r.ranked) 160.dp else 110.dp)
                            .background(Brush.horizontalGradient(0f to Color.Black, 0.75f to Color.Black, 1f to Color.Transparent)))
                    }
                }
            }
        }
    }
}

@Composable
private fun Top10Badge() {
    Box(Modifier.size(width = 24.dp, height = 28.dp).background(DRed, RoundedCornerShape(2.dp)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("TOP", color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Black, lineHeight = 8.sp)
            Text("10", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black, lineHeight = 12.sp)
        }
    }
}

@Composable
private fun DHint(text: String, icon: ImageVector) {
    Row(Modifier.background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(4.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Capa horizontal com o logo do título; nas fileiras de nota, a nota no canto. */
@Composable
private fun DCard(item: DItem, logo: String?, focused: Boolean, showRating: Boolean) {
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(160), label = "dc")
    Box(
        Modifier.zIndex(if (focused) 1f else 0f).scale(scale).size(width = 176.dp, height = 99.dp).clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF232323))
            .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(4.dp))
    ) {
        AsyncImage(model = item.backdrop, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
        if (!logo.isNullOrBlank()) {
            AsyncImage(model = logo, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 14.dp, end = 14.dp, bottom = 6.dp).height(28.dp).fillMaxWidth())
        } else Text(item.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 8.dp, bottom = 6.dp))
        if (showRating && item.vote > 0) {
            Row(Modifier.align(Alignment.TopEnd).padding(5.dp).background(Color.Black.copy(alpha = 0.72f), RoundedCornerShape(3.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Star, contentDescription = null, tint = DGreen, modifier = Modifier.size(10.dp))
                Spacer(Modifier.width(2.dp))
                Text(rating(item.vote), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

/** Top 10: número grande com contorno ao lado do pôster (como na Home). */
@Composable
private fun DRankCard(rank: Int, item: DItem, focused: Boolean) {
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(160), label = "dr")
    Row(Modifier.zIndex(if (focused) 1f else 0f).scale(scale), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.width(if (rank >= 10) 118.dp else 74.dp).height(150.dp), contentAlignment = Alignment.BottomEnd) {
            Text(rank.toString(), color = Color(0xFF8C8C8C), fontSize = 150.sp, fontWeight = FontWeight.Black, lineHeight = 150.sp,
                modifier = Modifier.offset(x = 10.dp, y = 30.dp), style = TextStyle(drawStyle = Stroke(width = 4f)))
        }
        Box(Modifier.size(width = 100.dp, height = 150.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF232323))
            .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(4.dp))) {
            AsyncImage(model = item.poster ?: item.backdrop, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun GhostDRow() {
    Column(Modifier.padding(start = 56.dp, bottom = 16.dp)) {
        Box(Modifier.size(width = 180.dp, height = 14.dp).background(Color(0xFF1C1C1C), RoundedCornerShape(3.dp)))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(5) { Box(Modifier.size(width = 176.dp, height = 99.dp).background(Color(0xFF1C1C1C), RoundedCornerShape(4.dp))) }
        }
    }
}
