package com.openroute.app.ui

import android.util.Log
import android.webkit.ConsoleMessage
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.webkit.WebViewAssetLoader
import com.openroute.app.data.MapRenderState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.json.JSONObject

@Composable
fun MapWebView(
    state: MapRenderState,
    modifier: Modifier = Modifier,
) {
    val stateJson = remember(state) {
        Json.encodeToString(state)
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            val localAssetLoader = WebViewAssetLoader.Builder()
                .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(context))
                .build()
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.domStorageEnabled = true
                settings.userAgentString = settings.userAgentString + " OpenRoute/0.1"
                webViewClient = object : WebViewClient() {
                    override fun shouldInterceptRequest(
                        view: WebView?,
                        request: WebResourceRequest?,
                    ): WebResourceResponse? {
                        return request?.url?.let(localAssetLoader::shouldInterceptRequest)
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: android.webkit.WebResourceError?,
                    ) {
                        Log.e(
                            "OpenRouteWebView",
                            "load error ${request?.url} code=${error?.errorCode} desc=${error?.description}",
                        )
                        super.onReceivedError(view, request, error)
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        val webView = view ?: this@apply
                        webView.evaluateMapState()
                    }
                }
                webChromeClient = object : WebChromeClient() {
                    override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                        Log.d(
                            "OpenRouteWebView",
                            "${consoleMessage.messageLevel()} ${consoleMessage.message()} " +
                                "@${consoleMessage.sourceId()}:${consoleMessage.lineNumber()}",
                        )
                        return super.onConsoleMessage(consoleMessage)
                    }
                }
                tag = stateJson
                loadUrl("https://appassets.androidplatform.net/assets/route_map.html")
            }
        },
        update = { webView ->
            webView.tag = stateJson
            webView.evaluateMapState()
        },
    )
}

private fun WebView.evaluateMapState() {
    val payload = tag as? String ?: return
    val script = """
        (function() {
            var payload = ${JSONObject.quote(payload)};
            if (typeof window.renderState === 'function') {
                window.renderState(payload);
            } else {
                window.__openRouteQueuedState = payload;
            }
        })();
    """.trimIndent()
    evaluateJavascript(script, null)
}
