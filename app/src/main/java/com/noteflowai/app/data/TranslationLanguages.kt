package com.noteflowai.app.data

import com.google.mlkit.nl.translate.TranslateLanguage
import com.noteflowai.app.R

/**
 * Single source of truth for the translation languages offered in Notes + Scan.
 *
 * - [code] is the canonical lowercase id used by the app (e.g. "french").
 * - [displayRes] is the user-visible label string resource.
 * - [nllb] is the FLORES-200 code consumed by the NLLB-200 (facebook/nllb-200-distilled-1.3B) model.
 * - [mlKit] is the ML Kit [TranslateLanguage] code used as the offline fallback.
 *   It is null when ML Kit does not support the language, in which case only the
 *   NLLB model is used.
 */
data class TLang(
    val code: String,
    val displayRes: Int,
    val nllb: String,
    val mlKit: String? = null
)

val TRANSLATION_LANGS = listOf(
    TLang("english",   R.string.tl_english,   "eng_Latn"),
    TLang("chinese",   R.string.tl_chinese,   "zho_Hans"),
    TLang("japanese",  R.string.tl_japanese,  "jpn_Jpan"),
    TLang("korean",    R.string.tl_korean,    "kor_Hang"),
    TLang("malay",     R.string.tl_malay,     "zsm_Latn", TranslateLanguage.MALAY),
    TLang("french",    R.string.tl_french,    "fra_Latn", TranslateLanguage.FRENCH),
    TLang("german",    R.string.tl_german,    "deu_Latn", TranslateLanguage.GERMAN),
    TLang("spanish",   R.string.tl_spanish,   "spa_Latn", TranslateLanguage.SPANISH),
    TLang("portuguese",R.string.tl_portuguese,"por_Latn", TranslateLanguage.PORTUGUESE),
    TLang("russian",   R.string.tl_russian,   "rus_Cyrl", TranslateLanguage.RUSSIAN),
    TLang("hindi",     R.string.tl_hindi,     "hin_Deva", TranslateLanguage.HINDI),
    TLang("thai",      R.string.tl_thai,      "tha_Thai", TranslateLanguage.THAI),
    TLang("vietnamese",R.string.tl_vietnamese,"vie_Latn", TranslateLanguage.VIETNAMESE),
    TLang("turkish",   R.string.tl_turkish,   "tur_Latn", TranslateLanguage.TURKISH),
    TLang("italian",   R.string.tl_italian,   "ita_Latn", TranslateLanguage.ITALIAN),
    TLang("arabic",    R.string.tl_arabic,    "ara_Arab", TranslateLanguage.ARABIC),
    TLang("indonesian",R.string.tl_indonesian,"ind_Latn", TranslateLanguage.INDONESIAN),
    TLang("filipino",  R.string.tl_filipino,  "tgl_Latn"),
    TLang("dutch",     R.string.tl_dutch,     "nld_Latn"),
    TLang("polish",     R.string.tl_polish,     "pol_Latn"),
    TLang("ukrainian", R.string.tl_ukrainian, "ukr_Cyrl"),
    TLang("czech",      R.string.tl_czech,      "ces_Latn"),
    TLang("swedish",   R.string.tl_swedish,   "swe_Latn"),
    TLang("greek",      R.string.tl_greek,      "ell_Grek"),
    TLang("romanian",   R.string.tl_romanian,   "ron_Latn")
)

/** Resolve the FLORES-200 code for an app language [code] (defaults to English). */
fun nllbCodeFor(code: String): String {
    return TRANSLATION_LANGS.firstOrNull { it.code == code }?.nllb ?: "eng_Latn"
}

// ML Kit language-detection short codes -> canonical app language code.
private val ML_KIT_TO_APP = mapOf(
    "en" to "english", "zh" to "chinese", "ja" to "japanese", "ko" to "korean",
    "ms" to "malay", "fr" to "french", "de" to "german", "es" to "spanish",
    "pt" to "portuguese", "ru" to "russian", "hi" to "hindi", "th" to "thai",
    "vi" to "vietnamese", "tr" to "turkish", "it" to "italian", "ar" to "arabic",
    "id" to "indonesian", "tl" to "filipino", "nl" to "dutch", "pl" to "polish",
    "uk" to "ukrainian", "cs" to "czech", "sv" to "swedish", "el" to "greek",
    "he" to "hebrew", "ro" to "romanian"
)

/** Normalize a possibly-ML-Kit short code (e.g. "en") to a canonical app code. */
fun normalizeLangCode(code: String): String {
    return ML_KIT_TO_APP[code] ?: code
}

/** Resolve the ML Kit code for an app language [code], or null if unsupported. */
fun mlKitCodeFor(code: String): String? {
    return TRANSLATION_LANGS.firstOrNull { it.code == code }?.mlKit
}
