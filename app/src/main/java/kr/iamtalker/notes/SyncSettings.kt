package kr.iamtalker.notes

import org.json.JSONObject
import java.io.File

/** 컴퓨터와 폰의 동기화 규칙(방향·함께 보낼 것)이 같은지 맞춰 본다(컴퓨터 앱 syncsettings.py 와 같은 규칙): 같아야 동기화하고, 다르면 그 폴더는 멈춘다.
 *  설정만 담는 작은 폴더(종류 cfg, 항상 양방향)로 각자 자기 파일만 쓴다(서로 덮어쓰지 않으니 '누가 더 나중에 바꿨나' 를 가릴 일이 없다).
 *   · pc-config.json    (컴퓨터가 씀) 컴퓨터의 지금 규칙
 *   · phone-config.json (폰이 씀)   폰의 규칙. 폰은 처음 한 번만 컴퓨터 값으로 채워 두고([seed]), 그 뒤로는 독립이다 — 컴퓨터가 바뀌어도 따라가지 않는다
 *                                   (따라가면 한쪽만 바꿔도 반대쪽이 따라와서 '같아야 동기화' 가 지켜지지 않는다) */
object SyncSettings {
    const val PC_FILE = "pc-config.json"
    const val OWN_FILE = "phone-config.json"
    val KEYS = listOf("media", "imported", "history", "trash")
    val DIRECTIONS = listOf("both", "pc2mobile", "mobile2pc", "off")
    const val NOTES = "notes"   // mismatched() 가 돌려주는 값 중 노트 폴더를 가리키는 이름(외부 폴더는 Syncthing 폴더 ID)

    /** 한쪽의 규칙. ext: 외부 폴더 Syncthing 폴더 ID → 방향 */
    data class Rules(val direction: String, val include: Map<String, Boolean>, val ext: Map<String, String>)

    /** 폰이 컴퓨터와 다르게 정한 것만 */
    data class Over(val direction: String? = null, val include: Map<String, Boolean> = emptyMap(), val ext: Map<String, String> = emptyMap())

    /** 폰의 설정 폴더 위치(노트 폴더 옆). ForkSync.localPath 의 cfg 와 같다 */
    fun dir(vaultPath: String) = File(vaultPath.trimEnd('/') + "-settings")

    /** 정책 모듈 중 외부 폴더(ext:…)의 Syncthing 폴더 ID → 모듈 이름 */
    fun extModules(pol: JSONObject): Map<String, String> {
        val vault = pol.optString("vault")
        val mods = pol.optJSONObject("modules") ?: return emptyMap()
        return mods.keys().asSequence().filter { mods.getJSONObject(it).optString("kind") == "external" }.associateBy { ForkSync.folderId(vault, "external", it) }
    }

    /** 옛 컴퓨터 앱(설정 폴더에 규칙 파일을 안 쓰는 버전)을 위해 정책 파일에서 컴퓨터의 규칙을 읽는다 */
    fun fromPolicy(pol: JSONObject): Rules? {
        val sm = pol.optJSONObject("simple") ?: return null
        val inc = sm.optJSONObject("include")
        val mods = pol.optJSONObject("modules")
        val ext = extModules(pol).mapValues { (_, mid) -> mods!!.getJSONObject(mid).optString("direction", "off") }
        return Rules(sm.optString("direction", "both"), KEYS.associateWith { inc?.optBoolean(it, false) == true }, ext)
    }

    fun parseRules(text: String): Rules? = runCatching {
        val o = JSONObject(text)
        val n = o.getJSONObject("notes")
        val dir = n.getString("direction")
        if (dir !in DIRECTIONS) return null
        val inc = n.optJSONObject("include")
        val e = o.optJSONObject("ext")
        val ext = HashMap<String, String>()
        e?.keys()?.forEach { id -> e.optString(id).takeIf { it in DIRECTIONS }?.let { ext[id] = it } }
        Rules(dir, KEYS.associateWith { inc?.optBoolean(it, false) == true }, ext)
    }.getOrNull()

    /** 컴퓨터의 규칙: 설정 폴더의 pc-config.json, 없으면(옛 컴퓨터 앱) 정책 파일에서 */
    fun pcRules(vaultPath: String, pol: JSONObject?): Rules? =
        runCatching { parseRules(File(dir(vaultPath), PC_FILE).readText()) }.getOrNull() ?: pol?.let { fromPolicy(it) }

    fun parseOver(text: String): Over = runCatching {
        val o = JSONObject(text)
        val n = o.optJSONObject("notes")
        val dir = n?.optString("direction")?.takeIf { it in DIRECTIONS }
        val inc = HashMap<String, Boolean>()
        n?.optJSONObject("include")?.let { j -> KEYS.forEach { k -> if (j.has(k)) inc[k] = j.optBoolean(k) } }
        val e = o.optJSONObject("ext")
        val ext = HashMap<String, String>()
        e?.keys()?.forEach { id -> e.optString(id).takeIf { it in DIRECTIONS }?.let { ext[id] = it } }
        Over(dir, inc, ext)
    }.getOrDefault(Over())

    fun toJson(o: Over): String {
        val notes = JSONObject()
        o.direction?.let { notes.put("direction", it) }
        if (o.include.isNotEmpty()) notes.put("include", JSONObject().also { j -> o.include.forEach { (k, v) -> j.put(k, v) } })
        return JSONObject().put("notes", notes).put("ext", JSONObject().also { j -> o.ext.forEach { (k, v) -> j.put(k, v) } }).toString()
    }

    fun overrides(vaultPath: String): Over = runCatching { parseOver(File(dir(vaultPath), OWN_FILE).readText()) }.getOrDefault(Over())

    private fun writeOver(vaultPath: String, o: Over) {
        val d = dir(vaultPath).apply { mkdirs() }
        val tmp = File(d, "$OWN_FILE.tmp")
        tmp.writeText(toJson(o))
        tmp.renameTo(File(d, OWN_FILE)).also { if (!it) { File(d, OWN_FILE).writeText(toJson(o)); tmp.delete() } }
    }

    /** 폰의 규칙을 처음 한 번 컴퓨터 값으로 채워 둔다(없는 항목만. 이미 정한 값은 그대로). 이 뒤로 폰은 컴퓨터를 따라가지 않는다.
     *  컴퓨터가 설정 폴더를 아직 보내 주지 않았으면(폴더가 없으면) 아무것도 쓰지 않는다 → 그동안은 컴퓨터를 그대로 따른다. 지금의 폰 규칙을 돌려준다 */
    fun seed(vaultPath: String, pc: Rules): Over {
        val o = overrides(vaultPath)
        if (!dir(vaultPath).isDirectory) return o
        val next = o.copy(direction = o.direction ?: pc.direction,
            include = pc.include.mapValues { (k, v) -> o.include[k] ?: v },
            ext = pc.ext.mapValues { (id, v) -> o.ext[id] ?: v })
        if (next != o) writeOver(vaultPath, next)
        return next
    }

    /** 폰의 지금 규칙: 폰이 정해 둔 값(seed 가 채운다). 아직 없는 항목은 컴퓨터 값 */
    fun effective(pc: Rules, o: Over): Rules = Rules(
        o.direction ?: pc.direction,
        pc.include.mapValues { (k, v) -> o.include[k] ?: v },
        pc.ext.mapValues { (id, v) -> o.ext[id] ?: v })

    /** 컴퓨터와 폰의 규칙이 다른 것: [NOTES](노트 폴더)와 외부 폴더 ID. 다르면 그 폴더는 멈춘다 */
    fun mismatched(pc: Rules, eff: Rules): Set<String> {
        val out = HashSet<String>()
        if (pc.direction != eff.direction || pc.include != eff.include) out.add(NOTES)
        pc.ext.forEach { (id, v) -> if (eff.ext[id] != v) out.add(id) }
        return out
    }

    /** 다른 폴더의 Syncthing 폴더 ID(노트 폴더는 정책의 vault 로 계산) */
    fun blockedFolderIds(vault: String, bad: Set<String>): Set<String> =
        bad.map { if (it == NOTES) ForkSync.folderId(vault, "markdown", "markdown") else it }.toSet()

    /** 정책 JSON 에 폰의 규칙을 덮어쓴 복사본(방향·함께 보낼 것·외부 폴더 방향·옛 형식 modules 까지). 폰의 Syncthing 폴더 종류와 가림 목록을 이 값으로 정한다 */
    fun overlay(pol: JSONObject, eff: Rules): JSONObject {
        val c = JSONObject(pol.toString())
        val sm = c.optJSONObject("simple") ?: return c
        sm.put("direction", eff.direction)
        sm.put("include", JSONObject().also { j -> KEYS.forEach { j.put(it, eff.include[it] == true) } })
        c.optJSONObject("modules")?.let { mods ->
            mods.optJSONObject("markdown")?.put("direction", eff.direction)
            for (k in KEYS) mods.optJSONObject(k)?.put("direction", if (eff.include[k] == true) eff.direction else "off")
            for ((id, mid) in extModules(c)) eff.ext[id]?.let { mods.getJSONObject(mid).put("direction", it) }
        }
        return c
    }

    /** 이 폰에서 노트 폴더의 방향·함께 보낼 것을 고친다(컴퓨터는 바뀌지 않는다: 다르면 그 폴더가 멈춘다). 바뀐 것이 있으면 적고 true */
    fun change(vaultPath: String, pc: Rules, direction: String? = null, key: String? = null, on: Boolean? = null): Boolean {
        val o = seed(vaultPath, pc)
        val cur = effective(pc, o)
        var dir = o.direction
        val inc = o.include.toMutableMap()
        if (direction != null && direction in DIRECTIONS) dir = direction
        if (key != null && on != null) inc[key] = on
        val next = o.copy(direction = dir, include = inc)
        if (effective(pc, next) == cur) return false
        writeOver(vaultPath, next)
        return true
    }

    /** 이 폰에서 외부 폴더 하나의 방향을 고친다(컴퓨터는 바뀌지 않는다: 다르면 그 폴더가 멈춘다) */
    fun changeExt(vaultPath: String, pc: Rules, folderId: String, direction: String): Boolean {
        if (direction !in DIRECTIONS || folderId !in pc.ext) return false
        val o = seed(vaultPath, pc)
        val ext = o.ext.toMutableMap()
        ext[folderId] = direction
        val next = o.copy(ext = ext)
        if (effective(pc, next) == effective(pc, o)) return false
        writeOver(vaultPath, next)
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
