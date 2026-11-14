package lol.varie.xue

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Character frequency levels (ƒ) from wfreq.txt, built by the varie repo (h/build_freq.py).
 * Lines `word|times|level` (level 1 commonest … 5 rarest); a single character's ƒ is the line of
 * that character. Piece lines (`piece|times|level|p`) are skipped.
 * A downloaded copy in filesDir wins over the bundled one.
 */
object Freq {
    const val SOURCE_URL = "https://varie.lol/h/wfreq.txt"
    private const val FILE = "wfreq.txt"

    /** Level for a character missing from the file: it is never a word on its own, so treat it as rare. */
    const val MISSING = 5

    @Volatile private var levels: Map<Int, Int> = emptyMap()
    @Volatile var total: Long = 0; private set
    @Volatile var fromDownload = false; private set

    fun level(codePoint: Int): Int = levels[codePoint] ?: MISSING
    val loaded: Boolean get() = levels.isNotEmpty()
    val size: Int get() = levels.size

    @Synchronized
    fun load(context: Context) {
        val downloaded = File(context.filesDir, FILE)
        val text = if (downloaded.isFile) downloaded.readText() else null
        if (text != null && parse(text)) {
            fromDownload = true
            return
        }
        parse(context.assets.open(FILE).bufferedReader().use { it.readText() })
        fromDownload = false
    }

    private fun parse(text: String): Boolean {
        val map = HashMap<Int, Int>(16384)
        var tot = 0L
        text.lineSequence().forEach { line ->
            if (line.startsWith("#")) {
                if (line.startsWith("# total ")) tot = line.substring(8).trim().toLongOrNull() ?: 0
                return@forEach
            }
            val parts = line.split('|')
            if (parts.size != 3) return@forEach
            val word = parts[0]
            if (word.isEmpty() || word.codePointCount(0, word.length) != 1) return@forEach
            val lv = parts[2].trim().toIntOrNull() ?: return@forEach
            if (lv in 1..5) map[word.codePointAt(0)] = lv
        }
        if (map.size < 1000) return false
        levels = map
        total = tot
        return true
    }

    /** Downloads a fresh copy; returns null on success, else a short error. Blocking: call off the main thread. */
    fun download(context: Context): String? = try {
        val conn = URL(SOURCE_URL).openConnection() as HttpURLConnection
        conn.connectTimeout = 15000
        conn.readTimeout = 30000
        val code = conn.responseCode
        if (code != 200) {
            "HTTP $code"
        } else {
            val text = conn.inputStream.bufferedReader().use { it.readText() }
            if (!text.contains("# total")) {
                "not a frequency file"
            } else {
                val tmp = File(context.filesDir, "$FILE.tmp")
                tmp.writeText(text)
                tmp.renameTo(File(context.filesDir, FILE))
                load(context)
                null
            }
        }
    } catch (e: Exception) {
        e.message ?: e.javaClass.simpleName
    }
}
