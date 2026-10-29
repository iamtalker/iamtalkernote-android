package kr.iamtalker.notes

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** 노트 머리말(조플린 내보내기의 `---` 블록)에서 수정 시각과 태그를 읽는다. 데스크톱 앱(store.py)의 규칙을 따른다. */
object Meta {
    data class Info(val updated: Long, val tags: List<String>)

    private val front = Regex("^﻿?---[ \\t]*\\r?\\n([\\s\\S]*?)\\r?\\n---[ \\t]*(?:\\r?\\n|$)")
    private val bodyTag = Regex("(?<![\\p{L}\\p{N}_&/#])#([\\p{L}\\p{N}_\\-]{1,40})")
    private val tagLine = Regex("^[ \\t]*(?:태그|tags?)[ \\t]*[:：](.*)$", RegexOption.IGNORE_CASE)
    private val date = Regex("(\\d{4})[-./](\\d{1,2})[-./](\\d{1,2})(?:[T\\s]+(\\d{1,2}):(\\d{2})(?::(\\d{2}))?)?(?:\\.\\d+)?\\s*(Z|[+-]\\d{2}:?\\d{2})?")

    /** (머리말 글, 본문) */
    fun split(text: String): Pair<String, String> {
        val m = front.find(text) ?: return "" to text
        return m.groupValues[1] to text.substring(m.range.last + 1)
    }

    fun parse(text: String): Info {
        val (fm, body) = split(text)
        var updated = 0L
        val tags = LinkedHashMap<String, String>()   // 소문자 → 처음 쓴 모양
        fun add(t: String) {
            val x = t.trim().trim('#', '"', '\'', '[', ']', ',').trim()
            if (x.isNotEmpty() && x.length <= 40) tags.putIfAbsent(x.lowercase(), x)
        }
        var inTagList = false
        for (line in fm.lines()) {
            val m = Regex("^([A-Za-z_]+)[ \\t]*:[ \\t]*(.*)$").find(line)
            if (m != null) {
                inTagList = false
                val key = m.groupValues[1].lowercase()
                val v = m.groupValues[2].trim()
                when (key) {
                    "updated" -> updated = parseDate(v)
                    "tags", "tag" -> {
                        if (v.isEmpty()) inTagList = true
                        else v.trim('[', ']').split(',').forEach(::add)
                    }
                }
            } else if (inTagList) {
                val t = line.trim()
                if (t.startsWith("-")) add(t.removePrefix("-")) else if (t.isNotEmpty()) inTagList = false
            }
        }
        var fence = false
        for (line in body.lineSequence()) {
            if (line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")) { fence = !fence; continue }
            if (fence) continue
            val tl = tagLine.find(line)
            if (tl != null) {
                tl.groupValues[1].split(',', ' ').forEach { if (it.isNotBlank()) add(it) }
                continue
            }
            if (line.contains('#') && !line.startsWith("#")) bodyTag.findAll(line).forEach { add(it.groupValues[1]) }
        }
        return Info(updated, tags.values.toList())
    }

    /** `2024-05-01T12:30:00.000Z`, `2024-05-01 12:30`, `2024.05.01` … → 밀리초(읽을 수 없으면 0) */
    fun parseDate(s: String): Long {
        val m = date.find(s) ?: return 0
        return try {
            val (y, mo, d, h, mi, se, z) = m.destructured
            val ldt = LocalDateTime.of(y.toInt(), mo.toInt(), d.toInt(), h.ifEmpty { "0" }.toInt(), mi.ifEmpty { "0" }.toInt(), se.ifEmpty { "0" }.toInt())
            val zone: ZoneOffset? = when {
                z.isEmpty() -> null
                z == "Z" -> ZoneOffset.UTC
                else -> ZoneOffset.of(if (z.contains(':')) z else z.substring(0, 3) + ":" + z.substring(3))
            }
            if (zone != null) ldt.toInstant(zone).toEpochMilli() else ldt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        } catch (e: Exception) {
            0
        }
    }
}
