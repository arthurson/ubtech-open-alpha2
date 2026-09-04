package com.ubtechinc.alpha.hardware;

/**
 * 命令位姿追踪（dead reckoning）。
 *
 * <p>背景：本机胸固件（1.1.7.3 系）UART 上<b>无实时角度回授</b>——实测
 * {@code cmd 13} 回包恒定不变（跳舞途中亦然），且只有 {@code cmd 3}
 * 能驱动舵机（{@code cmd 5/52} 有 ACK 但不动作）。3.0.0.2 的读角走自家
 * motor driver 通道，与胸 UART 不是同一条路，本机无此服务。</p>
 *
 * <p>因此“读角度”只能是<b>命令位姿</b>（最后一次经 {@code cmd 3} 发出的
 * 20 轴），而非测量值。对 tuner 备份/恢复/搬运位姿这个用途，命令位姿
 * 恰好就是正确语义。开机到首次 {@code cmd 3} 之前位姿未知
 * （{@link #snapshot()} 返回 null），调用方须如实报 unknown，不可编造。</p>
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
