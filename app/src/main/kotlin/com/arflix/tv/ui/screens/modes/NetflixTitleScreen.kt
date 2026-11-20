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
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.arflix.tv.data.model.Episode
import com.arflix.tv.data.model.EpisodeIdentity
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.ui.components.TrailerPlayer
import com.arflix.tv.ui.screens.details.DetailsViewModel
import kotlinx.coroutines.delay

private val NRed = Color(0xFFE50914)

private enum class TitlePane { MAIN, EPISODES, SIMILAR }

private data class TitleAction(val label: String, val icon: ImageVector, val run: () -> Unit, val progress: Float? = null)

/**
 * Página do título no estilo do app da Netflix para TV: fundo/trailer à direita, informações e botões
 * empilhados à esquerda (Assistir/Continuar, Episódios, Começar do início, Minha lista, Títulos semelhantes),
 * tela de episódios com temporadas à esquerda e episódios à direita.
 * "Assistir" abre o player, que escolhe a fonte pela prioridade do app (Minhas fontes, FenixFlix...).
 */
@Composable
internal fun NetflixTitleScreen(
    mediaType: MediaType,
    mediaId: Int,
    initialSeason: Int?,
    initialEpisode: Int?,
    autoPlay: Boolean,
    onAutoPlayHandled: () -> Unit,
    onPlay: (identity: EpisodeIdentity?, imdbId: String?, startPositionMs: Long?) -> Unit,
    onOpenTitle: (MediaType, Int) -> Unit,
    onBack: () -> Unit,
    viewModel: DetailsViewModel = hiltViewModel()
) {
    LaunchedEffect(mediaType, mediaId) { viewModel.loadDetails(mediaType, mediaId, initialSeason, initialEpisode) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val item = state.item
    val isTv = mediaType == MediaType.TV

    // Volta do player: atualiza progresso e "Continuar".
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        var first = true
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) { if (!first) viewModel.refreshAfterPlayerReturn(); first = false }
        }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs) }
    }

    fun identityFor(season: Int?, episode: Int?): EpisodeIdentity? {
        if (!isTv || season == null || episode == null) return null
        return state.episodes.firstOrNull { it.seasonNumber == season && it.episodeNumber == episode }?.identity
            ?: EpisodeIdentity.canonical(season, episode)
    }
    val resumeIdentity = identityFor(state.playSeason ?: 1, state.playEpisode ?: 1)
    val resumeMs = state.playPositionMs?.takeIf { it > 30_000L }
    fun playMain() = onPlay(resumeIdentity, state.imdbId, resumeMs)
    fun playFromStart() = onPlay(identityFor(state.playSeason ?: 1, state.playEpisode ?: 1), state.imdbId, 0L)
    fun playEpisode(e: Episode) {
        val p = state.episodePlaybackProgress[e.identity]
        onPlay(e.identity, state.imdbId, p?.positionMs?.takeIf { (p.percent in 3..94) })
    }

    // "Assistir" da Home: começa direto.
    var autoPlayDone by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(autoPlay, state.isLoading, state.playSeason) {
        if (autoPlay && !autoPlayDone && !state.isLoading && item != null) {
            autoPlayDone = true
            onAutoPlayHandled()
            playMain()
        }
    }

    var pane by rememberSaveable { mutableStateOf(TitlePane.MAIN) }
    var actionIndex by rememberSaveable { mutableIntStateOf(0) }
    var seasonFocus by rememberSaveable { mutableStateOf(false) }
    var episodeIndex by rememberSaveable { mutableIntStateOf(0) }
    var seasonIndex by rememberSaveable { mutableIntStateOf(0) }
    var similarIndex by rememberSaveable { mutableIntStateOf(0) }
    var pendingEpisode by rememberSaveable { mutableIntStateOf(1) }
    // Quando a temporada pedida chega, posiciona no episódio onde parou.
    LaunchedEffect(state.currentSeason, state.episodes.size) {
        if (pane == TitlePane.EPISODES && state.episodes.isNotEmpty()) {
            val i = state.episodes.indexOfFirst { it.episodeNumber == pendingEpisode }
            if (i >= 0) episodeIndex = i
        }
    }

    val playLabel = when {
        isTv && resumeMs != null -> "Continuar T${state.playSeason ?: 1}:E${state.playEpisode ?: 1}"
        isTv && (state.playSeason ?: 1) to (state.playEpisode ?: 1) != (1 to 1) -> "Assistir T${state.playSeason}:E${state.playEpisode}"
        resumeMs != null -> "Continuar"
        else -> "Assistir"
    }
    val actions = buildList {
        add(TitleAction(playLabel, Icons.Default.PlayArrow, ::playMain))
        if (isTv) add(TitleAction("Episódios", Icons.Default.List, {
            pane = TitlePane.EPISODES; seasonFocus = false
            // Abre na temporada e no episódio onde a pessoa parou.
            val target = state.playSeason ?: state.currentSeason
            seasonIndex = (target - 1).coerceAtLeast(0)
            pendingEpisode = state.playEpisode ?: 1
            if (target != state.currentSeason) viewModel.loadSeason(target)
            else episodeIndex = state.episodes.indexOfFirst { it.episodeNumber == pendingEpisode }.coerceAtLeast(0)
        }))
        if (resumeMs != null) add(TitleAction("Começar do início", Icons.Default.Replay, ::playFromStart))
        add(TitleAction(if (state.isInWatchlist) "Na Minha lista" else "Minha lista",
            if (state.isInWatchlist) Icons.Default.Check else Icons.Default.Add, { viewModel.toggleWatchlist() }))
        if (state.similar.isNotEmpty()) add(TitleAction("Títulos semelhantes", Icons.Default.ViewModule, { pane = TitlePane.SIMILAR; similarIndex = 0 }))
    }
    val safeAction = actionIndex.coerceIn(0, (actions.size - 1).coerceAtLeast(0))

    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { repeat(10) { if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect; delay(100) } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onPreviewKeyEvent ev.key in TITLE_KEYS
                when (pane) {
                    TitlePane.MAIN -> when (ev.key) {
                        Key.DirectionUp -> { actionIndex = (safeAction - 1).coerceAtLeast(0); true }
                        Key.DirectionDown -> { actionIndex = (safeAction + 1).coerceAtMost(actions.size - 1); true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> { actions.getOrNull(safeAction)?.run?.invoke(); true }
                        Key.MediaPlay, Key.MediaPlayPause -> { playMain(); true }
                        Key.Back -> { onBack(); true }
                        Key.DirectionLeft, Key.DirectionRight -> true
                        else -> false
                    }
                    TitlePane.EPISODES -> when (ev.key) {
                        Key.DirectionLeft -> { seasonFocus = true; true }
                        Key.DirectionRight -> { seasonFocus = false; true }
                        Key.DirectionUp -> {
                            if (seasonFocus) seasonIndex = (seasonIndex - 1).coerceAtLeast(0)
                            else episodeIndex = (episodeIndex - 1).coerceAtLeast(0)
                            true
                        }
                        Key.DirectionDown -> {
                            if (seasonFocus) seasonIndex = (seasonIndex + 1).coerceAtMost(state.totalSeasons - 1)
                            else episodeIndex = (episodeIndex + 1).coerceAtMost((state.episodes.size - 1).coerceAtLeast(0))
                            true
                        }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlay, Key.MediaPlayPause -> {
                            if (seasonFocus) {
                                pendingEpisode = 1; viewModel.loadSeason(seasonIndex + 1); episodeIndex = 0; seasonFocus = false
                            } else state.episodes.getOrNull(episodeIndex)?.let(::playEpisode)
                            true
                        }
                        Key.Back -> { pane = TitlePane.MAIN; true }
                        else -> false
                    }
                    TitlePane.SIMILAR -> when (ev.key) {
                        Key.DirectionLeft -> { similarIndex = (similarIndex - 1).coerceAtLeast(0); true }
                        Key.DirectionRight -> { similarIndex = (similarIndex + 1).coerceAtMost(state.similar.size - 1); true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            state.similar.getOrNull(similarIndex)?.let { onOpenTitle(it.mediaType, it.id) }; true
                        }
                        Key.Back, Key.DirectionUp -> { pane = TitlePane.MAIN; true }
                        Key.DirectionDown -> true
                        else -> false
                    }
                }
            }
    ) {
        // Fundo / trailer à direita.
        Box(Modifier.fillMaxHeight().fillMaxWidth(0.74f).align(Alignment.TopEnd)) {
            item?.backdrop?.let {
                AsyncImage(model = it, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            if (pane == TitlePane.MAIN && state.trailerKey != null) {
                TrailerPlayer(youtubeKey = state.trailerKey!!, delayMs = 2500L, volume = 0f, modifier = Modifier.fillMaxSize())
            }
            Box(Modifier.fillMaxSize().background(Brush.horizontalGradient(0f to Color.Black, 0.4f to Color.Black.copy(alpha = 0.6f), 0.75f to Color.Transparent)))
            Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.6f to Color.Transparent, 1f to Color.Black)))
        }
        if (pane == TitlePane.EPISODES) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.88f)))
        }

        when (pane) {
            TitlePane.MAIN, TitlePane.SIMILAR -> Column(
                Modifier.align(Alignment.TopStart).padding(start = 64.dp, top = 40.dp).widthIn(max = 420.dp)
            ) {
                if (item == null) {
                    Text("Carregando…", color = Color.White.copy(alpha = 0.6f), fontSize = 16.sp)
                    return@Column
                }
                TitleHeader(item, state.logoUrl, isTv, state.totalSeasons, state.genres)
                if (pane == TitlePane.MAIN) {
                    Spacer(Modifier.height(18.dp))
                    actions.forEachIndexed { i, a ->
                        TitleButton(a, focused = i == safeAction)
                        Spacer(Modifier.height(6.dp))
                    }
                    if (state.cast.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("Elenco: " + state.cast.take(3).joinToString(", ") { it.name }, color = Color.White.copy(alpha = 0.55f),
                            fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            TitlePane.EPISODES -> EpisodesPane(
                title = item?.title.orEmpty(),
                totalSeasons = state.totalSeasons,
                currentSeason = state.currentSeason,
                seasonIndex = seasonIndex,
                seasonFocus = seasonFocus,
                episodes = state.episodes,
                episodeIndex = episodeIndex,
                progress = { state.episodePlaybackProgress[it.identity]?.percent },
                loading = state.isSeasonLoading
            )
        }

        if (pane == TitlePane.SIMILAR) {
            Column(Modifier.align(Alignment.BottomStart).padding(bottom = 36.dp)) {
                Text("Títulos semelhantes", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(start = 64.dp, bottom = 8.dp))
                val rowState = rememberLazyListState()
                LaunchedEffect(similarIndex) { rowState.animateScrollToItem(similarIndex) }
                LazyRow(state = rowState, userScrollEnabled = false, contentPadding = PaddingValues(start = 64.dp, end = 300.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    itemsIndexed(state.similar, key = { _, it -> "${it.mediaType}_${it.id}" }) { i, s ->
                        SimilarCard(s, state.similarLogoUrls["${s.mediaType}_${s.id}"], i == similarIndex)
                    }
                }
            }
        }
    }
}

private val TITLE_KEYS = setOf(
    Key.DirectionUp, Key.DirectionDown, Key.DirectionLeft, Key.DirectionRight, Key.DirectionCenter,
    Key.Enter, Key.NumPadEnter, Key.Back, Key.MediaPlay, Key.MediaPlayPause
)

@Composable
private fun TitleHeader(item: MediaItem, logoUrl: String?, isTv: Boolean, seasons: Int, genres: List<String>) {
    if (!logoUrl.isNullOrBlank()) {
        AsyncImage(model = logoUrl, contentDescription = item.title, contentScale = ContentScale.Fit,
            alignment = Alignment.CenterStart, modifier = Modifier.heightIn(max = 76.dp).widthIn(max = 300.dp))
    } else {
        Text(item.title, color = Color.White, fontSize = 30.sp, fontWeight = FontWeight.Black, maxLines = 2, lineHeight = 32.sp)
    }
    Spacer(Modifier.height(10.dp))
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (item.year.isNotBlank()) Text(item.year, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
        item.contentRating?.substringAfterLast(' ')?.takeIf { it.isNotBlank() }?.let {
            Box(Modifier.border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(2.dp)).padding(horizontal = 4.dp)) {
                Text(it, color = Color.White, fontSize = 11.sp)
            }
        }
        val length = if (isTv) "$seasons ${if (seasons == 1) "temporada" else "temporadas"}" else item.duration
        if (length.isNotBlank()) Text(length, color = Color.White.copy(alpha = 0.85f), fontSize = 13.sp)
        Box(Modifier.border(1.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(2.dp)).padding(horizontal = 3.dp)) {
            Text("HD", color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
    }
    if (item.overview.isNotBlank()) {
        Spacer(Modifier.height(10.dp))
        Text(item.overview, color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp, lineHeight = 18.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
    }
    if (genres.isNotEmpty()) {
        Spacer(Modifier.height(6.dp))
        Text(genres.take(3).joinToString(" • "), color = Color.White.copy(alpha = 0.55f), fontSize = 12.sp)
    }
}

@Composable
private fun TitleButton(a: TitleAction, focused: Boolean) {
    Row(
        Modifier
            .widthIn(min = 260.dp)
            .background(if (focused) Color.White else Color.Transparent, RoundedCornerShape(4.dp))
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val fg = if (focused) Color.Black else Color.White.copy(alpha = 0.85f)
        Icon(a.icon, contentDescription = null, tint = fg, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(a.label, color = fg, fontSize = 15.sp, fontWeight = if (focused) FontWeight.Bold else FontWeight.Medium)
    }
}

@Composable
private fun EpisodesPane(
    title: String,
    totalSeasons: Int,
    currentSeason: Int,
    seasonIndex: Int,
    seasonFocus: Boolean,
    episodes: List<Episode>,
    episodeIndex: Int,
    progress: (Episode) -> Int?,
    loading: Boolean
) {
    Row(Modifier.fillMaxSize().padding(start = 64.dp, top = 36.dp, end = 40.dp)) {
        Column(Modifier.width(200.dp)) {
            Text(title, color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold, maxLines = 2)
            Spacer(Modifier.height(16.dp))
            val seasonsState = rememberLazyListState()
            LaunchedEffect(seasonIndex) { seasonsState.animateScrollToItem((seasonIndex - 3).coerceAtLeast(0)) }
            LazyColumn(state = seasonsState, userScrollEnabled = false) {
            items((1..totalSeasons.coerceAtLeast(1)).toList()) { n ->
                val focused = seasonFocus && seasonIndex == n - 1
                val selected = n == currentSeason
                Text(
                    "Temporada $n",
                    color = when { focused -> Color.Black; selected -> Color.White; else -> Color.White.copy(alpha = 0.55f) },
                    fontSize = 15.sp,
                    fontWeight = if (selected || focused) FontWeight.Bold else FontWeight.Medium,
                    modifier = Modifier
                        .padding(vertical = 2.dp)
                        .background(if (focused) Color.White else Color.Transparent, RoundedCornerShape(4.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )
            }
            }
        }
        Spacer(Modifier.width(24.dp))
        val listState = rememberLazyListState()
        LaunchedEffect(episodeIndex) { if (episodes.isNotEmpty()) listState.animateScrollToItem(episodeIndex.coerceAtMost(episodes.size - 1)) }
        if (loading && episodes.isEmpty()) {
            Text("Carregando episódios…", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
        } else LazyColumn(state = listState, userScrollEnabled = false, contentPadding = PaddingValues(bottom = 300.dp)) {
            itemsIndexed(episodes, key = { _, e -> "${e.seasonNumber}_${e.episodeNumber}" }) { i, e ->
                EpisodeRow(e, focused = !seasonFocus && i == episodeIndex, progress = progress(e))
            }
        }
    }
}

@Composable
private fun EpisodeRow(e: Episode, focused: Boolean, progress: Int?) {
    val scale by animateFloatAsState(if (focused) 1.02f else 1f, tween(140), label = "ep")
    Row(
        Modifier
            .fillMaxWidth()
            .scale(scale)
            .padding(vertical = 5.dp)
            .background(if (focused) Color.White.copy(alpha = 0.12f) else Color.Transparent, RoundedCornerShape(6.dp))
            .border(if (focused) 2.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(6.dp))
            .padding(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(width = 160.dp, height = 90.dp).clip(RoundedCornerShape(4.dp)).background(Color(0xFF222222))) {
            val still = e.stillPath?.let { if (it.startsWith("/")) "https://image.tmdb.org/t/p/w300$it" else it }
            if (still != null) AsyncImage(model = still, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            if (progress != null && progress > 0) {
                Box(Modifier.align(Alignment.BottomStart).fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = 0.3f))) {
                    Box(Modifier.fillMaxWidth(progress / 100f).height(3.dp).background(NRed))
                }
            }
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row {
                Text("${e.episodeNumber}. ${e.name}", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                if (e.runtime > 0) Text("${e.runtime} min", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
            }
            if (e.overview.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(e.overview, color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp, lineHeight = 16.sp,
                    maxLines = if (focused) 3 else 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun SimilarCard(item: MediaItem, logo: String?, focused: Boolean) {
    val scale by animateFloatAsState(if (focused) 1.08f else 1f, tween(150), label = "sim")
    Box(
        Modifier
            .scale(scale)
            .size(width = 176.dp, height = 99.dp)
            .clip(RoundedCornerShape(4.dp))
            .background(Color(0xFF232323))
            .border(if (focused) 3.dp else 0.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(4.dp))
    ) {
        AsyncImage(model = item.backdrop ?: item.image, contentDescription = item.title, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.5f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.7f))))
        if (!logo.isNullOrBlank()) {
            AsyncImage(model = logo, contentDescription = null, contentScale = ContentScale.Fit,
                modifier = Modifier.align(Alignment.BottomCenter).padding(horizontal = 14.dp, vertical = 6.dp).height(30.dp).fillMaxWidth())
        } else Text(item.title, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold, maxLines = 2,
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp))
    }
}
