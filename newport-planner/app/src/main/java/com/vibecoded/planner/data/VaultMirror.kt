package com.vibecoded.planner.data

import android.os.Environment
import android.util.Log
import com.vibecoded.planner.domain.AppState
import com.vibecoded.planner.domain.vaultFiles
import java.io.File
import java.time.LocalDate
import java.time.ZoneId

/**
 * Keeps `<vault>/planner/` in step with the app (one-way: app -> vault).
 *  - `state.json`   exact copy of the app's own data file = the backup a reinstall restores from
 *  - `Planner.md`, `days/YYYY-MM-DD.md`, `calendar.ics`   readable views for Obsidian / Claude
 * Needs "All files access" (like MealCam). Files are only rewritten when their content changed.
 */
object VaultMirror {
    private const val TAG = "VaultMirror"

    val vaultDir: File get() = File(Environment.getExternalStorageDirectory(), "vault")
    private val dir: File get() = File(vaultDir, "planner")
    val backupFile: File get() = File(dir, "state.json")

    fun canWrite(): Boolean = Environment.isExternalStorageManager() && vaultDir.isDirectory

    /** The backup in the vault, if there is one and it holds anything. */
    fun readBackup(): AppState? = try {
        if (!canWrite() || !backupFile.isFile) null
        else StateCodec.decode(backupFile.readText()).takeIf { !it.isPristine() }
    } catch (e: Exception) {
        Log.e(TAG, "Vault backup unreadable", e)
        null
    }

    fun write(state: AppState, json: String, today: LocalDate) {
        if (!canWrite()) return
        try {
            dir.mkdirs()
            // Never let an empty state (fresh install, lost data) overwrite a real backup.
            if (!(state.isPristine() && readBackup() != null)) writeIfChanged(backupFile, json)
            val daysDir = File(dir, "days")
            val existing = daysDir.listFiles()
                ?.mapNotNull { f -> f.name.removeSuffix(".md").takeIf { f.name.endsWith(".md") } }
                ?.mapNotNull { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?.toSet() ?: emptySet()
            for ((path, text) in vaultFiles(state, today, ZoneId.systemDefault(), existing)) {
                writeIfChanged(File(dir, path), text)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Writing to the vault failed", e)
        }
    }

    private fun writeIfChanged(f: File, text: String) {
        if (f.isFile && f.readText() == text) return
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "." + f.name + ".tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(f)) {
            f.writeText(text)
            tmp.delete()
        }
    }
}

/** True for a state that holds nothing the user made. */
fun AppState.isPristine(): Boolean =
    tasks.isEmpty() && series.isEmpty() && calendars.isEmpty() && reviewedDays.isEmpty()
