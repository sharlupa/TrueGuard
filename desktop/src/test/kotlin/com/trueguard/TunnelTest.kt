package com.trueguard

import com.trueguard.core.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import kotlin.test.*

/** Контроллер туннеля против поддельного ядра, говорящего тем же протоколом событий. */
class TunnelTest {
    private lateinit var tmp: File
    private val t = TunnelController

    /** behaviour: ok — работает до STOP; crash — падает после старта; auth — неверный пароль. */
    private fun fakeCore(behaviour: String) = """#!/bin/bash
conf=""
while [ ${'$'}# -gt 0 ]; do [ "${'$'}1" = "-c" ] && conf="${'$'}2"; shift; done
cp "${'$'}conf" "${tmp.path}/last.conf"
echo x >> "${tmp.path}/launches"
ev() { echo "__WDTT_EVENT__|${'$'}1|${'$'}2"; }
log() { echo "2026/10/12 10:00:00.000000 ${'$'}*" >&2; }
ev STARTED '{"mode":"proxy","workers":36}'
log "[ГРУППА #1] Запрос кредов (хеш: AAAA...)"
sleep 0.2
case "$behaviour" in
  crash) log "panic: boom"; exit 2 ;;
  auth) log "[ВОРКЕР #1] Фатальная ошибка: FATAL_AUTH: неверный пароль"
        ev ERROR '{"code":"AUTH","message":"неверный пароль","fatal":true}'; exit 0 ;;
esac
log "[ВОРКЕР #1] [DTLS] Рукопожатие (Handshake)..."
sleep 0.2
ev READY ''
ev TUNNEL_UP '{"mode":"proxy","proxy":"127.0.0.1:1080","address":"10.66.66.2/32"}'
ev STATS '{"active":9,"workers":36,"bytes_up":1000,"bytes_down":5000}'
while read -r line; do
  if [ "${'$'}line" = "STOP" ]; then log "[КЛИЕНТ] Команда STOP"; ev STOPPED ''; exit 0; fi
done
exit 0
"""

    /** Поддельный sudo: не кеширует права, пароль читает одной строкой из stdin. */
    private val fakeSudo = """#!/bin/bash
if [ "${'$'}1" = "-n" ] && [ "${'$'}2" = "-v" ]; then exit 1; fi
if [ "${'$'}1" = "-S" ]; then
  shift 3
  IFS= read -r pw
  if [ "${'$'}pw" != "correct-horse" ]; then
    echo "Sorry, try again." >&2
    IFS= read -r again
    echo "sudo: no password was provided" >&2
    echo "sudo: 1 incorrect password attempt" >&2
    exit 1
  fi
fi
[ "${'$'}1" = "-n" ] && shift
[ "${'$'}1" = "--" ] && shift
exec "${'$'}@"
"""

    private fun script(name: String, body: String): String {
        val f = File(tmp, name)
        f.writeText(body)
        f.setExecutable(true)
        return f.path
    }

    private fun useCore(behaviour: String) {
        Paths.coreOverride = script("core-$behaviour.sh", fakeCore(behaviour))
        File(tmp, "launches").delete()
    }

    private fun launches() = File(tmp, "launches").takeIf { it.exists() }?.readLines()?.size ?: 0

    private suspend fun waitFor(timeoutMs: Long = 10_000, cond: () -> Boolean) = withTimeout(timeoutMs) {
        while (!cond()) delay(50)
    }

    @BeforeTest
    fun setup() {
        tmp = Files.createTempDirectory("tg-tunnel").toFile()
        Paths.configDirOverride = tmp.path
        val s = Settings.instance
        s.servers = listOf(ServerProfile(id = "a", name = "Тест", ip = "203.0.113.10", password = "p@ss-1"))
        s.activeServerId = "a"
        s.vkHashes = listOf("AAAAAAAAAAAAAAAAAAAA", "BBBBBBBBBBBBBBBBBBBB", "", "")
        s.workersPerHash = 36
        s.tunnelMode = "proxy"
        s.customDns = "cloudflare"
        s.notifyDisconnects = false
        s.autoReconnect = true
        s.rootMethod = "auto"
        s.excludedApps = emptyList()
        Paths.toolOverride.clear()
        t.forgetRootPassword()
        t.askRootPassword = null
    }

    @AfterTest
    fun teardown() = runBlocking {
        t.stop()
        waitFor { t.phase == Phase.Idle }
    }

    @Test
    fun connectConfigAndStop() = runBlocking {
        useCore("ok")
        t.connectNow()
        waitFor { t.phase == Phase.Connected }
        assertEquals(9, t.activeWorkers)
        assertEquals(36, t.totalWorkers)
        assertEquals(5000, t.bytesDown)
        assertContains(t.tunnelInfo, "127.0.0.1:1080")

        val conf = File(tmp, "last.conf").readText()
        assertContains(conf, "peer = 203.0.113.10:56000")
        assertContains(conf, "vk = AAAAAAAAAAAAAAAAAAAA,BBBBBBBBBBBBBBBBBBBB")
        assertContains(conf, "password = p@ss-1")
        assertContains(conf, "n = 36")
        assertContains(conf, "mode = proxy")
        assertContains(conf, "wg-dns = 1.1.1.1,1.0.0.1")
        assertContains(conf, "captcha-mode = auto")
        // файл с паролем удаляется сразу после STARTED
        assertTrue(File(Paths.runtimeDir).listFiles().orEmpty().none { it.name.startsWith("run-") })

        t.stop()
        waitFor { t.phase == Phase.Idle }
        assertNull(t.lastError)
    }

    @Test
    fun crashRestarts() = runBlocking {
        useCore("crash")
        t.connectNow()
        waitFor(15_000) { launches() >= 2 }
        assertNotEquals(Phase.Idle, t.phase)
    }

    @Test
    fun wrongConnectionPasswordStops() = runBlocking {
        useCore("auth")
        t.connectNow()
        waitFor { t.phase == Phase.Idle }
        delay(3000)
        assertEquals(1, launches())
        assertContains(t.lastError ?: "", "Неверный пароль")
    }

    private fun useSudo() {
        Paths.toolOverride["sudo"] = script("sudo", fakeSudo)
        Paths.toolOverride["wg-quick"] = "/bin/true"
        Settings.instance.tunnelMode = "tun"
        Settings.instance.rootMethod = "sudo"
    }

    @Test
    fun sudoAskedOnceAndRemembered() = runBlocking {
        useCore("ok")
        useSudo()
        var asked = 0
        t.askRootPassword = { asked++; "correct-horse" }
        t.connectNow()
        waitFor { t.phase == Phase.Connected }
        assertEquals(1, asked)
        assertContains(File(tmp, "last.conf").readText(), "mode = tun")
        t.stop()
        waitFor { t.phase == Phase.Idle }
        // повторное подключение берёт пароль из памяти, окно не показывается
        t.connectNow()
        waitFor { t.phase == Phase.Connected }
        assertEquals(1, asked)
    }

    @Test
    fun wrongSudoPassword() = runBlocking {
        useCore("ok")
        useSudo()
        t.askRootPassword = { "wrong" }
        t.connectNow()
        waitFor { t.phase == Phase.Idle }
        delay(3000)
        assertEquals("Неверный пароль администратора", t.lastError)
        assertEquals(0, launches())
        var asked: String? = null
        t.askRootPassword = { "correct-horse".also { asked = it } }
        t.connectNow()
        waitFor { t.phase == Phase.Connected }
        assertEquals("correct-horse", asked)
    }

    @Test
    fun sudoDialogCancelled() = runBlocking {
        useCore("ok")
        useSudo()
        t.askRootPassword = { null }
        t.connectNow()
        assertEquals(Phase.Idle, t.phase)
        assertEquals("Запрос прав администратора отменён", t.lastError)
    }
}
