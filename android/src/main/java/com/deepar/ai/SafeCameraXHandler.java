package com.deepar.ai;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.graphics.ImageFormat;
import android.media.Image;
import android.util.Log;
import android.util.Size;

import androidx.annotation.NonNull;
import androidx.camera.core.CameraSelector;
import androidx.camera.core.ImageAnalysis;
import androidx.camera.core.ImageProxy;
import androidx.camera.core.TorchState;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.core.content.ContextCompat;
import androidx.lifecycle.LifecycleOwner;

import com.google.common.util.concurrent.ListenableFuture;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import ai.deepar.ar.CameraResolutionPreset;
import ai.deepar.ar.DeepAR;
import ai.deepar.ar.DeepARImageFormat;
import io.flutter.plugin.common.MethodCall;
import io.flutter.plugin.common.MethodChannel;

public class SafeCameraXHandler implements MethodChannel.MethodCallHandler {
    private static final String TAG = "SafeCameraXHandler";

    SafeCameraXHandler(Activity activity, long textureId, DeepAR deepAR, CameraResolutionPreset cameraResolutionPreset) {
        this.activity = activity;
        this.deepAR = deepAR;
        this.textureId = textureId;
        this.resolutionPreset = cameraResolutionPreset;
    }

    final private Activity activity;
    private DeepAR deepAR;
    private final long textureId;
    private ProcessCameraProvider processCameraProvider;
    private ListenableFuture<ProcessCameraProvider> future;
    private ByteBuffer[] buffers;
    private int currentBuffer = 0;
    private static final int NUMBER_OF_BUFFERS = 2;
    private final CameraResolutionPreset resolutionPreset;

    private int defaultLensFacing = CameraSelector.LENS_FACING_FRONT;
    private int lensFacing = defaultLensFacing;
    private androidx.camera.core.Camera camera;

    // Added for safety
    private final AtomicBoolean isDestroyed = new AtomicBoolean(false);
    private final AtomicBoolean isCameraStarted = new AtomicBoolean(false);

    @Override
    public void onMethodCall(@NonNull MethodCall call, @NonNull MethodChannel.Result result) {
        try {
            switch (call.method) {
                case MethodStrings.startCamera:
                    startNative(result);
                    break;
                case "flip_camera":
                    flipCamera();
                    result.success(true);
                    break;
                case "toggle_flash":
                    boolean isFlash = toggleFlash();
                    result.success(isFlash);
                    break;
                case "destroy":
                    destroy();
                    result.success("SHUTDOWN");
                    break;
                default:
                    result.notImplemented();
                    break;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error in method call: " + call.method, e);
            result.error("ERROR", "Error in " + call.method + ": " + e.getMessage(), null);
        }
    }

    private boolean toggleFlash() {
        try {
            if (camera != null && camera.getCameraInfo().hasFlashUnit()) {
                // TorchState.OFF = 0; TorchState.ON = 1
                boolean isFlashOn = camera.getCameraInfo().getTorchState().getValue() == TorchState.ON;

                camera.getCameraControl().enableTorch(!isFlashOn);

                return !isFlashOn;
            }
        } catch (Exception e) {
            Log.e(TAG, "Error toggling flash", e);
        }

        return false;
    }

    private void flipCamera() {
        if (isDestroyed.get()) {
            Log.w(TAG, "Trying to flip camera after destruction");
            return;
        }

        lensFacing = lensFacing == CameraSelector.LENS_FACING_FRONT ? CameraSelector.LENS_FACING_BACK : CameraSelector.LENS_FACING_FRONT;
        //unbind immediately to avoid mirrored frame.
        ProcessCameraProvider cameraProvider = null;
        try {
            cameraProvider = future.get();
            cameraProvider.unbindAll();
            isCameraStarted.set(false);
        } catch (ExecutionException | InterruptedException e) {
            Log.e(TAG, "Error unbinding camera", e);
        }
        startNative(null);
    }

    private void startNative(MethodChannel.Result result) {
        if (isDestroyed.get()) {
            Log.w(TAG, "Trying to start camera after destruction");
            if (result != null) {
                result.error("DESTROYED", "Camera handler has been destroyed", null);
            }
            return;
        }

        if (isCameraStarted.get()) {
            Log.w(TAG, "Camera already started, stopping first");
            try {
                ProcessCameraProvider cameraProvider = future.get();
                cameraProvider.unbindAll();
                isCameraStarted.set(false);
            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Error unbinding camera", e);
            }
        }

        future = ProcessCameraProvider.getInstance(activity);
        Executor executor = ContextCompat.getMainExecutor(activity);

        int width;
        int height;
        int orientation = getScreenOrientation();
        if (orientation == ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE || orientation == ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE) {
            width = resolutionPreset.getWidth();
            height = resolutionPreset.getHeight();
        } else {
            width = resolutionPreset.getHeight();
            height = resolutionPreset.getWidth();
        }

        // Initialize buffers with proper error handling
        try {
            buffers = new ByteBuffer[NUMBER_OF_BUFFERS];
            for (int i = 0; i < NUMBER_OF_BUFFERS; i++) {
                buffers[i] = ByteBuffer.allocateDirect(
                        CameraResolutionPreset.P1920x1080.getWidth()
                                * CameraResolutionPreset.P1920x1080.getHeight() * 3);
                buffers[i].order(ByteOrder.nativeOrder());
                buffers[i].position(0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Error allocating buffers", e);
            if (result != null) {
                result.error("BUFFER_ERROR", "Failed to allocate camera buffers", e.getMessage());
            }
            return;
        }

        future.addListener(() -> {
            if (isDestroyed.get()) {
                Log.w(TAG, "Camera setup callback after destruction");
                if (result != null) {
                    result.error("DESTROYED", "Camera handler has been destroyed", null);
                }
                return;
            }

            try {
                processCameraProvider = future.get();
                Size cameraResolution = new Size(width, height);

                ImageAnalysis.Analyzer analyzer = new ImageAnalysis.Analyzer() {
                    @Override
                    public void analyze(@NonNull ImageProxy image) {
                        if (isDestroyed.get()) {
                            image.close();
                            return;
                        }

                        try {
                            Image img = image.getImage();
                            if (img == null || img.getFormat() != ImageFormat.YUV_420_888) {
                                image.close();
                                return;
                            }

                            ByteBuffer yBuffer = img.getPlanes()[0].getBuffer();
                            ByteBuffer uBuffer = img.getPlanes()[1].getBuffer();
                            ByteBuffer vBuffer = img.getPlanes()[2].getBuffer();

                            int ySize = yBuffer.remaining();
                            int uSize = uBuffer.remaining();
                            int vSize = vBuffer.remaining();

                            if (buffers[currentBuffer].capacity() < ySize + uSize + vSize) {
                                Log.e(TAG, "Buffer too small");
                                image.close();
                                return;
                            }

                            // Create a temporary byte array to handle the YUV data with U and V swapped
                            byte[] byteData = new byte[ySize + uSize + vSize];

                            // U and V need to be swapped for proper color processing
                            yBuffer.get(byteData, 0, ySize);
                            vBuffer.get(byteData, ySize, vSize);          // Note: V comes before U
                            uBuffer.get(byteData, ySize + vSize, uSize);  // This is the correct YUV order

                            // Reset buffer position and put the properly ordered data
                            buffers[currentBuffer].position(0);
                            buffers[currentBuffer].put(byteData);
                            buffers[currentBuffer].position(0);

                            if (deepAR != null && !isDestroyed.get()) {
                                try {
                                    deepAR.receiveFrame(buffers[currentBuffer],
                                            img.getWidth(), img.getHeight(),
                                            image.getImageInfo().getRotationDegrees(),
                                            lensFacing == CameraSelector.LENS_FACING_FRONT,
                                            DeepARImageFormat.YUV_420_888,
                                            img.getPlanes()[1].getPixelStride()
                                    );
                                } catch (Exception e) {
                                    Log.e(TAG, "Error processing frame", e);
                                }
                            }

                            currentBuffer = (currentBuffer + 1) % NUMBER_OF_BUFFERS;
                        } catch (Exception e) {
                            Log.e(TAG, "Error in image analysis", e);
                        } finally {
                            image.close();
                        }
                    }
                };

                CameraSelector cameraSelector = new CameraSelector.Builder().requireLensFacing(lensFacing).build();

                ImageAnalysis imageAnalysis = new ImageAnalysis.Builder()
                        .setTargetResolution(cameraResolution)
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build();

                imageAnalysis.setAnalyzer(executor, analyzer);

                processCameraProvider.unbindAll();

                camera = processCameraProvider.bindToLifecycle((LifecycleOwner) activity,
                        cameraSelector, imageAnalysis);

                isCameraStarted.set(true);
                Log.d(TAG, "Camera started successfully");

                if (result != null) {
                    result.success(textureId);
                }

            } catch (ExecutionException | InterruptedException e) {
                Log.e(TAG, "Error starting camera", e);
                if (result != null) {
                    result.error("CAMERA_ERROR", "Failed to start camera", e.getMessage());
                }
            } catch (Exception e) {
                Log.e(TAG, "Unexpected error starting camera", e);
                if (result != null) {
                    result.error("UNEXPECTED_ERROR", "Unexpected error starting camera", e.getMessage());
                }
            }
        }, executor);
    }

    public void destroy() {
        if (isDestroyed.compareAndSet(false, true)) {
            Log.d(TAG, "Destroying SafeCameraXHandler");
            try {
                if (processCameraProvider != null) {
                    processCameraProvider.unbindAll();
                    isCameraStarted.set(false);
                }

                if (deepAR != null) {
                    deepAR.setAREventListener(null);
                    deepAR.release();
                    deepAR = null;
                }

                // Clear buffers
                for (int i = 0; i < NUMBER_OF_BUFFERS; i++) {
                    if (buffers != null && buffers[i] != null) {
                        buffers[i] = null;
                    }
                }
                buffers = null;

                Log.d(TAG, "SafeCameraXHandler destroyed successfully");
            } catch (Exception e) {
                Log.e(TAG, "Error during destroy", e);
            }
        } else {
            Log.w(TAG, "SafeCameraXHandler already destroyed");
        }
    }

    private int getScreenOrientation() {
        return activity.getResources().getConfiguration().orientation;
    }
}
