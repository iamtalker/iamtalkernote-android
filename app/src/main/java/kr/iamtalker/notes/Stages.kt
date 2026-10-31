package kr.iamtalker.notes

import org.json.JSONObject

/** 최근부터 단계별로 받기. 컴퓨터가 만든 .sync/stages.json(단계마다 파일 목록, 1단계가 가장 최근)을 읽어
 *  아직 받을 차례가 아닌 단계의 파일을 Syncthing 무시 목록에 올려 두고, 한 단계가 다 받아지면 다음 단계를 연다.
 *  규칙은 컴퓨터 앱의 stages.py 와 같다. */
object Stages {
    const val FILE = ".sync/stages.json"

    /** 단계 목록이 오기 전에 가려 두는 목록: 정책·단계 목록이 든 .sync 만 받는다(처음 연결 직후 전부 받기 시작하지 않게) */
    val GATE = listOf("!/.sync", "!/.sync/**", "*")

    /** stages.json 글 → 단계마다의 파일(상대 경로) 목록. 읽을 수 없으면 null */
    fun parse(text: String): List<List<String>>? = runCatching {
        val o = JSONObject(text)
        val a = o.getJSONArray("tiers")
        (0 until a.length()).map { i ->
            val f = a.getJSONObject(i).getJSONArray("files")
            (0 until f.length()).map { f.getString(it) }
        }
    }.getOrNull()

    /** 무시 패턴에서 뜻이 있는 글자(* ? [ ] { } 와 역슬래시)는 '아무 글자 하나'(?)로 바꿔 쓴다. 윈도의 Syncthing 은 역슬래시 이스케이프를 못 쓰므로 두 기기에서 같게 되는 방법이다.
     *  같은 모양의 다른 파일이 같이 가려질 수 있지만, 마지막 단계가 열리면 모두 풀린다 */
    fun escape(rel: String): String = "/" + rel.replace(Regex("[\\*?\\[\\]{}\\\\�]"), "?")

    /** released 단계까지 열었을 때(1이면 1단계만) 가려야 할 파일들 */
    fun hidden(tiers: List<List<String>>, released: Int): List<String> =
        tiers.drop(released.coerceAtLeast(0)).flatMap { t -> t.map(::escape) }

    /** 지금 단계가 다 받아진 것처럼 보이는가: 놀고 있고(idle), 더 받을 게 없고, 컴퓨터 목록을 이미 받았고(전체 용량 > 0), 컴퓨터와 연결 중.
     *  연결 직후 컴퓨터의 목록이 오기 전에도 '받을 것 없음'으로 보이므로 연결·목록 조건을 함께 본다 */
    fun looksDone(state: String, needBytes: Long, totalBytes: Long, connected: Boolean): Boolean =
        state == "idle" && needBytes == 0L && totalBytes > 0 && connected

    /** looksDone 이 holdMs 이상 이어졌을 때만 다음 단계로. since 는 처음 done 으로 보인 시각(0 이면 아직), 돌려주는 값은 (열린 단계 수, 새 since) */
    fun advance(released: Int, total: Int, done: Boolean, since: Long, now: Long, holdMs: Long = 20_000): Pair<Int, Long> = when {
        released >= total || !done -> released to 0L
        since == 0L -> released to now
        now - since >= holdMs -> (released + 1) to 0L
        else -> released to since
    }
}
