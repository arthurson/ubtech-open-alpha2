package com.open.alpha2;

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
                        for (int idx : CAMERA_INDEX_CANDIDATES) {
                            try { tmp = android.hardware.Camera.open(idx); break; } catch (Exception ignored) {}
                        }
                        if (tmp == null) { err.set("Camera.open failed for all indices"); }
                        else { result.set(tmp.getParameters().getSupportedPreviewSizes()); }
                    }
                } catch (Exception e) { err.set(e.getMessage()); }
                finally {
                    if (tmp != null) { try { tmp.release(); } catch (Exception ignored) {} }
                    latch.countDown();
                }
            }
        });
        try { latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
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
                        for (int idx : CAMERA_INDEX_CANDIDATES) {
                            try { tmp = android.hardware.Camera.open(idx); break; } catch (Exception ignored) {}
                        }
                        if (tmp != null) result.set(tmp.getParameters().getSupportedPictureSizes());
                    }
                } catch (Exception ignored) { result.set(null); }
                finally {
                    if (tmp != null) { try { tmp.release(); } catch (Exception ignored) {} }
                    latch.countDown();
                }
            }
        });
        try { latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
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
                        for (int idx : CAMERA_INDEX_CANDIDATES) {
                            try { tmp = android.hardware.Camera.open(idx); break; } catch (Exception ignored) {}
                        }
                        if (tmp != null) result.set(tmp.getParameters().getSupportedPreviewFpsRange());
                    }
                } catch (Exception ignored) {}
                finally {
                    if (tmp != null) { try { tmp.release(); } catch (Exception ignored) {} }
                    latch.countDown();
                }
            }
        });
        try { latch.await(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
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

        camera.setParameters(params);

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
    }

    private static byte[] nv21ToJpeg(byte[] nv21, int width, int height) {
        YuvImage yuv = new YuvImage(nv21, ImageFormat.NV21, width, height, null);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        yuv.compressToJpeg(new Rect(0, 0, width, height), JPEG_QUALITY, out);
        return out.toByteArray();
    }

    /** Picks the supported preview size with the smallest area difference from the
     *  requested width/height, since most legacy Camera HALs reject arbitrary sizes
     *  outright. Returns null if the driver reports no supported-size list at all. */
    private static Camera.Size closestSupportedPreviewSize(Camera.Parameters params,
            int wantWidth, int wantHeight) {
        List<Camera.Size> sizes = params.getSupportedPreviewSizes();
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

    // 2026-08 新增 (真正根源修正): 之前 self.camera.take_photo 一直複用
    // getLastFrame() 取的 preview stream frame (也就是這個 class 頭段 comment 說的
    // "continuous webcam-style streaming, NOT single-shot photos" 那套 pipeline) -
    // 反編譯一個用戶提供、實測上傳成功的第三方 apk (package com.huihongcloud.xiaozhi,
    // 用 androidx.camera CameraX 的 ImageCapture) 之後發現: 它送去 server 的照片是
    // 用真正的單張拍攝 (busy-wait poll 著 ImageCapture 完成 callback 的
    // photoBytes/doneFlag, 每 10ms check 一次), 不是 preview frame。preview frame
    // 沒經過相機 HAL 完整的單張 AE/AF/降噪 pipeline, 用戶已經核實過 server 端存下的照片
    // 解析度都對 (480x360, 不是解析度太小的問題), 所以真正差異在於 capture 方式
    // 本身, 不是 output size。
    //
    // 這個 method 用回這個 class 現有的 camera 實例 (要求 start() 已經成功才可以
    // call), 用 Camera1 legacy API 的 camera.takePicture(shutter, raw, jpeg)
    // 做一次真正的單張拍攝, jpeg callback 拿到的才是 driver 真正做完 AE/AF 收斂、完整
    // ISP pipeline 之後的照片, 不再靠 waitForStableFrame() 那樣單純等夠幀數來迴避
    // preview frame 過渡期問題 - takePicture() 本身就已經是硬體執行中的單張拍攝
    // 流程, 這個 workaround 就不再需要。
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
        final CountDownLatch latch = new CountDownLatch(1);
        final AtomicReference<byte[]> resultJpeg = new AtomicReference<>();
        final AtomicReference<String> resultError = new AtomicReference<>();

        cameraHandler.post(new Runnable() {
            @Override
            public void run() {
                if (camera == null) {
                    resultError.set("camera was released before takePicture() could run");
                    latch.countDown();
                    return;
                }
                try {
                    Camera.Parameters params = camera.getParameters();
                    Camera.Size bestPictureSize =
                            closestSupportedPictureSize(params, wantWidth, wantHeight);
                    if (bestPictureSize != null) {
                        params.setPictureSize(bestPictureSize.width, bestPictureSize.height);
                        camera.setParameters(params);
                        Log.i(TAG, "takePicture() picture size: " + bestPictureSize.width
                                + "x" + bestPictureSize.height + " (requested " + wantWidth
                                + "x" + wantHeight + ")");
                    } else {
                        Log.w(TAG, "takePicture(): driver reported no supported picture "
                                + "sizes, using driver default");
                    }
                } catch (Exception e) {
                    resultError.set("Failed to set picture size: " + e.getMessage());
                    latch.countDown();
                    return;
                }
                try {
                    camera.takePicture(null, null, new Camera.PictureCallback() {
                        @Override
                        public void onPictureTaken(byte[] data, Camera cam) {
                            resultJpeg.set(data);
                            // takePicture() stops preview as a side effect (Camera1
                            // contract) - restart it on this same camera thread so any
                            // camera/snapshot streaming subscribers keep receiving
                            // frames, and getLastFrame() keeps advancing.
                            try {
                                if (camera != null) {
                                    camera.startPreview();
                                }
                            } catch (Exception e) {
                                Log.w(TAG, "Failed to restart preview after takePicture()", e);
                            }
                            latch.countDown();
                        }
                    });
                } catch (Exception e) {
                    resultError.set("camera.takePicture() failed: " + e.getMessage());
                    latch.countDown();
                }
            }
        });

        try {
            if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                return PhotoResult.fail("Timed out waiting for takePicture() to complete");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return PhotoResult.fail("Interrupted while waiting for takePicture()");
        }
        String err = resultError.get();
        if (err != null) {
            return PhotoResult.fail(err);
        }
        byte[] jpeg = resultJpeg.get();
        if (jpeg == null) {
            return PhotoResult.fail("takePicture() completed with no error but no JPEG data");
        }
        return PhotoResult.ok(jpeg);
    }

    /** Same "closest area match" strategy as closestSupportedPreviewSize(), but against
     *  getSupportedPictureSizes() (the real single-shot capture size list) instead of
     *  getSupportedPreviewSizes() (the streaming preview size list) - see takePhoto()
     *  javadoc for why these need to be picked from different lists. */
    private static Camera.Size closestSupportedPictureSize(Camera.Parameters params,
            int wantWidth, int wantHeight) {
        List<Camera.Size> sizes = params.getSupportedPictureSizes();
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
        try {
            latch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
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
