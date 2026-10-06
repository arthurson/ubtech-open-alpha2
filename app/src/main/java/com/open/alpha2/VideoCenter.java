package com.open.alpha2;

import android.content.Context;
import android.content.Intent;
import android.media.MediaRecorder;
import android.os.SystemClock;
import android.util.Log;
import android.view.Surface;

import java.io.File;
import java.util.Map;

/**
 * 機端錄影（mp4，legacy Camera1＋MediaRecorder；同 CameraController 交接相機）。
 *
 * 流程（見 start）：開鏡（CameraController.start）→ 掛 dummy listener 頂住
 * stopIfIdle 誤放 → beginRecorderHandoff（拆預覽回調＋unlock）→ 配 MediaRecorder
 * （先試 MIC 有聲，prepare／start 炸即 reset 重配純畫面，唔會成單失敗）→ start。
 * 收尾（見 stop）：recorder.stop／reset／release → endRecorderHandoff（lock＋
 * 重掛預覽回調）→ 媒體掃描。用家撳停為止，不設時長上限。
 *
 * 並發約束：
 * - 錄影期間預覽回調已拆：MJPEG 訂閱者收唔到新幀（10 秒超時自斷，見 CameraApi），
 *   人臉／顏色追蹤訂閱仲在但收唔到幀（唔郁，唔會用 stale 幀郁頭）。
 * - takePhoto／snapshot／snapshotSave／resolution 錄影期間直接拒（CameraApi 守衛），
 *   否則 takePicture 停預覽會打斷編碼器。
 * - onDestroy 經 stopIfRecording() 先停錄影先放相機（見 MainActivity）。
 * - 全部方法 synchronized（HTTP worker 線程調用，stop 會 block 約 1 秒）。
 */
public final class VideoCenter {
    private static final String TAG = "VideoCenter";

    private static final int VIDEO_W = 640;
    private static final int VIDEO_H = 480;
    private static final int VIDEO_FPS = 30;
    private static final int VIDEO_BITRATE = 2000000;
    private static final int AUDIO_SAMPLERATE = 44100;
    // 剩餘空間少過呢個數即拒錄（爛尾 mp4 寫一半無意義）。
    private static final long MIN_FREE_BYTES = 50L * 1024 * 1024;

    private final Context appContext;
    private final CameraController cameraController;

    private MediaRecorder recorder;
    private Surface previewSurface;
    private boolean recording;
    private boolean hasAudio;
    private String outPath = "";
    private long startElapsedMs;

    private String lastPath = "";
    private long lastDurationMs;
    private long lastBytes;
    private boolean lastHasAudio;

    // stopIfIdle 誤放頂：錄影期間預覽回調已拆，listeners 隨時可能清空
    //（MJPEG 超時自斷＋追蹤無新幀），呢個空訂閱令 stopIfIdle 永遠見到非空。
    private final CameraController.FrameListener keepAlive =
            new CameraController.FrameListener() {
                @Override public void onFrame(CameraController.Frame frame) {
                }
            };
    private boolean keepAliveOn;

    public VideoCenter(Context context, CameraController cameraController) {
        this.appContext = context.getApplicationContext();
        this.cameraController = cameraController;
    }

    public synchronized boolean isRecording() {
        return recording;
    }

    /** 存檔目錄（同相片同一目錄，相簿即見）。 */
    private static File videoDir() {
        File dir = new File("/sdcard/DCIM/Alpha2");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static String newVideoPath(File dir) {
        String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS",
                java.util.Locale.US).format(new java.util.Date());
        return new File(dir, "alpha2_vid_" + ts + ".mp4").getAbsolutePath();
    }

    /** 單次配＋錄（withAudio＝配唔配 MIC；回 null＝錄緊，否則回錯）。 */
    private String tryStart(CameraController.RecorderHandoff h, String path, boolean withAudio) {
        MediaRecorder rec = new MediaRecorder();
        Surface surface = null;
        try {
            rec.setCamera(h.camera);
            if (withAudio) rec.setAudioSource(MediaRecorder.AudioSource.MIC);
            rec.setVideoSource(MediaRecorder.VideoSource.CAMERA);
            rec.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            if (withAudio) rec.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            rec.setVideoEncoder(MediaRecorder.VideoEncoder.H264);
            rec.setVideoSize(VIDEO_W, VIDEO_H);
            rec.setVideoFrameRate(VIDEO_FPS);
            rec.setVideoEncodingBitRate(VIDEO_BITRATE);
            if (withAudio) rec.setAudioSamplingRate(AUDIO_SAMPLERATE);
            surface = new Surface(h.previewTexture);
            rec.setPreviewDisplay(surface);
            rec.setOutputFile(path);
            rec.prepare();
            rec.start();
        } catch (Throwable t) {
            try {
                rec.reset();
            } catch (Throwable ignored) {
            }
            try {
                rec.release();
            } catch (Throwable ignored) {
            }
            if (surface != null) {
                try {
                    surface.release();
                } catch (Throwable ignored) {
                }
            }
            return String.valueOf(t.getMessage());
        }
        recorder = rec;
        previewSurface = surface;
        return null;
    }

    /** 開始錄影（query 暫無參數，留 dispatcher 一致性）。有聲優先，搶唔到咪自動轉純畫面。 */
    public synchronized HttpServer.ApiResponse start(Map<String, String> query) {
        if (recording) {
            return HttpServer.ApiResponse.error("already recording: " + outPath);
        }
        try {
            android.os.StatFs stat = new android.os.StatFs("/sdcard/DCIM");
            if (stat.getAvailableBytes() < MIN_FREE_BYTES) {
                return HttpServer.ApiResponse.error("low storage, need 50MB free to record");
            }
        } catch (Throwable t) {
            return HttpServer.ApiResponse.error("storage check failed: " + t.getMessage());
        }
        CameraController.StartResult sr = cameraController.start(8000);
        if (sr.error != null) {
            return HttpServer.ApiResponse.error(sr.error);
        }
        cameraController.subscribe(keepAlive);
        keepAliveOn = true;
        CameraController.RecorderHandoff h = cameraController.beginRecorderHandoff(5000);
        if (h == null || h.camera == null || h.previewTexture == null) {
            if (keepAliveOn) {
                try {
                    cameraController.unsubscribe(keepAlive);
                } catch (Throwable ignored) {
                }
                keepAliveOn = false;
            }
            return HttpServer.ApiResponse.error("camera handoff for recorder failed");
        }
        String path = newVideoPath(videoDir());
        String err = tryStart(h, path, true);
        boolean audio = true;
        if (err != null) {
            Log.w(TAG, "record with audio failed, retry silent: " + err);
            err = tryStart(h, path, false);
            audio = false;
        }
        if (err != null) {
            try {
                cameraController.endRecorderHandoff(5000);
            } catch (Throwable ignored) {
            }
            if (keepAliveOn) {
                try {
                    cameraController.unsubscribe(keepAlive);
                } catch (Throwable ignored) {
                }
                keepAliveOn = false;
            }
            return HttpServer.ApiResponse.error("recorder start failed: " + err);
        }
        recording = true;
        hasAudio = audio;
        outPath = path;
        startElapsedMs = SystemClock.elapsedRealtime();
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"recording\":true,\"path\":\""
                + MainActivity.jsonSafe(path) + "\",\"hasAudio\":" + audio + "}");
    }

    /** 停錄影（唔係錄緊即報錯；stop 炸即刪爛檔回錯）。 */
    public synchronized HttpServer.ApiResponse stop() {
        if (!recording) {
            return HttpServer.ApiResponse.error("not recording");
        }
        MediaRecorder rec = recorder;
        recorder = null;
        String path = outPath;
        long durMs = SystemClock.elapsedRealtime() - startElapsedMs;
        boolean audio = hasAudio;
        recording = false;
        outPath = "";
        hasAudio = false;
        String err = null;
        if (rec != null) {
            try {
                rec.stop();
            } catch (Throwable t) {
                err = String.valueOf(t.getMessage());
            }
            try {
                rec.reset();
            } catch (Throwable ignored) {
            }
            try {
                rec.release();
            } catch (Throwable ignored) {
            }
        }
        if (previewSurface != null) {
            try {
                previewSurface.release();
            } catch (Throwable ignored) {
            }
            previewSurface = null;
        }
        try {
            cameraController.endRecorderHandoff(5000);
        } catch (Throwable t) {
            Log.w(TAG, "endRecorderHandoff failed", t);
        }
        if (keepAliveOn) {
            try {
                cameraController.unsubscribe(keepAlive);
            } catch (Throwable ignored) {
            }
            keepAliveOn = false;
        }
        File f = new File(path);
        if (err != null || !f.exists() || f.length() == 0) {
            try {
                if (f.exists()) f.delete();
            } catch (Throwable ignored) {
            }
            return HttpServer.ApiResponse.error("recorder stop failed"
                    + (err != null ? ": " + err : ": empty file"));
        }
        try {
            appContext.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE,
                    android.net.Uri.fromFile(f)));
        } catch (Throwable ignored) {
        }
        lastPath = path;
        lastDurationMs = durMs;
        lastBytes = f.length();
        lastHasAudio = audio;
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"recording\":false,\"path\":\""
                + MainActivity.jsonSafe(path) + "\",\"durationMs\":" + durMs
                + ",\"bytes\":" + f.length() + ",\"hasAudio\":" + audio + "}");
    }

    /** 錄影狀態（錄緊回經過時間，閒置回上次成品）。 */
    public synchronized HttpServer.ApiResponse status() {
        if (recording) {
            long elapsed = SystemClock.elapsedRealtime() - startElapsedMs;
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"recording\":true,\"path\":\""
                    + MainActivity.jsonSafe(outPath) + "\",\"elapsedMs\":" + elapsed
                    + ",\"hasAudio\":" + hasAudio + "}");
        }
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"recording\":false,\"lastPath\":\""
                + MainActivity.jsonSafe(lastPath) + "\",\"lastDurationMs\":" + lastDurationMs
                + ",\"lastBytes\":" + lastBytes + ",\"lastHasAudio\":" + lastHasAudio + "}");
    }

    /** onDestroy 共用：錄緊即停（唔掟錯，免 camera shutdown 同編碼器打架）。 */
    public void stopIfRecording() {
        try {
            if (isRecording()) stop();
        } catch (Throwable ignored) {
        }
    }
}
