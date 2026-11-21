package com.arflix.tv.ui.screens.modes

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
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
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.modes.StreamingModes
import com.arflix.tv.ui.components.TrailerPlayer
import com.arflix.tv.ui.screens.collections.ServiceHome
import com.arflix.tv.ui.screens.collections.ServiceHomeViewModel
import com.arflix.tv.ui.screens.collections.serviceRowTitle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val NetflixRed = Color(0xFFE50914)
private val NetflixBg = Color(0xFF000000)

/** Fileira da Home Netflix (do catálogo do serviço ou pessoal: Continuar assistindo, Minha lista). */
private data class NRow(val id: String, val title: String, val items: List<MediaItem>, val top10: Boolean = false,
                        val originals: Boolean = false, val progress: Map<String, Int> = emptyMap())

/** Item do menu lateral (como no app da Netflix na TV). */
private data class NavEntry(val label: String, val icon: ImageVector, val action: () -> Unit, val filter: ServiceHome.Filter? = null)

/**
 * Home no estilo do app da Netflix para TV: menu lateral recolhido à esquerda (abre ao focar), área de
 * destaque no alto que acompanha o título focado (fundo, logo, selos, sinopse e trailer) e fileiras de capas
 * com o foco sempre na primeira posição; Top 10 com números grandes.
 */
@Composable
internal fun NetflixTvHome(
    mode: StreamingModes.Mode,
    onOpen: (item: MediaItem, play: Boolean) -> Unit,
    onSearch: () -> Unit,
    onMyList: () -> Unit,
    onSettings: () -> Unit,
    onPickMode: () -> Unit,
    onSwitchProfile: () -> Unit,
    onBack: () -> Unit,
    onNewPopular: () -> Unit = {},
    onDiscover: () -> Unit = {},
    viewModel: ServiceHomeViewModel = hiltViewModel(),
    extras: NetflixExtrasViewModel = hiltViewModel()
) {
    val catalog = remember(mode.key) { StreamingModes.catalog(mode) }
    LaunchedEffect(catalog.id) { viewModel.load(catalog) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val logos by viewModel.cardLogoUrls.collectAsStateWithLifecycle()
    val trailerSettings by viewModel.trailerSettings.collectAsStateWithLifecycle()
    // Fileiras pessoais: recarregam ao voltar para a Home (ex.: depois de assistir).
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    androidx.compose.runtime.DisposableEffect(lifecycle) {
        val obs = androidx.lifecycle.LifecycleEventObserver { _, e -> if (e == androidx.lifecycle.Lifecycle.Event.ON_RESUME) extras.refresh() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }
    val resume by extras.resume.collectAsStateWithLifecycle()
    val myList by extras.myList.collectAsStateWithLifecycle()
    val serviceRows = state.page.rows.filter { it.items.isNotEmpty() }.map { r ->
        NRow(r.row.id, serviceRowTitle(r.row.id, mode.name), r.items, top10 = r.row.id.contains("top10"),
            originals = r.row.id == ServiceHome.ORIGINALS_ROW_ID)
    }
    val showPersonal = state.filter == ServiceHome.Filter.ALL
    val rows = buildList {
        if (showPersonal && resume.isNotEmpty()) add(NRow("sm_cw", "Continuar assistindo", resume.map { it.item },
            progress = resume.associate { "${it.item.mediaType}_${it.item.id}" to it.progress }))
        serviceRows.firstOrNull()?.let(::add)
        if (showPersonal && myList.isNotEmpty()) add(NRow("sm_mylist", "Minha lista", myList))
        addAll(serviceRows.drop(1))
    }
    val billboard = state.page.billboard ?: serviceRows.firstOrNull()?.items?.firstOrNull { !it.backdrop.isNullOrBlank() }

    // ---- Foco ----
    var navOpen by rememberSaveable { mutableStateOf(false) }
    var navIndex by rememberSaveable { mutableIntStateOf(2) } // Início
    var inHero by rememberSaveable { mutableStateOf(true) }
    var heroButton by rememberSaveable { mutableIntStateOf(0) }
    var rowIndex by rememberSaveable { mutableIntStateOf(0) }
    val colByRow = remember { mutableStateMapOf<String, Int>() }

    val navEntries = remember(mode.key) {
        listOf(
            NavEntry("Perfil", Icons.Default.Person, onSwitchProfile),
            NavEntry("Buscar", Icons.Default.Search, onSearch),
            NavEntry("Início", Icons.Default.Home, {}, ServiceHome.Filter.ALL),
            NavEntry("Séries", Icons.Default.Tv, {}, ServiceHome.Filter.SERIES),
            NavEntry("Filmes", Icons.Default.Movie, {}, ServiceHome.Filter.MOVIES),
            NavEntry("Novidades e populares", Icons.Default.Add, onNewPopular),
            NavEntry("Descobrir", Icons.Default.Add, onDiscover),
            NavEntry("Minha Netflix", Icons.Default.Add, onMyList),
            NavEntry("Trocar modo", Icons.Default.SwapHoriz, onPickMode),
            NavEntry("Configurações", Icons.Default.Settings, onSettings)
        )
    }
    val selectedNav = when (state.filter) {
        ServiceHome.Filter.SERIES -> 3
        ServiceHome.Filter.MOVIES -> 4
        else -> 2
    }

    val safeRow = rowIndex.coerceIn(0, (rows.size - 1).coerceAtLeast(0))
    val currentRow = rows.getOrNull(safeRow)
    val currentCol = currentRow?.let { (colByRow[it.id] ?: 0).coerceIn(0, it.items.size - 1) } ?: 0
    val focusedItem: MediaItem? = if (inHero || navOpen && inHero) billboard else currentRow?.items?.getOrNull(currentCol)
    val previewItem = focusedItem ?: billboard

    LaunchedEffect(previewItem?.id, previewItem?.mediaType) {
        val item = previewItem ?: return@LaunchedEffect
        viewModel.focus(item)
        val row = currentRow?.items.orEmpty()
        val at = row.indexOf(item).coerceAtLeast(0)
        viewModel.preloadLogos(listOf(item) + row.drop(at).take(8))
    }
    LaunchedEffect(rows.size) { rows.take(3).forEach { viewModel.preloadLogos(it.items.take(8)) } }

    val featured = state.featured?.takeIf { previewItem != null && it.id == previewItem.id && it.mediaType == previewItem.mediaType }
    val trailerKey = state.trailer?.takeIf { trailerSettings.autoPlay && it.isFor(previewItem) }?.youtubeKey

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(10) { if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect; delay(100) } }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(safeRow, inHero, rows.size) {
        if (rows.isNotEmpty()) listState.animateScrollToItem(if (inHero) 0 else safeRow)
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(NetflixBg)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent ev.key in HANDLED
                when {
                    // Barra no topo (layout oficial da Netflix desde maio/2025).
                    navOpen -> when (ev.key) {
                        Key.DirectionLeft -> { navIndex = (navIndex - 1).coerceAtLeast(0); true }
                        Key.DirectionRight -> { navIndex = (navIndex + 1).coerceAtMost(navEntries.size - 1); true }
                        Key.DirectionDown -> { navOpen = false; true }
                        Key.DirectionUp -> true
                        Key.Back -> { onBack(); true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            val e = navEntries[navIndex]
                            navOpen = false
                            if (e.filter != null) {
                                if (e.filter != state.filter) viewModel.selectFilter(e.filter)
                                inHero = true; heroButton = 0; rowIndex = 0; colByRow.clear()
                            } else e.action()
                            true
                        }
                        else -> false
                    }
                    inHero -> when (ev.key) {
                        Key.DirectionLeft -> { if (heroButton > 0) heroButton--; true }
                        Key.DirectionRight -> { heroButton = (heroButton + 1).coerceAtMost(1); true }
                        Key.DirectionDown -> { if (rows.isNotEmpty()) { inHero = false; rowIndex = 0 }; true }
                        Key.DirectionUp -> { navIndex = selectedNav; navOpen = true; true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { billboard?.let { onOpen(it, heroButton == 0) }; true }
                        Key.MediaPlay, Key.MediaPlayPause -> { billboard?.let { onOpen(it, true) }; true }
                        Key.Back -> { navIndex = selectedNav; navOpen = true; true }
                        else -> false
                    }
                    else -> when (ev.key) {
                        Key.DirectionLeft -> {
                            val row = currentRow
                            if (row != null && currentCol > 0) colByRow[row.id] = currentCol - 1
                            true
                        }
                        Key.DirectionRight -> {
                            currentRow?.let { colByRow[it.id] = (currentCol + 1).coerceAtMost(it.items.size - 1) }
                            true
                        }
                        Key.DirectionUp -> { if (safeRow == 0) inHero = true else rowIndex = safeRow - 1; true }
                        Key.DirectionDown -> { rowIndex = (safeRow + 1).coerceAtMost(rows.size - 1); true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { focusedItem?.let { onOpen(it, false) }; true }
                        Key.MediaPlay, Key.MediaPlayPause -> { focusedItem?.let { onOpen(it, true) }; true }
                        Key.Back -> {
                            if (currentCol > 0) currentRow?.let { colByRow[it.id] = 0 } else { inHero = true }
                            scope.launch { listState.animateScrollToItem(0) }
                            true
                        }
                        else -> false
                    }
                }
            }
    ) {
        val screenH = maxHeight
        val previewH = screenH * 0.70f

        // ---- Destaque (acompanha o título focado) ----
        NetflixPreview(
            item = featured ?: previewItem,
            logoUrl = previewItem?.let { logos["${it.mediaType}_${it.id}"] },
            rank = previewItem?.let { rankOf(it, rows) },
            isOriginal = previewItem?.let { item -> rows.any { it.originals && it.items.any { i -> i.id == item.id } } } == true,
            trailerKey = trailerKey,
            trailerDelayMs = trailerSettings.delaySeconds * 1000L + 1200L,
            trailerVolume = if (trailerSettings.soundEnabled) 1f else 0f,
            showButtons = inHero,
            focusedButton = if (inHero && !navOpen) heroButton else -1,
            modifier = Modifier.fillMaxWidth().height(previewH)
        )

        // ---- Fileiras ----
        val rowsTop = if (inHero) previewH + 6.dp else previewH * 0.74f
        val animatedTop by animateDpAsState(rowsTop, tween(320), label = "rowsTop")
        LazyColumn(
            state = listState,
            userScrollEnabled = false,
            contentPadding = PaddingValues(bottom = screenH),
            modifier = Modifier
                .fillMaxSize()
                .padding(top = animatedTop)
        ) {
            itemsIndexed(rows, key = { _, r -> r.id }) { index, r ->
                val focusedRow = !inHero && !navOpen && index == safeRow
                NetflixRow(
                    title = r.title,
                    items = r.items,
                    top10 = r.top10,
                    focusedCol = if (focusedRow) (colByRow[r.id] ?: 0) else -1,
                    restingCol = colByRow[r.id] ?: 0,
                    logos = logos,
                    originals = r.originals,
                    progress = r.progress
                )
            }
        }

        if (state.page.isLoading && rows.isEmpty()) {
            Text("Carregando…", color = Color.White.copy(alpha = 0.6f), fontSize = 18.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 56.dp, bottom = 120.dp))
        }

        // ---- Barra no topo ----
        NetflixTopBar(
            entries = navEntries,
            focusedIndex = if (navOpen) navIndex else -1,
            selectedIndex = selectedNav,
            modifier = Modifier.fillMaxWidth().zIndex(10f)
        )
    }
}

private val HANDLED = setOf(
    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.DirectionCenter,
    Key.Enter, Key.NumPadEnter, Key.Back, Key.MediaPlay, Key.MediaPlayPause
)

/** Posição no Top 10 (séries ou filmes) do título, para o selo "Nº X em ... hoje". */
private fun rankOf(item: MediaItem, rows: List<NRow>): Pair<Int, Boolean>? {
    rows.filter { it.top10 }.forEach { r ->
        val i = r.items.indexOfFirst { it.id == item.id && it.mediaType == item.mediaType }
        if (i in 0..9) return (i + 1) to (item.mediaType == MediaType.TV)
    }
    return null
}

@Composable
private fun NetflixPreview(
    item: MediaItem?,
    logoUrl: String?,
    rank: Pair<Int, Boolean>?,
    isOriginal: Boolean,
    trailerKey: String?,
    trailerDelayMs: Long,
    trailerVolume: Float,
    showButtons: Boolean,
    focusedButton: Int,
    modifier: Modifier
) {
    Box(modifier) {
        // Fundo à direita, com degradê para o preto (esquerda e baixo), como no app.
        Box(Modifier.fillMaxHeight().fillMaxWidth(0.72f).align(Alignment.TopEnd)) {
            if (item?.backdrop != null) {
                AsyncImage(
                    model = item.backdrop,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
            if (trailerKey != null) {
                TrailerPlayer(youtubeKey = trailerKey, delayMs = trailerDelayMs, volume = trailerVolume, modifier = Modifier.fillMaxSize())
            }
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(
                0f to NetflixBg, 0.35f to NetflixBg.copy(alpha = 0.55f), 0.7f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(
                0.55f to Color.Transparent, 1f to NetflixBg)))
        }
        if (item == null) return@Box
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(start = 56.dp, top = 66.dp)
                .widthIn(max = 430.dp)
        ) {
            if (isOriginal) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("N", color = NetflixRed, fontSize = 20.sp, fontWeight = FontWeight.Black)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        if (item.mediaType == MediaType.TV) "S É R I E" else "F I L M E",
                        color = Color.White.copy(alpha = 0.85f), fontSize = 11.sp, fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(6.dp))
            }
            if (!logoUrl.isNullOrBlank()) {
                AsyncImage(
                    model = logoUrl,
                    contentDescription = item.title,
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart,
                    modifier = Modifier.heightIn(max = 72.dp).widthIn(max = 300.dp)
                )
            } else {
                Text(item.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black, maxLines = 2,
                    overflow = TextOverflow.Ellipsis, lineHeight = 32.sp)
            }
            Spacer(Modifier.height(12.dp))
            if (rank != null) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Top10Badge()
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Nº ${rank.first} em ${if (rank.second) "séries" else "filmes"} hoje",
                        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
            val meta = listOfNotNull(
                item.year.takeIf { it.isNotBlank() },
                item.contentRating?.substringAfterLast(' ')?.takeIf { it.isNotBlank() },
                item.duration.takeIf { it.isNotBlank() },
                "HD"
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                meta.forEachIndexed { i, m ->
                    if (i == 1 && item.contentRating != null) {
                        Box(Modifier.border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(2.dp)).padding(horizontal = 5.dp, vertical = 1.dp)) {
                            Text(m, color = Color.White, fontSize = 11.sp)
                        }
                    } else if (m == "HD") {
                        Box(Modifier.border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(2.dp)).padding(horizontal = 4.dp)) {
                            Text("HD", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        }
                    } else Text(m, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
                }
            }
            if (item.overview.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    item.overview, color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp, lineHeight = 18.sp,
                    maxLines = if (showButtons) 3 else 2, overflow = TextOverflow.Ellipsis,
                    style = TextStyle(shadow = Shadow(Color.Black, blurRadius = 6f))
                )
            }
            AnimatedVisibility(visible = showButtons, enter = fadeIn(), exit = fadeOut()) {
                Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HeroButton("Assistir", Icons.Default.PlayArrow, focused = focusedButton == 0, primary = true)
                    HeroButton("Mais informações", Icons.Default.Info, focused = focusedButton == 1, primary = false)
                }
            }
        }
    }
}

@Composable
private fun Top10Badge() {
    Box(
        Modifier.size(width = 26.dp, height = 30.dp).background(NetflixRed, RoundedCornerShape(2.dp)),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("TOP", color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Black, lineHeight = 8.sp)
            Text("10", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Black, lineHeight = 13.sp)
        }
    }
}

@Composable
private fun HeroButton(label: String, icon: ImageVector, focused: Boolean, primary: Boolean) {
    val bg = when {
        focused -> Color.White
        primary -> Color.White.copy(alpha = 0.25f)
        else -> Color.White.copy(alpha = 0.15f)
    }
    val fg = if (focused) Color.Black else Color.White
    Row(
        modifier = Modifier
            .scale(if (focused) 1.05f else 1f)
            .background(bg, RoundedCornerShape(4.dp))
            .padding(horizontal = 18.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(8.dp))
        Text(label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun NetflixRow(
    title: String,
    items: List<MediaItem>,
    top10: Boolean,
    focusedCol: Int,
    restingCol: Int,
    logos: Map<String, String>,
    originals: Boolean,
    progress: Map<String, Int> = emptyMap()
) {
    val rowState = rememberLazyListState()
    val target = if (focusedCol >= 0) focusedCol else restingCol
    LaunchedEffect(target) { rowState.animateScrollToItem(target.coerceAtLeast(0)) }
    Column(Modifier.padding(bottom = 18.dp)) {
        Text(
            title, color = Color.White.copy(alpha = if (focusedCol >= 0) 1f else 0.8f),
            fontSize = 15.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(start = 56.dp, bottom = 6.dp)
        )
        Box {
            LazyRow(
                state = rowState,
                userScrollEnabled = false,
                contentPadding = PaddingValues(start = 56.dp, end = 400.dp),
                horizontalArrangement = Arrangement.spacedBy(if (top10) 4.dp else 8.dp)
            ) {
                itemsIndexed(items, key = { _, it -> "${it.mediaType}_${it.id}" }) { i, item ->
                    if (top10) Top10Card(rank = i + 1, item = item, focused = i == focusedCol)
                    else LandscapeCard(item = item, logo = logos["${item.mediaType}_${item.id}"], focused = i == focusedCol, original = originals,
                        progress = progress["${item.mediaType}_${item.id}"])
                }
            }
            // As capas que já passaram somem à esquerda (o foco fica sempre na primeira posição).
            Box(
                Modifier.width(56.dp).height(if (top10) 160.dp else 110.dp)
                    .background(Brush.horizontalGradient(0f to NetflixBg, 0.75f to NetflixBg, 1f to Color.Transparent))
            )
        }
    }
}

@Composable
private fun LandscapeCard(item: MediaItem, logo: String?, focused: Boolean, original: Boolean, progress: Int? = null) {
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(160), label = "cardScale")
    Box(
        modifier = Modifier
            .zIndex(if (focused) 1f else 0f)
            .scale(scale)
            .size(width = 176.dp, height = 99.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF232323))
            .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(4.dp))
    ) {
        AsyncImage(
            model = item.backdrop ?: item.image,
            contentDescription = item.title,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))))
        if (!logo.isNullOrBlank()) {
            AsyncImage(
                model = logo, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 14.dp, vertical = 6.dp).height(30.dp).fillMaxWidth()
            )
        } else {
            Text(item.title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
        }
        if (original) {
            Text("N", color = NetflixRed, fontSize = 20.sp, fontWeight = FontWeight.Black,
                modifier = Modifier.align(Alignment.TopStart).padding(start = 6.dp, top = 2.dp))
        }
        // Barra de progresso do "Continuar assistindo".
        if (progress != null && progress > 0) {
            Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color(0xFF5A5A5A))) {
                Box(Modifier.fillMaxWidth(progress / 100f).height(3.dp).background(NetflixRed))
            }
        }
    }
}

@Composable
private fun Top10Card(rank: Int, item: MediaItem, focused: Boolean) {
    val scale by animateFloatAsState(if (focused) 1.06f else 1f, tween(160), label = "top10Scale")
    Row(Modifier.zIndex(if (focused) 1f else 0f).scale(scale), verticalAlignment = Alignment.Bottom) {
        // Número grande com contorno, encostado na capa (como o Top 10 da Netflix).
        Box(Modifier.width(if (rank >= 10) 118.dp else 74.dp).height(150.dp), contentAlignment = Alignment.BottomEnd) {
            Text(
                rank.toString(),
                color = Color(0xFF141414),
                fontSize = 150.sp,
                fontWeight = FontWeight.Black,
                lineHeight = 150.sp,
                modifier = Modifier.offset(x = 10.dp, y = 30.dp).drawWithContent {
                    drawContent()
                },
                style = TextStyle(
                    drawStyle = Stroke(width = 5f),
                    shadow = Shadow(Color.Black, blurRadius = 4f)
                )
            )
            Text(
                rank.toString(),
                color = Color(0xFF8C8C8C),
                fontSize = 150.sp,
                fontWeight = FontWeight.Black,
                lineHeight = 150.sp,
                modifier = Modifier.offset(x = 10.dp, y = 30.dp),
                style = TextStyle(drawStyle = Stroke(width = 4f))
            )
        }
        Box(
            Modifier
                .size(width = 100.dp, height = 150.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF232323))
                .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(4.dp))
        ) {
            AsyncImage(model = item.image.ifBlank { item.backdrop }, contentDescription = item.title,
                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

@Composable
private fun NetflixNavRail(entries: List<NavEntry>, open: Boolean, focusedIndex: Int, selectedIndex: Int, modifier: Modifier) {
    val width by animateDpAsState(if (open) 300.dp else 88.dp, tween(220), label = "railWidth")
    Box(
        modifier
            .width(if (open) 720.dp else 160.dp)
            .background(
                Brush.horizontalGradient(
                    0f to Color.Black.copy(alpha = if (open) 0.97f else 0.85f),
                    (if (open) 0.42f else 0.55f) to Color.Black.copy(alpha = if (open) 0.85f else 0.35f),
                    1f to Color.Transparent
                )
            )
    ) {
        Column(
            modifier = Modifier.width(width).fillMaxHeight().padding(start = 28.dp),
            verticalArrangement = Arrangement.Center
        ) {
            entries.forEachIndexed { i, e ->
                val focused = open && i == focusedIndex
                val selected = i == selectedIndex
                Row(
                    modifier = Modifier
                        .padding(vertical = 5.dp)
                        .then(if (focused) Modifier.background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(6.dp)) else Modifier)
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val tint = when {
                        focused -> Color.White
                        selected -> Color.White
                        else -> Color.White.copy(alpha = 0.55f)
                    }
                    if (i == 0) {
                        Box(Modifier.size(28.dp).background(Color(0xFF2B6CE0), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                            Icon(e.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                        }
                    } else Icon(e.icon, contentDescription = e.label, tint = tint, modifier = Modifier.size(24.dp))
                    if (open) {
                        Spacer(Modifier.width(18.dp))
                        Text(
                            e.label, color = tint,
                            fontSize = if (focused) 18.sp else 16.sp,
                            fontWeight = if (focused || selected) FontWeight.Bold else FontWeight.Medium
                        )
                    }
                }
                if (selected && !open) {
                    Box(Modifier.padding(start = 16.dp).size(width = 22.dp, height = 3.dp).background(NetflixRed, RoundedCornerShape(2.dp)))
                }
            }
        }
    }
}


/**
 * Barra de navegação no topo, como no app da Netflix para TV desde maio/2025: perfil, Buscar, Início, Séries,
 * Filmes e Minha Netflix à esquerda; Trocar modo e Configurações (do app) à direita.
 */
@Composable
private fun NetflixTopBar(entries: List<NavEntry>, focusedIndex: Int, selectedIndex: Int, modifier: Modifier) {
    Box(modifier.background(Brush.verticalGradient(0f to Color.Black.copy(alpha = 0.75f), 1f to Color.Transparent))) {
        Row(
            Modifier.fillMaxWidth().padding(start = 48.dp, end = 40.dp, top = 14.dp, bottom = 18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            entries.forEachIndexed { i, e ->
                if (i == entries.size - 2) Spacer(Modifier.weight(1f))
                val focused = i == focusedIndex
                val selected = i == selectedIndex
                val iconOnly = i == 0 || i == 1 || i >= entries.size - 2
                Box(
                    Modifier
                        .padding(horizontal = 4.dp)
                        .background(if (focused) Color.White else Color.Transparent, RoundedCornerShape(if (iconOnly) 50 else 6))
                        .padding(horizontal = if (iconOnly) 7.dp else 12.dp, vertical = 5.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val fg = when {
                        focused -> Color.Black
                        selected -> Color.White
                        else -> Color.White.copy(alpha = 0.7f)
                    }
                    when {
                        i == 0 -> Box(Modifier.size(24.dp).background(Color(0xFF2B6CE0), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                            Icon(e.icon, contentDescription = e.label, tint = Color.White, modifier = Modifier.size(18.dp))
                        }
                        iconOnly -> Icon(e.icon, contentDescription = e.label, tint = fg, modifier = Modifier.size(20.dp))
                        else -> Text(e.label, color = fg, fontSize = 15.sp, fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium)
                    }
                }
            }
        }
    }
}
