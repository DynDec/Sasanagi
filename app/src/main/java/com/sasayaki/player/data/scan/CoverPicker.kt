package com.sasayaki.player.data.scan

import com.sasayaki.player.data.Ref

/** Picks the illustration to represent an entry, preferring the plain (logo/text-free) one. */
object CoverPicker {
    class Candidate(val relPath: String, val ref: Ref)

    private val PLAIN = Regex(
        "(ロゴ|文字|タイトル|テキスト|題字|クレジット)\\s*(なし|無し|無|抜き)|イラストのみ|絵のみ|" +
            "(?<![a-z])(no[\\s_-]?logo|nologo|no[\\s_-]?text|notext|textless|no[\\s_-]?title|plain|clean)(?![a-z])",
        RegexOption.IGNORE_CASE,
    )
    private val MARKED = Regex(
        "(ロゴ|文字|タイトル|テキスト|題字|クレジット)\\s*(あり|有り|有|入り|付き)|" +
            "(?<![a-z])(with[\\s_-]?logo|logo|title)(?![a-z])",
        RegexOption.IGNORE_CASE,
    )
    private val MAIN = Regex(
        "メインビジュアル|キービジュアル|ジャケット|表紙|カバー|アートワーク|" +
            "(?<![a-z])(kv|key[\\s_-]?visual|main|cover|jacket|front|artwork)(?![a-z])|" +
            "^rj\\d+$|着彩|完成|オリジナルサイズ|フルサイズ|高解像度",
        RegexOption.IGNORE_CASE,
    )
    // Wallpapers are normally text-free, so they beat a generic "artwork" that may carry the logo.
    private val WALLPAPER = Regex("壁紙|(?<![a-z])wall[\\s_-]?paper(?![a-z])", RegexOption.IGNORE_CASE)
    private val WEAK = Regex(
        "アイコン|サムネ|キャラデザ|キャラクター|設定|説明|ラフ|線画|差分|おはなし|ポイント|スマートフォン|スマホ|" +
            "(?<![a-z])(icon|thumb|thumbnail|albumartsmall|rough|sketch|sp|smartphone|banner)(?![a-z])",
        RegexOption.IGNORE_CASE,
    )

    fun pick(candidates: List<Candidate>): Candidate? {
        if (candidates.isEmpty()) return null
        val stems = candidates.map { Naming.norm(Naming.stem(it.ref.fileName)) }.toSet()
        return candidates.maxWithOrNull(
            compareBy<Candidate> { score(it, stems) }
                .thenBy { it.ref.size }
                .thenByDescending { it.relPath.count { c -> c == '/' } }
                .thenComparator { a, b -> Naming.compareNatural(b.relPath, a.relPath) }
        )
    }

    private fun score(c: Candidate, stems: Set<String>): Int {
        val name = Naming.norm(Naming.stem(c.ref.fileName))
        var s = 0
        if (PLAIN.containsMatchIn(name)) s += 100
        if (MARKED.containsMatchIn(name)) s -= 60
        // "foo.png" next to "foo_ロゴあり.png" means "foo.png" is the plain one.
        if (stems.any { it != name && it.startsWith(name) && MARKED.containsMatchIn(it.removePrefix(name)) }) s += 60
        if (MAIN.containsMatchIn(name)) s += 20
        if (WALLPAPER.containsMatchIn(name)) s += 30
        if (WEAK.containsMatchIn(name)) s -= 40
        if (name == "folder") s -= 15
        if (c.ref.size in 1 until 60_000) s -= 30
        return s
    }
}
