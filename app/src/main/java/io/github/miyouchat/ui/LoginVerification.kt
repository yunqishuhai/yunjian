package io.github.miyouchat.ui

import android.annotation.SuppressLint
import android.webkit.*
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.miyouchat.data.LoginChallenge
import java.io.ByteArrayInputStream
import java.net.URI

/** The isolated page receives only challenge parameters, never phone numbers or credentials. */
@SuppressLint("SetJavaScriptEnabled")
@Composable fun LoginVerification(challenge: LoginChallenge, completed: (String) -> Unit, cancelled: () -> Unit) {
    var failed by remember(challenge) { mutableStateOf(false) }
    var revision by remember(challenge) { mutableIntStateOf(0) }
    val completion by rememberUpdatedState(completed)
    val handler = remember { Handler(Looper.getMainLooper()) }
    Dialog(onDismissRequest = cancelled, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.windowInsetsPadding(WindowInsets.safeDrawing).padding(horizontal = 16.dp, vertical = 12.dp)) {
                Text("安全验证", style = MaterialTheme.typography.titleLarge)
                Text("请完成米游社要求的验证，完成后将继续登录。", Modifier.padding(top = 8.dp, bottom = 16.dp), style = MaterialTheme.typography.bodyMedium)
                key(challenge, revision) {
                    AndroidView(modifier = Modifier.fillMaxWidth().weight(1f), factory = { context ->
                        WebView(context).apply {
                            layoutParams = android.view.ViewGroup.LayoutParams(android.view.ViewGroup.LayoutParams.MATCH_PARENT, android.view.ViewGroup.LayoutParams.MATCH_PARENT)
                            settings.javaScriptEnabled = true
                            settings.useWideViewPort = true
                            settings.loadWithOverviewMode = false
                            settings.textZoom = 100
                            settings.allowFileAccess = false
                            settings.allowContentAccess = false
                            settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                            settings.cacheMode = WebSettings.LOAD_NO_CACHE
                            settings.setGeolocationEnabled(false)
                            settings.setSupportMultipleWindows(false)
                            CookieManager.getInstance().setAcceptThirdPartyCookies(this, false)
                            addJavascriptInterface(object {
                                @JavascriptInterface fun complete(result: String) {
                                    if (result.length <= 16384) handler.post { completion(result) }
                                }
                                @JavascriptInterface fun error() { handler.post { failed = true } }
                            }, "YunjianVerification")
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = true
                                override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                                    return if (verificationResourceAllowed(request.url.toString())) null
                                    else WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                                }
                                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                    handler.post { failed = true }
                                }
                            }
                            loadDataWithBaseURL("https://yunjian.invalid/verification/", verificationHtml(challenge), "text/html", "UTF-8", null)
                        }
                    }, onReset = null, onRelease = { view ->
                        view.removeJavascriptInterface("YunjianVerification")
                        view.stopLoading()
                        view.destroy()
                    })
                }
                if (failed) Text("验证加载失败，请检查网络后重试。", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = cancelled) { Text("取消") }
                    TextButton(onClick = { failed = false; revision++ }) { Text("重新加载") }
                }
            }
        }
    }
}

internal fun verificationResourceAllowed(url: String): Boolean = runCatching {
    val uri = URI(url)
    val host = uri.host.orEmpty().lowercase()
    uri.scheme == "https" && uri.rawUserInfo == null && (uri.port == -1 || uri.port == 443) &&
        listOf("geetest.com", "geevisit.com", "gsensebot.com").any { host == it || host.endsWith(".$it") }
}.getOrDefault(false)

internal fun verificationHtml(challenge: LoginChallenge): String {
    val script = if (challenge.version == 4) "https://static.geetest.com/v4/gt4.js" else "https://static.geetest.com/static/js/gt.0.5.0.js"
    val initialize = if (challenge.version == 4) "initGeetest4" else "initGeetest"
    return """<!doctype html><html lang="zh-CN"><head>
        <meta name="viewport" content="width=device-width,initial-scale=1">
        <style>html,body{width:100%;height:100%;margin:0}body{font:16px sans-serif;background:#fff;color:#263648;display:flex;align-items:center;justify-content:center}#captcha{width:100%;text-align:center}#loading{position:absolute;top:24px;width:100%;text-align:center}</style>
        </head><body><div id="loading" role="status">正在加载验证…</div><div id="captcha"></div>
        <script src="$script" onerror="YunjianVerification.error()"></script><script>
        try {
          var options=${challenge.options};
          if(${challenge.version}===4){options.rem=1;options.nextWidth=Math.min(360,window.innerWidth-24)+'px';options.nativeButton={width:'100%',height:'50px'};}
          $initialize(options,function(captcha){
            captcha.appendTo('#captcha');
            captcha.onReady(function(){document.getElementById('loading').style.display='none';});
            captcha.onSuccess(function(){var result=captcha.getValidate();if(result)YunjianVerification.complete(JSON.stringify(result));});
            captcha.onError(function(){YunjianVerification.error();});
          });
        }catch(e){YunjianVerification.error();}
        </script></body></html>""".trimIndent()
}
