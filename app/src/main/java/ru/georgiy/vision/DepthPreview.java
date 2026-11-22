package ru.georgiy.vision;

import android.media.Image;
import android.opengl.GLES20;
import android.view.Surface;

import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.NotYetAvailableException;
import com.google.ar.core.exceptions.ResourceExhaustedException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

final class DepthPreview {
    private static final long MAX_DEPTH_AGE_NS = 200_000_000L;
    private static final float[] NDC_CORNERS = {-1f, -1f, 1f, -1f, -1f, 1f};

    private final float[] uv = new float[6];
    private final float[] projection = new float[16];
    final float[] uvTransform = new float[16];
    final int cameraTexture;
    final int depthTexture;
    float pixelsPerTangent;
    boolean hasDepth;

    private Session boundSession;
    private ByteBuffer depthBytes;
    private int rotation = -1;
    private int width;
    private int height;
    private int depthWidth;
    private int depthHeight;
    private long depthTimestamp;
    private long lastValidFrameTimestamp;
    private boolean needsTransform = true;

    DepthPreview(int cameraTexture) {
        this.cameraTexture = cameraTexture;
        int[] names = new int[1];
        GLES20.glGenTextures(1, names, 0);
        depthTexture = names[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTexture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, 1, 1, 0,
                GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, null);
        uvTransform[10] = 1f;
        uvTransform[15] = 1f;
    }

    void resetSession() {
        boundSession = null;
        hasDepth = false;
        lastValidFrameTimestamp = 0;
        depthTimestamp = 0;
        needsTransform = true;
    }

    boolean update(Session session, int displayDegrees, int newWidth, int newHeight,
            boolean automatic) throws CameraNotAvailableException {
        if (boundSession != session) {
            resetSession();
            boundSession = session;
            session.setCameraTextureName(cameraTexture);
        }
        int surfaceRotation = surfaceRotation(displayDegrees);
        if (needsTransform || width != newWidth || height != newHeight || rotation != surfaceRotation) {
            session.setDisplayGeometry(surfaceRotation, newWidth, newHeight);
            rotation = surfaceRotation;
            width = newWidth;
            height = newHeight;
            needsTransform = true;
        }

        Frame frame = session.update();
        long frameTimestamp = frame.getTimestamp();
        if (frameTimestamp == 0) return false;

        if (needsTransform || frame.hasDisplayGeometryChanged()) {
            frame.transformCoordinates2d(Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                    NDC_CORNERS, Coordinates2d.TEXTURE_NORMALIZED, uv);
            uvTransform[0] = uv[2] - uv[0];
            uvTransform[1] = uv[3] - uv[1];
            uvTransform[4] = uv[4] - uv[0];
            uvTransform[5] = uv[5] - uv[1];
            uvTransform[12] = uv[0];
            uvTransform[13] = uv[1];
            needsTransform = false;
        }
        com.google.ar.core.Camera camera = frame.getCamera();
        camera.getProjectionMatrix(projection, 0, 0.05f, 100f);
        pixelsPerTangent = 0.5f * width * Math.abs(projection[0]);
        if (pixelsPerTangent <= 0f) pixelsPerTangent = width;

        hasDepth = false;
        boolean tracking = camera.getTrackingState() == TrackingState.TRACKING;
        if (automatic && tracking) {
            try (Image image = frame.acquireDepthImage16Bits()) {
                if (image.getTimestamp() != depthTimestamp) {
                    if (upload(image)) lastValidFrameTimestamp = frameTimestamp;
                    depthTimestamp = image.getTimestamp();
                }
            } catch (NotYetAvailableException | ResourceExhaustedException unavailable) {
                // Keep showing the camera while ARCore collects enough motion.
            }
            hasDepth = lastValidFrameTimestamp != 0
                    && frameTimestamp - lastValidFrameTimestamp <= MAX_DEPTH_AGE_NS;
        } else if (!tracking) {
            lastValidFrameTimestamp = 0;
            depthTimestamp = 0;
        }
        return true;
    }

    private boolean upload(Image image) {
        int cols = image.getWidth();
        int rows = image.getHeight();
        if (depthBytes == null || depthBytes.capacity() < cols * rows) {
            depthBytes = ByteBuffer.allocateDirect(cols * rows);
        }
        depthBytes.clear();
        Image.Plane plane = image.getPlanes()[0];
        ByteBuffer raw = plane.getBuffer().order(ByteOrder.LITTLE_ENDIAN);
        int offset = raw.position();
        int rowStride = plane.getRowStride();
        int pixelStride = plane.getPixelStride();
        int depthBits = 0;
        for (int y = 0; y < rows; y++) {
            int row = offset + y * rowStride;
            for (int x = 0; x < cols; x++) {
                int millimeters = Short.toUnsignedInt(raw.getShort(row + x * pixelStride));
                depthBits |= millimeters;
                depthBytes.put(Optics.encodeDepth(millimeters));
            }
        }
        if (depthBits == 0) return false;
        depthBytes.flip();
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthTexture);
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1);
        if (depthWidth == cols && depthHeight == rows) {
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, cols, rows,
                    GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, depthBytes);
        } else {
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, cols, rows, 0,
                    GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, depthBytes);
            depthWidth = cols;
            depthHeight = rows;
        }
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4);
        return true;
    }

    private static int surfaceRotation(int degrees) {
        switch (degrees) {
            case 90: return Surface.ROTATION_90;
            case 180: return Surface.ROTATION_180;
            case 270: return Surface.ROTATION_270;
            default: return Surface.ROTATION_0;
        }
    }
}
