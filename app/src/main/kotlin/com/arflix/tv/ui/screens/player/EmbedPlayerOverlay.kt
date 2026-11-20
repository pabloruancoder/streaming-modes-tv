package com.arflix.tv.ui.screens.player

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.zIndex
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.runtime.SideEffect
import androidx.tv.material3.Text
import com.arflix.tv.data.embed.EmbedSources
import com.arflix.tv.data.repository.SkipInterval
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.io.ByteArrayInputStream
import kotlin.math.roundToInt

/**
 * Streaming Modes TV — player das "Minhas fontes" (StreamBetter/Pomfy), igual ao do PC: a página do site
 * fica num WebView (dentro de um iframe, como o site exige) e os NOSSOS controles ficam por cima.
 * O WebView nunca recebe o foco nem os cliques do controle remoto, então os anúncios de clique/pop-up
 * não abrem; janelas novas e navegações da página principal são bloqueadas, e domínios de anúncio
 * conhecidos são cortados.
 *
 * StreamBetter fala a API de embed dele (postMessage): manda streambetter:progress e aceita
 * play/pause/seek. Sites sem essa API (Pomfy) usam o cursor: setas movem, OK clica.
 * O cursor também serve se a Cloudflare pedir "Confirme que é humano" (quem clica é a pessoa).
 */
@Composable
internal fun EmbedPlayerOverlay(
    embed: EmbedSources.Embed,
    title: String,
    startPositionMs: Long,
    skip: SkipInterval?,
    skipDismissed: Boolean,
    hasNextEpisode: Boolean,
    onPosition: (positionMs: Long) -> Unit,
    onProgress: (positionMs: Long, durationMs: Long, playing: Boolean) -> Unit,
    onSkipUsed: () -> Unit,
    onNextEpisode: () -> Unit,
    onOpenSources: () -> Unit,
    onFailed: () -> Unit,
    onExit: () -> Unit,
    /** Endereço do vídeo que a própria página carregou (HLS/MP4) + cabeçalhos: toca no player do app. */
    onResolved: (url: String, headers: Map<String, String>) -> Unit = { _, _ -> },
) {
    val apiPlayer = embed.provider.streamBetterApi
    var positionS by remember(embed) { mutableFloatStateOf(0f) }
    var durationS by remember(embed) { mutableFloatStateOf(0f) }
    var playing by remember(embed) { mutableStateOf(false) }
    var gotProgress by remember(embed) { mutableStateOf(false) }
    var resumeDone by remember(embed) { mutableStateOf(false) }
    var controlsUntil by remember { mutableLongStateOf(SystemClock.uptimeMillis() + 5_000L) }
    var cursorMode by remember(embed) { mutableStateOf(!apiPlayer) }
    var cursorUsed by remember(embed) { mutableStateOf(false) }
    var cursorX by remember { mutableFloatStateOf(-1f) }
    var cursorY by remember { mutableFloatStateOf(-1f) }
    var areaW by remember { mutableFloatStateOf(1920f) }
    var areaH by remember { mutableFloatStateOf(1080f) }
    var lastReportAt by remember { mutableLongStateOf(0L) }
    var nowTick by remember { mutableLongStateOf(SystemClock.uptimeMillis()) }
    var failed by remember(embed) { mutableStateOf(false) }
    var userPaused by remember(embed) { mutableStateOf(false) }
    var autoplayTries by remember(embed) { mutableStateOf(0) }

    val onProgressState by rememberUpdatedState(onProgress)
    val onPositionState by rememberUpdatedState(onPosition)
    val onNextState by rememberUpdatedState(onNextEpisode)
    val hasNextState by rememberUpdatedState(hasNextEpisode)

    val context = androidx.compose.ui.platform.LocalContext.current
    val focus = remember { FocusRequester() }
    val onResolvedState by rememberUpdatedState(onResolved)
    var resolveGaveUp by remember(embed) { mutableStateOf(false) }
    val webView = remember(embed) {
        createEmbedWebView(context) { url, headers ->
            if (!resolveGaveUp) onResolvedState(url, headers)
        }
    }

    fun send(json: String) = webView.evaluateJavascript("window.smCmd && window.smCmd($json)", null)
    // API do StreamBetter + a nossa ponte (script dentro da página; serve também para o Pomfy).
    fun play() { send("{type:'streambetter:play'}"); send("{type:'sm-bridge:cmd',cmd:'play'}") }
    fun pause() { send("{type:'streambetter:pause'}"); send("{type:'sm-bridge:cmd',cmd:'pause'}") }
    fun seekTo(seconds: Float) {
        val target = seconds.coerceIn(0f, if (durationS > 0f) durationS - 1f else Float.MAX_VALUE)
        positionS = target
        send("{type:'streambetter:seek',seconds:$target}")
        send("{type:'sm-bridge:cmd',cmd:'seek',seconds:$target}")
    }
    fun showControls() { controlsUntil = SystemClock.uptimeMillis() + 4_000L }

    // Mensagens do site (via a página de fora).
    DisposableEffect(webView) {
        val main = Handler(Looper.getMainLooper())
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun onMessage(raw: String) {
                main.post {
                    val msg = runCatching { JSONObject(raw) }.getOrNull() ?: return@post
                    val type = msg.optString("type")
                    if (type != "streambetter:progress" && type != "sm-bridge:progress") return@post
                    val t = msg.optDouble("currentTime", 0.0).toFloat()
                    val d = msg.optDouble("duration", 0.0).toFloat()
                    val state = msg.optString("state")
                    positionS = t
                    if (d > 0f) durationS = d
                    playing = state == "playing"
                    // Começa sozinho: o site espera um clique em "play"; manda o play até tocar (só no começo).
                    if (state == "paused" && !userPaused && t < 5f && autoplayTries < 8) {
                        autoplayTries++
                        webView.evaluateJavascript("window.smCmd && (window.smCmd({type:'streambetter:play'}), window.smCmd({type:'sm-bridge:cmd',cmd:'play'}))", null)
                    }
                    if (!gotProgress && d > 0f) {
                        gotProgress = true
                        cursorMode = false
                    }
                    if (!resumeDone && d > 0f) {
                        resumeDone = true
                        val start = startPositionMs / 1000f
                        if (start > 30f && start < d - 60f && t < 10f) {
                            positionS = start
                            webView.evaluateJavascript("window.smCmd && window.smCmd({type:'streambetter:seek',seconds:$start})", null)
                        }
                    }
                    onPositionState((t * 1000).toLong())
                    val now = SystemClock.uptimeMillis()
                    if (d > 0f && (now - lastReportAt > 5_000L || state == "ended" || state == "paused")) {
                        lastReportAt = now
                        onProgressState((t * 1000).toLong(), (d * 1000).toLong(), playing)
                    }
                    if (state == "ended" && hasNextState) onNextState()
                }
            }
        }, "SM")
        webView.loadDataWithBaseURL(EMBED_BASE_URL, hostPage(embed.pageUrl), "text/html", "utf-8", null)
        onDispose {
            runCatching {
                webView.stopLoading()
                webView.loadUrl("about:blank")
                webView.removeAllViews()
                webView.destroy()
            }
        }
    }

    // Relógio da tela (esconde controles, prazo de carregamento).
    LaunchedEffect(embed) {
        val openedAt = SystemClock.uptimeMillis()
        while (true) {
            delay(500L)
            nowTick = SystemClock.uptimeMillis()
            // O site só começa com um "play": manda a cada 2 s nos primeiros 45 s, até tocar.
            if (!playing && !userPaused && nowTick - openedAt < 45_000L && (nowTick - openedAt) % 2_000L < 500L) play()
            if (!resolveGaveUp && nowTick - openedAt > 40_000L) {
                resolveGaveUp = true
                send("{type:'sm-bridge:cmd',cmd:'unmute'}")
            }
            if (apiPlayer && !gotProgress && !failed) {
                val waited = nowTick - openedAt
                // A página pode estar pedindo a verificação da Cloudflare: liga o cursor para a pessoa clicar.
                if (waited > 15_000L && !cursorMode && !cursorUsed) cursorMode = true
                val limit = if (cursorUsed) 180_000L else 60_000L
                if (waited > limit) { failed = true; onFailed() }
            }
        }
    }

    val skipVisible = skip != null && !skipDismissed && gotProgress &&
        (positionS * 1000).toLong() in skip.startMs until skip.endMs
    val nextVisible = hasNextEpisode && gotProgress && durationS > 300f && durationS - positionS < 45f && !skipVisible
    val controlsVisible = !gotProgress || !playing || nowTick < controlsUntil

    // Janela própria e transparente: no Fire TV o WebView da Amazon desenha o vídeo numa SurfaceView que fica
    // logo ATRÁS da janela dele e abre um "buraco" na página. Dentro da janela do app, o fundo do player tapava
    // o buraco (som sem imagem); numa janela só nossa, o que fica atrás do vídeo é só ele.
    Dialog(
        onDismissRequest = onExit,
        properties = DialogProperties(
            dismissOnBackPress = false,
            dismissOnClickOutside = false,
            usePlatformDefaultWidth = false,
            decorFitsSystemWindows = false
        )
    ) {
    val dialogWindow = (LocalView.current.parent as? DialogWindowProvider)?.window
    SideEffect {
        dialogWindow?.let { w ->
            w.setBackgroundDrawable(android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT))
            w.setFormat(android.graphics.PixelFormat.TRANSLUCENT)
            w.clearFlags(android.view.WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            w.setLayout(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
            w.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }
    LaunchedEffect(Unit) {
        // A janela do diálogo demora um quadro para existir: tenta o foco algumas vezes.
        repeat(10) { if (runCatching { focus.requestFocus() }.isSuccess) return@LaunchedEffect; delay(100L) }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(120f)
            .onSizeChanged {
                areaW = it.width.toFloat(); areaH = it.height.toFloat()
                if (cursorX < 0f) { cursorX = areaW / 2f; cursorY = areaH / 2f }
            }
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { ev ->
                val down = ev.type == KeyEventType.KeyDown
                if (cursorMode) {
                    val step = 48f
                    when (ev.key) {
                        Key.DirectionLeft -> { if (down) cursorX = (cursorX - step).coerceAtLeast(0f); true }
                        Key.DirectionRight -> { if (down) cursorX = (cursorX + step).coerceAtMost(areaW - 1f); true }
                        Key.DirectionUp -> { if (down) cursorY = (cursorY - step).coerceAtLeast(0f); true }
                        Key.DirectionDown -> { if (down) cursorY = (cursorY + step).coerceAtMost(areaH - 1f); true }
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter -> {
                            if (down) { cursorUsed = true; tapAt(webView, cursorX, cursorY) }
                            true
                        }
                        Key.Menu -> { if (down && apiPlayer) cursorMode = false; true }
                        Key.Back -> {
                            if (!down) return@onPreviewKeyEvent true
                            if (apiPlayer && gotProgress) cursorMode = false else onExit()
                            true
                        }
                        else -> false
                    }
                } else {
                    if (!down) {
                        return@onPreviewKeyEvent ev.key in HANDLED_KEYS
                    }
                    showControls()
                    when (ev.key) {
                        Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause -> {
                            when {
                                skipVisible -> { seekTo((skip!!.endMs + 500L) / 1000f); onSkipUsed() }
                                nextVisible -> onNextEpisode()
                                playing -> { userPaused = true; pause() }
                                else -> { userPaused = false; play() }
                            }
                            true
                        }
                        Key.MediaPlay -> { userPaused = false; play(); true }
                        Key.MediaPause -> { userPaused = true; pause(); true }
                        Key.DirectionLeft, Key.MediaRewind -> { seekTo(positionS - 10f); true }
                        Key.DirectionRight, Key.MediaFastForward -> { seekTo(positionS + 10f); true }
                        Key.DirectionUp -> { onOpenSources(); true }
                        Key.DirectionDown -> true
                        Key.Menu -> { cursorMode = true; true }
                        Key.MediaNext -> { if (hasNextEpisode) onNextEpisode(); true }
                        Key.Back -> { onExit(); true }
                        else -> false
                    }
                }
            }
    ) {
        AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())

        val providerLabel = "${EmbedSources.ADDON_NAME} · ${embed.provider.name}"
        if (!gotProgress && apiPlayer) {
            InfoCard(
                text = "$providerLabel — preparando o vídeo…",
                modifier = Modifier.align(Alignment.TopStart).padding(32.dp)
            )
        }
        if (cursorMode) {
            InfoCard(
                text = if (apiPlayer) "Cursor: setas movem · OK clica · ☰ esconde"
                else "$providerLabel · cursor: setas movem · OK clica · Voltar sai",
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 24.dp)
            )
            val d = LocalDensity.current
            val r = with(d) { 14.dp.toPx() }
            Box(
                modifier = Modifier
                    .offset { IntOffset((cursorX - r).roundToInt(), (cursorY - r).roundToInt()) }
                    .size(28.dp)
                    .background(Color.White.copy(alpha = 0.85f), CircleShape)
                    .border(3.dp, Color.Black.copy(alpha = 0.6f), CircleShape)
            )
        }

        if (gotProgress && controlsVisible && !cursorMode) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.85f))))
                    .padding(start = 48.dp, end = 48.dp, top = 48.dp, bottom = 32.dp)
            ) {
                Text(title, color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(providerLabel, color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp)
                Spacer(Modifier.height(12.dp))
                val frac = if (durationS > 0f) (positionS / durationS).coerceIn(0f, 1f) else 0f
                Box(Modifier.fillMaxWidth().height(6.dp).background(Color.White.copy(alpha = 0.25f), RoundedCornerShape(3.dp))) {
                    Box(Modifier.fillMaxWidth(frac).height(6.dp).background(Color(0xFFE50914), RoundedCornerShape(3.dp)))
                }
                Spacer(Modifier.height(8.dp))
                Row {
                    Text(
                        "${if (playing) "▶" else "❚❚"}  ${fmt(positionS)} / ${fmt(durationS)}",
                        color = Color.White, fontSize = 16.sp
                    )
                    Spacer(Modifier.width(24.dp))
                    Text(
                        "OK pausar · ◀ ▶ 10 s · ▲ fontes · ☰ cursor" + if (hasNextEpisode) " · ⏭ próximo" else "",
                        color = Color.White.copy(alpha = 0.6f), fontSize = 14.sp
                    )
                }
            }
        }

        if (skipVisible || nextVisible) {
            val label = when {
                nextVisible -> "Próximo episódio  ▶ OK"
                skip!!.type.lowercase() in listOf("recap") -> "Pular resumo  ▶ OK"
                skip.type.lowercase() in listOf("outro", "ed", "mixed-ed") -> "Pular créditos  ▶ OK"
                else -> "Pular abertura  ▶ OK"
            }
            Box(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(end = 48.dp, bottom = if (controlsVisible) 150.dp else 48.dp)
                    .background(Color.White, RoundedCornerShape(6.dp))
                    .padding(horizontal = 24.dp, vertical = 12.dp)
            ) {
                Text(label, color = Color.Black, fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    }
}

@Composable
private fun InfoCard(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(text, color = Color.White, fontSize = 16.sp)
    }
}

private val HANDLED_KEYS = setOf(
    Key.DirectionCenter, Key.Enter, Key.NumPadEnter, Key.MediaPlayPause, Key.MediaPlay, Key.MediaPause,
    Key.DirectionLeft, Key.DirectionRight, Key.DirectionUp, Key.DirectionDown, Key.MediaRewind,
    Key.MediaFastForward, Key.Menu, Key.MediaNext, Key.Back
)

private fun fmt(seconds: Float): String {
    val s = seconds.toInt().coerceAtLeast(0)
    val h = s / 3600; val m = (s % 3600) / 60; val sec = s % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%d:%02d".format(m, sec)
}

/** Clique de verdade no ponto do cursor (a pessoa escolheu onde clicar com o controle). */
private fun tapAt(view: WebView, x: Float, y: Float) {
    val t = SystemClock.uptimeMillis()
    val down = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0)
    val up = MotionEvent.obtain(t, t + 60, MotionEvent.ACTION_UP, x, y, 0)
    view.dispatchTouchEvent(down)
    view.dispatchTouchEvent(up)
    down.recycle(); up.recycle()
}

private const val EMBED_BASE_URL = "https://appassets.androidplatform.net/sm/"

private fun hostPage(pageUrl: String): String {
    val src = pageUrl.replace("&", "&amp;").replace("\"", "&quot;")
    return """<!doctype html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<style>html,body{margin:0;height:100%;background:transparent;overflow:hidden}iframe{position:fixed;inset:0;width:100%;height:100%;border:0}</style></head>
<body><iframe id="f" src="$src" allow="autoplay; encrypted-media; fullscreen; picture-in-picture" allowfullscreen referrerpolicy="origin"></iframe>
<script>
var f=document.getElementById('f');
addEventListener('message',function(e){var d=e.data;var ok=e.source===f.contentWindow||(d&&typeof d.type==='string'&&d.type.indexOf('sm-bridge:')===0);if(!ok)return;try{SM.onMessage(JSON.stringify(d))}catch(_){}});
window.smCmd=function(m){try{f.contentWindow.postMessage(m,'*')}catch(_){}};
</script></body></html>"""
}

/** Domínios de anúncio/pop-up comuns nesses players: a requisição volta vazia. */
private val AD_HOSTS = listOf(
    "doubleclick.net", "googlesyndication.com", "googleadservices.com", "adservice.google", "popads.net",
    "popcash.net", "propellerads", "adsterra", "exoclick.com", "juicyads.com", "onclickads", "hilltopads",
    "a-ads.com", "monetag", "adcash", "clickadu", "trafficjunky", "realsrv.com", "pushwhy", "highperformanceformat",
    "profitablecpmrate", "effectivegatecpm", "displayvertising", "admaven", "ad-maven", "acscdn.com", "aclib",
    "tsyndicate.com", "bidvertiser", "revcontent", "mgid.com", "taboola.com", "outbrain.com"
)

@SuppressLint("SetJavaScriptEnabled")
private fun createEmbedWebView(
    context: android.content.Context,
    onMedia: (url: String, headers: Map<String, String>) -> Unit
): WebView = WebView(context).apply {
    val resolved = java.util.concurrent.atomic.AtomicBoolean(false)
    val main = Handler(Looper.getMainLooper())
    setBackgroundColor(android.graphics.Color.TRANSPARENT)
    isFocusable = false
    isFocusableInTouchMode = false
    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    settings.javaScriptEnabled = true
    settings.domStorageEnabled = true
    settings.mediaPlaybackRequiresUserGesture = false
    settings.setSupportMultipleWindows(true) // janelas novas passam por onCreateWindow, que recusa
    settings.javaScriptCanOpenWindowsAutomatically = false
    settings.loadWithOverviewMode = true
    settings.useWideViewPort = true
    // Mesmo navegador (Chromium), sem a marca "wv" de WebView embutido.
    settings.userAgentString = settings.userAgentString.replace("; wv", "")
    val userAgent = settings.userAgentString
    CookieManager.getInstance().setAcceptCookie(true)
    CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    if (androidx.webkit.WebViewFeature.isFeatureSupported(androidx.webkit.WebViewFeature.DOCUMENT_START_SCRIPT)) {
        androidx.webkit.WebViewCompat.addDocumentStartJavaScript(this, BRIDGE_JS, setOf("*"))
    }
    webChromeClient = object : WebChromeClient() {
        override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?) = false
        override fun getDefaultVideoPoster(): Bitmap = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            // O iframe já ocupa a tela inteira; recusar o "tela cheia" do site evita trocar de view.
            callback?.onCustomViewHidden()
        }
    }
    webViewClient = object : WebViewClient() {
        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            // A página de fora é nossa: qualquer navegação dela (redirecionamento de anúncio) é bloqueada.
            return request?.isForMainFrame == true
        }

        override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
            val host = request?.url?.host?.lowercase() ?: return null
            if (AD_HOSTS.any { host.contains(it) }) {
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }
            // O vídeo que o player da página pede (lista HLS ou MP4): vai para o player do app.
            val req = request!!
            val url = req.url.toString()
            val path = req.url.path.orEmpty().lowercase()
            val isHls = path.endsWith(".m3u8") || url.contains(".m3u8?", ignoreCase = true) || url.contains("mpegurl", ignoreCase = true)
            val isFile = path.endsWith(".mp4") || path.endsWith(".mkv")
            if ((isHls || isFile) && !resolved.get()) {
                val headers = linkedMapOf<String, String>()
                req.requestHeaders?.forEach { (k, v) ->
                    if (k.equals("Referer", true) || k.equals("Origin", true)) headers[k] = v
                }
                headers["User-Agent"] = userAgent
                runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()
                    ?.takeIf { it.isNotBlank() }?.let { headers["Cookie"] = it }
                if (isHls) {
                    if (resolved.compareAndSet(false, true)) main.post { onMedia(url, headers) }
                } else {
                    // MP4 pode ser anúncio em vídeo: só usa se nenhuma lista HLS aparecer em 6 s.
                    main.postDelayed({ if (resolved.compareAndSet(false, true)) onMedia(url, headers) }, 6_000L)
                }
            }
            return null
        }
    }
}

/**
 * Ponte dentro das páginas (igual à extensão ponte.js do PC), injetada em todos os frames deste WebView:
 * - deixa transparente o fundo do <video> e dos elementos em volta: no Fire TV o vídeo é desenhado numa
 *   camada atrás da página e o fundo pintado por cima escondia a imagem (só som);
 * - manda tempo/duração/estado para a página de fora (sm-bridge:progress);
 * - recebe play/pause/seek (sm-bridge:cmd) e repassa para os iframes de dentro quando o vídeo não está aqui.
 */
private val BRIDGE_JS = """
(function(){
  if (window.top === window || window.__smBridge) return;
  window.__smBridge = true;
  var CSS = 'html,body{background:transparent!important;background-color:transparent!important}video{background:transparent!important}';
  function style(){ try { var s=document.createElement('style'); s.textContent=CSS; (document.head||document.documentElement).appendChild(s); } catch(e){} }
  style(); document.addEventListener('DOMContentLoaded', style);
  function vids(root, out){ out=out||[]; root.querySelectorAll('video').forEach(function(v){out.push(v)});
    root.querySelectorAll('*').forEach(function(el){ if (el.shadowRoot) vids(el.shadowRoot, out); }); return out; }
  var cur=null, last=0;
  function pick(){ var now=Date.now(); if (cur && cur.isConnected && now-last<3000) return cur; last=now; var best=null;
    vids(document).forEach(function(v){ var r=v.getBoundingClientRect(); if (r.width<80||r.height<45) return;
      var d=v.duration; if (!best || (isFinite(d) && d>(best.duration||0))) best=v; }); cur=best; return cur; }
  function clear(v){ var e=v; while (e && e!==document.documentElement) { try { e.style.setProperty('background','transparent','important'); } catch(x){} e=e.parentElement; } }
  function post(v){ if (!v) return; var d=v.duration; if (!isFinite(d)||d<=0) return;
    try { window.top.postMessage({type:'sm-bridge:progress', currentTime:v.currentTime, duration:d,
      state: v.ended?'ended':(v.paused?'paused':'playing')}, '*'); } catch(e){} }
  // Sem som enquanto o app procura o endereço do vídeo (ele toca no player do app); 'unmute' devolve o som.
  setInterval(function(){ var v=pick(); if (v) { if (!window.__smUnmuted) v.muted=true; clear(v); post(v); } }, 500);
  window.addEventListener('message', function(e){
    var m=e.data; if (!m || m.type!=='sm-bridge:cmd') return;
    var v=pick();
    if (!v) { document.querySelectorAll('iframe').forEach(function(f){ try { f.contentWindow.postMessage(m,'*'); } catch(x){} }); return; }
    if (m.cmd==='play') { var p=v.play(); if (p&&p.catch) p.catch(function(){}); }
    else if (m.cmd==='pause') v.pause();
    else if (m.cmd==='seek' && isFinite(m.seconds)) v.currentTime=Math.max(0,m.seconds);
    else if (m.cmd==='unmute') { window.__smUnmuted=true; v.muted=false; }
  });
})();
"""
