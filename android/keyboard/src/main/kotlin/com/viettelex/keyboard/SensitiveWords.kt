package com.viettelex.keyboard

/**
 * Lọc từ tục khỏi THANH GỢI Ý (vẫn học). Emoji không bị lọc. Cặp chửi/tục ghép từ hai TỪ THƯỜNG
 * (thằng chó, sờ chim…) = bảng [pairs] (SensitivePairs, sinh từ Scripts/seed-curated.txt
 * [sensitive-bigram]) — song sinh iOS SensitiveWords.swift.
 */
object SensitiveWords {
    fun filter(words: List<String>, enabled: Boolean): List<String> =
        if (enabled) words.filter { it.lowercase() !in set } else words

    /** Gợi ý đứng SAU [prev]: bỏ từ nhạy cảm VÀ từ tạo cặp nhạy cảm với [prev]. = iOS filter(_:after:enabled:). */
    fun filter(words: List<String>, enabled: Boolean, after: String?): List<String> {
        if (!enabled) return words
        val gated = after?.let { pairs[it.lowercase()] }
        return words.filter { val l = it.lowercase(); l !in set && (gated == null || l !in gated) }
    }

    /** Chỉ phần CẶP — tập ngữ cảnh (điểm thưởng từ kế tiếp): lọc bật ⇒ cặp nhạy cảm không thưởng. */
    fun dropPairs(words: List<String>, after: String?, enabled: Boolean): List<String> {
        val g = if (enabled) after?.let { pairs[it.lowercase()] } else null
        return if (g == null) words else words.filter { it.lowercase() !in g }
    }

    fun isPair(prev: String?, word: String): Boolean =
        prev != null && pairs[prev.lowercase()]?.contains(word.lowercase()) == true

    val set: Set<String> = hashSetOf(
        "đcm", "đm", "dm", "dcm", "vcl", "vkl", "vl", "cl", "clgt", "cmnr",
        "cứt", "lồn", "cặc", "buồi", "đĩ", "điếm", "đụ", "địt",
        "tml", "sml", "dâm", "đkm", "dkm", "buscu",
        "đéo", "nứng", "chịch", "trym", "loz", "cmm", "cmn", "đmm", "dmm", "đcmm", "dcmm", "clm", "clg", "vcc", "dume",
        "cc", "đb", "lìn", "fuckboy",
        "fuck", "shit", "bitch", "wtf", "motherfucker", "asshole", "bullshit", "dick", "dickhead", "pussy",
        "bastard", "dumbass", "fucking", "fucked", "fucker", "shitty", "stfu",
    )

    // BEGIN GENERATED SensitivePairs — Scripts/gen-seed-data.py từ Scripts/seed-curated.txt [sensitive-bigram]; KHÔNG sửa tay.
    val pairs: Map<String, Set<String>> = hashMapOf(
        "bú" to hashSetOf("cu"),
        "bố" to hashSetOf("mày"),
        "chó" to hashSetOf("chết", "má"),
        "chúa" to hashSetOf("phòng"),
        "chơi" to hashSetOf("cave", "gái"),
        "clip" to hashSetOf("nóng"),
        "cái" to hashSetOf("mâm"),
        "cò" to hashSetOf("hương"),
        "damn" to hashSetOf("it"),
        "fuck" to hashSetOf("boy"),
        "kích" to hashSetOf("dục"),
        "láo" to hashSetOf("chó"),
        "lùn" to hashSetOf("tịt"),
        "lộ" to hashSetOf("clip"),
        "massage" to hashSetOf("kích"),
        "má" to hashSetOf("mày"),
        "móc" to hashSetOf("lốp"),
        "mẹ" to hashSetOf("mày"),
        "như" to hashSetOf("chó", "cột"),
        "nâng" to hashSetOf("bi"),
        "não" to hashSetOf("phẳng"),
        "nện" to hashSetOf("nhau"),
        "phang" to hashSetOf("nhau"),
        "phim" to hashSetOf("heo"),
        "piss" to hashSetOf("off"),
        "quay" to hashSetOf("tay"),
        "rờ" to hashSetOf("cu"),
        "sờ" to hashSetOf("chim", "cu"),
        "thằng" to hashSetOf("chó", "khốn", "mặt", "ngu", "điên"),
        "vãi" to hashSetOf("đái"),
        "xoạc" to hashSetOf("nhau"),
        "óc" to hashSetOf("lợn"),
        "đù" to hashSetOf("má"),
        "đậu" to hashSetOf("má"),
        "đồ" to hashSetOf("chó", "khốn", "ngu", "điên"),
    )
    // END GENERATED SensitivePairs
}
