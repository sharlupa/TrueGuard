package com.trueguard.client

import android.Manifest
import android.annotation.SuppressLint
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.RenderProcessGoneDetail
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import com.trueguard.core.Settings
import com.trueguard.ui.AppShell
import com.trueguard.ui.Onboarding
import com.trueguard.ui.components.PlainButton
import com.trueguard.ui.theme.TrueGuardTheme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class MainActivity : ComponentActivity() {
    private var vpnCallback: ((Boolean) -> Unit)? = null
    private val vpnLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        vpnCallback?.invoke(r.resultCode == RESULT_OK)
        vpnCallback = null
    }
    private val notificationsLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        VpnPermission.launcher = { intent, done ->
            runOnUiThread {
                vpnCallback = done
                runCatching { vpnLauncher.launch(intent) }.onFailure { done(false) }
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationsLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            TrueGuardTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    val s = Settings.instance
                    if (!s.onboardingDone) {
                        Box(Modifier.fillMaxSize().safeDrawingPadding()) { Onboarding(onFinish = { s.onboardingDone = true }) }
                    } else {
                        // нижнюю системную полосу закрывает сама панель навигации, клавиатуру — imePadding
                        val sides = WindowInsetsSides.Top + WindowInsetsSides.Horizontal
                        Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(sides)).imePadding()) { AppShell() }
                    }
                    CaptchaHost()
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        visible = true
    }

    override fun onStop() {
        visible = false
        super.onStop()
    }

    override fun onDestroy() {
        if (isFinishing) VpnPermission.launcher = null
        super.onDestroy()
    }

    companion object {
        @Volatile var visible = false
            private set
    }
}

/** Капча ВК в WebView: перехватчик ядра сообщает токен через мост TGBridge. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
private fun CaptchaHost() {
    val req = CaptchaBridge.pending ?: return
    val cs = MaterialTheme.colorScheme
    Dialog(onDismissRequest = { CaptchaBridge.finish(null) }, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxWidth(0.94f), shape = RoundedCornerShape(28.dp), color = cs.surfaceContainerHigh) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.Security, null, tint = cs.primary)
                    Spacer(Modifier.width(10.dp))
                    Text("Проверка безопасности ВК", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                }
                Spacer(Modifier.height(12.dp))
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            addJavascriptInterface(object {
                                @JavascriptInterface
                                fun post(json: String) {
                                    val o = runCatching { Json.parseToJsonElement(json).jsonObject }.getOrNull() ?: return
                                    when (o["t"]?.jsonPrimitive?.content) {
                                        "ok" -> post { CaptchaBridge.finish(o["v"]?.jsonPrimitive?.content) }
                                        "err" -> post { Toast.makeText(ctx, "Ошибка проверки, попробуйте ещё раз", Toast.LENGTH_SHORT).show() }
                                    }
                                }
                            }, "TGBridge")
                            // мост ядра (Chrome DevTools binding) заменяется вызовом TGBridge.post
                            val shim = "window.${req.binding}=function(p){TGBridge.post(p)};"
                            val startScript = WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                            if (startScript) WebViewCompat.addDocumentStartJavaScript(this, shim + req.script, setOf("*"))
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
                                    if (!startScript) view.evaluateJavascript(shim + req.script, null)
                                }

                                // упавший рендерер WebView иначе завершает всё приложение вместе с VPN
                                override fun onRenderProcessGone(view: WebView, detail: RenderProcessGoneDetail): Boolean {
                                    if (CaptchaBridge.pending === req) CaptchaBridge.finish(null)
                                    return true
                                }
                            }
                            loadUrl(req.url)
                        }
                    },
                    onRelease = { it.destroy() },
                    modifier = Modifier.fillMaxWidth().height(520.dp),
                )
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.End) {
                    PlainButton(onClick = { CaptchaBridge.finish(null) }) { Text("Отменить и выключить") }
                }
            }
        }
    }
}
