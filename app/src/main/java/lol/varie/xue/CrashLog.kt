package lol.varie.xue

import android.app.Application
import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * What went wrong, kept for the settings screen ("Problems"): a crash that stopped 学 (Android then shows the
 * service as "Not working"), and failures of a single part that 学 survived by switching that part off.
 * The latest few, newest first, in files/problems.txt. Nothing is sent anywhere.
 */
object CrashLog {
    private const val FILE = "problems.txt"
    private const val KEEP = 20_000

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, e ->
            runCatching { write(app, "Crash (on ${thread.name})", e) }
            previous?.uncaughtException(thread, e)
        }
    }

    /** A part of 学 failed and was switched off; the rest goes on. */
    fun note(context: Context, what: String, e: Throwable) {
        runCatching { write(context, what, e) }
    }

    @Synchronized
    private fun write(context: Context, what: String, e: Throwable) {
        val f = File(context.filesDir, FILE)
        val version = runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull()
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(Date())
        val entry = "$time · v$version · $what\n${e.stackTraceToString().lines().take(30).joinToString("\n")}\n\n"
        val old = if (f.isFile) f.readText() else ""
        f.writeText((entry + old).take(KEEP))
    }

    fun read(context: Context): String? = File(context.filesDir, FILE).takeIf { it.isFile }?.readText()?.ifBlank { null }

    fun clear(context: Context) {
        File(context.filesDir, FILE).delete()
    }
}

/** The app's process: installs the crash log before anything else runs. */
class XueApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
    }
}
