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
    private val IN_LINK = Regex("(?:/list/|[#&?]pick=|paste\\.varie\\.lol/|paste\\.chatstats\\.wtf/)($CODE)(?:\\.txt)?(?![A-Za-z0-9])")
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

    /** Level 0..5 if the character is yours, else -1. */
    fun level(codePoint: Int): Int = levels[codePoint] ?: -1

    /** A code from what the user typed: the code itself, or a link with it (…/h/list/CODE.txt, …/h/#pick=CODE). */
    fun extractCode(input: String): String? {
        val s = input.trim()
        if (WHOLE.matches(s)) return s
        return IN_LINK.find(s)?.groupValues?.get(1)
    }

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
        var nWords = 0
        // Every character of a listed word is yours; New until the results say otherwise.
        list?.lineSequence()?.forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val p = line.split('|').map { it.trim() }
            val word = if (p[0].matches(Regex("^\\d{4}-\\d{2}-\\d{2}.*"))) p.getOrNull(1) else p[0]
            if (word.isNullOrEmpty()) return@forEach
            nWords++
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
            // Levels are per character; results kept for whole words don't colour single characters.
            if (item.isEmpty() || item.codePointCount(0, item.length) != 1) return@forEach
            val h = p.getOrNull(3).orEmpty().filter { it == '0' || it == '1' }
            val lv = if (h.isEmpty()) 0 else {
                val wrong = h.count { it == '0' }.toDouble() / h.length
                minOf(5, 1 + Math.floor(wrong * 5 + 1e-9).toInt())
            }
            if (h.isNotEmpty()) nAnswered++
            map[item.codePointAt(0)] = lv
        }
        levels = map
        words = nWords
        answered = nAnswered
    }
}
