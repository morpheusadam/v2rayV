package com.v2ray.ang.automode

/**
 * Works out which country a server claims to be in.
 *
 * There are two sources of truth and they disagree often. The remark a provider writes
 * ("🇳🇱 Netherlands", "US-01") is free but frequently marketing rather than fact. The
 * country reported by an IP lookup performed *through the tunnel* is the country the
 * user's traffic actually appears to come from, which is what anyone picking a country
 * really wants — but it only exists after the server has been tested successfully.
 *
 * So the label is used to decide what is worth testing, and the measured exit country
 * decides what is worth keeping.
 */
object CountryHint {

    // Regional indicator symbols: U+1F1E6 is 'A' … U+1F1FF is 'Z'. A flag emoji is just
    // two of them, so a country code can be read straight out of the remark with no table.
    private const val REGIONAL_INDICATOR_A = 0x1F1E6
    private const val REGIONAL_INDICATOR_Z = 0x1F1FF

    /**
     * Country names that turn up in real subscription remarks, mapped to ISO codes.
     * Deliberately short: the flag emoji and the bare code cover most lists, and a
     * wrong guess here costs a wasted test.
     */
    private val namesToCode: Map<String, String> = linkedMapOf(
        "united states" to "US", "usa" to "US", "america" to "US",
        "united kingdom" to "GB", "england" to "GB", "britain" to "GB",
        "netherlands" to "NL", "holland" to "NL",
        "germany" to "DE", "deutschland" to "DE",
        "france" to "FR", "canada" to "CA", "japan" to "JP",
        "singapore" to "SG", "korea" to "KR", "turkey" to "TR", "turkiye" to "TR",
        "iran" to "IR", "russia" to "RU", "poland" to "PL", "sweden" to "SE",
        "finland" to "FI", "norway" to "NO", "denmark" to "DK", "ireland" to "IE",
        "spain" to "ES", "italy" to "IT", "austria" to "AT", "switzerland" to "CH",
        "belgium" to "BE", "romania" to "RO", "bulgaria" to "BG", "hungary" to "HU",
        "czech" to "CZ", "ukraine" to "UA", "lithuania" to "LT", "latvia" to "LV",
        "estonia" to "EE", "moldova" to "MD", "serbia" to "RS", "croatia" to "HR",
        "greece" to "GR", "portugal" to "PT", "australia" to "AU",
        "india" to "IN", "china" to "CN", "hong kong" to "HK", "taiwan" to "TW",
        "vietnam" to "VN", "indonesia" to "ID", "malaysia" to "MY", "thailand" to "TH",
        "brazil" to "BR", "argentina" to "AR", "mexico" to "MX", "chile" to "CL",
        "emirates" to "AE", "dubai" to "AE", "israel" to "IL", "armenia" to "AM",
        "azerbaijan" to "AZ", "kazakhstan" to "KZ", "georgia" to "GE",
        "south africa" to "ZA", "egypt" to "EG", "cyprus" to "CY", "luxembourg" to "LU",
    )

    private val knownCodes: Set<String> = namesToCode.values.toSet()

    private val isoTokenRegex = Regex("(?<![A-Za-z0-9])([A-Z]{2})(?![A-Za-z0-9])")

    /**
     * A data allowance, so it can be taken out of the string before codes are looked for.
     *
     * 🔴 The token regex above used to be the only guard, on the reasoning that "digits
     * count as part of the surrounding word, so 500GB is a quota and not Great Britain".
     * That is true only while the digit actually touches the letters. Free lists write the
     * allowance with a space at least as often as without — `premium | 50 GB | 30 days` —
     * and there the lookbehind sees a space, the lookahead sees a space, and `GB` is
     * returned as a country with full confidence.
     *
     * It is the worst possible pair to get wrong: `GB` is simultaneously the commonest unit
     * on these lists and a real country code, so the mistake is silent and frequent. It
     * also fires *before* any code later in the same remark, because the scan runs
     * left to right, so `50 GB | IR` reads as Great Britain and the `IR` is never seen.
     *
     * In Iran mode that is not a cosmetic mislabel. `IranMode.labelledElsewhere` deletes
     * anything whose label points outside Iran, before it is ever tested — so a remark
     * quoting its quota in gigabytes was enough to remove a genuinely Iranian server from
     * the run.
     */
    /**
     * Separators that turn up between a number and its unit on these lists. `\s` alone is
     * not enough: the non-breaking space survives copy-paste from a channel post, and
     * hyphens, underscores and dots are all used as decoration.
     */
    private const val SEP = """[\s   ._\-–—/]*"""

    private val quotaRegex = Regex("""\d+(?:[.,]\d+)?$SEP[KMGT]B(?![A-Za-z])""", RegexOption.IGNORE_CASE)

    /**
     * A unit standing next to a word about data rather than next to a number — "unlimited
     * GB", "traffic GB". No figure to anchor on, so [quotaRegex] cannot see it.
     */
    private val unitWordRegex = Regex(
        """(?:unlimited|unlimit|traffic|data|quota|volume|vol|bandwidth|monthly)$SEP[KMGT]B(?![A-Za-z])""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * The one two-letter token that is a unit as often as it is a country.
     *
     * `TB`, `MB` and `KB` are not ISO country codes, so they never reach the token scan.
     * `GB` is both Great Britain and the unit almost every free list quotes its allowance
     * in, which is why it needs a rule of its own rather than a wider regex.
     */
    private const val AMBIGUOUS_UNIT = "GB"

    private val ipInfoRegex = Regex("\\(([A-Za-z]{2})\\)")

    /**
     * Best guess at the country a remark advertises, as an ISO code, or null.
     */
    fun fromRemark(remark: String?): String? {
        if (remark.isNullOrEmpty()) {
            return null
        }

        // A flag emoji is unambiguous, so it wins over anything spelled out.
        fromFlagEmoji(remark)?.let { return it }

        for ((name, code) in namesToCode) {
            if (remark.contains(name, ignoreCase = true)) {
                return code
            }
        }

        // A bare two-letter token, but only when it is a code we recognise — plenty of
        // remarks contain things like "GB" meaning gigabytes or "TV", and treating any
        // pair of capitals as a country produces confident nonsense.
        //
        // Allowances are removed rather than skipped over, so that a code standing later in
        // the same remark is still found: the scan used to return the first token it
        // recognised, and "50 GB | IR" has to answer IR.
        val stripped = unitWordRegex.replace(quotaRegex.replace(remark, " "), " ")
        val tokens = isoTokenRegex.findAll(stripped)
            .map { it.groupValues[1].uppercase() }
            .filter { knownCodes.contains(it) }
            .toList()

        // 🔴 GB loses to any other country in the same remark.
        //
        // Stripping the allowance only works when there is a number to strip. The forms
        // that have none — "unlimited GB", "traffic GB", "GB 500" with the unit leading —
        // still leave a bare GB standing, and being first in the string it won outright.
        // On a list where the allowance is quoted in gigabytes and the location is given
        // as a code, that is every single entry.
        //
        // Two codes in one remark is otherwise rare, so preferring the other one costs
        // almost nothing and removes the whole class. A remark whose only code is GB still
        // reads as Great Britain, which is the case the rule must not break.
        tokens.firstOrNull { it != AMBIGUOUS_UNIT }?.let { return it }
        return tokens.firstOrNull()
    }

    private fun fromFlagEmoji(text: String): String? {
        var i = 0
        while (i < text.length - 1) {
            if (!Character.isHighSurrogate(text[i])) {
                i++
                continue
            }

            val first = Character.codePointAt(text, i)
            if (first < REGIONAL_INDICATOR_A || first > REGIONAL_INDICATOR_Z) {
                i++
                continue
            }

            val next = i + 2
            if (next > text.length - 2 || !Character.isHighSurrogate(text[next])) {
                i++
                continue
            }

            val second = Character.codePointAt(text, next)
            if (second < REGIONAL_INDICATOR_A || second > REGIONAL_INDICATOR_Z) {
                i++
                continue
            }

            val a = 'A' + (first - REGIONAL_INDICATOR_A)
            val b = 'A' + (second - REGIONAL_INDICATOR_A)
            return "$a$b"
        }

        return null
    }

    /**
     * Reads the country out of the IP info recorded after a successful test, which
     * [com.v2ray.ang.handler.SpeedtestManager.getRemoteIPInfo] stores as "(NL) 1.2.3.4".
     */
    fun fromIpInfo(ipInfo: String?): String? {
        if (ipInfo.isNullOrEmpty()) {
            return null
        }
        val m = ipInfoRegex.find(ipInfo) ?: return null
        val code = m.groupValues[1].uppercase()
        return if (code == "UN") null else code
    }

    /**
     * Countries offered in the Auto Mode picker, as code to display name. Ordered by how
     * often they turn up as exit locations in public subscription lists, except for Iran.
     *
     * Iran leads because it is the one entry that does not behave like the others — it is
     * Iran mode rather than a preference, see [com.v2ray.ang.automode.IranMode.splitFilter]
     * — and an option with different rules should not be buried two rows down among the
     * ones it differs from.
     */
    val pickerOptions: List<Pair<String, String>> = listOf(
        "IR" to "Iran",
        "NL" to "Netherlands", "DE" to "Germany", "GB" to "United Kingdom",
        "US" to "United States", "FR" to "France", "FI" to "Finland",
        "SE" to "Sweden", "PL" to "Poland", "AT" to "Austria",
        "CH" to "Switzerland", "CA" to "Canada", "JP" to "Japan",
        "SG" to "Singapore", "KR" to "Korea", "TR" to "Turkey",
        "AE" to "Emirates", "RU" to "Russia", "IN" to "India",
        "AU" to "Australia", "BR" to "Brazil", "IT" to "Italy", "ES" to "Spain",
        "RO" to "Romania", "LT" to "Lithuania", "LV" to "Latvia", "EE" to "Estonia",
        "CZ" to "Czechia", "IE" to "Ireland", "HK" to "Hong Kong", "TW" to "Taiwan",
        "AM" to "Armenia", "AZ" to "Azerbaijan", "CY" to "Cyprus",
    )

    /** Parses a user-typed filter such as "NL, DE, gb" into ISO codes. */
    fun parseFilter(text: String?): MutableList<String> {
        val result = mutableListOf<String>()
        if (text.isNullOrEmpty()) {
            return result
        }

        for (part in text.split(',', ';', ' ', '\t', '\n', '\r')) {
            val token = part.trim().uppercase()
            if (token.isEmpty()) {
                continue
            }
            if (token.length == 2 && token.all { it in 'A'..'Z' }) {
                if (!result.contains(token)) {
                    result.add(token)
                }
                continue
            }

            // Also accept a spelled-out name, so "Netherlands" works as well as "NL".
            val mapped = namesToCode[token.lowercase()]
            if (mapped != null && !result.contains(mapped)) {
                result.add(mapped)
            }
        }

        return result
    }
}
