package com.sasayaki.player.data.scan

import java.text.Normalizer

/** Text heuristics for the file and folder names found in Japanese ASMR releases. */
object Naming {

    fun nfkc(s: String): String = Normalizer.normalize(s, Normalizer.Form.NFKC)

    fun norm(s: String): String = nfkc(s).lowercase().replace(Regex("\\s+"), " ").trim()

    fun stem(fileName: String): String = fileName.substringBeforeLast('.', fileName)

    /** Natural order: digit runs compare numerically, full-width digits included. */
    val natural: Comparator<String> = Comparator { a, b -> compareNatural(a, b) }

    fun compareNatural(a: String, b: String): Int {
        val x = nfkc(a).lowercase()
        val y = nfkc(b).lowercase()
        var i = 0
        var j = 0
        while (i < x.length && j < y.length) {
            val cx = x[i]
            val cy = y[j]
            if (cx.isDigit() && cy.isDigit()) {
                var ei = i
                while (ei < x.length && x[ei].isDigit()) ei++
                var ej = j
                while (ej < y.length && y[ej].isDigit()) ej++
                val nx = x.substring(i, ei).trimStart('0')
                val ny = y.substring(j, ej).trimStart('0')
                if (nx.length != ny.length) return nx.length - ny.length
                val c = nx.compareTo(ny)
                if (c != 0) return c
                // Same value: fewer leading zeros first, keeps ordering total.
                val z = (ei - i) - (ej - j)
                if (z != 0) return z
                i = ei
                j = ej
            } else {
                if (cx != cy) {
                    // Digits sort before letters so "EP06" < "EPILOGUE".
                    if (cx.isDigit()) return -1
                    if (cy.isDigit()) return 1
                    return cx.compareTo(cy)
                }
                i++
                j++
            }
        }
        return (x.length - i) - (y.length - j)
    }

    private val ORDINAL = Regex(
        "^\\s*(?:disc|cd|track|tr|ep|sv|#|no\\.?|トラック)?\\s*(\\d{1,4})([a-z])?(?:\\s*[._\\-、,:：)\\]】）」\\s]+|(?=[^\\da-z])|$)",
        RegexOption.IGNORE_CASE,
    )
    private val EMBEDDED_NUMBER = Regex(
        "(?:^|[_\\s\\-(\\[【（])(?:シーン|scene|part|パート|トラック|track|tr|t|ep|#|第)\\s*0*(\\d{1,3})(?=[_\\s.\\-)\\]】）話]|$)",
        RegexOption.IGNORE_CASE,
    )

    /** Removes a leading ordinal such as "01_", "1.", "２．", "SV01_", "04r_". */
    fun stripOrdinal(name: String): String {
        val s = nfkc(name)
        val m = ORDINAL.find(s) ?: return name.trim()
        val rest = s.substring(m.range.last + 1).trim()
        return rest.ifEmpty { name.trim() }
    }

    fun hasOrdinal(name: String): Boolean = ORDINAL.find(nfkc(name)) != null

    /** Track key used to line up the same track across versions: "4", "4r", ... */
    fun trackKey(fileName: String): String? {
        val s = nfkc(stem(fileName)).lowercase()
        ORDINAL.find(s)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return null
            return n.toString() + m.groupValues[2]
        }
        EMBEDDED_NUMBER.find(s)?.let { m -> return m.groupValues[1].toIntOrNull()?.toString() }
        return null
    }

    /** Words that mark a name fragment as describing a variant rather than content. */
    private val VARIANT_WORD = Regex(
        "なし|無し|あり|有り|付き|のみ|抜き|反転|バージョン|効果音|汁音|ノイズ|左右|バイノーラル|ダミーヘッド|ハイレゾ|" +
            "(?<![a-z])(?:se|bgm|ver|version|without|with|only|noise|binaural|mono|stereo|wav|mp3|flac|kbps|khz)(?![a-z])",
        RegexOption.IGNORE_CASE,
    )
    private val BRACKETS = Regex("[(\\[（【「『〔]([^()\\[\\]（）【】「」『』〔〕]*)[)\\]）】」』〕]")

    class Parsed(val core: String, val tags: Set<String>, val displayTags: List<String>)

    /**
     * Splits a track name into its content [Parsed.core] and the variant [Parsed.tags]
     * (e.g. "05_お姉様は四葉だけのもの。_汁音のみ" -> core "お姉様は四葉だけのもの。", tags {"汁音のみ"}).
     */
    fun parse(fileName: String): Parsed {
        var s = stripOrdinal(nfkc(stem(fileName)))
        val tags = sortedSetOf<String>()
        val display = ArrayList<String>()
        fun addTag(t: String) {
            if (tags.add(normTag(t))) display += t.trim()
        }
        s = BRACKETS.replace(s) { m ->
            val inner = m.groupValues[1].trim()
            if (VARIANT_WORD.containsMatchIn(inner)) {
                addTag(inner); ""
            } else m.value
        }
        val tokens = s.split('_')
        val kept = ArrayList<String>()
        for ((i, t) in tokens.withIndex()) {
            val tt = t.trim().trimEnd('.')
            if (i > 0 && tt.isNotEmpty() && VARIANT_WORD.containsMatchIn(tt) && tt.length <= 24) {
                addTag(tt)
            } else kept += t
        }
        var core = kept.joinToString("_").trim().trimEnd('.', '_', ' ')
        core = core.replace(Regex("(?i)(ver\\.?|version|バージョン)$"), "").trim().trimEnd('_', ' ', '.')
        return Parsed(norm(core), tags, display)
    }

    private fun normTag(t: String): String =
        norm(t).replace("無し", "なし").replace("有り", "あり").replace(Regex("(?i)ver\\.?$"), "").trim()

    fun similarCore(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return a == b
        return a == b || a.contains(b) || b.contains(a)
    }

    private val FORMAT_SEGMENT = Regex(
        "^(mp3|wav|flac|m4a|aac|ogg|opus|hi-?res|ハイレゾ)(\\s*(版|ファイル|形式|音源|データ|ver\\.?|バージョン|音声))?(\\s*[(（].*[)）])?$",
        RegexOption.IGNORE_CASE,
    )

    fun isFormatSegment(segment: String): Boolean = FORMAT_SEGMENT.matches(norm(stripOrdinal(segment)))

    private val FORMAT_SUFFIX = Regex(
        "[\\s_.\\-]*(mp3|wav|flac|m4a|aac|ogg|opus)(\\s*(版|ファイル|形式|音源|データ|ver\\.?))?$",
        RegexOption.IGNORE_CASE,
    )

    /** Human label for a version located at [relPath] inside an entry. */
    fun versionLabel(relPath: String): String {
        val parts = relPath.split('/').filter { it.isNotEmpty() }
        return parts.filterNot { isFormatSegment(it) }
            .map { stripOrdinal(it) }
            // "本編mp3" -> "本編", "寝息mp3" -> "寝息"
            .map { seg -> FORMAT_SUFFIX.replace(seg, "").trim().ifEmpty { seg } }
            .joinToString(" › ")
    }

    fun tagIsRedundant(tag: String, label: String): Boolean {
        val t = normTag(tag)
        val l = norm(label).replace("無し", "なし").replace("有り", "あり")
        return t.isNotEmpty() && l.contains(t)
    }

    private val BONUS = Regex("おまけ|オマケ|特典|付録|(?<![a-z])(bonus|extra|omake)(?![a-z])", RegexOption.IGNORE_CASE)
    private val MAIN_PART = Regex("本編|(?<![a-z])(main|honpen)(?![a-z])", RegexOption.IGNORE_CASE)

    /** 0 = main content, 1 = neutral, 2 = bonus material. */
    fun sectionRank(segment: String): Int = when {
        segment.isEmpty() -> 0
        MAIN_PART.containsMatchIn(nfkc(segment)) -> 0
        BONUS.containsMatchIn(nfkc(segment)) -> 2
        else -> 1
    }

    private val STRUCTURAL = Regex(
        "ハイレゾ|本編|おまけ|オマケ|特典|効果音|音声|トラック|パート|バージョン|イラスト|画像|ジャケット|壁紙|台本|" +
            "シナリオ|ビジュアル|データ|左右|反転|バイノーラル|ボイス|あり|なし|無し|有り|のみ|抜き|付き|ノイズ|漫画|" +
            "小説|テキスト|ループ|寝息|単独|全体|通常|ノーマル|" +
            "(?<![a-z])(?:mp3|wav|flac|m4a|ogg|opus|aac|hi-?res|bonus|extra|bgm|voice|audio|tracks?|parts?|ver|" +
            "version|illust|illustrations?|images?|art|artwork|jacket|wallpapers?|script|scenario|visual|readme|" +
            "data|binaural|mix|disc|cd|se|cg|manga|novel|text|pdf|loop|normal)(?![a-z])",
        RegexOption.IGNORE_CASE,
    )

    /** Whether a sub-folder name looks like part of a single work's layout (vs. a separate work). */
    fun isStructural(folderName: String): Boolean {
        if (hasOrdinal(folderName)) return true
        val n = norm(folderName)
        return n.length <= 24 && STRUCTURAL.containsMatchIn(n)
    }

    private val RJ = Regex("(?i)\\b((?:RJ|BJ|VJ|RE)\\d{6,8})\\b")

    fun rjCode(name: String): String? = RJ.find(nfkc(name))?.groupValues?.get(1)?.uppercase()

    /** Display title for an entry folder or archive. */
    fun entryTitle(name: String): String {
        var t = name.trim()
        if (t.lowercase().endsWith(".zip")) t = t.dropLast(4).trim()
        return t
    }

    /** Pretty track title: file stem without the leading ordinal noise. */
    fun trackTitle(fileName: String): String = stem(fileName).trim()

    private val NEGATIVE = Regex("なし|無し|抜き|のみ|反転|without|only|no ?se|off", RegexOption.IGNORE_CASE)

    fun isNegativeVariant(label: String): Boolean = NEGATIVE.containsMatchIn(nfkc(label))
}
