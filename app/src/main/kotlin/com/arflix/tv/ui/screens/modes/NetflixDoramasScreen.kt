package com.arflix.tv.ui.screens.modes

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
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
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.ThumbUp
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
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
import com.arflix.tv.ui.components.TrailerPlayer
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
    object Calendar : KChip("Calendário")
    object Dub : KChip("Só dublados")
    class Country(val country: DoramaCountry) : KChip(country.label)
}

/** Vinheta da aba já tocada nesta abertura do app. */
private var doramaIntroPlayed = false

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
    val homeRows by viewModel.rows.collectAsStateWithLifecycle()
    val homeLoading by viewModel.loading.collectAsStateWithLifecycle()
    val calRows by viewModel.calendar.collectAsStateWithLifecycle()
    val calLoading by viewModel.calLoading.collectAsStateWithLifecycle()
    val newEp by viewModel.newEp.collectAsStateWithLifecycle()
    val hidden by viewModel.hidden.collectAsStateWithLifecycle()
    // Calendário da semana: troca as fileiras da página pelas do calendário (Voltar sai dele).
    var calendarOn by rememberSaveable { mutableStateOf(false) }
    var homeRowIndex by rememberSaveable { mutableIntStateOf(0) }
    val dubOnly by viewModel.dubOnly.collectAsStateWithLifecycle()
    val dubScan by viewModel.dubScan.collectAsStateWithLifecycle()
    val ratings by viewModel.ratings.collectAsStateWithLifecycle()
    val audioMap by viewModel.audio.collectAsStateWithLifecycle()
    val baseRows = if (calendarOn) calRows else homeRows
    // "Só dublados": mostra só o que as fontes confirmaram com áudio em português.
    val rows = if (!dubOnly) baseRows else baseRows.map { r -> r.copy(items = r.items.filter { audioMap[it.key] == "Dublado" }) }.filter { it.items.isNotEmpty() }
    val loading = if (calendarOn) calLoading else homeLoading
    // Menu ☰: num dorama = gostei / não gostei / não tenho interesse; na barra = mostrar escondidos.
    var menuItem by remember { mutableStateOf<DItem?>(null) }
    var menuRestore by remember { mutableStateOf(false) }
    var menuOption by remember { mutableIntStateOf(0) }
    val country by viewModel.country.collectAsStateWithLifecycle()
    val info by viewModel.info.collectAsStateWithLifecycle()
    val progress by viewModel.progress.collectAsStateWithLifecycle()
    val titles by viewModel.titles.collectAsStateWithLifecycle()
    val airing by viewModel.airing.collectAsStateWithLifecycle()
    val audio by viewModel.audio.collectAsStateWithLifecycle()
    val trailers by viewModel.trailers.collectAsStateWithLifecycle()
    val trailerSettings by viewModel.trailerSettings.collectAsStateWithLifecycle()
    var showIntro by remember { mutableStateOf(!doramaIntroPlayed) }

    LaunchedEffect(Unit) {
        extras.refresh()
        val resumeList = withTimeoutOrNull(3_000) { extras.resume.first { it.isNotEmpty() } }.orEmpty()
        val resume = resumeList.map { it.item }
        val seeds = (resume + extras.myList.value)
            .filter { it.mediaType == MediaType.TV }
            .distinctBy { it.id }
            .map { NSSeed(it.mediaType, it.id, it.title) }
        val myList = withTimeoutOrNull(1_500) { extras.myList.first { it.isNotEmpty() } } ?: extras.myList.value
        viewModel.start(seeds, resumeList, myList)
    }

    val chips = remember { listOf(KChip.Surprise, KChip.Refresh, KChip.Calendar, KChip.Dub) + DORAMA_COUNTRIES.map { KChip.Country(it) } }
    var inBar by rememberSaveable { mutableStateOf(false) }
    var chipIndex by rememberSaveable { mutableIntStateOf(4) }
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
        item?.let { viewModel.wantTrailer(it) }
        // "Todos os doramas": perto do fim, busca a próxima página.
        if (row?.id == NetflixDoramasViewModel.ALL_ROW && col >= row.items.size - 8) viewModel.loadMoreAll()
    }

    // Dublado/legendado: só pergunta às fontes se a pessoa parar no item.
    LaunchedEffect(item?.key) {
        val current = item ?: return@LaunchedEffect
        delay(2_500)
        viewModel.wantAudio(current)
        // Já vai descobrindo os próximos da fileira (selo nas capas).
        row?.items?.drop(col + 1)?.take(2)?.forEach { viewModel.wantAudio(it) }
    }

    // "Só dublados" ligado: quando as fileiras mudam (país, calendário, carregamento), verifica as novas também.
    LaunchedEffect(dubOnly, calendarOn, baseRows.size, country.label) {
        if (dubOnly) viewModel.setDubOnly(true, baseRows)
    }

    // Lista do menu ☰ (rótulo -> ação), montada na hora de abrir.
    fun menuOptions(): List<Pair<String, () -> Unit>> {
        val target = menuItem
        if (target == null) return listOf("Mostrar de novo" to { viewModel.restoreHidden() }, "Cancelar" to {})
        val r = ratings[target.key]?.second ?: 0
        return listOf(
            (if (r > 0) "Tirar o \uD83D\uDC4D Gostei" else "\uD83D\uDC4D Gostei") to { viewModel.rate(target, if (r > 0) 0 else 1) },
            (if (r < 0) "Tirar o \uD83D\uDC4E Não gostei" else "\uD83D\uDC4E Não gostei") to { viewModel.rate(target, if (r < 0) 0 else -1) },
            "Não tenho interesse" to { viewModel.hide(target) },
            "Cancelar" to {}
        )
    }

    fun toggleCalendar() {
        if (calendarOn) {
            calendarOn = false
            rowIndex = homeRowIndex
        } else {
            homeRowIndex = rowIndex
            rowIndex = 0
            calendarOn = true
            viewModel.loadCalendar()
        }
    }

    fun pressChip() {
        when (val c = chips[chipIndex]) {
            KChip.Surprise -> viewModel.surprise()?.let { onOpen(it.type, it.id) }
            KChip.Refresh -> { if (calendarOn) { calendarOn = false; rowIndex = homeRowIndex }; viewModel.refresh() }
            KChip.Calendar -> toggleCalendar()
            KChip.Dub -> viewModel.setDubOnly(!dubOnly, baseRows)
            is KChip.Country -> { viewModel.setCountry(c.country); if (calendarOn) viewModel.loadCalendar() }
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
                if (showIntro) return@onPreviewKeyEvent ev.key != Key.Back
                if (menuItem != null || menuRestore) {
                    val opts = menuOptions()
                    when (ev.key) {
                        Key.DirectionUp -> menuOption = (menuOption - 1).coerceAtLeast(0)
                        Key.DirectionDown -> menuOption = (menuOption + 1).coerceAtMost(opts.size - 1)
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            val action = opts.getOrNull(menuOption)?.second
                            menuItem = null; menuRestore = false
                            action?.invoke()
                        }
                        Key.Back, Key.Menu -> { menuItem = null; menuRestore = false }
                        else -> {}
                    }
                    return@onPreviewKeyEvent true
                }
                if (ev.key == Key.Menu) {
                    menuOption = 0
                    if (inBar) { if (hidden.isNotEmpty()) menuRestore = true } else if (item != null) menuItem = item
                    return@onPreviewKeyEvent true
                }
                if (ev.key == Key.Back && calendarOn) { toggleCalendar(); return@onPreviewKeyEvent true }
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
                val trailerKey = item?.let { trailers[it.key] }
                if (trailerKey != null && trailerSettings.autoPlay && !showIntro) {
                    key(trailerKey) {
                        TrailerPlayer(youtubeKey = trailerKey, delayMs = trailerSettings.delaySeconds * 1000L + 1500L,
                            volume = if (trailerSettings.soundEnabled) 1f else 0f, modifier = Modifier.fillMaxSize())
                    }
                }
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
                    Text(when {
                        dubOnly && dubScan.first < dubScan.second -> "Procurando doramas dublados nas suas fontes… (${dubScan.first} de ${dubScan.second})"
                        dubOnly -> "Nenhum dorama dublado encontrado nas suas fontes."
                        calendarOn && loading -> "Montando o calendário da semana…"
                        calendarOn -> "Nenhum episódio marcado para esta semana."
                        loading -> "Separando os melhores doramas para você…"
                        else -> "Nenhum dorama encontrado com esse filtro."
                    },
                        color = PinkSoft.copy(alpha = 0.8f), fontSize = 15.sp)
                    return@Column
                }
                // Sempre o nome em português (o mesmo das outras telas), sem imagem de logo com outra tradução.
                val name = titles[item.key] ?: item.title
                val longName = name.length > 22
                Text(name, color = Color.White, fontSize = if (longName) 24.sp else 30.sp, fontWeight = FontWeight.Black, maxLines = 2,
                    lineHeight = if (longName) 26.sp else 32.sp,
                    overflow = TextOverflow.Ellipsis, style = TextStyle(shadow = Shadow(PinkDeep, blurRadius = 18f)))
                Spacer(Modifier.height(10.dp))
                val resumeInfo = progress[item.key]
                if (row?.id == NetflixDoramasViewModel.CONT_ROW && resumeInfo != null) Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.key in newEp) {
                        AiringPill("Episódio novo disponível")
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(listOfNotNull("Continuar", resumeInfo.label).joinToString(" "), color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    if (resumeInfo.progress > 0) {
                        Spacer(Modifier.width(10.dp))
                        PinkProgress(resumeInfo.progress, Modifier.width(140.dp))
                    }
                } else if (row?.ranked == true) Row(verticalAlignment = Alignment.CenterVertically) {
                    PinkTop10Badge()
                    Spacer(Modifier.width(8.dp))
                    Text("Nº ${col + 1} entre os doramas desta semana", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                } else airing[item.key]?.let { a ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        AiringPill(a)
                        Spacer(Modifier.width(8.dp))
                        Text("Em exibição: episódios novos toda semana", color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (item.vote > 0 && item.votes >= 10) {
                        Icon(Icons.Default.Star, contentDescription = null, tint = DoramaPink, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(3.dp))
                        Text(kRating(item.vote), color = DoramaPink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.width(10.dp))
                    }
                    when (ratings[item.key]?.second) {
                        1 -> { Text("\uD83D\uDC4D", fontSize = 12.sp); Spacer(Modifier.width(6.dp)) }
                        -1 -> { Text("\uD83D\uDC4E", fontSize = 12.sp); Spacer(Modifier.width(6.dp)) }
                        else -> {}
                    }
                    audio[item.key]?.let { a ->
                        Text(a.uppercase(), color = DoramaPink, fontSize = 10.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp,
                            modifier = Modifier.border(1.dp, DoramaPink, RoundedCornerShape(3.dp)).padding(horizontal = 5.dp, vertical = 1.dp))
                        Spacer(Modifier.width(8.dp))
                    }
                    val kind = if (item.type == MediaType.MOVIE) "Filme" else "Dorama"
                    val meta = listOfNotNull(kind, item.year.ifBlank { null }, info[item.key]) + item.genres.take(2)
                    Text(meta.joinToString("  •  "), color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (item.overview.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(item.overview, color = Color.White.copy(alpha = 0.88f), fontSize = 13.sp, lineHeight = 18.sp, maxLines = if (longName || resumeInfo != null) 2 else 3, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    KHint("Mais informações", Icons.Default.Info, primary = false)
                    KHint(if (row?.id == NetflixDoramasViewModel.CONT_ROW) "Continuar" else "Assistir", Icons.Default.PlayArrow, primary = true)
                    KHint("Gostei / Não tenho interesse", Icons.Default.Menu, primary = false)
                }
            }
        }

        // ---- Barra: Surpreenda-me, Atualizar e países ----
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 52.dp, top = 20.dp)
        ) {
            chips.forEachIndexed { i, c ->
                val focused = inBar && i == chipIndex
                val selected = (c is KChip.Country && c.country.label == country.label) || (c is KChip.Calendar && calendarOn) || (c is KChip.Dub && dubOnly)
                val bg = when {
                    focused -> DoramaPink
                    selected -> DoramaPink.copy(alpha = 0.25f)
                    else -> Color.Transparent
                }
                val fg = if (focused) Color.White else if (selected) PinkSoft else Color.White.copy(alpha = 0.8f)
                Row(
                    Modifier.background(bg, RoundedCornerShape(50))
                        .border(1.dp, if (focused || selected) DoramaPink else PinkSoft.copy(alpha = 0.35f), RoundedCornerShape(50))
                        .padding(horizontal = 11.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val icon = when (c) {
                        KChip.Surprise -> Icons.Default.Shuffle; KChip.Refresh -> Icons.Default.Refresh
                        KChip.Calendar -> Icons.Default.DateRange; KChip.Dub -> Icons.Default.RecordVoiceOver; else -> null
                    }
                    if (icon != null) {
                        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                    }
                    Text(c.label, color = fg, fontSize = 13.sp, fontWeight = if (focused || selected) FontWeight.Bold else FontWeight.Medium)
                }
            }
        }

        if (dubOnly && dubScan.first < dubScan.second && rows.isNotEmpty()) {
            Text("Verificando dublados… ${dubScan.first} de ${dubScan.second}", color = PinkSoft.copy(alpha = 0.7f), fontSize = 11.sp,
                modifier = Modifier.align(Alignment.TopEnd).padding(top = 62.dp, end = 56.dp))
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
                                if (r.ranked) KRankCard(j + 1, it, f) else KCard(it, titles[it.key] ?: it.title, progress[it.key]?.progress,
                                    if (it.key in newEp) "NOVO EPISÓDIO" else airing[it.key], audioMap[it.key], f, r.rating)
                            }
                        }
                        Box(Modifier.width(56.dp).height(if (r.ranked) 160.dp else 110.dp)
                            .background(Brush.horizontalGradient(0f to Plum, 0.75f to Plum, 1f to Color.Transparent)))
                    }
                }
            }
        }
        if (menuItem != null || menuRestore) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)), contentAlignment = Alignment.Center) {
                Column(Modifier.width(420.dp).background(PlumCard, RoundedCornerShape(12.dp))
                    .border(1.dp, DoramaPink.copy(alpha = 0.5f), RoundedCornerShape(12.dp)).padding(20.dp)) {
                    val target = menuItem
                    Text(if (target != null) (titles[target.key] ?: target.title) else "Doramas escondidos",
                        color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.height(4.dp))
                    Text(if (target != null) "Gostei / Não gostei ajusta as recomendações. \"Não tenho interesse\" tira da aba." else "Você escondeu ${hidden.size} da aba.",
                        color = PinkSoft.copy(alpha = 0.8f), fontSize = 13.sp)
                    Spacer(Modifier.height(14.dp))
                    menuOptions().map { it.first }.forEachIndexed { i, label ->
                        val f = i == menuOption
                        Text(label, color = if (f) Color.White else Color.White.copy(alpha = 0.75f), fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp)
                                .background(if (f) DoramaPink else Color.Transparent, RoundedCornerShape(6.dp)).padding(horizontal = 12.dp, vertical = 8.dp))
                    }
                }
            }
        }
        if (showIntro) DoramaIntro { doramaIntroPlayed = true; showIntro = false }
    }
}

/** Vinheta da aba: coração e "DORAMAS" em rosa crescendo e sumindo (uma vez por abertura do app). */
@Composable
private fun DoramaIntro(onDone: () -> Unit) {
    val scale = remember { Animatable(0.7f) }
    val alpha = remember { Animatable(0f) }
    val bg = remember { Animatable(1f) }
    LaunchedEffect(Unit) {
        alpha.animateTo(1f, tween(300))
        scale.animateTo(1.1f, tween(850, easing = FastOutSlowInEasing))
        scale.animateTo(5f, tween(420, easing = FastOutSlowInEasing))
        alpha.animateTo(0f, tween(150))
        bg.animateTo(0f, tween(250))
        onDone()
    }
    Box(Modifier.fillMaxSize().alpha(bg.value).background(Brush.radialGradient(listOf(PinkDeep.copy(alpha = 0.55f), Plum), radius = 1400f)),
        contentAlignment = Alignment.Center) {
        Row(Modifier.scale(scale.value).alpha(alpha.value), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Favorite, contentDescription = null, tint = DoramaPink, modifier = Modifier.size(64.dp))
            Spacer(Modifier.width(14.dp))
            Text("DORAMAS", color = DoramaPink, fontSize = 72.sp, fontWeight = FontWeight.Black, letterSpacing = 8.sp,
                style = TextStyle(shadow = Shadow(PinkSoft.copy(alpha = 0.6f), blurRadius = 30f)))
        }
    }
}

/** Selo "Ep. 7 sábado" / "Ep. novo hoje". */
@Composable
private fun AiringPill(text: String, modifier: Modifier = Modifier, small: Boolean = false) {
    Text(text, color = Color.White, fontSize = if (small) 9.sp else 11.sp, fontWeight = FontWeight.Black, maxLines = 1,
        modifier = modifier.background(DoramaPink, RoundedCornerShape(3.dp)).padding(horizontal = if (small) 4.dp else 6.dp, vertical = 1.dp))
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
private fun KCard(item: DItem, name: String, progress: Int?, airing: String?, audio: String?, focused: Boolean, showRating: Boolean) {
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(160), label = "kc")
    Box(
        Modifier.zIndex(if (focused) 1f else 0f).scale(scale).size(width = 176.dp, height = 99.dp).clip(RoundedCornerShape(6.dp))
            .background(PlumCard)
            .border(if (focused) 3.dp else 0.dp, if (focused) DoramaPink else Color.Transparent, RoundedCornerShape(6.dp))
    ) {
        AsyncImage(model = item.backdrop, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Plum.copy(alpha = 0.85f))))
        val hasBar = progress != null && progress > 0
        Text(name, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.align(Alignment.BottomStart).padding(start = 8.dp, end = 8.dp, bottom = if (hasBar) 12.dp else 6.dp))
        if (hasBar) PinkProgress(progress!!, Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 5.dp))
        if (airing != null) AiringPill(airing, Modifier.align(Alignment.TopStart).padding(5.dp), small = true)
        Column(Modifier.align(Alignment.TopEnd).padding(5.dp), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
            if (showRating && item.vote > 0) {
                Row(Modifier.background(Plum.copy(alpha = 0.8f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 5.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Star, contentDescription = null, tint = DoramaPink, modifier = Modifier.size(10.dp))
                    Spacer(Modifier.width(2.dp))
                    Text(kRating(item.vote), color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }
            if (audio != null) {
                Text(if (audio == "Dublado") "DUB" else "LEG", color = if (audio == "Dublado") Color.White else PinkSoft, fontSize = 9.sp,
                    fontWeight = FontWeight.Black, letterSpacing = 1.sp,
                    modifier = Modifier.background(if (audio == "Dublado") DoramaPink else Plum.copy(alpha = 0.85f), RoundedCornerShape(3.dp))
                        .padding(horizontal = 4.dp, vertical = 1.dp))
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
