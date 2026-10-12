// JNI bridge for RarExtractor.java over extractRar() (unrar_extract.cpp, which carries the UnRAR
// licence note).

#include <jni.h>

#include <string>

#include "unrar_extract.hpp"

namespace {

std::string jstringToUtf8(JNIEnv *env, jstring s) {
    const char *chars = env->GetStringUTFChars(s, nullptr);
    std::string out = chars != nullptr ? chars : "";
    if (chars != nullptr) env->ReleaseStringUTFChars(s, chars);
    return out;
}

std::u16string jstringToUtf16(JNIEnv *env, jstring s) {
    std::u16string out(static_cast<size_t>(env->GetStringLength(s)), u'\0');
    if (!out.empty()) env->GetStringRegion(s, 0, static_cast<jsize>(out.size()), reinterpret_cast<jchar *>(&out[0]));
    return out;
}

} // namespace

// Returns the absolute paths of the files written below outDir (possibly an empty array) and
// stores the first error met in error[0] (0 = none), so a damaged or partly encrypted archive
// still yields whatever records could be read.
extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_alidev_dfrtools_dfr_RarExtractor_nativeExtract(JNIEnv *env, jclass, jstring jArchive,
                                                       jstring jOutDir, jlong maxBytes,
                                                       jintArray jError) {
    RarExtractResult r = extractRar(jstringToUtf8(env, jArchive), jstringToUtf8(env, jOutDir),
                                    jstringToUtf16(env, jOutDir), maxBytes);

    jint err = r.error;
    env->SetIntArrayRegion(jError, 0, 1, &err);

    jclass stringClass = env->FindClass("java/lang/String");
    jobjectArray files = env->NewObjectArray(static_cast<jsize>(r.files.size()), stringClass, nullptr);
    if (files == nullptr) return nullptr;
    for (size_t i = 0; i < r.files.size(); i++) {
        const std::u16string &p = r.files[i];
        jstring s = env->NewString(reinterpret_cast<const jchar *>(p.data()), static_cast<jsize>(p.size()));
        if (s == nullptr) return nullptr;
        env->SetObjectArrayElement(files, static_cast<jsize>(i), s);
        env->DeleteLocalRef(s);
    }
    return files;
}
