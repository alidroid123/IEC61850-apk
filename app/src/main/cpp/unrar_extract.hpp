// .rar extraction on top of RARLAB's UnRAR (unrar/), kept free of JNI so it can be exercised
// off-device. unrar_jni.cpp exposes it to RarExtractor.java.
#pragma once

#include <string>
#include <vector>

// Beyond UnRAR's own ERAR_* codes - keep in sync with RarExtractor.java.
constexpr int RAR_ERR_TOO_LARGE = 1000;
constexpr int RAR_ERR_WRITE = 1001;

struct RarExtractResult {
    // Absolute paths (UTF-16, for Java) of the files written below outDir.
    std::vector<std::u16string> files;
    // First error met (an ERAR_* code or one of the above), 0 = none.
    int error = 0;
};

// Unpacks every regular file of `archive` below `outDir` (UTF-8; `outDir16` is the same path in
// UTF-16), keeping its folders. Directories, links and encrypted entries are skipped, and writing
// stops once more than `maxBytes` would be unpacked.
RarExtractResult extractRar(const std::string &archive, const std::string &outDir,
                            const std::u16string &outDir16, long long maxBytes);
