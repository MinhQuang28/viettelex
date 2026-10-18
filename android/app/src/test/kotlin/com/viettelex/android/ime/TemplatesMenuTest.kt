package com.viettelex.android.ime

import com.viettelex.keyboard.KeyboardSettings
import com.viettelex.keyboard.TextTool
import com.viettelex.keyboard.tr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** ☰ hiện khi Mẫu câu HOẶC Công cụ văn bản bật; Mẫu câu tắt ⇒ ☰ mở thẳng công cụ (user 10/2026). */
class TemplatesMenuTest {
    @Test fun leftZoneMatrixWithPlusGate() {
        for (templates in listOf(false, true)) for (toolsSwitch in listOf(false, true))
            for (unlocked in listOf(false, true)) for (secure in listOf(false, true)) {
                val s = KeyboardSettings(textToolsEnabled = toolsSwitch)
                val z = TemplatesMenu.leftZone(templates, s.textToolsActive(unlocked, secure))
                val want = when {
                    templates -> TemplatesMenu.LeftZone.TEMPLATES
                    toolsSwitch && unlocked && !secure -> TemplatesMenu.LeftZone.TOOLS
                    else -> TemplatesMenu.LeftZone.NONE
                }
                assertEquals("templates=$templates tools=$toolsSwitch unlocked=$unlocked secure=$secure", want, z)
            }
    }

    private val toolLabels get() = TextTool.entries.map { it.label }

    /** Chạm ☰ ở chế độ chỉ-công-cụ: lưới = danh sách công cụ, không mẫu câu / "‹ Mẫu câu" / ⚙️. */
    @Test fun toolsOnlyTapOpensToolsDirectly() {
        val p = TemplatesMenu.pane(toolsMode = false, templatesEnabled = false, textTools = true, translate = true)
        assertFalse(p.showTemplates)
        assertFalse(p.gear)
        assertEquals(toolLabels + tr("Dịch"), p.extras.map { it.first })
        assertFalse(p.extras.any { it.second == TemplatesMenu.TOOLS_BACK || it.second == TemplatesMenu.TOOLS_ENTRY })
    }

    /** Mẫu câu bật: như cũ — mẫu câu + chip ⚒️; vào công cụ có "‹ Mẫu câu". */
    @Test fun templatesOnUnchanged() {
        val grid = TemplatesMenu.pane(toolsMode = false, templatesEnabled = true, textTools = true, translate = false)
        assertTrue(grid.showTemplates); assertTrue(grid.gear)
        assertEquals(listOf(tr("⚒️ Công cụ") to TemplatesMenu.TOOLS_ENTRY), grid.extras)
        val tools = TemplatesMenu.pane(toolsMode = true, templatesEnabled = true, textTools = true, translate = false)
        assertFalse(tools.showTemplates); assertFalse(tools.gear)
        assertEquals(listOf(tr("‹ Mẫu câu")) + toolLabels, tools.extras.map { it.first })
        val noTools = TemplatesMenu.pane(toolsMode = false, templatesEnabled = true, textTools = false, translate = false)
        assertTrue(noTools.extras.isEmpty()); assertTrue(noTools.showTemplates)
    }
}
