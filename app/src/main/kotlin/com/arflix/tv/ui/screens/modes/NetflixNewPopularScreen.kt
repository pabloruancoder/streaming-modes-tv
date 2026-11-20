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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.TextStyle as DateTextStyle
import java.util.Locale

private val NPRed = Color(0xFFE50914)
private val ptBR = Locale("pt", "BR")

/** "Chega hoje", "Chega amanhã", "Chega sexta-feira", "Chega em 12 de dez" (como no PC). */
private fun arrivalLabel(item: NPItem): String {
    val d = item.daysUntil ?: return ""
    val date = runCatching { LocalDate.parse(item.date!!.take(10)) }.getOrNull() ?: return ""
    return when {
        d <= 0 -> "Chega hoje"
        d == 1L -> "Chega amanhã"
        d < 7 -> "Chega ${date.dayOfWeek.getDisplayName(DateTextStyle.FULL, ptBR)}"
        else -> "Chega em ${date.dayOfMonth} de ${date.month.getDisplayName(DateTextStyle.SHORT, ptBR).trimEnd('.')}"
    }
}

/**
 * "Novidades e populares" no estilo da Home Netflix do app: destaque no alto que acompanha a capa focada e
 * fileiras embaixo (as mesmas do PC). Estreias mostram a data na capa e "Lembrar-me" (OK liga/desliga);
 * títulos já disponíveis abrem a página do título (OK) ou começam a tocar (▶).
 */
@Composable
internal fun NetflixNewPopularScreen(
    onOpen: (MediaType, Int) -> Unit,
    onPlay: (MediaType, Int) -> Unit,
    onBack: () -> Unit,
    viewModel: NetflixNewPopularViewModel = hiltViewModel()
) {
    LaunchedEffect(Unit) { viewModel.load() }
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val loading by viewModel.loading.collectAsStateWithLifecycle()
    val reminders by viewModel.reminders.collectAsStateWithLifecycle()
    var rowIndex by rememberSaveable { mutableIntStateOf(0) }
    val cols = remember { mutableStateMapOf<String, Int>() }
    val scope = rememberCoroutineScope()

    val safeRow = rowIndex.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
    val row = rows.getOrNull(safeRow)
    val col = row?.let { (cols[it.id] ?: 0).coerceIn(0, it.items.size - 1) } ?: 0
    val item = row?.items?.getOrNull(col)

    fun open(play: Boolean) {
        val it = item ?: return
        if (it.upcoming && !play) { viewModel.toggleReminder(it); return }
        if (it.upcoming) return
        scope.launch { viewModel.resolve(it)?.let { (t, id) -> if (play) onPlay(t, id) else onOpen(t, id) } }
    }

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(10) { if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect; delay(100) } }
    val listState = rememberLazyListState()
    LaunchedEffect(safeRow, rows.size) { if (rows.isNotEmpty()) listState.animateScrollToItem(safeRow) }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black).focusRequester(focus).focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent true
                when (ev.key) {
                    Key.DirectionUp -> { rowIndex = (safeRow - 1).coerceAtLeast(0); true }
                    Key.DirectionDown -> { rowIndex = (safeRow + 1).coerceAtMost((rows.size - 1).coerceAtLeast(0)); true }
                    Key.DirectionLeft -> { row?.let { if (col > 0) cols[it.id] = col - 1 }; true }
                    Key.DirectionRight -> { row?.let { cols[it.id] = (col + 1).coerceAtMost(it.items.size - 1) }; true }
                    Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { open(play = false); true }
                    Key.MediaPlay, Key.MediaPlayPause -> { open(play = true); true }
                    Key.Back -> { onBack(); true }
                    else -> false
                }
            }
    ) {
        val previewH = maxHeight * 0.58f
        // ---- Destaque ----
        Box(Modifier.fillMaxWidth().height(previewH)) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(0.72f).align(Alignment.TopEnd)) {
                item?.background?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black, 0.35f to Color.Black.copy(alpha = 0.55f), 0.7f to Color.Transparent)))
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black)))
            }
            Column(Modifier.align(Alignment.TopStart).padding(start = 56.dp, top = 28.dp).widthIn(max = 430.dp)) {
                Text("Novidades e populares", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(14.dp))
                if (item == null) {
                    Text(if (loading) "Carregando…" else "Nada por aqui agora.", color = Color.White.copy(alpha = 0.6f), fontSize = 15.sp)
                    return@Column
                }
                if (!item.logo.isNullOrBlank()) {
                    AsyncImage(model = item.logo, contentDescription = item.name, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
                        modifier = Modifier.heightIn(max = 72.dp).widthIn(max = 300.dp))
                } else Text(item.name, color = Color.White, fontSize = 28.sp, fontWeight = FontWeight.Black, maxLines = 2, lineHeight = 30.sp)
                Spacer(Modifier.height(10.dp))
                when {
                    row?.upcoming == true -> Text(arrivalLabel(item), color = NPRed, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    row?.ranked == true -> Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(width = 24.dp, height = 28.dp).background(NPRed, RoundedCornerShape(2.dp)), contentAlignment = Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("TOP", color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Black, lineHeight = 8.sp)
                                Text("10", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black, lineHeight = 12.sp)
                            }
                        }
                        Spacer(Modifier.width(8.dp))
                        Text("Nº ${col + 1} em ${if (item.movie) "filmes" else "séries"} esta semana", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Spacer(Modifier.height(6.dp))
                val meta = listOfNotNull(item.kind ?: if (item.movie) "Filme" else "Série", item.year.takeIf { it.isNotBlank() && row?.upcoming != true }) + item.genres.take(3)
                Text(meta.joinToString("  •  "), color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp)
                if (item.description.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(item.description, color = Color.White.copy(alpha = 0.88f), fontSize = 13.sp, lineHeight = 18.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(12.dp))
                if (item.upcoming) {
                    val on = item.imdbId in reminders
                    HintChip(if (on) "Lembrete ativo · OK para remover" else "Lembrar-me · OK", if (on) Icons.Default.Check else Icons.Default.Notifications)
                } else Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HintChip("Mais informações", Icons.Default.Info)
                    HintChip("Assistir", Icons.Default.PlayArrow)
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
            itemsIndexed(rows, key = { _, r -> r.id }) { i, r ->
                val focusedRow = i == safeRow
                val rowState = rememberLazyListState()
                val c = if (focusedRow) col else (cols[r.id] ?: 0)
                LaunchedEffect(c) { rowState.animateScrollToItem(c) }
                Column(Modifier.padding(bottom = 16.dp)) {
                    Text(r.title, color = Color.White.copy(alpha = if (focusedRow) 1f else 0.75f), fontSize = 15.sp, fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 56.dp, bottom = 6.dp))
                    Box {
                        LazyRow(state = rowState, userScrollEnabled = false, contentPadding = PaddingValues(start = 56.dp, end = 400.dp),
                            horizontalArrangement = Arrangement.spacedBy(if (r.ranked) 4.dp else 8.dp)) {
                            itemsIndexed(r.items, key = { _, it -> it.imdbId }) { j, it ->
                                val f = focusedRow && j == c
                                when {
                                    r.ranked -> RankCard(j + 1, it, f)
                                    else -> NPCard(it, f, upcoming = r.upcoming, reminded = it.imdbId in reminders)
                                }
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
private fun HintChip(text: String, icon: ImageVector) {
    Row(Modifier.background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(4.dp)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

/** Capa horizontal; estreias com a data no canto, "Chega ..." embaixo e o sininho quando há lembrete. */
@Composable
private fun NPCard(item: NPItem, focused: Boolean, upcoming: Boolean, reminded: Boolean) {
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(160), label = "np")
    Box(
        Modifier.zIndex(if (focused) 1f else 0f).scale(scale).size(width = 176.dp, height = 99.dp).clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF232323))
            .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(4.dp))
    ) {
        AsyncImage(model = item.background ?: item.poster, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
        if (!item.logo.isNullOrBlank()) {
            AsyncImage(model = item.logo, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.align(Alignment.BottomCenter).padding(start = 14.dp, end = 14.dp, bottom = if (upcoming) 20.dp else 6.dp).height(28.dp).fillMaxWidth())
        } else Text(item.name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 8.dp, bottom = if (upcoming) 20.dp else 6.dp))
        if (upcoming) {
            val date = runCatching { LocalDate.parse(item.date!!.take(10)) }.getOrNull()
            if (date != null) {
                Column(Modifier.align(Alignment.TopStart).padding(6.dp).background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${date.dayOfMonth}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Black, lineHeight = 16.sp)
                    Text(date.month.getDisplayName(DateTextStyle.SHORT, ptBR).trimEnd('.').uppercase(), color = Color.White.copy(alpha = 0.8f), fontSize = 9.sp, lineHeight = 10.sp)
                }
            }
            Text(arrivalLabel(item), color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 4.dp))
            if (reminded) Icon(Icons.Default.Notifications, contentDescription = "Lembrete ativo", tint = NPRed,
                modifier = Modifier.align(Alignment.TopEnd).padding(6.dp).size(16.dp))
        }
    }
}

/** Top 10: número grande com contorno ao lado do pôster (como na Home). */
@Composable
private fun RankCard(rank: Int, item: NPItem, focused: Boolean) {
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(160), label = "rk")
    Row(Modifier.zIndex(if (focused) 1f else 0f).scale(scale), verticalAlignment = Alignment.Bottom) {
        Box(Modifier.width(if (rank >= 10) 118.dp else 74.dp).height(150.dp), contentAlignment = Alignment.BottomEnd) {
            Text(rank.toString(), color = Color(0xFF8C8C8C), fontSize = 150.sp, fontWeight = FontWeight.Black, lineHeight = 150.sp,
                modifier = Modifier.offset(x = 10.dp, y = 30.dp), style = TextStyle(drawStyle = Stroke(width = 4f)))
        }
        Box(Modifier.size(width = 100.dp, height = 150.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF232323))
            .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(4.dp))) {
            AsyncImage(model = item.poster ?: item.background, contentDescription = item.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}
