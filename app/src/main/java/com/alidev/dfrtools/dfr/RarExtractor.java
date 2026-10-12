package com.alidev.dfrtools.dfr;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * .rar extraction (RAR 1.5 up to RAR5 / WinRAR 7) through RARLAB's UnRAR library, built from
 * app/src/main/cpp/unrar into libunrar_jni.so (bridge: unrar_jni.cpp). UnRAR keeps global error
 * state, so calls are serialised.
 */
final class RarExtractor {

    /** UnRAR's ERAR_* codes this app tells apart, plus two of unrar_jni.cpp's own. */
    static final int ERAR_BAD_DATA = 12, ERAR_BAD_ARCHIVE = 13, ERAR_UNKNOWN_FORMAT = 14, ERAR_EOPEN = 15,
            ERAR_MISSING_PASSWORD = 22, ERAR_BAD_PASSWORD = 24, ERR_TOO_LARGE = 1000, ERR_WRITE = 1001;

    /** Nothing usable came out of the archive; `code` is one of the constants above (or another ERAR_*). */
    static class RarException extends IOException {
        final int code;

        RarException(int code) {
            super("RAR error " + code);
            this.code = code;
        }

        boolean isPassword() {
            return code == ERAR_MISSING_PASSWORD || code == ERAR_BAD_PASSWORD;
        }
    }

    private static boolean loaded;

    private RarExtractor() { }

    /**
     * Unpacks `rar` below `outDir` (keeping its folders, never escaping outDir) and returns the
     * files written. Entries that are damaged or encrypted are skipped; it only throws when the
     * archive yields nothing at all.
     */
    static synchronized List<File> extract(File rar, File outDir, long maxBytes) throws IOException {
        if (!loaded) {
            try {
                System.loadLibrary("unrar_jni");
                loaded = true;
            } catch (UnsatisfiedLinkError e) {
                throw new IOException("RAR support is not available on this device", e);
            }
        }
        if (!outDir.isDirectory() && !outDir.mkdirs()) throw new IOException("Cannot create " + outDir);

        // UnRAR turns the archive path into a wide string with the C library's locale functions;
        // keep it plain ASCII so a name with accents or emoji can't make it unopenable.
        File source = rar;
        File temp = null;
        if (!isAscii(rar.getAbsolutePath())) {
            temp = new File(outDir, ".source_" + System.nanoTime() + ".rar");
            copy(rar, temp);
            source = temp;
        }
        int[] error = new int[1];
        String[] paths;
        try {
            paths = nativeExtract(source.getAbsolutePath(), outDir.getAbsolutePath(), maxBytes, error);
        } finally {
            if (temp != null) //noinspection ResultOfMethodCallIgnored
                temp.delete();
        }
        List<File> files = new ArrayList<>();
        if (paths != null) for (String p : paths) files.add(new File(p));
        if (files.isEmpty() && error[0] != 0) throw new RarException(error[0]);
        if (error[0] == ERR_TOO_LARGE) throw new RarException(ERR_TOO_LARGE);
        return files;
    }

    private static boolean isAscii(String s) {
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) > 0x7E || s.charAt(i) < 0x20) return false;
        return true;
    }

    private static void copy(File from, File to) throws IOException {
        try (InputStream is = new FileInputStream(from); OutputStream os = new FileOutputStream(to)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
        }
    }

    private static native String[] nativeExtract(String archive, String outDir, long maxBytes, int[] error);
}
