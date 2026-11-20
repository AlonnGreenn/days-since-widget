package com.vibecoded.mealcam

import android.content.Context
import android.os.Environment
import java.io.File
import java.util.concurrent.Executors

/** Which meal is open. A meal stays open while photos (or note edits) come less than 5 min apart. */
class MealState(ctx: Context) {
    private val p = ctx.getSharedPreferences("meal", Context.MODE_PRIVATE)

    var mealId: String?
        get() = p.getString("id", null)
        set(v) = p.edit().putString("id", v).apply()
    var lastActivity: Long
        get() = p.getLong("last", 0)
        set(v) = p.edit().putLong("last", v).apply()
    var ended: Boolean
        get() = p.getBoolean("ended", false)
        set(v) = p.edit().putBoolean("ended", v).apply()

    fun isOpen(now: Long = System.currentTimeMillis()): Boolean {
        val id = mealId ?: return false
        return !ended && now - lastActivity < WINDOW_MS && vault.mealFile(id).exists()
    }

    /** Restarts the 5-minute window. */
    fun touch(at: Long = System.currentTimeMillis()) {
        lastActivity = maxOf(lastActivity, at)
        ended = false
    }

    companion object {
        const val WINDOW_MS = 5 * 60 * 1000L
        val vault = Vault(File(Environment.getExternalStorageDirectory(), "vault"))
        /** All vault file work runs here, one job at a time. */
        val io = Executors.newSingleThreadExecutor()
    }
}
