package com.arflix.tv.data.embed

import android.content.Context
import android.net.Uri
import com.arflix.tv.data.model.Addon
import com.arflix.tv.data.model.AddonType
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.model.StreamSource
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * "Minhas fontes" do Streaming Modes (iguais às do PC): players embutidos de sites (StreamBetter, Pomfy).
 * Não viram um endereço de vídeo: o app abre a página deles dentro de um WebView ([EmbedPlayerOverlay])
 * com os nossos controles por cima. Na lista de fontes aparecem com o endereço `smembed://...`.
 */
object EmbedSources {
    const val SCHEME = "smembed://"
    const val ADDON_PREFIX = "sm.embed."
    const val ADDON_NAME = "Minhas fontes"
    /** Cartão "Minhas fontes" na tela de Addons (não é um addon do Stremio: liga/desliga as fontes embutidas). */
    const val ADDON_ID = "sm.minhasfontes"
    private const val PREFS = "streaming_modes"
    private const val KEY_ENABLED = "minhas_fontes_enabled"

    private val _enabled = MutableStateFlow(true)
    val enabled: StateFlow<Boolean> = _enabled

    /** Lê a preferência (chamado na abertura do app). */
    fun attach(context: Context) {
        _enabled.value = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, true)
    }

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ENABLED, on).apply()
        _enabled.value = on
    }

    fun asAddon(isEnabled: Boolean): Addon = Addon(
        id = ADDON_ID,
        name = ADDON_NAME,
        version = "1.0",
        description = PROVIDERS.joinToString(" · ") { it.name } + " — tocam no player do app, sem anúncios",
        isInstalled = true,
        isEnabled = isEnabled,
        type = AddonType.CUSTOM
    )

    data class Provider(
        val key: String,
        val name: String,
        val movie: String,
        val series: String,
        /** O site fala a API de embed do StreamBetter (postMessage streambetter:progress/play/pause/seek). */
        val streamBetterApi: Boolean
    )

    /** Ordem = prioridade (StreamBetter primeiro, como o usuário pediu). */
    val PROVIDERS = listOf(
        Provider(
            key = "streambetter", name = "StreamBetter",
            movie = "https://streambetter.bond/filme/{tmdb}",
            series = "https://streambetter.bond/serie/{tmdb}/{s}/{e}",
            streamBetterApi = true
        ),
        Provider(
            key = "pomfy", name = "Pomfy",
            movie = "https://api.pomfy.stream/filme/{tmdb}",
            series = "https://api.pomfy.stream/serie/{tmdb}/{s}/{e}",
            streamBetterApi = false
        )
    )

    data class Embed(val provider: Provider, val pageUrl: String)

    fun isEmbedUrl(url: String?): Boolean = url?.startsWith(SCHEME) == true

    fun isEmbedStream(stream: StreamSource?): Boolean =
        stream != null && (stream.addonId.startsWith(ADDON_PREFIX) || isEmbedUrl(stream.url))

    /** Prioridade entre as minhas fontes (0 = StreamBetter). */
    fun rank(stream: StreamSource): Int {
        val key = stream.addonId.removePrefix(ADDON_PREFIX)
        return PROVIDERS.indexOfFirst { it.key == key }.let { if (it < 0) PROVIDERS.size else it }
    }

    fun parse(url: String?): Embed? {
        if (!isEmbedUrl(url)) return null
        val uri = Uri.parse(url)
        val provider = PROVIDERS.firstOrNull { it.key == uri.host } ?: return null
        val page = uri.getQueryParameter("u")?.takeIf { it.startsWith("https://") } ?: return null
        return Embed(provider, page)
    }

    /** Uma fonte por site, para o título pedido (ids do TMDB; série precisa de temporada e episódio). */
    fun streamsFor(mediaType: MediaType, tmdbId: Int, season: Int?, episode: Int?): List<StreamSource> {
        if (tmdbId <= 0 || !_enabled.value) return emptyList()
        val isTv = mediaType == MediaType.TV
        if (isTv && (season == null || episode == null)) return emptyList()
        return PROVIDERS.map { p ->
            val page = (if (isTv) p.series else p.movie)
                .replace("{tmdb}", tmdbId.toString())
                .replace("{s}", season?.toString().orEmpty())
                .replace("{e}", episode?.toString().orEmpty())
            val where = if (isTv) "T$season:E$episode" else "Filme"
            StreamSource(
                source = "▶ ${p.name}",
                addonName = ADDON_NAME,
                addonId = ADDON_PREFIX + p.key,
                quality = "",
                size = "",
                url = "$SCHEME${p.key}?u=" + Uri.encode(page),
                description = "Player embutido · $where\n${p.name}",
                addonTitle = p.name
            )
        }
    }
}
