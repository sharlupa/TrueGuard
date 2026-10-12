package com.trueguard.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** Установленное приложение из .desktop-файла (аналог списка пакетов Android). */
data class DesktopApp(
    val id: String,
    val name: String,
    val key: String, // что передаётся ядру в -apps
    val iconPath: String?,
    val system: Boolean,
    val comment: String,
)

object AppScanner {
    @Volatile private var cache: List<DesktopApp>? = null

    private val dataDirs: List<String>
        get() {
            val env = System.getenv()
            val home = env["XDG_DATA_HOME"]?.takeIf { it.isNotEmpty() } ?: "${Paths.home}/.local/share"
            val dirs = (env["XDG_DATA_DIRS"]?.takeIf { it.isNotEmpty() } ?: "/usr/local/share:/usr/share").split(':')
            return (listOf(home) + dirs + listOf(
                "${Paths.home}/.local/share/flatpak/exports/share",
                "/var/lib/flatpak/exports/share",
                "/var/lib/snapd/desktop",
            )).filter { it.isNotEmpty() }.distinct()
        }

    suspend fun scan(refresh: Boolean = false): List<DesktopApp> = withContext(Dispatchers.IO) {
        cache?.takeIf { !refresh }?.let { return@withContext it }
        val seen = HashSet<String>()
        val apps = ArrayList<DesktopApp>()
        for (dir in dataDirs) {
            val d = File(dir, "applications")
            if (!d.isDirectory) continue
            d.walkTopDown().filter { it.isFile && it.name.endsWith(".desktop") }.forEach { f ->
                val id = f.path.substring(d.path.length + 1).replace('/', '-')
                if (!seen.add(id)) return@forEach // пользовательский файл перекрывает системный
                runCatching { parse(id, f.readLines()) }.getOrNull()?.let(apps::add)
            }
        }
        val list = apps.distinctBy { it.key }.sortedBy { it.name.lowercase() }
        cache = list
        list
    }

    private val systemCategories = setOf("System", "Settings", "Monitor", "PackageManager", "HardwareSettings", "DesktopSettings", "Accessibility")

    private fun parse(id: String, lines: List<String>): DesktopApp? {
        val v = HashMap<String, String>()
        var inEntry = false
        for (raw in lines) {
            val line = raw.trim()
            if (line.startsWith("[")) {
                inEntry = line == "[Desktop Entry]"
                continue
            }
            if (!inEntry || line.startsWith("#")) continue
            val i = line.indexOf('=')
            if (i <= 0) continue
            v.putIfAbsent(line.substring(0, i).trim(), line.substring(i + 1).trim())
        }
        if (v["Type"] != "Application" || v["Hidden"] == "true") return null
        val exec = v["Exec"]?.takeIf { it.isNotEmpty() } ?: return null
        val name = v["Name[ru]"] ?: v["Name[ru_RU]"] ?: v["Name"] ?: id
        val key = keyFor(v, exec) ?: return null
        val cats = (v["Categories"] ?: "").split(';').toSet()
        val system = v["NoDisplay"] == "true" || v["Terminal"] == "true" || cats.intersect(systemCategories).isNotEmpty()
        return DesktopApp(id, name, key, findIcon(v["Icon"]), system, v["Comment[ru]"] ?: v["Comment"] ?: "")
    }

    private fun keyFor(v: Map<String, String>, exec: String): String? {
        v["X-Flatpak"]?.takeIf { it.isNotEmpty() }?.let { return "flatpak:$it" }
        v["X-SnapInstanceName"]?.takeIf { it.isNotEmpty() }?.let { return "snap:$it" }
        val args = splitExec(exec)
        var i = 0
        while (i < args.size && (args[i] == "env" || args[i].endsWith("/env") || Regex("^[A-Za-z_][A-Za-z0-9_]*=").containsMatchIn(args[i]))) i++
        if (i >= args.size) return null
        val prog = args[i]
        if (prog.endsWith("flatpak") && "run" in args) {
            val rest = args.drop(args.indexOf("run") + 1).filter { !it.startsWith("-") && !it.startsWith("@@") }
            if (rest.isNotEmpty()) return "flatpak:${rest.first()}"
        }
        if (prog.startsWith("/snap/bin/")) return "snap:${prog.substring(10).substringBefore('.')}"
        val base = prog.substringAfterLast('/')
        if (base.isEmpty() || base.startsWith("%")) return null
        return base
    }

    /** Разбор строки Exec= по правилам Desktop Entry: кавычки, экранирование, %-коды. */
    fun splitExec(exec: String): List<String> {
        val out = ArrayList<String>()
        val cur = StringBuilder()
        var quoted = false
        var hasToken = false
        var i = 0
        while (i < exec.length) {
            val c = exec[i]
            when {
                c == '\\' && i + 1 < exec.length -> {
                    cur.append(exec[++i]); hasToken = true
                }
                c == '"' -> {
                    quoted = !quoted; hasToken = true
                }
                c == ' ' && !quoted -> {
                    if (hasToken) out.add(cur.toString())
                    cur.clear(); hasToken = false
                }
                else -> {
                    cur.append(c); hasToken = true
                }
            }
            i++
        }
        if (hasToken) out.add(cur.toString())
        return out.filterNot { Regex("^%[a-zA-Z]$").matches(it) }
    }

    private val iconCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    private fun findIcon(icon: String?): String? {
        if (icon.isNullOrEmpty()) return null
        if (icon.startsWith("/")) return icon.takeIf { it.endsWith(".png") && File(it).exists() }
        iconCache[icon]?.let { return it.ifEmpty { null } }
        val bases = listOf("${Paths.home}/.local/share/icons") + dataDirs.map { "$it/icons" }
        val sizes = listOf("48x48", "64x64", "96x96", "128x128", "256x256", "32x32", "512x512")
        var found: String? = null
        loop@ for (base in bases) for (theme in listOf("hicolor", "Papirus", "breeze", "Adwaita")) for (size in sizes) {
            val p = "$base/$theme/$size/apps/$icon.png"
            if (File(p).exists()) {
                found = p
                break@loop
            }
        }
        if (found == null) {
            found = listOf("/usr/share/pixmaps", "${Paths.home}/.local/share/pixmaps").map { "$it/$icon.png" }.firstOrNull { File(it).exists() }
        }
        iconCache[icon] = found ?: ""
        return found
    }
}
