package ru.georgiy.vision;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.util.Log;

import com.google.ar.core.Session;
import com.google.ar.core.exceptions.CameraNotAvailableException;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

final class PreviewView extends GLSurfaceView {
    interface Listener {
        void onTextureReady(SurfaceTexture texture);
        void onFirstFrame(SurfaceTexture texture);
        void onDepthFrame(Session session, boolean available);
        void onDepthError(Session session);
        void onRendererError();
    }

    private final PreviewRenderer renderer;
    private int bufferWidth;
    private int bufferHeight;

    PreviewView(Context context, Listener listener) {
        super(context);
        setEGLContextClientVersion(2);
        setPreserveEGLContextOnPause(true);
        renderer = new PreviewRenderer(listener);
        setRenderer(renderer);
        setRenderMode(RENDERMODE_WHEN_DIRTY);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        if (width == 0 || height == 0) return;
        float scale = Math.min(1f, 1280f / Math.max(width, height));
        int newWidth = Math.max(1, Math.round(width * scale));
        int newHeight = Math.max(1, Math.round(height * scale));
        if (newWidth != bufferWidth || newHeight != bufferHeight) {
            bufferWidth = newWidth;
            bufferHeight = newHeight;
            getHolder().setFixedSize(bufferWidth, bufferHeight);
        }
    }

    void setGeometry(CameraFeed.Geometry geometry, int displayDegrees) {
        renderer.geometry = geometry;
        renderer.displayDegrees = displayDegrees;
        requestRender();
    }

    void setDisplayRotation(int displayDegrees) {
        renderer.displayDegrees = displayDegrees;
        requestRender();
    }

    void setEffect(int myopia, int astigmatism, int haze, boolean sharpen, int distance) {
        renderer.effect = new Effect(myopia, astigmatism, haze, sharpen, distance);
        requestRender();
    }

    void setArSession(Session session) {
        renderer.arSession = session;
        queueEvent(renderer::resetDepth);
        setRenderMode(session == null ? RENDERMODE_WHEN_DIRTY : RENDERMODE_CONTINUOUSLY);
        requestRender();
    }

    void clearFrame() {
        queueEvent(renderer::clearFrame);
        requestRender();
    }

    void dispose() {
        if (renderer.texture != null) renderer.texture.release();
    }

    private static final class Effect {
        final int myopia;
        final int astigmatism;
        final int haze;
        final boolean sharpen;
        final int distance;

        Effect(int myopia, int astigmatism, int haze, boolean sharpen, int distance) {
            this.myopia = myopia;
            this.astigmatism = astigmatism;
            this.haze = haze;
            this.sharpen = sharpen;
            this.distance = distance;
        }
    }

    private final class PreviewRenderer implements GLSurfaceView.Renderer {
        private final Listener listener;
        private final FloatBuffer vertices = ByteBuffer.allocateDirect(8 * Float.BYTES)
                .order(ByteOrder.nativeOrder()).asFloatBuffer();
        private final AtomicBoolean incomingFrame = new AtomicBoolean();
        private final float[] transform = new float[16];
        private volatile SurfaceTexture texture;
        private volatile CameraFeed.Geometry geometry;
        private volatile int displayDegrees;
        private volatile Effect effect = new Effect(6, 0, 0, false, Optics.AUTOMATIC);
        private volatile Session arSession;
        private DepthPreview depthPreview;
        private Session failedSession;
        private boolean hasArFrame;
        private boolean lastDepthAvailable;
        private boolean hasFrame;
        private int program;
        private int textureId;
        private int position;
        private int screenToPreview;
        private int textureTransform;
        private int pixelAxes;
        private int myopia;
        private int blurVariance;
        private int depthSampler;
        private int depthEnabled;
        private int sharpen;
        private int haze;
        private int sampler;
        private int width;
        private int height;

        PreviewRenderer(Listener listener) {
            this.listener = listener;
            vertices.put(new float[] {-1, -1, 1, -1, -1, 1, 1, 1}).position(0);
        }

        @Override public void onSurfaceCreated(GL10 gl, EGLConfig config) {
            if (texture != null) texture.release();
            texture = null;
            clearFrame();
            GLES20.glClearColor(0.063f, 0.09f, 0.075f, 1f);
            try {
                program = createProgram();
                position = GLES20.glGetAttribLocation(program, "aPosition");
                screenToPreview = GLES20.glGetUniformLocation(program, "uScreenToPreview");
                textureTransform = GLES20.glGetUniformLocation(program, "uTextureTransform");
                pixelAxes = GLES20.glGetUniformLocation(program, "uPixelAxes");
                myopia = GLES20.glGetUniformLocation(program, "uMyopia");
                blurVariance = GLES20.glGetUniformLocation(program, "uBlurVariance");
                depthSampler = GLES20.glGetUniformLocation(program, "uDepth");
                depthEnabled = GLES20.glGetUniformLocation(program, "uDepthEnabled");
                sharpen = GLES20.glGetUniformLocation(program, "uSharpen");
                haze = GLES20.glGetUniformLocation(program, "uHaze");
                sampler = GLES20.glGetUniformLocation(program, "uCamera");

                textureId = createCameraTexture();
                depthPreview = new DepthPreview(createCameraTexture());
                resetDepth();
                SurfaceTexture created = new SurfaceTexture(textureId);
                texture = created;
                created.setOnFrameAvailableListener(producer -> {
                    if (producer != texture) return;
                    incomingFrame.set(true);
                    requestRender();
                });
                post(() -> {
                    if (texture == created) listener.onTextureReady(created);
                });
            } catch (RuntimeException error) {
                Log.e("VisionPreview", "Could not initialize OpenGL preview", error);
                post(listener::onRendererError);
            }
        }

        void clearFrame() {
            hasFrame = false;
            incomingFrame.set(false);
        }

        void resetDepth() {
            if (depthPreview != null) depthPreview.resetSession();
            failedSession = null;
            hasArFrame = false;
            lastDepthAvailable = false;
        }

        @Override public void onSurfaceChanged(GL10 gl, int width, int height) {
            this.width = width;
            this.height = height;
            GLES20.glViewport(0, 0, width, height);
        }

        @Override public void onDrawFrame(GL10 gl) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            if (width == 0 || height == 0 || depthPreview == null || texture == null) return;
            Session depthSession = arSession;
            Effect currentEffect = effect;
            int rotation = displayDegrees;
            if (depthSession != null) {
                drawDepthFrame(depthSession, currentEffect, rotation);
                return;
            }
            SurfaceTexture current = texture;
            CameraFeed.Geometry camera = geometry;
            if (camera == null) return;

            if (incomingFrame.getAndSet(false)) {
                current.updateTexImage();
                current.getTransformMatrix(transform);
                if (!hasFrame) {
                    hasFrame = true;
                    post(() -> listener.onFirstFrame(current));
                }
            }
            if (!hasFrame) return;

            // Camera2 supplies the sensor rotation and mirroring through SurfaceTexture.
            // The display rotation is the only extra rotation in preview coordinates.
            boolean textureSwapsAxes = Math.abs(transform[1]) > Math.abs(transform[0]);
            boolean displaySwapsAxes = rotation % 180 != 0;
            boolean sideways = textureSwapsAxes != displaySwapsAxes;
            float cameraAspect = sideways ? 1f / camera.aspect : camera.aspect;
            float viewAspect = (float) width / height;
            float cropX = Math.min(1f, viewAspect / cameraAspect);
            float cropY = Math.min(1f, cameraAspect / viewAspect);
            double radians = Math.toRadians(-rotation);
            float cos = (float) Math.cos(radians);
            float sin = (float) Math.sin(radians);
            float previewXFromX = cos * cropX;
            float previewYFromX = sin * cropX;
            float previewXFromY = -sin * cropY;
            float previewYFromY = cos * cropY;

            float span = (sideways ? camera.verticalSpan : camera.horizontalSpan) * cropX;
            drawCamera(textureId, transform, previewXFromX, previewYFromX, previewXFromY,
                    previewYFromY, width / span, currentEffect, false);
        }

        private void drawDepthFrame(Session session, Effect current, int rotation) {
            if (session == failedSession) return;
            boolean automatic = current.myopia > 0 && current.distance == Optics.AUTOMATIC;
            try {
                if (!depthPreview.update(session, rotation, width, height, automatic)) return;
            } catch (CameraNotAvailableException | RuntimeException error) {
                Log.w("VisionPreview", "ARCore camera stopped", error);
                failedSession = session;
                post(() -> listener.onDepthError(session));
                return;
            }
            boolean available = depthPreview.hasDepth;
            drawCamera(depthPreview.cameraTexture, depthPreview.uvTransform, 1f, 0f, 0f, 1f,
                    depthPreview.pixelsPerTangent, current, available);
            if (!hasArFrame || available != lastDepthAvailable) {
                hasArFrame = true;
                lastDepthAvailable = available;
                post(() -> listener.onDepthFrame(session, available));
            }
        }

        private void drawCamera(int cameraTexture, float[] cameraTransform,
                float xx, float xy, float yx, float yy, float pixelsPerTangent, Effect current,
                boolean automatic) {
            GLES20.glUseProgram(program);
            GLES20.glUniform4f(screenToPreview, xx, xy, yx, yy);
            GLES20.glUniformMatrix4fv(textureTransform, 1, false, cameraTransform, 0);
            float cos = current.astigmatism > 0 ? 0.9063f : 1f;
            float sin = current.astigmatism > 0 ? 0.4226f : 0f;
            GLES20.glUniform4f(pixelAxes,
                    xx * cos / width + yx * sin / height,
                    xy * cos / width + yy * sin / height,
                    -xx * sin / width + yx * cos / height,
                    -xy * sin / width + yy * cos / height);
            GLES20.glUniform3f(myopia, current.myopia,
                    Optics.PUPIL_RADIUS_METERS * pixelsPerTangent, Optics.manualVergence(current.distance));
            float shortSide = Math.min(width, height);
            float cylinderRadius = 0.028f * shortSide * current.astigmatism / 100f;
            float hazeAmount = current.haze / 100f;
            float hazeRadius = 0.022f * shortSide * hazeAmount;
            float hazeVariance = hazeRadius * hazeRadius;
            float cylinderVariance = cylinderRadius * cylinderRadius;
            GLES20.glUniform2f(blurVariance, cylinderVariance + hazeVariance,
                    cylinderVariance * 0.0625f + hazeVariance);
            GLES20.glUniform1f(sharpen, current.sharpen ? 0.42f : 0f);
            GLES20.glUniform1f(haze, hazeAmount);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, depthPreview.depthTexture);
            GLES20.glUniform1i(depthSampler, 1);
            GLES20.glUniform1f(depthEnabled, automatic ? 1f : 0f);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture);
            GLES20.glUniform1i(sampler, 0);
            GLES20.glEnableVertexAttribArray(position);
            GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, vertices);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        }

    }

    private static int createCameraTexture() {
        int[] handles = new int[1];
        GLES20.glGenTextures(1, handles, 0);
        int target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
        GLES20.glBindTexture(target, handles[0]);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return handles[0];
    }

    private static final String SHADER_PRECISION =
            "#ifdef GL_FRAGMENT_PRECISION_HIGH\n"
            + "precision highp float;\n"
            + "#else\n"
            + "precision mediump float;\n"
            + "#endif\n";

    private static final String VERTEX_SHADER = SHADER_PRECISION
            + "attribute vec2 aPosition;\n"
            + "uniform vec4 uScreenToPreview;\n"
            + "varying vec2 vPreview;\n"
            + "void main() {\n"
            + "  gl_Position = vec4(aPosition, 0.0, 1.0);\n"
            + "  vPreview = vec2(0.5) + 0.5 * (uScreenToPreview.xy * aPosition.x"
            + " + uScreenToPreview.zw * aPosition.y);\n"
            + "}\n";

    private static String fragmentShader() {
        StringBuilder shader = new StringBuilder(
                "#extension GL_OES_EGL_image_external : require\n"
                + SHADER_PRECISION
                + "uniform samplerExternalOES uCamera;\n"
                + "uniform sampler2D uDepth;\n"
                + "uniform mat4 uTextureTransform;\n"
                + "uniform vec4 uPixelAxes;\n"
                + "uniform vec3 uMyopia;\n"
                + "uniform vec2 uBlurVariance;\n"
                + "uniform float uDepthEnabled;\n"
                + "uniform float uSharpen;\n"
                + "uniform float uHaze;\n"
                + "varying vec2 vPreview;\n"
                + "vec2 sourceUv(vec2 p) {\n"
                + "  return (uTextureTransform * vec4(clamp(p, 0.0, 1.0), 0.0, 1.0)).xy;\n"
                + "}\n"
                + "vec3 sampleCamera(vec2 p) {\n"
                + "  return texture2D(uCamera, sourceUv(p)).rgb;\n"
                + "}\n"
                + "void main() {\n"
                + "  vec2 uv = sourceUv(vPreview);\n"
                + "  vec3 rgb = texture2D(uCamera, uv).rgb;\n"
                + "  if (uSharpen > 0.0) {\n"
                + "    vec2 x = 1.25 * uPixelAxes.xy;\n"
                + "    vec2 y = 1.25 * uPixelAxes.zw;\n"
                + "    vec3 neighbors = sampleCamera(vPreview + x) + sampleCamera(vPreview - x)\n"
                + "        + sampleCamera(vPreview + y) + sampleCamera(vPreview - y);\n"
                + "    float detail = dot(rgb - 0.25 * neighbors, vec3(0.2126, 0.7152, 0.0722));\n"
                + "    rgb = clamp(rgb + vec3(uSharpen * clamp(detail, -0.13, 0.13)), 0.0, 1.0);\n"
                + "  } else {\n"
                + "    float vergence = uMyopia.z;\n"
                + "    if (uDepthEnabled > 0.5) {\n"
                + "      vergence = texture2D(uDepth, uv).r * " + Optics.MAX_DEPTH_VERGENCE + ".0;\n"
                + "    }\n"
                + "    float sphere = min(26.0, max(0.0, uMyopia.x - vergence) * uMyopia.y);\n"
                + "    vec2 radii = sqrt(uBlurVariance + vec2(sphere * sphere));\n"
                + "    if (max(radii.x, radii.y) > 0.20) {\n"
                + "      vec2 major = radii.x * uPixelAxes.xy;\n"
                + "      vec2 minor = radii.y * uPixelAxes.zw;\n");

        // Evenly distribute samples over a disk to approximate a defocused pupil.
        int pairs = 12;
        for (int index = 0; index < pairs; index++) {
            double radius = Math.sqrt((index + 0.5) / pairs);
            double angle = index * 2.399963229728653;
            String x = String.format(Locale.ROOT, "%.7f", radius * Math.cos(angle));
            String y = String.format(Locale.ROOT, "%.7f", radius * Math.sin(angle));
            shader.append("      vec2 d").append(index).append(" = major * ").append(x)
                    .append(" + minor * ").append(y).append(";\n");
            shader.append("      rgb += sampleCamera(vPreview + d").append(index)
                    .append(") + sampleCamera(vPreview - d").append(index).append(");\n");
        }

        return shader.append("      rgb *= 0.04;\n")
                .append("    }\n")
                .append("  }\n")
                .append("  if (uHaze > 0.0) {\n")
                .append("    float gray = dot(rgb, vec3(0.2126, 0.7152, 0.0722));\n")
                .append("    rgb = mix(rgb, vec3(gray), uHaze * 0.40);\n")
                .append("    rgb = mix(rgb, vec3(0.89, 0.88, 0.82), uHaze * 0.59);\n")
                .append("  }\n")
                .append("  gl_FragColor = vec4(rgb, 1.0);\n")
                .append("}\n").toString();
    }

    private static int createProgram() {
        int vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER);
        int fragment = compile(GLES20.GL_FRAGMENT_SHADER, fragmentShader());
        int program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        GLES20.glDeleteShader(vertex);
        GLES20.glDeleteShader(fragment);
        int[] result = new int[1];
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, result, 0);
        if (result[0] == 0) {
            throw new IllegalStateException(GLES20.glGetProgramInfoLog(program));
        }
        return program;
    }

    private static int compile(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);
        int[] result = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, result, 0);
        if (result[0] == 0) {
            throw new IllegalStateException(GLES20.glGetShaderInfoLog(shader));
        }
        return shader;
    }
}
