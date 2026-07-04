#include <dlfcn.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <limits.h>

typedef int (*mysqld_main_fn)(int, char**);

static pthread_t s_thread;
static volatile int s_exit_code = -1;

// Arguments for mysqld (set in main, used in thread)
static char** g_argv = NULL;
static int g_argc = 0;

static void* run_mysqld(void* arg) {
    char* lib_path = (char*)arg;
    void* handle = dlopen(lib_path, RTLD_LAZY | RTLD_GLOBAL);
    if (!handle) {
        fprintf(stderr, "launcher: dlopen(%s) failed: %s\n", lib_path, dlerror());
        s_exit_code = 1;
        return NULL;
    }
    mysqld_main_fn fn = (mysqld_main_fn)dlsym(handle, "_Z11mysqld_mainiPPc");
    if (!fn) {
        fprintf(stderr, "launcher: dlsym(mysqld_main) failed: %s\n", dlerror());
        s_exit_code = 2;
        return NULL;
    }
    s_exit_code = fn(g_argc, g_argv);
    return NULL;
}

int main(int argc, char* argv[], char* envp[]) {
    if (argc < 3) { fprintf(stderr, "launcher: usage <libmariadbd.so> <args...>\n"); return 1; }

    char* lib_path = argv[1];  // absolute path to libmariadbd.so
    // Build mysqld args (skip launcher path and lib_path)
    // argv[0] = launcher, argv[1] = lib_path, argv[2..] = mysqld args
    g_argc = argc - 2;
    g_argv = malloc((g_argc + 2) * sizeof(char*));
    g_argv[0] = "mariadbd";
    for (int i = 0; i < g_argc; i++)
        g_argv[i + 1] = argv[i + 2];
    g_argv[g_argc + 1] = NULL;

    // Set CWD to datadir if provided
    for (int i = 0; i < g_argc; i++)
        if (strcmp(g_argv[i], "--datadir") == 0 && g_argv[i+1]) { chdir(g_argv[i+1]); break; }

    // Pre-load dependency .so files from dep/ (same dir as libmariadbd.so)
    char dep_dir[2048];
    strncpy(dep_dir, lib_path, 2048);
    char* s = strrchr(dep_dir, '/');
    if (s) { *(s + 1) = '\0'; strncat(dep_dir, "dep/", 2048); }

    char tmp[4096];
    const char* deps[] = {
        "libandroid-support.so", "libpcre2-8.so", "libcrypt.so",
        "libz.so.1", "libssl.so.3", "libcrypto.so.3", "libc++_shared.so", NULL
    };
    for (int i = 0; deps[i]; i++) {
        snprintf(tmp, 4096, "%s%s", dep_dir, deps[i]);
        if (access(tmp, F_OK) == 0) dlopen(tmp, RTLD_LAZY | RTLD_GLOBAL);
    }

    // Start mysqld_main in a thread
    pthread_create(&s_thread, NULL, run_mysqld, lib_path);
    pthread_join(s_thread, NULL);
    free(g_argv);
    return s_exit_code;
}
