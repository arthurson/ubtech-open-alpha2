package com.open.alpha2;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.graphics.YuvImage;
import android.hardware.Camera;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Wraps the legacy android.hardware.Camera API (see docs/capabilities.md "Cameras /
 * vision" in the Alpha2OpenSdk repo): the robot forces camera2 into legacy mode
 * (camera2.portability.force_api=1), so the classic Camera API is what actually works
 * here, not android.hardware.camera2.
 *
 * Camera indices are NOT the usual 0=back/1=front - the robot documents 98/99 for
 * front/back, but real-device testing (firmware v1.1.7.3.20, logcat_2026-07-27) shows
 * 98/99 both fail immediately with "invalid cameraId" and 0 is what actually opens on
 * this hardware. CAMERA_INDEX_CANDIDATES therefore tries 0/1 first - the two guaranteed-
 * fail attempts otherwise cost a CameraService round trip on every single stream start
 * for no benefit on this firmware - and keeps 98/99 as a fallback for any other
 * firmware build where the documented indices turn out to be the real ones.
 *
 * Continuous webcam-style streaming, NOT single-shot photos: the camera is opened ONCE
 * and left in preview mode. Every preview frame is delivered to
 * {@link Camera#setPreviewCallbackWithBuffer} (buffer-queue variant - the plain
 * setPreviewCallback() re-allocates a new byte[] for every single frame, which at
 * 30fps is enough garbage-collector churn to itself cap the achievable frame rate),
 * converted from the camera's native NV21 to JPEG in-process with
 * {@link YuvImage#compressToJpeg}, and fanned out to every subscribed streaming client.
 * There is no takePicture()/stopPreview()/release() cycle per frame any more - that
 * open/close round trip alone costs low hundreds of ms and made 30fps physically
 * impossible; capture() used to pay that cost on every single call.
 *
 * All Camera calls happen on a dedicated background thread with its own Looper: Camera's
 * callbacks are delivered on the thread that opened it, and that thread must be running
 * a Looper to receive them.
 */
public class CameraController {
    private static final String TAG = "CameraController";
    private static final int[] CAMERA_INDEX_CANDIDATES = {0, 1, 98, 99};
    private static final int JPEG_QUALITY = 60;
    private static final int DEFAULT_PREVIEW_WIDTH = 1280;
    private static final int DEFAULT_PREVIEW_HEIGHT = 720;
    private volatile int requestedWidth = DEFAULT_PREVIEW_WIDTH;
    private volatile int requestedHeight = DEFAULT_PREVIEW_HEIGHT;
    // 數位變焦 x1-x5（硬件支援時用 Camera.Parameters.setZoom，否則前端 CSS / 軟件裁切）
    private volatile float zoomFactor = 1.0f;
    private volatile long lastZoomChangeMs = 0;
    private volatile long previewStartedAtMs = 0;
    // Two buffers cycled through addCallbackBuffer() so the camera driver can be filling
    // one while the previous one is still being JPEG-encoded on this thread.
    private static final int PREVIEW_BUFFER_COUNT = 2;

    /** One JPEG frame plus a monotonically increasing sequence number, so a stalled
     *  subscriber can tell "no new frame yet" (same seq) from "missed some frames"
     *  (seq jumped) without needing its own frame queue. */
    public static final class Frame {
        public final byte[] jpeg;
        public final long seq;

        Frame(byte[] jpeg, long seq) {
            this.jpeg = jpeg;
            this.seq = seq;
        }
    }

    /** Subscribes to receive every JPEG frame as it's produced (called on the camera
     *  thread - must not block, and must not call back into CameraController). */
    public interface FrameListener {
        void onFrame(Frame frame);
    }

    private HandlerThread cameraThread;
    private Handler cameraHandler;
    private volatile Camera camera;
    private volatile int openedIndex = -1;
    private volatile int previewWidth = DEFAULT_PREVIEW_WIDTH;
    private volatile int previewHeight = DEFAULT_PREVIEW_HEIGHT;
    private volatile long frameSeq = 0;
    private volatile Frame lastFrame;
    private final Set<FrameListener> listeners = new CopyOnWriteArraySet<>();

    /** Thread.sleep 共用形（UbxPlayer.joinQuietly 同系）：瞇 ms；被 interrupt 就補返
     *  interrupt flag 並回 false。caller 按自己回傳型別收尾（PhotoResult.fail／
     *  return false／照行）。之前各處逐字一樣嘅 try/catch 收斂到呢度。 */
    private static boolean sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return true;
    }

    /** getSupported*Sync 三份共用：逐個 index 試開相機（之前三份逐字一樣）。開唔到回 null。 */
    private static android.hardware.Camera openFallbackCamera() {
        for (int idx : CAMERA_INDEX_CANDIDATES) {
            try { return android.hardware.Camera.open(idx); } catch (Exception ignored) {}
        }
        return null;
    }

    /** getSupported*Sync 三份共用 finally：放掉臨時開嘅相機＋開閘（之前三份逐字一樣）。 */
    private static void releaseTmpAndCountDown(android.hardware.Camera tmp, CountDownLatch latch) {
        if (tmp != null) { try { tmp.release(); } catch (Exception ignored) {} }
        latch.countDown();
    }

    /** CountDownLatch.await 共用形（sleepQuietly 同系）：等 timeout；被 interrupt
     *  就補返 interrupt flag 並回 false。注意：timeout 照回 true——舊碼三處都唔睇
     *  await() 本身回值，淨係理 interrupt，呢度保留呢個語義。 */
    private static boolean awaitQuietly(CountDownLatch latch, long timeout, TimeUnit unit) {
        try {
            latch.await(timeout, unit);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
        return true;
    }
    // FPS 計算：滑動窗口記錄最近幀的時間戳（nanoTime），用於計算實時 FPS
    private final java.util.ArrayDeque<Long> fpsTimestamps = new java.util.ArrayDeque<>();
    private static final int FPS_WINDOW_SIZE = 30;

    /** The actual preview resolution in use (may differ from the requested size - see
     *  closestSupportedPreviewSize()). Valid once the camera has been opened at least
     *  once; returns the requested default beforehand. */
    public int getPreviewWidth() {
        return previewWidth;
    }

    public int getPreviewHeight() {
        return previewHeight;
    }

    /** Sets the resolution to request next time the camera is opened. Has no effect on
     *  an already-running stream - stop it first (stopIfIdle()/no listeners left, or
     *  shutdown()) and reconnect for a new resolution to take effect, since Camera
     *  doesn't support changing preview size while streaming. */
    public void setRequestedResolution(int width, int height) {
        requestedWidth = width;
        requestedHeight = height;
    }

    /** 取回相機硬件報告的全部支援 preview/picture 尺寸（需在 camera 線程上讀取參數） */
    public java.util.List<android.hardware.Camera.Size> getSupportedPreviewSizesSync(long timeoutMs) {
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<java.util.List<android.hardware.Camera.Size>> result = new java.util.concurrent.atomic.AtomicReference<>();
        final java.util.concurrent.atomic.AtomicReference<String> err = new java.util.concurrent.atomic.AtomicReference<>();
        startCameraThreadIfNeeded();
        cameraHandler.post(new Runnable() {
            @Override public void run() {
                android.hardware.Camera tmp = null;
                boolean openedHere = false;
                try {
                    if (camera != null) {
                        result.set(camera.getParameters().getSupportedPreviewSizes());
                    } else {
                        tmp = openFallbackCamera();
                        if (tmp == null) { err.set("Camera.open failed for all indices"); }
                        else { result.set(tmp.getParameters().getSupportedPreviewSizes()); }
                    }
                } catch (Exception e) { err.set(e.getMessage()); }
                finally {
                    releaseTmpAndCountDown(tmp, latch);
                }
            }
        });
        awaitQuietly(latch, timeoutMs, TimeUnit.MILLISECONDS);
        if (err.get() != null) return null;
        return result.get();
    }
    public java.util.List<android.hardware.Camera.Size> getSupportedPictureSizesSync(long timeoutMs) {
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<java.util.List<android.hardware.Camera.Size>> result = new java.util.concurrent.atomic.AtomicReference<>();
        startCameraThreadIfNeeded();
        cameraHandler.post(new Runnable() {
            @Override public void run() {
                android.hardware.Camera tmp = null;
                try {
                    if (camera != null) {
                        result.set(camera.getParameters().getSupportedPictureSizes());
                    } else {
                        tmp = openFallbackCamera();
                        if (tmp != null) result.set(tmp.getParameters().getSupportedPictureSizes());
                    }
                } catch (Exception ignored) { result.set(null); }
                finally {
                    releaseTmpAndCountDown(tmp, latch);
                }
            }
        });
        awaitQuietly(latch, timeoutMs, TimeUnit.MILLISECONDS);
        return result.get();
    }
    public java.util.List<int[]> getSupportedPreviewFpsRangesSync(long timeoutMs) {
        final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.atomic.AtomicReference<java.util.List<int[]>> result = new java.util.concurrent.atomic.AtomicReference<>();
        startCameraThreadIfNeeded();
        cameraHandler.post(new Runnable() {
            @Override public void run() {
                android.hardware.Camera tmp = null;
                try {
                    if (camera != null) {
                        result.set(camera.getParameters().getSupportedPreviewFpsRange());
                    } else {
                        tmp = openFallbackCamera();
                        if (tmp != null) result.set(tmp.getParameters().getSupportedPreviewFpsRange());
                    }
                } catch (Exception ignored) {}
                finally {
                    releaseTmpAndCountDown(tmp, latch);
                }
            }
        });
        awaitQuietly(latch, timeoutMs, TimeUnit.MILLISECONDS);
        return result.get();
    }

    /** Result of opening the camera: null means success. */
    public static final class StartResult {
        public final String error;
        private StartResult(String error) { this.error = error; }
        static StartResult ok() { return new StartResult(null); }
        static StartResult fail(String error) { return new StartResult(error); }
    }

    /**
     * Opens the camera (if not already open) and starts continuous preview. Safe to call
     * repeatedly - a no-op if the camera is already streaming. Blocks the calling thread
     * (must NOT be the main thread) until the camera has either started or failed.
     */
    public StartResult start(long timeoutMs) {
        if (camera != null) {
            return StartResult.ok();
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<String> error = new AtomicReference<>();

        startCameraThreadIfNeeded();
        cameraHandler.post(new Runnable() {
            @Override
            public void run() {
                if (camera != null) {
                    latch.countDown();
                    return;
                }
                for (int index : CAMERA_INDEX_CANDIDATES) {
                    try {
                        camera = Camera.open(index);
                        openedIndex = index;
                        Log.i(TAG, "Camera.open(" + index + ") succeeded");
                        break;
                    } catch (RuntimeException e) {
                        Log.w(TAG, "Camera.open(" + index + ") failed: " + e.getMessage());
                    }
                }
                if (camera == null) {
                    error.set("Camera.open() failed for all candidate indices "
                            + java.util.Arrays.toString(CAMERA_INDEX_CANDIDATES));
                    latch.countDown();
                    return;
                }
                try {
                    startPreviewLocked();
                } catch (Exception e) {
                    error.set("Failed to start preview: " + e.getMessage());
                    safeReleaseOnCameraThread();
                    latch.countDown();
                    return;
                }
                latch.countDown();
            }
        });

        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return StartResult.fail("Timed out opening camera");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return StartResult.fail("Interrupted while opening camera");
        }
        String err = error.get();
        return err != null ? StartResult.fail(err) : StartResult.ok();
    }

    /** Must run on the camera thread; sets up preview size, pixel format, the recycled
     *  callback buffers, and requests the highest frame rate the camera reports
     *  supporting (falling back to whatever default the driver picks if none is listed). */
    /** Kept alive for the controller's lifetime so the GL texture backing dummyPreviewTexture
     *  stays valid for as long as the camera might be streaming; torn down in shutdown(). */
    private EGLDisplay eglDisplay = EGL14.EGL_NO_DISPLAY;
    private EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
    private EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
    private int previewTextureId = -1;
    private SurfaceTexture dummyPreviewTexture;

    /**
     * Creates a minimal 1x1 pbuffer-backed EGL context purely so glGenTextures() has a
     * current GL context to allocate a real texture name from. Camera.setPreviewTexture()
     * requires a SurfaceTexture, but on this hardware's camera HAL, a SurfaceTexture built
     * from texture id 0 (GL's "no texture bound" placeholder, not an allocated texture)
     * makes cameraDisplayBufferCreate() fail with error -19 - the camera opens and
     * startPreview() reports success, but zero preview frames are ever delivered, so the
     * stream just hangs. A genuinely allocated texture id fixes that.
     */
    private void ensurePreviewTextureLocked() throws Exception {
        if (dummyPreviewTexture != null) {
            return;
        }
        eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (eglDisplay == EGL14.EGL_NO_DISPLAY) {
            throw new Exception("eglGetDisplay failed");
        }
        int[] version = new int[2];
        if (!EGL14.eglInitialize(eglDisplay, version, 0, version, 1)) {
            throw new Exception("eglInitialize failed");
        }
        int[] configAttribs = {
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] numConfigs = new int[1];
        if (!EGL14.eglChooseConfig(eglDisplay, configAttribs, 0, configs, 0, 1, numConfigs, 0)
                || numConfigs[0] <= 0) {
            throw new Exception("eglChooseConfig failed");
        }
        int[] contextAttribs = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
        eglContext = EGL14.eglCreateContext(eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT,
                contextAttribs, 0);
        if (eglContext == EGL14.EGL_NO_CONTEXT) {
            throw new Exception("eglCreateContext failed");
        }
        int[] pbufferAttribs = {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
        eglSurface = EGL14.eglCreatePbufferSurface(eglDisplay, configs[0], pbufferAttribs, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) {
            throw new Exception("eglCreatePbufferSurface failed");
        }
        if (!EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)) {
            throw new Exception("eglMakeCurrent failed");
        }

        int[] textures = new int[1];
        GLES20.glGenTextures(1, textures, 0);
        previewTextureId = textures[0];
        if (previewTextureId == 0) {
            throw new Exception("glGenTextures returned 0 (no valid texture allocated)");
        }
        dummyPreviewTexture = new SurfaceTexture(previewTextureId);
    }

    private void releasePreviewTextureLocked() {
        if (dummyPreviewTexture != null) {
            try {
                dummyPreviewTexture.release();
            } catch (Exception ignored) {
            }
            dummyPreviewTexture = null;
        }
        if (eglDisplay != EGL14.EGL_NO_DISPLAY) {
            EGL14.eglMakeCurrent(eglDisplay, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE,
                    EGL14.EGL_NO_CONTEXT);
            if (eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglDestroySurface(eglDisplay, eglSurface);
                eglSurface = EGL14.EGL_NO_SURFACE;
            }
            if (eglContext != EGL14.EGL_NO_CONTEXT) {
                EGL14.eglDestroyContext(eglDisplay, eglContext);
                eglContext = EGL14.EGL_NO_CONTEXT;
            }
            EGL14.eglTerminate(eglDisplay);
            eglDisplay = EGL14.EGL_NO_DISPLAY;
        }
        previewTextureId = -1;
    }

    private void startPreviewLocked() throws Exception {
        Camera.Parameters params = camera.getParameters();

        Camera.Size bestSize = closestSupportedPreviewSize(params, requestedWidth, requestedHeight);
        if (bestSize != null) {
            params.setPreviewSize(bestSize.width, bestSize.height);
            previewWidth = bestSize.width;
            previewHeight = bestSize.height;
        } else {
            previewWidth = params.getPreviewSize().width;
            previewHeight = params.getPreviewSize().height;
        }

        params.setPreviewFormat(ImageFormat.NV21); // YuvImage.compressToJpeg requires NV21/YUY2

        int[] bestFpsRange = highestSupportedFpsRange(params);
        if (bestFpsRange != null) {
            params.setPreviewFpsRange(bestFpsRange[0], bestFpsRange[1]);
        }
        // 對焦模式 — 優先連續圖片對焦，次選自動，避免快門時未對焦
        try {
            List<String> focusModes = params.getSupportedFocusModes();
            if (focusModes != null) {
                if (focusModes.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                    params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
                } else if (focusModes.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                    params.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                }
            }
        } catch (Throwable ignore) {}
        // 曝光補償歸零，避免過曝（部分 HAL 上次拍照後殘留 +2）
        try {
            int minEc = params.getMinExposureCompensation();
            int maxEc = params.getMaxExposureCompensation();
            if (minEc <= 0 && 0 <= maxEc) {
                try { params.setExposureCompensation(0); } catch (Throwable ignore2) {}
            }
            try {
                List<String> scenes = params.getSupportedSceneModes();
                if (scenes != null && scenes.contains(Camera.Parameters.SCENE_MODE_AUTO)) {
                    params.setSceneMode(Camera.Parameters.SCENE_MODE_AUTO);
                }
            } catch (Throwable ignore2) {}
            try {
                List<String> wbs = params.getSupportedWhiteBalance();
                if (wbs != null && wbs.contains(Camera.Parameters.WHITE_BALANCE_AUTO)) {
                    params.setWhiteBalance(Camera.Parameters.WHITE_BALANCE_AUTO);
                }
            } catch (Throwable ignore2) {}
        } catch (Throwable ignore) {}

        camera.setParameters(params);
        // 若已有 zoom 設定，立即套用硬件變焦（需在 setParameters 之後再取一次新 params）
        if (Math.abs(zoomFactor - 1.0f) > 0.01f) {
            try { applyHardwareZoomLocked(zoomFactor); } catch (Throwable ignore) {}
        }

        Log.i(TAG, "Preview resolution: " + previewWidth + "x" + previewHeight
                + " (requested " + requestedWidth + "x" + requestedHeight + ")"
                + ", fps range: " + (bestFpsRange != null
                        ? (bestFpsRange[0] / 1000.0) + "-" + (bestFpsRange[1] / 1000.0)
                        : "driver default"));

        int bufSize = previewWidth * previewHeight
                * ImageFormat.getBitsPerPixel(ImageFormat.NV21) / 8;
        for (int i = 0; i < PREVIEW_BUFFER_COUNT; i++) {
            camera.addCallbackBuffer(new byte[bufSize]);
        }
        camera.setPreviewCallbackWithBuffer(new Camera.PreviewCallback() {
            @Override
            public void onPreviewFrame(byte[] data, Camera cam) {
                try {
                    byte[] jpeg = nv21ToJpeg(data, previewWidth, previewHeight);
                    Frame frame = new Frame(jpeg, ++frameSeq);
                    lastFrame = frame;
                    // 記錄 FPS 時間戳
                    synchronized (CameraController.this) {
                        long now = System.nanoTime();
                        fpsTimestamps.addLast(now);
                        while (fpsTimestamps.size() > FPS_WINDOW_SIZE) {
                            fpsTimestamps.removeFirst();
                        }
                    }
                    for (FrameListener l : listeners) {
                        try {
                            l.onFrame(frame);
                        } catch (Exception ignored) {
                            // One bad subscriber must not stop frames reaching the others.
                        }
                    }
                } catch (Exception e) {
                    Log.w(TAG, "Failed to encode preview frame", e);
                } finally {
                    // Hand the same buffer back to the driver for reuse - required when
                    // using setPreviewCallbackWithBuffer (unlike the single-buffer
                    // setPreviewCallback(), the driver does NOT auto-recycle these).
                    if (camera != null) {
                        camera.addCallbackBuffer(data);
                    }
                }
            }
        });

        // A preview target is still required even though nothing ever displays it -
        // startPreview() delivers no frames at all without one set. See
        // ensurePreviewTextureLocked() for why this must be backed by a real GL texture
        // rather than SurfaceTexture(0).
        ensurePreviewTextureLocked();
        camera.setPreviewTexture(dummyPreviewTexture);
        camera.startPreview();
        previewStartedAtMs = System.currentTimeMillis();
    }

    private static byte[] nv21ToJpeg(byte[] nv21, int width, int height) {
        YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuv.compressToJpeg(new Rect(0, 0, width, height), JPEG_QUALITY, out);
        return out.toByteArray();
    }

    /** preview／picture 共用嘅最近面積匹配（之前兩份除咗讀邊個 list 外逐字一樣）。 */
    private static Camera.Size closestSizeByArea(java.util.List<Camera.Size> sizes,
            int wantWidth, int wantHeight) {
        if (sizes == null || sizes.isEmpty()) {
            return null;
        }
        Camera.Size best = null;
        long bestDiff = Long.MAX_VALUE;
        long wantArea = (long) wantWidth * wantHeight;
        for (Camera.Size s : sizes) {
            long diff = Math.abs((long) s.width * s.height - wantArea);
            if (diff < bestDiff) {
                bestDiff = diff;
                best = s;
            }
        }
        return best;
    }

    /** Picks the supported preview size with the smallest area difference from the
     *  requested width/height, since most legacy Camera HALs reject arbitrary sizes
     *  outright. Returns null if the driver reports no supported-size list at all. */
    private static Camera.Size closestSupportedPreviewSize(Camera.Parameters params,
            int wantWidth, int wantHeight) {
        return closestSizeByArea(params.getSupportedPreviewSizes(), wantWidth, wantHeight);
    }

    /** Picks the supported FPS range with the highest max fps (values are in
     *  thousandths of a frame-per-second per the Camera.Parameters contract, e.g.
     *  30000 = 30fps). Returns null if the driver reports no range list. */
    private static int[] highestSupportedFpsRange(Camera.Parameters params) {
        List<int[]> ranges = params.getSupportedPreviewFpsRange();
        if (ranges == null || ranges.isEmpty()) {
            return null;
        }
        int[] best = null;
        for (int[] range : ranges) {
            if (best == null
                    || range[Camera.Parameters.PREVIEW_FPS_MAX_INDEX]
                        > best[Camera.Parameters.PREVIEW_FPS_MAX_INDEX]) {
                best = range;
            }
        }
        return best;
    }

    /** Result of a single-shot camera.takePicture() capture (see takePhoto() below). */
    public static final class PhotoResult {
        public final byte[] jpeg;
        public final String error;
        private PhotoResult(byte[] jpeg, String error) {
            this.jpeg = jpeg;
            this.error = error;
        }
        static PhotoResult ok(byte[] jpeg) { return new PhotoResult(jpeg, null); }
        static PhotoResult fail(String error) { return new PhotoResult(null, error); }
    }

    // 真正單張拍攝：唔用 preview frame（冇經過 HAL 完整單張 AE/AF/降噪 pipeline），
    // 用 Camera1 legacy API camera.takePicture(shutter, raw, jpeg) 拍一次；
    // jpeg callback 嘅先係 driver 做完 AE/AF 收斂＋完整 ISP pipeline 嘅相。
    // （要求 start() 已成功；用現有 camera 實例。）
    //
    // Camera1 API 的 takePicture() 會讓 driver 自動 stopPreview() (拍完照不會自動
    // 繼續 preview) - 這個 method 完成之後會重新 startPreview(), 保持
    // camera/snapshot MJPEG streaming 的其他 subscriber 不受影響 (只是拍照時有
    // 一瞬間的 streaming 中斷, 對單一 take_photo call 來說可接受)。
    //
    // 解析度: 沿用 closestSupportedPreviewSize() 一樣的「最接近所求 area」選法, 但
    // 這裡改用 getSupportedPictureSizes() (真正拍攝解析度清單), 不再用
    // getSupportedPreviewSizes() (streaming 用的小解析度清單, 通常選擇範圍小於拍攝
    // 清單很多) - 保留用戶已核實過對的 480x360 request size, 純粹換一個更對的
    // supported-sizes 來源來選。
    public PhotoResult takePhoto(final int wantWidth, final int wantHeight, long timeoutMs) {
        if (camera == null) {
            return PhotoResult.fail("camera not started - call start() first");
        }
        long overallDeadline = System.currentTimeMillis() + timeoutMs;
        // 1) 等 AE/AF 收斂（在呼叫線程 polling，不阻塞 cameraHandler 的預覽回調）
        long readyBudget = Math.min(2200, Math.max(0, overallDeadline - System.currentTimeMillis()));
        boolean ready = waitForPreviewReady(readyBudget);
        if (!ready) Log.w(TAG, "takePhoto: preview not fully ready after " + readyBudget + "ms, proceeding anyway (may be dark/blurry)");
        else Log.i(TAG, "takePhoto: preview ready, elapsed=" + (System.currentTimeMillis() - previewStartedAtMs) + "ms fps=" + String.format(java.util.Locale.US, "%.1f", getFps()));

        // 2) 設定 picture size（投遞到 cameraHandler 並等待）
        final CountDownLatch sizeLatch = new CountDownLatch(1);
        final AtomicReference<String> sizeError = new AtomicReference<>();
        cameraHandler.post(new Runnable() {
            @Override public void run() {
                if (camera == null) { sizeError.set("camera released before setPictureSize"); sizeLatch.countDown(); return; }
                try {
                    Camera.Parameters params = camera.getParameters();
                    Camera.Size bestPictureSize = closestSupportedPictureSize(params, wantWidth, wantHeight);
                    if (bestPictureSize != null) {
                        params.setPictureSize(bestPictureSize.width, bestPictureSize.height);
                        camera.setParameters(params);
                        Log.i(TAG, "takePicture() picture size: " + bestPictureSize.width + "x" + bestPictureSize.height + " (requested " + wantWidth + "x" + wantHeight + ")");
                    } else Log.w(TAG, "takePicture(): driver reported no supported picture sizes, using driver default");
                } catch (Exception e) { sizeError.set("Failed to set picture size: " + e.getMessage()); }
                finally { sizeLatch.countDown(); }
            }
        });
        try {
            long remain = overallDeadline - System.currentTimeMillis();
            if (remain <= 0 || !sizeLatch.await(Math.min(1000, remain), TimeUnit.MILLISECONDS)) {
                return PhotoResult.fail("Timed out setting picture size");
            }
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return PhotoResult.fail("Interrupted while setting picture size"); }
        if (sizeError.get() != null) return PhotoResult.fail(sizeError.get());

        // 3) 對焦：查詢當前 focusMode，AUTO/MACRO 需觸發一次 autoFocus，連續模式則短暫等待
        final AtomicReference<String> focusModeRef = new AtomicReference<>();
        final CountDownLatch modeLatch = new CountDownLatch(1);
        cameraHandler.post(new Runnable() {
            @Override public void run() {
                try { if (camera != null) focusModeRef.set(camera.getParameters().getFocusMode()); } catch (Throwable ignore) {}
                finally { modeLatch.countDown(); }
            }
        });
        awaitQuietly(modeLatch, 800, TimeUnit.MILLISECONDS);
        String mode = focusModeRef.get();
        boolean needAf = mode == null || Camera.Parameters.FOCUS_MODE_AUTO.equals(mode) || Camera.Parameters.FOCUS_MODE_MACRO.equals(mode);
        if (!needAf) {
            if (!sleepQuietly(180)) return PhotoResult.fail("Interrupted before shutter");
        } else {
            final CountDownLatch afLatch = new CountDownLatch(1);
            cameraHandler.post(new Runnable() {
                @Override public void run() {
                    if (camera == null) { afLatch.countDown(); return; }
                    try {
                        try { camera.cancelAutoFocus(); } catch (Throwable ignore) {}
                        camera.autoFocus(new Camera.AutoFocusCallback() {
                            @Override public void onAutoFocus(boolean success, Camera cam) {
                                Log.i(TAG, "autoFocus callback success=" + success);
                                afLatch.countDown();
                            }
                        });
                    } catch (Throwable t) {
                        Log.w(TAG, "autoFocus throw", t);
                        afLatch.countDown();
                    }
                }
            });
            try {
                long afRemain = overallDeadline - System.currentTimeMillis();
                if (afRemain <= 0) return PhotoResult.fail("Timed out before autoFocus");
                boolean afDone = afLatch.await(Math.min(2200, afRemain), TimeUnit.MILLISECONDS);
                if (!afDone) {
                    Log.w(TAG, "autoFocus timeout, proceeding to shutter anyway");
                    try { cameraHandler.post(new Runnable() { @Override public void run() { try { camera.cancelAutoFocus(); } catch (Throwable ignore) {} } }); } catch (Throwable ignore) {}
                }
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); return PhotoResult.fail("Interrupted during autoFocus"); }
            if (!sleepQuietly(220)) return PhotoResult.fail("Interrupted after autoFocus");
        }
        // 3.5) 鎖 AE/AWB 避免過曝
        lockAeAwbSync(700);
        if (!sleepQuietly(180)) return PhotoResult.fail("Interrupted before shutter");

        // 4) 真正 shutter
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<byte[]> resultJpeg = new AtomicReference<>();
        final AtomicReference<String> resultError = new AtomicReference<>();
        cameraHandler.post(new Runnable() {
            @Override public void run() {
                if (camera == null) { resultError.set("camera was released before takePicture() could run"); latch.countDown(); return; }
                try {
                    camera.takePicture(null, null, new Camera.PictureCallback() {
                        @Override public void onPictureTaken(byte[] data, Camera cam) {
                            resultJpeg.set(data);
                            try {
                                if (camera != null) {
                                    camera.startPreview();
                                    previewStartedAtMs = System.currentTimeMillis();
                                }
                            } catch (Exception e) { Log.w(TAG, "Failed to restart preview after takePicture()", e); }
                            finally {
                                try { unlockAeAwbAsync(); } catch (Throwable ignore) {}
                            }
                            latch.countDown();
                        }
                    });
                } catch (Exception e) { resultError.set("camera.takePicture() failed: " + e.getMessage()); latch.countDown(); }
            }
        });
        try {
            long picRemain = overallDeadline - System.currentTimeMillis();
            if (picRemain <= 0) return PhotoResult.fail("Timed out before shutter");
            if (!latch.await(picRemain, TimeUnit.MILLISECONDS)) return PhotoResult.fail("Timed out waiting for takePicture() to complete");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return PhotoResult.fail("Interrupted while waiting for takePicture()"); }
        String err = resultError.get();
        if (err != null) return PhotoResult.fail(err);
        byte[] jpeg = resultJpeg.get();
        if (jpeg == null) return PhotoResult.fail("takePicture() completed with no error but no JPEG data");
        return PhotoResult.ok(jpeg);
    }

    /** Same "closest area match" strategy as closestSupportedPreviewSize(), but against
     *  getSupportedPictureSizes() (the real single-shot capture size list) instead of
     *  getSupportedPreviewSizes() (the streaming preview size list) - see takePhoto()
     *  javadoc for why these need to be picked from different lists. */
    private static Camera.Size closestSupportedPictureSize(Camera.Parameters params,
            int wantWidth, int wantHeight) {
        return closestSizeByArea(params.getSupportedPictureSizes(), wantWidth, wantHeight);
    }

    /** Registers a listener for every future frame. Does NOT replay {@link #lastFrame} -
     *  callers that want an immediate first frame should read {@link #getLastFrame()}
     *  themselves before subscribing. */
    public void subscribe(FrameListener listener) {
        listeners.add(listener);
    }

    public void unsubscribe(FrameListener listener) {
        listeners.remove(listener);
    }

    /** Most recently produced frame, or null if streaming hasn't produced one yet. */
    public Frame getLastFrame() {
        return lastFrame;
    }

    public boolean isStreaming() {
        return camera != null;
    }

    /** 計算最近 FPS（基於滑動窗口內幀間隔），無幀或窗口不足回 0 */
    public synchronized double getFps() {
        if (fpsTimestamps.size() < 2) return 0;
        long first = fpsTimestamps.peekFirst();
        long last = fpsTimestamps.peekLast();
        double seconds = (last - first) / 1_000_000_000.0;
        if (seconds <= 0) return 0;
        return (fpsTimestamps.size() - 1) / seconds;
    }

    /**
     * 等待預覽 AE/AF 收斂：要求 startPreview 後至少 1400ms 且已收到 ≥5 幀且 FPS>4，
     * 否則快門捕到的正是曝光/對焦仍在拉動的過渡幀（實測「未 ready 就按 shutter」會過曝）。
     * 此法在 HttpServer 工作線程 polling，不阻塞 cameraHandler，避免卡住預覽回調。
     * @return true 已 ready，false 超時（仍可嘗試影，但畫質可能欠佳）
     */
    public boolean waitForPreviewReady(long timeoutMs) {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (camera == null) return false;
            long now = System.currentTimeMillis();
            long elapsed = now - previewStartedAtMs;
            long zoomElapsed = now - lastZoomChangeMs;
            boolean zoomSettled = lastZoomChangeMs == 0 || zoomElapsed >= 900;
            int frames;
            double fps;
            synchronized (this) { frames = fpsTimestamps.size(); fps = getFps(); }
            if (elapsed >= 1400 && zoomSettled && lastFrame != null && frames >= 5 && fps > 4.0) return true;
            if (previewStartedAtMs == 0) {
                if (!sleepQuietly(150)) return false;
                continue;
            }
            if (elapsed >= 1800 && zoomSettled) return lastFrame != null;
            if (!zoomSettled) {
                if (!sleepQuietly(150)) return false;
                continue;
            }
            if (!sleepQuietly(120)) return false;
        }
        return lastFrame != null;
    }

    /** 鎖定 AE/AWB（若硬件支援），在 shutter 前穩定曝光，避免 overexposure */
    public boolean lockAeAwbSync(long timeoutMs) {
        if (camera == null || cameraHandler == null) return false;
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<Boolean> ok = new AtomicReference<>(false);
        cameraHandler.post(new Runnable() { @Override public void run() {
            try {
                if (camera == null) return;
                Camera.Parameters p = camera.getParameters();
                boolean changed = false;
                try { if (p.isAutoExposureLockSupported() && !p.getAutoExposureLock()) { p.setAutoExposureLock(true); changed = true; } } catch (Throwable ignore) {}
                try { if (p.isAutoWhiteBalanceLockSupported() && !p.getAutoWhiteBalanceLock()) { p.setAutoWhiteBalanceLock(true); changed = true; } } catch (Throwable ignore) {}
                if (changed) { camera.setParameters(p); Log.i(TAG, "AE/AWB locked"); }
                ok.set(true);
            } catch (Throwable t) { Log.w(TAG, "lockAeAwb failed", t); }
            finally { latch.countDown(); }
        }});
        if (!awaitQuietly(latch, timeoutMs, TimeUnit.MILLISECONDS)) return false;
        return Boolean.TRUE.equals(ok.get());
    }
    public void unlockAeAwbAsync() {
        if (cameraHandler == null) return;
        cameraHandler.post(new Runnable() { @Override public void run() {
            try {
                if (camera == null) return;
                Camera.Parameters p = camera.getParameters();
                boolean changed = false;
                try { if (p.isAutoExposureLockSupported() && p.getAutoExposureLock()) { p.setAutoExposureLock(false); changed = true; } } catch (Throwable ignore) {}
                try { if (p.isAutoWhiteBalanceLockSupported() && p.getAutoWhiteBalanceLock()) { p.setAutoWhiteBalanceLock(false); changed = true; } } catch (Throwable ignore) {}
                if (changed) { camera.setParameters(p); Log.i(TAG, "AE/AWB unlocked"); }
            } catch (Throwable ignore) {}
        }});
    }

    /**
     * 在預覽幀路徑（snapshot）觸發一次自動對焦並等待回調，確保對好焦才取幀。
     * 連續對焦模式下 HAL 已在背景持續對焦，此處僅短暫等待；AUTO 模式則真正走 autoFocus。
     * 呼叫線程為 HttpServer 工作線程，不阻塞 cameraHandler（回調在 handler 線程計時）。
     */
    public boolean triggerAutoFocusAndWait(long timeoutMs) {
        if (camera == null || cameraHandler == null) return false;
        final AtomicReference<String> modeRef = new AtomicReference<>();
        final CountDownLatch modeLatch = new CountDownLatch(1);
        cameraHandler.post(new Runnable() { @Override public void run() { try { if (camera != null) modeRef.set(camera.getParameters().getFocusMode()); } catch (Throwable ignore) {} finally { modeLatch.countDown(); } } });
        if (!awaitQuietly(modeLatch, 600, TimeUnit.MILLISECONDS)) return false;
        String mode = modeRef.get();
        boolean needAf = mode == null || Camera.Parameters.FOCUS_MODE_AUTO.equals(mode) || Camera.Parameters.FOCUS_MODE_MACRO.equals(mode);
        if (!needAf) {
            sleepQuietly(150);
            return true;
        }
        final CountDownLatch afLatch = new CountDownLatch(1);
        cameraHandler.post(new Runnable() {
            @Override public void run() {
                if (camera == null) { afLatch.countDown(); return; }
                try { try { camera.cancelAutoFocus(); } catch (Throwable ignore) {} camera.autoFocus(new Camera.AutoFocusCallback() { @Override public void onAutoFocus(boolean success, Camera cam) { Log.i(TAG, "snapshot AF callback success=" + success); afLatch.countDown(); } }); } catch (Throwable t) { Log.w(TAG, "snapshot autoFocus throw", t); afLatch.countDown(); }
            }
        });
        try {
            boolean done = afLatch.await(Math.min(timeoutMs, 2200), TimeUnit.MILLISECONDS);
            if (!done) Log.w(TAG, "snapshot AF timeout");
            Thread.sleep(180);
            return done;
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); return false; }
    }

    // ── Zoom (x1-x5) ──────────────────────────────────────────
    public float getZoom() { return zoomFactor; }

    public static final class ZoomInfo {
        public final float current;
        public final boolean hardwareSupported;
        public final int maxZoom;
        public final java.util.List<Integer> ratios;
        ZoomInfo(float current, boolean hw, int max, java.util.List<Integer> ratios) {
            this.current = current; this.hardwareSupported = hw; this.maxZoom = max; this.ratios = ratios;
        }
    }

    /** 同步查詢當前變焦能力（需在 camera 線程讀參數，超時回 null） */
    public ZoomInfo getZoomInfoSync(long timeoutMs) {
        if (camera == null) {
            // 未開相機時仍回當前因子與「未知硬件能力」標記
            return new ZoomInfo(zoomFactor, false, 0, null);
        }
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<ZoomInfo> out = new AtomicReference<>();
        startCameraThreadIfNeeded();
        cameraHandler.post(new Runnable() {
            @Override public void run() {
                try {
                    Camera c = camera;
                    if (c == null) { out.set(new ZoomInfo(zoomFactor, false, 0, null)); }
                    else {
                        Camera.Parameters p = c.getParameters();
                        boolean hw = false;
                        try { hw = p.isZoomSupported(); } catch (Throwable ignore) {}
                        int max = 0; java.util.List<Integer> ratios = null;
                        if (hw) {
                            try { max = p.getMaxZoom(); } catch (Throwable ignore) {}
                            try { ratios = p.getZoomRatios(); } catch (Throwable ignore) {}
                        }
                        out.set(new ZoomInfo(zoomFactor, hw, max, ratios));
                    }
                } catch (Exception e) { out.set(new ZoomInfo(zoomFactor, false, 0, null)); }
                finally { latch.countDown(); }
            }
        });
        awaitQuietly(latch, timeoutMs, TimeUnit.MILLISECONDS);
        ZoomInfo v = out.get();
        return v != null ? v : new ZoomInfo(zoomFactor, false, 0, null);
    }

    /** 設定變焦 x1-5（1.0=無變焦）。硬件支援時映射到最接近的 ratio，否則僅記錄供軟件/前端使用 */
    public void setZoom(final float zoom) {
        final float clamped = Math.max(1.0f, Math.min(5.0f, zoom));
        zoomFactor = clamped;
        if (camera == null || cameraHandler == null) return;
        cameraHandler.post(new Runnable() {
            @Override public void run() { applyHardwareZoomLocked(clamped); }
        });
    }

    private void applyHardwareZoomLocked(float zoom) {
        Camera c = camera;
        if (c == null) return;
        try {
            Camera.Parameters p = c.getParameters();
            boolean hw = false;
            try { hw = p.isZoomSupported(); } catch (Throwable ignore) {}
            if (!hw) {
                Log.i(TAG, "setZoom x" + zoom + " — hardware zoom not supported, keeping software/frontend path");
                return;
            }
            int maxZoom = 0; java.util.List<Integer> ratios = null;
            try { maxZoom = p.getMaxZoom(); } catch (Throwable ignore) {}
            try { ratios = p.getZoomRatios(); } catch (Throwable ignore) {}
            int level = 0;
            if (ratios != null && !ratios.isEmpty()) {
                int want = Math.round(zoom * 100);
                int bestIdx = 0; int bestDiff = Integer.MAX_VALUE;
                for (int i = 0; i < ratios.size(); i++) {
                    int diff = Math.abs(ratios.get(i) - want);
                    if (diff < bestDiff) { bestDiff = diff; bestIdx = i; }
                }
                level = bestIdx;
            } else if (maxZoom > 0) {
                level = Math.round((zoom - 1.0f) / 4.0f * maxZoom);
                if (level < 0) level = 0;
                if (level > maxZoom) level = maxZoom;
            }
            p.setZoom(level);
            c.setParameters(p);
            Log.i(TAG, "Hardware zoom applied: x" + zoom + " -> level " + level + "/" + maxZoom + (ratios!=null?" ratios="+ratios.get(Math.min(level, ratios.size()-1)):""));
        } catch (Exception e) {
            Log.w(TAG, "applyHardwareZoom x" + zoom + " failed", e);
        }
    }

    /** 軟件數位變焦：將 JPEG 中心裁切再放大回原尺寸（用於拍照存檔，串流由前端 CSS 處理以免每幀重編碼） */
    public byte[] applySoftwareZoomToJpeg(byte[] jpeg, float zoom) {
        if (jpeg == null || zoom <= 1.01f) return jpeg;
        try {
            Bitmap src = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.length);
            if (src == null) return jpeg;
            int w = src.getWidth(), h = src.getHeight();
            int cropW = Math.max(1, Math.round(w / zoom));
            int cropH = Math.max(1, Math.round(h / zoom));
            int left = (w - cropW) / 2;
            int top = (h - cropH) / 2;
            // 保證偶數對齊，避免部分機型裁切異常
            left &= ~1; top &= ~1; cropW &= ~1; cropH &= ~1;
            if (cropW <= 0 || cropH <= 0) { src.recycle(); return jpeg; }
            Bitmap cropped = Bitmap.createBitmap(src, left, top, cropW, cropH);
            Bitmap scaled = Bitmap.createScaledBitmap(cropped, w, h, true);
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            scaled.compress(Bitmap.CompressFormat.JPEG, 85, out);
            byte[] ret = out.toByteArray();
            src.recycle(); if (cropped != src) cropped.recycle(); scaled.recycle();
            Log.i(TAG, "Software zoom x" + zoom + " applied: " + w + "x" + h + " crop " + cropW + "x" + cropH);
            return ret;
        } catch (Throwable t) {
            Log.w(TAG, "Software zoom failed x" + zoom, t);
            return jpeg;
        }
    }

    private void safeReleaseOnCameraThread() {
        if (camera != null) {
            try {
                camera.setPreviewCallbackWithBuffer(null);
            } catch (Exception ignored) {
            }
            try {
                camera.stopPreview();
            } catch (Exception ignored) {
            }
            try {
                camera.release();
            } catch (Exception ignored) {
            }
            camera = null;
            openedIndex = -1;
            lastFrame = null;
            previewStartedAtMs = 0;
            synchronized (this) {
                fpsTimestamps.clear();
            }
        }
    }

    private synchronized void startCameraThreadIfNeeded() {
        if (cameraThread == null || !cameraThread.isAlive()) {
            cameraThread = new HandlerThread("CameraControllerThread");
            cameraThread.start();
            cameraHandler = new Handler(cameraThread.getLooper());
        }
    }

    /**
     * Stops preview and releases the camera so another process/app can use it. Only
     * releases when there are no remaining stream subscribers - call this from a
     * client-disconnect path, not on a fixed schedule, so one browser tab closing
     * doesn't cut the stream out from under another that's still watching.
     */
    public void stopIfIdle() {
        if (cameraHandler == null) {
            return;
        }
        cameraHandler.post(new Runnable() {
            @Override
            public void run() {
                if (listeners.isEmpty()) {
                    safeReleaseOnCameraThread();
                }
            }
        });
    }

    /**
     * Forces the camera closed regardless of remaining stream listeners, and blocks the
     * calling thread until release has actually completed - unlike stopIfIdle(), which
     * only closes when idle and returns immediately either way. Needed for changing
     * resolution: an existing /stream/camera connection only notices its client
     * disconnected (and calls stopIfIdle() itself) whenever its next out.write() happens
     * to hit a broken pipe, which has no guaranteed timing. Racing a resolution change
     * against that produces exactly the "new stream opens against the still-open old
     * camera session, start() sees camera != null and no-ops, requested resolution never
     * takes effect" bug. This instead gives the caller (camera/resolution's HTTP handler)
     * a real synchronization point: by the time this returns, the camera is genuinely
     * closed, so the next start() is guaranteed to actually reopen it with the new size.
     */
    public void forceStopAndWait(long timeoutMs) {
        if (cameraHandler == null) {
            return;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        cameraHandler.post(new Runnable() {
            @Override
            public void run() {
                safeReleaseOnCameraThread();
                latch.countDown();
            }
        });
        awaitQuietly(latch, timeoutMs, TimeUnit.MILLISECONDS);
    }

    public void shutdown() {
        if (cameraHandler != null) {
            cameraHandler.post(new Runnable() {
                @Override
                public void run() {
                    safeReleaseOnCameraThread();
                    releasePreviewTextureLocked();
                }
            });
        }
        if (cameraThread != null) {
            cameraThread.quitSafely();
        }
    }
}
