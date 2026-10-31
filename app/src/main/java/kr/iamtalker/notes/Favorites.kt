package kr.iamtalker.notes

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 즐겨찾기: 노트 파일은 건드리지 않고 설정 폴더(컴퓨터와 항상 양방향으로 맞추는 작은 폴더)의 작은 파일에 적는다(컴퓨터 앱 favorites.py 와 같은 규칙).
 *  충돌이 없도록 폰은 favorites-phone.json 만 쓰고 컴퓨터가 쓴 favorites-pc.json 은 읽기만 한다. 읽을 때는 둘을 합쳐 노트마다 더 최근에 바꾼 쪽을 따른다.
 *  노트 경로는 노트 폴더 기준 `폴더/이름.md`. 이름을 바꾸거나 옮기면 그 노트의 즐겨찾기는 풀린다. */
object Favorites {
    const val OWN = "favorites-phone.json"
    const val OTHER = "favorites-pc.json"

    fun relOf(folder: String, name: String): String = if (folder.isEmpty()) name else "$folder/$name"

    fun relOf(n: Note): String = relOf(n.folder, n.name)

    /** 여러 {경로: (값, 시각)} 을 합친다: 경로마다 시각이 가장 늦은 것 */
    fun merge(vararg maps: Map<String, Pair<Boolean, Long>>): Map<String, Boolean> {
        val best = HashMap<String, Pair<Boolean, Long>>()
        for (m in maps) for ((k, v) in m) if (best[k]?.second?.let { v.second > it } != false) best[k] = v
        return best.mapValues { it.value.first }
    }

    fun read(f: File): Map<String, Pair<Boolean, Long>> = runCatching {
        val items = JSONObject(f.readText()).optJSONObject("items") ?: return emptyMap()
        val out = HashMap<String, Pair<Boolean, Long>>()
        for (k in items.keys()) {
            val a = items.optJSONArray(k) ?: continue
            if (a.length() >= 2) out[k] = (a.optInt(0) != 0) to a.optLong(1)
        }
        out
    }.getOrDefault(emptyMap())

    /** 합친 상태 {노트 경로: 즐겨찾기인가} */
    fun load(vaultPath: String): Map<String, Boolean> {
        val d = SyncSettings.dir(vaultPath)
        return merge(read(File(d, OWN)), read(File(d, OTHER)))
    }

    /** 즐겨찾기를 켜거나 끈다(내 파일에만 적는다) */
    fun set(vaultPath: String, rel: String, on: Boolean, now: Long = System.currentTimeMillis()) {
        val d = SyncSettings.dir(vaultPath).apply { mkdirs() }
        val own = File(d, OWN)
        val items = JSONObject()
        read(own).forEach { (k, v) -> items.put(k, JSONArray().put(if (v.first) 1 else 0).put(v.second)) }
        items.put(rel, JSONArray().put(if (on) 1 else 0).put(now))
        val tmp = File(d, "$OWN.tmp")
        tmp.writeText(JSONObject().put("items", items).toString())
        if (!tmp.renameTo(own)) { own.writeText(JSONObject().put("items", items).toString()); tmp.delete() }
    }
}
