package com.raahi.app.core.voice

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.Normalizer

/**
 * On-device voice command matcher (Urdu script, Roman Urdu, English). Pure Kotlin: no network, ~1 ms.
 *
 * Vocabulary: `assets/voice_commands.json` (one file, shared with the backend tests
 * `tests/test_app_commands.py`, whose `server/voice/app_commands.py` is the reference implementation of this
 * class - keep both in step).
 *
 * Matching: normalise the text, strip the wake word ("Raahi"), then the command whose phrase is the LONGEST
 * match wins ("accessibility settings" beats "settings"). Phrases shorter than [MIN_FUZZY_LEN] letters must
 * match whole words exactly; longer phrases also match with small typos.
 */
class VoiceCommands(json: String) {
    private val wake: List<String>
    private val commands: List<Pair<String, List<String>>>

    init {
        val root = Json.parseToJsonElement(json).jsonObject
        wake = root["wake_words"]!!.jsonArray.map { normalise(it.jsonPrimitive.content) }.filter { it.isNotEmpty() }
        commands = root["commands"]!!.jsonArray.map { el ->
            val o = el.jsonObject
            o["id"]!!.jsonPrimitive.content to
                o["phrases"]!!.jsonArray.map { normalise(it.jsonPrimitive.content) }.filter { it.isNotEmpty() }
        }
    }

    val ids: List<String> get() = commands.map { it.first }

    /**
     * Command id, [UNKNOWN] (wake word but nothing recognised), [WAKE_ONLY] (just "Raahi"),
     * or null (no wake word while [requireWake] -> not meant for us, ignore).
     */
    fun match(text: String, requireWake: Boolean): String? {
        val norm = normalise(text)
        if (norm.isEmpty()) return null
        val (found, rest) = stripWake(norm)
        if (!found && requireWake) return null
        if (found && rest.isEmpty()) return WAKE_ONLY
        return matchCommand(if (found) rest else norm)
    }

    /** True when [text] starts with the wake word (used to react to partial transcripts early). */
    fun hasWake(text: String): Boolean = stripWake(normalise(text)).first

    private fun stripWake(norm: String): Pair<Boolean, String> {
        val toks = norm.split(' ').filter { it.isNotEmpty() }
        for ((i, tok) in toks.take(3).withIndex()) {
            for (w in wake) {
                if (tok == w || (tok.length > 3 && ratio(tok, w) >= WAKE_RATIO)) {
                    var rest = toks.drop(i + 1)
                    if (rest.isNotEmpty() && (rest[0] == "ji" || rest[0] == "جی")) rest = rest.drop(1)
                    return true to rest.joinToString(" ")
                }
            }
        }
        return false to norm
    }

    private fun phraseScore(text: String, toks: List<String>, phrase: String): Int {
        if (" $text ".contains(" $phrase ")) return phrase.length
        if (phrase.length < MIN_FUZZY_LEN) return 0
        val n = phrase.split(' ').size
        for (i in 0..(toks.size - n)) {
            if (ratio(toks.subList(i, i + n).joinToString(" "), phrase) >= PHRASE_RATIO) {
                return phrase.length - 1 // an exact match of equal length wins ties
            }
        }
        return 0
    }

    private fun matchCommand(norm: String): String {
        val toks = norm.split(' ').filter { it.isNotEmpty() }
        var bestId = UNKNOWN
        var best = 0
        for ((id, phrases) in commands) {
            for (p in phrases) {
                val s = phraseScore(norm, toks, p)
                if (s > best) {
                    bestId = id
                    best = s
                }
            }
        }
        return bestId
    }

    companion object {
        const val UNKNOWN = "unknown"
        const val WAKE_ONLY = "wake_only"
        const val WAKE_RATIO = 0.80
        const val PHRASE_RATIO = 0.85
        const val MIN_FUZZY_LEN = 5

        private val MAP = mapOf(
            'ي' to 'ی', 'ى' to 'ی', 'ې' to 'ی', 'ئ' to 'ی', 'ك' to 'ک', 'ە' to 'ہ', 'ة' to 'ہ',
            'ۀ' to 'ہ', 'ۂ' to 'ہ', 'ۃ' to 'ہ', 'أ' to 'ا', 'إ' to 'ا', 'ؤ' to 'و',
        )

        private fun isMark(ch: Char): Boolean =
            ch in 'ً'..'ٟ' || ch == 'ٰ' || ch in 'ۖ'..'ۭ'

        /** Same steps as `normalise()` in server/voice/app_commands.py. */
        fun normalise(text: String): String {
            val nfc = Normalizer.normalize(text, Normalizer.Form.NFC)
            val sb = StringBuilder(nfc.length)
            for (raw in nfc) {
                val ch = MAP[raw] ?: raw
                when {
                    isMark(ch) || ch == '‍' -> Unit
                    ch == '‌' -> sb.append(' ')
                    Character.isLetterOrDigit(ch) -> sb.append(ch.lowercaseChar())
                    else -> sb.append(' ')
                }
            }
            // "dhooondo" -> "dhoondo" (3+ repeats -> 2), then collapse spaces
            val collapsed = StringBuilder(sb.length)
            for (ch in sb) {
                val n = collapsed.length
                if (n >= 2 && collapsed[n - 1] == ch && collapsed[n - 2] == ch) continue
                collapsed.append(ch)
            }
            return collapsed.toString().split(' ').filter { it.isNotEmpty() }.joinToString(" ")
        }

        /** 1 - levenshtein / max length. */
        fun ratio(a: String, b: String): Double {
            if (a == b) return 1.0
            if (a.isEmpty() || b.isEmpty()) return 0.0
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1)
                cur[0] = i
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                }
                prev = cur
            }
            return 1.0 - prev[b.length].toDouble() / maxOf(a.length, b.length)
        }
    }
}
