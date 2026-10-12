package com.trueguard.client

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/** Системное окно «Разрешить VPN» показывает активность; общий код ждёт ответа здесь. */
object VpnPermission {
    /** Регистрирует MainActivity: запускает системный диалог и возвращает ответ. */
    @Volatile var launcher: ((Intent, (Boolean) -> Unit) -> Unit)? = null

    suspend fun request(ctx: Context, intent: Intent): Boolean {
        if (launcher == null) {
            // подключение из плитки или уведомления: сначала открываем приложение
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            withTimeoutOrNull(10_000) { while (launcher == null) delay(50) }
        }
        val l = launcher ?: return false
        val answer = CompletableDeferred<Boolean>()
        l(intent) { answer.complete(it) }
        return answer.await()
    }
}

/** Запрос капчи от ядра: показывается диалогом с WebView в MainActivity. */
class CaptchaRequest(
    val id: Int,
    val url: String,
    val script: String,
    val binding: String,
    val result: CompletableDeferred<String?>,
)

object CaptchaBridge {
    var pending by mutableStateOf<CaptchaRequest?>(null)
        private set

    suspend fun solve(ctx: Context, request: JsonObject): String? {
        val url = (request["url"] as? JsonPrimitive)?.content ?: return null
        val req = CaptchaRequest(
            id = (request["id"] as? JsonPrimitive)?.intOrNull ?: 0,
            url = url,
            script = (request["script"] as? JsonPrimitive)?.content ?: "",
            binding = (request["binding"] as? JsonPrimitive)?.content ?: "__wdttCaptcha",
            result = CompletableDeferred(),
        )
        pending = req
        if (!MainActivity.visible) notifyCaptcha(ctx)
        return try {
            req.result.await()
        } finally {
            if (pending === req) pending = null
            ctx.getSystemService(NotificationManager::class.java).cancel(NOTIFY_CAPTCHA)
        }
    }

    fun finish(token: String?) {
        pending?.result?.complete(token)
        pending = null
    }

    private fun notifyCaptcha(ctx: Context) {
        val open = PendingIntent.getActivity(ctx, 3, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, TrueGuardApp.CHANNEL_ALERTS)
            .setSmallIcon(R.drawable.ic_stat_connected)
            .setContentTitle("Требуется подтверждение капчи")
            .setContentText("ВК запросил проверку безопасности. Нажмите для решения.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()
        runCatching { ctx.getSystemService(NotificationManager::class.java).notify(NOTIFY_CAPTCHA, n) }
    }

    private const val NOTIFY_CAPTCHA = 3
}
