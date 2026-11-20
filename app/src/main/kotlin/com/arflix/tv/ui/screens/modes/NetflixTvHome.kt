package com.arflix.tv.ui.screens.modes

import androidx.compose.animation.animateContentSize
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.Event
import androidx.compose.material.icons.filled.FiberNew
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SentimentSatisfied
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
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
import com.arflix.tv.ui.components.movieGenreNameRes
import com.arflix.tv.ui.components.tvGenreNameRes
import com.arflix.tv.ui.screens.collections.ServiceHome
import com.arflix.tv.ui.screens.collections.ServiceHomeViewModel
import com.arflix.tv.ui.screens.collections.serviceRowTitle
import kotlinx.coroutines.delay

private val NetflixRed = Color(0xFFE50914)
private val NetflixBg = Color(0xFF000000)
private val SideMargin = 40.dp
private val TopBarH = 56.dp

/** Fileira da Home Netflix (do catálogo do serviço ou pessoal: Continuar assistindo, Minha lista). */
private data class NRow(
    val id: String, val title: String, val items: List<MediaItem>, val top10: Boolean = false,
    val originals: Boolean = false, val newRow: Boolean = false,
    val progress: Map<String, Int> = emptyMap(), val resume: Map<String, NetflixResume> = emptyMap()
)

/** Item da barra do topo. */
private data class NavEntry(val label: String, val icon: ImageVector?, val action: () -> Unit, val filter: ServiceHome.Filter? = null)

/**
 * Home no layout atual do app da Netflix para TV (redesenho de maio/2025, conferido nas imagens oficiais):
 * - barra no topo: avatar do perfil com ▾ à esquerda (abre o menu do perfil), 🔍 Início Séries Filmes
 *   Novidades Minha Netflix centralizados com o item atual numa pílula, "N" vermelho à direita;
 * - destaque num cartão grande de cantos arredondados (logo, "N SÉRIE", gêneros/ano/classificação e um selo);
 * - fileira focada: a capa focada vira um cartão horizontal grande (com selos), as vizinhas viram pôsteres
 *   altos e a sinopse aparece embaixo da fileira; as outras fileiras ficam pequenas.
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
    viewModel: ServiceHomeViewModel = hiltViewModel(),
    extras: NetflixExtrasViewModel = hiltViewModel()
) {
    val catalog = remember(mode.key) { StreamingModes.catalog(mode) }
    LaunchedEffect(catalog.id) { viewModel.load(catalog) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val logos by viewModel.cardLogoUrls.collectAsStateWithLifecycle()
    val trailerSettings by viewModel.trailerSettings.collectAsStateWithLifecycle()
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
            originals = r.row.id == ServiceHome.ORIGINALS_ROW_ID, newRow = r.row.id == ServiceHome.NEW_ROW_ID)
    }
    val showPersonal = state.filter == ServiceHome.Filter.ALL
    val rows = buildList {
        if (showPersonal && resume.isNotEmpty()) add(NRow("sm_cw", "Continuar assistindo", resume.map { it.item },
            progress = resume.associate { "${it.item.mediaType}_${it.item.id}" to it.progress },
            resume = resume.associateBy { "${it.item.mediaType}_${it.item.id}" }))
        serviceRows.firstOrNull()?.let(::add)
        if (showPersonal && myList.isNotEmpty()) add(NRow("sm_mylist", "Minha lista", myList))
        addAll(serviceRows.drop(1))
    }
    val billboard = state.page.billboard ?: serviceRows.firstOrNull()?.items?.firstOrNull { !it.backdrop.isNullOrBlank() }

    // ---- Foco: barra do topo / destaque (seção 0) / fileiras (seção 1..n) ----
    var inTopBar by rememberSaveable { mutableStateOf(false) }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var menuIndex by rememberSaveable { mutableIntStateOf(0) }
    var navIndex by rememberSaveable { mutableIntStateOf(2) }
    var section by rememberSaveable { mutableIntStateOf(0) }
    val colByRow = remember { mutableStateMapOf<String, Int>() }

    val navEntries = remember(mode.key) {
        listOf(
            NavEntry("Perfil", null, {}),
            NavEntry("Buscar", Icons.Default.Search, onSearch),
            NavEntry("Início", null, {}, ServiceHome.Filter.ALL),
            NavEntry("Séries", null, {}, ServiceHome.Filter.SERIES),
            NavEntry("Filmes", null, {}, ServiceHome.Filter.MOVIES),
            NavEntry("Novidades e populares", null, onNewPopular),
            NavEntry("Minha Netflix", null, onMyList)
        )
    }
    val profileMenu = remember(mode.key) {
        listOf("Trocar perfil" to onSwitchProfile, "Trocar modo" to onPickMode, "Configurações do app" to onSettings)
    }
    val selectedNav = when (state.filter) {
        ServiceHome.Filter.SERIES -> 3
        ServiceHome.Filter.MOVIES -> 4
        else -> 2
    }

    val safeSection = section.coerceIn(0, rows.size)
    val currentRow = rows.getOrNull(safeSection - 1)
    val currentCol = currentRow?.let { (colByRow[it.id] ?: 0).coerceIn(0, it.items.size - 1) } ?: 0
    val focusedItem: MediaItem? = if (safeSection == 0) billboard else currentRow?.items?.getOrNull(currentCol)

    LaunchedEffect(focusedItem?.id, focusedItem?.mediaType) {
        val item = focusedItem ?: return@LaunchedEffect
        viewModel.focus(item)
        val row = currentRow?.items.orEmpty()
        val at = row.indexOf(item).coerceAtLeast(0)
        viewModel.preloadLogos(listOf(item) + row.drop(at).take(6))
    }
    LaunchedEffect(rows.size) { rows.take(3).forEach { viewModel.preloadLogos(it.items.take(6)) } }
    val featured = state.featured?.takeIf { focusedItem != null && it.id == focusedItem.id && it.mediaType == focusedItem.mediaType }
    val trailerKey = state.trailer?.takeIf { trailerSettings.autoPlay && it.isFor(focusedItem) }?.youtubeKey
    val trailerDelay = trailerSettings.delaySeconds * 1000L + 2500L
    val trailerVolume = if (trailerSettings.soundEnabled) 1f else 0f

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(10) { if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect; delay(100) } }
    val listState = rememberLazyListState()
    LaunchedEffect(safeSection, inTopBar, rows.size) {
        listState.animateScrollToItem(if (inTopBar) 0 else safeSection)
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
                    menuOpen -> when (ev.key) {
                        Key.DirectionUp -> { menuIndex = (menuIndex - 1).coerceAtLeast(0); true }
                        Key.DirectionDown -> { menuIndex = (menuIndex + 1).coerceAtMost(profileMenu.size - 1); true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { menuOpen = false; profileMenu[menuIndex].second(); true }
                        Key.Back, Key.DirectionRight, Key.DirectionLeft -> { menuOpen = false; true }
                        else -> true
                    }
                    inTopBar -> when (ev.key) {
                        Key.DirectionLeft -> { navIndex = (navIndex - 1).coerceAtLeast(0); true }
                        Key.DirectionRight -> { navIndex = (navIndex + 1).coerceAtMost(navEntries.size - 1); true }
                        Key.DirectionDown -> { inTopBar = false; true }
                        Key.DirectionUp -> true
                        Key.Back -> { onBack(); true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            val e = navEntries[navIndex]
                            when {
                                navIndex == 0 -> { menuOpen = true; menuIndex = 0 }
                                e.filter != null -> {
                                    if (e.filter != state.filter) viewModel.selectFilter(e.filter)
                                    inTopBar = false; section = 0; colByRow.clear()
                                }
                                else -> e.action()
                            }
                            true
                        }
                        else -> false
                    }
                    else -> when (ev.key) {
                        Key.DirectionUp -> { if (safeSection == 0) { inTopBar = true; navIndex = selectedNav } else section = safeSection - 1; true }
                        Key.DirectionDown -> { section = (safeSection + 1).coerceAtMost(rows.size); true }
                        Key.DirectionLeft -> { currentRow?.let { if (currentCol > 0) colByRow[it.id] = currentCol - 1 }; true }
                        Key.DirectionRight -> { currentRow?.let { colByRow[it.id] = (currentCol + 1).coerceAtMost(it.items.size - 1) }; true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { focusedItem?.let { onOpen(it, false) }; true }
                        Key.MediaPlay, Key.MediaPlayPause -> { focusedItem?.let { onOpen(it, true) }; true }
                        Key.Back -> {
                            when {
                                currentRow != null && currentCol > 0 -> colByRow[currentRow.id] = 0
                                safeSection > 0 -> section = 0
                                else -> { inTopBar = true; navIndex = selectedNav }
                            }
                            true
                        }
                        else -> false
                    }
                }
            }
    ) {
        val screenH = maxHeight
        val contentW = maxWidth - SideMargin * 2
        val heroH = screenH * 0.56f
        val bigH = screenH * 0.47f

        LazyColumn(
            state = listState,
            userScrollEnabled = false,
            contentPadding = PaddingValues(top = TopBarH, bottom = screenH),
            modifier = Modifier.fillMaxSize()
        ) {
            item(key = "hero") {
                NetflixHeroCard(
                    item = if (safeSection == 0 && featured != null) featured else billboard,
                    logoUrl = billboard?.let { logos["${it.mediaType}_${it.id}"] },
                    badge = billboard?.let { heroBadge(it, rows) },
                    isOriginal = billboard?.let { b -> rows.any { it.originals && it.items.any { i -> i.id == b.id } } } == true,
                    focused = safeSection == 0 && !inTopBar && !menuOpen,
                    trailerKey = if (safeSection == 0 && !inTopBar) trailerKey else null,
                    trailerDelayMs = trailerDelay,
                    trailerVolume = trailerVolume,
                    modifier = Modifier.padding(horizontal = SideMargin).fillMaxWidth().height(heroH)
                )
                Spacer(Modifier.height(18.dp))
            }
            itemsIndexed(rows, key = { _, r -> r.id }) { index, r ->
                val isFocusedRow = !inTopBar && !menuOpen && safeSection == index + 1
                NetflixRow(
                    row = r,
                    focused = isFocusedRow,
                    col = if (isFocusedRow) currentCol else (colByRow[r.id] ?: 0),
                    logos = logos,
                    allRows = rows,
                    featured = if (isFocusedRow) featured else null,
                    trailerKey = if (isFocusedRow) trailerKey else null,
                    trailerDelayMs = trailerDelay,
                    trailerVolume = trailerVolume,
                    contentWidth = contentW,
                    bigHeight = bigH
                )
            }
        }

        if (state.page.isLoading && rows.isEmpty()) {
            Text("Carregando…", color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = SideMargin, bottom = 80.dp))
        }

        NetflixTopBar(
            entries = navEntries,
            focusedIndex = if (inTopBar && !menuOpen) navIndex else -1,
            selectedIndex = selectedNav,
            modifier = Modifier.fillMaxWidth().height(TopBarH).zIndex(10f)
        )
        if (menuOpen) {
            ProfileMenu(profileMenu.map { it.first }, menuIndex,
                Modifier.align(Alignment.TopStart).padding(start = SideMargin, top = TopBarH).zIndex(11f))
        }
    }
}

private val HANDLED = setOf(
    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.DirectionCenter,
    Key.Enter, Key.NumPadEnter, Key.Back, Key.MediaPlay, Key.MediaPlayPause
)

/** Selo do canto (como "#1 em séries", "Novidade", "Estreia em..."). */
private data class Badge(val text: String, val icon: ImageVector, val tint: Color)

private fun rankOf(item: MediaItem, rows: List<NRow>): Pair<Int, Boolean>? {
    rows.filter { it.top10 }.forEach { r ->
        val i = r.items.indexOfFirst { it.id == item.id && it.mediaType == item.mediaType }
        if (i in 0..9) return (i + 1) to (item.mediaType == MediaType.TV)
    }
    return null
}

private fun heroBadge(item: MediaItem, rows: List<NRow>): Badge? {
    rankOf(item, rows)?.let { (n, tv) -> return Badge("Nº $n em ${if (tv) "séries" else "filmes"} hoje", Icons.Default.EmojiEvents, Color(0xFFE5B13A)) }
    val release = item.releaseDate?.take(10)?.let { runCatching { java.time.LocalDate.parse(it) }.getOrNull() }
    if (release != null && release.isAfter(java.time.LocalDate.now())) {
        return Badge("Estreia em ${release.dayOfMonth}/${"%02d".format(release.monthValue)}", Icons.Default.Event, Color(0xFFFF4FA3))
    }
    if (rows.any { it.newRow && it.items.any { i -> i.id == item.id } }) return Badge("Novidade", Icons.Default.FiberNew, NetflixRed)
    return null
}

/** Linha "Série • Drama • 2025 • 16" (gêneros traduzidos). */
@Composable
private fun metaLine(item: MediaItem): String {
    val genres = item.genreIds.take(2).mapNotNull { id ->
        (if (item.mediaType == MediaType.TV) tvGenreNameRes(id) else movieGenreNameRes(id))?.let { stringResource(it) }
    }
    return listOfNotNull(
        if (item.mediaType == MediaType.TV) "Série" else "Filme",
        *genres.toTypedArray(),
        item.year.takeIf { it.isNotBlank() },
        item.duration.takeIf { it.isNotBlank() && item.mediaType == MediaType.MOVIE },
        item.contentRating?.substringAfterLast(' ')?.takeIf { it.isNotBlank() }
    ).joinToString("  •  ")
}

@Composable
private fun NetflixHeroCard(
    item: MediaItem?,
    logoUrl: String?,
    badge: Badge?,
    isOriginal: Boolean,
    focused: Boolean,
    trailerKey: String?,
    trailerDelayMs: Long,
    trailerVolume: Float,
    modifier: Modifier
) {
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier
            .clip(shape)
            .background(Color(0xFF1A1A1A))
            .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, shape)
    ) {
        item?.backdrop?.let { AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
        if (trailerKey != null) TrailerPlayer(youtubeKey = trailerKey, delayMs = trailerDelayMs, volume = trailerVolume, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.85f))))
        Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.55f), 0.5f to Color.Transparent)))
        if (item == null) return@Box
        Column(Modifier.align(Alignment.BottomStart).padding(start = 22.dp, bottom = 20.dp).widthIn(max = 420.dp)) {
            if (isOriginal) OriginalTag(item.mediaType == MediaType.TV)
            TitleLogo(item, logoUrl, maxH = 64.dp, maxW = 300.dp, fallbackSize = 30)
            Spacer(Modifier.height(10.dp))
            Text(metaLine(item), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
        if (badge != null) BadgeChip(badge, Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 18.dp))
    }
}

@Composable
private fun OriginalTag(series: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
        Text("N", color = NetflixRed, fontSize = 18.sp, fontWeight = FontWeight.Black)
        Spacer(Modifier.width(4.dp))
        Text(if (series) "S É R I E" else "F I L M E", color = Color.White.copy(alpha = 0.9f), fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun TitleLogo(item: MediaItem, logoUrl: String?, maxH: Dp, maxW: Dp, fallbackSize: Int) {
    if (!logoUrl.isNullOrBlank()) {
        AsyncImage(model = logoUrl, contentDescription = item.title, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
            modifier = Modifier.heightIn(max = maxH).widthIn(max = maxW))
    } else {
        Text(item.title, color = Color.White, fontSize = fallbackSize.sp, fontWeight = FontWeight.Black, maxLines = 2,
            overflow = TextOverflow.Ellipsis, lineHeight = (fallbackSize + 2).sp)
    }
}

@Composable
private fun BadgeChip(badge: Badge, modifier: Modifier = Modifier) {
    Row(
        modifier.background(Color.Black.copy(alpha = 0.65f), RoundedCornerShape(6.dp)).padding(horizontal = 9.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(badge.icon, contentDescription = null, tint = badge.tint, modifier = Modifier.size(15.dp))
        Spacer(Modifier.width(6.dp))
        Text(badge.text, color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun NetflixRow(
    row: NRow,
    focused: Boolean,
    col: Int,
    logos: Map<String, String>,
    allRows: List<NRow>,
    featured: MediaItem?,
    trailerKey: String?,
    trailerDelayMs: Long,
    trailerVolume: Float,
    contentWidth: Dp,
    bigHeight: Dp
) {
    val rowState = rememberLazyListState()
    LaunchedEffect(col) { rowState.animateScrollToItem(col.coerceAtLeast(0)) }
    Column(Modifier.fillMaxWidth().animateContentSize(tween(220)).padding(bottom = if (focused) 8.dp else 16.dp)) {
        Text(row.title, color = Color.White.copy(alpha = if (focused) 1f else 0.7f), fontSize = if (focused) 16.sp else 14.sp,
            fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = SideMargin, bottom = 7.dp))
        LazyRow(
            state = rowState,
            userScrollEnabled = false,
            contentPadding = PaddingValues(start = SideMargin, end = contentWidth),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Top
        ) {
            itemsIndexed(row.items, key = { _, it -> "${it.mediaType}_${it.id}" }) { i, item ->
                val key = "${item.mediaType}_${item.id}"
                when {
                    focused && i == col -> ExpandedCard(
                        item = item, logo = logos[key], badges = cardBadges(item, row, allRows),
                        original = row.originals || allRows.any { it.originals && it.items.any { x -> x.id == item.id } },
                        progress = row.progress[key], trailerKey = trailerKey, trailerDelayMs = trailerDelayMs,
                        trailerVolume = trailerVolume, height = bigHeight
                    )
                    focused -> PosterCard(item, height = bigHeight, progress = row.progress[key], rank = if (row.top10) i + 1 else null)
                    else -> SmallCard(item, progress = row.progress[key], rank = if (row.top10) i + 1 else null)
                }
            }
        }
        if (focused) {
            val item = row.items.getOrNull(col)
            if (item != null) {
                val shown = featured ?: item
                val res = row.resume["${item.mediaType}_${item.id}"]
                Column(Modifier.padding(start = SideMargin, top = 10.dp).widthIn(max = 560.dp)) {
                    if (res != null && item.mediaType == MediaType.TV && res.season != null && res.episode != null) {
                        Text("T${res.season} E${res.episode}", color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                        Text("${100 - res.progress}% restante", color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    } else {
                        Text(metaLine(shown), color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                        if (shown.overview.isNotBlank()) {
                            Spacer(Modifier.height(4.dp))
                            Text(shown.overview, color = Color.White.copy(alpha = 0.72f), fontSize = 14.sp, lineHeight = 19.sp,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}

private fun cardBadges(item: MediaItem, row: NRow, rows: List<NRow>): List<Badge> = buildList {
    rankOf(item, rows)?.let { (n, tv) -> add(Badge("Nº $n em ${if (tv) "séries" else "filmes"}", Icons.Default.EmojiEvents, Color(0xFFE5B13A))) }
    if (row.newRow || rows.any { it.newRow && it.items.any { i -> i.id == item.id } }) add(Badge("Novidade", Icons.Default.FiberNew, NetflixRed))
}.take(2)

@Composable
private fun ExpandedCard(
    item: MediaItem, logo: String?, badges: List<Badge>, original: Boolean, progress: Int?,
    trailerKey: String?, trailerDelayMs: Long, trailerVolume: Float, height: Dp
) {
    val shape = RoundedCornerShape(10.dp)
    Box(
        Modifier.size(width = height * 16f / 9f, height = height).clip(shape).background(Color(0xFF1F1F1F))
            .border(3.dp, Color.White, shape)
    ) {
        AsyncImage(model = item.backdrop ?: item.image, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (trailerKey != null) TrailerPlayer(youtubeKey = trailerKey, delayMs = trailerDelayMs, volume = trailerVolume, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.8f))))
        Column(Modifier.align(Alignment.BottomStart).padding(start = 16.dp, bottom = if (progress != null) 16.dp else 14.dp).widthIn(max = 260.dp)) {
            if (original) OriginalTag(item.mediaType == MediaType.TV)
            TitleLogo(item, logo, maxH = 46.dp, maxW = 220.dp, fallbackSize = 20)
        }
        Row(Modifier.align(Alignment.BottomEnd).padding(end = 12.dp, bottom = if (progress != null) 16.dp else 12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            badges.forEach { BadgeChip(it) }
        }
        ProgressBar(progress, Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun PosterCard(item: MediaItem, height: Dp, progress: Int?, rank: Int?) {
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.size(width = height * 2f / 3f, height = height).clip(shape).background(Color(0xFF1F1F1F))) {
        AsyncImage(model = item.image.ifBlank { item.backdrop }, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        if (rank != null) RankTag(rank, Modifier.align(Alignment.TopStart))
        ProgressBar(progress, Modifier.align(Alignment.BottomStart))
    }
}

@Composable
private fun SmallCard(item: MediaItem, progress: Int?, rank: Int?) {
    val shape = RoundedCornerShape(8.dp)
    Box(Modifier.size(width = 150.dp, height = 84.dp).clip(shape).background(Color(0xFF1F1F1F))) {
        AsyncImage(model = item.backdrop ?: item.image, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))))
        if (rank != null) RankTag(rank, Modifier.align(Alignment.TopStart))
        ProgressBar(progress, Modifier.align(Alignment.BottomStart))
    }
}

/** Faixa "TOP 10" no canto da capa, como a Netflix marca títulos do ranking. */
@Composable
private fun RankTag(rank: Int, modifier: Modifier) {
    Column(
        modifier.padding(start = 6.dp).background(NetflixRed, RoundedCornerShape(bottomStart = 3.dp, bottomEnd = 3.dp))
            .padding(horizontal = 4.dp, vertical = 2.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("TOP", color = Color.White, fontSize = 7.sp, fontWeight = FontWeight.Black, lineHeight = 8.sp)
        Text("$rank", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Black, lineHeight = 12.sp)
    }
}

@Composable
private fun ProgressBar(progress: Int?, modifier: Modifier) {
    if (progress == null || progress <= 0) return
    Box(modifier.fillMaxWidth().height(4.dp).background(Color(0xFF6B6B6B))) {
        Box(Modifier.fillMaxWidth(progress / 100f).height(4.dp).background(NetflixRed))
    }
}

/** Barra do topo como nas imagens oficiais: avatar ▾ à esquerda, itens centralizados em pílula, "N" à direita. */
@Composable
private fun NetflixTopBar(entries: List<NavEntry>, focusedIndex: Int, selectedIndex: Int, modifier: Modifier) {
    // Fundo sólido: o conteúdo que sobe ao rolar não aparece por trás da barra.
    Box(modifier.background(NetflixBg)) {
        // Avatar do perfil com seta (abre o menu do perfil).
        Row(
            Modifier.align(Alignment.CenterStart).padding(start = SideMargin - 6.dp)
                .background(if (focusedIndex == 0) Color.White.copy(alpha = 0.25f) else Color.Transparent, RoundedCornerShape(8.dp))
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(26.dp).background(NetflixRed, RoundedCornerShape(5.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Default.SentimentSatisfied, contentDescription = "Perfil", tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Icon(Icons.Default.ArrowDropDown, contentDescription = null, tint = Color.White.copy(alpha = 0.8f), modifier = Modifier.size(18.dp))
        }
        Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            entries.forEachIndexed { i, e ->
                if (i == 0) return@forEachIndexed
                val focused = i == focusedIndex
                val selected = i == selectedIndex
                Box(
                    Modifier
                        .background(
                            when { focused -> Color.White; selected -> Color.White.copy(alpha = 0.16f); else -> Color.Transparent },
                            RoundedCornerShape(50)
                        )
                        .padding(horizontal = if (e.icon != null) 8.dp else 14.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val fg = if (focused) Color.Black else Color.White
                    if (e.icon != null) Icon(e.icon, contentDescription = e.label, tint = fg, modifier = Modifier.size(20.dp))
                    else Text(e.label, color = fg, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
        Text("N", color = NetflixRed, fontSize = 30.sp, fontWeight = FontWeight.Black,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = SideMargin))
    }
}

/** Menu do perfil (avatar ▾): trocar perfil, trocar modo, configurações. */
@Composable
private fun ProfileMenu(options: List<String>, index: Int, modifier: Modifier) {
    Column(modifier.widthIn(min = 220.dp).background(Color(0xF2181818), RoundedCornerShape(10.dp)).padding(8.dp)) {
        options.forEachIndexed { i, label ->
            val focused = i == index
            Text(label, color = if (focused) Color.Black else Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.fillMaxWidth().background(if (focused) Color.White else Color.Transparent, RoundedCornerShape(6.dp))
                    .padding(horizontal = 14.dp, vertical = 9.dp))
        }
    }
}
