/**
 * Override for ggml_fopen and llama_open to support Android SAF file descriptors.
 * 
 * This file provides custom implementations that:
 * - Detect paths starting with "fd:" (e.g., "fd:123")
 * - Use dup() + fdopen()/dup() to provide file access
 * - Fall back to standard fopen()/open() for regular paths
 * 
 * Key insight: llama.cpp opens files multiple times (for metadata, mmap, etc.)
 * We use dup() to create a copy of the fd each time, so the original stays valid.
 * 
 * This allows llama.cpp to load models from Android Storage Access Framework
 * without modifying the llama.cpp source code.
 */

#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <fcntl.h>
#include <android/log.h>

#define LOG_TAG "ggml_fopen_override"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

// Override ggml_fopen to support file descriptor paths
// Path format: "fd:123" where 123 is the file descriptor number
FILE * ggml_fopen(const char * fname, const char * mode) {
    LOGI("ggml_fopen called: fname='%s', mode='%s'", fname ? fname : "NULL", mode ? mode : "NULL");
    
    // Check if this is a file descriptor path
    if (fname && strncmp(fname, "fd:", 3) == 0) {
        int original_fd = atoi(fname + 3);
        LOGI("Detected fd path, original_fd=%d", original_fd);
        if (original_fd > 0) {
            // IMPORTANT: Use dup() to create a copy of the fd
            // This way the original fd stays valid for subsequent opens
            // (llama.cpp opens the file multiple times for metadata, mmap fallback, etc.)
            int fd_copy = dup(original_fd);
            if (fd_copy < 0) {
                LOGE("dup() failed for fd=%d", original_fd);
                return NULL;
            }
            LOGI("dup(%d) = %d", original_fd, fd_copy);

            // Use fdopen on the COPY
            // fdopen takes ownership of fd_copy - it will be closed when fclose() is called
            FILE * file = fdopen(fd_copy, mode);
            if (file) {
                LOGI("fdopen succeeded for fd_copy=%d (original=%d)", fd_copy, original_fd);
            } else {
                LOGE("fdopen failed for fd_copy=%d, closing it", fd_copy);
                close(fd_copy);
            }
            return file;
        }
    }
    
    // Fall back to standard fopen for regular paths
    LOGI("Using standard fopen for: %s", fname ? fname : "NULL");
    return fopen(fname, mode);
}

// Override llama_open to support file descriptor paths (used for mmap)
// This is called by llama-mmap.cpp for memory mapping
int llama_open(const char * fname, int flags) {
    LOGI("llama_open called: fname='%s', flags=%d", fname ? fname : "NULL", flags);
    
    // Check if this is a file descriptor path
    if (fname && strncmp(fname, "fd:", 3) == 0) {
        int original_fd = atoi(fname + 3);
        LOGI("Detected fd path for open, original_fd=%d", original_fd);
        if (original_fd > 0) {
            // Use dup() to create a copy of the fd
            int fd_copy = dup(original_fd);
            if (fd_copy < 0) {
                LOGE("dup() failed for fd=%d in llama_open", original_fd);
                return -1;
            }
            LOGI("llama_open: dup(%d) = %d", original_fd, fd_copy);
            return fd_copy;
        }
    }
    
    // Fall back to standard open for regular paths
    LOGI("Using standard open for: %s", fname ? fname : "NULL");
    return open(fname, flags);
}
