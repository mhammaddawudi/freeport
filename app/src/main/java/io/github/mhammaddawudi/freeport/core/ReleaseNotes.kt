package io.github.mhammaddawudi.freeport.core

/**
 * The few lines of a release's notes the update dialog shows.
 *
 * A release body may hold one line per language, each led by its tag —
 * `fa: …`, `en: …`, `ckb: …`, `kmr: …`. The line in the app's language is
 * shown, else the English one, else the untagged text. Markdown marks are
 * dropped and the result is cut to [MAX_CHARS], since the dialog is a
 * glance, not the changelog.
 */
object ReleaseNotes {

    const val MAX_CHARS = 220
    private val TAGGED = Regex("""^\s*([a-z]{2,3})\s*:\s*(.+)$""")
    private val MARKS = Regex("""[*_`#>]+|\[([^\]]*)]\([^)]*\)""")

    fun summary(body: String, language: String): String {
        val lines = body.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val tagged = lines.mapNotNull { line -> TAGGED.find(line)?.let { it.groupValues[1] to it.groupValues[2] } }.toMap()
        val chosen = tagged[language]
            ?: tagged["en"]
            ?: lines.filterNot { TAGGED.matches(it) }.joinToString(" ")
        return shorten(clean(chosen))
    }

    private fun clean(text: String): String =
        text.replace(MARKS) { match -> match.groupValues.getOrNull(1).orEmpty() }
            .replace(Regex("""^\s*[-•]\s*"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun shorten(text: String): String {
        if (text.length <= MAX_CHARS) return text
        val cut = text.take(MAX_CHARS)
        val end = maxOf(cut.lastIndexOf(". "), cut.lastIndexOf("، "), cut.lastIndexOf(' '))
        return (if (end > MAX_CHARS / 2) cut.take(end + 1) else cut).trimEnd(' ', ',', '،') + "…"
    }
}
