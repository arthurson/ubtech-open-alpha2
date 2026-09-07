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

    /** 记录一次成功的 cmd3 发送（20 轴全量）。 */
    public synchronized void update(int[] angles20) {
        if (angles20 == null || angles20.length != 20) return;
        System.arraycopy(angles20, 0, pose, 0, 20);
        known = true;
    }

    /** 当前命令位姿拷贝；未知返回 null。 */
    public synchronized int[] snapshot() {
        return known ? pose.clone() : null;
    }

    public synchronized boolean isKnown() {
        return known;
    }
}
