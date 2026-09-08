package com.ubtechinc.alpha.hardware;

/**
 * 命令位姿追踪（dead reckoning）：記下最後一次經本 App 發出的 20 軸命令值。
 *
 * <p>2026-09-06 修正舊認知：cmd 13 回包<b>不是</b>恒定的——官方 PC tuner
 * 實測回的是會變的 signed 實測值（且本 App 已改行 live 實讀）；cmd 5
 *（05 00 頭）<b>能</b>驱动本机舵机（官方 tuner ＋用戶目視確認）。舊「只有
 * cmd 3 能動、cmd13 恒定」結論疑似源於舊 00 00 發送頭，作廢。</p>
 *
 * <p>追踪值只代表「最後一次命令」，不等於實際位置，跳舞/重啟/官方 tuner
 * 郁過之後即過時——因此<b>禁止</b>用佢嚟補齊全幀發送（曾導致 19 軸齊扯錯位
 * 的「發狂」事故）。單舵機一律走 cmd05 只郁目標一粒。開機到首次全量發送
 * 之前位姿未知（{@link #snapshot()} 返回 null），調用方須如實報 unknown，
 * 不可编造。</p>
 */
public final class ServoPoseTracker {
    private final int[] pose = new int[20];
    private volatile boolean known = false;
    // 2026-09-08 新增：逐軸 known 旗（servoAngleAllResponse 命令位姿榜用）。
    // 背景：全量 known 要等一次 servo/all 全發，開機後好耐都未知；但單軸
    // servo/one 成功即代表嗰一軸肯定喺命令位（±1° 誤差內），逐軸記低，
    // 使個榜儘快有嘢睇——依然零 wire，依然唔保證其他軸（跳舞／重啟後過時）。
    private final boolean[] knownIdx = new boolean[20];

    /** 记录一次成功的 cmd3 发送（20 轴全量）。 */
    public synchronized void update(int[] angles20) {
        if (angles20 == null || angles20.length != 20) return;
        System.arraycopy(angles20, 0, pose, 0, 20);
        java.util.Arrays.fill(knownIdx, true);
        known = true;
    }

    /** 记录一次成功的 cmd05 单发（只 mark 嗰一軸 known，其餘唔郁）。 */
    public synchronized void updateOne(int id, int angle) {
        if (id < 1 || id > 20) return;
        pose[id - 1] = angle;
        knownIdx[id - 1] = true;
    }

    /** 当前命令位姿拷贝；未知返回 null。 */
    public synchronized int[] snapshot() {
        return known ? pose.clone() : null;
    }

    /** 逐軸版 snapshot：未知嗰軸回 null（servoAngleAllResponse 直接用嚟填榜）。 */
    public synchronized Integer[] snapshotBoxed() {
        Integer[] out = new Integer[20];
        for (int i = 0; i < 20; i++) {
            out[i] = knownIdx[i] ? Integer.valueOf(pose[i]) : null;
        }
        return out;
    }

    public synchronized boolean isKnown() {
        return known;
    }
}
