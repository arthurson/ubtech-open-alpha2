package com.open.alpha2;

import android.content.Context;
import android.util.Log;

import com.ubtechinc.alpha.hardware.HardwareDirectManager;
import com.ubtechinc.alpha.hardware.RobotWire;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 胸口 MCU 同步查詢：韌體版本 (cmd 51) 同 SN/UUID (cmd 55)，pure-direct。
 *
 * 2026-09 由 MainActivity 抽出 (第一刀拆 god object)：latch/raw/len 狀態、
 * 幀解析、阻塞查詢原本全部係 MainActivity 私有成員，搬過嚟一字不改邏輯。
 * 擁有關係：
 * - MainActivity.onDirectChestFrame() 收到胸串口幀先處理心跳/避障/mute/PIR/
 *   升級 ACK，剩低交畀 {@link #onFrame} 認領版本/UUID 回覆。
 * - handleApi chest/version、misc/request_uuid、system/discover、
 *   胸升級 reset 經呢度做阻塞查詢 (HttpServer worker thread，可阻塞)。
 *
 * 線程：latch 欄位 volatile，和以前一樣——兩個查詢同時行會互踩
 * (後者覆蓋前者嘅 latch)，行為同未抽之前完全一致，調用方本來就唔會並行。
 * 2026-09 dispatcher Phase 1 第三刀加：misc/request_uuid、misc/set_uuid
 * 2 個 handleApi case body 搬入 (requestUuidResponse/setUuidResponse)。
 */
public final class ChestQuery {
    private static final String TAG = "ChestQuery";

    /** set_uuid 經本 App 成功寫入的上次 SN 長度, 下次讀回用來截尾 (見
     *  truncateUuidTail)。無記錄 (-1) 就行 pattern/大小寫規則。 */
    public static final String PREF_UUID_WRITTEN_LEN = "uuid_written_len";

    private final Context appContext;
    private final RobotStub robot;

    // 2026-08: 真實胸口 MCU 韌體版本查詢 (CHEST_READ_VERSION 51 / 0x33) 用的
    // 同步等待狀態，供 queryFirmwareVersion() 阻塞等待 (HttpServer worker
    // thread，非主 thread)，onFrame() 回調一到就 countDown。
    private volatile CountDownLatch chestVersionLatch;
    private volatile byte[] chestVersionRaw;
    private volatile int chestVersionLen;
    // 2026-09: 機械人 SN/UUID 直讀 (CHEST_READ_SID_EEPROM 55 / 0x37) 用的
    // 同步等待狀態，同一個 pattern。機身已無 alpha2services,
    // robot.requestRobotUUID() 的 broadcast 永遠無人回覆。
    private volatile CountDownLatch chestUuidLatch;
    private volatile byte[] chestUuidRaw;
    private volatile int chestUuidLen;
    // 2026-09-06: 單舵機實讀 (cmd 13 / 0x0d) 用的同步等待狀態。官方 PC tuner
    // 實測 wire 格式：查詢 f8 8f 08 05 00 0d <id> <sum> ed；正常回覆
    // f8 8f 0b 05 00 0d 00 <id> <hi> <lo> <sum> ed（BE16 signed），
    // 壞舵機（如本機 5/6 號，硬件問題）回短 error 幀
    // f8 8f 09 05 00 0d 01 <id> <sum> ed（無角度值）。
    private volatile CountDownLatch servoLatch;
    private volatile int servoExpectId = -1;
    private volatile Integer servoValue = null;
    private volatile boolean servoError = false;
    // 2026-09-06 晚：trim 寫入 (cmd 12) 回覆 latch。回覆 09 05 00 0c 00 <id>
    // = OK，01 <id> = 該軸無回授（同 0d 一樣，本機 5/6 號即此例）。
    private volatile CountDownLatch trimLatch;
    private volatile int trimExpectId = -1;
    private volatile Boolean trimOk = null;
    // 2026-09-08 新增：單舵機絕對角度實讀 (cmd 6 / 0x06) latch。實機 verified
    // 格式：查詢 f8 8f 08 00 00 06 <id> <sum> ed；回覆
    // f8 8f 0b 00 00 06 00 <id> <hi> <lo> <sum> ed（BE16，同 servo/one 同單位，
    // 跟位誤差約 1°——注意唔係 cmd 13 嗰個 trim/偏差）。只認領等待中那顆 id。
    private volatile CountDownLatch absAngleLatch;
    private volatile int absAngleExpectId = -1;
    private volatile Integer absAngleValue = null;

    public ChestQuery(Context context, RobotStub robot) {
        this.appContext = context.getApplicationContext();
        this.robot = robot;
    }

    private boolean chestReady() {
        try {
            return HardwareDirectManager.get(appContext).chest().isAvailable();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 認領版本/UUID 回覆幀 (由 MainActivity.onDirectChestFrame 在升級 ACK
     * 之後調用，順序不變：升級優先，UUID 次之，版本最後 fallback)。
     * @return true = 已認領 (調用方應直接 return)。
     */
    public boolean onFrame(byte[] frame, byte[] payload, int plen) {
        // 2026-09: UUID/SN 回覆 latch (cmd 55)。放喺版本 latch 之前優先處理,
        // 避免版本查詢的 fallback 誤食 uuid 幀 (uuid 幀 plen 好長, 唔係 sonar ack /
        // obstacle, 舊 fallback 條件會當佢係版本回覆)。
        if (chestUuidLatch != null && chestUuidLatch.getCount() > 0) {
            boolean isUuid = isUuidFrame(frame, frame.length)
                    || (plen >= 1 && payload[0] == RobotWire.CHEST_READ_SID_EEPROM);
            if (isUuid) {
                chestUuidRaw = java.util.Arrays.copyOf(frame, frame.length);
                chestUuidLen = frame.length;
                chestUuidLatch.countDown();
                return true;
            }
        }
        // 2026-09-06: 舵機實讀回覆 latch (cmd 13)。只認領等待中那顆 id，
        // 其他 id 的回覆交還（return false），免得 20 連讀時食錯幀。
        if (servoLatch != null && servoLatch.getCount() > 0
                && plen >= 3 && payload[0] == 13) {
            int rid = payload[2] & 0xFF;
            if (rid == servoExpectId) {
                if (plen >= 5 && payload[1] == 0) {
                    int hi = payload[3] & 0xFF, lo = payload[4] & 0xFF;
                    servoValue = (int) (short) ((hi << 8) | lo);
                    servoError = false;
                    servoLatch.countDown();
                    return true;
                }
                if (plen == 3 && payload[1] == 1) {
                    // 短 error 幀：舵機無回授（本機 5/6 號硬件壞即此例）
                    servoValue = null;
                    servoError = true;
                    servoLatch.countDown();
                    return true;
                }
            }
            return false;
        }
        // 2026-09-06 晚：trim 寫入回覆 latch (cmd 12)。同樣只認領等待中那顆 id。
        if (trimLatch != null && trimLatch.getCount() > 0
                && plen >= 3 && payload[0] == 12) {
            int rid = payload[2] & 0xFF;
            if (rid == trimExpectId) {
                if (payload[1] == 0) {
                    trimOk = Boolean.TRUE;
                    trimLatch.countDown();
                    return true;
                }
                if (plen == 3 && payload[1] == 1) {
                    trimOk = Boolean.FALSE;
                    trimLatch.countDown();
                    return true;
                }
            }
            return false;
        }
        // 2026-09-08 新增: 絕對角度回覆 latch (cmd 6)。只認領等待中那顆 id，
        // 其他交還（return false），免得 20 連讀時食錯幀。注意放喺版本
        // fallback 之前——06 回覆 plen=5 會被舊 fallback 誤食。
        if (absAngleLatch != null && absAngleLatch.getCount() > 0
                && plen >= 5 && payload[0] == 6) {
            int rid = payload[2] & 0xFF;
            if (rid == absAngleExpectId) {
                if (payload[1] == 0) {
                    int hi = payload[3] & 0xFF, lo = payload[4] & 0xFF;
                    absAngleValue = (hi << 8) | lo;
                    absAngleLatch.countDown();
                    return true;
                }
            }
            return false;
        }
        // 版本 latch：完整帧优先（isVersionFrame 认 F8 8F），否则按 payload fallback
        if (chestVersionLatch != null && chestVersionLatch.getCount() > 0) {
            boolean isVer = isVersionFrame(frame, frame.length, RobotWire.CHEST_READ_VERSION);
            boolean isFallback = false;
            if (!isVer) {
                boolean isSonarAck = (plen == 2 && payload[0] == 4 && payload[1] == 0);
                boolean isObstacle = (plen >= 2 && payload[0] == (byte) -127);
                boolean isUuid = (plen >= 1 && payload[0] == RobotWire.CHEST_READ_SID_EEPROM);
                if (plen >= 1 && !isSonarAck && !isObstacle && !isUuid) {
                    isFallback = true;
                }
            }
            if (isVer || isFallback) {
                if (isVer) {
                    chestVersionRaw = java.util.Arrays.copyOf(frame, frame.length);
                    chestVersionLen = frame.length;
                } else {
                    chestVersionRaw = java.util.Arrays.copyOf(payload, plen);
                    chestVersionLen = plen;
                }
                chestVersionLatch.countDown();
                return true;
            }
        }
        return false;
    }

    /** 清掉未完成的等待 (胸升級開始前調用，和以前 resetChestUpgradeState 一致)。 */
    public void reset() {
        chestVersionLatch = null;
        chestUuidLatch = null;
        servoLatch = null;
        servoExpectId = -1;
        trimLatch = null;
        trimExpectId = -1;
        absAngleLatch = null;
        absAngleExpectId = -1;
    }

    /** 最後一次版本回覆原幀 (拷貝，可 null)，供升級超時診斷 log 用。 */
    public byte[] getLastVersionRaw() {
        byte[] raw = chestVersionRaw;
        return raw != null ? java.util.Arrays.copyOf(raw, raw.length) : null;
    }

    /** 最後一次 UUID 回覆原幀 (拷貝，可 null)，供 request_uuid 診斷用。 */
    public byte[] getLastUuidRaw() {
        byte[] raw = chestUuidRaw;
        return raw != null ? java.util.Arrays.copyOf(raw, raw.length) : null;
    }

    /**
     * 判斷一段 raw serial 回調是否為版本幀 (CHEST_READ_VERSION / HEADER_READ_VERSION 51)。
     * 標準 wire 格式: F8 8F len 01/00 00 33 payload sum ED，其中 33h=51。
     * 為兼容多次連幀或 SDK 預剝 header 的情況，掃描整段 bytes 內任何 F8 8F 窗口。
     */
    private static boolean isVersionFrame(byte[] bytes, int len, byte expectedCmd) {
        if (bytes == null || len < 8) return false;
        int n = Math.min(len, bytes.length);
        for (int i = 0; i + 5 < n; i++) {
            if ((bytes[i] & 0xFF) == 0xF8 && (bytes[i + 1] & 0xFF) == 0x8F) {
                if (i + 5 >= n) continue;
                if (bytes[i + 5] == expectedCmd) {
                    // 進一步確認：len byte 與實際長度大致相符 (7+payloadLen)
                    // 不強校驗 checksum，避免韌體差異導致誤判
                    return true;
                }
            }
        }
        // 兼容 SDK 已剝頭只剩 payload 的極端情況：單字節就是 cmd 的回顯
        // 此分支由外層 fallback 邏輯處理，這裡只認標準幀
        return false;
    }

    /**
     * 從版本幀中抽出 payload 並解碼為可讀字串。
     * 1) 若為標準 F8 8F 幀，payload = bytes[6 .. 6+payloadLen-1], payloadLen = (lenByte &0xFF)-7
     * 2) 若非標準幀（fallback），整段 bytes 即 payload
     * 解碼策略：先嘗試 ASCII 打印字符，若全為可打印則直接返回；否則返回點分十進制 (例如 1.18.3)
     * 或 hex 兜底。
     */
    private static String parseVersionFrame(byte[] bytes, int len) {
        if (bytes == null || len <= 0) return null;
        int n = Math.min(len, bytes.length);
        byte[] payload = null;
        int payloadLen = 0;
        // 嘗試按標準幀解析
        for (int i = 0; i + 5 < n; i++) {
            if ((bytes[i] & 0xFF) == 0xF8 && (bytes[i + 1] & 0xFF) == 0x8F) {
                if (bytes[i + 5] == RobotWire.CHEST_READ_VERSION || bytes[i + 5] == RobotWire.HEADER_READ_VERSION) {
                    int lenByte = bytes[i + 2] & 0xFF;
                    int pl = lenByte - 7;
                    if (pl < 0) pl = 0;
                    if (i + 6 + pl <= n) {
                        payload = new byte[pl];
                        System.arraycopy(bytes, i + 6, payload, 0, pl);
                        payloadLen = pl;
                        break;
                    }
                }
            }
        }
        if (payload == null) {
            // Fallback：整段即 payload（SDK 可能已拆掉 header）
            // 但若開頭仍是 F8 8F 則跳過 header 嘗試最後一次剝離
            if (n >= 6 && (bytes[0] & 0xFF) == 0xF8 && (bytes[1] & 0xFF) == 0x8F) {
                int lenByte = bytes[2] & 0xFF;
                int pl = lenByte - 7;
                if (pl > 0 && 6 + pl <= n) {
                    payload = new byte[pl];
                    System.arraycopy(bytes, 6, payload, 0, pl);
                    payloadLen = pl;
                } else {
                    payload = java.util.Arrays.copyOf(bytes, n);
                    payloadLen = n;
                }
            } else {
                payload = java.util.Arrays.copyOf(bytes, n);
                payloadLen = n;
            }
        }
        if (payloadLen == 0) return "(empty payload)";
        // 去掉尾部 0x00 padding
        int trim = payloadLen;
        while (trim > 0 && payload[trim - 1] == 0) trim--;
        if (trim == 0) return MainActivity.toHex(payload, payloadLen);
        // 先嘗試直接全可打印
        boolean allPrintable = true;
        for (int i = 0; i < trim; i++) {
            int b = payload[i] & 0xFF;
            if (b < 0x20 || b > 0x7E) { allPrintable = false; break; }
        }
        if (allPrintable) {
            String s = new String(payload, 0, trim, StandardCharsets.US_ASCII).trim();
            s = s.replaceAll("[^A-Za-z0-9._\\-]", "");
            if (!s.isEmpty()) return s;
        }
        // 兼容真機實測：payload 開頭夾帶 cmd(0x33) + length(0x00) 等非打印前綴
        // 掃描最長可打印連續段（例如 "ALPHA2Q-CHEST-B-V352-171031"）
        int bestStart = -1, bestLen = 0, curStart = -1;
        for (int i = 0; i <= trim; i++) {
            boolean printable = i < trim && (payload[i] & 0xFF) >= 0x20 && (payload[i] & 0xFF) <= 0x7E;
            if (printable) {
                if (curStart == -1) curStart = i;
            } else {
                if (curStart != -1) {
                    int curLen = i - curStart;
                    if (curLen > bestLen) { bestLen = curLen; bestStart = curStart; }
                    curStart = -1;
                }
            }
        }
        if (bestLen >= 3) {
            String s = new String(payload, bestStart, bestLen, StandardCharsets.US_ASCII).trim();
            s = s.replaceAll("[^A-Za-z0-9._\\-]", "");
            // 若最長段看起來像版本（含 V 或 - 或 . 或 ALPHA），直接返回
            if (s.length() >= 3 && (s.contains("V") || s.contains("-") || s.contains(".") || s.contains("ALPHA"))) {
                return s;
            }
            if (s.length() >= 4) return s;
        }
        // 二進制版本號：常見為 3-4 bytes 各為 major/minor/patch/build
        if (trim <= 8) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < trim; i++) {
                if (i > 0) sb.append('.');
                sb.append(payload[i] & 0xFF);
            }
            return sb.toString() + " (hex:" + MainActivity.toHex(payload, trim) + ")";
        }
        // 兜底：返回過濾後的 ASCII + hex 對照，方便日後診斷
        String filtered = new String(payload, 0, trim, StandardCharsets.US_ASCII).replaceAll("[^\\x20-\\x7E]", "").trim();
        if (!filtered.isEmpty() && filtered.length() >= 4) return filtered;
        return MainActivity.toHex(payload, trim);
    }

    /**
     * 同步阻塞查詢胸口 MCU 真實韌體版本。
     * 必須在非主 thread 調用 (HttpServer worker thread)，否則 waitForInitComplete 會立刻返回。
     * @param timeoutMs 最多等幾耐 (建議 1500-2000ms)
     * @return 解碼後版本字串，失敗回 null
     */
    public String queryFirmwareVersion(long timeoutMs) {
        // pure-direct: 经 /dev/ttyS1 直发 cmd 51（旧 robot.chest_readFirmwareVersion 走 binder，已停用）。
        // 此方法已保证不在主 thread。
        if (!chestReady()) {
            Log.w(TAG, "queryFirmwareVersion: chest not ready (pure-direct)");
            return null;
        }
        CountDownLatch latch = new CountDownLatch(1);
        chestVersionLatch = latch;
        chestVersionRaw = null;
        chestVersionLen = 0;
        boolean sent = HardwareDirectManager.get(appContext).chest().readVersion();
        Log.i(TAG, "chest_readFirmwareVersion direct send -> " + sent);
        if (!sent) {
            chestVersionLatch = null;
            // Fallback：用标准长式 raw 帧直接发送 (F8 8F 07 00 00 33 3A ED)
            try {
                byte[] rawFrame = new byte[]{(byte)0xF8,(byte)0x8F,0x07,0x00,0x00,0x33,0x3A,(byte)0xED};
                CountDownLatch latch2 = new CountDownLatch(1);
                chestVersionLatch = latch2;
                boolean sent2 = HardwareDirectManager.get(appContext).chest().sendRaw(rawFrame);
                Log.i(TAG, "chest_sendRaw fallback send -> " + sent2);
                if (sent2) {
                    boolean ok2 = latch2.await(timeoutMs, TimeUnit.MILLISECONDS);
                    if (ok2 && chestVersionRaw != null) {
                        String v = parseVersionFrame(chestVersionRaw, chestVersionLen);
                        Log.i(TAG, "chest version (raw fallback) raw=" + MainActivity.toHex(chestVersionRaw,chestVersionLen) + " parsed=" + v);
                        return v;
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "chest raw fallback failed", e);
            } finally {
                chestVersionLatch = null;
            }
            return null;
        }
        try {
            boolean ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!ok) {
                Log.w(TAG, "queryFirmwareVersion timeout " + timeoutMs + "ms, try raw fallback");
                // timeout 仍無回覆，補一次 raw 幀再等半個週期
                chestVersionLatch = null;
                try {
                    byte[] rawFrame = new byte[]{(byte)0xF8,(byte)0x8F,0x07,0x00,0x00,0x33,0x3A,(byte)0xED};
                    CountDownLatch latch2 = new CountDownLatch(1);
                    chestVersionLatch = latch2;
                    chestVersionRaw = null; chestVersionLen = 0;
                    boolean sent2 = HardwareDirectManager.get(appContext).chest().sendRaw(rawFrame);
                    Log.i(TAG, "chest timeout raw fallback send -> " + sent2);
                    if (sent2) {
                        boolean ok2 = latch2.await(Math.max(800, timeoutMs/2), TimeUnit.MILLISECONDS);
                        if (ok2 && chestVersionRaw != null) {
                            String v2 = parseVersionFrame(chestVersionRaw, chestVersionLen);
                            Log.i(TAG, "chest version (timeout raw fallback) raw=" + MainActivity.toHex(chestVersionRaw,chestVersionLen) + " parsed=" + v2);
                            return v2;
                        }
                    }
                } catch (Exception e2) {
                    Log.w(TAG, "chest timeout raw fallback failed", e2);
                } finally {
                    chestVersionLatch = null;
                }
                return null;
            }
            if (chestVersionRaw == null) {
                Log.w(TAG, "queryFirmwareVersion latch counted but raw==null");
                return null;
            }
            String v = parseVersionFrame(chestVersionRaw, chestVersionLen);
            Log.i(TAG, "chest version raw=" + MainActivity.toHex(chestVersionRaw, chestVersionLen) + " parsed=" + v);
            return v;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            chestVersionLatch = null;
        }
    }

    /**
     * 2026-09-06 新增：單舵機實讀 (cmd 13 / 0x0d)，官方 PC tuner 同款問法。
     * 發送 f8 8f 08 05 00 0d &lt;id&gt; &lt;sum&gt; ed，阻塞等回覆。
     * @return signed 角度（BE16）；null = 超時或舵機回 error（無回授，
     * 如本機 5/6 號硬件壞）。此方法已保證不在主 thread。
     */
    public Integer queryServoAngle(int id, long timeoutMs) {
        if (id < 1 || id > 20) return null;
        if (!chestReady()) {
            Log.w(TAG, "queryServoAngle: chest not ready (pure-direct)");
            return null;
        }
        CountDownLatch latch = new CountDownLatch(1);
        servoExpectId = id;
        servoValue = null;
        servoError = false;
        servoLatch = latch;
        boolean sent;
        try {
            sent = HardwareDirectManager.get(appContext).chest().readServo((byte) id);
        } catch (Exception e) {
            Log.w(TAG, "queryServoAngle send failed", e);
            servoLatch = null;
            return null;
        }
        Log.d(TAG, "queryServoAngle send id=" + id + " -> " + sent);
        if (!sent) {
            servoLatch = null;
            return null;
        }
        try {
            boolean ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!ok) {
                Log.w(TAG, "queryServoAngle timeout id=" + id + " " + timeoutMs + "ms");
                return null;
            }
            if (servoError) {
                Log.i(TAG, "queryServoAngle id=" + id + " no feedback (servo error frame)");
                return null;
            }
            Log.d(TAG, "queryServoAngle id=" + id + " value=" + servoValue);
            return servoValue;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            servoLatch = null;
        }
    }

    /**
     * 2026-09-08 新增：單舵機絕對角度實讀 (cmd 6 / 0x06)，實機 verified。
     * 發送 f8 8f 08 00 00 06 &lt;id&gt; &lt;sum&gt; ed，阻塞等回覆
     * f8 8f 0b 00 00 06 00 &lt;id&gt; &lt;hi&gt; &lt;lo&gt; &lt;sum&gt; ed
     *（BE16 unsigned，同 servo/one 同單位；跟位實測誤差約 1°）。
     * 注意唔係 cmd 13 嗰個 trim/偏差（嗰個唔跟位）。發送沿用實測過嘅 00 00
     * 頭原幀（唔用 port.send 嘅 05 00 頭，cmd 6 未驗證過嗰個形）。
     * @return 即時角度；null = 超時（暫未知有無短 error 幀，有待實測補）。
     * 此方法已保證不在主 thread。
     */
    public Integer queryServoAbsAngle(int id, long timeoutMs) {
        if (id < 1 || id > 20) return null;
        if (!chestReady()) {
            Log.w(TAG, "queryServoAbsAngle: chest not ready (pure-direct)");
            return null;
        }
        CountDownLatch latch = new CountDownLatch(1);
        absAngleExpectId = id;
        absAngleValue = null;
        absAngleLatch = latch;
        boolean sent;
        try {
            int sum = (8 + 6 + id) & 0xFF;
            byte[] frame = new byte[]{(byte) 0xF8, (byte) 0x8F, 0x08, 0x00, 0x00,
                    0x06, (byte) id, (byte) sum, (byte) 0xED};
            sent = HardwareDirectManager.get(appContext).chest().sendRaw(frame);
        } catch (Exception e) {
            Log.w(TAG, "queryServoAbsAngle send failed", e);
            absAngleLatch = null;
            return null;
        }
        Log.d(TAG, "queryServoAbsAngle send id=" + id + " -> " + sent);
        if (!sent) {
            absAngleLatch = null;
            return null;
        }
        try {
            boolean ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!ok) {
                Log.w(TAG, "queryServoAbsAngle timeout id=" + id + " " + timeoutMs + "ms");
                return null;
            }
            Log.d(TAG, "queryServoAbsAngle id=" + id + " value=" + absAngleValue);
            return absAngleValue;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            absAngleLatch = null;
        }
    }

    /**
     * 2026-09-06 晚新增：寫舵機 trim/偏差 (cmd 12)，官方 PC tuner 同款。
     * 發送 f8 8f 0a 05 00 0c &lt;id&gt; &lt;hi&gt; &lt;lo&gt; &lt;sum&gt; ed，
     * 阻塞等 MCU 回覆。
     * @return TRUE = MCU 回 OK；FALSE = 該軸回 error 幀（ definitive NAK，
     * 如本機 5/6 號）；null = 超時／發送失敗（機身官方 service 爭食回覆 bytes
     * 時常見，調用方應該重試）。注意寫入掉電保持（chest EEPROM）——調用方
     * 必須經用戶明確寫入動作。此方法已保證不在主 thread。
     */
    public Boolean writeServoTrim(int id, int trim, long timeoutMs) {
        if (id < 1 || id > 20) return null;
        if (!chestReady()) {
            Log.w(TAG, "writeServoTrim: chest not ready (pure-direct)");
            return null;
        }
        CountDownLatch latch = new CountDownLatch(1);
        trimExpectId = id;
        trimOk = null;
        trimLatch = latch;
        boolean sent;
        try {
            sent = HardwareDirectManager.get(appContext).chest().writeServoTrim((byte) id, trim);
        } catch (Exception e) {
            Log.w(TAG, "writeServoTrim send failed", e);
            trimLatch = null;
            return null;
        }
        Log.i(TAG, "writeServoTrim send id=" + id + " trim=" + trim + " -> " + sent);
        if (!sent) {
            trimLatch = null;
            return null;
        }
        try {
            boolean ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!ok) {
                Log.w(TAG, "writeServoTrim timeout id=" + id + " " + timeoutMs + "ms");
                return null;
            }
            Log.i(TAG, "writeServoTrim id=" + id + " ack=" + trimOk);
            if (trimOk == null) return null;
            return trimOk;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            trimLatch = null;
        }
    }

    /**
     * 2026-09 新增: UUID/SN 直讀回覆是否為 cmd 55 幀 (CHEST_READ_SID_EEPROM)。
     * 同 isVersionFrame 的掃描邏輯, 認標準 F8 8F 長式幀的 cmd byte (i+5)。
     * 已剝頭只剩 payload 的情況由外層 fallback (payload[0]==55) 覆蓋。
     */
    private static boolean isUuidFrame(byte[] bytes, int len) {
        if (bytes == null || len < 8) return false;
        int n = Math.min(len, bytes.length);
        for (int i = 0; i + 5 < n; i++) {
            if ((bytes[i] & 0xFF) == 0xF8 && (bytes[i + 1] & 0xFF) == 0x8F) {
                if (i + 5 >= n) continue;
                if (bytes[i + 5] == RobotWire.CHEST_READ_SID_EEPROM) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 2026-09 新增: 從 cmd 55 回覆幀抽出 SN/UUID 字串。
     * 實機證據 (見 misc/set_uuid comment 的 hex dump
     * "f8 8f 28 01 00 37 00 42 41 ... 00 00...00 3c ed"): 標準長式幀,
     * cmd=0x37 後的 payload = [flag byte 0x00] + SN ASCII + 0x00 padding。
     * 解碼和 RobotEventReceiver.decodeUuidExtra / 舊 broadcast 路徑完全一致:
     * ASCII 解碼 -> 切掉第一個 \0 之後的東西 -> 白名單只留英數/-/_ (蓋掉舊 SN
     * 較長時殘留的非零垃圾 byte, 見 2026-08 v4 修正)。
     * 找不到 cmd 55 幀 / 洗完是空字串就回 null。
     */
    private static String parseRobotUuidFrame(byte[] bytes, int len) {
        if (bytes == null || len <= 0) return null;
        int n = Math.min(len, bytes.length);
        byte[] snBytes = null;
        for (int i = 0; i + 5 < n; i++) {
            if ((bytes[i] & 0xFF) == 0xF8 && (bytes[i + 1] & 0xFF) == 0x8F) {
                if (i + 5 >= n) continue;
                if (bytes[i + 5] != RobotWire.CHEST_READ_SID_EEPROM) continue;
                int lenByte = bytes[i + 2] & 0xFF;
                int pl = lenByte - 7;
                if (pl < 0) pl = 0;
                if (i + 6 + pl <= n) {
                    snBytes = new byte[pl];
                    System.arraycopy(bytes, i + 6, snBytes, 0, pl);
                    break;
                }
            }
        }
        if (snBytes == null) {
            // Fallback: 已剝頭的 payload (bytes[0] 即 cmd, 見 stripSerialFrame /
            // 舊 AIDL onListenSerialPortRcvData 格式)。
            byte[] payload = MainActivity.stripSerialFrame(bytes);
            if (payload != null && payload.length >= 1
                    && payload[0] == RobotWire.CHEST_READ_SID_EEPROM) {
                snBytes = java.util.Arrays.copyOfRange(payload, 1, payload.length);
            } else if (n >= 1 && bytes[0] == RobotWire.CHEST_READ_SID_EEPROM) {
                snBytes = java.util.Arrays.copyOfRange(bytes, 1, n);
            }
        }
        if (snBytes == null || snBytes.length == 0) return null;
        String s;
        try {
            s = new String(snBytes, StandardCharsets.US_ASCII);
        } catch (Exception e) {
            return null;
        }
        // 2026-09 實測修正 (logcat 真幀 f8 8f 28 00 00 37 00 42 41...):
        // payload 第一個 byte 是 flag 0x00, 舊寫法 indexOf('\0') 切第一個 \0
        // 會切出空字串 -> 回 null ->「無法讀取 uuid」。先跳過開頭的 flag/padding
        // (SN 合法字元只有英數/-/_), 再切第一個 \0 之後的尾部 padding, 最後白名單
        // 過濾。注意尾段可能有非零殘留 (舊 SN 較長時): 白名單留唔到佢哋, 完整值照
        // 顯示由用戶對實體貼紙核對 (見 misc/request_uuid 的 log)。
        int start = 0;
        while (start < s.length() && !isUuidChar(s.charAt(start))) start++;
        s = s.substring(start);
        int cut = s.indexOf('\0');
        if (cut >= 0) {
            s = s.substring(0, cut);
        }
        s = s.replaceAll("[^A-Za-z0-9\\-_]", "").trim();
        return s.isEmpty() ? null : s;
    }

    /** SN/UUID 合法字元 (見 misc/set_uuid 輸入驗證): 英數/-/_ 。 */
    private static boolean isUuidChar(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z')
                || (c >= '0' && c <= '9') || c == '-' || c == '_';
    }

    /** 已知 SN 版式 (實機貼紙 + uuidGenerateRandom 範圍): BAF006UBT + 8 digits。 */
    private static final java.util.regex.Pattern KNOWN_SN_PATTERN =
            java.util.regex.Pattern.compile("BAF006UBT\\d{8}");

    /**
     * 2026-09 新增: 斬走 EEPROM 尾段非零殘留, 只留真 SN。
     * 背景: 用戶已對實體貼紙確認, 真 SN 係 17 字 "BAF006UBT10000001",
     * 讀返嚟 31 字尾段 "yy44567oumamae" 係舊長 SN 被短 SN 蓋過之後的殘留
     * (EEPROM 欄位定長, 寫幾多 byte 就蓋幾多, 其餘唔郁)。規則按優先序:
     * 1) preferredLen (本 App 上次 set_uuid 寫入長度, 有記錄就最準);
     * 2) BAF006UBT+8digits 版式對中就取該段;
     * 3) UBTech SN 全大寫+數字, 第一個小寫字母起即殘留 (截完要有返 >=8 字,
     *    否則當 SN 本身含小寫, 回全串唔斬);
     * 4) 乜都對唔中就回全串 (寧願顯示多唔顯示少)。
     * 回 null 只代表輸入本身空/全非法。
     */
    static String truncateUuidTail(String s, int preferredLen) {
        if (s == null || s.isEmpty()) return null;
        if (preferredLen >= 1 && preferredLen <= 31 && s.length() > preferredLen) {
            String t = s.substring(0, preferredLen).trim();
            if (!t.isEmpty()) return t;
        }
        java.util.regex.Matcher m = KNOWN_SN_PATTERN.matcher(s);
        if (m.find()) return m.group();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 'a' && c <= 'z') {
                String t = s.substring(0, i).replaceAll("[^A-Za-z0-9\\-_]", "").trim();
                if (t.length() >= 8) return t;
                break;
            }
        }
        return s;
    }

    /**
     * 2026-09 新增: 同步阻塞查詢胸口 EEPROM 的 SN/UUID (pure-direct)。
     * 取代 robot.requestRobotUUID() 的 broadcast 路徑 —— 機身已無 alpha2services,
     * 那個 broadcast 發出去永遠無人回覆 "com.ubtechinc.robot_uuid.info",
     * 這就是「無法讀取 uuid」的根因。
     * 必須在非主 thread 調用 (HttpServer worker thread), 和
     * queryFirmwareVersion() 同一個約束。
     * @param timeoutMs 最多等幾耐 (建議 2000ms)
     * @return 乾淨 SN 字串, 失敗回 null
     */
    public String queryRobotUuid(long timeoutMs) {
        if (!chestReady()) {
            Log.w(TAG, "queryRobotUuid: chest not ready (pure-direct)");
            return null;
        }
        CountDownLatch latch = new CountDownLatch(1);
        chestUuidLatch = latch;
        chestUuidRaw = null;
        chestUuidLen = 0;
        boolean sent;
        try {
            sent = HardwareDirectManager.get(appContext).chest().readSidEeprom();
        } catch (Exception e) {
            Log.w(TAG, "queryRobotUuid send failed", e);
            chestUuidLatch = null;
            return null;
        }
        Log.i(TAG, "chest_readSidEeprom direct send -> " + sent);
        if (!sent) {
            chestUuidLatch = null;
            return null;
        }
        try {
            boolean ok = latch.await(timeoutMs, TimeUnit.MILLISECONDS);
            if (!ok) {
                Log.w(TAG, "queryRobotUuid timeout " + timeoutMs + "ms");
                return null;
            }
            if (chestUuidRaw == null) {
                Log.w(TAG, "queryRobotUuid latch counted but raw==null");
                return null;
            }
            String uuid = parseRobotUuidFrame(chestUuidRaw, chestUuidLen);
            // 2026-09: 斬尾 (見 truncateUuidTail) + 記 log 對照: raw 係全幀 hex,
            // parsed 係截完的真 SN。
            if (uuid != null) {
                int prefLen = -1;
                try {
                    prefLen = appContext.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE)
                            .getInt(PREF_UUID_WRITTEN_LEN, -1);
                } catch (Throwable ignore) {
                }
                uuid = truncateUuidTail(uuid, prefLen);
            }
            Log.i(TAG, "chest uuid raw=" + MainActivity.toHex(chestUuidRaw, chestUuidLen) + " parsed=" + uuid);
            return uuid;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } finally {
            chestUuidLatch = null;
        }
    }

    // -- UUID endpoint 回應層 (2026-09 dispatcher Phase 1 第三刀由 handleApi 搬入) --
    public HttpServer.ApiResponse requestUuidResponse() {
        // 2026-09 修正「無法讀取 uuid」: 之前只發
        // robot.requestRobotUUID() (broadcast "com.ubtechinc.robot_uuid.request"),
        // 但機身已無 alpha2services, 呢個 broadcast 永遠無人回覆
        // "com.ubtechinc.robot_uuid.info", UI 永久停喺「查詢中」。
        // 改走 pure-direct: 經 /dev/ttyS1 直發 cmd 55 讀 chest EEPROM,
        // 同步等回覆 (HttpServer worker thread, 可阻塞, 同版本查詢一樣),
        // 讀到即經 EventBus 發 robot_uuid (舊 WS 路徑, 前端唔使改) +
        // HTTP response 順手帶埋 uuid (新 fallback, 前端直接用, 唔使等 WS)。
        // 舊 broadcast 照發 (向後相容, 有朝一日裝返 alpha2services 都唔會壞)。
        try {
            robot.requestRobotUUID();
        } catch (Throwable ignore) {
        }
        String uuid = queryRobotUuid(2000);
        if (uuid != null && !uuid.isEmpty()) {
            EventBus.get().publish("robot_uuid", "{\"uuid\":\"" + MainActivity.jsonSafe(uuid) + "\"}");
            return HttpServer.ApiResponse.ok(
                    "{\"ok\":true,\"uuid\":\"" + MainActivity.jsonSafe(uuid) + "\"}");
        }
        // 2026-09: 分辨 timeout (完全無回幀) 同 parse 失敗 (有回幀但洗唔出
        // 字串), 後者連 raw hex 一齊回, 等 logcat/前端可以直接對。
        String diag = "";
        try {
            byte[] uuidRaw = getLastUuidRaw();
            if (uuidRaw != null) {
                diag = " raw=" + MainActivity.toHex(uuidRaw, uuidRaw.length);
            }
        } catch (Throwable ignore) {
        }
        Log.w(TAG, "misc/request_uuid direct read failed (chest cmd 55)." + diag);
        EventBus.get().publish("robot_uuid", "{\"uuid\":null}");
        return HttpServer.ApiResponse.ok(
                "{\"ok\":false,\"error\":\"uuid read failed - chest cmd 55"
                        + MainActivity.jsonSafe(diag) + "\"}");
    }

    public HttpServer.ApiResponse setUuidResponse(Map<String, String> query) {
        // 2026-08 v2 新增: 更改機械人 ID (chest EEPROM SN 欄位)。格式由
        // 實機逆向 + 實測確認: cmd=54 (0x36), payload = 新 SN 的 ASCII bytes
        // (寫幾多個 byte 就幾多個, 其餘補 0), wire frame
        // F8 8F <7+n> 00 00 36 <sn...> <sum> ED, sum=(len+0x36+Σsn)&0xFF。
        // 寫入後即刻 requestUUID 讀返驗證 (robot_uuid event 經 WS 更新 UI)。
        //
        // 2026-08 v3: 曾經誤以為亂碼尾巴代表 EEPROM 定長 32 bytes 沒有被完
        // 全覆寫, 一度改成把整個 payload padding 到 32 bytes 才寫 —— 這個
        // 方向錯了, 已經用實機 logcat 推翻: hex dump (CHEST_READ_SID_EEPROM
        // 回應幀 "f8 8f 28 01 00 37 00 42 41 ... 00 00...00 3c ed") 顯示
        // 讀出來的 payload 本身很乾淨 —— [flag byte] + 17 bytes SN ASCII +
        // 0x00 padding, 完全沒有非零垃圾。之所以那行 firmware 自己的 Java log
        // "serialNumber=BAF006UBT10000377<方塊亂碼>" 只是 logcat/String 把
        // 尾隨的 \0 null byte 渲染成不可見方塊字元的顯示效果, 不代表
        // EEPROM 真的有垃圾殘留。RobotEventReceiver.java 讀取時已經用
        // indexOf('\0') 切掉這些 padding, 不需要也不應該在寫入那邊自己
        // padding 到某個定長 —— 太長的 payload (例如 32 bytes) 反而會讓
        // firmware 把 len byte 也當大了, 讀出來的欄位長度也跟著變,
        // 造成完全不同的殘留問題 (見專案內部事故記錄:「全域清零反而有
        // 2026-09 實測補充: 上面「讀出來很乾淨」只適用舊 SN 未郁過的情況。
        // 真幀 (f8 8f 28 00 00 37 00 42 41 46...6f 75 6d 61 6d 61 65 00 0c ed)
        // 證實: 曾經寫入較短 SN (17B "BAF006UBT10000001") 蓋過較長舊值之後,
        // 尾段會有 14 bytes 非零殘留 ("yy44567oumamae"), 唔係 0x00 padding。
        // 所以讀取側唔可以靠 \0 cut; 截尾規則見 truncateUuidTail (用戶已對
        // 實體貼紙確認真 SN 係 17 字, 尾段小寫殘留要斬走先係正確綁定 ID)。
        // 寫入格式本身不變, 這裡保持
        // v2 原本的 [len byte]+SN, 沒有 terminator 沒有 padding 的寫法,
        // 這才是經實機驗證過的正確格式。
        String v = ApiValidator.requireUuidValue(query);
        byte[] sn = v.getBytes(StandardCharsets.US_ASCII);
        // payload 格式實測確認是 [長度byte] + SN ASCII bytes — 沒有
        // terminator 沒有 padding! 讀取幾個 byte 是依這個 len byte 決定 (正常機
        // 讀出來是乾乾淨淨 N 字元 + firmware 自己 EEPROM 欄位的 0x00
        // padding, 不會有非零尾隨 bytes)。
        // checksum 包 LEN byte (7 + payload 總長) + cmd + Σpayload。
        byte[] payload = new byte[sn.length + 1];
        payload[0] = (byte) sn.length;
        System.arraycopy(sn, 0, payload, 1, sn.length);
        int sum = (7 + payload.length + 54) & 0xFF;
        for (byte b : payload) sum = (sum + (b & 0xFF)) & 0xFF;
        byte[] frame = new byte[payload.length + 9];
        frame[0] = (byte) 0xF8;
        frame[1] = (byte) 0x8F;
        frame[2] = (byte) (7 + payload.length);
        frame[3] = 0x00;
        frame[4] = 0x00;
        frame[5] = 54;
        System.arraycopy(payload, 0, frame, 6, payload.length);
        frame[6 + payload.length] = (byte) sum;
        frame[7 + payload.length] = (byte) 0xED;
        // pure-direct: 经 /dev/ttyS1 直发（旧 robot.chest_sendRawData 走 binder，已停用）。
        boolean sent = HardwareDirectManager.get(appContext).chest().sendRaw(frame);
        UbxErrorCode.API_ERROR_CODE code = MainActivity.directCode(sent);
        Log.i(TAG, "set_uuid -> " + v + " (" + sn.length + "B) " + code.name());
        // 2026-09 修正: 寫完唔好即刻 request_uuid —— alpha2services/firmware
        // 會 cache 開機讀到的 SN, 即刻讀返嚟多數係舊值, 經 robot_uuid event
        // 蓋走前端頭先樂觀顯示的新值, 睇落好似寫入失敗 (見 app-accel.js
        // uuidWriteNew() 已經樂觀顯示新值 + 提示要重啟, 嗰個先係正確流程)。
        // 舊碼 robot.requestRobotUUID() 而家仲係 no-op (無 alpha2services),
        // 直接唔再叫, 等用戶重啟後先 request_uuid 讀新值。
        // 2026-09: 記低今次寫入長度, 下次讀回截尾用 (見 truncateUuidTail
        // 規則 1) - 只在發送成功先記。
        if (code == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) {
            try {
                appContext.getSharedPreferences(MainActivity.PREFS_NAME, Context.MODE_PRIVATE).edit()
                        .putInt(PREF_UUID_WRITTEN_LEN, sn.length).apply();
            } catch (Throwable ignore) {
            }
        }
        return HttpServer.ApiResponse.ok(
                "{\"ok\":" + (code == UbxErrorCode.API_ERROR_CODE.API_ERROR_SUCCEED) + "}");
    }
}
