package com.vibecoded.planner.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vibecoded.planner.BuildConfig
import com.vibecoded.planner.domain.AppState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

private const val SOURCE_ASSET = "source.zip"

@Composable
fun SettingsScreen(state: AppState, vm: PlannerViewModel, onClose: () -> Unit) {
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.readIcs(uri)
    }
    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/calendar")) { uri ->
        if (uri != null) writeText(context, uri, vm.exportIcs(), "Calendar exported")
    }
    val sourceLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) copySource(context, uri)
    }
    val notifPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onClose) { Text("← Back") }
            Text("Settings", style = MaterialTheme.typography.titleLarge)
        }

        Heading("Morning routine")
        Text("Show “pushed N times” after an item was pushed this many times:", fontSize = 14.sp)
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { vm.setThreshold(state.postponeThreshold - 1) }) { Text("−", fontSize = 20.sp) }
            Text(state.postponeThreshold.toString(), fontSize = 18.sp)
            TextButton(onClick = { vm.setThreshold(state.postponeThreshold + 1) }) { Text("+", fontSize = 20.sp) }
        }
        OutlinedButton(onClick = {
            clipboard.setText(AnnotatedString(vm.reasonsText()))
            Toast.makeText(context, "Reasons copied", Toast.LENGTH_SHORT).show()
        }) { Text("Copy “why not done” notes as markdown") }

        Heading("Calendars")
        Text(
            "Import an .ics file (what universities and Google Calendar export). " +
                "Re-importing a calendar with the same name lets you replace it.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row {
            OutlinedButton(onClick = { importLauncher.launch(arrayOf("*/*")) }) { Text("Import .ics") }
            Spacer(Modifier.padding(4.dp))
            OutlinedButton(onClick = { exportLauncher.launch("planner.ics") }) { Text("Export .ics") }
        }
        val fmt = DateTimeFormatter.ofPattern("d MMM yyyy")
        for (c in state.calendars) {
            val count = state.tasks.count { it.calendarId == c.id }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(c.name)
                    Text(
                        "$count events · imported " + Instant.ofEpochMilli(c.importedAt).atZone(ZoneId.systemDefault()).format(fmt),
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { vm.removeCalendar(c.id) }) { Text("Remove") }
            }
        }

        Heading("Make it reliable on Xiaomi")
        Text(
            "MIUI / HyperOS stops background apps aggressively. Without these the 05:30 cue, " +
                "the badge and the lock-screen block may be late or missing.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (Build.VERSION.SDK_INT >= 33) {
            SetupButton("1. Allow notifications") { notifPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
        SetupButton("2. Autostart: on") { openXiaomiAutostart(context) }
        SetupButton("3. Battery: no restrictions") { requestBatteryExemption(context) }
        SetupButton("4. Lock screen + icon badge") { openNotificationSettings(context) }
        Text(
            "In step 4 turn on “Lock screen notifications” and “App icon badges” for both channels.",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Heading("For future Claude")
        Text(
            "Saves this app's full source code plus HANDOFF.md (design decisions and the original request) " +
                "as one zip you can attach to a new chat.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = { sourceLauncher.launch("planner-source.zip") }) { Text("Save source code (.zip)") }

        Spacer(Modifier.height(24.dp))
        Text("Version ${BuildConfig.VERSION_NAME}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun Heading(text: String) {
    Spacer(Modifier.height(16.dp))
    HorizontalDivider()
    Text(text, fontWeight = FontWeight.Bold, fontSize = 16.sp, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
}

@Composable
private fun SetupButton(label: String, onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) { Text(label) }
}

private fun writeText(context: Context, uri: Uri, text: String, done: String) {
    try {
        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
        Toast.makeText(context, done, Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "Failed: ${e.message}", Toast.LENGTH_LONG).show()
    }
}

private fun copySource(context: Context, uri: Uri) {
    try {
        context.assets.open(SOURCE_ASSET).use { input ->
            context.contentResolver.openOutputStream(uri)?.use { input.copyTo(it) }
        }
        Toast.makeText(context, "Source saved", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        Toast.makeText(context, "This build has no bundled source (only CI builds include it).", Toast.LENGTH_LONG).show()
    }
}

private fun tryStart(context: Context, vararg intents: Intent) {
    for (intent in intents) {
        try {
            context.startActivity(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            return
        } catch (e: ActivityNotFoundException) {
            // try the next one
        } catch (e: SecurityException) {
            // try the next one
        }
    }
    Toast.makeText(context, "Couldn't open that screen; find it under Settings → Apps → Planner.", Toast.LENGTH_LONG).show()
}

private fun appDetails(context: Context) =
    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + context.packageName))

private fun openXiaomiAutostart(context: Context) = tryStart(
    context,
    Intent().setComponent(ComponentName("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")),
    appDetails(context),
)

@SuppressLint("BatteryLife")
private fun requestBatteryExemption(context: Context) = tryStart(
    context,
    Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + context.packageName)),
    Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS),
    appDetails(context),
)

private fun openNotificationSettings(context: Context) = tryStart(
    context,
    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    appDetails(context),
)
