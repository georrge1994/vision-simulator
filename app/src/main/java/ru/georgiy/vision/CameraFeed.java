package ru.georgiy.vision;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.util.Size;
import android.util.SizeF;
import android.view.Surface;

import java.util.Collections;

final class CameraFeed {
    interface Listener {
        void onGeometry(Geometry geometry);
        void onError(int message);
    }

    static final class Geometry {
        final float aspect;
        final float horizontalSpan;
        final float verticalSpan;

        Geometry(Size size, float horizontalSpan, float verticalSpan) {
            aspect = (float) size.getWidth() / size.getHeight();
            this.horizontalSpan = horizontalSpan;
            this.verticalSpan = verticalSpan;
        }
    }

    private static final String TAG = "VisionCamera";
    private final Context context;
    private final CameraManager manager;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private CameraDevice device;
    private CameraCaptureSession session;
    private Surface surface;
    private int generation;
    private boolean opening;

    CameraFeed(Context context, Listener listener) {
        this.context = context;
        manager = context.getSystemService(CameraManager.class);
        this.listener = listener;
    }

    boolean isActive() {
        return opening || device != null;
    }

    @SuppressLint("MissingPermission")
    void open(SurfaceTexture texture) {
        if (isActive()) return;
        int token = ++generation;
        try {
            String id = rearCameraId();
            if (id == null) {
                fail(token, R.string.camera_missing, null);
                return;
            }
            CameraCharacteristics camera = manager.getCameraCharacteristics(id);
            StreamConfigurationMap streams = camera.get(
                    CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            Size[] sizes = streams == null ? null : streams.getOutputSizes(SurfaceTexture.class);
            Size size = previewSize(sizes);
            if (size == null) {
                fail(token, R.string.camera_preview_unsupported, null);
                return;
            }

            texture.setDefaultBufferSize(size.getWidth(), size.getHeight());
            surface = new Surface(texture);
            listener.onGeometry(geometry(camera, size));
            opening = true;
            manager.openCamera(id, new CameraDevice.StateCallback() {
                @Override public void onOpened(CameraDevice opened) {
                    if (token != generation) {
                        opened.close();
                        return;
                    }
                    opening = false;
                    device = opened;
                    startPreview(camera, token);
                }

                @Override public void onDisconnected(CameraDevice disconnected) {
                    disconnected.close();
                    fail(token, R.string.camera_disconnected, null);
                }

                @Override public void onError(CameraDevice failed, int error) {
                    failed.close();
                    fail(token, R.string.camera_unavailable, null);
                }
            }, main);
        } catch (CameraAccessException | IllegalArgumentException | IllegalStateException
                | SecurityException error) {
            fail(token, R.string.camera_open_failed, error);
        }
    }

    private void startPreview(CameraCharacteristics camera, int token) {
        try {
            CaptureRequest.Builder request = device.createCaptureRequest(
                    CameraDevice.TEMPLATE_PREVIEW);
            request.addTarget(surface);
            int[] focusModes = camera.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
            if (focusModes != null) {
                for (int mode : focusModes) {
                    if (mode == CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) {
                        request.set(CaptureRequest.CONTROL_AF_MODE, mode);
                        break;
                    }
                }
            }
            device.createCaptureSession(Collections.singletonList(surface),
                    new CameraCaptureSession.StateCallback() {
                @Override public void onConfigured(CameraCaptureSession configured) {
                    if (token != generation) {
                        configured.close();
                        return;
                    }
                    session = configured;
                    try {
                        session.setRepeatingRequest(request.build(), null, main);
                    } catch (CameraAccessException | IllegalArgumentException
                            | IllegalStateException error) {
                        fail(token, R.string.camera_preview_failed, error);
                    }
                }

                @Override public void onConfigureFailed(CameraCaptureSession failed) {
                    failed.close();
                    fail(token, R.string.camera_configuration_failed, null);
                }
            }, main);
        } catch (CameraAccessException | IllegalArgumentException | IllegalStateException error) {
            fail(token, R.string.camera_preview_failed, error);
        }
    }

    void close() {
        generation++;
        opening = false;
        if (session != null) session.close();
        if (device != null) device.close();
        if (surface != null) surface.release();
        session = null;
        device = null;
        surface = null;
    }

    private void fail(int token, int message, Exception error) {
        if (token != generation) return;
        if (error != null) Log.w(TAG, context.getString(message), error);
        close();
        listener.onError(message);
    }

    private String rearCameraId() throws CameraAccessException {
        String fallback = null;
        for (String id : manager.getCameraIdList()) {
            Integer facing = manager.getCameraCharacteristics(id).get(CameraCharacteristics.LENS_FACING);
            if (facing != null && facing == CameraCharacteristics.LENS_FACING_BACK) return id;
            if (fallback == null) fallback = id;
        }
        return fallback;
    }

    private static Size previewSize(Size[] sizes) {
        if (sizes == null || sizes.length == 0) return null;
        Size best = sizes[0];
        double bestScore = Double.POSITIVE_INFINITY;
        for (Size size : sizes) {
            double longSide = Math.max(size.getWidth(), size.getHeight());
            double shortSide = Math.min(size.getWidth(), size.getHeight());
            double score = Math.abs(Math.log(longSide / 1280d))
                    + 0.45d * Math.abs(longSide / shortSide - 16d / 9d);
            if (score < bestScore) {
                bestScore = score;
                best = size;
            }
        }
        return best;
    }

    private static Geometry geometry(CameraCharacteristics camera, Size size) {
        SizeF sensor = camera.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        float[] focalLengths = camera.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        float aspect = (float) size.getWidth() / size.getHeight();
        float horizontal = 1.33f;
        float vertical = horizontal / aspect;
        if (sensor != null && focalLengths != null && focalLengths.length > 0
                && focalLengths[0] > 0 && sensor.getWidth() > 0 && sensor.getHeight() > 0) {
            horizontal = sensor.getWidth() / focalLengths[0];
            vertical = sensor.getHeight() / focalLengths[0];
            horizontal = Math.min(horizontal, vertical * aspect);
            vertical = horizontal / aspect;
        }
        return new Geometry(size, horizontal, vertical);
    }
}
