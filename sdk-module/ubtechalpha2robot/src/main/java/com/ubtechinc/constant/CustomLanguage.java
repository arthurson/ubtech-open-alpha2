package com.ubtechinc.constant;

/**
 * Optional language selector accepted by {@code Alpha2RobotApi.initSpeechApi(...)}.
 *
 * <p>On this firmware the active offline engine (Nuance VoCon) uses its own bundled
 * grammar, so the selection is effectively advisory. {@link #DEFAULT_LANGUAGE} is the
 * safe default. (The {@code UKRANIAN} constant was misspelled in the original SDK and
 * is corrected to {@code UKRAINIAN} here.)
 */
public enum CustomLanguage {
   DEFAULT_LANGUAGE,
   EUROPEAN_FRENCH,
   JAPANESE,
   GERMAN,
   EUROPEAN_SPANISH,
   NORWEGIAN,
   SWEDISH,
   AUSTRALIAN_ENGLISH,
   POLISH,
   BRAZILIAN_PORTUGUESE,
   EUROPEAN_PORTUGUESE,
   CHINESE_MANDARIN,
   FINNISH,
   DANISH,
   DUTCH,
   TAIWANESE_MANDARIN,
   UKRAINIAN,
   LATIN_AMERICAN_SPANISH,
   KOREAN,
   ITALIAN,
   CANADIAN_FRENCH,
   UNITED_STATES_ENGLISH,
   GREEK,
   RUSSIAN,
   CHINESE_CANTONESE,
   CANTONESE_SIMPLIFIED,
   BRITISH_ENGLISH,
   TURKISH,
   GULF_ARABIC,
   INDONESIAN_ENGLISH,
   BULGARIAN,
   CZECH,
   THAI,
   UNSPECIFIED
}
