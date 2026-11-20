package lol.varie.xue

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What 学 has seen per app: whether its text gives per-character boxes, and every character marked in it, from its
 * text or read from its pictures (OCR, Lens), with when it was first seen. The characters are kept on the phone
 * (files/seen.tsv) and can be exported from the settings screen.
 */
object Stats {
    /** Distinct characters kept per app, at most. */
    private const val MAX_CHARS = 5000
    private const val FILE = "seen.tsv"

    /** A character in an app: first seen when, and whether only in pictures so far. */
    class Seen(val first: Long, var picture: Boolean)

    class App(val pkg: String) {
        /** Text blocks with Chinese seen in the last scan of this app. */
        var blocks = 0
        /** Of those, how many gave character boxes. */
        var withBoxes = 0
        var lastSeen = 0L
        var scanMs = 0L
        /** Every character marked in this app, in the order first seen. */
        val seen = LinkedHashMap<Int, Seen>()
    }

    private val apps = LinkedHashMap<String, App>()
    private var loaded = false

    /** Bumped on every change that shows (new app, new characters), so the screen redraws only then. */
    @Volatile var version = 0; private set
    private var savedVersion = 0
    private var savedAt = 0L

    /** From the app's text: the scan's box report and its characters. */
    @Synchronized
    fun record(pkg: String, blocks: Int, withBoxes: Int, scanMs: Long, cps: Collection<Int>) {
        if (blocks == 0) return
        val a = app(pkg)
        a.blocks = blocks
        a.withBoxes = withBoxes
        a.scanMs = scanMs
        add(a, cps, picture = false)
    }

    /** Read from the app's pictures (OCR mode, Lens). */
    @Synchronized
    fun recordPicture(pkg: String, cps: Collection<Int>) {
        if (cps.isEmpty()) return
        add(app(pkg), cps, picture = true)
    }

    private fun app(pkg: String) = apps.getOrPut(pkg) { version++; App(pkg) }

    private fun add(a: App, cps: Collection<Int>, picture: Boolean) {
        val now = System.currentTimeMillis()
        a.lastSeen = now
        for (cp in cps) {
            val s = a.seen[cp]
            if (s == null) {
                if (a.seen.size < MAX_CHARS) {
                    a.seen[cp] = Seen(now, picture)
                    version++
                }
            } else if (s.picture && !picture) {
                s.picture = false
            }
        }
    }

    /** Copies, so the screen can read them while scans go on. */
    @Synchronized
    fun snapshot(): List<App> = apps.values.sortedByDescending { it.lastSeen }.map { a ->
        App(a.pkg).also {
            it.blocks = a.blocks; it.withBoxes = a.withBoxes
            it.lastSeen = a.lastSeen; it.scanMs = a.scanMs
            for ((cp, s) in a.seen) it.seen[cp] = Seen(s.first, s.picture)
        }
    }

    @Synchronized
    fun clear(context: Context) {
        apps.clear()
        version++
        File(context.filesDir, FILE).delete()
        savedVersion = version
    }

    /** Reads the saved history once per process. */
    @Synchronized
    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val f = File(context.filesDir, FILE)
        if (!f.isFile) return
        runCatching {
            f.forEachLine { line ->
                if (line.startsWith("#")) return@forEachLine
                val p = line.split('\t')
                if (p.size < 3) return@forEachLine
                val cp = p[1].takeIf { it.isNotEmpty() }?.codePointAt(0) ?: return@forEachLine
                val t = p[2].toLongOrNull() ?: return@forEachLine
                val a = apps.getOrPut(p[0]) { App(p[0]) }
                if (cp !in a.seen) a.seen[cp] = Seen(t, p.getOrNull(3) == "picture")
                if (t > a.lastSeen) a.lastSeen = t
            }
        }
        version++
        savedVersion = version
    }

    /** Saves when something changed and the last save is 10 s old (called often, e.g. every second). */
    @Synchronized
    fun saveSoon(context: Context) {
        val now = System.currentTimeMillis()
        if (version == savedVersion || now - savedAt < 10_000) return
        runCatching {
            val tmp = File(context.filesDir, "$FILE.tmp")
            tmp.writeText(text(millis = true))
            tmp.renameTo(File(context.filesDir, FILE))
            savedVersion = version
            savedAt = now
        }
    }

    /**
     * The whole history as a tab-separated table: app, character, first seen, where (text or picture).
     * [millis]: times as milliseconds (the saved file) or as readable local dates (the export).
     */
    @Synchronized
    fun text(millis: Boolean = false): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.ROOT)
        val b = StringBuilder()
        b.append("# 学: characters seen per app\n# app\tcharacter\tfirst seen\twhere\n")
        for (a in apps.values) for ((cp, s) in a.seen) {
            b.append(a.pkg).append('\t').appendCodePoint(cp).append('\t')
                .append(if (millis) s.first.toString() else fmt.format(Date(s.first))).append('\t')
                .append(if (s.picture) "picture" else "text").append('\n')
        }
        return b.toString()
    }
}
