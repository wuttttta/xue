package lol.varie.xue

/**
 * Which apps give per-character boxes, for the first test round (step 1 of the plan).
 * Kept in memory; the settings screen shows it.
 */
object Stats {
    class App(val pkg: String) {
        /** Text blocks with Chinese seen in the last scan of this app. */
        var blocks = 0
        /** Of those, how many gave character boxes. */
        var withBoxes = 0
        var chars = 0
        var lastSeen = 0L
        var scanMs = 0L
    }

    private val apps = LinkedHashMap<String, App>()

    @Synchronized
    fun record(pkg: String, blocks: Int, withBoxes: Int, chars: Int, scanMs: Long) {
        if (blocks == 0) return
        val a = apps.getOrPut(pkg) { App(pkg) }
        a.blocks = blocks
        a.withBoxes = withBoxes
        a.chars = chars
        a.scanMs = scanMs
        a.lastSeen = System.currentTimeMillis()
    }

    @Synchronized
    fun snapshot(): List<App> = apps.values.sortedByDescending { it.lastSeen }

    @Synchronized
    fun clear() = apps.clear()
}
