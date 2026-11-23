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
import androidx.compose.material.icons.filled.Favorite
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
import androidx.compose.ui.graphics.Shadow
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

// Paleta rosa da aba Doramas.
internal val DoramaPink = Color(0xFFFF4FA3)
private val PinkSoft = Color(0xFFFF9CCB)
private val PinkDeep = Color(0xFFB0245F)
private val Plum = Color(0xFF16070F)      // fundo
private val PlumCard = Color(0xFF2A0F1F)  // capa sem imagem / esqueleto

private sealed class KChip(val label: String) {
    object Surprise : KChip("Surpreenda-me")
    object Refresh : KChip("Atualizar")
    class Country(val country: DoramaCountry) : KChip(country.label)
}

private fun kRating(v: Double) = String.format(Locale("pt", "BR"), "%.1f", v)

/**
 * Aba "Doramas" (modo Netflix): página só de doramas, toda em rosa. Barra no alto com Surpreenda-me,
 * Atualizar e país; destaque que acompanha a capa focada; fileiras (Top 10, em alta, mais bem avaliados...)
 * e, por último, "Todos os doramas", que carrega mais conforme a pessoa anda para a direita.
 */
@Composable
internal fun NetflixDoramasScreen(
    onOpen: (MediaType, Int) -> Unit,
    onPlay: (MediaType, Int) -> Unit,
    onBack: () -> Unit,
    viewModel: NetflixDoramasViewModel = hiltViewModel(),
    extras: NetflixExtrasViewModel = hiltViewModel()
) {
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val country by viewModel.country.collectAsStateWithLifecycle()
    val info by viewModel.info.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        extras.refresh()
        val resumeList = withTimeoutOrNull(3_000) { extras.resume.first { it.isNotEmpty() } }.orEmpty()
        val resume = resumeList.map { it.item }
        val seeds = (resume + extras.myList.value)
            .filter { it.mediaType == MediaType.TV }
            .distinctBy { it.id }
            .map { NSSeed(it.mediaType, it.id, it.title) }
        viewModel.start(seeds, resumeList)
    }

    val chips = remember { listOf(KChip.Surprise, KChip.Refresh) + DORAMA_COUNTRIES.map { KChip.Country(it) } }
    var inBar by rememberSaveable { mutableStateOf(false) }
    var chipIndex by rememberSaveable { mutableIntStateOf(2) }
    var rowIndex by rememberSaveable { mutableIntStateOf(0) }
    val cols = remember { mutableStateMapOf<String, Int>() }

    val safeRow = rowIndex.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
    val row = rows.getOrNull(safeRow)
    val col = row?.let { (cols[it.id] ?: 0).coerceIn(0, it.items.size - 1) } ?: 0
    val item = row?.items?.getOrNull(col)

    LaunchedEffect(rows.firstOrNull()?.id, rows.firstOrNull()?.items?.firstOrNull()?.key) {
        if (rows.isEmpty()) { rowIndex = 0; cols.clear() }
    }
    LaunchedEffect(item?.key) {
        row?.let { viewModel.wantInfo(it.items.drop(col).take(3)) }
        // "Todos os doramas": perto do fim, busca a próxima página.
        if (row?.id == NetflixDoramasViewModel.ALL_ROW && col >= row.items.size - 8) viewModel.loadMoreAll()
    }

    fun pressChip() {
        when (val c = chips[chipIndex]) {
            KChip.Surprise -> viewModel.surprise()?.let { onOpen(it.type, it.id) }
            KChip.Refresh -> viewModel.refresh()
            is KChip.Country -> viewModel.setCountry(c.country)
        }
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(10) { if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect; delay(100) } }
    val listState = rememberLazyListState()
    LaunchedEffect(safeRow, rows.size) { if (rows.isNotEmpty()) listState.animateScrollToItem(safeRow) }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Plum).focusRequester(focus).focusable()
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
        // Brilho rosa no canto de cima.
        Box(Modifier.fillMaxWidth().height(previewH).background(
            Brush.radialGradient(listOf(DoramaPink.copy(alpha = 0.22f), Color.Transparent), radius = 900f,
                center = androidx.compose.ui.geometry.Offset(0f, 0f))
        ))
        // ---- Destaque ----
        Box(Modifier.fillMaxWidth().height(previewH)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(0.7f).align(Alignment.TopEnd)) {
                item?.backdrop?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Plum, 0.35f to Plum.copy(alpha = 0.6f), 0.7f to Color.Transparent)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Plum.copy(alpha = 0.7f), 0.25f to Color.Transparent, 0.6f to Color.Transparent, 1f to Plum)))
                // Véu rosa leve por cima da imagem.
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 1f to PinkDeep.copy(alpha = 0.18f))))
            }
            Column(Modifier.align(Alignment.TopStart).padding(start = 56.dp, top = 74.dp).widthIn(max = 430.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Favorite, contentDescription = null, tint = DoramaPink, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("DORAMAS", color = DoramaPink, fontSize = 16.sp, fontWeight = FontWeight.Black, letterSpacing = 3.sp)
                    val sub = listOfNotNull(country.label.takeIf { it != "Todos" }, row?.title).joinToString("  ·  ")
                    if (sub.isNotBlank()) Text("   $sub", color = PinkSoft.copy(alpha = 0.75f), fontSize = 13.sp, fontWeight = FontWeight.Bold,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(12.dp))
                if (item == null) {
                    Text(if (loading) "Separando os melhores doramas para você…" else "Nenhum dorama encontrado com esse filtro.",
                        color = PinkSoft.copy(alpha = 0.8f), fontSize = 15.sp)
                    return@Column
                }
                // Sempre o nome em português (o mesmo das outras telas), sem imagem de logo com outra tradução.
                Text(item.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black, maxLines = 2, lineHeight = 32.sp,
                    overflow = TextOverflow.Ellipsis, style = TextStyle(shadow = Shadow(PinkDeep, blurRadius = 18f)))
                Spacer(Modifier.height(10.dp))
                val resumeInfo = progress[item.key]
                if (row?.id == NetflixDoramasViewModel.CONT_ROW && resumeInfo != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(listOfNotNull("Continuar", resumeInfo.label).joinToString(" "), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    if (resumeInfo.progress > 0) {
                        Spacer(Modifier.width(10.dp))
                        PinkProgress(resumeInfo.progress, Modifier.width(140.dp))
                    }
                } else if (row?.ranked == true) Row(verticalAlignment = Alignment.CenterVertically) {
                    PinkTop10Badge()
                    Spacer(Modifier.width(8.dp))
                    Text("Nº ${col + 1} entre os doramas desta semana", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.vote > 0 && item.votes >= 10) {
                        Icon(Icons.Default.Star, contentDescription = null, tint = DoramaPink, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(kRating(item.vote), color = DoramaPink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(10.dp))
                    }
                    val meta = listOfNotNull("Dorama", item.year.ifBlank { null }, info[item.key]) + item.genres.take(2)
                    Text(meta.joinToString("  •  "), color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                }
                if (item.overview.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(item.overview, color = Color.White.copy(alpha = 0.88f), fontSize = 13.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KHint("Mais informações", Icons.Default.Info, primary = false)
                    KHint(if (row?.id == NetflixDoramasViewModel.CONT_ROW) "Continuar" else "Assistir", Icons.Default.PlayArrow, primary = true)
                }
            }
        }

        // ---- Barra: Surpreenda-me, Atualizar e países ----
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 52.dp, top = 20.dp)
        ) {
            chips.forEachIndexed { i, c ->
                val focused = inBar && i == chipIndex
                val selected = c is KChip.Country && c.country.label == country.label
                val bg = when {
                    focused -> DoramaPink
                    selected -> DoramaPink.copy(alpha = 0.25f)
                    else -> Color.Transparent
                }
                val fg = if (focused) Color.White else if (selected) PinkSoft else Color.White.copy(alpha = 0.8f)
                Row(
                    Modifier.background(bg, RoundedCornerShape(50))
                        .border(1.dp, if (focused || selected) DoramaPink else PinkSoft.copy(alpha = 0.35f), RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val icon = when (c) { KChip.Surprise -> Icons.Default.Shuffle; KChip.Refresh -> Icons.Default.Refresh; else -> null }
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
                items(2) { GhostKRow() }
            }
            itemsIndexed(rows, key = { _, r -> r.id }) { i, r ->
                val focusedRow = !inBar && i == safeRow
                val rowState = rememberLazyListState()
                val c = if (i == safeRow) col else (cols[r.id] ?: 0)
                LaunchedEffect(c) { rowState.animateScrollToItem(c) }
                Column(Modifier.padding(bottom = 16.dp)) {
                    Row(Modifier.padding(start = 56.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(r.title, color = if (focusedRow) PinkSoft else Color.White.copy(alpha = 0.75f), fontSize = 15.sp, fontWeight = FontWeight.Bold)
                        if (r.id == NetflixDoramasViewModel.ALL_ROW && focusedRow) {
                            Text("   ${col + 1} de ${r.items.size}${if (r.items.size >= 20) "+" else ""}", color = PinkSoft.copy(alpha = 0.6f), fontSize = 12.sp)
                        }
                    }
                    Box {
                        LazyRow(state = rowState, userScrollEnabled = false, contentPadding = PaddingValues(start = 56.dp, end = 400.dp),
                            horizontalArrangement = Arrangement.spacedBy(if (r.ranked) 4.dp else 8.dp)) {
                            itemsIndexed(r.items, key = { _, it -> it.key }) { j, it ->
                                val f = focusedRow && j == c
                                if (r.ranked) KRankCard(j + 1, it, f) else KCard(it, progress[it.key]?.progress, f, r.rating)
                            }
                        }
                        Box(Modifier.width(56.dp).height(if (r.ranked) 160.dp else 110.dp)
                            .background(Brush.horizontalGradient(0f to Plum, 0.75f to Plum, 1f to Color.Transparent)))
                    }
                }
            }
        }
    }
}

/** Barra de progresso rosa (quanto já foi assistido). */
@Composable
private fun PinkProgress(percent: Int, modifier: Modifier) {
    Box(modifier.height(4.dp).clip(RoundedCornerShape(2.dp)).background(Color.White.copy(alpha = 0.3f))) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(percent.coerceIn(2, 100) / 100f).background(DoramaPink))
    }
}

@Composable
private fun PinkTop10Badge() {
    Box(Modifier.size(width = 24.dp, height = 28.dp).background(DoramaPink, RoundedCornerShape(2.dp)), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("TOP", color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Black, lineHeight = 8.sp)
            Text("10", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black, lineHeight = 12.sp)
        }
    }
}

@Composable
private fun KHint(text: String, icon: ImageVector, primary: Boolean) {
    Row(Modifier.background(if (primary) DoramaPink else Color.White.copy(alpha = 0.14f), RoundedCornerShape(4.dp))
        .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun KCard(item: DItem, progress: Int?, focused: Boolean, showRating: Boolean) {
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(160), label = "kc")
    Box(
        Modifier.zIndex(if (focused) 1f else 0f).scale(scale).size(width = 176.dp, height = 99.dp).clip(RoundedCornerShape(6.dp))
            .background(PlumCard)
            .border(if (focused) 3.dp else 0.dp, if (focused) DoramaPink else Color.Transparent, RoundedCornerShape(6.dp))
    ) {
        AsyncImage(model = item.backdrop, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Plum.copy(alpha = 0.85f))))
        val hasBar = progress != null && progress > 0
        Text(item.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 8.dp, bottom = if (hasBar) 12.dp else 6.dp))
        if (hasBar) PinkProgress(progress!!, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 5.dp))
        if (showRating && item.vote > 0) {
            Row(Modifier.align(Alignment.TopEnd).padding(5.dp).background(Plum.copy(alpha = 0.8f), RoundedCornerShape(3.dp))
                .padding(horizontal = 5.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Star, contentDescription = null, tint = DoramaPink, modifier = Modifier.size(10.dp))
                Spacer(Modifier.width(2.dp))
                Text(kRating(item.vote), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun KRankCard(rank: Int, item: DItem, focused: Boolean) {
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(160), label = "kr")
    Row(Modifier.zIndex(if (focused) 1f else 0f).scale(scale), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.width(if (rank >= 10) 118.dp else 74.dp).height(150.dp), contentAlignment = Alignment.BottomEnd) {
            Text(rank.toString(), color = DoramaPink, fontSize = 150.sp, fontWeight = FontWeight.Black, lineHeight = 150.sp,
                modifier = Modifier.offset(x = 10.dp, y = 30.dp), style = TextStyle(drawStyle = Stroke(width = 4f)))
        }
        Box(Modifier.size(width = 100.dp, height = 150.dp).clip(RoundedCornerShape(6.dp)).background(PlumCard)
            .border(if (focused) 3.dp else 0.dp, if (focused) DoramaPink else Color.Transparent, RoundedCornerShape(6.dp))) {
            AsyncImage(model = item.poster ?: item.backdrop, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun GhostKRow() {
    Column(Modifier.padding(start = 56.dp, bottom = 16.dp)) {
        Box(Modifier.size(width = 180.dp, height = 14.dp).background(PlumCard, RoundedCornerShape(3.dp)))
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            repeat(5) { Box(Modifier.size(width = 176.dp, height = 99.dp).background(PlumCard, RoundedCornerShape(6.dp))) }
        }
    }
}
