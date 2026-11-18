package com.agentos.agent

/**
 * Secrets the model must never see: one-time codes and the contents of password fields. Before
 * any text goes to a model it passes [redact], which swaps each secret for a stable token
 * (`⟦otp:1⟧`, `⟦pw:1⟧`; the same value always gets the same token in a session). `type_text`
 * accepts tokens and [resolve]s them on the phone, so the agent can still enter a code it was
 * texted without the code leaving the device. Values live in memory only, never on disk.
 */
class Secrets {
    private val byValue = LinkedHashMap<String, String>()
    private val byToken = HashMap<String, String>()

    @Synchronized private fun token(kind: String, value: String): String = byValue.getOrPut("$kind:$value") {
        val t = "⟦$kind:${byValue.keys.count { it.startsWith("$kind:") } + 1}⟧"
        byToken[t] = value
        t
    }

    /** [text] with every secret replaced by its token. */
    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var out = text
        // Password fields: ScreenSnapshot flags them; the quoted text on such a line is the secret.
        if ("password" in out) out = PASSWORD_LINE.replace(out) { m ->
            val v = m.groupValues[2]
            if (v.isBlank() || v.all { it == '•' || it == '*' || it == '·' }) m.value
            else m.groupValues[1] + "\"" + token("pw", v) + "\"" + m.groupValues[3]
        }
        // One-time codes: 4-8 digits (maybe split by a space or dash) close to a word that says "code".
        out = OTP.replace(out) { m ->
            val code = m.groups["code"]!!
            val digits = code.value.filter { it.isDigit() }
            if (digits.length !in 4..8 || YEAR.matches(digits)) m.value
            else m.value.replaceRange(code.range.first - m.range.first, code.range.last - m.range.first + 1, token("otp", digits))
        }
        out = OTP_AFTER.replace(out) { m ->
            val code = m.groups["code"]!!
            val digits = code.value.filter { it.isDigit() }
            if (digits.length !in 4..8 || YEAR.matches(digits)) m.value
            else m.value.replaceRange(code.range.first - m.range.first, code.range.last - m.range.first + 1, token("otp", digits))
        }
        return out
    }

    /** [text] with tokens replaced by their values (for typing on the phone). Unknown tokens stay as they are. */
    @Synchronized fun resolve(text: String): String = TOKEN.replace(text) { byToken[it.value] ?: it.value }

    fun hasToken(text: String) = TOKEN.containsMatchIn(text)

    companion object {
        val TOKEN = Regex("⟦(?:otp|pw):\\d+⟧")
        private const val KEY = "(?:code|otp|passcode|pass code|verification|verify|one[- ]time|2fa|two[- ]factor|security|login|sign[- ]in|pin|token|验证码|código|codice)"
        // "Your verification code is 123456", "G-482913 is your Google code", "code: 12 34 56".
        private val OTP = Regex("(?i)\\b$KEY[^\\n\\d]{0,40}?(?<code>(?<![\\d.,:/$€£])(?:G-)?\\d{2,4}[ -]?\\d{2,4}(?![\\d,:/%]|\\.\\d))")
        private val OTP_AFTER = Regex("(?i)(?<code>(?<![\\d.,:/$€£])(?:G-)?\\d{4,8}(?![\\d,:/%]|\\.\\d))[^\\n\\d]{0,30}?(?:is your|is the)[^\\n]{0,30}?\\b$KEY")
        private val YEAR = Regex("(19|20)\\d\\d")
        private val PASSWORD_LINE = Regex("(\\[\\d+] \\S+ )\"([^\"]*)\"([^\\n]*\\bpassword\\b[^\\n]*)")
    }
}
