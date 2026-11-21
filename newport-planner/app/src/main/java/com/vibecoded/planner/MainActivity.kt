package com.vibecoded.planner

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import com.vibecoded.planner.data.PlannerRepository
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import com.vibecoded.planner.system.Notifier
import com.vibecoded.planner.ui.AppRoot
import com.vibecoded.planner.ui.PlannerTheme
import com.vibecoded.planner.ui.PlannerViewModel

class MainActivity : ComponentActivity() {

    private val vm: PlannerViewModel by viewModels()
    private var scheduleRequest by mutableIntStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        Notifier.createChannels(this)
        handleIntent(intent)
        setContent {
            PlannerTheme {
                AppRoot(vm, scheduleRequest)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        vm.tick()
        syncVault()
    }

    /** Vault copy: restore after a reinstall, refresh it, or ask once for "All files access". */
    private fun syncVault() {
        val repo = PlannerRepository.get(this)
        if (Environment.isExternalStorageManager()) {
            if (repo.restoreFromVaultIfEmpty()) {
                Toast.makeText(this, "Restored your planner from the vault backup", Toast.LENGTH_LONG).show()
            }
            repo.mirrorNow()
            return
        }
        val prefs = getSharedPreferences("planner", MODE_PRIVATE)
        if (prefs.getBoolean(PREF_ASKED_VAULT, false)) return
        prefs.edit().putBoolean(PREF_ASKED_VAULT, true).apply()
        openVaultAccess(this)
    }

    /** Opens the schedule list from the morning notification, or imports a tapped .ics file. */
    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(EXTRA_OPEN_SCHEDULE, false)) scheduleRequest++
        if (intent.action == Intent.ACTION_VIEW) intent.data?.let { vm.readIcs(it) }
    }

    companion object {
        const val EXTRA_OPEN_SCHEDULE = "open_schedule"
        private const val PREF_ASKED_VAULT = "asked_vault_access"

        fun openVaultAccess(activity: android.content.Context) {
            Toast.makeText(activity, "Allow all files access so the planner is saved into your vault", Toast.LENGTH_LONG).show()
            val pkg = Uri.parse("package:" + activity.packageName)
            try {
                activity.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, pkg).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        }
    }
}
