package com.vibecoded.mealcam

import android.Manifest
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.OrientationEventListener
import android.view.Surface
import android.view.View
import android.view.WindowInsets
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import java.io.File
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

/**
 * The whole capture flow on one screen. The overlay buttons are drawn on the first frame; the
 * camera binds afterwards, so the water button works before the camera is ready.
 */
class MainActivity : ComponentActivity() {
    private lateinit var state: MealState
    private lateinit var previewView: PreviewView
    private lateinit var shot: ImageView
    private lateinit var review: View
    private lateinit var waterBtn: TextView
    private lateinit var mealBtn: TextView
    private lateinit var noteLabel: TextView
    private lateinit var shutter: View
    private lateinit var flash: View

    private var capture: ImageCapture? = null
    /** Photo taken but not yet confirmed. It is committed on V or when the app stops. */
    private var pending: File? = null
    private var shooting = false
    private var cachedNote = ""
    private var glow: ObjectAnimator? = null
    private val ui = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { refreshMeal(); ui.postDelayed(this, 1000) }
    }
    private lateinit var orientation: OrientationEventListener

    private val pendingDir by lazy { File(filesDir, "pending").apply { mkdirs() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        state = MealState(this)
        previewView = findViewById(R.id.preview)
        shot = findViewById(R.id.shot)
        review = findViewById(R.id.review)
        waterBtn = findViewById(R.id.water)
        mealBtn = findViewById(R.id.meal)
        noteLabel = findViewById(R.id.note)
        shutter = findViewById(R.id.shutter)
        flash = findViewById(R.id.flash)

        findViewById<View>(R.id.overlay).setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        waterBtn.setOnClickListener { logWater(500) }
        waterBtn.setOnLongClickListener { askWater(); true }
        shutter.setOnClickListener { takePhoto() }
        findViewById<View>(R.id.keep).setOnClickListener { commitPending() }
        findViewById<View>(R.id.discard).setOnClickListener { discardPending() }
        mealBtn.setOnClickListener { if (state.isOpen()) editNote() else resumeMeal() }
        noteLabel.setOnClickListener { editNote() }
        mealBtn.setOnLongClickListener { startActivity(Intent(this, FineTuneActivity::class.java)); true }

        orientation = object : OrientationEventListener(this) {
            override fun onOrientationChanged(deg: Int) {
                if (deg == ORIENTATION_UNKNOWN) return
                capture?.targetRotation = when (deg) {
                    in 45 until 135 -> Surface.ROTATION_270
                    in 135 until 225 -> Surface.ROTATION_180
                    in 225 until 315 -> Surface.ROTATION_90
                    else -> Surface.ROTATION_0
                }
            }
        }

        // Photos left over from a killed process (rare) are kept, not lost.
        pendingDir.listFiles()?.forEach { commitFile(it) }

        // Bind the camera only after the first frame is on screen.
        previewView.post { if (hasCamera()) bindCamera() else requestPermissions(arrayOf(Manifest.permission.CAMERA), 1) }
        if (!Environment.isExternalStorageManager()) askStorage()
    }

    override fun onStart() {
        super.onStart()
        orientation.enable()
        ui.post(tick)
        MealState.io.execute { // the note may have changed in fine-tune or Obsidian
            val n = runCatching { state.mealId?.let { MealState.vault.readMeal(it) }?.note }.getOrNull() ?: ""
            runOnUiThread { cachedNote = n; refreshMeal() }
        }
    }

    /** Leaving the app, locking the screen, or turning it off all land here: keep the photo. */
    override fun onStop() {
        super.onStop()
        if (pending != null) commitPending()
        orientation.disable()
        ui.removeCallbacks(tick)
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, results: IntArray) {
        super.onRequestPermissionsResult(code, perms, results)
        if (hasCamera()) bindCamera()
    }

    private fun hasCamera() = checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun askStorage() {
        Toast.makeText(this, "MealCam needs \"All files access\" to save into the vault", Toast.LENGTH_LONG).show()
        startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun bindCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewView.surfaceProvider)
            // ~2048 px on the long edge at JPEG quality 82: about 300-500 KB, labels stay readable.
            val size = ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy(android.util.Size(2048, 1536),
                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER))
                .build()
            val ic = ImageCapture.Builder()
                .setResolutionSelector(size)
                .setJpegQuality(82)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, ic)
            capture = ic
        }, mainExecutor)
    }

    // ---------- photos ----------

    private fun takePhoto() {
        val ic = capture ?: return
        if (shooting || pending != null) return
        shooting = true
        val file = File(pendingDir, "pending_${System.currentTimeMillis()}.jpg")
        flash.alpha = 0.7f
        flash.visibility = View.VISIBLE
        flash.animate().alpha(0f).setDuration(200).withEndAction { flash.visibility = View.GONE }
        ic.takePicture(ImageCapture.OutputFileOptions.Builder(file).build(), mainExecutor,
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                    shooting = false
                    pending = file
                    showReview(file)
                    // If the screen went off while the photo was being written, keep it now.
                    if (!lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) commitPending()
                }
                override fun onError(e: ImageCaptureException) {
                    shooting = false
                    Toast.makeText(this@MainActivity, "Photo failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            })
    }

    private fun showReview(file: File) {
        shot.setImageDrawable(null)
        shot.visibility = View.VISIBLE
        review.visibility = View.VISIBLE
        shutter.visibility = View.GONE
        MealState.io.execute {
            val bmp = runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { d, _, _ -> d.setTargetSampleSize(2) }
            }.getOrNull()
            runOnUiThread { if (pending == file) shot.setImageBitmap(bmp) }
        }
    }

    private fun hideReview() {
        shot.visibility = View.GONE
        shot.setImageDrawable(null)
        review.visibility = View.GONE
        shutter.visibility = View.VISIBLE
    }

    private fun commitPending() {
        val f = pending ?: return
        pending = null
        hideReview()
        commitFile(f)
    }

    private fun discardPending() {
        val f = pending ?: return
        pending = null
        hideReview()
        f.delete() // only ever in app storage, never in the vault
    }

    /** Moves a photo into the vault, joining the open meal or starting a new one. */
    private fun commitFile(f: File) {
        val takenMs = f.name.removePrefix("pending_").removeSuffix(".jpg").toLongOrNull() ?: f.lastModified()
        val taken = LocalDateTime.ofInstant(Instant.ofEpochMilli(takenMs), ZoneId.systemDefault())
        // Decided on the io thread, after any earlier photo has been saved, so quick shots group.
        MealState.io.execute {
            if (!Environment.isExternalStorageManager()) return@execute // stays pending, retried next launch
            val joinId = state.mealId.takeIf { state.isOpen(takenMs) }
            val id = runCatching { MealState.vault.addPhoto(f, taken, joinId) }
                .onFailure { e -> runOnUiThread { Toast.makeText(this, "Save failed: ${e.message}", Toast.LENGTH_LONG).show() } }
                .getOrNull() ?: return@execute
            if (id != joinId) state.lastActivity = 0
            state.mealId = id
            state.touch(takenMs)
            val note = MealState.vault.readMeal(id)?.note ?: ""
            runOnUiThread { cachedNote = note; refreshMeal() }
        }
    }

    // ---------- meal icon ----------

    private fun refreshMeal() {
        val open = state.isOpen()
        if (open) {
            if (glow == null) {
                mealBtn.alpha = 1f
                glow = ObjectAnimator.ofFloat(mealBtn, View.ALPHA, 1f, 0.45f).apply {
                    duration = 900; repeatMode = ValueAnimator.REVERSE; repeatCount = ValueAnimator.INFINITE; start()
                }
                mealBtn.setBackgroundResource(R.drawable.glow_bg)
            }
            noteLabel.visibility = View.VISIBLE
            noteLabel.text = cachedNote.ifBlank { "+ note" }
        } else {
            glow?.cancel(); glow = null
            mealBtn.setBackgroundResource(R.drawable.round_bg)
            mealBtn.alpha = 0.4f
            noteLabel.visibility = View.GONE
        }
    }

    private fun editNote() {
        val id = state.mealId ?: return
        if (!state.isOpen()) return
        state.touch()
        val input = EditText(this).apply {
            setText(cachedNote)
            setSelection(text.length)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            hint = "Note for this meal"
        }
        fun save() {
            val text = input.text.toString().trim()
            cachedNote = text
            MealState.io.execute { MealState.vault.setNote(id, text) }
        }
        AlertDialog.Builder(this)
            .setView(input)
            .setPositiveButton("Save") { _, _ -> save(); state.touch(); refreshMeal() }
            .setNeutralButton("End meal") { _, _ -> save(); state.ended = true; refreshMeal() }
            .setNegativeButton("Cancel", null)
            .show()
            .window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        input.requestFocus()
    }

    /** Dim icon tapped: the next photo joins the last meal. */
    private fun resumeMeal() {
        MealState.io.execute {
            val id = state.mealId?.takeIf { MealState.vault.mealFile(it).exists() }
                ?: MealState.vault.mealIds().firstOrNull()
            val m = id?.let { MealState.vault.readMeal(it) }
            runOnUiThread {
                if (m == null) { Toast.makeText(this, "No meal yet", Toast.LENGTH_SHORT).show(); return@runOnUiThread }
                state.mealId = m.id
                state.ended = false
                state.lastActivity = System.currentTimeMillis()
                cachedNote = m.note
                Toast.makeText(this, "Next photo joins the ${m.time.substringAfter('T').take(5)} meal", Toast.LENGTH_SHORT).show()
                refreshMeal()
            }
        }
    }

    // ---------- water ----------

    private fun logWater(ml: Int) {
        waterBtn.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        val now = LocalDateTime.now()
        MealState.io.execute {
            val total = runCatching { MealState.vault.addWater(now, ml) }.getOrElse { e ->
                runOnUiThread { Toast.makeText(this, "Water not saved: ${e.message}", Toast.LENGTH_LONG).show() }
                return@execute
            }
            runOnUiThread { flashWater("+$ml\n${String.format(Locale.US, "%.1f", total / 1000.0)} L") }
        }
    }

    private fun flashWater(text: String) {
        waterBtn.text = text
        ui.removeCallbacks(resetWater)
        ui.postDelayed(resetWater, 2000)
    }

    private val resetWater = Runnable { waterBtn.text = getString(R.string.water) }

    private fun askWater() {
        val input = EditText(this).apply { inputType = InputType.TYPE_CLASS_NUMBER; hint = "ml" }
        AlertDialog.Builder(this)
            .setTitle("Water (ml)")
            .setView(input)
            .setPositiveButton("Add") { _, _ -> input.text.toString().toIntOrNull()?.takeIf { it > 0 }?.let { logWater(it) } }
            .setNegativeButton("Cancel", null)
            .show()
            .window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        input.requestFocus()
    }
}
