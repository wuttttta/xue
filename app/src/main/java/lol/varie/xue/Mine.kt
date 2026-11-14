package lol.varie.xue

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The user's own characters: their word list and quiz results, read from pastebox (paste.varie.lol).
 *
 * - List paste (the quiz's own list, h.sync.v1's code): lines `added | word | meaning` after `#` lines;
 *   a `# stats: <code>` header points at the results paste.
 * - Results paste: lines `item | right | wrong | last answers` (1 right, 0 wrong), from the quiz's h.stats.v1.
 *
 * Level as in the quiz: no answers → New (0); else min(5, 1 + floor(share wrong × 5)).
 * Reading needs only the code; the app never writes. Copies are kept in filesDir for offline use.
 */
object Mine {
    const val PASTE = "https://paste.varie.lol"
    private const val CODE = "(?:[A-Z0-9]{8}|[A-Za-z0-9]{10})"
    private val WHOLE = Regex("^$CODE$")
    private val IN_LINK = Regex("(?:/list/|[#&?](?:pick|sync)=|paste\\.varie\\.lol/|paste\\.chatstats\\.wtf/)($CODE)(?:\\.txt)?(?![A-Za-z0-9])")
    private val STATS_HEADER = Regex("^#\\s*stats:\\s*($CODE)\\s*$")
    private const val LIST_FILE = "list.txt"
    private const val STATS_FILE = "stats.txt"
    const val REFRESH_MS = 15 * 60 * 1000L

    /** Character → level 0..5 (0 New) for characters in the list or the results. */
    @Volatile private var levels: Map<Int, Int> = emptyMap()
    @Volatile var words = 0; private set
    @Volatile var answered = 0; private set
    @Volatile var statsCode: String? = null; private set
    @Volatile var syncedAt = 0L; private set
    @Volatile var error: String? = null; private set

    /** A word of your list: when it was added (as the list file writes it) and its meaning. */
    class Entry(val word: String, val added: String, val meaning: String)

    /** A result line: right and wrong answers in all, and the last answers (1 right, 0 wrong, newest last). */
    class Stat(val right: Int, val wrong: Int, val last: String) {
        val level: Int get() = if (last.isEmpty()) 0 else {
            val w = last.count { it == '0' }.toDouble() / last.length
            minOf(5, 1 + Math.floor(w * 5 + 1e-9).toInt())
        }
        val wrongShare: Double get() = if (last.isEmpty()) 0.0 else last.count { it == '0' }.toDouble() / last.length
    }

    /** What the panel shows about a character: its own results, and the list words it is in. */
    class Info(val level: Int, val stat: Stat?, val words: List<Entry>)

    @Volatile private var entries: List<Entry> = emptyList()
    @Volatile private var stats: Map<String, Stat> = emptyMap()

    /** Results of a list item (character or word), if any. */
    fun stat(item: String): Stat? = stats[item]

    /** Your data for a character, or null if it isn't yours. */
    fun info(ch: String): Info? {
        if (ch.isEmpty()) return null
        val lv = level(ch.codePointAt(0))
        if (lv < 0) return null
        // The word that is the character itself first, then the longer words containing it.
        val ws = entries.filter { it.word.contains(ch) }.sortedBy { if (it.word == ch) 0 else 1 }
        return Info(lv, stats[ch], ws)
    }

    /** Level 0..5 if the character is yours, else -1. */
    fun level(codePoint: Int): Int = levels[codePoint] ?: -1

    /** A code from what the user typed: the code itself, or a link with it (…/h/list/CODE.txt, …/h/#pick=CODE, …/h/#sync=CODE.KEY). */
    fun extractCode(input: String): String? {
        val s = input.trim()
        if (WHOLE.matches(s)) return s
        return IN_LINK.find(s)?.groupValues?.get(1)
    }

    private val SYNC_LINK = Regex("#sync=($CODE\\.[0-9a-f]{64})(?![0-9a-f])")

    /** `CODE.KEY` from the quiz's "Edit list" link, which signs a browser in to the list; null for other input. */
    fun extractSync(input: String): String? = SYNC_LINK.find(input.trim())?.groupValues?.get(1)

    fun code(context: Context): String? = Prefs.get(context).getString(Prefs.LIST_CODE, null)

    /** Reads the saved copies (no network). */
    @Synchronized
    fun load(context: Context) {
        val list = File(context.filesDir, LIST_FILE).takeIf { it.isFile }?.readText()
        val stats = File(context.filesDir, STATS_FILE).takeIf { it.isFile }?.readText()
        syncedAt = Prefs.get(context).getLong(Prefs.SYNCED_AT, 0)
        parse(list, stats)
    }

    fun stale(): Boolean = System.currentTimeMillis() - syncedAt > REFRESH_MS

    /** Downloads list and results. Blocking: call off the main thread. Returns null or a short error. */
    @Synchronized
    fun sync(context: Context): String? {
        val code = code(context)
        if (code == null) {
            clear(context)
            return null
        }
        val err = try {
            val list = get("$PASTE/$code.txt") ?: throw Problem("list $code not found")
            val sc = list.lineSequence().mapNotNull { STATS_HEADER.find(it.trim())?.groupValues?.get(1) }.firstOrNull()
            val stats = if (sc != null) get("$PASTE/$sc.txt") else null
            File(context.filesDir, LIST_FILE).writeText(list)
            val sf = File(context.filesDir, STATS_FILE)
            if (stats != null) sf.writeText(stats) else sf.delete()
            syncedAt = System.currentTimeMillis()
            Prefs.get(context).edit().putLong(Prefs.SYNCED_AT, syncedAt).apply()
            parse(list, stats)
            null
        } catch (e: Problem) {
            e.message
        } catch (e: Exception) {
            "offline (${e.message ?: e.javaClass.simpleName})"
        }
        error = err
        return err
    }

    @Synchronized
    fun clear(context: Context) {
        File(context.filesDir, LIST_FILE).delete()
        File(context.filesDir, STATS_FILE).delete()
        Prefs.get(context).edit().remove(Prefs.SYNCED_AT).apply()
        syncedAt = 0
        error = null
        parse(null, null)
    }

    private class Problem(msg: String) : Exception(msg)

    private fun get(url: String): String? {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        conn.useCaches = false
        return when (val code = conn.responseCode) {
            200 -> conn.inputStream.bufferedReader().use { it.readText() }
            404 -> null
            else -> throw Problem("HTTP $code")
        }
    }

    private fun parse(list: String?, stats: String?) {
        val map = HashMap<Int, Int>()
        val es = ArrayList<Entry>()
        val st = HashMap<String, Stat>()
        var nWords = 0
        // Every character of a listed word is yours; New until the results say otherwise.
        list?.lineSequence()?.forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val p = line.split('|').map { it.trim() }
            val dated = p[0].matches(Regex("^\\d{4}-\\d{2}-\\d{2}.*"))
            val word = if (dated) p.getOrNull(1) else p[0]
            if (word.isNullOrEmpty()) return@forEach
            nWords++
            es.add(Entry(word, if (dated) p[0] else "", p.getOrNull(2).orEmpty()))
            word.codePoints().forEach { cp ->
                if (Character.UnicodeScript.of(cp) == Character.UnicodeScript.HAN) map.putIfAbsent(cp, 0)
            }
        }
        var nAnswered = 0
        statsCode = list?.lineSequence()?.mapNotNull { STATS_HEADER.find(it.trim())?.groupValues?.get(1) }?.firstOrNull()
        stats?.lineSequence()?.forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val p = line.split('|').map { it.trim() }
            val item = p[0]
            if (item.isEmpty()) return@forEach
            val h = p.getOrNull(3).orEmpty().filter { it == '0' || it == '1' }
            val s = Stat(p.getOrNull(1)?.toIntOrNull() ?: 0, p.getOrNull(2)?.toIntOrNull() ?: 0, h)
            st[item] = s
            // Levels are per character; results kept for whole words don't colour single characters
            // (the panel shows them with the word).
            if (item.codePointCount(0, item.length) != 1) return@forEach
            if (h.isNotEmpty()) nAnswered++
            map[item.codePointAt(0)] = s.level
        }
        levels = map
        entries = es
        this.stats = st
        words = nWords
        answered = nAnswered
    }
}
