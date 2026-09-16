package com.open.alpha2;

import android.content.Context;
import android.content.Intent;

import java.nio.charset.StandardCharsets;

import java.util.Map;

/**
 * 相機拍照層：單幀快照／存檔／資訊（串流走 /stream/camera，唔喺度）。
 * 硬件經傳入嘅同一個 CameraController；存檔廣播經 Context；
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

    /** snapshot 系三 endpoint 共用起手式：start 相機，失敗即回現成 error response
     *  （之前三份逐字一樣；stream 版行 socket 503、vision 版回 XiaozhiVisionResult，
     *  各自保留）。成功回 null。 */
    private HttpServer.ApiResponse startCameraOrError() {
        CameraController.StartResult started = cameraController.start(8000);
        if (started.error != null) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"" + MainActivity.jsonSafe(started.error) + "\"}");
        }
        return null;
    }

    /** snapshot／snapshotSave 共用：等 AE/AF 收斂＋對焦＋鎖 AE 才取幀（之前兩份逐字一樣；
     *  takePhotoSave 唔用——takePicture 內部自己行 AF/AE）。 */
    private void settleCameraForSnapshot() {
        // 等 AE/AF 收斂 + 對焦 + 鎖 AE 才取幀，避免「未 ready 就按 shutter」糊/暗/過曝
        cameraController.waitForPreviewReady(2500);
        cameraController.triggerAutoFocusAndWait(2200);
        cameraController.lockAeAwbSync(700);
        try { Thread.sleep(150); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
    }

    /** 軟件變焦 fallback（硬件唔支援時拍照裁切放大；之前三份邏輯一樣，淨變量名唔同）。 */
    private byte[] maybeSoftwareZoom(byte[] jpeg) {
        float z = cameraController.getZoom();
        if (z > 1.01f) {
            CameraController.ZoomInfo zi = cameraController.getZoomInfoSync(800);
            if (zi != null && !zi.hardwareSupported) {
                return cameraController.applySoftwareZoomToJpeg(jpeg, z);
            }
        }
        return jpeg;
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
        HttpServer.ApiResponse startErr = startCameraOrError();
        if (startErr != null) return startErr;
        settleCameraForSnapshot();
        CameraController.Frame frame = waitForFrame(cameraController, 3000);
        cameraController.unlockAeAwbAsync();
        if (frame == null) {
            return HttpServer.ApiResponse.ok(
                    "{\"ok\":false,\"error\":\"timed out waiting for a preview frame\"}");
        }
        byte[] jpeg = maybeSoftwareZoom(frame.jpeg);
        String b64 = android.util.Base64.encodeToString(jpeg, android.util.Base64.NO_WRAP);
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"jpegBase64\":\"" + b64 + "\"}");
    }

    public HttpServer.ApiResponse snapshotSave(Map<String, String> query) {
        // 齊 9 檔影相並存入 Android：可選 w/h，未提供則用當前 preview 解像度；存至 /sdcard/DCIM/Alpha2
        // 有俾就要跟 spec 範圍驗。
        Integer wOpt = ApiValidator.optionalIntegerRange(query, "w", 1, 4208);
        Integer hOpt = ApiValidator.optionalIntegerRange(query, "h", 1, 3120);
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
        HttpServer.ApiResponse startErr = startCameraOrError();
        if (startErr != null) return startErr;
        settleCameraForSnapshot();
        CameraController.Frame frame = waitForFrame(cameraController, 3000);
        cameraController.unlockAeAwbAsync();
        if (frame == null) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"timed out waiting for frame\"}");
        }
        byte[] jpegToSave = maybeSoftwareZoom(frame.jpeg);
        try {
            java.io.File dir = new java.io.File("/sdcard/DCIM/Alpha2");
            if (!dir.exists()) dir.mkdirs();
            String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).format(new java.util.Date());
            String name = "alpha2_" + jpegToSave.length + "_" + cameraController.getPreviewWidth() + "x" + cameraController.getPreviewHeight() + "_" + ts + ".jpg";
            // 若有指定尺寸，用指定尺寸命名更直觀
            if (hasSize) name = "alpha2_" + reqW + "x" + reqH + "_" + ts + ".jpg";
            java.io.File outFile = new java.io.File(dir, name);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile)) { fos.write(jpegToSave); }
            // 同時觸發媒體掃描，讓相簿即時可見
            try { appContext.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, android.net.Uri.fromFile(outFile))); } catch (Exception ignored) {}
            // 恢復之前解像度（若有切換）
            if (hasSize && (prevW != reqW || prevH != reqH)) {
                cameraController.setRequestedResolution(prevW, prevH);
                cameraController.forceStopAndWait(2000);
                // 不自動重開，讓前端按需再開，避免長時間佔用
            }
            String b64 = android.util.Base64.encodeToString(jpegToSave, android.util.Base64.NO_WRAP);
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"path\":\"" + MainActivity.jsonSafe(outFile.getAbsolutePath()) + "\",\"jpegBase64\":\"" + b64 + "\",\"width\":" + cameraController.getPreviewWidth() + ",\"height\":" + cameraController.getPreviewHeight() + "}");
        } catch (Exception e) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"" + MainActivity.jsonSafe(e.getMessage()) + "\"}");
        }
    }

    public HttpServer.ApiResponse takePhotoSave(Map<String, String> query) {
        // 真正單張拍攝（picture 尺寸，經 Camera.takePicture 完整 ISP），存入 Android
        // 若未指定，用最大 picture 尺寸 (見 openapi default w=4208 h=3120)。
        int reqW = ApiValidator.optionalIntRange(query, "w", 1, 4208, 4208);
        int reqH = ApiValidator.optionalIntRange(query, "h", 1, 3120, 3120);
        HttpServer.ApiResponse startErr = startCameraOrError();
        if (startErr != null) return startErr;
        CameraController.PhotoResult photo = cameraController.takePhoto(reqW, reqH, 8000);
        if (photo.error != null) {
            return HttpServer.ApiResponse.ok("{\"ok\":false,\"error\":\"" + MainActivity.jsonSafe(photo.error) + "\"}");
        }
        // 軟件變焦 fallback（硬件唔支援時，拍照裁切放大）
        byte[] outJpeg = maybeSoftwareZoom(photo.jpeg);
        try {
            java.io.File dir = new java.io.File("/sdcard/DCIM/Alpha2");
            if (!dir.exists()) dir.mkdirs();
            String ts = new java.text.SimpleDateFormat("yyyyMMdd_HHmmss_SSS", java.util.Locale.US).format(new java.util.Date());
            String name = "alpha2_pic_" + reqW + "x" + reqH + "_" + ts + ".jpg";
            java.io.File outFile = new java.io.File(dir, name);
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(outFile)) { fos.write(outJpeg); }
            try { appContext.sendBroadcast(new Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE, android.net.Uri.fromFile(outFile))); } catch (Exception ignored) {}
            String b64 = android.util.Base64.encodeToString(outJpeg, android.util.Base64.NO_WRAP);
            return HttpServer.ApiResponse.ok("{\"ok\":true,\"path\":\"" + MainActivity.jsonSafe(outFile.getAbsolutePath()) + "\",\"jpegBase64\":\"" + b64 + "\",\"width\":" + reqW + ",\"height\":" + reqH + ",\"bytes\":" + outJpeg.length + "}");
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
        return HttpServer.ApiResponse.okTrue();
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

    // 數位變焦 x1-x5（硬件支援時映射到最接近 ratio，否則前端 CSS / 軟件裁切）
    public HttpServer.ApiResponse zoom(Map<String, String> query) {
        // 支援兩種用法：無參數=查詢，帶 zoom=設定並回當前狀態
        String zoomStr = query != null ? query.get("zoom") : null;
        if (zoomStr != null && !zoomStr.isEmpty()) {
            float z;
            try { z = Float.parseFloat(zoomStr.trim()); } catch (Exception e) { throw new IllegalArgumentException("parameter 'zoom' must be a number 1.0-5.0, got: " + zoomStr); }
            if (z < 1.0f || z > 5.0f) throw new IllegalArgumentException("parameter 'zoom' must be between 1.0 and 5.0, got: " + z);
            cameraController.setZoom(z);
            // 稍等硬件生效（最多 200ms）
            try { Thread.sleep(120); } catch (InterruptedException ie) { Thread.currentThread().interrupt(); }
        }
        CameraController.ZoomInfo info = cameraController.getZoomInfoSync(1500);
        StringBuilder sb = new StringBuilder("{\"ok\":true,");
        sb.append("\"zoom\":").append(String.format(java.util.Locale.US, "%.1f", info.current)).append(",");
        sb.append("\"hardwareSupported\":").append(info.hardwareSupported).append(",");
        sb.append("\"maxZoom\":").append(info.maxZoom).append(",");
        sb.append("\"ratios\":[");
        if (info.ratios != null) {
            for (int i = 0; i < info.ratios.size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(info.ratios.get(i));
            }
        }
        sb.append("]}");
        return HttpServer.ApiResponse.ok(sb.toString());
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
        // 跟 spec（w 1-4208，h 1-3120）顯式驗。
        int w = ApiValidator.requireIntRange(query, "w", 1, 4208);
        int h = ApiValidator.requireIntRange(query, "h", 1, 3120);
        cameraController.setRequestedResolution(w, h);
        // Block until the camera is genuinely released before answering - see
        // forceStopAndWait()'s javadoc for why stopIfIdle() alone isn't enough
        // here (it doesn't guarantee timing, just that it *will* close once idle).
        cameraController.forceStopAndWait(3000);
        return HttpServer.ApiResponse.ok("{\"ok\":true,\"requestedWidth\":" + w
                + ",\"requestedHeight\":" + h + "}");
    }

    // -- Camera streaming (MJPEG over "/stream/camera") -------------------------------

    private static final String MJPEG_BOUNDARY = "alpha2testpanelframe";
    /**
     * Serves the live camera feed as "multipart/x-mixed-replace" MJPEG - the format
     * every browser's plain &lt;img src="..."&gt; already knows how to render as a live
     * video-like feed with zero client-side JS, which is why this is a stream/ HTTP
     * route rather than a WebSocket: an &lt;img&gt; tag can't speak WebSocket, but it can
     * point straight at a URL that never stops responding.
     *
     * Runs on an HttpServer worker thread and blocks for as long as the client stays
     * connected, same as WebSocketServer.Connection.readLoop() does for "/ws" - both
     * rely on the pool's cached-thread-per-connection model rather than needing NIO.
     */
    public void handleCameraStream(java.net.Socket socket) throws java.io.IOException {
        CameraController.StartResult started = cameraController.start(8000);
        java.io.OutputStream out = socket.getOutputStream();
        if (started.error != null) {
            byte[] msg = ("Camera unavailable: " + started.error).getBytes(StandardCharsets.UTF_8);
            out.write(("HTTP/1.1 503 Service Unavailable\r\nContent-Length: " + msg.length
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            out.write(msg);
            out.flush();
            return;
        }

        out.write(("HTTP/1.1 200 OK\r\n"
                + "Content-Type: multipart/x-mixed-replace; boundary=" + MJPEG_BOUNDARY + "\r\n"
                + "Cache-Control: no-store, no-cache, must-revalidate, max-age=0\r\n"
                + "Access-Control-Allow-Origin: *\r\n"
                + "Connection: close\r\n"
                + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
        out.flush();

        // BlockingQueue rather than writing directly from onFrame(): onFrame() runs on
        // CameraController's own camera thread and must return immediately (it's also
        // fanning the same frame out to every other connected stream client) - it must
        // not block on this connection's socket write, which can stall arbitrarily long
        // on a slow/stuck client. capacity 1 + offer-that-drops-the-oldest keeps this
        // socket's writer thread always working from the newest frame rather than
        // buffering up a backlog if the network can't keep up with 30fps.
        final java.util.concurrent.ArrayBlockingQueue<CameraController.Frame> queue =
                new java.util.concurrent.ArrayBlockingQueue<>(1);
        CameraController.FrameListener listener = new CameraController.FrameListener() {
            @Override
            public void onFrame(CameraController.Frame frame) {
                queue.poll(); // drop whatever stale frame was waiting, if any
                queue.offer(frame);
            }
        };
        cameraController.subscribe(listener);
        try {
            while (true) {
                CameraController.Frame frame;
                try {
                    frame = queue.poll(10, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (frame == null) {
                    // No frame in 10s - camera likely died; stop rather than hold the
                    // connection (and the pool thread) open forever with a frozen image.
                    break;
                }
                out.write(("--" + MJPEG_BOUNDARY + "\r\n"
                        + "Content-Type: image/jpeg\r\n"
                        + "Content-Length: " + frame.jpeg.length + "\r\n"
                        + "\r\n").getBytes(StandardCharsets.ISO_8859_1));
                out.write(frame.jpeg);
                out.write("\r\n".getBytes(StandardCharsets.ISO_8859_1));
                out.flush(); // each part must reach the client promptly, not batch up
            }
        } finally {
            cameraController.unsubscribe(listener);
            // Only actually releases the camera once every other stream client (if any)
            // has also disconnected - see CameraController.stopIfIdle() javadoc.
            cameraController.stopIfIdle();
        }
    }

}
