package com.arflix.tv.data.modes

import android.content.Context
import com.arflix.tv.data.model.CatalogConfig
import com.arflix.tv.data.model.CatalogKind
import com.arflix.tv.data.model.CatalogSourceType
import com.arflix.tv.data.model.CollectionGroupKind
import com.arflix.tv.data.model.CollectionSourceConfig
import com.arflix.tv.data.model.CollectionSourceKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Streaming Modes (igual ao plugin do PC): escolher um streaming muda a cara do app — vinheta, cor e uma
 * Home só com o catálogo daquele serviço no Brasil. "Padrão" volta para a Home normal do app.
 * A escolha fica salva por perfil.
 */
object StreamingModes {
    data class Mode(
        val key: String,
        val name: String,
        /** Logo em texto (o app não distribui marcas em imagem). */
        val wordmark: String,
        /** Ids de provedor do TMDB (watch providers) no Brasil. */
        val providers: List<Int>,
        val accent: Long
    )

    val NETFLIX = Mode("netflix", "Netflix", "NETFLIX", listOf(8), 0xFFE50914)

    /** Netflix primeiro (o modo completo); os outros usam a mesma Home de serviço. */
    val ALL: List<Mode> = listOf(
        NETFLIX,
        Mode("disney", "Disney+", "Disney+", listOf(337), 0xFF0063E5),
        Mode("prime", "Prime Video", "prime video", listOf(119, 9), 0xFF00A8E1),
        Mode("max", "HBO Max", "HBO max", listOf(1899), 0xFF8A2BE2),
        Mode("apple", "Apple TV+", "tv+", listOf(350), 0xFFE5E5E5),
        Mode("globoplay", "Globoplay", "globoplay", listOf(307), 0xFFFF2A47),
        Mode("crunchyroll", "Crunchyroll", "crunchyroll", listOf(283), 0xFFF47521)
    )

    private const val CATALOG_PREFIX = "sm_mode_"
    private const val PREFS = "streaming_modes"
    private const val REGION = "BR"

    fun byKey(key: String?): Mode? = ALL.firstOrNull { it.key == key }

    fun catalogId(mode: Mode) = CATALOG_PREFIX + mode.key

    /** A "página de serviço" do app para o modo, com o catálogo do Brasil. */
    fun catalog(mode: Mode): CatalogConfig = CatalogConfig(
        id = catalogId(mode),
        title = mode.name,
        sourceType = CatalogSourceType.PREINSTALLED,
        isPreinstalled = true,
        kind = CatalogKind.COLLECTION,
        collectionGroup = CollectionGroupKind.SERVICE,
        collectionSources = mode.providers.flatMap { provider ->
            listOf("movie", "series").map { type ->
                CollectionSourceConfig(
                    kind = CollectionSourceKind.TMDB_WATCH_PROVIDER,
                    mediaType = type,
                    tmdbWatchProviderId = provider,
                    watchRegion = REGION,
                    sortBy = "popularity.desc"
                )
            }
        }
    )

    /** Para a tela "Ver tudo" de uma fileira do modo achar o catálogo pelo id. */
    fun catalogFor(id: String?): CatalogConfig? =
        id?.takeIf { it.startsWith(CATALOG_PREFIX) }?.let { byKey(it.removePrefix(CATALOG_PREFIX)) }?.let(::catalog)

    // ---- Escolha por perfil ----
    private val _selection = MutableStateFlow<Map<String, String>>(emptyMap())
    /** perfil -> chave do modo ("" = padrão). */
    val selection: StateFlow<Map<String, String>> = _selection

    fun attach(context: Context) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _selection.value = prefs.all.mapNotNull { (k, v) ->
            if (k.startsWith("mode_") && v is String) k.removePrefix("mode_") to v else null
        }.toMap()
    }

    /** Perfil em uso (a navegação atualiza): telas sem acesso ao perfil (player) consultam o modo por aqui. */
    @Volatile var currentProfileId: String? = null

    fun isNetflixActive(selection: Map<String, String> = _selection.value): Boolean =
        modeFor(currentProfileId, selection)?.key == NETFLIX.key

    fun modeFor(profileId: String?, selection: Map<String, String> = _selection.value): Mode? =
        byKey(selection[profileId ?: ""])

    fun select(context: Context, profileId: String?, mode: Mode?) {
        val id = profileId ?: ""
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("mode_$id", mode?.key ?: "").apply()
        _selection.value = _selection.value + (id to (mode?.key ?: ""))
    }
}
