package lol.varie.xue

/**
 * What 学 has seen per app: whether it gives per-character boxes, and every character marked in it (from its
 * text; OCR'd characters aren't counted here). Kept in memory while the service runs; the settings screen shows it.
 */
object Stats {
    /** Distinct characters kept per app, at most. */
    private const val MAX_CHARS = 5000

    class App(val pkg: String) {
        /** Text blocks with Chinese seen in the last scan of this app. */
        var blocks = 0
        /** Of those, how many gave character boxes. */
        var withBoxes = 0
        var chars = 0
        var lastSeen = 0L
        var scanMs = 0L
        /** Every character marked in this app, in the order first seen. */
        val seen = LinkedHashSet<Int>()
    }

    private val apps = LinkedHashMap<String, App>()

    /** Bumped on every change that shows (new app, new characters), so the screen redraws only then. */
    @Volatile var version = 0; private set

    @Synchronized
    fun record(pkg: String, blocks: Int, withBoxes: Int, chars: Int, scanMs: Long, cps: Collection<Int>) {
        if (blocks == 0) return
        val a = apps.getOrPut(pkg) { version++; App(pkg) }
        a.blocks = blocks
        a.withBoxes = withBoxes
        a.chars = chars
        a.scanMs = scanMs
        a.lastSeen = System.currentTimeMillis()
        for (cp in cps) if (a.seen.size < MAX_CHARS && a.seen.add(cp)) version++
    }

    /** Copies, so the screen can read them while scans go on. */
    @Synchronized
    fun snapshot(): List<App> = apps.values.sortedByDescending { it.lastSeen }.map { a ->
        App(a.pkg).also {
            it.blocks = a.blocks; it.withBoxes = a.withBoxes; it.chars = a.chars
            it.lastSeen = a.lastSeen; it.scanMs = a.scanMs; it.seen.addAll(a.seen)
        }
    }

    @Synchronized
    fun clear() {
        apps.clear()
        version++
    }
}
