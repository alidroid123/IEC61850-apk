// Force-included into every UnRAR source file (see CMakeLists.txt) so the vendored unrar/
// sources stay unchanged: fills the bionic gaps below this app's minSdk that UnRAR's Linux code
// assumes are there.
#pragma once

#include <fcntl.h>
#include <sys/stat.h>
#include <sys/time.h>

#if defined(__ANDROID__) && __ANDROID_API__ < 26
// lutimes() only exists from API 26. UnRAR calls it to restore symlink times, which never
// happens here (unrar_jni.cpp skips links), but ulinks.cpp still has to compile.
static inline int lutimes(const char *path, const struct timeval tv[2]) {
    struct timespec ts[2];
    for (int i = 0; i < 2; i++) {
        ts[i].tv_sec = tv[i].tv_sec;
        ts[i].tv_nsec = tv[i].tv_usec * 1000;
    }
    return utimensat(AT_FDCWD, path, ts, AT_SYMLINK_NOFOLLOW);
}
#endif
