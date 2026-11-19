#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <errno.h>
#include <fcntl.h>
#include <link.h>
#include <linux/fb.h>
#include <linux/input.h>
#include <poll.h>
#include <stdio.h>
#include <string.h>
#include <sys/ioctl.h>
#include <sys/mman.h>
#include <unistd.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#define LOG_TAG "QuasarNative"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

constexpr int kScreenWidth = 25;
constexpr int kScreenHeight = 16;
constexpr int kRingSize = 24;

struct FramebufferDevice {
    int fd = -1;
    uint8_t* map = nullptr;
    size_t mapSize = 0;
    fb_var_screeninfo var{};
    fb_fix_screeninfo fix{};
    int bytesPerPixel = 0;
    bool ok = false;
    bool commitWarned = false;

    bool openDevice(const char* path) {
        fd = ::open(path, O_RDWR | O_CLOEXEC);
        if (fd < 0) {
            LOGE("open %s failed: %s", path, strerror(errno));
            return false;
        }
        if (ioctl(fd, FBIOGET_FSCREENINFO, &fix) < 0) {
            LOGE("FBIOGET_FSCREENINFO %s failed: %s", path, strerror(errno));
            closeDevice();
            return false;
        }
        if (ioctl(fd, FBIOGET_VSCREENINFO, &var) < 0) {
            LOGE("FBIOGET_VSCREENINFO %s failed: %s", path, strerror(errno));
            closeDevice();
            return false;
        }
        bytesPerPixel = static_cast<int>(var.bits_per_pixel / 8);
        mapSize = fix.smem_len;
        map = static_cast<uint8_t*>(mmap(nullptr, mapSize, PROT_READ | PROT_WRITE, MAP_SHARED, fd, 0));
        if (map == MAP_FAILED) {
            map = nullptr;
            LOGE("mmap %s failed: %s", path, strerror(errno));
            closeDevice();
            return false;
        }
        int blank = FB_BLANK_UNBLANK;
        ioctl(fd, FBIOBLANK, blank);
        ok = true;
        LOGI("fb %s: %ux%u bpp=%u stride=%u smem=%zu red=%u/%u green=%u/%u blue=%u/%u",
             path, var.xres, var.yres, var.bits_per_pixel, fix.line_length, mapSize,
             var.red.offset, var.red.length, var.green.offset, var.green.length,
             var.blue.offset, var.blue.length);
        return true;
    }

    // mmap only fills the driver's shadow buffer. Both quasar_led_* drivers
    // push the buffer to the panel/LEDs from their fb_pan_display callback
    // (qls_fb_sync / fb_sync), so an explicit FBIOPAN_DISPLAY is the commit.
    bool commit() {
        if (!ok || fd < 0) return false;
        fb_var_screeninfo v = var;
        v.xoffset = 0;
        v.yoffset = 0;
        if (ioctl(fd, FBIOPAN_DISPLAY, &v) < 0) {
            if (!commitWarned) {
                LOGW("FBIOPAN_DISPLAY failed: %s", strerror(errno));
                commitWarned = true;
            }
            return false;
        }
        return true;
    }

    void closeDevice() {
        if (map != nullptr) {
            munmap(map, mapSize);
            map = nullptr;
        }
        if (fd >= 0) {
            ::close(fd);
            fd = -1;
        }
        ok = false;
    }

    void draw(const std::vector<uint32_t>& pixels, int width, int height) {
        if (!ok || map == nullptr) return;
        const int copyWidth = std::min<int>(width, static_cast<int>(var.xres));
        const int copyHeight = std::min<int>(height, static_cast<int>(var.yres));
        for (int y = 0; y < copyHeight; ++y) {
            const size_t row = static_cast<size_t>(y) * fix.line_length;
            for (int x = 0; x < copyWidth; ++x) {
                const uint32_t color = pixels[static_cast<size_t>(y) * width + x];
                const uint8_t r = static_cast<uint8_t>((color >> 16) & 0xFF);
                const uint8_t g = static_cast<uint8_t>((color >> 8) & 0xFF);
                const uint8_t b = static_cast<uint8_t>(color & 0xFF);
                const size_t offset = row + static_cast<size_t>(x) * bytesPerPixel;
                if (bytesPerPixel == 3) {
                    map[offset + (var.red.offset / 8)] = r;
                    map[offset + (var.green.offset / 8)] = g;
                    map[offset + (var.blue.offset / 8)] = b;
                } else if (bytesPerPixel == 4) {
                    uint32_t value = 0;
                    value |= static_cast<uint32_t>(r >> (8 - var.red.length)) << var.red.offset;
                    value |= static_cast<uint32_t>(g >> (8 - var.green.length)) << var.green.offset;
                    value |= static_cast<uint32_t>(b >> (8 - var.blue.length)) << var.blue.offset;
                    *reinterpret_cast<uint32_t*>(map + offset) = value;
                }
            }
        }
    }
};

struct DisplayState {
    std::mutex mutex;
    std::condition_variable cv;
    std::thread thread;
    std::atomic<bool> running{false};
    bool screenDirty = false;
    bool ringDirty = false;
    std::vector<uint32_t> screen = std::vector<uint32_t>(kScreenWidth * kScreenHeight, 0);
    std::vector<uint32_t> ring = std::vector<uint32_t>(kRingSize, 0);
    FramebufferDevice screenFb;
    FramebufferDevice ringFb;

    void renderLoop() {
        std::vector<uint32_t> screenCopy(kScreenWidth * kScreenHeight, 0);
        std::vector<uint32_t> ringCopy(kRingSize, 0);
        while (running.load(std::memory_order_acquire)) {
            bool haveScreen = false;
            bool haveRing = false;
            {
                std::unique_lock<std::mutex> lock(mutex);
                cv.wait_for(lock, std::chrono::milliseconds(500), [this] {
                    return !running.load(std::memory_order_acquire) || screenDirty || ringDirty;
                });
                if (!running.load(std::memory_order_acquire)) break;
                if (screenDirty) {
                    screenCopy = screen;
                    screenDirty = false;
                    haveScreen = true;
                }
                if (ringDirty) {
                    ringCopy = ring;
                    ringDirty = false;
                    haveRing = true;
                }
            }
            if (haveScreen) {
                screenFb.draw(screenCopy, kScreenWidth, kScreenHeight);
                screenFb.commit();
            }
            if (haveRing) {
                ringFb.draw(ringCopy, kRingSize, 1);
                ringFb.commit();
            }
        }
    }

    bool start() {
        if (running.load(std::memory_order_acquire)) return true;
        bool screenOk = screenFb.openDevice("/dev/graphics/fb4");
        bool ringOk = ringFb.openDevice("/dev/graphics/fb5");
        if (!screenOk && !ringOk) {
            LOGE("no framebuffer could be opened");
            return false;
        }
        running.store(true, std::memory_order_release);
        thread = std::thread(&DisplayState::renderLoop, this);
        return true;
    }

    void stop() {
        bool wasRunning = running.exchange(false, std::memory_order_acq_rel);
        cv.notify_all();
        if (thread.joinable()) thread.join();
        screenFb.closeDevice();
        ringFb.closeDevice();
        if (wasRunning) LOGI("display stopped");
    }
};

DisplayState g_display;

struct InputState {
    JavaVM* vm = nullptr;
    jobject callback = nullptr;
    jmethodID onInput = nullptr;
    std::thread thread;
    std::atomic<bool> running{false};

    void stop();
};

InputState g_input;

void inputLoop() {
    const char* names[3] = {"event0", "event2", "event4"};
    const char* paths[3] = {"/dev/input/event0", "/dev/input/event2", "/dev/input/event4"};
    int fds[3] = {-1, -1, -1};
    pollfd pollFds[3]{};
    for (int i = 0; i < 3; ++i) {
        fds[i] = ::open(paths[i], O_RDONLY | O_CLOEXEC | O_NONBLOCK);
        if (fds[i] < 0) {
            LOGW("open %s failed: %s", paths[i], strerror(errno));
        } else {
            LOGI("input %s opened", paths[i]);
        }
        pollFds[i].fd = fds[i];
        pollFds[i].events = POLLIN;
    }

    JNIEnv* env = nullptr;
    bool attached = false;
    if (g_input.vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        if (g_input.vm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            LOGE("could not attach input thread to JVM");
            for (int fd : fds) if (fd >= 0) ::close(fd);
            return;
        }
        attached = true;
    }

    while (g_input.running.load(std::memory_order_acquire)) {
        int result = ::poll(pollFds, 3, 500);
        if (result < 0) {
            if (errno == EINTR) continue;
            LOGE("poll failed: %s", strerror(errno));
            break;
        }
        if (result == 0) continue;
        for (int i = 0; i < 3; ++i) {
            if (pollFds[i].fd < 0 || (pollFds[i].revents & POLLIN) == 0) continue;
            input_event events[16];
            ssize_t got = ::read(pollFds[i].fd, events, sizeof(events));
            if (got <= 0) continue;
            const int count = static_cast<int>(got / sizeof(input_event));
            for (int e = 0; e < count; ++e) {
                const input_event& ev = events[e];
                if (ev.type != EV_KEY && ev.type != EV_SW && ev.type != EV_REL && ev.type != EV_ABS) continue;
                jstring device = env->NewStringUTF(names[i]);
                if (device == nullptr) continue;
                env->CallVoidMethod(g_input.callback, g_input.onInput, device,
                                    static_cast<jint>(ev.type), static_cast<jint>(ev.code),
                                    static_cast<jint>(ev.value));
                env->DeleteLocalRef(device);
                if (env->ExceptionCheck()) env->ExceptionClear();
            }
        }
    }

    for (int fd : fds) if (fd >= 0) ::close(fd);
    if (attached) g_input.vm->DetachCurrentThread();
    LOGI("input stopped");
}

void InputState::stop() {
    running.store(false, std::memory_order_release);
    if (thread.joinable()) thread.join();
    JNIEnv* env = nullptr;
    if (vm != nullptr && vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) == JNI_OK) {
        if (callback != nullptr) env->DeleteGlobalRef(callback);
    }
    callback = nullptr;
    onInput = nullptr;
    vm = nullptr;
}

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_quasar_rubcut_openio_data_display_NativeDisplay_nativeStartDisplay(JNIEnv*, jobject) {
    return g_display.start() ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT void JNICALL
Java_quasar_rubcut_openio_data_display_NativeDisplay_nativeStopDisplay(JNIEnv*, jobject) {
    g_display.stop();
}

extern "C" JNIEXPORT jboolean JNICALL
Java_quasar_rubcut_openio_data_display_NativeDisplay_nativeSetScreen(JNIEnv* env, jobject, jintArray pixels) {
    if (pixels == nullptr) return JNI_FALSE;
    const jsize length = env->GetArrayLength(pixels);
    if (length != kScreenWidth * kScreenHeight) return JNI_FALSE;
    std::vector<uint32_t> copy(static_cast<size_t>(length));
    env->GetIntArrayRegion(pixels, 0, length, reinterpret_cast<jint*>(copy.data()));
    {
        std::lock_guard<std::mutex> lock(g_display.mutex);
        g_display.screen = copy;
        g_display.screenDirty = true;
    }
    g_display.cv.notify_one();
    return JNI_TRUE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_quasar_rubcut_openio_data_display_NativeDisplay_nativeSetRing(JNIEnv* env, jobject, jintArray pixels) {
    if (pixels == nullptr) return JNI_FALSE;
    const jsize length = env->GetArrayLength(pixels);
    if (length != kRingSize) return JNI_FALSE;
    std::vector<uint32_t> copy(static_cast<size_t>(length));
    env->GetIntArrayRegion(pixels, 0, length, reinterpret_cast<jint*>(copy.data()));
    {
        std::lock_guard<std::mutex> lock(g_display.mutex);
        g_display.ring = copy;
        g_display.ringDirty = true;
    }
    g_display.cv.notify_one();
    return JNI_TRUE;
}

extern "C" JNIEXPORT jstring JNICALL
Java_quasar_rubcut_openio_data_display_NativeDisplay_nativeDisplayInfo(JNIEnv* env, jobject) {
    char buffer[256];
    snprintf(buffer, sizeof(buffer), "fb4=%dx%d fb5=%dx%d",
             static_cast<int>(g_display.screenFb.var.xres), static_cast<int>(g_display.screenFb.var.yres),
             static_cast<int>(g_display.ringFb.var.xres), static_cast<int>(g_display.ringFb.var.yres));
    return env->NewStringUTF(buffer);
}

extern "C" JNIEXPORT jboolean JNICALL
Java_quasar_rubcut_openio_data_input_NativeInput_nativeStartInput(JNIEnv* env, jobject thiz) {
    if (g_input.running.load(std::memory_order_acquire)) return JNI_TRUE;
    if (env->GetJavaVM(&g_input.vm) != JNI_OK) {
        LOGE("GetJavaVM failed");
        return JNI_FALSE;
    }
    g_input.callback = env->NewGlobalRef(thiz);
    if (g_input.callback == nullptr) return JNI_FALSE;
    jclass clazz = env->GetObjectClass(thiz);
    g_input.onInput = env->GetMethodID(clazz, "onNativeInput", "(Ljava/lang/String;III)V");
    env->DeleteLocalRef(clazz);
    if (g_input.onInput == nullptr) {
        env->DeleteGlobalRef(g_input.callback);
        g_input.callback = nullptr;
        return JNI_FALSE;
    }
    g_input.running.store(true, std::memory_order_release);
    g_input.thread = std::thread(inputLoop);
    return JNI_TRUE;
}

extern "C" JNIEXPORT void JNICALL
Java_quasar_rubcut_openio_data_input_NativeInput_nativeStopInput(JNIEnv*, jobject) {
    g_input.stop();
}
// ---------------------------------------------------------------------------
// Stock io.sdk spectrogram bridge.
//
// The original screen visualisers are not configurable files: they are C++
// classes inside libquasar_daemons.so. io.sdk loads that library straight from
// /system/priv-app/ServicesIosdk/ServicesIosdk.apk, where it is stored
// uncompressed, so bionic can dlopen it through the
// "apk!/lib/arm64-v8a/<name>" path. We call the very same draw functions on a
// struct that mirrors the fields they read, which keeps the picture identical
// to what the Station itself draws instead of an approximation.
// ---------------------------------------------------------------------------

namespace {

constexpr int kStockPixels = kScreenWidth * kScreenHeight;

struct StockBridge {
    std::mutex mutex;
    void* base = nullptr;
    bool ready = false;
    unsigned char object[0x200];
    float state[64];
};

StockBridge g_stock;
const char* const kStockLibName = "libquasar_daemons.so";

const char* const kStockDeps[] = {
    "libiconv.so",
    "libglib.so",
    "libtflite.so",
    "libavahi-common.so",
    "libavahi-core.so",
    "libDspConfig.so",
    "libSimpleSetup.so",
    "libdatastore_shared_counter.so",
    "libquark_yaos_shm_transport_runtime.so",
    "libquark_tv.so",
    "libquasar_gstreamer.so",
    "libleds.so",
};

int stockPhdrCallback(struct dl_phdr_info* info, size_t, void*) {
    if (info->dlpi_name == nullptr) return 0;
    const char* slash = strrchr(info->dlpi_name, '/');
    const char* name = slash ? slash + 1 : info->dlpi_name;
    if (strcmp(name, kStockLibName) != 0) return 0;
    g_stock.base = reinterpret_cast<void*>(info->dlpi_addr);
    return 1;
}

bool stockLoad(const std::string& apkPath) {
    const std::string dir = apkPath + "!/lib/arm64-v8a";
    for (const char* dep : kStockDeps) {
        const std::string path = dir + "/" + dep;
        if (dlopen(path.c_str(), RTLD_NOW | RTLD_GLOBAL) == nullptr) {
            LOGW("stock dep %s unavailable: %s", dep, dlerror());
        }
    }
    const std::string main = dir + "/" + kStockLibName;
    if (dlopen(main.c_str(), RTLD_NOW | RTLD_GLOBAL) == nullptr) {
        LOGE("stock library load failed: %s", dlerror());
        return false;
    }
    g_stock.base = nullptr;
    dl_iterate_phdr(stockPhdrCallback, nullptr);
    if (g_stock.base == nullptr) {
        LOGE("stock library base address not found");
        return false;
    }
    LOGI("stock renderer mapped at %p", g_stock.base);
    return true;
}

inline void putU64(unsigned char* obj, size_t off, uint64_t value) {
    *reinterpret_cast<uint64_t*>(obj + off) = value;
}

inline void putF32(unsigned char* obj, size_t off, float value) {
    *reinterpret_cast<float*>(obj + off) = value;
}

using StockRenderFn = void (*)(void* self, const void** levels);

}  // namespace

extern "C" JNIEXPORT jboolean JNICALL
Java_quasar_rubcut_openio_data_display_NativeStock_nativeStockInit(JNIEnv* env, jclass, jstring apkPath) {
    if (apkPath == nullptr) return JNI_FALSE;
    const char* raw = env->GetStringUTFChars(apkPath, nullptr);
    if (raw == nullptr) return JNI_FALSE;
    std::string path(raw);
    env->ReleaseStringUTFChars(apkPath, raw);

    std::lock_guard<std::mutex> lock(g_stock.mutex);
    if (g_stock.ready) return JNI_TRUE;
    std::memset(g_stock.object, 0, sizeof(g_stock.object));
    std::memset(g_stock.state, 0, sizeof(g_stock.state));
    g_stock.ready = stockLoad(path);
    return g_stock.ready ? JNI_TRUE : JNI_FALSE;
}

extern "C" JNIEXPORT jboolean JNICALL
Java_quasar_rubcut_openio_data_display_NativeStock_nativeStockRender(
        JNIEnv* env, jclass, jint style, jintArray ip, jfloatArray fp,
        jfloatArray levels, jfloatArray out) {
    if (ip == nullptr || fp == nullptr || levels == nullptr || out == nullptr) return JNI_FALSE;
    if (env->GetArrayLength(ip) != 8 || env->GetArrayLength(fp) != 8) return JNI_FALSE;
    const jsize nlevels = env->GetArrayLength(levels);
    if (nlevels <= 0 || nlevels > 64) return JNI_FALSE;
    if (env->GetArrayLength(out) != kStockPixels) return JNI_FALSE;

    jint ipv[8] = {0};
    jfloat fpv[8] = {0};
    float levelBuf[64] = {0};
    env->GetIntArrayRegion(ip, 0, 8, ipv);
    env->GetFloatArrayRegion(fp, 0, 8, fpv);
    env->GetFloatArrayRegion(levels, 0, nlevels, levelBuf);

    std::lock_guard<std::mutex> lock(g_stock.mutex);
    if (!g_stock.ready) return JNI_FALSE;

    static float frame[kStockPixels];
    std::memset(frame, 0, sizeof(frame));

    unsigned char* obj = g_stock.object;
    std::memset(obj, 0, sizeof(g_stock.object));
    const uintptr_t base = reinterpret_cast<uintptr_t>(g_stock.base);

    putU64(obj, 0x20, kScreenWidth);
    putU64(obj, 0x28, kScreenHeight);
    putU64(obj, 0x30, reinterpret_cast<uintptr_t>(frame));
    putU64(obj, 0x38, reinterpret_cast<uintptr_t>(frame + kStockPixels));
    putU64(obj, 0x40, reinterpret_cast<uintptr_t>(frame + kStockPixels));

    uintptr_t entry = 0;
    switch (style) {
        case 0:  // PadsSpectrogram::render
            putU64(obj, 0x00, base + 0x48a1ed0);
            putU64(obj, 0x58, static_cast<uint64_t>(ipv[1]));  // rows
            putU64(obj, 0x60, static_cast<uint64_t>(ipv[0]));  // columns
            putU64(obj, 0x68, static_cast<uint64_t>(ipv[2]));  // xOffset
            putU64(obj, 0x70, static_cast<uint64_t>(ipv[3]));  // yOffset
            putU64(obj, 0x78, static_cast<uint64_t>(ipv[4]));  // padSize
            putU64(obj, 0x80, static_cast<uint64_t>(ipv[5]));  // pad pitch
            putF32(obj, 0x88, fpv[0]);                         // inner fade
            putF32(obj, 0x8c, fpv[1]);                         // brightness gamma
            entry = 0x4595a64;
            break;
        case 1:  // WaveSpectrogram::render
            putU64(obj, 0x00, base + 0x48a28e0);
            putU64(obj, 0x60, static_cast<uint64_t>(ipv[0]));  // band count
            putF32(obj, 0x68, fpv[0]);                         // minimum amplitude
            putF32(obj, 0x6c, fpv[1]);                         // brightness fading
            putU64(obj, 0x70, static_cast<uint64_t>(ipv[1]));  // dense columns
            putU64(obj, 0x74, static_cast<uint64_t>(ipv[2]));  // half height
            entry = 0x45b9cf0;
            break;
        case 2:  // BricksSpectrogram::render
            putU64(obj, 0x00, base + 0x48a2870);
            putU64(obj, 0x50, static_cast<uint64_t>(ipv[0]));  // brick width
            putU64(obj, 0x54, static_cast<uint64_t>(ipv[1]));  // brick height
            putU64(obj, 0x58, static_cast<uint64_t>(ipv[2]));  // brick height (rows)
            putF32(obj, 0x64, fpv[3]);                         // falloff speed
            putF32(obj, 0x68, fpv[0]);                         // brick colour
            putF32(obj, 0x6c, fpv[2]);                         // falloff colour
            putF32(obj, 0x70, fpv[1]);                         // brightness fading
            *(obj + 0x74) = static_cast<unsigned char>(ipv[3] & 0xFF);
            putU64(obj, 0x78, reinterpret_cast<uintptr_t>(g_stock.state));
            entry = 0x45b948c;
            break;
        default:
            return JNI_FALSE;
    }

    const void* levelArg[2] = {levelBuf, levelBuf + nlevels};
    reinterpret_cast<StockRenderFn>(base + entry)(obj, levelArg);

    env->SetFloatArrayRegion(out, 0, kStockPixels, frame);
    return JNI_TRUE;
}
