package lol.varie.xue

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What 学 has seen per app: whether its text gives per-character boxes, and the history of the Chinese text marked
 * in it, as it was on screen: each text block, line, subtitle or sentence in one piece, with when it was first seen
 * and whether it was text or read from a picture (OCR, Lens). The same text seen again isn't added again.
 * Entries less than [GROUP_MS] apart in an app form one group (what was on screen together, a reading session).
 * Kept on the phone (files/history.tsv) and exportable from the settings screen.
 */
object Stats {
    /** Entries kept per app, at most (the oldest go first). */
    private const val MAX_ENTRIES = 5000
    /** Longest text kept for one entry (a whole article in one block is cut). */
    private const val MAX_TEXT = 400
    /** A pause longer than this in an app starts a new group. */
    const val GROUP_MS = 2 * 60 * 1000L
    private const val FILE = "history.tsv"

    class Entry(val time: Long, val text: String, val picture: Boolean)

    class App(val pkg: String) {
        /** Text blocks with Chinese seen in the last scan of this app. */
        var blocks = 0
        /** Of those, how many gave character boxes. */
        var withBoxes = 0
        var lastSeen = 0L
        /** Oldest first. */
        val entries = ArrayList<Entry>()
        val known = HashSet<String>()

        /** Entries in groups (a pause over [GROUP_MS] starts a new one), oldest first. */
        fun groups(): List<List<Entry>> {
            val out = ArrayList<ArrayList<Entry>>()
            for (e in entries) {
                val g = out.lastOrNull()
                if (g != null && e.time - g.last().time <= GROUP_MS) g.add(e) else out.add(arrayListOf(e))
            }
            return out
        }

        /** Different Chinese characters in all its entries. */
        fun distinct(): Int = entries.flatMap { e -> e.text.codePoints().toArray().filter { isHan(it) } }.toSet().size
    }

    private val apps = LinkedHashMap<String, App>()
    private var loaded = false

    /** Bumped on every change that shows, so the screen redraws only then. */
    @Volatile var version = 0; private set
    private var savedVersion = 0
    private var savedAt = 0L

    fun isHan(cp: Int) = Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN

    /** One line of text as kept: spaces collapsed, no tabs or line breaks, cut to [MAX_TEXT]. */
    fun clean(t: String): String = t.replace(Regex("\\s+"), " ").trim().take(MAX_TEXT)

    /** From the app's text: the scan's box report and the text blocks marked. */
    @Synchronized
    fun record(pkg: String, blocks: Int, withBoxes: Int, texts: Collection<String>) {
        if (blocks == 0) return
        val a = app(pkg)
        a.blocks = blocks
        a.withBoxes = withBoxes
        add(a, texts, picture = false)
    }

    /** Read from the app's pictures (OCR mode, Lens): the lines with Chinese. */
    @Synchronized
    fun recordPicture(pkg: String, lines: Collection<String>) {
        if (lines.isEmpty()) return
        add(app(pkg), lines, picture = true)
    }

    private fun app(pkg: String) = apps.getOrPut(pkg) { version++; App(pkg) }

    private fun add(a: App, texts: Collection<String>, picture: Boolean) {
        val now = System.currentTimeMillis()
        a.lastSeen = now
        for (raw in texts) {
            val t = clean(raw)
            if (t.isEmpty() || !t.codePoints().anyMatch { isHan(it) } || !a.known.add(t)) continue
            a.entries.add(Entry(now, t, picture))
            if (a.entries.size > MAX_ENTRIES) a.known.remove(a.entries.removeAt(0).text)
            version++
        }
    }

    /** Copies, so the screen can read them while scans go on. */
    @Synchronized
    fun snapshot(): List<App> = apps.values.sortedByDescending { it.lastSeen }.map { a ->
        App(a.pkg).also {
            it.blocks = a.blocks; it.withBoxes = a.withBoxes; it.lastSeen = a.lastSeen
            it.entries.addAll(a.entries)
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
        File(context.filesDir, "seen.tsv").delete() // 0.1.32's characters-only history: no order, nothing to group
        val f = File(context.filesDir, FILE)
        if (!f.isFile) return
        runCatching {
            f.forEachLine { line ->
                if (line.startsWith("#")) return@forEachLine
                val p = line.split('\t')
                if (p.size < 4) return@forEachLine
                val t = p[1].toLongOrNull() ?: return@forEachLine
                val a = apps.getOrPut(p[0]) { App(p[0]) }
                if (a.known.add(p[3])) a.entries.add(Entry(t, p[3], p[2] == "picture"))
                if (t > a.lastSeen) a.lastSeen = t
            }
            for (a in apps.values) a.entries.sortBy { it.time }
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
            val b = StringBuilder("# 学 history\n# app\ttime (ms)\twhere\ttext\n")
            for (a in apps.values) for (e in a.entries) {
                b.append(a.pkg).append('\t').append(e.time).append('\t')
                    .append(if (e.picture) "picture" else "text").append('\t').append(e.text).append('\n')
            }
            val tmp = File(context.filesDir, "$FILE.tmp")
            tmp.writeText(b.toString())
            tmp.renameTo(File(context.filesDir, FILE))
            savedVersion = version
            savedAt = now
        }
    }

    /**
     * The export: a tab-separated table, app by app, oldest first, with a blank line between groups.
     * Columns: app, group (when it started), time, where (text or picture), text.
     */
    @Synchronized
    fun export(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT)
        val b = StringBuilder()
        b.append("app\tgroup\ttime\twhere\ttext\n")
        for (a in apps.values) for (g in a.groups()) {
            val start = fmt.format(Date(g.first().time))
            for (e in g) {
                b.append(a.pkg).append('\t').append(start).append('\t').append(fmt.format(Date(e.time))).append('\t')
                    .append(if (e.picture) "picture" else "text").append('\t').append(e.text).append('\n')
            }
            b.append('\n')
        }
        return b.toString()
    }
}
