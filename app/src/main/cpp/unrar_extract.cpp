// .rar extraction (every RAR version up to RAR5 / WinRAR 7) with RARLAB's UnRAR library,
// vendored unchanged in unrar/ (v7.3.1). Its licence (unrar/license.txt) asks for this paragraph
// to be kept in the source of software using it:
//
//   UnRAR source code may be used in any software to handle RAR archives without limitations
//   free of charge, but cannot be used to develop RAR (WinRAR) compatible archiver and to
//   re-create RAR compression algorithm, which is proprietary. Distribution of modified UnRAR
//   source code in separate form or as a part of other software is permitted, provided that
//   full text of this paragraph, starting from "UnRAR source code" words, is included in
//   license, or in documentation if license is not available, and in source code comments of
//   resulting package.
//
// Entries are "tested" (RAR_TEST) instead of extracted and their data is written out here from
// the UCM_PROCESSDATA callback. So this file, not UnRAR, decides every output path: archive
// names can never escape outDir ("../", absolute paths) and links are never created.

#include "unrar_extract.hpp"

#include <cstdint>
#include <cstdio>
#include <memory>

#include <sys/stat.h>
#include <unistd.h>

#include "unrar/raros.hpp"
#include "unrar/dll.hpp"

namespace {

struct Sink {
    FILE *out = nullptr;
    long long written = 0;
    long long limit = 0;
    int error = 0;
};

int CALLBACK onRarEvent(UINT msg, LPARAM userData, LPARAM p1, LPARAM p2) {
    Sink *sink = reinterpret_cast<Sink *>(userData);
    switch (msg) {
        case UCM_PROCESSDATA: {
            if (sink->out == nullptr) return 1;
            size_t n = static_cast<size_t>(p2);
            sink->written += static_cast<long long>(n);
            if (sink->written > sink->limit) {
                sink->error = RAR_ERR_TOO_LARGE;
                return -1;
            }
            if (fwrite(reinterpret_cast<const void *>(p1), 1, n, sink->out) != n) {
                sink->error = RAR_ERR_WRITE;
                return -1;
            }
            return 1;
        }
        case UCM_NEEDPASSWORD:
        case UCM_NEEDPASSWORDW:
            return -1; // no password prompt: reported as ERAR_MISSING_PASSWORD
        case UCM_CHANGEVOLUME:
        case UCM_CHANGEVOLUMEW:
            return p2 == RAR_VOL_ASK ? -1 : 1; // next part of a multi-volume set is missing
        default:
            return 0; // UCM_LARGEDICT: refuse dictionaries over UnRAR's default limit
    }
}

void appendUtf8(std::string &out, char32_t c) {
    if (c < 0x80) {
        out += static_cast<char>(c);
    } else if (c < 0x800) {
        out += static_cast<char>(0xC0 | (c >> 6));
        out += static_cast<char>(0x80 | (c & 0x3F));
    } else if (c < 0x10000) {
        out += static_cast<char>(0xE0 | (c >> 12));
        out += static_cast<char>(0x80 | ((c >> 6) & 0x3F));
        out += static_cast<char>(0x80 | (c & 0x3F));
    } else {
        out += static_cast<char>(0xF0 | (c >> 18));
        out += static_cast<char>(0x80 | ((c >> 12) & 0x3F));
        out += static_cast<char>(0x80 | ((c >> 6) & 0x3F));
        out += static_cast<char>(0x80 | (c & 0x3F));
    }
}

void appendUtf16(std::u16string &out, char32_t c) {
    if (c < 0x10000) {
        out += static_cast<char16_t>(c);
    } else {
        c -= 0x10000;
        out += static_cast<char16_t>(0xD800 | (c >> 10));
        out += static_cast<char16_t>(0xDC00 | (c & 0x3FF));
    }
}

// Splits an archive entry name on '/' and '\', dropping empty, "." and ".." parts so the result
// always stays below the output directory. wchar_t is UTF-32 on Android (and Linux); control
// characters and invalid code points become '_'.
std::vector<std::u32string> safeParts(const wchar_t *name) {
    std::vector<std::u32string> parts;
    std::u32string cur;
    auto flush = [&]() {
        if (!cur.empty() && cur != U"." && cur != U"..") parts.push_back(cur);
        cur.clear();
    };
    for (const wchar_t *p = name; *p != 0; ++p) {
        char32_t c = static_cast<char32_t>(*p);
        if (c == '/' || c == '\\') {
            flush();
            continue;
        }
        if (c < 0x20 || c == 0x7F || c > 0x10FFFF || (c >= 0xD800 && c <= 0xDFFF)) c = '_';
        cur += c;
    }
    flush();
    return parts;
}

// Per-entry failures that leave the rest of the archive readable.
bool isPerEntryError(int code) {
    return code == ERAR_BAD_DATA || code == ERAR_MISSING_PASSWORD || code == ERAR_BAD_PASSWORD
           || code == ERAR_EREFERENCE;
}

} // namespace

RarExtractResult extractRar(const std::string &archive, const std::string &outDir,
                            const std::u16string &outDir16, long long maxBytes) {
    RarExtractResult result;
    auto fail = [&result](int code) {
        if (result.error == 0) result.error = code;
    };

    Sink sink;
    sink.limit = maxBytes;

    RAROpenArchiveDataEx open{};
    open.ArcName = const_cast<char *>(archive.c_str());
    open.OpenMode = RAR_OM_EXTRACT;
    open.Callback = onRarEvent;
    open.UserData = reinterpret_cast<LPARAM>(&sink);
    HANDLE arc = RAROpenArchiveEx(&open);
    if (arc == nullptr) {
        fail(open.OpenResult != 0 ? static_cast<int>(open.OpenResult) : ERAR_EOPEN);
        return result;
    }

    std::unique_ptr<RARHeaderDataEx> hd(new RARHeaderDataEx());
    while (true) {
        int rc = RARReadHeaderEx(arc, hd.get());
        if (rc == ERAR_END_ARCHIVE) break;
        if (rc != ERAR_SUCCESS) {
            fail(rc);
            break;
        }
        std::vector<std::u32string> parts = safeParts(hd->FileNameW);
        bool skip = (hd->Flags & RHDF_DIRECTORY) != 0 || hd->RedirType != 0 || parts.empty();
        if (!skip && (hd->Flags & RHDF_ENCRYPTED) != 0) {
            fail(ERAR_MISSING_PASSWORD);
            skip = true;
        }
        if (skip) {
            rc = RARProcessFile(arc, RAR_SKIP, nullptr, nullptr);
            if (rc != ERAR_SUCCESS) {
                fail(rc);
                if (!isPerEntryError(rc)) break;
            }
            continue;
        }

        std::string path = outDir;
        std::u16string path16 = outDir16;
        for (size_t i = 0; i < parts.size(); i++) {
            path += '/';
            path16 += u'/';
            for (char32_t c : parts[i]) {
                appendUtf8(path, c);
                appendUtf16(path16, c);
            }
            if (i + 1 < parts.size()) mkdir(path.c_str(), 0700); // EEXIST is fine
        }

        FILE *f = fopen(path.c_str(), "wb");
        if (f == nullptr) {
            fail(RAR_ERR_WRITE);
            rc = RARProcessFile(arc, RAR_SKIP, nullptr, nullptr);
            if (rc != ERAR_SUCCESS && !isPerEntryError(rc)) break;
            continue;
        }
        sink.out = f;
        rc = RARProcessFile(arc, RAR_TEST, nullptr, nullptr);
        sink.out = nullptr;
        bool closed = fclose(f) == 0;
        if (rc == ERAR_SUCCESS && sink.error == 0 && closed) {
            result.files.push_back(path16);
            continue;
        }
        unlink(path.c_str());
        fail(sink.error != 0 ? sink.error : (rc != ERAR_SUCCESS ? rc : RAR_ERR_WRITE));
        if (sink.error != 0 || (rc != ERAR_SUCCESS && !isPerEntryError(rc))) break;
    }
    RARCloseArchive(arc);
    return result;
}
