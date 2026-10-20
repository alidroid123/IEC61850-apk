package com.alidev.dfrtools.dfr;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Full IEEE C37.111 (COMTRADE 1991 / 1999 / 2013) reader used by the double-ended fault locator.
 *
 * Kept separate from DfrViewerActivity.ComtradeParser on purpose: that one only understands
 * ASCII .dat files and a fixed .cfg line layout, which is fine for plotting but not for fault
 * location - most relays (Siemens, ABB, NR, ...) write BINARY .dat files, and the locator also
 * needs the absolute start/trigger timestamps, per-channel phase/unit info and the
 * primary/secondary ratios to work in primary amps/volts. Pure Java (no Android imports) so it
 * can be exercised off-device.
 */
public class ComtradeRecord {

    public static class AnalogChannel {
        public int index;
        public String id = "", phase = "", ccbm = "", unit = "";
        public double a = 1, b = 0, primary = 0, secondary = 0;
        /** 'P' = values already primary, 'S' = values are secondary (scaled by primary/secondary). */
        public char ps = 'P';

        public String label() {
            return "#" + (index + 1) + " " + id + (unit.isEmpty() ? "" : " (" + unit + ")");
        }

        /** Multiplier from (a*x+b) in the cfg's unit to SI base units (V or A). */
        public double unitFactor() {
            String u = unit.trim();
            if (u.length() > 1) {
                char p = u.charAt(0);
                if (p == 'k' || p == 'K') return 1000.0;
                if (p == 'M' && u.length() > 1) return 1e6;
                if (p == 'm') return 0.001;
            }
            return 1.0;
        }

        /** True when the channel is recorded in secondary values and carries a usable ratio. */
        public boolean hasRatio() {
            return primary > 0 && secondary > 0;
        }

        /** Multiplier taking (a*x+b) to primary SI units, as far as the cfg itself allows. */
        public double primaryFactor() {
            double f = unitFactor();
            if (ps == 'S' && hasRatio()) f *= primary / secondary;
            return f;
        }

        /** Recorded in secondary values but the cfg has no ratio (COMTRADE 1991, or left blank). */
        public boolean isSecondaryWithoutRatio() {
            return ps == 'S' && !hasRatio();
        }

        public boolean looksLikeVoltage() {
            String u = unit.trim().toUpperCase(Locale.ROOT);
            if (u.endsWith("V")) return true;
            if (u.endsWith("A")) return false;
            String n = id.trim().toUpperCase(Locale.ROOT);
            return n.startsWith("U") || n.startsWith("V");
        }

        public boolean looksLikeCurrent() {
            String u = unit.trim().toUpperCase(Locale.ROOT);
            if (u.endsWith("A")) return true;
            if (u.endsWith("V")) return false;
            return id.trim().toUpperCase(Locale.ROOT).startsWith("I");
        }
    }

    public String sourceName = "";
    public String stationName = "", deviceId = "";
    public int revYear = 1991;
    public final List<AnalogChannel> analogs = new ArrayList<>();
    public final List<String> digitalNames = new ArrayList<>();

    /**
     * One digital (status) channel stored run-length: the value at sample 0 plus every change
     * afterwards. Mirrors the GAS engine's {init, changes:[[k,v]]} so the BI/BO analysis is
     * identical - the full per-sample array is never materialised.
     */
    public static final class DigitalChannel {
        public int init;
        /** Sample index of each change. */
        public int[] k = new int[0];
        /** New value (0/1) at each change. */
        public int[] v = new int[0];
    }

    /** One entry per digital channel (empty until the .dat is parsed). */
    public DigitalChannel[] digitalData = new DigitalChannel[0];
    /** Normal (de-energised) state per digital channel: 0, 1, or -1 when the cfg leaves it blank. */
    public int[] digitalNormal = new int[0];
    public double lineFrequency = 50;
    public double[] sampleRates = new double[0];
    public long[] rateEndSamples = new long[0];
    /** Naive (timezone-less) microseconds since 1970-01-01 of the first sample / trigger point. */
    public long startMicros = Long.MIN_VALUE, triggerMicros = Long.MIN_VALUE;
    public String startText = "", triggerText = "";
    public String fileType = "ASCII";
    public double timeMult = 1;

    public int sampleCount;
    /** Seconds since the first sample (the cfg start timestamp). */
    public double[] time = new double[0];
    /** [channel][sample], already a*x+b (cfg units, before unit/ratio scaling). */
    public float[][] values = new float[0][0];

    public int analogCount() { return analogs.size(); }

    /** Trigger position in seconds relative to the first sample, or NaN if unknown. */
    public double triggerSeconds() {
        if (startMicros == Long.MIN_VALUE || triggerMicros == Long.MIN_VALUE) return Double.NaN;
        return (triggerMicros - startMicros) / 1e6;
    }

    // ------------------------------------------------------------------
    // Loading
    // ------------------------------------------------------------------

    public static ComtradeRecord load(File cfgFile, File datFile) throws IOException {
        ComtradeRecord r = new ComtradeRecord();
        r.sourceName = cfgFile.getName();
        r.parseCfg(readLines(readAll(cfgFile)));
        r.parseDat(readAll(datFile));
        return r;
    }

    /** Loads from already-read cfg/dat bytes (e.g. a content:// URI stream). */
    public static ComtradeRecord load(byte[] cfgBytes, byte[] datBytes) throws IOException {
        ComtradeRecord r = new ComtradeRecord();
        r.parseCfg(readLines(cfgBytes));
        r.parseDat(datBytes);
        return r;
    }

    /** Finds the .dat that belongs to `cfg` (same base name, any extension case). */
    public static File findDatFor(File cfg) {
        File dir = cfg.getParentFile();
        String name = cfg.getName();
        int dot = name.lastIndexOf('.');
        String base = dot >= 0 ? name.substring(0, dot) : name;
        if (dir == null) return null;
        File[] files = dir.listFiles();
        if (files == null) return null;
        for (File f : files) {
            if (f.getName().equalsIgnoreCase(base + ".dat")) return f;
        }
        return null;
    }

    static byte[] readAll(File f) throws IOException {
        try (InputStream is = new FileInputStream(f)) {
            return readAll(is);
        }
    }

    static byte[] readAll(InputStream is) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
        return bos.toByteArray();
    }

    private static List<String> readLines(byte[] bytes) {
        String text = new String(bytes, StandardCharsets.ISO_8859_1);
        if (text.startsWith("ï»¿")) text = text.substring(3); // UTF-8 BOM
        List<String> lines = new ArrayList<>();
        for (String s : text.split("\r\n|\n|\r")) lines.add(s);
        return lines;
    }

    // ------------------------------------------------------------------
    // CFG
    // ------------------------------------------------------------------

    void parseCfg(List<String> raw) throws IOException {
        List<String> cfg = new ArrayList<>();
        for (String s : raw) if (!s.trim().isEmpty()) cfg.add(s.trim());
        if (cfg.size() < 6) throw new IOException("CFG too short");

        int li = 0;
        String[] l1 = splitCsv(cfg.get(li++));
        stationName = l1.length > 0 ? l1[0] : "";
        deviceId = l1.length > 1 ? l1[1] : "";
        if (l1.length > 2) {
            try { revYear = Integer.parseInt(l1[2].replaceAll("[^0-9]", "")); } catch (NumberFormatException ignored) { }
        }

        String[] counts = splitCsv(cfg.get(li++));
        int nA = 0, nD = 0;
        for (int i = 1; i < counts.length; i++) {
            String c = counts[i].toUpperCase(Locale.ROOT);
            int v = parseIntSafe(c.replaceAll("[^0-9]", ""));
            if (c.endsWith("A")) nA = v;
            else if (c.endsWith("D")) nD = v;
        }
        if (nA == 0 && counts.length >= 3) { // non-standard "TT,nA,nD" without suffixes
            nA = parseIntSafe(counts[1].replaceAll("[^0-9]", ""));
            nD = parseIntSafe(counts[2].replaceAll("[^0-9]", ""));
        }

        for (int i = 0; i < nA; i++) {
            String[] p = splitCsv(cfg.get(li++));
            AnalogChannel ch = new AnalogChannel();
            ch.index = i;
            ch.id = p.length > 1 ? p[1] : ("A" + (i + 1));
            ch.phase = p.length > 2 ? p[2] : "";
            ch.ccbm = p.length > 3 ? p[3] : "";
            ch.unit = p.length > 4 ? p[4] : "";
            ch.a = p.length > 5 ? parseDoubleSafe(p[5], 1) : 1;
            ch.b = p.length > 6 ? parseDoubleSafe(p[6], 0) : 0;
            if (p.length > 11) {
                ch.primary = parseDoubleSafe(p[10], 0);
                ch.secondary = parseDoubleSafe(p[11], 0);
            }
            if (p.length > 12 && !p[12].isEmpty()) {
                ch.ps = Character.toUpperCase(p[12].charAt(0)) == 'S' ? 'S' : 'P';
            } else {
                ch.ps = 'P';
            }
            analogs.add(ch);
        }
        digitalNormal = new int[nD];
        for (int i = 0; i < nD; i++) {
            String[] p = splitCsv(cfg.get(li++));
            digitalNames.add(p.length > 1 ? p[1] : ("D" + (i + 1)));
            // Normal state = last field (y), per COMTRADE; blank/non-binary -> unknown (-1).
            digitalNormal[i] = parseBinaryState(p.length > 0 ? p[p.length - 1] : "");
        }

        lineFrequency = parseDoubleSafe(cfg.get(li++), 50);
        if (lineFrequency <= 0) lineFrequency = 50;

        int nRates = parseIntSafe(splitCsv(cfg.get(li++))[0]);
        int rateLines = Math.max(1, nRates);
        sampleRates = new double[rateLines];
        rateEndSamples = new long[rateLines];
        for (int i = 0; i < rateLines; i++) {
            String[] p = splitCsv(cfg.get(li++));
            sampleRates[i] = p.length > 0 ? parseDoubleSafe(p[0], 0) : 0;
            rateEndSamples[i] = p.length > 1 ? (long) parseDoubleSafe(p[1], 0) : 0;
        }

        startText = cfg.get(li++);
        triggerText = cfg.get(li++);
        startMicros = parseTimestamp(startText, revYear);
        triggerMicros = parseTimestamp(triggerText, revYear);

        fileType = li < cfg.size() ? cfg.get(li++).trim().toUpperCase(Locale.ROOT) : "ASCII";
        if (li < cfg.size()) {
            double tm = parseDoubleSafe(splitCsv(cfg.get(li))[0], 1);
            timeMult = tm > 0 ? tm : 1;
        }
    }

    // ------------------------------------------------------------------
    // DAT
    // ------------------------------------------------------------------

    void parseDat(byte[] dat) throws IOException {
        int nA = analogs.size();
        int nD = digitalNames.size();
        double[] stamps;
        // Digital channels accumulated run-length (value at 0 + changes), like the GAS parser.
        int[] dInit = new int[nD];
        java.util.Arrays.fill(dInit, -1);
        @SuppressWarnings("unchecked")
        List<int[]>[] dChg = new List[nD];
        for (int c = 0; c < nD; c++) dChg[c] = new ArrayList<>();
        if (fileType.startsWith("ASCII")) {
            List<String> lines = readLines(dat);
            List<float[]> rows = new ArrayList<>(lines.size());
            List<Double> ts = new ArrayList<>(lines.size());
            float[] last = new float[nA];
            for (String line : lines) {
                String s = line.trim();
                if (s.isEmpty()) continue;
                String[] p = s.contains(",") ? s.split(",", -1) : s.split("\\s+");
                if (p.length < 2 + nA) continue;
                ts.add(parseDoubleSafe(p[1], Double.NaN));
                float[] row = new float[nA];
                for (int j = 0; j < nA; j++) {
                    String v = p[2 + j].trim();
                    double x = v.isEmpty() ? Double.NaN : parseDoubleSafe(v, Double.NaN);
                    // 99999 is the 1999 "missing value" marker; blank is the 2013 one.
                    if (Double.isNaN(x) || (revYear < 2013 && x == 99999)) row[j] = last[j];
                    else row[j] = (float) (x * analogs.get(j).a + analogs.get(j).b);
                    last[j] = row[j];
                }
                int kk = rows.size();
                for (int c = 0; c < nD; c++) {
                    int col = 2 + nA + c;
                    if (col >= p.length) continue;
                    int dv = parseBinaryState(p[col]);
                    if (dv >= 0) digAccumulate(dInit, dChg, c, kk, dv);
                }
                rows.add(row);
            }
            sampleCount = rows.size();
            values = new float[nA][sampleCount];
            stamps = new double[sampleCount];
            for (int k = 0; k < sampleCount; k++) {
                float[] row = rows.get(k);
                for (int j = 0; j < nA; j++) values[j][k] = row[j];
                stamps[k] = ts.get(k);
            }
        } else {
            int width;
            if (fileType.startsWith("BINARY32")) width = 4;
            else if (fileType.startsWith("FLOAT32")) width = 4;
            else if (fileType.startsWith("BINARY")) width = 2;
            else throw new IOException("Unsupported DAT file type: " + fileType);
            boolean isFloat = fileType.startsWith("FLOAT32");
            int digitalWords = (nD + 15) / 16;
            int recSize = 8 + width * nA + 2 * digitalWords;
            sampleCount = dat.length / recSize;
            values = new float[nA][sampleCount];
            stamps = new double[sampleCount];
            ByteBuffer bb = ByteBuffer.wrap(dat).order(ByteOrder.LITTLE_ENDIAN);
            float[] last = new float[nA];
            for (int k = 0; k < sampleCount; k++) {
                int base = k * recSize;
                long stamp = bb.getInt(base + 4) & 0xFFFFFFFFL;
                stamps[k] = stamp == 0xFFFFFFFFL ? Double.NaN : stamp;
                for (int j = 0; j < nA; j++) {
                    int off = base + 8 + j * width;
                    double x;
                    boolean missing;
                    if (width == 2) {
                        short s = bb.getShort(off);
                        missing = s == Short.MIN_VALUE;
                        x = s;
                    } else if (isFloat) {
                        float f = bb.getFloat(off);
                        missing = Float.isNaN(f);
                        x = f;
                    } else {
                        int v = bb.getInt(off);
                        missing = v == Integer.MIN_VALUE;
                        x = v;
                    }
                    AnalogChannel ch = analogs.get(j);
                    values[j][k] = missing ? last[j] : (float) (x * ch.a + ch.b);
                    last[j] = values[j][k];
                }
                // Digital: 16-bit little-endian words after the analog block, bit 0 = first channel.
                int digBase = base + 8 + width * nA;
                for (int c = 0; c < nD; c++) {
                    int word = bb.getShort(digBase + 2 * (c >> 4)) & 0xFFFF;
                    digAccumulate(dInit, dChg, c, k, (word >> (c & 15)) & 1);
                }
            }
        }
        if (sampleCount < 2) throw new IOException("DAT has no samples");
        digitalData = new DigitalChannel[nD];
        for (int c = 0; c < nD; c++) {
            DigitalChannel dc = new DigitalChannel();
            dc.init = dInit[c] < 0 ? 0 : dInit[c];
            List<int[]> chg = dChg[c];
            dc.k = new int[chg.size()];
            dc.v = new int[chg.size()];
            for (int j = 0; j < chg.size(); j++) { dc.k[j] = chg.get(j)[0]; dc.v[j] = chg.get(j)[1]; }
            digitalData[c] = dc;
        }
        buildTimeAxis(stamps);
    }

    /**
     * Sample times from the cfg's sample-rate table when it has one (the normal case), falling
     * back to the per-sample timestamp column (rate "0" = timestamps are authoritative).
     */
    private void buildTimeAxis(double[] stamps) {
        time = new double[sampleCount];
        boolean ratesUsable = sampleRates.length > 0;
        for (double r : sampleRates) if (r <= 0) { ratesUsable = false; break; }
        if (ratesUsable) {
            double t = 0;
            int seg = 0;
            for (int k = 0; k < sampleCount; k++) {
                time[k] = t;
                long n = k + 1L;
                while (seg < sampleRates.length - 1 && n >= rateEndSamples[seg]) seg++;
                t += 1.0 / sampleRates[seg];
            }
            return;
        }
        double t0 = Double.isNaN(stamps[0]) ? 0 : stamps[0];
        double prev = 0;
        for (int k = 0; k < sampleCount; k++) {
            double s = stamps[k];
            time[k] = Double.isNaN(s) ? prev : (s - t0) * timeMult * 1e-6;
            prev = time[k];
        }
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static String[] splitCsv(String line) {
        String[] p = line.split(",", -1);
        for (int i = 0; i < p.length; i++) p[i] = p[i].trim();
        return p;
    }

    private static int parseIntSafe(String s) {
        try { return Integer.parseInt(s.trim()); } catch (Exception e) { return (int) parseDoubleSafe(s, 0); }
    }

    /** Returns 0 or 1 when the text is exactly that, else -1 (unknown). */
    private static int parseBinaryState(String s) {
        try { int v = Integer.parseInt(s.trim()); return v == 0 || v == 1 ? v : -1; } catch (Exception e) { return -1; }
    }

    /** Run-length accumulator shared by the ASCII and binary digital readers. */
    private static void digAccumulate(int[] dInit, List<int[]>[] dChg, int c, int k, int val) {
        if (dInit[c] < 0) { dInit[c] = val; return; }
        List<int[]> ch = dChg[c];
        int last = ch.isEmpty() ? dInit[c] : ch.get(ch.size() - 1)[1];
        if (val != last) ch.add(new int[]{k, val});
    }

    private static double parseDoubleSafe(String s, double def) {
        try { return Double.parseDouble(s.trim()); } catch (Exception e) { return def; }
    }

    /**
     * Parses "dd/mm/yyyy,hh:mm:ss.ssssss" (1999/2013) or "mm/dd/yy,hh:mm:ss.ssssss" (1991) into
     * naive microseconds since the epoch. Day/month are swapped automatically when the declared
     * order is impossible (some recorders write mm/dd in a 1999 file). Returns Long.MIN_VALUE when
     * unparseable - absolute time is only needed for the optional time-synchronised cross-check.
     */
    static long parseTimestamp(String text, int revYear) {
        try {
            String[] dt = text.split(",");
            String[] d = dt[0].trim().split("[/\\-.]");
            int p0 = Integer.parseInt(d[0].trim()), p1 = Integer.parseInt(d[1].trim());
            int year = Integer.parseInt(d[2].trim());
            int day, month;
            if (revYear < 1999) { month = p0; day = p1; } else { day = p0; month = p1; }
            if (month > 12 && day <= 12) { int tmp = month; month = day; day = tmp; }
            if (year < 100) year += year < 70 ? 2000 : 1900;
            String[] t = dt[1].trim().split(":");
            int hh = Integer.parseInt(t[0].trim());
            int mm = Integer.parseInt(t[1].trim());
            double ss = Double.parseDouble(t[2].trim());
            long wholeSec = (long) Math.floor(ss);
            long frac = Math.round((ss - wholeSec) * 1e6);
            return (daysFromCivil(year, month, day) * 86400L + hh * 3600L + mm * 60L + wholeSec) * 1_000_000L + frac;
        } catch (Exception e) {
            return Long.MIN_VALUE;
        }
    }

    /** Days since 1970-01-01 for a proleptic Gregorian date (H. Hinnant's algorithm). */
    private static long daysFromCivil(int y, int m, int d) {
        y -= m <= 2 ? 1 : 0;
        long era = (y >= 0 ? y : y - 399) / 400;
        long yoe = y - era * 400;
        long doy = (153L * (m + (m > 2 ? -3 : 9)) + 2) / 5 + d - 1;
        long doe = yoe * 365 + yoe / 4 - yoe / 100 + doy;
        return era * 146097 + doe - 719468;
    }

    @Override
    public String toString() {
        return String.format(Locale.US, "%s [%s] %dA/%dD %d samples %s",
                stationName, fileType, analogs.size(), digitalNames.size(), sampleCount, Arrays.toString(sampleRates));
    }
}
