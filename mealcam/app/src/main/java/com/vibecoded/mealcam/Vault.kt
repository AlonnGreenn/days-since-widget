package com.vibecoded.mealcam

import java.io.File
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * All reading and writing of vault files. Plain JVM (no Android imports) so it is unit-tested.
 *
 *   food/<id>.md                  one note per meal, id = yyyy-MM-dd_HHmm (+ "-2", "-3" on a clash)
 *   food/photos/<id>_<n>.jpg      photos, never renamed
 *   food/water/<yyyy-MM-dd>.md    one note per day
 *   _trash/...                    deletions are moves here (never hard-deleted)
 */
class Vault(val root: File) {
    val food = File(root, "food")
    val photos = File(food, "photos")
    val water = File(food, "water")
    val trash = File(root, "_trash")

    class Meal(
        val id: String,
        var time: String,
        val photo: MutableList<String> = mutableListOf(),
        val spec: MutableList<String> = mutableListOf(),
        var note: String = "",
        /** Frontmatter keys this app doesn't own (e.g. added in Obsidian), kept verbatim. */
        val extra: MutableList<String> = mutableListOf(),
    ) {
        val all get() = photo + spec
    }

    fun mealFile(id: String) = File(food, "$id.md")

    // ---------- meals ----------

    fun readMeal(id: String): Meal? {
        val f = mealFile(id)
        if (!f.exists()) return null
        val fm = Frontmatter.parse(f.readText())
        return Meal(
            id = id,
            time = fm.scalar("time") ?: "",
            photo = fm.list("photo").map(::unlink).toMutableList(),
            spec = fm.list("spec").map(::unlink).toMutableList(),
            note = fm.scalar("note") ?: "",
            extra = fm.rawExcept(OWN_KEYS).toMutableList(),
        )
    }

    fun writeMeal(m: Meal) {
        val sb = StringBuilder("---\n")
        sb.append("time: ").append(m.time).append('\n')
        appendList(sb, "photo", m.photo.map { "[[$it]]" })
        appendList(sb, "spec", m.spec.map { "[[$it]]" })
        sb.append("note: ").append(Frontmatter.quote(m.note)).append('\n')
        m.extra.forEach { sb.append(it).append('\n') }
        sb.append("---\n")
        m.all.forEach { sb.append("![[").append(it).append("]]\n") }
        writeAtomic(mealFile(m.id), sb.toString())
    }

    /** Meal ids, newest first. */
    fun mealIds(): List<String> =
        (food.listFiles() ?: emptyArray())
            .filter { it.isFile && it.name.endsWith(".md") && MEAL_ID.matches(it.name.removeSuffix(".md")) }
            .map { it.name.removeSuffix(".md") }
            .sortedDescending()

    fun newMealId(t: LocalDateTime): String {
        val base = t.format(ID_FMT)
        if (!mealFile(base).exists()) return base
        var i = 2
        while (mealFile("$base-$i").exists()) i++
        return "$base-$i"
    }

    /**
     * Moves [src] (a finished JPEG) into the vault as a photo of meal [mealId], or of a new meal
     * if [mealId] is null or gone. The first photo of a meal is its main photo, the rest are specs.
     * Returns the meal id used.
     */
    fun addPhoto(src: File, takenAt: LocalDateTime, mealId: String?): String {
        photos.mkdirs()
        val meal = mealId?.let { readMeal(it) }
            ?: Meal(id = newMealId(takenAt), time = takenAt.withNano(0).format(TIME_FMT))
        var n = 1
        while (File(photos, "${meal.id}_$n.jpg").exists() || "${meal.id}_$n.jpg" in meal.all) n++
        val name = "${meal.id}_$n.jpg"
        val tmp = File(photos, ".syncthing.$name.tmp")
        src.copyTo(tmp, overwrite = true)
        rename(tmp, File(photos, name))
        src.delete()
        if (meal.photo.isEmpty()) meal.photo += name else meal.spec += name
        writeMeal(meal)
        return meal.id
    }

    fun setNote(id: String, note: String) {
        val m = readMeal(id) ?: return
        m.note = note
        writeMeal(m)
    }

    /** Marks [name] as a main photo (true) or a spec photo (false), keeping the order taken. */
    fun setMain(id: String, name: String, main: Boolean) {
        val m = readMeal(id) ?: return
        val order = m.all.sortedBy(::photoIndex)
        val mains = (m.photo.toSet() - name) + (if (main) setOf(name) else emptySet())
        m.photo.clear(); m.spec.clear()
        order.forEach { if (it in mains) m.photo += it else m.spec += it }
        writeMeal(m)
    }

    /** Moves one photo to _trash. A meal left with no photos and no note goes to _trash too. */
    fun trashPhoto(id: String, name: String) {
        val m = readMeal(id) ?: return
        m.photo.remove(name); m.spec.remove(name)
        toTrash(File(photos, name))
        if (m.all.isEmpty() && m.note.isBlank()) toTrash(mealFile(id)) else writeMeal(m)
    }

    fun trashMeal(id: String) {
        val m = readMeal(id) ?: return
        m.all.forEach { toTrash(File(photos, it)) }
        toTrash(mealFile(id))
    }

    // ---------- water ----------

    class WaterEntry(val time: String, val ml: Int)

    fun waterFile(d: LocalDate) = File(water, "$d.md")

    fun waterEntries(d: LocalDate): List<WaterEntry> {
        val f = waterFile(d)
        if (!f.exists()) return emptyList()
        return Frontmatter.body(f.readText()).lines().mapNotNull { WATER_LINE.matchEntire(it.trim()) }
            .map { WaterEntry(it.groupValues[1], it.groupValues[2].toInt()) }
    }

    /** Logs [ml] at [t]; returns the day's new total. */
    fun addWater(t: LocalDateTime, ml: Int): Int {
        val d = t.toLocalDate()
        val entries = waterEntries(d) + WaterEntry(t.format(HM_FMT), ml)
        return writeWater(d, entries)
    }

    fun removeWater(d: LocalDate, index: Int): Int {
        val entries = waterEntries(d).toMutableList()
        if (index in entries.indices) entries.removeAt(index)
        return writeWater(d, entries)
    }

    private fun writeWater(d: LocalDate, entries: List<WaterEntry>): Int {
        val total = entries.sumOf { it.ml }
        val sb = StringBuilder("---\ndate: $d\nwater_ml: $total\n---\n")
        entries.forEach { sb.append("- ").append(it.time).append(' ').append(it.ml).append('\n') }
        writeAtomic(waterFile(d), sb.toString())
        return total
    }

    // ---------- files ----------

    /** Syncthing ignores ".syncthing.*.tmp", so it never syncs a half-written file. */
    private fun writeAtomic(f: File, text: String) {
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, ".syncthing.${f.name}.tmp")
        tmp.writeText(text)
        rename(tmp, f)
    }

    private fun rename(from: File, to: File) {
        if (from.renameTo(to)) return
        to.delete()
        if (!from.renameTo(to)) error("rename failed: $from -> $to")
    }

    private fun toTrash(f: File) {
        if (!f.exists()) return
        val rel = f.relativeTo(root).path
        var dest = File(trash, rel)
        var i = 2
        while (dest.exists()) dest = File(trash, "$rel.$i").also { i++ }
        dest.parentFile?.mkdirs()
        rename(f, dest)
    }

    private fun appendList(sb: StringBuilder, key: String, items: List<String>) {
        if (items.isEmpty()) { sb.append(key).append(": []\n"); return }
        sb.append(key).append(":\n")
        items.forEach { sb.append("  - ").append(Frontmatter.quote(it)).append('\n') }
    }

    companion object {
        val OWN_KEYS = setOf("time", "photo", "spec", "note")
        val ID_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")
        val TIME_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")
        val HM_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")
        val MEAL_ID = Regex("""\d{4}-\d\d-\d\d_\d{4}(-\d+)?""")
        val WATER_LINE = Regex("""- (\d\d:\d\d) (\d+)""")

        fun unlink(s: String) = s.removePrefix("!").removePrefix("[[").removeSuffix("]]").substringBefore('|')
        fun photoIndex(name: String) = name.substringAfterLast('_').substringBefore('.').toIntOrNull() ?: 0
    }
}

/** Minimal YAML frontmatter reader: scalars, block lists ("- x") and inline lists ("[a, b]"). */
class Frontmatter private constructor(private val blocks: LinkedHashMap<String, List<String>>) {

    fun scalar(key: String): String? = blocks[key]?.firstOrNull()?.let { unquote(it.substringAfter(':').trim()) }

    fun list(key: String): List<String> {
        val lines = blocks[key] ?: return emptyList()
        val inline = lines.first().substringAfter(':').trim()
        if (inline.startsWith("[")) {
            val inner = inline.removePrefix("[").removeSuffix("]").trim()
            if (inner.isEmpty()) return emptyList()
            return splitInline(inner).map { unquote(it.trim()) }.filter { it.isNotEmpty() }
        }
        if (inline.isNotEmpty()) return listOf(unquote(inline))
        return lines.drop(1).map { it.trim() }.filter { it.startsWith("-") }
            .map { unquote(it.removePrefix("-").trim()) }.filter { it.isNotEmpty() }
    }

    fun rawExcept(keys: Set<String>): List<String> = blocks.filterKeys { it !in keys }.values.flatten()

    companion object {
        fun parse(text: String): Frontmatter {
            val blocks = LinkedHashMap<String, List<String>>()
            val lines = text.lines()
            if (lines.firstOrNull()?.trim() != "---") return Frontmatter(blocks)
            var key: String? = null
            var cur = mutableListOf<String>()
            for (line in lines.drop(1)) {
                if (line.trim() == "---") break
                val top = line.isNotEmpty() && !line[0].isWhitespace() && !line.startsWith("-") && line.contains(':')
                if (top) {
                    key?.let { blocks[it] = cur }
                    key = line.substringBefore(':').trim()
                    cur = mutableListOf(line)
                } else if (key != null) cur += line
            }
            key?.let { blocks[it] = cur }
            return Frontmatter(blocks)
        }

        fun body(text: String): String {
            val lines = text.lines()
            if (lines.firstOrNull()?.trim() != "---") return text
            val end = lines.drop(1).indexOfFirst { it.trim() == "---" }
            return if (end < 0) "" else lines.drop(end + 2).joinToString("\n")
        }

        fun quote(s: String): String =
            "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\""

        fun unquote(s: String): String = when {
            s.length >= 2 && s.startsWith("\"") && s.endsWith("\"") -> {
                val sb = StringBuilder()
                var i = 1
                while (i < s.length - 1) {
                    val c = s[i]
                    if (c == '\\' && i + 1 < s.length - 1) {
                        when (val n = s[i + 1]) { 'n' -> sb.append('\n'); 't' -> sb.append('\t'); else -> sb.append(n) }
                        i += 2
                    } else { sb.append(c); i++ }
                }
                sb.toString()
            }
            s.length >= 2 && s.startsWith("'") && s.endsWith("'") -> s.substring(1, s.length - 1).replace("''", "'")
            else -> s
        }

        /** Splits on commas outside quotes and [[ ]]. */
        private fun splitInline(s: String): List<String> {
            val out = mutableListOf<String>()
            val cur = StringBuilder()
            var q: Char? = null
            var depth = 0
            var i = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    q != null -> { cur.append(c); if (c == '\\' && q == '"' && i + 1 < s.length) { cur.append(s[i + 1]); i++ } else if (c == q) q = null }
                    c == '"' || c == '\'' -> { q = c; cur.append(c) }
                    c == '[' -> { depth++; cur.append(c) }
                    c == ']' -> { depth--; cur.append(c) }
                    c == ',' && depth == 0 -> { out += cur.toString(); cur.clear() }
                    else -> cur.append(c)
                }
                i++
            }
            out += cur.toString()
            return out
        }
    }
}
