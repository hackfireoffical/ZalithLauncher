//
// Created by maks on 06.01.2025.
// Extended for Minecraft 26.2+ Android natives (Zalith fork).
//

#include <android/api-level.h>
#include <android/log.h>
#include <jni.h>

#include <environ/environ.h>

#include <dlfcn.h>
#include <string.h>
#include <stdlib.h>
#include <stdio.h>

extern void* maybe_load_vulkan();

/**
 * Redirect LWJGL's DynamicLinkLoader.ndlopen so that:
 *  1. libvulkan.so is loaded via our custom path
 *  2. Any attempt to load a glibc-linked library name that we ship
 *     as an Android .so is redirected to the APK native dir
 */
static jlong ndlopen_bugfix(__attribute__((unused)) JNIEnv *env,
                            __attribute__((unused)) jclass class,
                            jlong filename_ptr,
                            jint jmode) {
    const char* filename = (const char*) filename_ptr;
    int mode = (int)jmode;

    // Override vulkan loading to let us load vulkan ourselves
    if (filename != NULL && strstr(filename, "libvulkan.so") == filename) {
        printf("LWJGL linkerhook: replacing load for libvulkan.so with custom driver\n");
        return (jlong) maybe_load_vulkan();
    }

    // If the path still points at a cache-extracted Linux native (glibc),
    // try the same basename from POJAV_NATIVEDIR first.
    if (filename != NULL) {
        const char* base = strrchr(filename, '/');
        base = base ? base + 1 : filename;

        // Modules we provide (or will provide) as Android builds
        static const char* kAndroidModules[] = {
            "libglfw.so",
            "libopenal.so",
            "libjemalloc.so",
            "liblwjgl_stb.so",
            "libstb.so",
            "liblwjgl_tinyfd.so",
            "libtinyfd.so",
            "libspirv-cross.so",
            "libshaderc.so",
            "libvma.so",
            "liblwjgl_vma.so",
            "liblwjgl.so",
            NULL
        };

        int is_ours = 0;
        for (int i = 0; kAndroidModules[i]; i++) {
            if (strcmp(base, kAndroidModules[i]) == 0) {
                is_ours = 1;
                break;
            }
        }

        if (is_ours) {
            const char* nativeDir = getenv("POJAV_NATIVEDIR");
            if (nativeDir && nativeDir[0]) {
                char alt[512];
                snprintf(alt, sizeof(alt), "%s/%s", nativeDir, base);
                void* handle = dlopen(alt, mode);
                if (handle != NULL) {
                    printf("LWJGL linkerhook: redirected %s -> %s\n", filename, alt);
                    return (jlong) handle;
                }
            }
        }
    }

    // Fallback: normal dlopen (still needed for genuine Android libs)
    return (jlong) dlopen(filename, mode);
}

/**
 * Install the LWJGL dlopen hook. This allows us to mitigate linker bugs and add custom library overrides.
 */
void installLwjglDlopenHook() {
    __android_log_print(ANDROID_LOG_INFO, "LwjglLinkerHook", "Installing LWJGL dlopen() hook");
    JNIEnv* env = pojav_environ->runtimeJNIEnvPtr_JRE;
    jclass dynamicLinkLoader = (*env)->FindClass(env, "org/lwjgl/system/linux/DynamicLinkLoader");
    if(dynamicLinkLoader == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, "LwjglLinkerHook", "Failed to find the target class");
        (*env)->ExceptionClear(env);
        return;
    }
    JNINativeMethod ndlopenMethod[] = {
            {"ndlopen", "(JI)J", &ndlopen_bugfix}
    };
    if((*env)->RegisterNatives(env, dynamicLinkLoader, ndlopenMethod, 1) != 0) {
        __android_log_print(ANDROID_LOG_ERROR, "LwjglLinkerHook", "Failed to register the hooked method");
        (*env)->ExceptionClear(env);
    }
}
