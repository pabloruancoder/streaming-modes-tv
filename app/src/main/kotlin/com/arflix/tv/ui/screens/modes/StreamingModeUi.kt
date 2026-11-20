package com.arflix.tv.ui.screens.modes

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.tv.material3.Text
import com.arflix.tv.data.model.CatalogConfig
import com.arflix.tv.data.model.MediaItem
import com.arflix.tv.data.modes.StreamingModes
import com.arflix.tv.ui.screens.collections.ServiceHeaderAction
import com.arflix.tv.ui.screens.collections.ServiceHome
import com.arflix.tv.ui.screens.collections.ServiceHomeScreen
import kotlinx.coroutines.delay

/** Vinheta já tocada nesta abertura do app (por modo): não repete a cada volta para a Home. */
private val introPlayed = mutableSetOf<String>()

/**
 * Home de um modo de streaming: vinheta (uma vez por abertura do app), depois a página do serviço com o
 * catálogo do Brasil e, no cabeçalho, Buscar, Minha lista, Modo e Configurações.
 */
@Composable
internal fun StreamingModeHome(
    mode: StreamingModes.Mode,
    onOpen: (item: MediaItem, play: Boolean) -> Unit,
    onViewAll: (catalog: CatalogConfig, ServiceHome.ViewAll) -> Unit,
    onSearch: () -> Unit,
    onMyList: () -> Unit,
    onSettings: () -> Unit,
    onPickMode: () -> Unit,
    onBack: () -> Unit,
    onSwitchProfile: () -> Unit = {},
    onNewPopular: () -> Unit = {}
) {
    var showIntro by remember(mode.key) { mutableStateOf(mode.key !in introPlayed) }
    val catalog = remember(mode.key) { StreamingModes.catalog(mode) }
    val actions = remember(mode.key) {
        listOf(
            // Só ícones: o cabeçalho já tem Início, Séries, Filmes e Filtrar.
            ServiceHeaderAction("", Icons.Default.Search, onSearch),
            ServiceHeaderAction("", Icons.Default.Bookmark, onMyList),
            ServiceHeaderAction("Modo", Icons.Default.SwapHoriz, onPickMode),
            ServiceHeaderAction("", Icons.Default.Settings, onSettings)
        )
    }
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        // Netflix: interface do app da Netflix para TV. Os outros modos ainda usam a página de serviço.
        if (mode.key == StreamingModes.NETFLIX.key) NetflixTvHome(
            mode = mode,
            onOpen = onOpen,
            onSearch = onSearch,
            onMyList = onMyList,
            onSettings = onSettings,
            onPickMode = onPickMode,
            onSwitchProfile = onSwitchProfile,
            onBack = onBack,
            onNewPopular = onNewPopular
        ) else ServiceHomeScreen(
            service = catalog,
            onOpen = onOpen,
            onViewAll = { onViewAll(catalog, it) },
            onBack = onBack,
            headerActions = actions,
            titleColor = Color(mode.accent),
            titleText = mode.wordmark
        )
        if (showIntro) {
            ModeIntro(mode) {
                introPlayed += mode.key
                showIntro = false
            }
        }
    }
}

/** Vinheta própria: o nome do serviço na cor dele, crescendo e sumindo (sem vídeo nem som de terceiros). */
@Composable
internal fun ModeIntro(mode: StreamingModes.Mode, onDone: () -> Unit) {
    val scale = remember { Animatable(0.7f) }
    val alpha = remember { Animatable(0f) }
    val bg = remember { Animatable(1f) }
    LaunchedEffect(mode.key) {
        alpha.animateTo(1f, tween(350))
        scale.animateTo(1.12f, tween(900, easing = FastOutSlowInEasing))
        scale.animateTo(6f, tween(450, easing = FastOutSlowInEasing))
        alpha.animateTo(0f, tween(150))
        bg.animateTo(0f, tween(250))
        onDone()
    }
    Box(
        modifier = Modifier.fillMaxSize().alpha(bg.value).background(Color.Black)
            .focusable().onPreviewKeyEvent { true },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = mode.wordmark,
            color = Color(mode.accent),
            fontSize = 120.sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier.scale(scale.value).alpha(alpha.value)
        )
    }
}

/** Seletor de modo (botão ☰ do controle na Home, ou "Modo" no cabeçalho de um modo). */
@Composable
internal fun StreamingModePicker(
    current: StreamingModes.Mode?,
    onPick: (StreamingModes.Mode?) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val first = remember { FocusRequester() }
        LaunchedEffect(Unit) { repeat(10) { if (runCatching { first.requestFocus() }.isSuccess) return@LaunchedEffect; delay(80) } }
        Column(
            modifier = Modifier
                .width(620.dp)
                .background(Color(0xF2141414), RoundedCornerShape(16.dp))
                .verticalScroll(androidx.compose.foundation.rememberScrollState())
                .padding(horizontal = 28.dp, vertical = 22.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Text("Escolha o modo", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Text("A Home mostra só o catálogo do streaming escolhido (Brasil).", color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
            Spacer(Modifier.height(4.dp))
            val options = StreamingModes.ALL.map { it as StreamingModes.Mode? } + listOf(null)
            options.forEachIndexed { i, mode ->
                ModeOption(
                    label = mode?.name ?: "Padrão (Home do app)",
                    accent = mode?.let { Color(it.accent) } ?: Color.White,
                    selected = mode?.key == current?.key,
                    modifier = if (i == 0) Modifier.focusRequester(first) else Modifier,
                    onClick = { onPick(mode) }
                )
            }
        }
    }
}

@Composable
private fun ModeOption(label: String, accent: Color, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .background(if (focused) Color.White else Color.White.copy(alpha = 0.06f), RoundedCornerShape(10.dp))
            .border(if (selected && !focused) 2.dp else 0.dp, if (selected) accent else Color.Transparent, RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(14.dp).background(accent, CircleShape))
        Spacer(Modifier.width(16.dp))
        Text(label, color = if (focused) Color.Black else Color.White, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        if (selected) {
            Spacer(Modifier.weight(1f))
            Text("✓", color = if (focused) Color.Black else accent, fontSize = 20.sp)
        }
    }
}
