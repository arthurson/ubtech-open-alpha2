package com.open.alpha2;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 解壓外來 zip（動作包、Vosk 模型）嘅共用實作：zip-slip 防護（entry 必須解到目標目錄之內，否則跳過）
 * ＋ 壓縮炸彈／寫滿磁碟保護。
 *
 * <p>以前兩個 controller 各有一份幾乎一樣嘅解壓代碼，只擋路徑跳出，冇任何大小限制：一個細細個
 * zip 可以解成幾十 GB 寫滿 sdcard。呢度嘅限制全部用「實際寫入嘅 byte 數」計（唔信 zip header 自報
 * 嘅大小，header 可以呃人），並且跳過咗嘅 entry 都照計（解壓本身食 CPU）：</p>
 * <ul>
 *   <li>entry 數量上限、單一檔案大小上限、解壓後總大小上限；</li>
 *   <li>開始前同寫入途中定期檢查可用空間，低過保留量即停（唔好令 sdcard 被填滿）；</li>
 *   <li>取消時拋 {@code IOException("cancelled")}（controller 靠呢個字串分辨取消同錯誤）；</li>
 *   <li>任何失敗都刪走正寫緊嗰個半截檔案。</li>
 * </ul>
 * 純 java.*，可 JVM 單元測試（SafeUnzipTest）。
 */
final class SafeUnzip {
    /** 取消檢查：回 true 即以 IOException("cancelled") 中止。 */
    interface Cancel {
        boolean isCancelled();
    }

    /** 可用空間探測（測試可注入假值）。 */
    interface Space {
        long usableBytes(File dir);
    }

    static final class Limits {
        final int maxEntries;
        final long maxTotalBytes;
        final long maxEntryBytes;
        final long reserveFreeBytes;

        Limits(int maxEntries, long maxTotalBytes, long maxEntryBytes, long reserveFreeBytes) {
            this.maxEntries = maxEntries;
            this.maxTotalBytes = maxTotalBytes;
            this.maxEntryBytes = maxEntryBytes;
            this.reserveFreeBytes = reserveFreeBytes;
        }
    }

    private static final long MB = 1024L * 1024L;
    /** 寫咗咁多 byte 先重新探測一次可用空間（探測係 syscall，唔使每個 chunk 做）。 */
    static final long SPACE_CHECK_INTERVAL = 4 * MB;

    /** 動作包：實測 379 entries／71 MB／最大單檔 2.1 MB；上限留 ~7 倍餘裕，防 GB 級炸彈。 */
    static final Limits ACTIONS = new Limits(10000, 512 * MB, 128 * MB, 128 * MB);
    /** Vosk 模型（< 1GB 級，entries 通常幾百）。 */
    static final Limits VOSK = new Limits(10000, 1024 * MB, 512 * MB, 128 * MB);

    static final Space FILE_SPACE = new Space() {
        @Override
        public long usableBytes(File dir) {
            File d = dir;
            while (d != null && !d.exists()) d = d.getParentFile();
            return d == null ? 0 : d.getUsableSpace();
        }
    };

    private SafeUnzip() {
    }

    static void extract(File zip, File dir, Limits lim, Cancel cancel) throws IOException {
        extract(zip, dir, lim, cancel, FILE_SPACE);
    }

    static void extract(File zip, File dir, Limits lim, Cancel cancel, Space space) throws IOException {
        String dirCanon = dir.getCanonicalPath();
        long free0 = space.usableBytes(dir);
        if (free0 < lim.reserveFreeBytes + zip.length()) {
            throw new IOException("not enough free space to extract (" + (free0 / MB) + " MB free, need "
                    + ((lim.reserveFreeBytes + zip.length()) / MB) + " MB)");
        }
        long[] total = {0};
        long[] sinceSpaceCheck = {0};
        int entries = 0;
        ZipInputStream zis = null;
        try {
            zis = new ZipInputStream(new BufferedInputStream(new FileInputStream(zip)));
            ZipEntry e;
            byte[] buf = new byte[32768];
            while ((e = zis.getNextEntry()) != null) {
                checkCancel(cancel);
                if (++entries > lim.maxEntries) {
                    throw new IOException("zip has too many entries (> " + lim.maxEntries + ")");
                }
                File f = new File(dir, e.getName());
                String canon = f.getCanonicalPath();
                if (!canon.equals(dirCanon) && !canon.startsWith(dirCanon + File.separator)) {
                    // 擋絕對路徑／.. 跳出：唔寫，但解壓出嚟嘅 byte 照計（防止用被跳過嘅 entry 食 CPU）。
                    copy(zis, null, buf, lim, total, sinceSpaceCheck, dir, space, cancel);
                    continue;
                }
                if (e.isDirectory()) {
                    f.mkdirs();
                } else {
                    File parent = f.getParentFile();
                    if (parent != null) parent.mkdirs();
                    OutputStream o = null;
                    boolean ok = false;
                    try {
                        o = new BufferedOutputStream(new FileOutputStream(f));
                        copy(zis, o, buf, lim, total, sinceSpaceCheck, dir, space, cancel);
                        o.flush();
                        ok = true;
                    } finally {
                        if (o != null) {
                            try {
                                o.close();
                            } catch (IOException ignore) {
                            }
                        }
                        if (!ok) f.delete(); // 失敗／超限／取消：唔好留低半截大檔
                    }
                }
            }
        } finally {
            if (zis != null) {
                try {
                    zis.close();
                } catch (IOException ignore) {
                }
            }
        }
    }

    /** 由當前 entry 讀到尾，寫去 o（null＝丟棄），途中套用全部限制。 */
    private static void copy(InputStream in, OutputStream o, byte[] buf, Limits lim, long[] total,
            long[] sinceSpaceCheck, File dir, Space space, Cancel cancel) throws IOException {
        long entryBytes = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            checkCancel(cancel);
            entryBytes += n;
            total[0] += n;
            if (entryBytes > lim.maxEntryBytes) {
                throw new IOException("zip entry too large (> " + (lim.maxEntryBytes / MB) + " MB)");
            }
            if (total[0] > lim.maxTotalBytes) {
                throw new IOException("zip too large when extracted (> " + (lim.maxTotalBytes / MB) + " MB)");
            }
            if (o != null) {
                o.write(buf, 0, n);
                sinceSpaceCheck[0] += n;
                if (sinceSpaceCheck[0] >= SPACE_CHECK_INTERVAL) {
                    sinceSpaceCheck[0] = 0;
                    o.flush(); // 先寫落去，getUsableSpace 先反映到
                    long free = space.usableBytes(dir);
                    if (free < lim.reserveFreeBytes) {
                        throw new IOException("not enough free space (" + (free / MB) + " MB left)");
                    }
                }
            }
        }
    }

    private static void checkCancel(Cancel cancel) throws IOException {
        if (cancel != null && cancel.isCancelled()) throw new IOException("cancelled");
    }
}
