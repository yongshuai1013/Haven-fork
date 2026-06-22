/*
 * C1 keystone probe: in-process 2D-AHB GPU loopback.
 *
 * c0probe proved a 2D R8G8B8A8 AHB can be IMPORTED + bound as a texture on
 * this Mali (EGL_ANDROID_image_native_buffer). That alone doesn't prove the
 * in-process bridge works -- it proves nothing flows. C1 proves the data path:
 *
 *   PRODUCER ctx:  AHB -> EGLImage -> texture -> FBO color0 -> GPU render
 *                  (clear whole = blue, scissor left half = red)
 *   CONSUMER ctx:  (independent, NON-shared) same AHB -> a SECOND EGLImage ->
 *                  texture -> SAMPLE in a fragment shader into a normal RGBA
 *                  FBO -> glReadPixels -> verify the producer's two regions.
 *
 * If this passes, the compositor (a separate GL context in Haven's process)
 * can sample a venus swapchain image IF venus exports it as a 2D AHB. That is
 * the gate that justifies the vkr_device_memory.c BLOB->2D-image patch.
 *
 * Cross-compiled arm64, run from `adb shell` against the real Mali ICD.
 */
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <android/hardware_buffer.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define P(...) do { printf(__VA_ARGS__); fflush(stdout); } while (0)

static PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC getNCB;
static PFNEGLCREATEIMAGEKHRPROC createImg;
static PFNEGLDESTROYIMAGEKHRPROC destroyImg;
static PFNGLEGLIMAGETARGETTEXTURE2DOESPROC imgTex;

/* import an AHB as a fresh GL_TEXTURE_2D in the current context; returns tex or 0 */
static GLuint import_ahb_tex(EGLDisplay dpy, AHardwareBuffer *ahb, EGLImageKHR *out_img) {
	EGLClientBuffer cb = getNCB(ahb);
	EGLint ia[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
	EGLImageKHR img = createImg(dpy, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, cb, ia);
	if (img == EGL_NO_IMAGE_KHR) { P("  import: createImage FAILED 0x%x\n", eglGetError()); return 0; }
	GLuint tex; glGenTextures(1, &tex); glBindTexture(GL_TEXTURE_2D, tex);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_S, GL_CLAMP_TO_EDGE);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_WRAP_T, GL_CLAMP_TO_EDGE);
	imgTex(GL_TEXTURE_2D, (GLeglImageOES)img);
	GLenum e = glGetError();
	if (e != GL_NO_ERROR) { P("  import: imgTex glerr=0x%x\n", e); destroyImg(dpy, img); return 0; }
	*out_img = img;
	return tex;
}

static GLuint compile(GLenum type, const char *src) {
	GLuint s = glCreateShader(type); glShaderSource(s, 1, &src, NULL); glCompileShader(s);
	GLint ok = 0; glGetShaderiv(s, GL_COMPILE_STATUS, &ok);
	if (!ok) { char log[512]; glGetShaderInfoLog(s, sizeof log, NULL, log); P("  shader FAIL: %s\n", log); }
	return s;
}

#define W 256
#define H 256

int main(void) {
	EGLDisplay dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
	EGLint maj, mn;
	if (!eglInitialize(dpy, &maj, &mn)) { P("eglInitialize FAILED 0x%x\n", eglGetError()); return 1; }
	const char *exts = eglQueryString(dpy, EGL_EXTENSIONS);
	P("EGL %d.%d vendor=%s\n", maj, mn, eglQueryString(dpy, EGL_VENDOR));
	if (!exts || !strstr(exts, "EGL_ANDROID_image_native_buffer")) {
		P("EGL_ANDROID_image_native_buffer ABSENT -> in-process bridge impossible here\n"); return 1;
	}
	getNCB    = (void *)eglGetProcAddress("eglGetNativeClientBufferANDROID");
	createImg = (void *)eglGetProcAddress("eglCreateImageKHR");
	destroyImg= (void *)eglGetProcAddress("eglDestroyImageKHR");
	imgTex    = (void *)eglGetProcAddress("glEGLImageTargetTexture2DOES");
	if (!getNCB || !createImg || !destroyImg || !imgTex) { P("missing EGL procs\n"); return 1; }

	EGLint cfgattr[] = { EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
	                     EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE };
	EGLConfig cfg; EGLint n = 0;
	eglChooseConfig(dpy, cfgattr, &cfg, 1, &n);
	eglBindAPI(EGL_OPENGL_ES_API);
	EGLint ctxattr[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
	EGLint pbattr[]  = { EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE };

	/* two INDEPENDENT contexts (no sharelist) -- like compositor vs client */
	EGLContext ctxP = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, ctxattr);
	EGLContext ctxC = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, ctxattr);
	EGLSurface pbP  = eglCreatePbufferSurface(dpy, cfg, pbattr);
	EGLSurface pbC  = eglCreatePbufferSurface(dpy, cfg, pbattr);
	if (ctxP == EGL_NO_CONTEXT || ctxC == EGL_NO_CONTEXT) { P("ctx create FAILED 0x%x\n", eglGetError()); return 1; }

	/* the swapchain-shaped 2D AHB */
	AHardwareBuffer_Desc d; memset(&d, 0, sizeof d);
	d.width = W; d.height = H; d.layers = 1;
	d.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
	d.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER;
	AHardwareBuffer *ahb = NULL;
	if (AHardwareBuffer_allocate(&d, &ahb) != 0) { P("AHB allocate FAILED\n"); return 1; }
	P("AHB %dx%d R8G8B8A8 GPU_SAMPLED|GPU_FRAMEBUFFER allocated\n", W, H);

	/* ---- PRODUCER: GPU-render two regions INTO the AHB ---- */
	if (!eglMakeCurrent(dpy, pbP, pbP, ctxP)) { P("makeCurrent P FAILED 0x%x\n", eglGetError()); return 1; }
	EGLImageKHR imgP; GLuint texP = import_ahb_tex(dpy, ahb, &imgP);
	if (!texP) { P("PRODUCER import FAILED\n"); return 2; }
	GLuint fboP; glGenFramebuffers(1, &fboP); glBindFramebuffer(GL_FRAMEBUFFER, fboP);
	glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texP, 0);
	GLenum fst = glCheckFramebufferStatus(GL_FRAMEBUFFER);
	if (fst != GL_FRAMEBUFFER_COMPLETE) { P("PRODUCER FBO incomplete 0x%x (AHB not renderable as 2D color)\n", fst); return 2; }
	P("PRODUCER FBO complete -> AHB is GPU-renderable as 2D color\n");
	glViewport(0, 0, W, H);
	glClearColor(0.2f, 0.4f, 0.6f, 1.0f); glClear(GL_COLOR_BUFFER_BIT);          /* whole = blue */
	glEnable(GL_SCISSOR_TEST); glScissor(0, 0, W/2, H);
	glClearColor(0.8f, 0.1f, 0.1f, 1.0f); glClear(GL_COLOR_BUFFER_BIT);          /* left = red  */
	glDisable(GL_SCISSOR_TEST);
	glFinish();
	P("PRODUCER rendered (left=red right=blue), glerr=0x%x\n", glGetError());

	/* ---- CONSUMER: independent ctx, sample the SAME AHB ---- */
	if (!eglMakeCurrent(dpy, pbC, pbC, ctxC)) { P("makeCurrent C FAILED 0x%x\n", eglGetError()); return 1; }
	EGLImageKHR imgC; GLuint texC = import_ahb_tex(dpy, ahb, &imgC);
	if (!texC) { P("CONSUMER import FAILED\n"); return 3; }

	/* a normal RGBA texture as the consumer's render target */
	GLuint outTex; glGenTextures(1, &outTex); glBindTexture(GL_TEXTURE_2D, outTex);
	glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA, W, H, 0, GL_RGBA, GL_UNSIGNED_BYTE, NULL);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
	glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);
	GLuint fboC; glGenFramebuffers(1, &fboC); glBindFramebuffer(GL_FRAMEBUFFER, fboC);
	glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, outTex, 0);
	if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE) { P("CONSUMER FBO incomplete\n"); return 3; }

	const char *vs = "attribute vec2 p; varying vec2 uv;"
	                 "void main(){ uv = p*0.5+0.5; gl_Position = vec4(p,0.0,1.0); }";
	const char *fs = "precision mediump float; varying vec2 uv; uniform sampler2D t;"
	                 "void main(){ gl_FragColor = texture2D(t, uv); }";
	GLuint prog = glCreateProgram();
	glAttachShader(prog, compile(GL_VERTEX_SHADER, vs));
	glAttachShader(prog, compile(GL_FRAGMENT_SHADER, fs));
	glBindAttribLocation(prog, 0, "p");
	glLinkProgram(prog);
	GLint linked = 0; glGetProgramiv(prog, GL_LINK_STATUS, &linked);
	if (!linked) { char log[512]; glGetProgramInfoLog(prog, sizeof log, NULL, log); P("link FAIL: %s\n", log); return 3; }
	glUseProgram(prog);
	glActiveTexture(GL_TEXTURE0);
	glBindTexture(GL_TEXTURE_2D, texC);           /* SAMPLE the AHB */
	glUniform1i(glGetUniformLocation(prog, "t"), 0);

	const GLfloat quad[] = { -1,-1,  1,-1,  -1,1,  1,1 };
	glViewport(0, 0, W, H);
	glClearColor(0, 0, 0, 1); glClear(GL_COLOR_BUFFER_BIT);
	glVertexAttribPointer(0, 2, GL_FLOAT, GL_FALSE, 0, quad);
	glEnableVertexAttribArray(0);
	glDrawArrays(GL_TRIANGLE_STRIP, 0, 4);
	glFinish();
	P("CONSUMER sampled AHB into RGBA FBO, glerr=0x%x\n", glGetError());

	/* read back two probe points: one over the left half, one over the right */
	unsigned char left[4], right[4];
	glReadPixels(W/4,   H/2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, left);
	glReadPixels(3*W/4, H/2, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE, right);
	P("readback left=(%d,%d,%d) right=(%d,%d,%d)\n",
	  left[0],left[1],left[2], right[0],right[1],right[2]);

	/* flip-tolerant: one probe must be red-dominant, the other blue-dominant */
	int a_red  = left[0]  > 150 && left[1]  < 90 && left[2]  < 90;
	int a_blue = left[2]  > 110 && left[0]  < 110;
	int b_red  = right[0] > 150 && right[1] < 90 && right[2] < 90;
	int b_blue = right[2] > 110 && right[0] < 110;
	int pass = (a_red && b_blue) || (a_blue && b_red);
	P("VERDICT: %s\n", pass
	  ? "PASS -- in-process 2D-AHB GPU render->sample loopback works (venus 2D-AHB patch justified)"
	  : "FAIL -- rendered pixels did not survive cross-context AHB sample");

	destroyImg(dpy, imgP); destroyImg(dpy, imgC);
	AHardwareBuffer_release(ahb);
	return pass ? 0 : 4;
}
