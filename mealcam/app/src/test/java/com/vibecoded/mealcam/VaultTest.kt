package com.vibecoded.mealcam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime

class VaultTest {
    private val root = Files.createTempDirectory("vault").toFile()
    private val v = Vault(root)
    private val t = LocalDateTime.of(2026, 11, 20, 13, 12, 5)

    private fun jpg(): File = File.createTempFile("pending_", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }

    @Test fun firstPhotoIsMainRestAreSpecs() {
        val id = v.addPhoto(jpg(), t, null)
        assertEquals("2026-11-20_1312", id)
        assertEquals(id, v.addPhoto(jpg(), t.plusMinutes(1), id))
        val m = v.readMeal(id)!!
        assertEquals("2026-11-20T13:12:05", m.time)
        assertEquals(listOf("2026-11-20_1312_1.jpg"), m.photo)
        assertEquals(listOf("2026-11-20_1312_2.jpg"), m.spec)
        assertTrue(File(v.photos, "2026-11-20_1312_2.jpg").exists())
        val text = v.mealFile(id).readText()
        assertTrue(text.contains("![[2026-11-20_1312_1.jpg]]\n![[2026-11-20_1312_2.jpg]]"))
        assertFalse(v.photos.list()!!.any { it.endsWith(".tmp") })
    }

    @Test fun sameMinuteNewMealGetsSuffix() {
        val a = v.addPhoto(jpg(), t, null)
        val b = v.addPhoto(jpg(), t, null)
        assertEquals("2026-11-20_1312-2", b)
        assertEquals(listOf(b, a), v.mealIds())
    }

    @Test fun noteRoundTripsWithQuotesAndNewlines() {
        val id = v.addPhoto(jpg(), t, null)
        v.setNote(id, "half a can, \"spicy\"\nwith rice")
        assertEquals("half a can, \"spicy\"\nwith rice", v.readMeal(id)!!.note)
    }

    @Test fun setMainKeepsOrderAndAllowsSeveralMains() {
        val id = v.addPhoto(jpg(), t, null)
        v.addPhoto(jpg(), t, id); v.addPhoto(jpg(), t, id)
        v.setMain(id, "${id}_3.jpg", true)
        assertEquals(listOf("${id}_1.jpg", "${id}_3.jpg"), v.readMeal(id)!!.photo)
        v.setMain(id, "${id}_1.jpg", false)
        val m = v.readMeal(id)!!
        assertEquals(listOf("${id}_3.jpg"), m.photo)
        assertEquals(listOf("${id}_1.jpg", "${id}_2.jpg"), m.spec)
    }

    @Test fun trashMovesInsteadOfDeleting() {
        val id = v.addPhoto(jpg(), t, null)
        v.addPhoto(jpg(), t, id)
        v.trashPhoto(id, "${id}_2.jpg")
        assertTrue(File(root, "_trash/food/photos/${id}_2.jpg").exists())
        v.trashPhoto(id, "${id}_1.jpg") // last photo, empty note -> the meal goes too
        assertFalse(v.mealFile(id).exists())
        assertTrue(File(root, "_trash/food/$id.md").exists())
    }

    @Test fun readsObsidianStyleFrontmatterAndKeepsExtraKeys() {
        v.food.mkdirs()
        v.mealFile("2026-11-20_0800").writeText(
            "---\ntime: 2026-11-20T08:00:00\nphoto: [\"[[a_1.jpg]]\", \"[[a_2.jpg]]\"]\nspec:\n- \"[[a_3.jpg]]\"\nnote: 'it''s fine'\ntags:\n  - test\n---\n"
        )
        val m = v.readMeal("2026-11-20_0800")!!
        assertEquals(listOf("a_1.jpg", "a_2.jpg"), m.photo)
        assertEquals(listOf("a_3.jpg"), m.spec)
        assertEquals("it's fine", m.note)
        v.writeMeal(m)
        assertTrue(v.mealFile(m.id).readText().contains("tags:\n  - test\n"))
    }

    @Test fun waterTotalsAndRemoval() {
        assertEquals(500, v.addWater(t, 500))
        assertEquals(750, v.addWater(t.plusHours(1), 250))
        assertEquals(
            "---\ndate: 2026-11-20\nwater_ml: 750\n---\n- 13:12 500\n- 14:12 250\n",
            v.waterFile(t.toLocalDate()).readText()
        )
        assertEquals(250, v.removeWater(t.toLocalDate(), 0))
    }
}
