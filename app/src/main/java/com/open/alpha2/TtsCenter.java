package com.open.alpha2;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.speech.tts.Voice;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Android 系統 TTS 層：引擎綁定、讀出、語言／引擎清單。
 */
public final class TtsCenter {
    private static final String TAG = "TtsCenter";

    /** TTS 卡揀緊嘅 Android 語言 BCP-47 tag (空=沿用引擎目前
     *  語言)。前端 setAndroidTtsLang() 同步寫入，對話管線 speakAndroidTts()
     *  優先用佢——一揀即時跟，唔使等。 */
    private static final String PREF_ANDROID_TTS_LANG = "android_tts_lang";

    /** TTS 卡揀緊嘅具體聲音 (TextToSpeech.Voice.getName()，空=
     *  用引擎該語言預設聲)。Google TTS 每個語言有多把聲（男女／網絡／裝置），
     *  前端揀完語言再揀聲；呢個名綁死引擎＋語言，轉引擎／轉語言嗰陣一齊清
     *  （見 setTtsEngine/setTtsLang），唔好將舊聲套落新語言度。 */
    private static final String PREF_ANDROID_TTS_VOICE = "android_tts_voice";

    public static final int TTS_DATA_CHECK_REQUEST_CODE = 0x7454; // "T T" leetspeak-ish, 只是要一個穩定、未用過的 code

    private final Activity activity;
    private final VoskController vosk;

    public TtsCenter(Activity activity, VoskController vosk) {
        this.activity = activity;
        this.vosk = vosk;
    }

    private android.content.SharedPreferences prefs() {
        return activity.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE);
    }

    // Android system TTS (a third engine option alongside the robot's own Nuance/
    // iFlytek, used directly rather than via ISpeechInterface). No voice selection -
    // voice choice is only meaningful for iFlytek's named voices.
    // volatile: initAndroidTts() reassigns this from an HTTP worker thread when
    // switching engines, and it's read from other worker threads on every speech/tts
    // call - a plain field could let one thread see a stale/half-published reference.
    private volatile TextToSpeech androidTts;
    private volatile boolean androidTtsReady = false;
    private volatile String androidTtsEnginePkg = ""; // package of the engine androidTts is currently bound to

    // listAndroidTtsLanguages() 的 legacy fallback (SVOX Pico 沒實作
    // getVoices(), IPC 層直接 throw "NullPointerException: collection ==
    // null" - 不是回空 collection, 是完全沒實作) 用的 blocking 狀態, 見
    // checkTtsDataSyncLegacy()/onTtsDataResult() javadoc。
    private final Object ttsDataCheckLock = new Object();
    private CountDownLatch ttsDataCheckLatch;
    private volatile ArrayList<String> ttsDataCheckResult;

    public boolean isReady() {
        return androidTtsReady;
    }

    /** speech/stop 等共用：停咗佢 (唔 shutdown，留返下次用)。 */
    public void stop() {
        TextToSpeech tts = androidTts;
        if (tts != null) {
            tts.stop();
        }
    }

    /** onDestroy 共用：停＋shutdown。 */
    public void shutdown() {
        TextToSpeech tts = androidTts;
        if (tts != null) {
            tts.stop();
            tts.shutdown();
        }
    }

    /** 列出目前 androidTts 綁定的那個 engine 支援的所有語言/國家變體, 供
     *  speech/tts_languages endpoint 使用 (選了 engine=android 才顯示語言選擇)。
     *  getVoices() (API 21+) 做主要來源, 得到空清單才退回
     *  ACTION_CHECK_TTS_DATA legacy fallback - SVOX Pico 完全沒有實作
     *  getVoices(), IPC 層直接 throw "NullPointerException: collection ==
     *  null" (不是回傳空 collection, 已經用 try/catch 接住不會 crash, 但結果是空
     *  清單), Google TTS 則用 getVoices() 取得完整清單, 不需要走 legacy 這條路。
     *
     *  用 getVoices() 而不是 ACTION_CHECK_TTS_DATA 做主要來源的原因: 這台機器沒有
     *  Google Play Store, Google TTS 的 ACTION_CHECK_TTS_DATA 只能答出出廠
     *  內建的那一個國家變體 (中文只有 zh-TW, 英文只有 en-US) - getVoices() 直接問
     *  engine 自己完整的 voice metadata, 不受這個限制。 */
    private List<TtsLanguageOption> listAndroidTtsLanguages(Locale displayLocale) {
        List<TtsLanguageOption> viaVoices = checkTtsDataViaGetVoices(displayLocale);
        if (!viaVoices.isEmpty()) {
            return viaVoices;
        }
        // getVoices() 得到空清單 (engine 未 ready、丟出 exception 被接住、
        // 或者根本沒實作) - 不要就這樣把空清單給用戶, 退回舊方法再試一次。
        return checkTtsDataSyncLegacy(displayLocale);
    }

    /** 用 TextToSpeech.getVoices() 窮舉目前 androidTts 綁定的那個 engine 支援的所有
     *  voice/語言變體 - 見 listAndroidTtsLanguages() javadoc 解釋為何選這個
     *  API 做主要來源。 */
    private List<TtsLanguageOption> checkTtsDataViaGetVoices(Locale displayLocale) {
        if (androidTts == null) {
            return new ArrayList<>();
        }
        Set<Voice> voices;
        try {
            voices = androidTts.getVoices();
        } catch (Exception e) {
            // user-confirmed 有 OEM engine 會在這裡 throw NPE/IllegalStateException
            // 而不是正常回傳 null - 當作沒有資料處理, 退回 legacy 方法。
            Log.e(TAG, "androidTts.getVoices() failed", e);
            return new ArrayList<>();
        }
        if (voices == null || voices.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, TtsLanguageOption> options = new HashMap<>();
        for (Voice voice : voices) {
            Locale locale = voice.getLocale();
            if (locale == null) continue;
            String tag = locale.toLanguageTag();
            if (tag == null || tag.isEmpty() || "und".equals(tag)) continue;
            if (options.containsKey(tag)) continue;
            String displayName = locale.getDisplayName(displayLocale);
            if (displayName == null || displayName.isEmpty() || displayName.equals(tag)) {
                displayName = tag;
            }
            options.put(tag, new TtsLanguageOption(tag, displayName));
        }
        List<TtsLanguageOption> result = new ArrayList<>(options.values());
        Collections.sort(result, new Comparator<TtsLanguageOption>() {
            @Override
            public int compare(TtsLanguageOption a, TtsLanguageOption b) {
                return a.displayName.compareTo(b.displayName);
            }
        });
        return result;
    }

    /** Fires TextToSpeech.Engine.ACTION_CHECK_TTS_DATA at whichever engine androidTts
     *  is currently bound to, and blocks (with a timeout) for the result -同 Android
     *  自己「文字轉語音輸出」設定畫面建立「已安裝」清單所用的 intent 一樣。Result
     *  extras 用 lang-COUNTRY-variant 3 個字母 ISO code (例如 "eng-USA"), 不是
     *  BCP-47 - iso3ToIso1Language()/iso3ToIso1Country() 轉做 2 個字母先起
     *  Locale。 */
    private List<TtsLanguageOption> checkTtsDataSyncLegacy(Locale displayLocale) {
        String enginePkg = androidTtsEnginePkg;
        if (enginePkg == null || enginePkg.isEmpty()) {
            return new ArrayList<>();
        }
        CountDownLatch latch;
        synchronized (ttsDataCheckLock) {
            latch = new CountDownLatch(1);
            ttsDataCheckLatch = latch;
            ttsDataCheckResult = null;
        }
        try {
            Intent checkIntent = new Intent();
            checkIntent.setAction(TextToSpeech.Engine.ACTION_CHECK_TTS_DATA);
            checkIntent.setPackage(enginePkg); // 指定該 engine, 不是「隨便哪個應用程式搶到就用哪個」
            // 註：呢度多數跑喺 HttpServer worker thread——startActivityForResult
            // 係 binder call，唔掂 View，worker thread 調用無問題；結果經主線程
            // onActivityResult → latch 返嚟，唔好「修正」做 mainHandler.post
            //（post 完仲要等 latch，多此一舉；直接調用先唔會 deadlock）。
            activity.startActivityForResult(checkIntent, TTS_DATA_CHECK_REQUEST_CODE);
        } catch (Exception e) {
            Log.e(TAG, "ACTION_CHECK_TTS_DATA launch failed for engine=" + enginePkg, e);
            return new ArrayList<>();
        }
        try {
            // 3 秒對一個正常應該即時、不涉及網路/磁碟 IO 的本機查詢來說已經很夠 - 超過
            // 還沒回應就代表有問題 (engine 沒有回應), 應該回傳空清單給 caller, 不應該
            // 令個 HTTP request 無限期卡住。
            if (!latch.await(3, TimeUnit.SECONDS)) {
                Log.e(TAG, "ACTION_CHECK_TTS_DATA timed out for engine=" + enginePkg);
                return new ArrayList<>();
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new ArrayList<>();
        }
        ArrayList<String> raw = ttsDataCheckResult;
        if (raw == null) {
            return new ArrayList<>();
        }
        Map<String, TtsLanguageOption> options = new HashMap<>();
        for (String voice : raw) {
            // "eng" 或 "eng-USA" 或 "eng-USA-FEMALE" - 拆開, 淨係要 lang[-country],
            // 去除第 4 段開始的任何 variant 後綴 (不是 Locale 的 country, 也
            // 不是 toLanguageTag() 任何位置會放置的 engine-specific variant 標籤)。
            String[] parts = voice.split("-");
            if (parts.length == 0 || parts[0].isEmpty()) continue;
            // 兩段都是 ISO-639-2/ISO-3166-1 ALPHA-3 (3 個字母), 例如 "eng"/"USA" -
            // user-confirmed 實機 bug: new Locale("eng").toLanguageTag() 不會變回
            // "en" 這樣 (只有 2 個字母時才會)。Locale 的 constructor 完全不會將 3 個字母
            // 的 ISO code 轉成 2 個字母的對應版本 - 它只是把傳入的字串原樣存起來,
            // 所以 toLanguageTag() 之前會直接漏出原始的 3 字母 code ("ara",
            // "ben", "eng", ...), 而不是正確的 BCP-47 tag。iso3ToIso1Language()/
            // iso3ToIso1Country() 就是在做這個轉換, 靠 Locale.getAvailableLocales()
            // 反查, 因為 Locale 本身沒有「3 個字母轉 2 個字母」的直接 API。
            String lang2 = iso3ToIso1Language(parts[0]);
            if (lang2 == null) {
                // 不是一個有 2 個字母對應版本的 3 個字母 ISO-639-2 code -
                // user-confirmed 真實 case: "yue" (粵語) 根本沒有 ISO-639-1 2 個
                // 字母 code, 所以 iso3ToIso1Language("yue") 合理地回傳 null, 之前
                // 這裡會直接 "continue" (跳過整個 entry), 悄悄地漏掉了粵語, 雖然
                // Google TTS 確實裝了 (logcat 看到 "Download of yue-hk started"/
                // "Download yue-hk Success true")。BCP-47 (和 Java 的 Locale)
                // 都接受 3 個字母的 primary language subtag 直接使用 (IANA 的
                // language subtag registry 本身有列出 "yue" 作為合法 primary
                // subtag) - 所以退回用 3 個字母 code 原樣, 不要去掉整個語言。
                lang2 = parts[0];
            }
            String country2 = null;
            if (parts.length >= 2 && !parts[1].isEmpty()) {
                country2 = iso3ToIso1Country(parts[1]);
                if (country2 == null) {
                    // 和上面語言那句一樣的道理 - 保留原本 3 個字母 country code
                    // 比去掉好 (雖然不是 ISO-3166-1 alpha-2, 但仍然有意義)。
                    country2 = parts[1];
                }
            }
            Locale locale = (country2 != null) ? new Locale(lang2, country2) : new Locale(lang2);
            String tag = locale.toLanguageTag();
            if (options.containsKey(tag)) continue;
            String displayName = locale.getDisplayName(displayLocale);
            if (displayName == null || displayName.isEmpty() || displayName.equals(tag)) {
                displayName = tag;
            }
            options.put(tag, new TtsLanguageOption(tag, displayName));
        }
        List<TtsLanguageOption> result = new ArrayList<>(options.values());
        Collections.sort(result, new Comparator<TtsLanguageOption>() {
            @Override
            public int compare(TtsLanguageOption a, TtsLanguageOption b) {
                return a.displayName.compareTo(b.displayName);
            }
        });
        return result;
    }

    private static volatile Map<String, String> iso3LanguageMap;
    private static volatile Map<String, String> iso3CountryMap;

    /** Lazily builds (一次過, cache 落 static field) 一個由 ISO-639-2 3 個字母語言
     *  code 到 ISO-639-1 2 個字母 code 的反查表, 因為 java.util.Locale 沒有這個方向
     *  的直接 API - 只有正向的 Locale.getISO3Language() (由一個已經是 2 個字母
     *  的 Locale 出發)。用 Locale.getAvailableLocales() (這個 JVM 支援的全部
     *  Locale) 起, 覆蓋範圍遠比手寫一個表齊全。 */
    private static String iso3ToIso1Language(String iso3) {
        Map<String, String> map = iso3LanguageMap;
        if (map == null) {
            map = new HashMap<>();
            for (Locale l : Locale.getAvailableLocales()) {
                String lang2 = l.getLanguage();
                if (lang2.isEmpty()) continue;
                try {
                    String lang3 = l.getISO3Language();
                    // 用 containsKey()+put() 而不是 putIfAbsent() - user-confirmed
                    // 真機 crash: 呢部機 Android 版本早過 API 24 (Nougat),
                    // Map.putIfAbsent() 係 default method, 淨係 API 24 開始先有
                    // (呢個 app 自己個 minSdkVersion 係 19) - call 落去會 throw
                    // NoSuchMethodError 令成個 app 死埋。containsKey()+put() 用
                    // pre-Java-8/pre-API-24 都支援的 Map method 做出同樣「keep the
                    // first mapping seen」的效果。
                    if (lang3 != null && !lang3.isEmpty() && !map.containsKey(lang3)) {
                        map.put(lang3, lang2);
                    }
                } catch (Exception ignored) {
                    // 有部分 Locale 會在這裡 throw MissingResourceException - 只是
                    // 代表那一個貢獻不了映射, 不是要中止建立整個表的理由。
                }
            }
            iso3LanguageMap = map;
        }
        return map.get(iso3);
    }

    /** 和 iso3ToIso1Language() 想法一樣, 但是轉 ISO-3166-1 alpha-3 國家 code
     *  (例如 "USA" -> "US")。 */
    private static String iso3ToIso1Country(String iso3) {
        Map<String, String> map = iso3CountryMap;
        if (map == null) {
            map = new HashMap<>();
            for (Locale l : Locale.getAvailableLocales()) {
                String country2 = l.getCountry();
                if (country2.isEmpty()) continue;
                try {
                    String country3 = l.getISO3Country();
                    // 見上面 iso3ToIso1Language() 為何不用 putIfAbsent()。
                    if (country3 != null && !country3.isEmpty() && !map.containsKey(country3)) {
                        map.put(country3, country2);
                    }
                } catch (Exception ignored) {
                }
            }
            iso3CountryMap = map;
        }
        return map.get(iso3);
    }

    /** langTag 傳回給 speak(text, langTag)/setLanguage(), displayName 是提供給 UI 顯示
     *  的名稱 - 在 server 端經由 Locale.getDisplayName() 建立, 不用讓前端自己維護一份
     *  tag->name 對照表。 */
    private static final class TtsLanguageOption {
        final String langTag;
        final String displayName;
        TtsLanguageOption(String langTag, String displayName) {
            this.langTag = langTag;
            this.displayName = displayName;
        }
    }

    /** speech/tts_voices 一粒聲：name 係 set 時傳返嚟嘅 id (Voice.getName())，
     *  localeTag 係呢把聲屬邊個語言，network＝要上網先讀到，quality＝
     *  Voice.getQuality() (API 21+ 先有 Voice，見 listAndroidTtsVoices 守門)。
     *  顯示名唔喺呢度砌——中／英文後綴（本地／網絡）由前端按 uiLang 加。 */
    private static final class TtsVoiceOption {
        final String name;
        final String localeTag;
        final boolean network;
        final int quality;
        TtsVoiceOption(String name, String localeTag, boolean network, int quality) {
            this.name = name;
            this.localeTag = localeTag;
            this.network = network;
            this.quality = quality;
        }
    }

    /** 列出指定語言（BCP-47 tag，空＝唔過濾，成個引擎）嘅全部具體聲音。
     *  用 getVoices()（API 21+，呢個 APK 要行 API 19，守門＋Throwable 全接，
     *  舊機回空清單唔炒；同 checkTtsDataViaGetVoices 同一假設）。
     *  配對規則：locale 完全等於要求先排頭，其次同 language（zh-HK 要 zh 把聲
     *  頂上，唔好回家；Google TTS 每個語言多把聲就係靠呢層撈出嚟）。 */
    private List<TtsVoiceOption> listAndroidTtsVoices(String langTag) {
        List<TtsVoiceOption> out = new ArrayList<>();
        if (android.os.Build.VERSION.SDK_INT < 21) return out;
        TextToSpeech tts = androidTts;
        if (tts == null) return out;
        Set<Voice> voices;
        try {
            voices = tts.getVoices();
        } catch (Throwable e) {
            Log.e(TAG, "androidTts.getVoices() failed", e);
            return out;
        }
        if (voices == null || voices.isEmpty()) return out;
        Locale req = null;
        if (langTag != null && !langTag.isEmpty()) {
            try {
                req = Locale.forLanguageTag(langTag);
            } catch (Throwable ignore) {
                req = null;
            }
            if (req != null && (req.getLanguage() == null || req.getLanguage().isEmpty()
                    || "und".equals(req.getLanguage()))) {
                req = null; // 傳咗個怪 tag，當無過濾好過回空
            }
        }
        List<TtsVoiceOption> exact = new ArrayList<>();
        List<TtsVoiceOption> langOnly = new ArrayList<>();
        List<TtsVoiceOption> rest = new ArrayList<>();
        for (Voice v : voices) {
            Locale locale;
            String name;
            boolean network;
            int quality;
            try {
                locale = v.getLocale();
                name = v.getName();
                network = v.isNetworkConnectionRequired();
                quality = v.getQuality();
            } catch (Throwable ignore) {
                continue;
            }
            if (locale == null || name == null || name.isEmpty()) continue;
            String tag = locale.toLanguageTag();
            if (tag == null || tag.isEmpty() || "und".equals(tag)) continue;
            TtsVoiceOption opt = new TtsVoiceOption(name, tag, network, quality);
            if (req == null) {
                rest.add(opt);
            } else if (locale.equals(req)) {
                exact.add(opt);
            } else if (locale.getLanguage().equals(req.getLanguage())) {
                langOnly.add(opt);
            }
        }
        Comparator<TtsVoiceOption> byName = new Comparator<TtsVoiceOption>() {
            @Override
            public int compare(TtsVoiceOption a, TtsVoiceOption b) {
                return a.name.compareTo(b.name);
            }
        };
        Collections.sort(exact, byName);
        Collections.sort(langOnly, byName);
        Collections.sort(rest, byName);
        // 同語言先（完全配對行先），唔啱語言嘅唔回（前端揀咗中文唔應該見到英文聲）。
        out.addAll(exact);
        out.addAll(langOnly);
        if (req == null) out.addAll(rest);
        return out;
    }

    /** 名揾聲＋setVoice。return true＝已切聲（連 locale 一齊換埋，唔使再
     *  setLanguage）；false＝搵唔到／唔支援（caller 跌返 setLanguage 路）。 */
    private boolean applyVoiceByName(TextToSpeech tts, String voiceName) {
        if (tts == null || voiceName == null || voiceName.isEmpty()) return false;
        if (android.os.Build.VERSION.SDK_INT < 21) return false;
        try {
            Set<Voice> voices = tts.getVoices();
            if (voices == null) return false;
            for (Voice v : voices) {
                String name;
                try {
                    name = v.getName();
                } catch (Throwable ignore) {
                    continue;
                }
                if (voiceName.equals(name)) {
                    try {
                        int r = tts.setVoice(v);
                        return r == TextToSpeech.SUCCESS;
                    } catch (Throwable e) {
                        Log.w(TAG, "setVoice failed: " + voiceName, e);
                        return false;
                    }
                }
            }
        } catch (Throwable e) {
            Log.w(TAG, "applyVoiceByName getVoices failed", e);
        }
        return false;
    }

    private String ttsVoicePref() {
        try {
            String v = prefs().getString(PREF_ANDROID_TTS_VOICE, "");
            return v == null ? "" : v;
        } catch (Throwable e) {
            return "";
        }
    }

    /** 列出機身已安裝的全部 Android TTS 引擎 package name (已排序) - 供
     *  speech/tts_engines endpoint 使用, 讓 speech tab 的 Android 選項可以選擇哪個
     *  引擎發音。用一個 throwaway TextToSpeech instance 取得這個裝置層面的清單,
     *  不綁定目前使用中的 androidTts field - getEngines() 本身不是
     *  engine-specific, 不用等 androidTtsReady 才能查詢, 用 live 的
     *  androidTts 反而有可能取得「舊 engine 時捕捉到」的過時清單。 */
    private List<String> listAndroidTtsEngines() {
        List<String> result = new ArrayList<>();
        TextToSpeech probe = null;
        try {
            final CountDownLatch initLatch = new CountDownLatch(1);
            probe = new TextToSpeech(activity, status -> initLatch.countDown());
            // getEngines() 本身不需要 init 完成 (不是 engine-specific), 但稍等一下
            // 避免和 constructor 自己的 async setup 互相衝突 (部分 OEM engine 見過)。
            try {
                initLatch.await(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            List<TextToSpeech.EngineInfo> engines = probe.getEngines();
            if (engines != null) {
                Set<String> pkgs = new TreeSet<>();
                for (TextToSpeech.EngineInfo e : engines) {
                    pkgs.add(e.name);
                }
                result.addAll(pkgs);
            }
        } catch (Exception e) {
            Log.e(TAG, "androidTts.getEngines failed", e);
        } finally {
            if (probe != null) {
                probe.shutdown();
            }
        }
        return result;
    }

    /** (Re)binds androidTts to a specific TTS engine and wires up the same
     *  OnInitListener/UtteranceProgressListener behaviour every time - called once from
     *  MainActivity.onCreate() with enginePackage=null (device default) and again from
     *  speech/set_tts_engine whenever the user switches engines. The old instance
     *  (if any) is stopped and shut down first, since Android
     *  has no API to rebind an existing TextToSpeech to a different engine in place -
     *  switching means tearing down and constructing a fresh one bound to the new
     *  engine's Service. androidTtsReady is set false for the duration of the rebind so
     *  speak() calls that land mid-switch fail fast (see the "speech/tts" case below)
     *  instead of silently going to whichever instance happened to still be assigned. */
    public void initAndroidTts(String enginePackage) {
        TextToSpeech old = androidTts;
        androidTtsReady = false;
        if (old != null) {
            old.stop();
            old.shutdown();
        }
        // Holder so initListener can reference the instance being constructed even if
        // onInit() fires synchronously (before the constructor returns and "created"/
        // the androidTts field get assigned) - some OEM engines do call back inline on
        // failure rather than always posting asynchronously.
        final TextToSpeech[] holder = new TextToSpeech[1];
        TextToSpeech.OnInitListener initListener = status -> {
            androidTtsReady = (status == TextToSpeech.SUCCESS);
            if (androidTtsReady) {
                // Use the REQUESTED enginePackage, not getDefaultEngine() - user-
                // confirmed bug on real hardware: getDefaultEngine() reports the
                // device's system-wide default TTS engine (a Settings-level concept),
                // NOT "which engine this particular TextToSpeech instance is bound
                // to". After switching to Pico via the 3-arg constructor below,
                // getDefaultEngine() kept reporting com.google.android.tts (the
                // system default, unchanged) - so androidTtsEnginePkg silently stayed
                // wrong after every switch, and checkTtsDataSync() went on querying
                // the OLD engine's languages while the UI showed the NEW engine's name
                // (visible in logcat: ACTION_CHECK_TTS_DATA fired with
                // cmp=.../CheckVoiceData targeting com.google.android.tts right after
                // switching to com.svox.pico). If enginePackage is null (device-default
                // request, e.g. the very first init in onCreate()), fall back to
                // getDefaultEngine() since there's no explicit request to trust instead.
                androidTtsEnginePkg = (enginePackage != null && !enginePackage.isEmpty())
                        ? enginePackage
                        : (holder[0] != null ? holder[0].getDefaultEngine() : "");
            } else {
                // status == LANG_MISSING_DATA/ERROR usually means this engine has no
                // usable voice data on this device, or (if enginePackage was invalid)
                // the package doesn't exist / isn't a TTS engine - either way, this app
                // can't fix that without bundling engine/voice data itself.
                Log.e(TAG, "Android TTS init failed, status=" + status + ", engine="
                        + (enginePackage != null ? enginePackage : "(default)"));
            }
        };
        TextToSpeech created = (enginePackage != null && !enginePackage.isEmpty())
                ? new TextToSpeech(activity, initListener, enginePackage)
                : new TextToSpeech(activity, initListener);
        holder[0] = created;
        // Unlike onServerPlayEnd (robot-side TTS), Android system TTS reports per-
        // utterance completion only through this listener, not through onInit - needed
        // to know when to stop the mouth LED breathing effect started in speech/tts's
        // engine=android branch. "panel_tts" is the utteranceId passed to speak() there;
        // onStart/onDone/onError all fire on whichever id is currently in flight since
        // QUEUE_FLUSH means only one utterance is ever in flight from this app at a time.
        created.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                // no-op: the mouth LED is already started right before speak() is
                // called, not here, so it lights up without waiting for this callback's
                // round-trip.
                // Vosk 聆聽緊就 pause 返，唔好將自己把聲認返入去無限迴音。
                // mic 照 hold 住（pause 唔放 recorder），播完 onDone  resume。
                if (vosk != null) {
                    try {
                        vosk.setPaused(true);
                    } catch (Throwable ignore) {
                    }
                }
            }

            @Override
            public void onDone(String utteranceId) {
                LedCenter.stopMouthLedForTts();
                if (vosk != null) {
                    try {
                        vosk.setPaused(false);
                    } catch (Throwable ignore) {
                    }
                }
                // 和 robot-side TTS 的 onServerPlayEnd 一致, publish tts_end
                // 讓前端知道這句讀完了 - 小智 tab 選了本地引擎的時候靠這個 event
                // 排隊讀多句回覆 (見 xiaozhiTtsQueue 相關 comment)。isEnd 固定
                // true, Android TTS 沒有對應 onServerPlayEnd 的 isEnd 語意, 這裡
                // 沒有對應的 false case。
                EventBus.get().publish("tts_end", "{\"isEnd\":true}");
            }

            @Override
            public void onError(String utteranceId) {
                LedCenter.stopMouthLedForTts();
                if (vosk != null) {
                    try {
                        vosk.setPaused(false);
                    } catch (Throwable ignore) {
                    }
                }
                // 出錯也要 publish, 不然前端的 queue 會卡在那裡等一個永遠不會來
                // 的 tts_end, 之後所有排隊的句子都讀不到。
                EventBus.get().publish("tts_end", "{\"isEnd\":true}");
            }
        });
        androidTts = created;
    }

    /**
     * 經 Android 內置 TTS 讀一句 (供語意配對答案等唔經 speech/tts
     * endpoint 的內部調用)。同 speech/tts engine=android 分支同一個語義:
     * locale 參數而家只係 fallback —— TTS 卡有明確選擇
     * (PREF_ANDROID_TTS_LANG 非空) 就優先用卡嘅選擇，對話 TTS 即時跟卡走；
     * 卡留空 ("沿用引擎目前語言") 先用傳入嘅自動判斷值。
     * 嘗試切 locale (唔支援就記 warning 照用引擎現有語言讀, 唔靜音),
     * QUEUE_FLUSH 單句播放。嘴 LED 由 UtteranceProgressListener 負責熄,
     * 呼叫方開始前點亮、失敗時自己熄即可。
     * @return true = 已送去播放, false = Android TTS 未 ready (呼叫方要自己熄燈)
     */
    public boolean speakAndroidTts(String text, java.util.Locale locale) {
        TextToSpeech tts = androidTts;
        if (tts == null || !androidTtsReady || text == null || text.isEmpty()) {
            Log.w(TAG, "speakAndroidTts: Android TTS not ready, drop: " + text);
            return false;
        }
        // TTS 卡優先：有明確選擇就用佢，否則用傳入嘅自動判斷值。
        java.util.Locale effective = locale;
        try {
            String cardLang = prefs().getString(PREF_ANDROID_TTS_LANG, "");
            if (cardLang != null && !cardLang.isEmpty()) {
                effective = java.util.Locale.forLanguageTag(cardLang);
            }
        } catch (Throwable ignore) {
        }
        locale = effective;
        // 卡有揀具體聲就用聲（setVoice 連 locale 一齊換）；搵唔到先跌返下面 setLanguage。
        boolean voiced = false;
        try {
            voiced = applyVoiceByName(tts, ttsVoicePref());
        } catch (Throwable ignore) {
            voiced = false;
        }
        if (locale != null && !voiced) {
            try {
                int r = tts.setLanguage(locale);
                if (r < TextToSpeech.LANG_AVAILABLE) {
                    Log.w(TAG, "speakAndroidTts: locale " + locale.toLanguageTag()
                            + " not supported, speak with current language instead");
                }
            } catch (Exception e) {
                Log.w(TAG, "speakAndroidTts setLanguage failed", e);
            }
        }
        try {
            tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "semantic_tts");
            return true;
        } catch (Exception e) {
            Log.w(TAG, "speakAndroidTts speak failed", e);
            return false;
        }
    }

    /** 接住 checkTtsDataSyncLegacy() 發出的 ACTION_CHECK_TTS_DATA 結果 (由
     *  MainActivity.onActivityResult 轉交)。只處理這個 app 自己認得的
     *  requestCode, 其他一律交回給 super。 */
    public void onTtsDataResult(android.content.Intent data) {
        CountDownLatch latch;
        synchronized (ttsDataCheckLock) {
            latch = ttsDataCheckLatch;
            ttsDataCheckResult = (data != null)
                    ? data.getStringArrayListExtra(TextToSpeech.Engine.EXTRA_AVAILABLE_VOICES)
                    : null;
        }
        if (latch != null) {
            latch.countDown();
        }
    }

    // -- HTTP endpoints (handleApi 轉調，參數校驗照舊經 ApiValidator) ----------

    /** speech/tts engine=android 分支本體。回 null = 已送去讀；回字串 = 錯誤訊息。
     *  voice（可空）＝ Voice.getName()：有就優先 setVoice（連 locale 一齊換，
     *  唔使 lang 都啱）；搵唔到先跌返 lang 路。空就照舊只用 lang。 */
    public String speakPanelTts(String text, String lang, String voice) {
        if (androidTts == null || !androidTtsReady) {
            return "Android TTS not ready";
        }
        boolean voiced = false;
        if (voice != null && !voice.isEmpty()) {
            voiced = applyVoiceByName(androidTts, voice);
            if (!voiced) {
                Log.w(TAG, "speakPanelTts: voice not found, fallback to lang: " + voice);
            }
        }
        if (!voiced && !lang.isEmpty()) {
            Locale locale = Locale.forLanguageTag(lang);
            int result = androidTts.setLanguage(locale);
            if (result < TextToSpeech.LANG_AVAILABLE) {
                return "Android TTS engine does not support language: " + lang;
            }
        }
        LedCenter.startMouthLedForTts();
        androidTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, "panel_tts");
        return null;
    }

    // Android TTS 語言揀擇 - 淨係 engine=android 用得 (Nuance/iFlytek
    // 兩個 AIDL engine 沒有語言參數選擇, lang 已經由 engine 本身固定死,
    // 見 speech/tts 的 android 分支)。ui_lang ("zh"/"en") 控制的是
    // displayName 用邊種語言顯示。
    public HttpServer.ApiResponse ttsLanguages(Map<String, String> query) {
        boolean english = "en".equals(ApiValidator.optionalUiLang(query));
        List<TtsLanguageOption> langs = listAndroidTtsLanguages(
                english ? Locale.ENGLISH : Locale.TRADITIONAL_CHINESE);
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"languages\":[");
        for (int i = 0; i < langs.size(); i++) {
            if (i > 0) sb.append(',');
            TtsLanguageOption opt = langs.get(i);
            sb.append("{\"tag\":\"").append(MainActivity.jsonSafe(opt.langTag))
              .append("\",\"name\":\"").append(MainActivity.jsonSafe(opt.displayName)).append("\"}");
        }
        sb.append("]}");
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    // Android TTS 引擎選擇 - 機身可能裝了不只一個系統 TTS 引擎 (例如出廠
    // 內建 + Google TTS + SVOX Pico), 這三個 endpoint 供 speech tab 選擇
    // speech/tts 的 engine=android 分支實際用哪個發音, 不涉及 Nuance/
    // iFlytek。
    public HttpServer.ApiResponse ttsEngines() {
        List<String> engines = listAndroidTtsEngines();
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"engines\":[");
        for (int i = 0; i < engines.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(MainActivity.jsonSafe(engines.get(i))).append('"');
        }
        sb.append("]}");
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    public HttpServer.ApiResponse setTtsEngine(Map<String, String> query) {
        String enginePkg = ApiValidator.require(query, "engine");
        initAndroidTts(enginePkg);
        // 轉引擎＝舊聲作廢（個名綁死舊引擎），一齊清，唔好套落新引擎度。
        try {
            prefs().edit().putString(PREF_ANDROID_TTS_VOICE, "").apply();
        } catch (Throwable ignore) {
        }
        // 呢個切換本身係 async (initAndroidTts() 拆舊起新一個
        // TextToSpeech instance, 再等 OnInitListener 先真正 ready) -
        // 這裡的 "ok" 只是說已經觸發了切換, 不代表立即可以講話, 前端
        // 應該延遲少少先再 poll speech/cur_tts_engine。
        return HttpServer.ApiResponse.ok("{\"ok\":true}");
    }

    public HttpServer.ApiResponse curTtsEngine() {
        return HttpServer.ApiResponse.ok(
                "{\"ok\":true,\"engine\":\"" + MainActivity.jsonSafe(androidTtsEnginePkg) + "\"}");
    }

    // TTS 卡語言選擇嘅後端 pref (BCP-47 tag，空=沿用引擎
    // 目前語言)。前端 setAndroidTtsLang() 同步寫入；對話管線
    // speakAndroidTts() 優先讀佢——一揀即時跟。
    public HttpServer.ApiResponse setTtsLang(Map<String, String> query) {
        String lang = ApiValidator.optional(query, "lang", "");
        prefs().edit().putString(PREF_ANDROID_TTS_LANG, lang == null ? "" : lang).apply();
        // 轉語言＝舊聲作廢（唔同語言唔同聲），一齊清。
        try {
            prefs().edit().putString(PREF_ANDROID_TTS_VOICE, "").apply();
        } catch (Throwable ignore) {
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true}");
    }

    public HttpServer.ApiResponse curTtsLang() {
        String lang;
        try {
            lang = prefs().getString(PREF_ANDROID_TTS_LANG, "");
        } catch (Throwable e) {
            lang = "";
        }
        return HttpServer.ApiResponse.ok(
                "{\"ok\":true,\"lang\":\"" + MainActivity.jsonSafe(lang == null ? "" : lang) + "\"}");
    }

    // TTS 卡聲音選擇嘅後端 pref (Voice.getName()，空=用該語言
    // 預設聲)。前端揀完語言再載入該語言把聲嚟揀；對話管線 speakAndroidTts()
    // 同 speech/tts 都會用（經 applyVoiceByName，搵唔到跌返語言路）。
    // lang 參數（可空）：有就只回該語言把聲；空就回成個引擎（前端唔用，留俾診斷）。
    public HttpServer.ApiResponse ttsVoices(Map<String, String> query) {
        String lang = ApiValidator.optional(query, "lang", "");
        List<TtsVoiceOption> voices = listAndroidTtsVoices(lang == null ? "" : lang);
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"voices\":[");
        for (int i = 0; i < voices.size(); i++) {
            if (i > 0) sb.append(',');
            TtsVoiceOption v = voices.get(i);
            sb.append("{\"name\":\"").append(MainActivity.jsonSafe(v.name))
              .append("\",\"locale\":\"").append(MainActivity.jsonSafe(v.localeTag))
              .append("\",\"network\":").append(v.network)
              .append(",\"quality\":").append(v.quality).append('}');
        }
        sb.append("]}");
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    public HttpServer.ApiResponse setTtsVoice(Map<String, String> query) {
        String voice = ApiValidator.optional(query, "voice", "");
        prefs().edit().putString(PREF_ANDROID_TTS_VOICE, voice == null ? "" : voice).apply();
        return HttpServer.ApiResponse.ok("{\"ok\":true}");
    }

    public HttpServer.ApiResponse curTtsVoice() {
        return HttpServer.ApiResponse.ok(
                "{\"ok\":true,\"voice\":\"" + MainActivity.jsonSafe(ttsVoicePref()) + "\"}");
    }
}
