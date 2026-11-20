package com.arflix.tv.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.modes.StreamingModes
import kotlinx.coroutines.delay

/** Vermelho da Netflix (barra de progresso, bolinha, carregamento). */
internal val NetflixPlayerRed = Color(0xFFE50914)

/** O modo Netflix está ativo no perfil atual: o player usa o visual da Netflix. */
@Composable
internal fun rememberNetflixPlayer(): Boolean {
    val selection by StreamingModes.selection.collectAsState()
    return StreamingModes.isNetflixActive(selection)
}

/**
 * Informação do topo no player Netflix. Tocando: título e episódio, discretos. Pausado há alguns segundos:
 * tela escurece e aparece "Você está assistindo", o título grande, o episódio e a sinopse (como na Netflix).
 */
@Composable
internal fun NetflixPlayerInfo(
    uiState: PlayerUiState,
    mediaType: MediaType,
    seasonNumber: Int?,
    episodeNumber: Int?,
    isPaused: Boolean,
    modifier: Modifier = Modifier
) {
    var pausedLong by remember { mutableStateOf(false) }
    LaunchedEffect(isPaused) {
        pausedLong = false
        if (isPaused) { delay(4_000L); pausedLong = true }
    }
    val episodeLine = if (mediaType == MediaType.TV && seasonNumber != null && episodeNumber != null) {
        "T$seasonNumber:E$episodeNumber" + (uiState.episodeTitle?.takeIf { it.isNotBlank() }?.let { " \"$it\"" } ?: "")
    } else null
    Box(modifier) {
        if (!pausedLong) {
            Column(Modifier.padding(start = 8.dp, top = 4.dp)) {
                Text(uiState.title, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (episodeLine != null) Text(episodeLine, color = Color.White.copy(alpha = 0.75f), fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A tela "Você está assistindo" da pausa longa, ocupando a tela toda por trás dos controles. */
@Composable
internal fun NetflixPauseScreen(
    uiState: PlayerUiState,
    mediaType: MediaType,
    seasonNumber: Int?,
    episodeNumber: Int?,
    isPaused: Boolean
) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(isPaused) {
        show = false
        if (isPaused) { delay(4_000L); show = true }
    }
    AnimatedVisibility(visible = show, enter = fadeIn(tween(500)), exit = fadeOut(tween(200))) {
        val overview = playerPauseOverview(mediaType, seasonNumber, episodeNumber, uiState.seasonEpisodes, uiState.overview)
        Box(
            Modifier.fillMaxSize().background(
                Brush.horizontalGradient(0f to Color.Black.copy(alpha = 0.92f), 0.55f to Color.Black.copy(alpha = 0.6f), 1f to Color.Black.copy(alpha = 0.25f))
            )
        ) {
            Column(Modifier.align(Alignment.CenterStart).padding(start = 64.dp).widthIn(max = 460.dp)) {
                Text("Você está assistindo", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
                Spacer(Modifier.height(6.dp))
                Text(uiState.title, color = Color.White, fontSize = 34.sp, fontWeight = FontWeight.Black, lineHeight = 36.sp, maxLines = 2)
                if (mediaType == MediaType.TV && seasonNumber != null && episodeNumber != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "T$seasonNumber:E$episodeNumber" + (uiState.episodeTitle?.takeIf { it.isNotBlank() }?.let { " \"$it\"" } ?: ""),
                        color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis
                    )
                }
                if (overview.isNotBlank()) {
                    Spacer(Modifier.height(10.dp))
                    Text(overview, color = Color.White.copy(alpha = 0.85f), fontSize = 14.sp, lineHeight = 19.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
                }
            }
            Text("Pausado", color = Color.White.copy(alpha = 0.55f), fontSize = 14.sp,
                modifier = Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 120.dp))
        }
    }
}

/** Carregamento da Netflix: fundo preto e um círculo vermelho girando. */
@Composable
internal fun NetflixLoadingSpinner(label: String?, modifier: Modifier = Modifier) {
    val transition = rememberInfiniteTransition(label = "nfspin")
    val angle by transition.animateFloat(0f, 360f, infiniteRepeatable(tween(900, easing = LinearEasing), RepeatMode.Restart), label = "angle")
    Box(modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(64.dp).rotate(angle)) {
            val stroke = 5.dp.toPx()
            drawArc(
                color = NetflixPlayerRed,
                startAngle = 0f, sweepAngle = 270f, useCenter = false,
                topLeft = Offset(stroke / 2, stroke / 2),
                size = Size(size.width - stroke, size.height - stroke),
                style = Stroke(width = stroke, cap = StrokeCap.Round)
            )
        }
        if (!label.isNullOrBlank()) {
            Text(label, color = Color.White.copy(alpha = 0.55f), fontSize = 13.sp,
                modifier = Modifier.align(Alignment.Center).padding(top = 120.dp))
        }
    }
}
