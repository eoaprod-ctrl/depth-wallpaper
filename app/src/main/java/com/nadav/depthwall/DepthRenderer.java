package com.nadav.depthwall;

import android.graphics.Bitmap;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.os.SystemClock;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * Draws one full-screen quad. All parallax is done in the fragment shader with a per-image
 * depth map. Renders only on demand: the loop stops as soon as the motion has settled.
 */
final class DepthRenderer implements GLSurfaceView.Renderer {

    interface Host {
        void requestRender();
        /** Called on the GL thread after the surface (and thus all textures) was (re)created. */
        void onSurfaceReady();
    }

    private static final String VS =
            "attribute vec2 aPos;\n" +
            "varying vec2 vUv;\n" +
            "void main(){\n" +
            "  vUv = vec2(aPos.x*0.5+0.5, 0.5-aPos.y*0.5);\n" +
            "  gl_Position = vec4(aPos, 0.0, 1.0);\n" +
            "}\n";

    private static final String FS =
            "precision highp float;\n" +
            "varying vec2 vUv;\n" +
            "uniform sampler2D uTexA; uniform sampler2D uDepA;\n" +
            "uniform sampler2D uTexB; uniform sampler2D uDepB;\n" +
            "uniform vec2 uScaleA; uniform vec2 uScaleB;\n" +
            "uniform vec2 uTilt; uniform vec2 uScroll;\n" +
            "uniform float uDepthAmt; uniform float uZoom; uniform float uMix;\n" +
            "float wgt(float d){ return mix(1.0, (d - 0.3) * 1.5, uDepthAmt); }\n" +
            "vec3 look(sampler2D t, sampler2D dm, vec2 sc){\n" +
            "  vec2 c = (vUv - 0.5) * sc * uZoom + 0.5 + uScroll;\n" +
            "  float d = texture2D(dm, c).r;\n" +
            "  vec2 off = uTilt * wgt(d);\n" +
            "  d = 0.5 * (d + texture2D(dm, c + off).r);\n" +   // one refinement step: less smearing at depth edges
            "  off = uTilt * wgt(d);\n" +
            "  return texture2D(t, c + off).rgb;\n" +
            "}\n" +
            "void main(){\n" +
            "  vec3 col = look(uTexA, uDepA, uScaleA);\n" +
            "  if (uMix > 0.001) col = mix(col, look(uTexB, uDepB, uScaleB), uMix);\n" +
            "  gl_FragColor = vec4(col, 1.0);\n" +
            "}\n";

    private static final float FADE_MS = 700f;

    private final Host host;

    // inputs written from the main thread
    private volatile float targetX, targetY;     // -1..1 tilt
    private volatile float targetScroll;          // -0.5..0.5 launcher page position
    private volatile float strength = 0.5f;      // 0..1
    private volatile float depthAmt = 1f;
    private volatile boolean scrollEnabled = true;

    // smoothed state (GL thread only)
    private float curX, curY, curScroll;

    private int prog, aPos;
    private int uTexA, uDepA, uTexB, uDepB, uScaleA, uScaleB, uTilt, uScroll, uDepthAmt, uZoom, uMix;
    private FloatBuffer quad;
    private int viewW = 1, viewH = 1;

    // texture slots: [0]=image A, [1]=depth A, [2]=image B, [3]=depth B
    private final int[] tex = new int[4];
    private boolean hasA, hasB;
    private float aspectA = 1f, aspectB = 1f;
    private long fadeStart = -1;

    DepthRenderer(Host host) { this.host = host; }

    // ---------- inputs ----------

    void setTilt(float x, float y) { targetX = x; targetY = y; }
    void setScroll(float s) { targetScroll = s; }
    void setParams(float strength01, boolean depth, boolean scroll) {
        strength = strength01; depthAmt = depth ? 1f : 0f; scrollEnabled = scroll;
    }

    /** Must be called on the GL thread (via queueEvent). Takes ownership of the bitmaps. */
    void setImage(Bitmap img, Bitmap depth, boolean fade) {
        if (img == null || depth == null) return;
        if (tex[0] == 0) return; // surface not ready, will be reloaded in onSurfaceReady
        boolean doFade = fade && hasA;
        int slot = doFade ? 2 : 0;
        upload(tex[slot], img, true);
        upload(tex[slot + 1], depth, false);
        float aspect = img.getWidth() / (float) img.getHeight();
        img.recycle();
        depth.recycle();
        if (doFade) {
            hasB = true; aspectB = aspect;
            fadeStart = SystemClock.uptimeMillis();
        } else {
            hasA = true; aspectA = aspect;
        }
    }

    // ---------- GL ----------

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        prog = link(VS, FS);
        aPos = GLES30.glGetAttribLocation(prog, "aPos");
        uTexA = GLES30.glGetUniformLocation(prog, "uTexA");
        uDepA = GLES30.glGetUniformLocation(prog, "uDepA");
        uTexB = GLES30.glGetUniformLocation(prog, "uTexB");
        uDepB = GLES30.glGetUniformLocation(prog, "uDepB");
        uScaleA = GLES30.glGetUniformLocation(prog, "uScaleA");
        uScaleB = GLES30.glGetUniformLocation(prog, "uScaleB");
        uTilt = GLES30.glGetUniformLocation(prog, "uTilt");
        uScroll = GLES30.glGetUniformLocation(prog, "uScroll");
        uDepthAmt = GLES30.glGetUniformLocation(prog, "uDepthAmt");
        uZoom = GLES30.glGetUniformLocation(prog, "uZoom");
        uMix = GLES30.glGetUniformLocation(prog, "uMix");

        float[] v = {-1, -1, 1, -1, -1, 1, 1, 1};
        quad = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(v).position(0);

        GLES30.glGenTextures(4, tex, 0);
        hasA = hasB = false;
        fadeStart = -1;
        GLES30.glDisable(GLES30.GL_DEPTH_TEST);
        GLES30.glDisable(GLES30.GL_BLEND);
        host.onSurfaceReady();
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int w, int h) {
        viewW = Math.max(1, w); viewH = Math.max(1, h);
        GLES30.glViewport(0, 0, w, h);
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        // critically-damped-ish smoothing toward the sensor target
        float k = 0.16f;
        float tx = targetX, ty = targetY, ts = scrollEnabled ? targetScroll : 0f;
        curX += (tx - curX) * k;
        curY += (ty - curY) * k;
        curScroll += (ts - curScroll) * 0.25f;
        boolean moving = Math.abs(tx - curX) > 0.002f || Math.abs(ty - curY) > 0.002f
                || Math.abs(ts - curScroll) > 0.0008f;

        float mix = 0f;
        boolean fading = false;
        if (fadeStart >= 0 && hasB) {
            float t = (SystemClock.uptimeMillis() - fadeStart) / FADE_MS;
            if (t >= 1f) {
                swapSlots();
            } else {
                mix = t * t * (3f - 2f * t);
                fading = true;
            }
        }

        if (!hasA) {
            GLES30.glClearColor(0.055f, 0.067f, 0.086f, 1f);
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT);
            return;
        }

        // amplitude in UV units; overscan (zoom) is derived from it so edges never show
        float amp = 0.012f + 0.048f * strength;
        float scrollAmp = scrollEnabled ? 0.06f : 0f;
        float maxOff = amp * 1.06f + scrollAmp * 0.5f;
        float zoom = 1f / (1f + 2.05f * maxOff);

        GLES30.glUseProgram(prog);
        GLES30.glEnableVertexAttribArray(aPos);
        GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 0, quad);

        bind(0, tex[0], uTexA);
        bind(1, tex[1], uDepA);
        bind(2, hasB ? tex[2] : tex[0], uTexB);
        bind(3, hasB ? tex[3] : tex[1], uDepB);

        float screenAspect = viewW / (float) viewH;
        setScale(uScaleA, aspectA, screenAspect);
        setScale(uScaleB, hasB ? aspectB : aspectA, screenAspect);

        // tilt right -> near content slides right (looking "into" a window)
        GLES30.glUniform2f(uTilt, -curX * amp, -curY * amp * screenAspect);
        GLES30.glUniform2f(uScroll, curScroll * scrollAmp, 0f);
        GLES30.glUniform1f(uDepthAmt, depthAmt);
        GLES30.glUniform1f(uZoom, zoom);
        GLES30.glUniform1f(uMix, mix);

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

        if (moving || fading) host.requestRender();
    }

    private void swapSlots() {
        int t0 = tex[0], t1 = tex[1];
        tex[0] = tex[2]; tex[1] = tex[3];
        tex[2] = t0; tex[3] = t1;
        aspectA = aspectB;
        hasB = false;
        fadeStart = -1;
    }

    /** Center-crop: scale UVs so the image fills the screen without stretching. */
    private static void setScale(int loc, float imgAspect, float screenAspect) {
        if (imgAspect > screenAspect) GLES30.glUniform2f(loc, screenAspect / imgAspect, 1f);
        else GLES30.glUniform2f(loc, 1f, imgAspect / screenAspect);
    }

    private static void bind(int unit, int id, int loc) {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0 + unit);
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id);
        GLES30.glUniform1i(loc, unit);
    }

    private static void upload(int id, Bitmap b, boolean mipmap) {
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, id);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER,
                mipmap ? GLES30.GL_LINEAR_MIPMAP_LINEAR : GLES30.GL_LINEAR);
        GLUtils.texImage2D(GLES30.GL_TEXTURE_2D, 0, b, 0);
        if (mipmap) GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D);
    }

    private static int link(String vs, String fs) {
        int v = compile(GLES30.GL_VERTEX_SHADER, vs);
        int f = compile(GLES30.GL_FRAGMENT_SHADER, fs);
        int p = GLES30.glCreateProgram();
        GLES30.glAttachShader(p, v);
        GLES30.glAttachShader(p, f);
        GLES30.glLinkProgram(p);
        int[] ok = new int[1];
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("link: " + GLES30.glGetProgramInfoLog(p));
        return p;
    }

    private static int compile(int type, String src) {
        int s = GLES30.glCreateShader(type);
        GLES30.glShaderSource(s, src);
        GLES30.glCompileShader(s);
        int[] ok = new int[1];
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new RuntimeException("shader: " + GLES30.glGetShaderInfoLog(s));
        return s;
    }
}
