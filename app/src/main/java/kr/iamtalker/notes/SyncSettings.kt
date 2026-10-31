package kr.iamtalker.notes

import org.json.JSONObject
import java.io.File

/** 설정 양방향 맞추기(컴퓨터 앱 syncsettings.py 와 같은 규칙): 방향·함께 보낼 것을 폰에서도 고치고, '더 최근에 바꾼 쪽'이 이긴다.
 *  설정만 담는 작은 폴더(종류 cfg, 항상 양방향)의 settings.json 으로 컴퓨터와 주고받는다. */
object SyncSettings {
    const val FILE = "settings.json"
    val KEYS = listOf("media", "imported", "history", "trash")
    val DIRECTIONS = listOf("both", "pc2mobile", "mobile2pc", "off")

    data class S(val updated: Long, val direction: String, val include: Map<String, Boolean>)

    /** 폰의 설정 폴더 위치(노트 폴더 옆). ForkSync.localPath 의 cfg 와 같다 */
    fun dir(vaultPath: String) = File(vaultPath.trimEnd('/') + "-settings")

    fun fromPolicy(pol: JSONObject): S? {
        val sm = pol.optJSONObject("simple") ?: return null
        val inc = sm.optJSONObject("include")
        return S(sm.optLong("updated", 0L), sm.optString("direction", "both"), KEYS.associateWith { inc?.optBoolean(it, false) == true })
    }

    fun parse(text: String): S? = runCatching {
        val o = JSONObject(text)
        val dir = o.getString("direction")
        if (dir !in DIRECTIONS) return null
        val inc = o.optJSONObject("include")
        S(o.getLong("updated"), dir, KEYS.associateWith { inc?.optBoolean(it, false) == true })
    }.getOrNull()

    fun toJson(s: S): String = JSONObject().put("updated", s.updated).put("direction", s.direction)
        .put("include", JSONObject().also { j -> KEYS.forEach { j.put(it, s.include[it] == true) } }).toString()

    fun read(vaultPath: String): S? = runCatching { parse(File(dir(vaultPath), FILE).readText()) }.getOrNull()

    fun write(vaultPath: String, s: S) {
        val d = dir(vaultPath).apply { mkdirs() }
        val tmp = File(d, "$FILE.tmp")
        tmp.writeText(toJson(s))
        tmp.renameTo(File(d, FILE)).also { if (!it) { File(d, FILE).writeText(toJson(s)); tmp.delete() } }
    }

    /** 컴퓨터가 준 정책과 설정 파일 중 더 최근 것(같으면 정책) */
    fun effective(policy: S, file: S?): S = if (file != null && file.updated > policy.updated) file else policy

    /** 정책 JSON 에 설정을 덮어쓴 복사본(방향·함께 보낼 것·옛 형식 modules 까지) */
    fun overlay(pol: JSONObject, s: S): JSONObject {
        val c = JSONObject(pol.toString())
        val sm = c.optJSONObject("simple") ?: return c
        sm.put("direction", s.direction).put("updated", s.updated)
        sm.put("include", JSONObject().also { j -> KEYS.forEach { j.put(it, s.include[it] == true) } })
        c.optJSONObject("modules")?.let { mods ->
            mods.optJSONObject("markdown")?.put("direction", s.direction)
            for (k in KEYS) mods.optJSONObject(k)?.put("direction", if (s.include[k] == true) s.direction else "off")
        }
        return c
    }

    /** 이 폰에서 설정을 고친다: 바뀐 것이 있으면 지금 시각으로 적는다. 돌려주는 값: 적었는가 */
    fun change(vaultPath: String, policy: JSONObject, direction: String? = null, key: String? = null, on: Boolean? = null, now: Long = System.currentTimeMillis()): Boolean {
        val cur = effective(fromPolicy(policy) ?: return false, read(vaultPath))
        val inc = cur.include.toMutableMap()
        if (key != null && on != null) inc[key] = on
        val next = cur.copy(direction = direction ?: cur.direction, include = inc, updated = now)
        if (next.direction == cur.direction && next.include == cur.include) return false
        write(vaultPath, next)
        return true
    }
}

/** 폰이 자기 저장소에서 직접 센 진행(글·그림 개수)을 설정 폴더의 progress.json 으로 컴퓨터에 알린다 */
object PhoneProgress {
    const val FILE = "progress.json"

    fun toJson(o: Overall, now: Long): String = JSONObject().put("updated", now).put("notesHave", o.notesHave).put("notesAll", o.notesAll)
        .put("mediaHave", o.mediaHave).put("mediaAll", o.mediaAll).toString()

    /** 설정 폴더가 있을 때만 적는다(컴퓨터가 설정 폴더를 보내 줬다는 뜻). 적었으면 true */
    fun write(vaultPath: String, o: Overall, now: Long = System.currentTimeMillis()): Boolean = runCatching {
        val d = SyncSettings.dir(vaultPath)
        if (!d.isDirectory) return false
        val tmp = File(d, "$FILE.tmp")
        tmp.writeText(toJson(o, now))
        if (!tmp.renameTo(File(d, FILE))) { File(d, FILE).writeText(toJson(o, now)); tmp.delete() }
        true
    }.getOrDefault(false)
}
