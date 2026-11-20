package com.vibecoded.mealcam

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File
import java.time.LocalDate

/** Fixing mistakes: the last 2 meals (main/spec, note, delete) and today's water. Rarely used. */
class FineTuneActivity : Activity() {
    private val vault get() = MealState.vault
    private lateinit var list: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(16), dp(16), dp(16)) }
        val scroll = ScrollView(this).apply { addView(list) }
        scroll.setOnApplyWindowInsetsListener { v, insets ->
            val b = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }
        setContentView(scroll)
        reload()
    }

    /** Runs [work] on the io thread, then rebuilds the screen. */
    private fun act(work: () -> Unit) = MealState.io.execute { runCatching(work); reload() }

    private fun reload() = MealState.io.execute {
        val meals = vault.mealIds().take(2).mapNotNull { vault.readMeal(it) }
        val thumbs = meals.flatMap { it.all }.associateWith { thumb(File(vault.photos, it)) }
        val today = LocalDate.now()
        val water = vault.waterEntries(today)
        runOnUiThread { build(meals, thumbs, today, water) }
    }

    private fun build(meals: List<Vault.Meal>, thumbs: Map<String, Bitmap?>, today: LocalDate, water: List<Vault.WaterEntry>) {
        list.removeAllViews()
        if (meals.isEmpty()) list.addView(text("No meals yet"))
        for (m in meals) {
            list.addView(text(m.time.replace('T', ' ').take(16), bold = true))
            val note = EditText(this).apply { setText(m.note); hint = "Note" }
            list.addView(note)
            list.addView(row(
                button("Save note") { act { vault.setNote(m.id, note.text.toString().trim()) } },
                button("Delete meal") { confirm("Move this meal and its photos to _trash?") { act { vault.trashMeal(m.id) } } },
            ))
            val strip = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for (name in m.all.sortedBy(Vault::photoIndex)) {
                val main = name in m.photo
                val cell = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, dp(8), 0) }
                cell.addView(ImageView(this).apply {
                    setImageBitmap(thumbs[name])
                    scaleType = ImageView.ScaleType.CENTER_CROP
                    layoutParams = LinearLayout.LayoutParams(dp(140), dp(140))
                    alpha = if (main) 1f else 0.75f
                })
                cell.addView(button(if (main) "★ main" else "spec") { act { vault.setMain(m.id, name, !main) } })
                cell.addView(button("Delete") { confirm("Move this photo to _trash?") { act { vault.trashPhoto(m.id, name) } } })
                strip.addView(cell)
            }
            list.addView(HorizontalScrollView(this).apply { addView(strip) })
            list.addView(View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(24)) })
        }

        list.addView(text("Water today: ${water.sumOf { it.ml }} ml", bold = true))
        water.forEachIndexed { i, w ->
            list.addView(row(text("${w.time}   ${w.ml} ml"), button("✕") { act { vault.removeWater(today, i) } }))
        }
    }

    private fun thumb(f: File): Bitmap? = runCatching {
        ImageDecoder.decodeBitmap(ImageDecoder.createSource(f)) { d, _, _ -> d.setTargetSampleSize(8) }
    }.getOrNull()

    private fun confirm(msg: String, yes: () -> Unit) {
        AlertDialog.Builder(this).setMessage(msg).setPositiveButton("Move") { _, _ -> yes() }.setNegativeButton("Cancel", null).show()
    }

    private fun text(s: String, bold: Boolean = false) = TextView(this).apply {
        text = s; textSize = if (bold) 18f else 16f
        if (bold) setTypeface(typeface, Typeface.BOLD)
        setPadding(0, dp(8), dp(16), dp(4))
    }

    private fun button(s: String, onClick: () -> Unit) = Button(this).apply { text = s; isAllCaps = false; setOnClickListener { onClick() } }

    private fun row(vararg views: View) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        views.forEach { addView(it) }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
