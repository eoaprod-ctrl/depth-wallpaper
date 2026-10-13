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

    // Two layers per photo. aux: R = subject depth, G = subject mask, B = background depth.
    // Depth 0.5 is the focus plane: nearer content moves against the tilt, farther moves with it.
    private static final String FS =
            "precision highp float;\n" +
            "varying vec2 vUv;\n" +
            "uniform sampler2D uImgA; uniform sampler2D uBgA; uniform sampler2D uAuxA;\n" +
            "uniform sampler2D uImgB; uniform sampler2D uBgB; uniform sampler2D uAuxB;\n" +
            "uniform vec2 uScaleA; uniform vec2 uScaleB;\n" +
            "uniform vec2 uTilt; uniform vec2 uScroll; uniform vec2 uShadowOff;\n" +
            "uniform float uZoom; uniform float uMix; uniform float uBlur; uniform float uShadow;\n" +
            "vec3 look(sampler2D img, sampler2D bg, sampler2D aux, vec2 sc){\n" +
            "  vec2 c = (vUv - 0.5) * sc * uZoom + 0.5 + uScroll;\n" +
            // background layer (subject removed), smooth depth -> one refinement is enough
            "  float hb = texture2D(aux, c).b;\n" +
            "  vec2 bc = c - uTilt * (hb - 0.5);\n" +
            "  hb = 0.5 * (hb + texture2D(aux, bc).b);\n" +
            "  bc = c - uTilt * (hb - 0.5);\n" +
            "  vec3 col = texture2D(bg, bc, uBlur * (1.0 - hb)).rgb;\n" +
            // subject layer: depth was dilated at import, so edges move as one piece
            "  float hf = texture2D(aux, c).r;\n" +
            "  vec2 fc = c - uTilt * (hf - 0.5);\n" +
            "  hf = texture2D(aux, fc).r;\n" +
            "  fc = c - uTilt * (hf - 0.5);\n" +
            "  float a = smoothstep(0.2, 0.8, texture2D(aux, fc).g);\n" +
            "  float sh = texture2D(aux, fc + uShadowOff, 3.0).g;\n" +
            "  col *= 1.0 - uShadow * sh * (1.0 - a);\n" +
            "  return mix(col, texture2D(img, fc).rgb, a);\n" +
            "}\n" +
            "void main(){\n" +
            "  vec3 col = look(uImgA, uBgA, uAuxA, uScaleA);\n" +
            "  if (uMix > 0.001) col = mix(col, look(uImgB, uBgB, uAuxB, uScaleB), uMix);\n" +
            "  gl_FragColor = vec4(col, 1.0);\n" +
            "}\n";

    private static final float FADE_MS = 700f;

    private final Host host;

    // inputs written from the main thread
    private volatile float targetX, targetY;     // -1..1 tilt
    private volatile float targetScroll;          // -0.5..0.5 launcher page position
    private volatile float strength = 0.5f;      // 0..1
    private volatile boolean blur = true, shadow = true;
    private volatile boolean scrollEnabled = true;

    // smoothed state (GL thread only)
    private float curX, curY, curScroll;

    private int prog, aPos;
    private int uScaleA, uScaleB, uTilt, uScroll, uShadowOff, uZoom, uMix, uBlur, uShadow;
    private final int[] uSamplers = new int[6];
    private FloatBuffer quad;
    private int viewW = 1, viewH = 1;

    // texture slots: [0..2] = image, background, aux of A; [3..5] = same for B
    private final int[] tex = new int[6];
    private boolean hasA, hasB;
    private float aspectA = 1f, aspectB = 1f;
    private long fadeStart = -1;

    DepthRenderer(Host host) { this.host = host; }

    // ---------- inputs ----------

    void setTilt(float x, float y) { targetX = x; targetY = y; }
    void setScroll(float s) { targetScroll = s; }
    void setParams(float strength01, boolean blurOn, boolean shadowOn, boolean scroll) {
        strength = strength01; blur = blurOn; shadow = shadowOn; scrollEnabled = scroll;
    }

    /** Must be called on the GL thread (via queueEvent). Takes ownership of the bitmaps. */
    void setImage(Bitmap img, Bitmap bg, Bitmap aux, boolean fade) {
        if (img == null || bg == null || aux == null) return;
        if (tex[0] == 0) { img.recycle(); bg.recycle(); aux.recycle(); return; }
        boolean doFade = fade && hasA;
        int slot = doFade ? 3 : 0;
        upload(tex[slot], img);
        upload(tex[slot + 1], bg);
        upload(tex[slot + 2], aux);
        float aspect = img.getWidth() / (float) img.getHeight();
        img.recycle();
        bg.recycle();
        aux.recycle();
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
        String[] names = {"uImgA", "uBgA", "uAuxA", "uImgB", "uBgB", "uAuxB"};
        for (int i = 0; i < 6; i++) uSamplers[i] = GLES30.glGetUniformLocation(prog, names[i]);
        uScaleA = GLES30.glGetUniformLocation(prog, "uScaleA");
        uScaleB = GLES30.glGetUniformLocation(prog, "uScaleB");
        uTilt = GLES30.glGetUniformLocation(prog, "uTilt");
        uScroll = GLES30.glGetUniformLocation(prog, "uScroll");
        uShadowOff = GLES30.glGetUniformLocation(prog, "uShadowOff");
        uBlur = GLES30.glGetUniformLocation(prog, "uBlur");
        uShadow = GLES30.glGetUniformLocation(prog, "uShadow");
        uZoom = GLES30.glGetUniformLocation(prog, "uZoom");
        uMix = GLES30.glGetUniformLocation(prog, "uMix");

        float[] v = {-1, -1, 1, -1, -1, 1, 1, 1};
        quad = ByteBuffer.allocateDirect(v.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(v).position(0);

        GLES30.glGenTextures(6, tex, 0);
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

        // tilt amplitude: offset = tilt * (depth - 0.5), so the max shift is amp / 2
        float amp = 0.02f + 0.10f * strength;
        float scrollAmp = scrollEnabled ? 0.06f : 0f;
        float maxOff = amp * 0.5f + scrollAmp * 0.5f;
        float zoom = 1f / (1f + 2.1f * maxOff);

        GLES30.glUseProgram(prog);
        GLES30.glEnableVertexAttribArray(aPos);
        GLES30.glVertexAttribPointer(aPos, 2, GLES30.GL_FLOAT, false, 0, quad);

        for (int i = 0; i < 6; i++) {
            int id = (i >= 3 && !hasB) ? tex[i - 3] : tex[i];
            bind(i, id, uSamplers[i]);
        }

        float screenAspect = viewW / (float) viewH;
        setScale(uScaleA, aspectA, screenAspect);
        setScale(uScaleB, hasB ? aspectB : aspectA, screenAspect);

        float ox = -curX * amp, oy = -curY * amp * screenAspect;
        GLES30.glUniform2f(uTilt, ox, oy);
        GLES30.glUniform2f(uScroll, curScroll * scrollAmp, 0f);
        // shadow falls away from the light, a little down-right, and slides with the tilt
        GLES30.glUniform2f(uShadowOff, -0.012f + ox * 0.15f, -0.018f + oy * 0.15f);
        GLES30.glUniform1f(uShadow, shadow ? 0.32f : 0f);
        GLES30.glUniform1f(uBlur, blur ? 2.2f : 0f);
        GLES30.glUniform1f(uZoom, zoom);
        GLES30.glUniform1f(uMix, mix);

        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

        if (moving || fading) host.requestRender();
    }

    private void swapSlots() {
        for (int i = 0; i < 3; i++) { int t = tex[i]; tex[i] = tex[i + 3]; tex[i + 3] = t; }
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

    private static void upload(int id, Bitmap b) {
        final boolean mipmap = true;
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
