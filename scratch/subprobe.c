/*
 * Minimal Wayland subsurface + EGL repro for the "embedded GL canvas blank"
 * gap (PrusaSlicer wxGLCanvas). A parent xdg_toplevel maps with a solid SHM
 * background (so the parent is definitely visible), and a wl_subsurface inside
 * it carries an EGL/zink window that GL-clears left=red / right=blue.
 *
 * Capture test: if the subsurface region shows red|blue, GL subsurfaces present
 * fine (so PrusaSlicer's blank canvas is something else). If it stays grey
 * (parent showing through), GL subsurfaces don't present over this stack — the
 * real gap, independent of the toplevel path that already works (weston-simple-egl).
 *
 * Built + run in the guest (glibc) against zink+venus. cc with the generated
 * xdg-shell client glue.
 */
#define _GNU_SOURCE
#include <wayland-client.h>
#include <wayland-egl.h>
#include <EGL/egl.h>
#include <GLES2/gl2.h>
#include "xdg-shell-client-protocol.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <math.h>
#include <sys/mman.h>

static struct wl_compositor *compositor;
static struct wl_subcompositor *subcompositor;
static struct wl_shm *shm;
static struct xdg_wm_base *wm_base;
static int configured;

#define PW 400
#define PH 300
#define CW 200
#define CH 150

static void wm_ping(void *d, struct xdg_wm_base *b, uint32_t s) { xdg_wm_base_pong(b, s); }
static const struct xdg_wm_base_listener wm_listener = { wm_ping };

static void reg_global(void *d, struct wl_registry *r, uint32_t name,
                       const char *iface, uint32_t ver) {
	if (!strcmp(iface, "wl_compositor"))
		compositor = wl_registry_bind(r, name, &wl_compositor_interface, 4);
	else if (!strcmp(iface, "wl_subcompositor"))
		subcompositor = wl_registry_bind(r, name, &wl_subcompositor_interface, 1);
	else if (!strcmp(iface, "wl_shm"))
		shm = wl_registry_bind(r, name, &wl_shm_interface, 1);
	else if (!strcmp(iface, "xdg_wm_base")) {
		wm_base = wl_registry_bind(r, name, &xdg_wm_base_interface, 1);
		xdg_wm_base_add_listener(wm_base, &wm_listener, 0);
	}
}
static void reg_rem(void *d, struct wl_registry *r, uint32_t n) {}
static const struct wl_registry_listener reg_listener = { reg_global, reg_rem };

static void xdg_surf_configure(void *d, struct xdg_surface *s, uint32_t serial) {
	xdg_surface_ack_configure(s, serial);
	configured = 1;
}
static const struct xdg_surface_listener xdg_surf_listener = { xdg_surf_configure };
static void xdg_top_configure(void *d, struct xdg_toplevel *t, int32_t w, int32_t h,
                              struct wl_array *st) {}
static void xdg_top_close(void *d, struct xdg_toplevel *t) { exit(0); }
static const struct xdg_toplevel_listener xdg_top_listener = { xdg_top_configure,
                                                               xdg_top_close };

static struct wl_buffer *make_shm(uint32_t argb) {
	int stride = PW * 4, size = stride * PH;
	int fd = memfd_create("p", 0);
	if (ftruncate(fd, size) < 0) { perror("ftruncate"); exit(4); }
	uint32_t *data = mmap(0, size, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0);
	for (int i = 0; i < PW * PH; i++) data[i] = argb;
	struct wl_shm_pool *pool = wl_shm_create_pool(shm, fd, size);
	struct wl_buffer *buf =
	    wl_shm_pool_create_buffer(pool, 0, PW, PH, stride, WL_SHM_FORMAT_XRGB8888);
	wl_shm_pool_destroy(pool);
	close(fd);
	return buf;
}

int main(void) {
	struct wl_display *dpy = wl_display_connect(0);
	if (!dpy) { fprintf(stderr, "no wayland\n"); return 1; }
	struct wl_registry *reg = wl_display_get_registry(dpy);
	wl_registry_add_listener(reg, &reg_listener, 0);
	wl_display_roundtrip(dpy);
	if (!compositor || !subcompositor || !shm || !wm_base) {
		fprintf(stderr, "missing globals c=%p sc=%p shm=%p wm=%p\n",
		        (void *)compositor, (void *)subcompositor, (void *)shm, (void *)wm_base);
		return 2;
	}

	/* parent toplevel */
	struct wl_surface *parent = wl_compositor_create_surface(compositor);
	struct xdg_surface *xs = xdg_wm_base_get_xdg_surface(wm_base, parent);
	xdg_surface_add_listener(xs, &xdg_surf_listener, 0);
	struct xdg_toplevel *top = xdg_surface_get_toplevel(xs);
	xdg_toplevel_add_listener(top, &xdg_top_listener, 0);
	xdg_toplevel_set_title(top, "subprobe");
	wl_surface_commit(parent);
	while (!configured) wl_display_dispatch(dpy);
	struct wl_buffer *bg = make_shm(0xFF303030); /* dark grey */
	wl_surface_attach(parent, bg, 0, 0);
	wl_surface_damage(parent, 0, 0, PW, PH);
	wl_surface_commit(parent);
	wl_display_roundtrip(dpy);
	fprintf(stderr, "parent mapped (%dx%d grey)\n", PW, PH);

	/* child subsurface with an EGL window */
	struct wl_surface *child = wl_compositor_create_surface(compositor);
	struct wl_subsurface *sub =
	    wl_subcompositor_get_subsurface(subcompositor, child, parent);
	wl_subsurface_set_position(sub, 100, 75);
	wl_subsurface_set_desync(sub);
	struct wl_egl_window *ew = wl_egl_window_create(child, CW, CH);

	EGLDisplay ed = eglGetDisplay((EGLNativeDisplayType)dpy);
	EGLint maj, min;
	if (!eglInitialize(ed, &maj, &min)) { fprintf(stderr, "eglInit FAIL 0x%x\n", eglGetError()); return 3; }
	eglBindAPI(EGL_OPENGL_ES_API);
	EGLint cfgattr[] = { EGL_SURFACE_TYPE, EGL_WINDOW_BIT, EGL_RENDERABLE_TYPE,
	                     EGL_OPENGL_ES2_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8,
	                     EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 8, EGL_NONE };
	EGLConfig cfg;
	EGLint n = 0;
	eglChooseConfig(ed, cfgattr, &cfg, 1, &n);
	if (!n) { fprintf(stderr, "no egl config\n"); return 3; }
	EGLint ctxattr[] = { EGL_CONTEXT_CLIENT_VERSION, 2, EGL_NONE };
	EGLContext ctx = eglCreateContext(ed, cfg, EGL_NO_CONTEXT, ctxattr);
	EGLSurface es = eglCreateWindowSurface(ed, cfg, (EGLNativeWindowType)ew, 0);
	if (es == EGL_NO_SURFACE) { fprintf(stderr, "no egl surface 0x%x\n", eglGetError()); return 3; }
	eglMakeCurrent(ed, es, es, ctx);
	fprintf(stderr, "GL_RENDERER=%s  egl=%d.%d\n", (const char *)glGetString(GL_RENDERER), maj, min);

	/* Animate continuously with a non-black fill + a moving band, pushing fresh
	 * frames fast so the sw-WSI readback path churns — if it reads stale cache
	 * lines, grim (compositor output, not VNC) will catch the black runs. */
	for (int f = 0; f < 1200; f++) {
		float ph = f * 0.04f;
		glViewport(0, 0, CW, CH);
		glClearColor(0.25f + 0.2f * sinf(ph), 0.55f, 0.75f, 1.0f);
		glClear(GL_COLOR_BUFFER_BIT);
		int split = (int)(CW * (0.5f + 0.4f * sinf(ph)));
		glEnable(GL_SCISSOR_TEST);
		glScissor(0, 0, split, CH);
		glClearColor(0.95f, 0.4f, 0.1f, 1.0f); /* moving orange band */
		glClear(GL_COLOR_BUFFER_BIT);
		glDisable(GL_SCISSOR_TEST);
		eglSwapBuffers(ed, es);  /* commits the child subsurface */
		wl_surface_commit(parent);
		wl_display_dispatch_pending(dpy);
		wl_display_flush(dpy);
		usleep(8000); /* ~120 fps push */
	}
	fprintf(stderr, "done rendering subsurface\n");
	wl_display_roundtrip(dpy);
	sleep(3);
	return 0;
}
