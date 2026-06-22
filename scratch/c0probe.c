/*
 * C0 keystone probe: can the Android-EGL compositor import a venus-style
 * swapchain buffer from ONLY what zwp_linux_dmabuf_v1 carries (a bare
 * dmabuf-fd + format + modifier + stride/offset), vs needing the full
 * AHardwareBuffer native handle?
 *
 * PATH A = full AHB handle, in-process  (EGL_ANDROID_image_native_buffer).
 *          This is what the compositor does today; the NON-shippable route
 *          (needs a custom protocol to carry the whole handle cross-process).
 * PATH B = dmabuf-fd only               (EGL_EXT_image_dma_buf_import).
 *          The standard zwp_linux_dmabuf_v1 route; if this works -> shippable,
 *          no guest-Mesa change.
 *
 * Cross-compiled for arm64, run from `adb shell` against the real Mali ICD.
 */
#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <GLES2/gl2.h>
#include <GLES2/gl2ext.h>
#include <android/hardware_buffer.h>
#include <stdio.h>
#include <string.h>

/* native_handle_t isn't in the NDK; stable ABI since Android 4.0 */
typedef struct native_handle { int version; int numFds; int numInts; int data[0]; } native_handle_t;
extern const native_handle_t *AHardwareBuffer_getNativeHandle(const AHardwareBuffer *);

/* DRM fourcc (no drm_fourcc.h in the NDK) */
#define FOURCC(a,b,c,d) ((unsigned)(a)|((unsigned)(b)<<8)|((unsigned)(c)<<16)|((unsigned)(d)<<24))
#define DRM_FORMAT_ABGR8888 FOURCC('A','B','2','4')  /* matches AHB R8G8B8A8_UNORM */

#define P(...) do { printf(__VA_ARGS__); fflush(stdout); } while (0)

int main(void) {
	EGLDisplay dpy = eglGetDisplay(EGL_DEFAULT_DISPLAY);
	EGLint maj, min;
	if (!eglInitialize(dpy, &maj, &min)) { P("eglInitialize FAILED 0x%x\n", eglGetError()); return 1; }
	P("EGL %d.%d  vendor=%s\n", maj, min, eglQueryString(dpy, EGL_VENDOR));

	const char *exts = eglQueryString(dpy, EGL_EXTENSIONS);
	P("=== EGL_EXTENSIONS ===\n%s\n=== end ===\n", exts ? exts : "(null)");
#define HAS(e) ((exts && strstr(exts, e)) ? "YES" : "no")
	P("[ext] EGL_EXT_image_dma_buf_import............ %s\n", HAS("EGL_EXT_image_dma_buf_import"));
	P("[ext] EGL_EXT_image_dma_buf_import_modifiers.. %s\n", HAS("EGL_EXT_image_dma_buf_import_modifiers"));
	P("[ext] EGL_ANDROID_image_native_buffer......... %s\n", HAS("EGL_ANDROID_image_native_buffer"));
	P("[ext] EGL_ANDROID_get_native_client_buffer.... %s\n", HAS("EGL_ANDROID_get_native_client_buffer"));
	P("[ext] EGL_KHR_image_base...................... %s\n", HAS("EGL_KHR_image_base"));

	EGLint cfgattr[] = { EGL_SURFACE_TYPE, EGL_PBUFFER_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT, EGL_NONE };
	EGLConfig cfg; EGLint n = 0;
	eglChooseConfig(dpy, cfgattr, &cfg, 1, &n);
	eglBindAPI(EGL_OPENGL_ES_API);
	EGLint ctxattr[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
	EGLContext ctx = eglCreateContext(dpy, cfg, EGL_NO_CONTEXT, ctxattr);
	EGLint pbattr[] = { EGL_WIDTH, 16, EGL_HEIGHT, 16, EGL_NONE };
	EGLSurface surf = eglCreatePbufferSurface(dpy, cfg, pbattr);
	if (!eglMakeCurrent(dpy, surf, surf, ctx)) { P("makeCurrent FAILED 0x%x (ctx=%p surf=%p)\n", eglGetError(), ctx, surf); return 2; }
	P("GL_RENDERER=%s\n", (const char *)glGetString(GL_RENDERER));
	const char *glx = (const char *)glGetString(GL_EXTENSIONS);
	P("[gl ] GL_OES_EGL_image_external.............. %s\n", (glx && strstr(glx, "GL_OES_EGL_image_external")) ? "YES" : "no");

	/* allocate an AHB shaped like a swapchain image */
	AHardwareBuffer_Desc d; memset(&d, 0, sizeof d);
	d.width = 256; d.height = 256; d.layers = 1;
	d.format = AHARDWAREBUFFER_FORMAT_R8G8B8A8_UNORM;
	d.usage = AHARDWAREBUFFER_USAGE_GPU_SAMPLED_IMAGE | AHARDWAREBUFFER_USAGE_GPU_FRAMEBUFFER |
	          AHARDWAREBUFFER_USAGE_CPU_READ_OFTEN | AHARDWAREBUFFER_USAGE_CPU_WRITE_OFTEN;
	AHardwareBuffer *ahb = NULL;
	int r = AHardwareBuffer_allocate(&d, &ahb);
	P("AHardwareBuffer_allocate: %d\n", r);
	if (r != 0) return 3;

	const native_handle_t *h = AHardwareBuffer_getNativeHandle(ahb);
	AHardwareBuffer_Desc dd; AHardwareBuffer_describe(ahb, &dd);
	P("native_handle numFds=%d numInts=%d fd0=%d  describe.stride=%u px (=%u bytes)\n",
	  h ? h->numFds : -1, h ? h->numInts : -1, (h && h->numFds > 0) ? h->data[0] : -1, dd.stride, dd.stride * 4);

	PFNEGLGETNATIVECLIENTBUFFERANDROIDPROC getNCB = (void *)eglGetProcAddress("eglGetNativeClientBufferANDROID");
	PFNEGLCREATEIMAGEKHRPROC createImg = (void *)eglGetProcAddress("eglCreateImageKHR");
	PFNEGLDESTROYIMAGEKHRPROC destroyImg = (void *)eglGetProcAddress("eglDestroyImageKHR");
	PFNGLEGLIMAGETARGETTEXTURE2DOESPROC imgTex = (void *)eglGetProcAddress("glEGLImageTargetTexture2DOES");

	/* ---- PATH A: full AHB handle, in-process ---- */
	if (getNCB && createImg) {
		EGLClientBuffer cb = getNCB(ahb);
		EGLint ia[] = { EGL_IMAGE_PRESERVED_KHR, EGL_TRUE, EGL_NONE };
		EGLImageKHR img = createImg(dpy, EGL_NO_CONTEXT, EGL_NATIVE_BUFFER_ANDROID, cb, ia);
		P("PATH A native_buffer import: img=%p err=0x%x\n", img, eglGetError());
		if (img != EGL_NO_IMAGE_KHR && imgTex) {
			GLuint tex; glGenTextures(1, &tex); glBindTexture(GL_TEXTURE_2D, tex);
			imgTex(GL_TEXTURE_2D, (GLeglImageOES)img);
			P("PATH A bind-as-texture glerr=0x%x -> %s\n", glGetError(),
			  glGetError() == GL_NO_ERROR ? "OK (in-process AHB import works)" : "FAIL");
			destroyImg(dpy, img);
		}
	} else {
		P("PATH A: missing procs getNCB=%p createImg=%p\n", (void *)getNCB, (void *)createImg);
	}

	/* ---- PATH B: dmabuf-fd ONLY (what zwp_linux_dmabuf_v1 carries cross-process) ---- */
	if (exts && strstr(exts, "EGL_EXT_image_dma_buf_import") && createImg) {
		int fd = h->data[0];
		EGLint ba[] = {
			EGL_WIDTH, 256, EGL_HEIGHT, 256,
			EGL_LINUX_DRM_FOURCC_EXT, (EGLint)DRM_FORMAT_ABGR8888,
			EGL_DMA_BUF_PLANE0_FD_EXT, fd,
			EGL_DMA_BUF_PLANE0_OFFSET_EXT, 0,
			EGL_DMA_BUF_PLANE0_PITCH_EXT, (EGLint)(dd.stride * 4),
			EGL_NONE
		};
		EGLImageKHR img2 = createImg(dpy, EGL_NO_CONTEXT, EGL_LINUX_DMA_BUF_EXT, (EGLClientBuffer)NULL, ba);
		P("PATH B dmabuf-fd import: img=%p err=0x%x -> %s\n", img2, eglGetError(),
		  img2 != EGL_NO_IMAGE_KHR ? "OK (SHIPPABLE: standard zwp_linux_dmabuf works)" : "FAIL");
		if (img2 != EGL_NO_IMAGE_KHR && imgTex) {
			GLuint tex; glGenTextures(1, &tex); glBindTexture(GL_TEXTURE_2D, tex);
			imgTex(GL_TEXTURE_2D, (GLeglImageOES)img2);
			P("PATH B bind-as-texture glerr=0x%x\n", glGetError());
			destroyImg(dpy, img2);
		}
	} else {
		P("PATH B: EGL_EXT_image_dma_buf_import ABSENT -> standard zwp_linux_dmabuf import IMPOSSIBLE on this EGL\n");
	}

	AHardwareBuffer_release(ahb);
	P("DONE\n");
	return 0;
}
