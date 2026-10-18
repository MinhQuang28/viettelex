package com.viettelex.android.ime

import com.viettelex.keyboard.TextTool
import com.viettelex.keyboard.tr

/**
 * Ô ☰ mép trái thanh gợi ý + nội dung lưới mẫu câu / công cụ — THUẦN (TemplatesMenuTest).
 * Song sinh iOS KeyboardView.leftZone / buildTemplates.
 *
 * ☰ hiện khi Mẫu câu HOẶC Công cụ văn bản bật (user 10/2026: tắt Mẫu câu làm mất lối vào
 * Công cụ). Mẫu câu tắt + Công cụ bật ⇒ chạm ☰ mở THẲNG danh sách công cụ (không mẫu câu,
 * không "‹ Mẫu câu", không ⚙️). `toolsEnabled` = Plus mở khoá + công tắc + không phải ô mật
 * khẩu (KeyboardSettings.textToolsActive).
 */
object TemplatesMenu {
    enum class LeftZone { TEMPLATES, TOOLS, NONE }

    fun leftZone(templatesEnabled: Boolean, toolsEnabled: Boolean): LeftZone = when {
        templatesEnabled -> LeftZone.TEMPLATES
        toolsEnabled -> LeftZone.TOOLS
        else -> LeftZone.NONE
    }

    const val TOOLS_ENTRY = "tools"
    const val TOOLS_BACK = "back"
    const val TOOLS_TRANSLATE = "translate"

    /** Nội dung lưới: [showTemplates] = có hiện danh sách mẫu câu; [extras] = chip đặc biệt đầu lưới. */
    data class Pane(val showTemplates: Boolean, val extras: List<Pair<String, String>>, val gear: Boolean)

    fun pane(toolsMode: Boolean, templatesEnabled: Boolean, textTools: Boolean, translate: Boolean): Pane {
        val anyTools = textTools || translate
        val toolsOnly = !templatesEnabled
        return if ((toolsMode || toolsOnly) && anyTools) {
            Pane(false,
                (if (toolsOnly) emptyList() else listOf(tr("‹ Mẫu câu") to TOOLS_BACK)) +
                    (if (textTools) TextTool.entries.map { it.label to it.id } else emptyList()) +
                    (if (translate) listOf(tr("Dịch") to TOOLS_TRANSLATE) else emptyList()),
                gear = false)
        } else {
            Pane(templatesEnabled,
                if (anyTools) listOf(tr("⚒️ Công cụ") to TOOLS_ENTRY) else emptyList(), gear = true)
        }
    }
}
