/*
 * dfr_jni.c - v2 (Fase 3: IEC 61850 DFR Downloader)
 * Melanjutkan dari v1.
 *
 * v2 CHANGES: ported vendor-detection primitives from the user's original
 * Windows Comtrade_Downloader project (DownloaderLogic.cpp/ShouldDeepScan)
 * so the smart multi-vendor scan logic can run on Android. Unlike the
 * original (which hardcoded the MMS domain name "IEDRCD"), this version
 * discovers the Logical Device name dynamically via
 * IedConnection_getLogicalDeviceList() - the user confirmed LD naming is
 * not guaranteed consistent across all relays in the field, so hardcoding
 * would have silently broken vendor detection on some substations.
 * New: nativeGetLogicalDevices(), nativeReadDataAttribute().
 *
 * Thin JNI bridge between com.alidev.dfrtools.dfr.Iec61850DfrClient (Java)
 * and libiec61850's blocking IedConnection client API.
 *
 * Design choice: every native method here is BLOCKING (connect, list,
 * download). The Java side is responsible for calling these off the main
 * thread (e.g. from an ExecutorService), matching the pattern already used
 * elsewhere in the app for background work. This keeps the JNI surface
 * small and avoids the extra complexity/fragility of marshalling async C
 * callbacks back into Java across JNI - a reasonable trade-off for a
 * field-engineering tool talking to one relay at a time.
 *
 * All business logic (which folders to scan, vendor keyword matching,
 * smart file-pairing, download queue ordering) intentionally lives in
 * Java (see dfr/ComtradeSmartSearch.java), not here. This native layer
 * only exposes MMS primitives - easier to iterate on the search logic
 * without touching/recompiling native code, matching how the rest of
 * this app keeps calculation logic in Java (Calculators.java) rather
 * than native.
 *
 * Handle pattern: nativeConnect() allocates a small C struct wrapping the
 * IedConnection* plus a last-error buffer, and returns its address to Java
 * as a jlong "handle". Every other native method takes that handle back.
 * nativeDisconnect() frees it. If Java loses the handle without calling
 * nativeDisconnect() the native memory leaks for the lifetime of the
 * process - acceptable for this use case (one connection per screen,
 * closed in onDestroy()), but worth knowing if this code is reused
 * elsewhere.
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <stdio.h>
#include <android/log.h>

#include "iec61850_client.h"
#include "iec61850_common.h"
#include "mms_value.h"

#define LOG_TAG "dfr_jni"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

typedef struct {
    IedConnection con;
    char lastError[256];
} DfrHandle;


static void setError(DfrHandle* h, const char* msg) {
    if (h == NULL) return;
    strncpy(h->lastError, msg, sizeof(h->lastError) - 1);
    h->lastError[sizeof(h->lastError) - 1] = '\0';
}

static const char* errorToString(IedClientError err) {
    switch (err) {
        case IED_ERROR_OK: return "OK";
        case IED_ERROR_NOT_CONNECTED: return "Tidak terhubung ke relay";
        case IED_ERROR_ALREADY_CONNECTED: return "Sudah terhubung";
        case IED_ERROR_CONNECTION_LOST: return "Koneksi ke relay terputus";
        case IED_ERROR_SERVICE_NOT_SUPPORTED: return "Relay tidak mendukung layanan ini";
        case IED_ERROR_CONNECTION_REJECTED: return "Koneksi ditolak oleh relay";
        case IED_ERROR_OUTSTANDING_CALL_LIMIT_REACHED: return "Batas permintaan tercapai";
        case IED_ERROR_USER_PROVIDED_INVALID_ARGUMENT: return "Parameter tidak valid";
        case IED_ERROR_ENABLE_REPORT_FAILED_DATASET_MISMATCH: return "Gagal aktifkan report (dataset tidak cocok)";
        case IED_ERROR_OBJECT_REFERENCE_INVALID: return "Referensi objek tidak valid";
        case IED_ERROR_UNEXPECTED_VALUE_RECEIVED: return "Menerima nilai tidak terduga dari relay";
        case IED_ERROR_TIMEOUT: return "Waktu tunggu habis (timeout) - cek IP/port/jaringan";
        case IED_ERROR_ACCESS_DENIED: return "Akses ditolak oleh relay";
        case IED_ERROR_OBJECT_DOES_NOT_EXIST: return "File/objek tidak ditemukan di relay";
        case IED_ERROR_OBJECT_EXISTS: return "Objek sudah ada";
        case IED_ERROR_OBJECT_ACCESS_UNSUPPORTED: return "Akses objek tidak didukung";
        case IED_ERROR_TYPE_INCONSISTENT: return "Tipe data tidak konsisten";
        case IED_ERROR_TEMPORARILY_UNAVAILABLE: return "Relay sementara tidak tersedia";
        case IED_ERROR_OBJECT_UNDEFINED: return "Objek tidak terdefinisi";
        case IED_ERROR_INVALID_ADDRESS: return "Alamat tidak valid";
        case IED_ERROR_HARDWARE_FAULT: return "Kegagalan perangkat keras relay";
        case IED_ERROR_TYPE_UNSUPPORTED: return "Tipe tidak didukung";
        case IED_ERROR_OBJECT_ATTRIBUTE_INCONSISTENT: return "Atribut objek tidak konsisten";
        case IED_ERROR_OBJECT_VALUE_INVALID: return "Nilai objek tidak valid";
        case IED_ERROR_OBJECT_INVALIDATED: return "Objek sudah tidak berlaku";
        case IED_ERROR_MALFORMED_MESSAGE: return "Pesan dari relay tidak valid (malformed)";
        case IED_ERROR_SERVICE_NOT_IMPLEMENTED: return "Layanan belum diimplementasikan";
        case IED_ERROR_UNKNOWN: return "Kesalahan tidak diketahui";
        default: return "Kesalahan tidak diketahui";
    }
}

JNIEXPORT jlong JNICALL
Java_com_alidev_dfrtools_dfr_Iec61850DfrClient_nativeConnect(JNIEnv* env, jobject thiz,
        jstring jHost, jint port, jint timeoutMs, jint profile) {
    const char* host = (*env)->GetStringUTFChars(env, jHost, NULL);

    DfrHandle* h = (DfrHandle*) malloc(sizeof(DfrHandle));
    h->lastError[0] = '\0';
    h->con = IedConnection_create();

    if (timeoutMs > 0) {
        IedConnection_setConnectTimeout(h->con, (uint32_t) timeoutMs);
    }

    /*
     * Improvisasi Koneksi (MMS Profiles):
     * Setiap vendor relay (ABB, Siemens, GE, Schneider, dll) terkadang
     * memiliki requirement ISO-over-TCP (OSI Stack) yang berbeda.
     * Profile 0: Default (Standard libiec61850)
     * Profile 1: ABB/GE Profile (T-SEL: 00 01, S-SEL: 00 01, P-SEL: 00 00 00 01)
     * Profile 2: Schneider/Other (T-SEL: 00 01, S-SEL: 00 01)
     */
    if (profile == 1 || profile == 2) {
        MmsConnection mmsCon = IedConnection_getMmsConnection(h->con);
        IsoConnectionParameters isoParams = MmsConnection_getIsoConnectionParameters(mmsCon);

        TSelector tsel = {0};
        tsel.size = 2;
        tsel.value[0] = 0x00; tsel.value[1] = 0x01;

        SSelector ssel = {0};
        ssel.size = 2;
        ssel.value[0] = 0x00; ssel.value[1] = 0x01;

        PSelector psel = isoParams->remotePSelector;

        if (profile == 1) {
            psel.size = 4;
            psel.value[0] = 0x00; psel.value[1] = 0x00; psel.value[2] = 0x00; psel.value[3] = 0x01;
        }

        IsoConnectionParameters_setRemoteAddresses(isoParams, psel, ssel, tsel);
    }

    IedClientError err;
    IedConnection_connect(h->con, &err, host, (int) port);

    (*env)->ReleaseStringUTFChars(env, jHost, host);

    if (err != IED_ERROR_OK) {
        setError(h, errorToString(err));
        IedConnection_destroy(h->con);
        free(h);
        return 0;
    }

    return (jlong)(intptr_t) h;
}

JNIEXPORT void JNICALL
Java_com_alidev_dfrtools_dfr_Iec61850DfrClient_nativeDisconnect(JNIEnv* env, jobject thiz, jlong handle) {
    DfrHandle* h = (DfrHandle*)(intptr_t) handle;
    if (h == NULL) return;

    IedConnectionState state = IedConnection_getState(h->con);
    if (state == IED_STATE_CONNECTED) {
        IedClientError err;
        IedConnection_close(h->con);
        (void) err;
    }
    IedConnection_destroy(h->con);
    free(h);
}

JNIEXPORT jstring JNICALL
Java_com_alidev_dfrtools_dfr_Iec61850DfrClient_nativeGetLastError(JNIEnv* env, jobject thiz, jlong handle) {
    DfrHandle* h = (DfrHandle*)(intptr_t) handle;
    if (h == NULL) return (*env)->NewStringUTF(env, "Handle tidak valid");
    return (*env)->NewStringUTF(env, h->lastError);
}

/*
 * Returns entries flattened as "name|sizeBytes|lastModifiedEpochMillis" strings,
 * one per directory entry. Keeping this as String[] instead of a custom Java
 * object avoids FindClass/NewObject bookkeeping on the native side; the Java
 * wrapper (Iec61850DfrClient) parses these into DfrFileEntry objects.
 */
JNIEXPORT jobjectArray JNICALL
Java_com_alidev_dfrtools_dfr_Iec61850DfrClient_nativeListFiles(JNIEnv* env, jobject thiz,
        jlong handle, jstring jDirectory) {
    DfrHandle* h = (DfrHandle*)(intptr_t) handle;
    jclass stringClass = (*env)->FindClass(env, "java/lang/String");

    if (h == NULL) {
        return (*env)->NewObjectArray(env, 0, stringClass, NULL);
    }

    const char* directory = NULL;
    if (jDirectory != NULL) {
        directory = (*env)->GetStringUTFChars(env, jDirectory, NULL);
    }

    IedClientError err;
    LinkedList fileNames = IedConnection_getFileDirectory(h->con, &err, directory);

    if (directory != NULL) {
        (*env)->ReleaseStringUTFChars(env, jDirectory, directory);
    }

    if (err != IED_ERROR_OK) {
        setError(h, errorToString(err));
        return (*env)->NewObjectArray(env, 0, stringClass, NULL);
    }

    int count = fileNames != NULL ? LinkedList_size(fileNames) : 0;
    jobjectArray result = (*env)->NewObjectArray(env, count, stringClass, NULL);

    if (fileNames != NULL) {
        LinkedList entry = LinkedList_getNext(fileNames);
        int i = 0;
        char lineBuf[512];
        while (entry != NULL && i < count) {
            FileDirectoryEntry fde = (FileDirectoryEntry) LinkedList_getData(entry);
            const char* name = FileDirectoryEntry_getFileName(fde);
            uint32_t size = FileDirectoryEntry_getFileSize(fde);
            uint64_t modified = FileDirectoryEntry_getLastModified(fde);

            snprintf(lineBuf, sizeof(lineBuf), "%s|%u|%llu",
                     name != NULL ? name : "", (unsigned int) size,
                     (unsigned long long) modified);

            jstring jLine = (*env)->NewStringUTF(env, lineBuf);
            (*env)->SetObjectArrayElement(env, result, i, jLine);
            (*env)->DeleteLocalRef(env, jLine);

            entry = LinkedList_getNext(entry);
            i++;
        }
        LinkedList_destroyDeep(fileNames, (LinkedListValueDeleteFunction) FileDirectoryEntry_destroy);
    }

    return result;
}

typedef struct {
    FILE* fp;
    uint32_t totalBytes;
} DownloadContext;

static bool downloadFileHandler(void* parameter, uint8_t* buffer, uint32_t bytesRead) {
    DownloadContext* ctx = (DownloadContext*) parameter;
    size_t written = fwrite(buffer, 1, bytesRead, ctx->fp);
    ctx->totalBytes += (uint32_t) written;
    return written == bytesRead; /* false aborts the transfer on local write failure */
}

JNIEXPORT jboolean JNICALL
Java_com_alidev_dfrtools_dfr_Iec61850DfrClient_nativeDownloadFile(JNIEnv* env, jobject thiz,
        jlong handle, jstring jRemoteFileName, jstring jLocalFilePath) {
    DfrHandle* h = (DfrHandle*)(intptr_t) handle;
    if (h == NULL) return JNI_FALSE;

    const char* remoteFileName = (*env)->GetStringUTFChars(env, jRemoteFileName, NULL);
    const char* localFilePath = (*env)->GetStringUTFChars(env, jLocalFilePath, NULL);

    DownloadContext ctx;
    ctx.totalBytes = 0;
    ctx.fp = fopen(localFilePath, "wb");

    jboolean success = JNI_FALSE;

    if (ctx.fp == NULL) {
        setError(h, "Gagal membuat file lokal (cek izin penyimpanan)");
    } else {
        IedClientError err;
        IedConnection_getFile(h->con, &err, remoteFileName, downloadFileHandler, &ctx);
        fclose(ctx.fp);

        if (err == IED_ERROR_OK) {
            success = JNI_TRUE;
        } else {
            setError(h, errorToString(err));
            remove(localFilePath); /* don't leave a partial/corrupt file behind */
        }
    }

    (*env)->ReleaseStringUTFChars(env, jRemoteFileName, remoteFileName);
    (*env)->ReleaseStringUTFChars(env, jLocalFilePath, localFilePath);

    return success;
}

JNIEXPORT jboolean JNICALL
Java_com_alidev_dfrtools_dfr_Iec61850DfrClient_nativeIsConnected(JNIEnv* env, jobject thiz, jlong handle) {
    DfrHandle* h = (DfrHandle*)(intptr_t) handle;
    if (h == NULL) return JNI_FALSE;
    return IedConnection_getState(h->con) == IED_STATE_CONNECTED ? JNI_TRUE : JNI_FALSE;
}

/*
 * v2: Returns the server's Logical Device names (GetServerDirectory ACSI
 * service). Ported from the original project's need to read
 * "LPHD1.PhyNam.vendor" out of a specific domain - but instead of
 * hardcoding that domain name (as the original Windows tool did with
 * "IEDRCD"), Java picks a Logical Device from this list at runtime.
 */
JNIEXPORT jobjectArray JNICALL
Java_com_alidev_dfrtools_dfr_Iec61850DfrClient_nativeGetLogicalDevices(JNIEnv* env, jobject thiz, jlong handle) {
    DfrHandle* h = (DfrHandle*)(intptr_t) handle;
    jclass stringClass = (*env)->FindClass(env, "java/lang/String");

    if (h == NULL) {
        return (*env)->NewObjectArray(env, 0, stringClass, NULL);
    }

    IedClientError err;
    LinkedList devices = IedConnection_getLogicalDeviceList(h->con, &err);

    if (err != IED_ERROR_OK || devices == NULL) {
        setError(h, errorToString(err));
        return (*env)->NewObjectArray(env, 0, stringClass, NULL);
    }

    int count = LinkedList_size(devices);
    jobjectArray result = (*env)->NewObjectArray(env, count, stringClass, NULL);

    LinkedList entry = LinkedList_getNext(devices);
    int i = 0;
    while (entry != NULL && i < count) {
        char* name = (char*) LinkedList_getData(entry);
        jstring jName = (*env)->NewStringUTF(env, name != NULL ? name : "");
        (*env)->SetObjectArrayElement(env, result, i, jName);
        (*env)->DeleteLocalRef(env, jName);
        entry = LinkedList_getNext(entry);
        i++;
    }
    LinkedList_destroy(devices);

    return result;
}

/*
 * v2: Reads one data attribute as a plain string, e.g.
 * dataAttributeReference = "IEDRCD/LPHD1.PhyNam.vendor", fc = "DC".
 * Used by ComtradeSmartSearch.java to read PhyNam.vendor / PhyNam.model
 * for vendor detection. Returns null (not empty string) on any failure
 * so Java can distinguish "attribute doesn't exist / wrong FC" from
 * "attribute exists but is blank" without an extra native call.
 *
 * Only DC (Description/Configuration) and ST (Status) functional
 * constraints are supported here since that covers every use this
 * feature currently needs; extend the fc-string mapping below if a
 * future feature needs to read from another FC.
 */
JNIEXPORT jstring JNICALL
Java_com_alidev_dfrtools_dfr_Iec61850DfrClient_nativeReadDataAttribute(JNIEnv* env, jobject thiz,
        jlong handle, jstring jObjectRef, jstring jFc) {
    DfrHandle* h = (DfrHandle*)(intptr_t) handle;
    if (h == NULL) return NULL;

    const char* objectRef = (*env)->GetStringUTFChars(env, jObjectRef, NULL);
    const char* fcStr = (*env)->GetStringUTFChars(env, jFc, NULL);

    FunctionalConstraint fc = IEC61850_FC_DC;
    if (strcmp(fcStr, "ST") == 0) fc = IEC61850_FC_ST;

    IedClientError err;
    MmsValue* value = IedConnection_readObject(h->con, &err, objectRef, fc);

    (*env)->ReleaseStringUTFChars(env, jObjectRef, objectRef);
    (*env)->ReleaseStringUTFChars(env, jFc, fcStr);

    if (err != IED_ERROR_OK || value == NULL) {
        if (value != NULL) MmsValue_delete(value);
        setError(h, errorToString(err));
        return NULL;
    }

    jstring result = NULL;
    MmsType type = MmsValue_getType(value);
    if (type == MMS_VISIBLE_STRING || type == MMS_STRING) {
        const char* s = MmsValue_toString(value);
        if (s != NULL) {
            result = (*env)->NewStringUTF(env, s);
        }
    }

    MmsValue_delete(value);
    return result;
}

/*
 * MMS Explorer support methods
 */

JNIEXPORT jobjectArray JNICALL
Java_com_alidev_dfrtools_dfr_Iec61850DfrClient_nativeGetLogicalDeviceDirectory(JNIEnv* env, jobject thiz,
        jlong handle, jstring jLdName) {
    DfrHandle* h = (DfrHandle*)(intptr_t) handle;
    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    if (h == NULL) return (*env)->NewObjectArray(env, 0, stringClass, NULL);

    const char* ldName = (*env)->GetStringUTFChars(env, jLdName, NULL);
    IedClientError err;
    LinkedList lnList = IedConnection_getLogicalDeviceDirectory(h->con, &err, ldName);
    (*env)->ReleaseStringUTFChars(env, jLdName, ldName);

    if (err != IED_ERROR_OK || lnList == NULL) {
        if (lnList != NULL) LinkedList_destroy(lnList);
        setError(h, errorToString(err));
        return (*env)->NewObjectArray(env, 0, stringClass, NULL);
    }

    int count = LinkedList_size(lnList);
    jobjectArray result = (*env)->NewObjectArray(env, count, stringClass, NULL);
    LinkedList entry = LinkedList_getNext(lnList);
    int i = 0;
    while (entry != NULL && i < count) {
        char* name = (char*) LinkedList_getData(entry);
        jstring jName = (*env)->NewStringUTF(env, name != NULL ? name : "");
        (*env)->SetObjectArrayElement(env, result, i, jName);
        (*env)->DeleteLocalRef(env, jName);
        entry = LinkedList_getNext(entry);
        i++;
    }
    LinkedList_destroy(lnList);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_com_alidev_dfrtools_dfr_Iec61850DfrClient_nativeGetLogicalNodeDirectory(JNIEnv* env, jobject thiz,
        jlong handle, jstring jLnRef) {
    DfrHandle* h = (DfrHandle*)(intptr_t) handle;
    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    if (h == NULL) return (*env)->NewObjectArray(env, 0, stringClass, NULL);

    const char* lnRef = (*env)->GetStringUTFChars(env, jLnRef, NULL);
    IedClientError err;
    LinkedList doList = IedConnection_getLogicalNodeDirectory(h->con, &err, lnRef, ACSI_CLASS_DATA_OBJECT);
    (*env)->ReleaseStringUTFChars(env, jLnRef, lnRef);

    if (err != IED_ERROR_OK || doList == NULL) {
        if (doList != NULL) LinkedList_destroy(doList);
        setError(h, errorToString(err));
        return (*env)->NewObjectArray(env, 0, stringClass, NULL);
    }

    int count = LinkedList_size(doList);
    jobjectArray result = (*env)->NewObjectArray(env, count, stringClass, NULL);
    LinkedList entry = LinkedList_getNext(doList);
    int i = 0;
    while (entry != NULL && i < count) {
        char* name = (char*) LinkedList_getData(entry);
        jstring jName = (*env)->NewStringUTF(env, name != NULL ? name : "");
        (*env)->SetObjectArrayElement(env, result, i, jName);
        (*env)->DeleteLocalRef(env, jName);
        entry = LinkedList_getNext(entry);
        i++;
    }
    LinkedList_destroy(doList);
    return result;
}

JNIEXPORT jobjectArray JNICALL
Java_com_alidev_dfrtools_dfr_Iec61850DfrClient_nativeGetDataDirectory(JNIEnv* env, jobject thiz,
        jlong handle, jstring jDataRef) {
    DfrHandle* h = (DfrHandle*)(intptr_t) handle;
    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    if (h == NULL) return (*env)->NewObjectArray(env, 0, stringClass, NULL);

    const char* dataRef = (*env)->GetStringUTFChars(env, jDataRef, NULL);
    IedClientError err;
    LinkedList daList = IedConnection_getDataDirectory(h->con, &err, dataRef);
    (*env)->ReleaseStringUTFChars(env, jDataRef, dataRef);

    if (err != IED_ERROR_OK || daList == NULL) {
        if (daList != NULL) LinkedList_destroy(daList);
        setError(h, errorToString(err));
        return (*env)->NewObjectArray(env, 0, stringClass, NULL);
    }

    int count = LinkedList_size(daList);
    jobjectArray result = (*env)->NewObjectArray(env, count, stringClass, NULL);
    LinkedList entry = LinkedList_getNext(daList);
    int i = 0;
    while (entry != NULL && i < count) {
        char* name = (char*) LinkedList_getData(entry);
        jstring jName = (*env)->NewStringUTF(env, name != NULL ? name : "");
        (*env)->SetObjectArrayElement(env, result, i, jName);
        (*env)->DeleteLocalRef(env, jName);
        entry = LinkedList_getNext(entry);
        i++;
    }
    LinkedList_destroy(daList);
    return result;
}

static const char* dataAccessErrorToString(MmsDataAccessError err) {
    switch (err) {
        case DATA_ACCESS_ERROR_OBJECT_INVALIDATED: return "Objek tidak berlaku";
        case DATA_ACCESS_ERROR_HARDWARE_FAULT: return "Gangguan hardware relay";
        case DATA_ACCESS_ERROR_TEMPORARILY_UNAVAILABLE: return "Tidak tersedia sementara";
        case DATA_ACCESS_ERROR_OBJECT_ACCESS_DENIED: return "Akses ditolak";
        case DATA_ACCESS_ERROR_OBJECT_UNDEFINED: return "Objek tidak terdefinisi";
        case DATA_ACCESS_ERROR_INVALID_ADDRESS: return "Alamat tidak valid";
        case DATA_ACCESS_ERROR_TYPE_UNSUPPORTED: return "Tipe tidak didukung";
        case DATA_ACCESS_ERROR_TYPE_INCONSISTENT: return "Tipe tidak konsisten";
        case DATA_ACCESS_ERROR_OBJECT_ATTRIBUTE_INCONSISTENT: return "Atribut tidak konsisten";
        case DATA_ACCESS_ERROR_OBJECT_ACCESS_UNSUPPORTED: return "Akses tidak didukung";
        case DATA_ACCESS_ERROR_OBJECT_NONE_EXISTENT: return "Objek tidak ditemukan";
        case DATA_ACCESS_ERROR_OBJECT_VALUE_INVALID: return "Nilai tidak valid";
        default: return "Error akses data";
    }
}

JNIEXPORT jobjectArray JNICALL
Java_com_alidev_dfrtools_dfr_Iec61850DfrClient_nativeGetDeviceVariables(JNIEnv* env, jobject thiz,
        jlong handle, jstring jLdName) {
    DfrHandle* h = (DfrHandle*)(intptr_t) handle;
    jclass stringClass = (*env)->FindClass(env, "java/lang/String");
    if (h == NULL) return (*env)->NewObjectArray(env, 0, stringClass, NULL);

    const char* ldName = (*env)->GetStringUTFChars(env, jLdName, NULL);
    IedClientError err;
    LinkedList varList = IedConnection_getLogicalDeviceVariables(h->con, &err, ldName);
    (*env)->ReleaseStringUTFChars(env, jLdName, ldName);

    if (err != IED_ERROR_OK || varList == NULL) {
        if (varList != NULL) LinkedList_destroy(varList);
        setError(h, errorToString(err));
        return (*env)->NewObjectArray(env, 0, stringClass, NULL);
    }

    int count = LinkedList_size(varList);
    jobjectArray result = (*env)->NewObjectArray(env, count, stringClass, NULL);
    LinkedList entry = LinkedList_getNext(varList);
    int i = 0;
    while (entry != NULL && i < count) {
        char* name = (char*) LinkedList_getData(entry);
        jstring jName = (*env)->NewStringUTF(env, name != NULL ? name : "");
        (*env)->SetObjectArrayElement(env, result, i, jName);
        (*env)->DeleteLocalRef(env, jName);
        entry = LinkedList_getNext(entry);
        i++;
    }
    LinkedList_destroyDeep(varList, (LinkedListValueDeleteFunction) free);
    return result;
}

static void formatMmsValueRecursive(MmsValue* value, char* buf, size_t bufSize) {
    MmsType type = MmsValue_getType(value);

    switch (type) {
        case MMS_BOOLEAN:
            snprintf(buf, bufSize, "%s", MmsValue_getBoolean(value) ? "TRUE" : "FALSE");
            break;
        case MMS_FLOAT:
            snprintf(buf, bufSize, "%.4f", MmsValue_toFloat(value));
            break;
        case MMS_INTEGER:
            snprintf(buf, bufSize, "%d", (int)MmsValue_toInt32(value));
            break;
        case MMS_UNSIGNED:
            snprintf(buf, bufSize, "%u", (unsigned int)MmsValue_toUint32(value));
            break;
        case MMS_VISIBLE_STRING:
        case MMS_STRING:
            snprintf(buf, bufSize, "%s", MmsValue_toString(value));
            break;
        case MMS_UTC_TIME:
            snprintf(buf, bufSize, "%llu", (unsigned long long)MmsValue_getUtcTimeInMs(value));
            break;
        case MMS_BIT_STRING:
            {
                int size = MmsValue_getBitStringSize(value);
                if (size == 13) { // Quality
                    Quality q = Quality_fromMmsValue(value);
                    snprintf(buf, bufSize, "Q:0x%04x", (unsigned int)q);
                } else if (size == 2) { // Dbpos
                    Dbpos d = Dbpos_fromMmsValue(value);
                    const char* dStr = "Intermediate";
                    if (d == DBPOS_OFF) dStr = "OFF";
                    else if (d == DBPOS_ON) dStr = "ON";
                    else if (d == DBPOS_BAD_STATE) dStr = "Bad";
                    snprintf(buf, bufSize, "%s", dStr);
                } else {
                    snprintf(buf, bufSize, "0x%02x", MmsValue_getBitStringAsInteger(value));
                }
            }
            break;
        case MMS_STRUCTURE:
            {
                int size = MmsValue_getArraySize(value);
                if (size > 0) {
                    /* Recursive summary for structures (e.g. mag.f) */
                    char subBuf[128];
                    formatMmsValueRecursive(MmsValue_getElement(value, 0), subBuf, sizeof(subBuf));
                    if (size == 1) {
                        snprintf(buf, bufSize, "%s", subBuf);
                    } else {
                        snprintf(buf, bufSize, "%s, ...", subBuf);
                    }
                } else {
                    snprintf(buf, bufSize, "{}");
                }
            }
            break;
        case MMS_DATA_ACCESS_ERROR:
            snprintf(buf, bufSize, "Access Error: %d", (int)MmsValue_getDataAccessError(value));
            break;
        default:
            snprintf(buf, bufSize, "Type %d", (int)type);
            break;
    }
}

JNIEXPORT jstring JNICALL
Java_com_alidev_dfrtools_dfr_Iec61850DfrClient_nativeReadValueFormatted(JNIEnv* env, jobject thiz,
        jlong handle, jstring jObjectRef, jstring jFc) {
    DfrHandle* h = (DfrHandle*)(intptr_t) handle;
    if (h == NULL) return NULL;

    const char* objectRef = (*env)->GetStringUTFChars(env, jObjectRef, NULL);
    const char* fcStr = (*env)->GetStringUTFChars(env, jFc, NULL);

    FunctionalConstraint fc = IEC61850_FC_ST;
    if (strcmp(fcStr, "MX") == 0) fc = IEC61850_FC_MX;
    else if (strcmp(fcStr, "DC") == 0) fc = IEC61850_FC_DC;
    else if (strcmp(fcStr, "SP") == 0) fc = IEC61850_FC_SP;
    else if (strcmp(fcStr, "CO") == 0) fc = IEC61850_FC_CO;
    else if (strcmp(fcStr, "CF") == 0) fc = IEC61850_FC_CF;

    IedClientError err;
    MmsValue* value = IedConnection_readObject(h->con, &err, objectRef, fc);

    (*env)->ReleaseStringUTFChars(env, jObjectRef, objectRef);

    if (err != IED_ERROR_OK || value == NULL) {
        (*env)->ReleaseStringUTFChars(env, jFc, fcStr);
        if (value != NULL) MmsValue_delete(value);
        return NULL;
    }

    char valBuf[256];
    formatMmsValueRecursive(value, valBuf, sizeof(valBuf));

    char lineBuf[300];
    snprintf(lineBuf, sizeof(lineBuf), "%s|%s", fcStr, valBuf);

    (*env)->ReleaseStringUTFChars(env, jFc, fcStr);
    MmsValue_delete(value);
    return (*env)->NewStringUTF(env, lineBuf);
}

