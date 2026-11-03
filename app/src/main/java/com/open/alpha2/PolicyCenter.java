package com.open.alpha2;

import android.content.Context;
import android.util.Log;

import com.ubtechinc.alpha.hardware.HardwareDirectManager;
import com.ubtechinc.alpha.hardware.DirectChestController;
import com.ubtechinc.alpha.hardware.policy.Alpha2PolicyIo;
import com.ubtechinc.alpha.hardware.policy.OnnxMlp;
import com.ubtechinc.alpha.hardware.policy.PolicyManifest;
import com.ubtechinc.alpha.hardware.policy.PolicyRunner;
import com.ubtechinc.alpha.hardware.ubx.UbxPlayer;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileInputStream;
import java.io.InputStream;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * ONNX policy 載入／運行（Microduck 式「policy.onnx ＋ manifest」部署，見 docs/onnx-policy.md）。
 *
 * <p>Policy 放喺 {@code <externalFilesDir>/policies/<name>/}（{@code adb push}）：
 * {@code policy.onnx}、{@code manifest.json}、{@code selftest.json}、（walk）{@code ref.json}；
 * 由 {@code scripts/policy_tool.py pack} 生成。推論由純 Java 嘅 {@link OnnxMlp} 做，迴圈由
 * {@link PolicyRunner} 喺 {@link UbxPlayer#startTask} 嘅 play 線程跑，所以其他動作／
 * {@code ubx/stop} 自動搶佔（硬停，舵機保持末位姿）。</p>
 *
 * <p>安全預設：{@code policy/start} 預設 {@code dry=true}（只計算、唔送 servo，排練用）；
 * 真機要明確 {@code dry=false}，而且 manifest 必須通過 sha256 吻合、selftest 通過、加速度計有數、
 * 命令位姿已知。Policy 永遠按自己訓練嘅 control_hz 跑；period &lt; 200ms（例如 50 Hz）嘅高頻
 * policy 真機要再加 {@code fast=true}（實驗：實機穩定性未驗證，靠 overrun 偵測自保）。</p>
 *
 * <p>多 policy 預載＋即時切換（Microduck 式 hot-swap）：{@code policy/load} 係「加入預載組（bank）」而唔係取代，
 * 最多 {@link #MAX_BANK} 個／合共 {@link #MAX_BANK_BYTES} bytes（呢個上限係 Alpha2 自己定嘅，Microduck 冇公開硬上限）。
 * 同一時間只有一個 policy 控制機身；{@code policy/switch} 喺運行中即時切換（下一格開頭生效，換入新 policy 嘅
 * period／速度包絡／ref，prev action 由上一個目標換算，見 {@link PolicyRunner}）。真機運行只會預載「有資格」
 * 嘅 policy（通過 sha256／selftest，非 fast 唔含高頻 policy）；唔合資格嘅會留喺 bank 但唔入 runner，切換被拒。</p>
 */
public final class PolicyCenter {
    private static final String TAG = "PolicyCenter";
    private static final int ACCEL_MAX_AGE_MS = 500;
    private static final long MAX_ONNX_BYTES = 4L * 1024 * 1024;
    private static final long MAX_SMALL_BYTES = 2L * 1024 * 1024;
    /** 預載組上限（Alpha2 自訂，防記憶體耗盡；每個 .onnx 另受 MAX_ONNX_BYTES 限制）。 */
    public static final int MAX_BANK = 8;
    public static final long MAX_BANK_BYTES = 16L * 1024 * 1024;

    private static final class Loaded {
        PolicyManifest man;
        OnnxMlp model;
        PolicyRunner.Ref ref;
        String dir;
        boolean hashOk;          // manifest 有 sha256 且吻合
        boolean selftestOk;      // selftest.json 存在且全部喺容差內
        boolean selftestPresent; // 有 selftest.json（面板上載冇；有就必須過）
        String warnings = "";
        String selftestInfo = "";
        String realBlock = "";   // 非空＝唔准真機（原因；唔計 fast）
        boolean needsFast;       // period < 200ms：真機要 fast=true
        long onnxBytes;          // policy.onnx 檔案大小（bank 總量上限用）
        String fastBlock = "";   // 非空＝連 fast 都唔准（例如 period < 20ms）
    }

    private final Context appContext;
    private final UbxPlayer ubxPlayer;
    private final DeviceStatus deviceStatus;

    /** 預載組（bank）：name → 已驗證嘅 policy。同步靠 synchronized(this)。 */
    private final Map<String, Loaded> bank = new LinkedHashMap<String, Loaded>();
    /** 揀咗嘅 policy：下一次 start 嘅目標；運行中跟住最近一次 switch，面板顯示用。 */
    private volatile Loaded loaded;
    private volatile PolicyRunner runner;
    private volatile boolean runnerDry = true;
    private volatile boolean runnerFast = false;
    private volatile float runSpeedOverride = 0f;   // 本次運行嘅覆寫（之後預載嘅 policy 都跟）
    private volatile float runTiltOverride = 0f;
    private volatile String lastError = "";

    public PolicyCenter(Context context, UbxPlayer ubxPlayer, DeviceStatus deviceStatus) {
        this.appContext = context.getApplicationContext();
        this.ubxPlayer = ubxPlayer;
        this.deviceStatus = deviceStatus;
    }

    // -- 檔案 ---------------------------------------------------------------

    private File policiesDir() {
        File base = null;
        try {
            base = appContext.getExternalFilesDir(null);
        } catch (Throwable ignore) { /* fall through */ }
        if (base == null) base = appContext.getFilesDir();
        return new File(base, "policies");
    }

    private static byte[] readAll(File f, long max) throws Exception {
        if (!f.isFile()) throw new IllegalArgumentException("missing file: " + f.getName());
        if (f.length() > max) throw new IllegalArgumentException(f.getName() + " too large (" + f.length() + " > " + max + ")");
        InputStream in = new FileInputStream(f);
        try {
            ByteArrayOutputStream bo = new ByteArrayOutputStream((int) f.length());
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toByteArray();
        } finally {
            try { in.close(); } catch (Exception ignore) { /* ignore */ }
        }
    }

    private static String hex(byte[] d) {
        StringBuilder sb = new StringBuilder();
        for (byte b : d) sb.append(String.format("%02x", b & 0xFF));
        return sb.toString();
    }

    // -- 載入 ---------------------------------------------------------------

    private static PolicyManifest parseManifest(JSONObject o) {
        PolicyManifest m = new PolicyManifest();
        m.schema = o.optInt("schema", 1);
        m.name = o.optString("name", "");
        m.obs = o.optString("obs", "stand");
        m.task = o.optString("task", "");
        m.holdS = o.optDouble("hold_s", m.holdS);
        m.then = o.optString("then", "");
        m.obsDim = o.optInt("obs_dim", 0);
        m.actionDim = o.optInt("action_dim", 20);
        m.actScale = o.optDouble("act_scale", m.actScale);
        m.controlHz = o.optDouble("control_hz", 0);
        m.moveOverlap = o.optDouble("move_overlap", m.moveOverlap);
        m.accelAxes = o.optString("accel_axes", m.accelAxes);
        m.ref = o.optString("ref", "");
        m.maxSpeedDps = o.optDouble("max_speed_dps", m.maxSpeedDps);
        m.maxRunS = o.optInt("max_run_s", m.maxRunS);
        m.leaseMs = o.optInt("lease_ms", m.leaseMs);
        m.tiltLimitDeg = o.optDouble("tilt_limit_deg", m.tiltLimitDeg);
        m.sha256 = o.optString("onnx_sha256", "");
        m.notes = o.optString("notes", "");
        return m;
    }

    /** 載入並驗證；失敗 throw IllegalArgumentException（訊息直接回俾 API）。 */
    private synchronized Loaded load(String name) throws Exception {
        if (!PolicyManifest.validName(name)) throw new IllegalArgumentException("bad policy name");
        File dir = new File(policiesDir(), name);
        if (!dir.isDirectory()) throw new IllegalArgumentException("policy not found: " + name
                + " (expected " + dir.getAbsolutePath() + ")");
        return loadFrom(dir, name);
    }

    private Loaded loadFrom(File dir, String name) throws Exception {
        Loaded l = new Loaded();
        l.dir = dir.getAbsolutePath();
        l.man = parseManifest(new JSONObject(new String(readAll(new File(dir, "manifest.json"), 64 * 1024), "UTF-8")));
        l.man.validate();
        if (!name.equals(l.man.name)) throw new IllegalArgumentException("manifest name '" + l.man.name + "' != directory '" + name + "'");
        byte[] onnx = readAll(new File(dir, "policy.onnx"), MAX_ONNX_BYTES);
        String digest = hex(MessageDigest.getInstance("SHA-256").digest(onnx));
        if (!l.man.sha256.isEmpty()) {
            if (!l.man.sha256.equals(digest)) throw new IllegalArgumentException("policy.onnx sha256 mismatch (manifest " + l.man.sha256 + ", file " + digest + ")");
            l.hashOk = true;
        }
        l.onnxBytes = onnx.length;
        l.model = OnnxMlp.load(onnx);
        if (l.model.inputDim() != l.man.obsDim) throw new IllegalArgumentException("model input " + l.model.inputDim() + " != manifest obs_dim " + l.man.obsDim);
        if (l.model.outputDim() != Alpha2PolicyIo.N) throw new IllegalArgumentException("model output " + l.model.outputDim() + " != 20");
        if ("walk".equals(l.man.obs) || "getup".equals(l.man.obs)) {
            JSONObject r = new JSONObject(new String(readAll(new File(dir, l.man.ref), MAX_SMALL_BYTES), "UTF-8"));
            JSONArray qa = r.getJSONArray("q");
            double[][] q = new double[qa.length()][];
            for (int i = 0; i < q.length; i++) {
                JSONArray row = qa.getJSONArray(i);
                q[i] = new double[row.length()];
                for (int j = 0; j < row.length(); j++) q[i][j] = row.getDouble(j);
            }
            if ("getup".equals(l.man.obs)) {
                // get-up：ref 唔循環（播完維持末格）；loop 參數 runner 唔用，填成全段。
                l.ref = new PolicyRunner.Ref(q, 0, q.length, r.optDouble("dt", 0.02));
            } else {
                JSONArray lp = r.getJSONArray("loop");
                l.ref = new PolicyRunner.Ref(q, lp.getInt(0), lp.getInt(1), r.optDouble("dt", 0.02));
            }
        }
        runSelftest(l, new File(dir, "selftest.json"));
        String[] why = new String[1];
        l.needsFast = l.man.periodMs() < PolicyManifest.MIN_PERIOD_MS;
        if (!l.man.realRunAllowed(why, true)) { l.fastBlock = why[0]; l.realBlock = why[0]; }
        else if (!l.hashOk) l.realBlock = "sha256 not verified";
        else if (l.selftestPresent && !l.selftestOk) l.realBlock = "selftest failed: " + l.selftestInfo;
        // 冇 selftest.json（面板上載）：照放行，但標明冇同 onnxruntime 對照過。
        if (!l.selftestPresent) l.warnings = "no selftest.json: on-device output not cross-checked against onnxruntime";
        return l;
    }

    /** 機上推論同 onnxruntime 對照（Microduck parity evidence 嘅機上版）。 */
    private static void runSelftest(Loaded l, File f) {
        try {
            if (!f.isFile()) { l.selftestInfo = "none"; return; }
            l.selftestPresent = true;
            JSONObject o = new JSONObject(new String(readAll(f, MAX_SMALL_BYTES), "UTF-8"));
            double tol = o.optDouble("tol", 2e-5);
            JSONArray cs = o.getJSONArray("cases");
            if (cs.length() == 0) { l.selftestInfo = "empty selftest"; return; }
            double worst = 0;
            for (int i = 0; i < cs.length(); i++) {
                JSONObject c = cs.getJSONObject(i);
                JSONArray oa = c.getJSONArray("obs"), aa = c.getJSONArray("action");
                float[] obs = new float[oa.length()];
                for (int j = 0; j < obs.length; j++) obs[j] = (float) oa.getDouble(j);
                float[] got = l.model.run(obs);
                if (got.length != aa.length()) { l.selftestInfo = "action length mismatch"; return; }
                for (int j = 0; j < got.length; j++) worst = Math.max(worst, Math.abs(got[j] - aa.getDouble(j)));
            }
            l.selftestInfo = "max|err|=" + worst + " tol=" + tol + " cases=" + cs.length();
            l.selftestOk = worst <= tol;
        } catch (Exception e) {
            l.selftestInfo = "selftest error: " + e.getMessage();
        }
    }

    // -- 運行 ---------------------------------------------------------------

    private boolean running() {
        PolicyRunner r = runner;
        return r != null && r.isRunning();
    }

    /** 真機（非 dry）時，呢個 policy 可唔可以入 runner：通過資格檢查，而且高頻 policy 要本次運行有 fast。 */
    private static boolean eligible(Loaded b, boolean dry, boolean fast) {
        return dry || (b.realBlock.isEmpty() && (!b.needsFast || fast));
    }

    private static String ineligibleReason(Loaded b, boolean fast) {
        if (!b.realBlock.isEmpty()) return b.realBlock;
        if (b.needsFast && !fast) return "high-rate policy (period " + b.man.periodMs() + " ms < " + PolicyManifest.MIN_PERIOD_MS
                + " ms floor) needs a run started with fast=true";
        return "";
    }

    /** 每個 policy 自己嘅 runner 設定（period／act_scale／ref／速度包絡…）；speed／tilt 覆寫套用於所有 slot。 */
    private PolicyRunner.Config buildConfig(Loaded l, boolean dry, float speedOverride, float tiltOverride) {
        PolicyRunner.Config c = new PolicyRunner.Config();
        PolicyManifest m = l.man;
        // Policy 永遠按自己嘅 control_hz 跑（唔放慢）；非 fast 且 period < 200ms 嘅只可能係 dry（真機已擋）。
        c.periodMs = Math.max(m.periodMs(), dry ? 1 : PolicyManifest.FAST_MIN_PERIOD_MS);
        c.moveOverlap = (float) m.moveOverlap;
        c.actScale = m.actScale;
        c.obsDim = m.obsDim;
        c.axes = Alpha2PolicyIo.parseAxes(m.accelAxes);
        c.lo = new int[Alpha2PolicyIo.N];
        c.hi = new int[Alpha2PolicyIo.N];
        for (int i = 0; i < Alpha2PolicyIo.N; i++) {
            c.lo[i] = ApiValidator.servoCalMin(i + 1);
            c.hi[i] = ApiValidator.servoCalMax(i + 1);
        }
        c.maxSpeedDps = speedOverride > 0 ? speedOverride : m.maxSpeedDps;
        c.tiltLimitRad = Math.toRadians(tiltOverride > 0 ? tiltOverride : m.tiltLimitDeg);
        c.leaseMs = m.leaseMs;
        c.maxRunMs = m.maxRunS * 1000L;
        c.dry = dry;
        c.ref = l.ref;
        if ("getup".equals(m.obs)) {
            c.getup = true;
            c.lying = m.lyingSide();
            c.holdS = m.holdS;
            c.then = m.then;
            // 運行時間至少夠：settle（最長 6 s）＋ ref ＋ hold ＋ 餘裕，唔好俾 manifest 嘅 max_run_s 提早切斷起身動作。
            long need = (long) ((6.0 + l.ref.frames() * l.ref.dtSec() + m.holdS + 10.0) * 1000);
            if (c.maxRunMs < need) c.maxRunMs = need;
        }
        return c;
    }

    private synchronized HttpServer.ApiResponse start(String name, boolean dry, boolean fast, float speedOverride, float tiltOverride) {
        // 可選 name：由預載組揀起步 policy（運行中再叫 start 只係續租約，唔改揀選——要換就用 policy/switch）。
        if (name != null && !name.isEmpty() && !running()) {
            Loaded pick = bank.get(name);
            if (pick == null) return HttpServer.ApiResponse.badRequest("policy not preloaded: " + name + " (policy/load first)");
            loaded = pick;
        }
        Loaded l = loaded;
        if (l == null) return HttpServer.ApiResponse.badRequest("no policy loaded (policy/load first)");
        if (running()) { runner.beat(); return statusResponse(); }   // 同 walk/start 一樣：再叫即續租約
        if (!MainActivity.isBootServoReady()) return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"booting\"}");
        final DirectChestController chest = HardwareDirectManager.get(appContext).chest();
        if (!dry) {
            if (!l.realBlock.isEmpty()) return HttpServer.ApiResponse.badRequest("real run refused: " + l.realBlock + " (use dry=true to rehearse)");
            if (l.needsFast && !fast) return HttpServer.ApiResponse.badRequest("real run refused: high-rate policy (period "
                    + l.man.periodMs() + " ms < " + PolicyManifest.MIN_PERIOD_MS
                    + " ms stable-gait floor) needs fast=true (experimental; keep the robot supported/harnessed, start with a low max_speed_dps)");
            if (!chest.isAvailable()) return HttpServer.ApiResponse.error("chest not available");
            if (!ubxPlayer.poseKnown()) return HttpServer.ApiResponse.badRequest("servo pose unknown: stand up first (boot stand-up or play an action)");
        }
        if (!deviceStatus.hasAccelerometer()) return HttpServer.ApiResponse.error("no accelerometer sensor on this device");
        if (!deviceStatus.isAccelerometerEnabled()) {
            Map<String, String> q = new HashMap<String, String>();
            q.put("on", "true");
            deviceStatus.accelerometerSet(q);
        }
        PolicyManifest m = l.man;
        PolicyRunner.Config c = buildConfig(l, dry, speedOverride, tiltOverride);
        // 預載組入面全部合資格嘅 policy 一齊入 runner（起步嗰個一定合資格：上面已擋）。
        List<PolicyRunner.Slot> slots = new ArrayList<PolicyRunner.Slot>();
        for (Loaded b : bank.values()) {
            if (!eligible(b, dry, fast)) continue;
            slots.add(new PolicyRunner.Slot(b.man.name, b.model, b == l ? c : buildConfig(b, dry, speedOverride, tiltOverride)));
        }
        final boolean fdry = dry;
        PolicyRunner r;
        try {
            r = new PolicyRunner(slots, m.name, new PolicyRunner.Sender() {
                @Override public boolean send(int[] angles, int timeMs) {
                    try {
                        if (chest.playAllServos(angles, (short) timeMs)) {
                            ubxPlayer.notePose(angles);          // 命令位姿追踪（成功先記）
                            return true;
                        }
                    } catch (Exception e) {
                        Log.w(TAG, "send failed: " + e.getMessage());
                    }
                    return false;
                }
            }, new PolicyRunner.AccelSource() {
                @Override public double[] accel() { return deviceStatus.accelSmoothed(ACCEL_MAX_AGE_MS); }
            }, new PolicyRunner.PoseSource() {
                @Override public int[] pose() {
                    int[] p = ubxPlayer.pose();
                    // dry 排練：位姿未知就當喺預設企姿（反正唔送 servo）
                    return p != null ? p : (fdry ? Alpha2PolicyIo.servoDefault() : null);
                }
            });
        } catch (IllegalArgumentException e) {
            return HttpServer.ApiResponse.badRequest(e.getMessage());
        }
        // 普通動作播放中：搶佔（同 WalkCenter 一致）。
        if (ubxPlayer.isPlaying()) ubxPlayer.stop();
        final PolicyRunner fr = r;
        final boolean quiet = !dry && fast;        // fast＝實驗高頻模式：關閉每幀 TX log（切換後可能跑 20ms policy）
        Runnable task = new Runnable() {
            @Override public void run() {
                try {
                    // 高頻控制：提高線程優先級、關閉每幀 TX log；結束必還原。
                    try { android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_DISPLAY); }
                    catch (Throwable ignore) { /* best effort */ }
                    if (quiet) chest.setQuietTx(true);
                    fr.run();
                } finally {
                    // adb logcat -s PolicyCenter 睇結束原因（面板亦會顯示）
                    float[][] tr = fr.trace();
                    float[] last = tr.length > 0 ? tr[tr.length - 1] : null;
                    Log.i(TAG, "policy end reason=" + fr.endReason() + " steps=" + fr.steps()
                            + (last != null ? " lastTilt=" + last[1] + " lastAccel=" + last[5] + "," + last[6] + "," + last[7] : ""));
                    if (quiet) chest.setQuietTx(false);
                }
            }
        };
        fr.markStarting();     // 回應 status 前就要見到 running=true（見 PolicyRunner.markStarting）
        if (!ubxPlayer.startTask("policy:" + m.name + (dry ? " (dry)" : "") + (fast ? " [fast]" : ""), task)) {
            fr.cancelStart();
            lastError = ubxPlayer.lastError();
            return HttpServer.ApiResponse.error("cannot start policy: " + lastError);
        }
        runner = r;
        runnerDry = dry;
        runnerFast = fast;
        runSpeedOverride = speedOverride;
        runTiltOverride = tiltOverride;
        lastError = "";
        return statusResponse();
    }


    // -- 預載組（bank）-------------------------------------------------------

    /** 呢個 policy 而家係咪運行會話入面用緊／排緊切換嘅目標（用緊嘅唔准取代／移除）。 */
    private boolean inUse(String name) {
        PolicyRunner r = runner;
        return r != null && r.isRunning() && (name.equals(r.activeName()) || name.equals(r.pendingSwitch()));
    }

    /** 檢查加入 l 會唔會超出預載組上限（唔改任何嘢）。呼叫者持鎖。 */
    private void bankCheck(Loaded l) {
        String n = l.man.name;
        if (!bank.containsKey(n) && bank.size() >= MAX_BANK) {
            throw new IllegalArgumentException("preload bank full (" + MAX_BANK + " policies): policy/unload one first");
        }
        long total = l.onnxBytes;
        for (Loaded b : bank.values()) if (!b.man.name.equals(n)) total += b.onnxBytes;
        if (total > MAX_BANK_BYTES) {
            throw new IllegalArgumentException("preload bank too large (" + total + " > " + MAX_BANK_BYTES + " bytes of .onnx): policy/unload one first");
        }
    }

    /** 加入／取代預載組；運行中同步入 runner（取代同名閒置 slot；用緊嘅 policy 唔准取代）。呼叫者持鎖。 */
    private void bankPut(Loaded l) {
        bankCheck(l);
        String n = l.man.name;
        PolicyRunner r = runner;
        if (r != null && r.isRunning()) {
            try {
                if (eligible(l, runnerDry, runnerFast)) {
                    r.putSlot(new PolicyRunner.Slot(n, l.model, buildConfig(l, runnerDry, runSpeedOverride, runTiltOverride)));
                } else {
                    r.removeSlot(n);        // 新版唔合資格：舊版都唔應該留喺 runner
                }
            } catch (IllegalStateException e) {
                throw new IllegalArgumentException("policy '" + n + "' is in use by the running session: policy/switch to another one first");
            }
        }
        bank.put(n, l);
    }

    /** 由預載組（同運行中嘅 runner）移除；呼叫者持鎖，並且已經確認唔係用緊。 */
    private void bankRemove(String name) {
        PolicyRunner r = runner;
        if (r != null && r.isRunning()) {
            try { r.removeSlot(name); } catch (IllegalStateException e) { /* inUse 已擋；競態下保守保留 */ return; }
        }
        bank.remove(name);
        Loaded cur = loaded;
        if (cur != null && name.equals(cur.man.name)) {
            loaded = bank.isEmpty() ? null : bank.values().iterator().next();
        }
    }

    // -- 上載／刪除（面板 ONNX 輸入介面）-----------------------------------------

    private static final String[] KNOWN = {"policy.onnx", "ref.json", "manifest.json", "selftest.json"};

    private static byte[] readCapped(java.io.InputStream in, long max, String what) throws Exception {
        ByteArrayOutputStream bo = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        long total = 0;
        int n;
        while ((n = in.read(buf)) > 0) {
            total += n;
            if (total > max) throw new IllegalArgumentException(what + " too large (> " + max + " bytes)");
            bo.write(buf, 0, n);
        }
        return bo.toByteArray();
    }

    private static void deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) for (File k : kids) deleteRecursive(k);
        f.delete();
    }

    private static String baseName(String entry) {
        int i = Math.max(entry.lastIndexOf('/'), entry.lastIndexOf('\\'));
        return (i >= 0 ? entry.substring(i + 1) : entry).toLowerCase(java.util.Locale.US);
    }

    /**
     * /upload/policy?name=…&amp;kind=zip|onnx[&amp;obs=&amp;control_hz=&amp;act_scale=&amp;max_speed_dps=&amp;accel_axes=]。
     * kind=zip：zip 內取 policy.onnx（或唯一一個 *.onnx）、ref.json、manifest.json、selftest.json，其他檔
     * （model.zip、meta.json、*.pth…）一律唔讀；kind=onnx：body 就係 .onnx。冇 manifest 就按參數生成
     * （obs 缺省由模型輸入維度推斷 43/65）。sha256 由 server 計；上載即載入（驗證通過先覆蓋舊目錄）。
     */
    public HttpServer.ApiResponse handleUpload(Map<String, String> query, byte[] body) {
        try {
            String name = ApiValidator.require(query, "name");
            if (!PolicyManifest.validName(name)) return HttpServer.ApiResponse.badRequest("bad policy name");
            if (inUse(name)) return HttpServer.ApiResponse.badRequest("policy '" + name + "' is in use by the running session: switch away or stop it before replacing it");
            String kind = ApiValidator.optionalEnum(query, "kind", new String[] {"zip", "onnx"}, "zip");
            Map<String, byte[]> files = new HashMap<String, byte[]>();
            if ("onnx".equals(kind)) {
                if (body.length > MAX_ONNX_BYTES) return HttpServer.ApiResponse.badRequest("policy.onnx too large");
                files.put("policy.onnx", body);
            } else {
                ZipInputStream zin = new ZipInputStream(new ByteArrayInputStream(body));
                byte[] otherOnnx = null;
                int entries = 0;
                ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    if (++entries > 64) return HttpServer.ApiResponse.badRequest("zip has too many entries");
                    if (e.isDirectory()) continue;
                    String bn = baseName(e.getName());
                    boolean known = false;
                    for (String k : KNOWN) if (k.equals(bn)) known = true;
                    boolean isOnnx = bn.endsWith(".onnx");
                    if (!known && !isOnnx) continue;                    // 其他檔唔讀（防 zip bomb／model.zip）
                    byte[] data = readCapped(zin, "policy.onnx".equals(bn) || isOnnx ? MAX_ONNX_BYTES : MAX_SMALL_BYTES, bn);
                    if (known) files.put(bn, data);
                    else if (otherOnnx == null) otherOnnx = data;
                    else return HttpServer.ApiResponse.badRequest("zip has more than one .onnx (name it policy.onnx)");
                }
                if (!files.containsKey("policy.onnx") && otherOnnx != null) files.put("policy.onnx", otherOnnx);
            }
            byte[] onnx = files.get("policy.onnx");
            if (onnx == null) return HttpServer.ApiResponse.badRequest("no policy.onnx in upload");
            String digest = hex(MessageDigest.getInstance("SHA-256").digest(onnx));

            JSONObject man;
            if (files.containsKey("manifest.json")) {
                man = new JSONObject(new String(files.get("manifest.json"), "UTF-8"));
                String have = man.optString("onnx_sha256", "");
                if (!have.isEmpty() && !have.equals(digest)) return HttpServer.ApiResponse.badRequest("manifest onnx_sha256 does not match policy.onnx");
            } else {
                OnnxMlp probe;
                try { probe = OnnxMlp.load(onnx); }
                catch (IllegalArgumentException ex) { return HttpServer.ApiResponse.badRequest("not a supported ONNX policy: " + ex.getMessage()); }
                String obs = ApiValidator.optionalEnum(query, "obs", new String[] {"auto", "stand", "walk"}, "auto");
                if ("auto".equals(obs)) {
                    obs = probe.inputDim() == Alpha2PolicyIo.OBS_WALK ? "walk"
                            : probe.inputDim() == Alpha2PolicyIo.OBS_STAND ? "stand" : "";
                    if (obs.isEmpty()) return HttpServer.ApiResponse.badRequest("model input " + probe.inputDim() + " is neither 43 (stand) nor 65 (walk)");
                }
                man = new JSONObject();
                man.put("schema", 1);
                man.put("obs", obs);
                man.put("obs_dim", "walk".equals(obs) ? Alpha2PolicyIo.OBS_WALK : Alpha2PolicyIo.OBS_STAND);
                man.put("action_dim", Alpha2PolicyIo.N);
                // float→double 會出 0.20000000298…：經字串轉返十進位。
                man.put("act_scale", dec(ApiValidator.optionalFloatRange(query, "act_scale", 0.01f, 0.6f, "walk".equals(obs) ? 0.2f : 0.3f)));
                man.put("control_hz", dec(ApiValidator.optionalFloatRange(query, "control_hz", 0.1f, 100f, 50f)));
                man.put("max_speed_dps", dec(ApiValidator.optionalFloatRange(query, "max_speed_dps", 10f, (float) PolicyManifest.MAX_SPEED_DPS_CAP, 90f)));
                man.put("accel_axes", ApiValidator.optional(query, "accel_axes", "+x+y+z"));
                if ("walk".equals(obs)) man.put("ref", "ref.json");
                man.put("notes", "uploaded via web panel");
            }
            man.put("name", name);
            man.put("onnx_sha256", digest);

            File root = policiesDir();
            File staging = new File(root, "." + name + ".staging");
            if (staging.exists()) deleteRecursive(staging);
            if (!staging.mkdirs()) return HttpServer.ApiResponse.error("cannot create " + staging.getAbsolutePath());
            boolean committed = false;
            try {
                writeFile(new File(staging, "policy.onnx"), onnx);
                writeFile(new File(staging, "manifest.json"), man.toString(2).getBytes("UTF-8"));
                if (files.containsKey("ref.json")) writeFile(new File(staging, "ref.json"), files.get("ref.json"));
                if (files.containsKey("selftest.json")) writeFile(new File(staging, "selftest.json"), files.get("selftest.json"));
                Loaded l;
                synchronized (this) {
                    l = loadFrom(staging, name);            // 驗證（結構／算子／ref／sha）過先覆蓋舊目錄
                    bankCheck(l);                           // 預載組超限就唔好動舊目錄
                    File dst = new File(root, name);
                    if (dst.exists()) deleteRecursive(dst);
                    if (!staging.renameTo(dst)) return HttpServer.ApiResponse.error("cannot move policy into place");
                    committed = true;
                    l.dir = dst.getAbsolutePath();
                    bankPut(l);
                    // 閒置：揀呢個做下次起步；運行中：只加入預載組（要用就 policy/switch）。
                    if (!running() || (loaded != null && name.equals(loaded.man.name))) loaded = l;
                    lastError = "";
                }
            } finally {
                if (!committed) deleteRecursive(staging);
            }
            return statusResponse();
        } catch (IllegalArgumentException e) {
            lastError = String.valueOf(e.getMessage());
            return HttpServer.ApiResponse.badRequest(lastError);
        } catch (Exception e) {
            Log.w(TAG, "policy upload failed", e);
            lastError = String.valueOf(e);
            return HttpServer.ApiResponse.error("upload failed: " + lastError);
        }
    }

    private static double dec(float f) { return Double.parseDouble(Float.toString(f)); }

    private static void writeFile(File f, byte[] data) throws Exception {
        FileOutputStream out = new FileOutputStream(f);
        try { out.write(data); out.getFD().sync(); } finally { out.close(); }
    }

    /** policy/delete?name=：刪除機上 policy 目錄（跑緊唔准）。 */
    public HttpServer.ApiResponse deleteResponse(Map<String, String> query) {
        String name = ApiValidator.require(query, "name");
        if (!PolicyManifest.validName(name)) return HttpServer.ApiResponse.badRequest("bad policy name");
        if (inUse(name)) return HttpServer.ApiResponse.badRequest("policy '" + name + "' is in use by the running session: switch away or stop it first");
        synchronized (this) {
            File dir = new File(policiesDir(), name);
            if (!dir.isDirectory()) return HttpServer.ApiResponse.badRequest("policy not found: " + name);
            deleteRecursive(dir);
            bankRemove(name);
        }
        return listResponse();
    }

    // -- HTTP ---------------------------------------------------------------

    public HttpServer.ApiResponse listResponse() {
        File[] ds = policiesDir().listFiles();
        StringBuilder sb = new StringBuilder("[");
        boolean first = true;
        if (ds != null) {
            Arrays.sort(ds);
            for (File d : ds) {
                if (d.isDirectory() && PolicyManifest.validName(d.getName()) && new File(d, "manifest.json").isFile()) {
                    if (!first) sb.append(',');
                    sb.append('"').append(JsonUtil.esc(d.getName())).append('"');
                    first = false;
                }
            }
        }
        StringBuilder pre = new StringBuilder("[");
        synchronized (this) {
            boolean f2 = true;
            for (String n : bank.keySet()) {
                if (!f2) pre.append(',');
                pre.append('"').append(JsonUtil.esc(n)).append('"');
                f2 = false;
            }
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"dir\":\"" + JsonUtil.esc(policiesDir().getAbsolutePath())
                + "\",\"policies\":" + sb.append(']') + ",\"loaded\":" + pre.append(']') + "}");
    }

    public HttpServer.ApiResponse loadResponse(Map<String, String> query) {
        String name = ApiValidator.require(query, "name");
        if (inUse(name)) return HttpServer.ApiResponse.badRequest("policy '" + name + "' is in use by the running session: switch away or stop it before reloading it");
        try {
            synchronized (this) {
                Loaded l = load(name);
                bankPut(l);                                  // 加入預載組（唔取代其他已載入嘅）
                if (!running() || (loaded != null && name.equals(loaded.man.name))) loaded = l;
            }
            lastError = "";
        } catch (IllegalArgumentException e) {
            lastError = String.valueOf(e.getMessage());
            return HttpServer.ApiResponse.badRequest(lastError);
        } catch (Exception e) {
            lastError = String.valueOf(e);
            Log.w(TAG, "load failed", e);
            return HttpServer.ApiResponse.error("load failed: " + lastError);
        }
        return statusResponse();
    }

    /**
     * policy/switch?name=：即時切換去預載組入面嘅另一個 policy（Microduck 式 hot-swap）。
     * 運行中：喺下一格開頭生效（回應當刻 status 會見到 pendingSwitch，之後 active 變成新名）；
     * 真機運行只可以切去「有資格」嘅 policy。閒置：只係揀呢個做下次 policy/start 嘅目標。
     */
    public HttpServer.ApiResponse switchResponse(Map<String, String> query) {
        String name = ApiValidator.require(query, "name");
        synchronized (this) {
            Loaded l = bank.get(name);
            if (l == null) return HttpServer.ApiResponse.badRequest("policy not preloaded: " + name + " (policy/load first)");
            PolicyRunner r = runner;
            if (r != null && r.isRunning()) {
                if (!eligible(l, runnerDry, runnerFast)) {
                    return HttpServer.ApiResponse.badRequest("switch refused: " + ineligibleReason(l, runnerFast));
                }
                String err = r.requestSwitch(name);
                if (!err.isEmpty()) return HttpServer.ApiResponse.badRequest("switch refused: " + err);
            }
            loaded = l;
            lastError = "";
        }
        return statusResponse();
    }

    /** policy/unload?name=：由預載組移除（磁碟檔案保留）。運行中用緊／排緊切換嘅唔准。 */
    public HttpServer.ApiResponse unloadResponse(Map<String, String> query) {
        String name = ApiValidator.require(query, "name");
        if (!PolicyManifest.validName(name)) return HttpServer.ApiResponse.badRequest("bad policy name");
        synchronized (this) {
            if (inUse(name)) return HttpServer.ApiResponse.badRequest("policy '" + name + "' is in use by the running session: switch away first");
            if (!bank.containsKey(name)) return HttpServer.ApiResponse.badRequest("policy not preloaded: " + name);
            bankRemove(name);
        }
        return statusResponse();
    }

    /** policy/start：dry 預設 true（排練）；真機 dry=false。已經跑緊＝續租約。 */
    public HttpServer.ApiResponse startResponse(Map<String, String> query) {
        boolean dry = ApiValidator.optionalBoolean(query, "dry", true);
        boolean fast = ApiValidator.optionalBoolean(query, "fast", false);
        // 速度包絡覆寫（°/s）：逐步加大去測試，唔使重新打包；0＝用 manifest 值。
        float sp = ApiValidator.optionalFloatRange(query, "max_speed_dps", 10, PolicyManifest.MAX_SPEED_DPS_CAP, 0f);
        float tilt = ApiValidator.optionalFloatRange(query, "tilt_limit_deg", 10, 60, 0f);
        return start(ApiValidator.optional(query, "name", ""), dry, fast, sp, tilt);
    }

    /**
     * policy/beat：只係續租約並回狀態；冇運行就乜都唔做（唔會啟動）——面板心跳專用，
     * 避免用 policy/start 做心跳時喺 policy 剛結束嘅一刻意外重新開跑。
     */
    public HttpServer.ApiResponse beatResponse() {
        PolicyRunner r = runner;
        if (r != null && r.isRunning()) r.beat();
        return statusResponse();
    }

    /** policy/stop：硬停（舵機保持末位姿）。只停 policy，唔會掂其他播放。 */
    public HttpServer.ApiResponse stopResponse() {
        if (running()) ubxPlayer.stop();
        return statusResponse();
    }

    private static String arr(float[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); sb.append(a[i]); }
        return sb.append(']').toString();
    }

    private static String arr(int[] a) {
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < a.length; i++) { if (i > 0) sb.append(','); sb.append(a[i]); }
        return sb.append(']').toString();
    }

    public HttpServer.ApiResponse statusResponse() {
        Loaded l = loaded;
        PolicyRunner r = runner;
        boolean run = r != null && r.isRunning();
        StringBuilder sb = new StringBuilder("{\"ok\":true");
        sb.append(",\"loaded\":").append(l != null);
        if (l != null) {
            PolicyManifest m = l.man;
            sb.append(",\"name\":\"").append(JsonUtil.esc(m.name)).append('"');
            sb.append(",\"obs\":\"").append(m.obs).append("\",\"obsDim\":").append(m.obsDim);
            sb.append(",\"controlHz\":").append(m.controlHz).append(",\"periodMs\":").append(m.periodMs());
            sb.append(",\"actScale\":").append(m.actScale);
            sb.append(",\"hashVerified\":").append(l.hashOk);
            sb.append(",\"selftestOk\":").append(l.selftestOk);
            sb.append(",\"selftestPresent\":").append(l.selftestPresent);
            sb.append(",\"warnings\":\"").append(JsonUtil.esc(l.warnings)).append('"');
            sb.append(",\"selftest\":\"").append(JsonUtil.esc(l.selftestInfo)).append('"');
            sb.append(",\"realRunAllowed\":").append(l.realBlock.isEmpty());
            sb.append(",\"realRunBlock\":\"").append(JsonUtil.esc(l.realBlock)).append('"');
            sb.append(",\"needsFast\":").append(l.needsFast);
        }
        sb.append(",\"running\":").append(run);
        sb.append(",\"dry\":").append(runnerDry);
        sb.append(",\"fast\":").append(runnerFast);
        // 預載組：每個 policy 一項（active＝運行會話而家控制機身；selected＝下次起步／最近揀嘅；inRun＝已入 runner）
        String activeNow = r != null ? r.activeName() : "";
        List<String> inRunNames = run ? r.slotNames() : new ArrayList<String>();
        sb.append(",\"bankMax\":").append(MAX_BANK).append(",\"bank\":[");
        synchronized (this) {
            boolean firstB = true;
            for (Loaded b : bank.values()) {
                PolicyManifest bm = b.man;
                if (!firstB) sb.append(',');
                firstB = false;
                sb.append("{\"name\":\"").append(JsonUtil.esc(bm.name)).append('"');
                sb.append(",\"obs\":\"").append(bm.obs).append("\",\"obsDim\":").append(bm.obsDim);
                sb.append(",\"task\":\"").append(JsonUtil.esc(bm.effectiveTask())).append('"');
                sb.append(",\"then\":\"").append(JsonUtil.esc(bm.then)).append('"');
                sb.append(",\"controlHz\":").append(bm.controlHz).append(",\"periodMs\":").append(bm.periodMs());
                sb.append(",\"actScale\":").append(bm.actScale);
                sb.append(",\"needsFast\":").append(b.needsFast);
                sb.append(",\"realRunAllowed\":").append(b.realBlock.isEmpty());
                sb.append(",\"realRunBlock\":\"").append(JsonUtil.esc(b.realBlock)).append('"');
                sb.append(",\"selected\":").append(l != null && bm.name.equals(l.man.name));
                sb.append(",\"active\":").append(run && bm.name.equals(activeNow));
                sb.append(",\"inRun\":").append(inRunNames.contains(bm.name));
                sb.append('}');
            }
        }
        sb.append(']');
        sb.append(",\"accelAvailable\":").append(deviceStatus.hasAccelerometer());
        sb.append(",\"accelEnabled\":").append(deviceStatus.isAccelerometerEnabled());
        if (r != null) {
            sb.append(",\"steps\":").append(r.steps());
            sb.append(",\"tiltDeg\":").append(String.format(java.util.Locale.US, "%.2f", r.tiltDeg()));
            sb.append(",\"inferUs\":").append(r.lastInferUs());
            sb.append(",\"overruns\":").append(r.overruns());
            sb.append(",\"maxStepUs\":").append(r.maxStepUs());
            sb.append(",\"maxSendUs\":").append(r.maxSendUs());
            sb.append(",\"active\":\"").append(JsonUtil.esc(activeNow)).append('"');
            String pendingSw = r.pendingSwitch();
            sb.append(",\"pendingSwitch\":\"").append(pendingSw == null ? "" : JsonUtil.esc(pendingSw)).append('"');
            sb.append(",\"switches\":").append(r.switches());
            sb.append(",\"switchLog\":[");
            List<String> swl = r.switchLog();
            for (int i = 0; i < swl.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(JsonUtil.esc(swl.get(i))).append('"');
            }
            sb.append(']');
            sb.append(",\"periodMsEff\":").append(r.config().periodMs);
            sb.append(",\"maxSpeedDps\":").append(r.config().maxSpeedDps);
            sb.append(",\"tiltLimitDeg\":").append(String.format(java.util.Locale.US, "%.1f", Math.toDegrees(r.config().tiltLimitRad)));
            if (!run) {
                // 停低後附最近 64 步診斷（欄位見 traceCols；非有限值＝null）
                sb.append(",\"traceCols\":[");
                for (int i = 0; i < PolicyRunner.TRACE_COLS.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append('"').append(PolicyRunner.TRACE_COLS[i]).append('"');
                }
                sb.append("],\"trace\":[");
                float[][] tr = r.trace();
                for (int i = 0; i < tr.length; i++) {
                    if (i > 0) sb.append(',');
                    sb.append('[');
                    for (int j = 0; j < tr[i].length; j++) {
                        if (j > 0) sb.append(',');
                        float v = tr[i][j];
                        if (Float.isNaN(v) || Float.isInfinite(v)) sb.append("null");
                        else sb.append(String.format(java.util.Locale.US, "%.3f", v));
                    }
                    sb.append(']');
                }
                sb.append(']');
            }
            sb.append(",\"action\":").append(arr(r.lastAction()));
            sb.append(",\"servo\":").append(arr(r.lastServo()));
            sb.append(",\"endReason\":\"").append(run ? "" : JsonUtil.esc(r.endReason())).append('"');
        }
        sb.append(",\"error\":\"").append(JsonUtil.esc(lastError)).append("\"}");
        return HttpServer.ApiResponse.ok(sb.toString());
    }
}
