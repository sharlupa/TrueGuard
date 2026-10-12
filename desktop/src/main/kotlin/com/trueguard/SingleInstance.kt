package com.trueguard

import com.trueguard.core.LogStore
import com.trueguard.core.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.net.StandardProtocolFamily
import java.net.UnixDomainSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.ServerSocketChannel
import java.nio.channels.SocketChannel
import java.nio.file.Files

/** Один экземпляр: команды второго запуска (show / connect) приходят по unix-сокету. */
object SingleInstance {
    private var server: ServerSocketChannel? = null
    private val path get() = File(Paths.runtimeDir, "gui.sock")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun forward(cmd: String): Boolean = try {
        SocketChannel.open(UnixDomainSocketAddress.of(path.toPath())).use { ch ->
            ch.write(ByteBuffer.wrap("$cmd\n".toByteArray()))
        }
        true
    } catch (_: Exception) {
        false
    }

    suspend fun listen(onCommand: (String) -> Unit) = withContext(Dispatchers.IO) {
        try {
            path.parentFile.mkdirs()
            Files.deleteIfExists(path.toPath())
            val srv = ServerSocketChannel.open(StandardProtocolFamily.UNIX)
            srv.bind(UnixDomainSocketAddress.of(path.toPath()))
            server = srv
            scope.launch {
                while (srv.isOpen) {
                    val client = runCatching { srv.accept() }.getOrNull() ?: break
                    launch {
                        client.use { ch ->
                            val buf = ByteBuffer.allocate(256)
                            val sb = StringBuilder()
                            while (ch.read(buf) > 0) {
                                buf.flip()
                                sb.append(Charsets.UTF_8.decode(buf))
                                buf.clear()
                            }
                            sb.lines().map { it.trim() }.filter { it.isNotEmpty() }.forEach { cmd ->
                                withContext(Dispatchers.Main) { onCommand(cmd) }
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            LogStore.addRaw("[GUI] single-instance: $e")
        }
    }

    fun close() {
        runCatching { server?.close() }
        runCatching { Files.deleteIfExists(path.toPath()) }
    }
}
