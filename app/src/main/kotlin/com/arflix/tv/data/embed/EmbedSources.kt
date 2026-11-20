package com.arflix.tv.data.embed

import android.net.Uri
import com.arflix.tv.data.model.MediaType
import com.arflix.tv.data.model.StreamSource

/**
 * "Minhas fontes" do Streaming Modes (iguais às do PC): players embutidos de sites (StreamBetter, Pomfy).
 * Não viram um endereço de vídeo: o app abre a página deles dentro de um WebView ([EmbedPlayerOverlay])
 * com os nossos controles por cima. Na lista de fontes aparecem com o endereço `smembed://...`.
 */
object EmbedSources {
    const val SCHEME = "smembed://"
    const val ADDON_PREFIX = "sm.embed."
    const val ADDON_NAME = "Minhas fontes"

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
        if (tmdbId <= 0) return emptyList()
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
