package com.open.alpha2;

import android.content.Context;
import android.content.Intent;

import java.util.Map;

/**
 * 相機拍照層：單幀快照／存檔／資訊（串流走 /stream/camera，唔喺度）。
 *
 * 2026-09 由 MainActivity 抽出 (拆 god object)：camera/snapshot、
 * snapshot_save、take_photo_save、shutter_sound、info、fps、
 * supported_sizes、resolution 8 個 case body + waitForFrame，邏輯一字不改
 * 搬過嚟。硬件經傳入嘅同一個 CameraController；存檔廣播經 Context；
 * 快門聲經 RingtoneCenter。
 */
public final class CameraApi {
    private final Context appContext;
    private final CameraController cameraController;
    private final RingtoneCenter ringtoneCenter;

    public CameraApi(Context context, CameraController cameraController, RingtoneCenter ringtoneCenter) {
        this.appContext = context.getApplicationContext();
        this.cameraController = cameraController;
        this.ringtoneCenter = ringtoneCenter;
    }

    /** Polls CameraController.getLastFrame() until a frame newer than "none yet"
     *  appears, for the single-shot camera/snapshot endpoint. */
    private static CameraController.Frame waitForFrame(CameraController controller, long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            CameraController.Frame frame = controller.getLastFrame();
            if (frame != null) {
                return frame;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return controller.getLastFrame();
    }

    // -- Camera: standard Android legacy Camera API, not SDK-gated (see
    // CameraController for the front/back index quirk on this hardware). The
    // live feed itself is served at GET /stream/camera (see handleStream()) as
    // MJPEG, not through this JSON api/ path - a continuous multipart response
    // doesn't fit the single-JSON-body ApiResponse shape. This single-frame
    // snapshot endpoint just starts the camera (if it isn't already streaming)
    // and returns whatever the most recent preview frame is, for callers that
    // want one still image rather than opening the stream. -----------------------
    public HttpServer.ApiResponse snapshot() {
        CameraController.StartResult started = cameraController.start(8000);
        if (started.error != null) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\""
                    + MainActivity.jsonSafe(started.error) + "\"}");
        }
        CameraController.Frame frame = waitForFrame(cameraController, 3000);
        if (frame == null) {
            return HttpServer.ApiResponse.ok(
                    "{\"ok\":false,\"error\":\"timed out waiting for a preview frame\"}");
        }
        String b64 = android.util.Base64.encodeToString(frame.jpeg, android.util.Base64.NO_WRAP);
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"jpegBase64\":\"" + b64 + "\"}");
    }

    public HttpServer.ApiResponse snapshotSave(Map<String, String> query) {
        // 齊 9 檔影相並存入 Android：可選 w/h，未提供則用當前 preview 解像度；存至 /sdcard/DCIM/Alpha2
        Integer wOpt = ApiValidator.optionalInteger(query, "w");
        Integer hOpt = ApiValidator.optionalInteger(query, "h");
        int reqW = 0, reqH = 0;
        boolean hasSize = wOpt != null && hOpt != null;
        if (hasSize) {
            reqW = wOpt.intValue();
            reqH = hOpt.intValue();
        }
        int prevW = cameraController.getPreviewWidth();
        int prevH = cameraController.getPreviewHeight();
        if (hasSize) {
            cameraController.setRequestedResolution(reqW, reqH);
            cameraController.forceStopAndWait(3000);
        }
        CameraController.StartResult started = cameraController.start(8000);
        if (started.error != null) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"" + MainActivity.jsonSafe(started.error) + "\"}");
        }
        CameraController.Frame frame = waitForFrame(cameraController, 3000);
        if (frame == null) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"timed out waiting for frame\"}");
        }
        try {
            java.io.File dir = new java.io.File("/sdcard/DCIM/Alpha2");
            if (!dir.exists()) dir.mkdirs();
            String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).format(new java.util.Date());
            String name = "alpha2_" + frame.jpeg.length + "_" + cameraController.getPreviewWidth() + "x" + cameraController.getPreviewHeight() + "_" + ts + ".jpg";
            // 若有指定尺寸，用指定尺寸命名更直觀
            if (hasSize) name = "alpha2_" + reqW + "x" + reqH + "_" + ts + ".jpg";
            java.io.File outFile = new java.io.File(dir, name);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile)) { fos.write(frame.jpeg); }
            // 同時觸發媒體掃描，讓相簿即時可見
            try { appContext.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, android.net.Uri.fromFile(outFile))); } catch (Exception ignored) {}
            // 恢復之前解像度（若有切換）
            if (hasSize && (prevW != reqW || prevH != reqH)) {
                cameraController.setRequestedResolution(prevW, prevH);
                cameraController.forceStopAndWait(2000);
                // 不自動重開，讓前端按需再開，避免長時間佔用
            }
            String b64 = android.util.Base64.encodeToString(frame.jpeg, android.util.Base64.NO_WRAP);
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"path\":\"" + MainActivity.jsonSafe(outFile.getAbsolutePath()) + "\",\"jpegBase64\":\"" + b64 + "\",\"width\":" + cameraController.getPreviewWidth() + ",\"height\":" + cameraController.getPreviewHeight() + "}");
        } catch (Exception e) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"" + MainActivity.jsonSafe(e.getMessage()) + "\"}");
        }
    }

    public HttpServer.ApiResponse takePhotoSave(Map<String, String> query) {
        // 真正單張拍攝（picture 尺寸，經 Camera.takePicture 完整 ISP），存入 Android
        // 若未指定，用最大 picture 尺寸 (見 openapi default w=4208 h=3120)。
        int reqW = ApiValidator.optionalInt(query, "w", 4208);
        int reqH = ApiValidator.optionalInt(query, "h", 3120);
        CameraController.StartResult started = cameraController.start(8000);
        if (started.error != null) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"" + MainActivity.jsonSafe(started.error) + "\"}");
        }
        CameraController.PhotoResult photo = cameraController.takePhoto(reqW, reqH, 8000);
        if (photo.error != null) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"" + MainActivity.jsonSafe(photo.error) + "\"}");
        }
        try {
            java.io.File dir = new java.io.File("/sdcard/DCIM/Alpha2");
            if (!dir.exists()) dir.mkdirs();
            String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).format(new java.util.Date());
            String name = "alpha2_pic_" + reqW + "x" + reqH + "_" + ts + ".jpg";
            java.io.File outFile = new java.io.File(dir, name);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile)) { fos.write(photo.jpeg); }
            try { appContext.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, android.net.Uri.fromFile(outFile))); } catch (Exception ignored) {}
            String b64 = android.util.Base64.encodeToString(photo.jpeg, android.util.Base64.NO_WRAP);
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"path\":\"" + MainActivity.jsonSafe(outFile.getAbsolutePath()) + "\",\"jpegBase64\":\"" + b64 + "\",\"width\":" + reqW + ",\"height\":" + reqH + ",\"bytes\":" + photo.jpeg.length + "}");
        } catch (Exception e) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"" + MainActivity.jsonSafe(e.getMessage()) + "\"}");
        }
    }

    // Plays the "Sirrah" shutter cue out of the robot's own speaker (see
    // RingtoneCenter.playShutterCue() javadoc) - called by the browser right after a
    // successful camera/snapshot, instead of synthesizing a click sound in
    // the browser itself.
    public HttpServer.ApiResponse shutterSound() {
        ringtoneCenter.playShutterCue();
        return HttpServer.ApiResponse.ok("{\"ok\":true}");
    }

    public HttpServer.ApiResponse info() {
        return HttpServer.ApiResponse.ok("{\"ok\":true,"
                + "\"previewWidth\":" + cameraController.getPreviewWidth() + ","
                + "\"previewHeight\":" + cameraController.getPreviewHeight() + "}");
    }

    public HttpServer.ApiResponse fps() {
        double fps = cameraController.getFps();
        String fpsStr = String.format(java.util.Locale.US, "%.1f", fps);
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"fps\":" + fpsStr + ",\"streaming\":" + cameraController.isStreaming() + "}");
    }

    public HttpServer.ApiResponse supportedSizes() {
        java.util.List<android.hardware.Camera.Size> preview = cameraController.getSupportedPreviewSizesSync(4000);
        java.util.List<android.hardware.Camera.Size> picture = cameraController.getSupportedPictureSizesSync(4000);
        java.util.List<int[]> fpsRanges = cameraController.getSupportedPreviewFpsRangesSync(4000);
        StringBuilder sb = new StringBuilder("{\"ok\":true,\"preview\":[");
        if (preview != null) {
            boolean first = true;
            for (android.hardware.Camera.Size s : preview) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(s.width).append("x").append(s.height).append("\"");
            }
        }
        sb.append("],\"picture\":[");
        if (picture != null) {
            boolean first = true;
            for (android.hardware.Camera.Size s : picture) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(s.width).append("x").append(s.height).append("\"");
            }
        }
        sb.append("],\"fpsRanges\":[");
        if (fpsRanges != null) {
            boolean first = true;
            for (int[] r : fpsRanges) {
                if (!first) sb.append(",");
                first = false;
                sb.append("\"").append(r[0]/1000.0).append("-").append(r[1]/1000.0).append("\"");
            }
        }
        sb.append("],\"current\":\"").append(cameraController.getPreviewWidth()).append("x").append(cameraController.getPreviewHeight()).append("\"}");
        return HttpServer.ApiResponse.ok(sb.toString());
    }

    public HttpServer.ApiResponse resolution(Map<String, String> query) {
        int w = ApiValidator.requireInt(query, "w");
        int h = ApiValidator.requireInt(query, "h");
        cameraController.setRequestedResolution(w, h);
        // Block until the camera is genuinely released before answering - see
        // forceStopAndWait()'s javadoc for why stopIfIdle() alone isn't enough
        // here (it doesn't guarantee timing, just that it *will* close once idle).
        cameraController.forceStopAndWait(3000);
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"requestedWidth\":" + w
                + ",\"requestedHeight\":" + h + "}");
    }
}
