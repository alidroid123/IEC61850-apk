package com.alidev.dfrtools.dfr;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Double-ended (two-terminal) transmission-line fault locator working on one COMTRADE record
 * from the line relay at each end (GI A and GI B).
 *
 * Primary method - unsynchronised two-ended, negative sequence (IEEE C37.114-2014 §6.2;
 * Novosel et al., "Unsynchronized two-terminal fault location estimation", IEEE Trans. PWRD
 * 11(1), 1996; Tziouvaras, Roberts, Benmouyal, "New multi-ended fault location design for two-
 * or three-terminal lines", IEE DPSP 2001). Relay DFR clocks are rarely synchronised to better
 * than a few ms (1 ms = 18 deg at 50 Hz), so the method only relies on the fact that the fault
 * point has ONE voltage: walking the line model from each end must reach the same |V2| at the
 * fault. That needs no common time reference, and with the negative sequence it is also immune
 * to pre-fault load, fault resistance and zero-sequence mutual coupling. Balanced (3-phase)
 * faults have no negative sequence and fall back to the positive sequence.
 *
 * Everything else is cross-checks: positive-sequence two-ended, time-synchronised two-ended
 * (valid only when both relays are GPS/PTP-synchronised), single-ended Takagi from each end,
 * fault-type classification at the fault point, fault resistance and per-end direction.
 *
 * Pure Java on purpose (no Android imports) so it can be verified off-device.
 */
public class DoubleEndedFaultLocator {

    // ------------------------------------------------------------------
    // Complex numbers
    // ------------------------------------------------------------------

    public static final class Cx {
        public final double re, im;
        public static final Cx ZERO = new Cx(0, 0);
        public static final Cx ONE = new Cx(1, 0);
        static final Cx A = polar(1, 2 * Math.PI / 3);
        static final Cx A2 = polar(1, -2 * Math.PI / 3);

        public Cx(double re, double im) { this.re = re; this.im = im; }
        public static Cx polar(double mag, double ang) { return new Cx(mag * Math.cos(ang), mag * Math.sin(ang)); }
        public Cx add(Cx o) { return new Cx(re + o.re, im + o.im); }
        public Cx sub(Cx o) { return new Cx(re - o.re, im - o.im); }
        public Cx mul(Cx o) { return new Cx(re * o.re - im * o.im, re * o.im + im * o.re); }
        public Cx mul(double k) { return new Cx(re * k, im * k); }
        public Cx div(Cx o) {
            double d = o.re * o.re + o.im * o.im;
            if (d == 0) return new Cx(Double.NaN, Double.NaN);
            return new Cx((re * o.re + im * o.im) / d, (im * o.re - re * o.im) / d);
        }
        public Cx conj() { return new Cx(re, -im); }
        public Cx neg() { return new Cx(-re, -im); }
        public double abs() { return Math.hypot(re, im); }
        public double arg() { return Math.atan2(im, re); }
        public Cx sqrt() { return polar(Math.sqrt(abs()), arg() / 2); }
        public Cx cosh() { return new Cx(Math.cosh(re) * Math.cos(im), Math.sinh(re) * Math.sin(im)); }
        public Cx sinh() { return new Cx(Math.sinh(re) * Math.cos(im), Math.cosh(re) * Math.sin(im)); }
    }

    // ------------------------------------------------------------------
    // Inputs
    // ------------------------------------------------------------------

    /** Per-km line constants (primary ohm/km, uS/km) + length. Z0 and B1 are optional. */
    public static class LineParams {
        public double lengthKm, r1, x1, r0, x0, b1;

        public boolean hasZ0() { return r0 > 0 || x0 > 0; }
        public boolean distributed() { return b1 > 0; }
        Cx z1PerKm() { return new Cx(r1, x1); }
        Cx z0PerKm() { return new Cx(r0, x0); }
        Cx z1Total() { return z1PerKm().mul(lengthKm); }

        /** Copy with another Z1 per km (same length, Z0 and B1). */
        LineParams withZ1(Cx z1) {
            LineParams p = new LineParams();
            p.lengthKm = lengthKm; p.r1 = z1.re; p.x1 = z1.im; p.r0 = r0; p.x0 = x0; p.b1 = b1;
            return p;
        }
        Cx z0Total() { return z0PerKm().mul(lengthKm); }
    }

    /** Which analog channel carries which phase quantity, plus per-end corrections. */
    public static class ChannelMap {
        public int va = -1, vb = -1, vc = -1, ia = -1, ib = -1, ic = -1;
        public boolean invertCurrent;
        /** Extra multipliers on top of the cfg's own scaling (e.g. VT/CT ratio when the cfg has none). */
        public double vMult = 1, iMult = 1;

        public int[] volt() { return new int[]{va, vb, vc}; }
        public int[] curr() { return new int[]{ia, ib, ic}; }

        public boolean complete(int analogCount) {
            for (int c : new int[]{va, vb, vc, ia, ib, ic}) if (c < 0 || c >= analogCount) return false;
            return true;
        }

        public ChannelMap copy() {
            ChannelMap m = new ChannelMap();
            m.va = va; m.vb = vb; m.vc = vc; m.ia = ia; m.ib = ib; m.ic = ic;
            m.invertCurrent = invertCurrent; m.vMult = vMult; m.iMult = iMult;
            return m;
        }
    }

    // ------------------------------------------------------------------
    // Outputs
    // ------------------------------------------------------------------

    public static class Phasors {
        public final Cx[] v = new Cx[3], i = new Cx[3];
        public Cx v0, v1, v2, i0, i1, i2;

        void computeSequences() {
            v0 = seq0(v); v1 = seq1(v); v2 = seq2(v);
            i0 = seq0(i); i1 = seq1(i); i2 = seq2(i);
        }

        Cx v(int s) { return s == 0 ? v0 : s == 1 ? v1 : v2; }
        Cx i(int s) { return s == 0 ? i0 : s == 1 ? i1 : i2; }
    }

    public static class EndSummary {
        public String station = "", triggerText = "", fileType = "";
        public double fs, f0;
        public int samplesPerCycle;
        public double inceptionTime;
        /** "I" = from currents, "V" = from voltages, "T" = fell back to the cfg trigger time. */
        public String inceptionSource = "T";
        public boolean hasPrefault;
        public Phasors pre, flt;
        /** Angle (rad) all displayed phasors of this end are rotated by, so Va(pre-fault) sits at 0 deg. */
        public double displayRefAngle;
        public boolean secondaryWithoutRatio;
    }

    public static class Warning {
        public final String code;
        public final Object[] args;
        Warning(String code, Object... args) { this.code = code; this.args = args; }
    }

    public static class Result {
        /** Non-null when the analysis could not run at all (see ERR_* codes). */
        public String error;
        public Object[] errorArgs = new Object[0];

        /** Fault position as a fraction of the line from end A (primary method). */
        public double m = Double.NaN;
        public double distA = Double.NaN, distB = Double.NaN;
        public double lengthKm;
        /** The other in-range root of the magnitude equation, if the equation had two. */
        public double mAlt = Double.NaN;
        /** Sequence used by the primary method: 2 = negative, 1 = positive (balanced fault). */
        public int sequenceUsed = 2;
        /** Positive sequence was used as superimposed (fault minus pre-fault) quantities. */
        public boolean superimposed;
        public boolean distributedModel;

        public double mPos = Double.NaN;
        public double mSync = Double.NaN;
        public boolean syncTrusted;
        public double takagiA = Double.NaN, takagiB = Double.NaN; // km from their own end

        /** "AG","BG","CG","AB","BC","CA","ABG","BCG","CAG","ABC" or "?" */
        public String faultType = "?";
        /** Phase-angle offset between the two records' phasor frames (deg), from the primary method. */
        public double syncAngleDeg = Double.NaN;
        /** Absolute-time difference of the two detected inceptions (ms), from the cfg timestamps. */
        public double inceptionDiffMs = Double.NaN;
        public double rf = Double.NaN;
        /** +1 forward (into the line), -1 reverse, 0 undetermined. */
        public int dirA, dirB;
        public double offsetCycles;
        public double instability;
        /** Number of consecutive fault windows whose estimates were combined (median). */
        public int windowsUsed = 1;
        /** Half the min-max range of those estimates, km (NaN with a single window). */
        public double spreadKm = Double.NaN;

        public EndSummary endA = new EndSummary(), endB = new EndSummary();
        public double[] profileKm, profileA, profileB; // |V_F| (kV) walked from each end
        public final List<Warning> warnings = new ArrayList<>();

        /** Circular spread (half range) of the sync angle over the fault windows used, degrees. */
        public double syncAngleSpreadDeg = Double.NaN;
        /**
         * Sync angle from the pre-fault load flow alone: A's pre-fault V1/I1 walked over the whole
         * line must give B's pre-fault V1. Independent of the fault equation (NaN without pre-fault).
         */
        public double syncAnglePreDeg = Double.NaN;
        /** Z1 per km measured from the pre-fault load flow, rotated by the fault sync angle (NaN if unusable). */
        public double z1MeasR = Double.NaN, z1MeasX = Double.NaN;
        /** Relative 1-sigma uncertainty of that measurement (VT/CT + sync angle errors over the line voltage drop). */
        public double z1MeasUnc = Double.NaN;

        /**
         * Record time shift (B time = A time + tau), s, from the sync angle of the main method:
         * tau = delta / omega + k * T, k picking the period nearest the inception alignment.
         */
        public double tauAuto = Double.NaN;
        /** Same from the pre-fault sync angle, and from the voltage-only V1/V0 angle match, s. */
        public double tauPre = Double.NaN, tauVoltage = Double.NaN;
        /** Shift that lines up the detected inceptions / the cfg timestamps (NaN if unknown), s. */
        public double tauInception = Double.NaN, tauTimestamp = Double.NaN;
        /** Fixed reference instant at end A (record time, s) and A's fault current there (A rms). */
        public double alignRefTimeA = Double.NaN, alignRefCurrentA = Double.NaN;
        /** Vectors and synchronised location at tauAuto (the screen may move it with the slider). */
        public Alignment alignment;
        /** Live handle on both records for re-aligning at another tau; null for a reloaded result. */
        public transient Session session;
    }

    /** Both ends' voltage/current vectors at one physical instant, given a record time shift. */
    public static class Alignment {
        public double tau = Double.NaN;
        public boolean valid;
        public Phasors a, b;
        /** Angle of B minus angle of A, degrees. */
        public double dV1Deg = Double.NaN, dV0Deg = Double.NaN, dV2Deg = Double.NaN;
        /** V0 is large enough at both ends to take part in the match (earth faults). */
        public boolean v0Used;
        /** Two-ended location (fraction from A) solved with these time-aligned vectors. */
        public double m = Double.NaN;
    }

    /**
     * Keeps both prepared records after analyze() so the screen can re-align them at any time
     * shift (the slider) without re-parsing or re-running the whole analysis.
     */
    public static class Session {
        private final End a, b;
        private final LineModel lm;
        private final int s;
        private final double off, L;
        /** Same physical instant by detected inception / by the cfg timestamps (NaN if unknown). */
        public final double tauInception, tauTimestamp;
        /** Shift range for which B's window still fits inside its record. */
        public final double tauMin, tauMax;
        public final double period;
        /** Fixed reference instant at end A (record time, s) and the fault current there (A rms). */
        public final double refTimeA, refCurrentA;
        private final int k0A;
        private final Phasors pa;

        Session(End a, End b, LineModel lm, int s, double off, double L) {
            this.a = a;
            this.b = b;
            this.lm = lm;
            this.s = s;
            this.off = off;
            this.L = L;
            period = a.T;
            tauInception = b.t[b.inc] - a.t[a.inc];
            tauTimestamp = a.rec.startMicros != Long.MIN_VALUE && b.rec.startMicros != Long.MIN_VALUE
                    ? (a.rec.startMicros - b.rec.startMicros) / 1e6 : Double.NaN;
            k0A = peakCurrentWindow(a, Math.min(a.inc + (int) Math.round(off * a.N), a.n - a.N));
            double tA0 = a.t[k0A];
            pa = phasorsAt(a, k0A, tA0);
            refTimeA = tA0;
            refCurrentA = pa == null ? Double.NaN : maxI(pa);
            tauMin = b.t[0] - tA0;
            tauMax = b.t[Math.max(0, b.n - b.N - 1)] - tA0;
        }

        /**
         * End A is the fixed reference and B is the record that gets shifted, so the reference
         * instant is where A's fault current (fundamental, so the DC offset does not count) is
         * highest - searched from inception until the current collapses at breaker opening.
         */
        private static int peakCurrentWindow(End e, int fallback) {
            int step = Math.max(1, e.N / 8);
            int last = Math.min(e.inc + 15 * e.N, e.n - e.N);
            List<Integer> ks = new ArrayList<>();
            List<Phasors> phs = new ArrayList<>();
            double peak = 0;
            for (int k = e.inc; k <= last; k += step) {
                Phasors ph = phasorsAt(e, k, 0);
                if (ph == null) break;
                double mag = maxI(ph);
                if (mag < 0.3 * peak) break; // breaker has opened
                peak = Math.max(peak, mag);
                ks.add(k);
                phs.add(ph);
            }
            // Only a window that is steady a quarter cycle later counts: one straddling the
            // inception or the breaker opening fits a cut waveform and can read falsely high.
            int lag = Math.max(1, (e.N / 4 + step - 1) / step);
            double bestStable = 0;
            boolean[] stable = new boolean[ks.size()];
            for (int i = 0; i + lag < ks.size(); i++) {
                Phasors x = phs.get(i), y = phs.get(i + lag);
                stable[i] = relChange(x, y) < 0.05 && maxI(y) >= 0.95 * maxI(x);
                if (stable[i]) bestStable = Math.max(bestStable, maxI(x));
            }
            for (int i = 0; i < ks.size(); i++) {
                if (stable[i] && maxI(phs.get(i)) >= 0.99 * bestStable) return ks.get(i);
            }
            return fallback;
        }

        private Phasors bAt(double tau) {
            double tB0 = a.t[k0A] + tau;
            if (tau < tauMin || tau > tauMax) return null;
            int lo = 0, hi = b.n - 1;
            while (lo < hi) { // first sample at or after tB0
                int mid = (lo + hi) >>> 1;
                if (b.t[mid] < tB0) lo = mid + 1; else hi = mid;
            }
            return phasorsAt(b, lo, tB0);
        }

        /** Voltage-only mismatch: 1 - cos of the V1 (and, when present, V0) angle differences. */
        private double mismatch(Phasors pb) {
            double j = 1 - Math.cos(pb.v1.arg() - pa.v1.arg());
            if (v0Significant(pa, pb)) j += 1 - Math.cos(pb.v0.arg() - pa.v0.arg());
            return j;
        }

        private static boolean v0Significant(Phasors pa, Phasors pb) {
            double ref = Math.max(pa.v1.abs(), pb.v1.abs());
            return Math.min(pa.v0.abs(), pb.v0.abs()) > 0.02 * ref;
        }

        /** Coarse shift both records agree on: detected inceptions, else the cfg timestamps. */
        private double coarseTau(boolean inceptionsFromSignals) {
            return inceptionsFromSignals || Double.isNaN(tauTimestamp) ? tauInception : tauTimestamp;
        }

        /**
         * Record time shift implied by a sync angle (rad, rotation taking B's record-frame phasors
         * into A's). Phasors are referenced to each record's own t = 0, so the angle is omega*tau
         * modulo one period; the period nearest the coarse inception alignment is taken. The
         * off-nominal frequency term cancels because both ends are read at the same instant.
         */
        public double tauFromSyncAngle(double deltaRad, boolean inceptionsFromSignals) {
            if (Double.isNaN(deltaRad)) return Double.NaN;
            double t = deltaRad / (2 * Math.PI) * period;
            return t + Math.round((coarseTau(inceptionsFromSignals) - t) / period) * period;
        }

        /**
         * "Voltage only" synchronisation: within half a cycle either side of the shift that lines
         * the two detected fault inceptions up (so both windows really are the same moment of the
         * fault), finds the shift whose V1 and V0 angles agree best between the two records.
         * Biased by the real V1 angle across the line (load angle + fault drop), so it is only
         * offered as a manual alternative to tauFromSyncAngle().
         */
        public double autoAlignVoltage(boolean inceptionsFromSignals) {
            double c = coarseTau(inceptionsFromSignals);
            double lo = Math.max(tauMin, c - period / 2), hi = Math.min(tauMax, c + period / 2);
            if (!(hi > lo)) return Math.max(tauMin, Math.min(tauMax, c));
            int steps = 240;
            double best = c, bestJ = Double.MAX_VALUE;
            for (int k = 0; k <= steps; k++) {
                double tau = lo + (hi - lo) * k / steps;
                Phasors pb = bAt(tau);
                if (pb == null) continue;
                double j = mismatch(pb);
                if (j < bestJ) { bestJ = j; best = tau; }
            }
            double step = (hi - lo) / steps, x = Math.max(lo, best - step), y = Math.min(hi, best + step);
            double gr = (Math.sqrt(5) - 1) / 2;
            for (int it = 0; it < 50; it++) {
                double p = y - gr * (y - x), q = x + gr * (y - x);
                Phasors pp = bAt(p), pq = bAt(q);
                double jp = pp == null ? Double.MAX_VALUE : mismatch(pp), jq = pq == null ? Double.MAX_VALUE : mismatch(pq);
                if (jp < jq) y = q; else x = p;
            }
            return (x + y) / 2;
        }

        /** Vectors of both ends at shift tau, plus the time-synchronised two-ended location. */
        public Alignment alignAt(double tau) {
            Alignment al = new Alignment();
            al.tau = tau;
            Phasors pb = bAt(tau);
            if (pa == null || pb == null) return al;
            al.valid = true;
            al.a = pa;
            al.b = pb;
            al.dV1Deg = wrapDeg(Math.toDegrees(pb.v1.arg() - pa.v1.arg()));
            al.dV0Deg = wrapDeg(Math.toDegrees(pb.v0.arg() - pa.v0.arg()));
            al.dV2Deg = wrapDeg(Math.toDegrees(pb.v2.arg() - pa.v2.arg()));
            al.v0Used = v0Significant(pa, pb);
            final Cx vA = pa.v(s), iA = pa.i(s), vB = pb.v(s), iB = pb.i(s);
            al.m = argMin(m -> lm.walk(vA, iA, m * L).sub(lm.walk(vB, iB, (1 - m) * L)).abs(), -0.25, 1.25, 600);
            return al;
        }
    }

    public static final String ERR_MAPPING_A = "MAPPING_A", ERR_MAPPING_B = "MAPPING_B";
    public static final String ERR_RATE_A = "RATE_A", ERR_RATE_B = "RATE_B";
    public static final String ERR_SHORT_A = "SHORT_A", ERR_SHORT_B = "SHORT_B";
    public static final String ERR_LINE = "LINE";
    public static final String ERR_NO_SIGNAL = "NO_SIGNAL";

    // ------------------------------------------------------------------
    // Channel auto-mapping
    // ------------------------------------------------------------------

    // e.g. IA, Ia, IL1, I_L1, IR, UL1E, U L1-E, VAN, Va, VR. Trailing N/E/G = to-neutral/earth.
    private static final Pattern PHASE_NAME = Pattern.compile("^(I|U|V)(?:PH)?(A|B|C|R|S|T|L1|L2|L3|1|2|3)(?:N|E|G)?$");

    // Derived channels some recorders add next to the sampled waveforms (RMS, magnitude, angle,
    // frequency, power, sequence values) - the locator only ever uses instantaneous samples.
    // No bare "...ANG" suffix rule: plenty of substation names end in it (Pemalang, Batang).
    private static final Pattern DERIVED_NAME = Pattern.compile(
            "(^|[^A-Z])(RMS|TRMS|MAG|MAGN|ABS|AMPL|ANG|ANGLE|PHI|ARG|FREQ|FRQ|HZ|PWR|POWER|SEQ|AVG|MEAN)([^A-Z]|$)|(RMS|MAG)$");

    /** True for a sampled (instantaneous) voltage/current waveform channel. */
    public static boolean isInstantaneous(ComtradeRecord.AnalogChannel ch) {
        String u = ch.unit.trim().toUpperCase(Locale.ROOT);
        if (u.contains("DEG") || u.contains("RAD") || u.equals("HZ") || u.equals("%") || u.endsWith("W")
                || u.endsWith("VAR") || u.equals("VA") || u.equals("KVA") || u.equals("MVA") || u.contains("OHM")) {
            return false;
        }
        return !DERIVED_NAME.matcher(ch.id.toUpperCase(Locale.ROOT)).find();
    }

    /** Rank 0 = A/B/C or L1/L2/L3 naming, 1 = R/S/T naming, 2 = cfg "ph" field only, 3 = bare digits. */
    private static int[] classifyChannel(ComtradeRecord.AnalogChannel ch) {
        if (!isInstantaneous(ch)) return null;
        boolean isV = ch.looksLikeVoltage(), isI = ch.looksLikeCurrent();
        String name = ch.id.toUpperCase(Locale.ROOT);
        String[] tokens = name.split("[^A-Z0-9]+");
        List<String> candidates = new ArrayList<>();
        candidates.add(name.replaceAll("[^A-Z0-9]", ""));
        for (int t = 0; t < tokens.length; t++) {
            candidates.add(tokens[t]);
            if (t + 1 < tokens.length) candidates.add(tokens[t] + tokens[t + 1]);
            if (t + 2 < tokens.length) candidates.add(tokens[t] + tokens[t + 1] + tokens[t + 2]);
        }
        for (String c : candidates) {
            Matcher m = PHASE_NAME.matcher(c);
            if (!m.matches()) continue;
            boolean qV = !m.group(1).equals("I");
            if ((qV && isI && !isV) || (!qV && isV && !isI)) continue; // name and unit disagree
            String p = m.group(2);
            int phase = phaseIndex(p);
            int rank = p.equals("R") || p.equals("S") || p.equals("T") ? 1 : p.length() == 1 && Character.isDigit(p.charAt(0)) ? 3 : 0;
            return new int[]{qV ? 1 : 0, phase, rank};
        }
        String ph = ch.phase.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
        if (ph.length() > 1 && (ph.endsWith("N") || ph.endsWith("E") || ph.endsWith("G"))) ph = ph.substring(0, ph.length() - 1);
        if (ph.startsWith("PH")) ph = ph.substring(2);
        int phase = phaseIndex(ph);
        if (phase >= 0 && (isV || isI)) return new int[]{isV ? 1 : 0, phase, 2};
        return null;
    }

    private static int phaseIndex(String p) {
        switch (p) {
            case "A": case "R": case "L1": case "1": return 0;
            case "B": case "S": case "L2": case "2": return 1;
            case "C": case "T": case "L3": case "3": return 2;
            default: return -1;
        }
    }

    /** Best-effort Va/Vb/Vc/Ia/Ib/Ic detection from channel names, "ph" fields and units. */
    public static ChannelMap autoMap(ComtradeRecord rec) {
        int n = rec.analogCount();
        int[][] cls = new int[n][];
        for (int k = 0; k < n; k++) cls[k] = classifyChannel(rec.analogs.get(k));
        int[][] chosen = new int[2][3]; // [qty: 0=I,1=V][phase]
        for (int q = 0; q < 2; q++) {
            int[] best = {-1, -1, -1};
            boolean done = false;
            for (int rank = 0; rank <= 3 && !done; rank++) {
                int[] found = {-1, -1, -1};
                for (int k = 0; k < n; k++) {
                    if (cls[k] == null || cls[k][0] != q || cls[k][2] != rank) continue;
                    if (found[cls[k][1]] < 0) found[cls[k][1]] = k;
                }
                if (found[0] >= 0 && found[1] >= 0 && found[2] >= 0) { best = found; done = true; }
            }
            if (!done) { // patch together whatever exists, best rank first
                for (int rank = 0; rank <= 3; rank++) {
                    for (int k = 0; k < n; k++) {
                        if (cls[k] == null || cls[k][0] != q || cls[k][2] != rank) continue;
                        if (best[cls[k][1]] < 0) best[cls[k][1]] = k;
                    }
                }
            }
            chosen[q] = best;
        }
        ChannelMap m = new ChannelMap();
        m.ia = chosen[0][0]; m.ib = chosen[0][1]; m.ic = chosen[0][2];
        m.va = chosen[1][0]; m.vb = chosen[1][1]; m.vc = chosen[1][2];
        return m;
    }

    // ------------------------------------------------------------------
    // Per-end signal preparation
    // ------------------------------------------------------------------

    private static class End {
        ComtradeRecord rec;
        double[][] v = new double[3][], i = new double[3][];
        double[] t;
        int n, N;
        double fs, f0, w, T;
        int inc;
        String incSource;
        boolean hasPre;
        Phasors pre;
    }

    private static End prepare(ComtradeRecord rec, ChannelMap map, boolean isA, Result res) {
        End e = new End();
        e.rec = rec;
        e.t = rec.time;
        e.n = rec.sampleCount;
        e.f0 = rec.lineFrequency > 0 ? rec.lineFrequency : 50;
        e.w = 2 * Math.PI * e.f0;
        e.T = 1.0 / e.f0;
        double[] dts = new double[Math.max(1, e.n - 1)];
        for (int k = 1; k < e.n; k++) dts[k - 1] = e.t[k] - e.t[k - 1];
        java.util.Arrays.sort(dts);
        double dt = dts[dts.length / 2];
        e.fs = dt > 0 ? 1.0 / dt : 0;
        e.N = (int) Math.round(e.fs / e.f0);
        if (e.N < 8) {
            res.error = isA ? ERR_RATE_A : ERR_RATE_B;
            res.errorArgs = new Object[]{e.fs};
            return null;
        }
        if (e.n < 3 * e.N) {
            res.error = isA ? ERR_SHORT_A : ERR_SHORT_B;
            return null;
        }
        int[] vc = map.volt(), ic = map.curr();
        boolean secNoRatio = false;
        for (int p = 0; p < 3; p++) {
            ComtradeRecord.AnalogChannel chV = rec.analogs.get(vc[p]);
            ComtradeRecord.AnalogChannel chI = rec.analogs.get(ic[p]);
            secNoRatio |= chV.isSecondaryWithoutRatio() || chI.isSecondaryWithoutRatio();
            double kv = chV.primaryFactor() * map.vMult;
            double ki = chI.primaryFactor() * map.iMult * (map.invertCurrent ? -1 : 1);
            e.v[p] = new double[e.n];
            e.i[p] = new double[e.n];
            float[] rv = rec.values[vc[p]], ri = rec.values[ic[p]];
            for (int k = 0; k < e.n; k++) {
                e.v[p][k] = rv[k] * kv;
                e.i[p][k] = ri[k] * ki;
            }
        }
        if (secNoRatio && map.vMult == 1 && map.iMult == 1) {
            res.warnings.add(new Warning(isA ? "SEC_NO_RATIO_A" : "SEC_NO_RATIO_B"));
        }
        EndSummary s = isA ? res.endA : res.endB;
        s.secondaryWithoutRatio = secNoRatio;
        s.station = rec.stationName;
        s.triggerText = rec.triggerText;
        s.fileType = rec.fileType;
        s.fs = e.fs;
        s.f0 = e.f0;
        s.samplesPerCycle = e.N;
        return e;
    }

    // ------------------------------------------------------------------
    // Fault inception detection (cycle-to-cycle superimposed detector)
    // ------------------------------------------------------------------

    private static double[] superimposed(double[][] sig, int n, int N) {
        double[] d = new double[n];
        for (int k = N; k < n; k++) {
            double m = 0;
            for (int p = 0; p < 3; p++) m = Math.max(m, Math.abs(sig[p][k] - sig[p][k - N]));
            d[k] = m;
        }
        return d;
    }

    /** Returns {index, quality(peak/noise)} or null. */
    private static double[] detectOn(double[][] sig, int n, int N) {
        double[] d = superimposed(sig, n, N);
        int noiseEnd = Math.min(n, 3 * N);
        double[] noiseWin = java.util.Arrays.copyOfRange(d, N, Math.max(N + 1, noiseEnd));
        java.util.Arrays.sort(noiseWin);
        double noise = noiseWin[noiseWin.length / 2];
        double peak = 0;
        for (int k = N; k < n; k++) peak = Math.max(peak, d[k]);
        if (peak <= 0) return null;
        double thr = Math.max(noise * 8, peak * 0.15);
        int span = Math.max(2, N / 4);
        for (int k = N; k < n - span; k++) {
            if (d[k] <= thr) continue;
            int above = 0;
            for (int j = k; j < k + span; j++) if (d[j] > thr * 0.5) above++;
            if (above < span * 0.6) continue;
            double low = Math.max(noise * 4, thr * 0.25);
            int s = k;
            while (s - 1 >= N && d[s - 1] > low) s--;
            return new double[]{s, peak / Math.max(noise, peak * 1e-6)};
        }
        return null;
    }

    private static void detectInception(End e) {
        double[] di = detectOn(e.i, e.n, e.N);
        double[] dv = detectOn(e.v, e.n, e.N);
        // Currents first; voltages only for a weak-infeed end whose current barely moves.
        if (di != null && di[1] >= 10) {
            e.inc = (int) di[0];
            e.incSource = "I";
            return;
        }
        if (dv != null && dv[1] >= 10) {
            e.inc = (int) dv[0];
            e.incSource = "V";
            return;
        }
        double trig = e.rec.triggerSeconds();
        int idx = 0;
        if (!Double.isNaN(trig)) {
            while (idx < e.n - 1 && e.t[idx] < trig) idx++;
        } else {
            idx = e.n / 3;
        }
        e.inc = Math.max(e.N, idx);
        e.incSource = "T";
    }

    // ------------------------------------------------------------------
    // Phasor estimation: least-squares fit over one cycle with fundamental + 2nd/3rd harmonic +
    // quadratic trend. The trend terms soak up the decaying DC offset of fault currents, which a
    // plain full-cycle DFT would otherwise leak into the fundamental during the first cycles.
    // ------------------------------------------------------------------

    private static Cx[] fit(double[][] sigs, double[] t, int k0, int len, double tRef, double w) {
        int p = len >= 16 ? 9 : 5;
        double tMid = t[k0 + len / 2];
        double span = Math.max(1e-9, t[k0 + len - 1] - t[k0]);
        double[][] M = new double[p][p];
        double[][] rhs = new double[sigs.length][p];
        double[] g = new double[p];
        for (int k = k0; k < k0 + len; k++) {
            double tau = t[k] - tRef;
            double u = (t[k] - tMid) / span;
            g[0] = Math.cos(w * tau);
            g[1] = Math.sin(w * tau);
            g[2] = 1;
            g[3] = u;
            g[4] = u * u;
            if (p == 9) {
                g[5] = Math.cos(2 * w * tau);
                g[6] = Math.sin(2 * w * tau);
                g[7] = Math.cos(3 * w * tau);
                g[8] = Math.sin(3 * w * tau);
            }
            for (int r = 0; r < p; r++) {
                for (int c = 0; c < p; c++) M[r][c] += g[r] * g[c];
                for (int s = 0; s < sigs.length; s++) rhs[s][r] += g[r] * sigs[s][k];
            }
        }
        double[][] sol = solve(M, rhs);
        Cx[] out = new Cx[sigs.length];
        double k = 1 / Math.sqrt(2);
        for (int s = 0; s < sigs.length; s++) out[s] = new Cx(sol[s][0] * k, -sol[s][1] * k);
        return out;
    }

    /** Gaussian elimination with partial pivoting, several right-hand sides at once. */
    private static double[][] solve(double[][] Min, double[][] rhsIn) {
        int p = Min.length, ns = rhsIn.length;
        double[][] M = new double[p][];
        for (int r = 0; r < p; r++) M[r] = Min[r].clone();
        double[][] B = new double[p][ns];
        for (int r = 0; r < p; r++) for (int s = 0; s < ns; s++) B[r][s] = rhsIn[s][r];
        for (int col = 0; col < p; col++) {
            int piv = col;
            for (int r = col + 1; r < p; r++) if (Math.abs(M[r][col]) > Math.abs(M[piv][col])) piv = r;
            double[] tmp = M[col]; M[col] = M[piv]; M[piv] = tmp;
            double[] tb = B[col]; B[col] = B[piv]; B[piv] = tb;
            double d = M[col][col];
            if (Math.abs(d) < 1e-300) continue;
            for (int r = col + 1; r < p; r++) {
                double f = M[r][col] / d;
                if (f == 0) continue;
                for (int c = col; c < p; c++) M[r][c] -= f * M[col][c];
                for (int s = 0; s < ns; s++) B[r][s] -= f * B[col][s];
            }
        }
        double[][] x = new double[ns][p];
        for (int s = 0; s < ns; s++) {
            for (int r = p - 1; r >= 0; r--) {
                double acc = B[r][s];
                for (int c = r + 1; c < p; c++) acc -= M[r][c] * x[s][c];
                x[s][r] = Math.abs(M[r][r]) < 1e-300 ? 0 : acc / M[r][r];
            }
        }
        return x;
    }

    private static Phasors phasorsAt(End e, int k0, double tRef) {
        if (k0 < 0 || k0 + e.N > e.n) return null;
        double[][] sigs = {e.v[0], e.v[1], e.v[2], e.i[0], e.i[1], e.i[2]};
        Cx[] x = fit(sigs, e.t, k0, e.N, tRef, e.w);
        Phasors ph = new Phasors();
        for (int p = 0; p < 3; p++) { ph.v[p] = x[p]; ph.i[p] = x[3 + p]; }
        ph.computeSequences();
        return ph;
    }

    /** Fault-window phasors `off` cycles after this end's own inception, record-relative frame. */
    private static Phasors faultPhasors(End e, double off) {
        return phasorsAt(e, e.inc + (int) Math.round(off * e.N), 0);
    }

    private static Cx seq0(Cx[] x) { return x[0].add(x[1]).add(x[2]).mul(1.0 / 3); }
    private static Cx seq1(Cx[] x) { return x[0].add(Cx.A.mul(x[1])).add(Cx.A2.mul(x[2])).mul(1.0 / 3); }
    private static Cx seq2(Cx[] x) { return x[0].add(Cx.A2.mul(x[1])).add(Cx.A.mul(x[2])).mul(1.0 / 3); }

    private static double relChange(Phasors a, Phasors b) {
        double vRef = 0, iRef = 0, dv = 0, di = 0;
        for (int p = 0; p < 3; p++) {
            vRef = Math.max(vRef, a.v[p].abs());
            iRef = Math.max(iRef, a.i[p].abs());
            dv = Math.max(dv, a.v[p].sub(b.v[p]).abs());
            di = Math.max(di, a.i[p].sub(b.i[p]).abs());
        }
        return Math.max(vRef > 0 ? dv / vRef : 0, iRef > 0 ? di / iRef : 0);
    }

    private static double maxI(Phasors ph) {
        return Math.max(ph.i[0].abs(), Math.max(ph.i[1].abs(), ph.i[2].abs()));
    }

    // ------------------------------------------------------------------
    // Line model: voltage at distance x (km) from an end, walking in from that end's V and I
    // ------------------------------------------------------------------

    private static class LineModel {
        final boolean distributed;
        final Cx z, gamma, zc;
        final double length;

        LineModel(LineParams lp) {
            length = lp.lengthKm;
            z = lp.z1PerKm();
            distributed = lp.distributed();
            if (distributed) {
                Cx y = new Cx(0, lp.b1 * 1e-6);
                gamma = z.mul(y).sqrt();
                zc = z.div(y).sqrt();
            } else {
                gamma = zc = null;
            }
        }

        Cx walk(Cx v, Cx i, double xKm) {
            if (!distributed) return v.sub(z.mul(i).mul(xKm));
            Cx gx = gamma.mul(xKm);
            return v.mul(gx.cosh()).sub(zc.mul(i).mul(gx.sinh()));
        }

        /** Exact pi equivalent of xKm of line: {series Z, shunt Y at each end}. */
        Cx[] pi(double xKm) {
            if (!distributed) return new Cx[]{z.mul(xKm), Cx.ZERO};
            Cx gx = gamma.mul(xKm), h = gx.mul(0.5);
            return new Cx[]{zc.mul(gx.sinh()), h.sinh().div(h.cosh()).div(zc)};
        }
    }

    private interface Fn { double f(double m); }

    /** All sign changes of fn on [lo, hi], refined by bisection. */
    private static List<Double> roots(Fn fn, double lo, double hi, int steps) {
        List<Double> out = new ArrayList<>();
        double prevX = lo, prevY = fn.f(lo);
        for (int s = 1; s <= steps; s++) {
            double x = lo + (hi - lo) * s / steps;
            double y = fn.f(x);
            if (prevY == 0) out.add(prevX);
            else if (prevY * y < 0) {
                double a = prevX, b = x, fa = prevY;
                for (int it = 0; it < 60; it++) {
                    double mid = (a + b) / 2, fm = fn.f(mid);
                    if (fa * fm <= 0) { b = mid; } else { a = mid; fa = fm; }
                }
                out.add((a + b) / 2);
            }
            prevX = x;
            prevY = y;
        }
        return out;
    }

    private static double argMin(Fn fn, double lo, double hi, int steps) {
        double best = lo, bestY = Double.MAX_VALUE;
        for (int s = 0; s <= steps; s++) {
            double x = lo + (hi - lo) * s / steps;
            double y = fn.f(x);
            if (y < bestY) { bestY = y; best = x; }
        }
        double a = Math.max(lo, best - (hi - lo) / steps), b = Math.min(hi, best + (hi - lo) / steps);
        double gr = (Math.sqrt(5) - 1) / 2;
        for (int it = 0; it < 80; it++) {
            double c = b - gr * (b - a), d = a + gr * (b - a);
            if (fn.f(c) < fn.f(d)) b = d; else a = c;
        }
        return (a + b) / 2;
    }

    private static double wrapDeg(double d) {
        while (d > 180) d -= 360;
        while (d <= -180) d += 360;
        return d;
    }

    private static double angDiff(double a, double b) { return Math.abs(wrapDeg(a - b)); }

    // ------------------------------------------------------------------
    // Main entry point
    // ------------------------------------------------------------------

    /**
     * @param manualOffsetCycles fault-window start in cycles after each end's own inception, or
     *                           NaN to pick the most stable window automatically.
     */
    public static Result analyze(ComtradeRecord recA, ChannelMap mapA, ComtradeRecord recB, ChannelMap mapB,
                                 LineParams line, double manualOffsetCycles) {
        Result res = new Result();
        res.lengthKm = line.lengthKm;
        if (!(line.lengthKm > 0) || !(line.x1 > 0) || line.r1 < 0) { res.error = ERR_LINE; return res; }
        if (mapA == null || !mapA.complete(recA.analogCount())) { res.error = ERR_MAPPING_A; return res; }
        if (mapB == null || !mapB.complete(recB.analogCount())) { res.error = ERR_MAPPING_B; return res; }

        End a = prepare(recA, mapA, true, res);
        if (a == null) return res;
        End b = prepare(recB, mapB, false, res);
        if (b == null) return res;
        if (Math.abs(a.f0 - b.f0) > 0.5) res.warnings.add(new Warning("FREQ_DIFF", a.f0, b.f0));

        detectInception(a);
        detectInception(b);
        res.endA.inceptionSource = a.incSource;
        res.endB.inceptionSource = b.incSource;
        res.endA.inceptionTime = a.t[a.inc];
        res.endB.inceptionTime = b.t[b.inc];
        if ("T".equals(a.incSource)) res.warnings.add(new Warning("INC_TRIGGER_A"));
        if ("T".equals(b.incSource)) res.warnings.add(new Warning("INC_TRIGGER_B"));

        for (End e : new End[]{a, b}) {
            int preStart = e.inc - (int) Math.round(1.5 * e.N);
            if (preStart < 0 && e.inc - e.N - 1 >= 0) preStart = e.inc - e.N - 1;
            e.pre = preStart >= 0 ? phasorsAt(e, preStart, 0) : null;
            e.hasPre = e.pre != null;
        }
        if (!a.hasPre) res.warnings.add(new Warning("NO_PREFAULT_A"));
        if (!b.hasPre) res.warnings.add(new Warning("NO_PREFAULT_B"));

        // ---- fault window: same post-inception offset at both ends, most stable phasors ----
        double off;
        if (!Double.isNaN(manualOffsetCycles)) {
            off = Math.max(0, manualOffsetCycles);
        } else {
            off = 1.0;
            double best = Double.MAX_VALUE;
            Phasors refA = faultPhasors(a, 1.0), refB = faultPhasors(b, 1.0);
            for (int j = 0; j <= 8; j++) {
                double o = 1.0 + 0.125 * j;
                Phasors pa = faultPhasors(a, o), pa2 = faultPhasors(a, o + 0.25);
                Phasors pb = faultPhasors(b, o), pb2 = faultPhasors(b, o + 0.25);
                if (pa == null || pa2 == null || pb == null || pb2 == null) break;
                // A window whose currents already collapsed belongs to breaker opening, not the fault.
                if (refA != null && maxI(pa2) < 0.5 * maxI(refA)) break;
                if (refB != null && maxI(pb2) < 0.5 * maxI(refB)) break;
                double metric = Math.max(relChange(pa, pa2), relChange(pb, pb2));
                if (metric < best - 0.005) { best = metric; off = o; }
            }
        }
        // Clamp so both windows fit inside their records.
        double maxOffA = (a.n - a.N - a.inc) / (double) a.N, maxOffB = (b.n - b.N - b.inc) / (double) b.N;
        double maxOff = Math.min(maxOffA, maxOffB);
        if (maxOff < 0) { res.error = maxOffA < 0 ? ERR_SHORT_A : ERR_SHORT_B; return res; }
        if (off > maxOff) { off = Math.floor(maxOff * 8) / 8; res.warnings.add(new Warning("SHORT_FAULT")); }
        res.offsetCycles = off;

        Phasors fa = faultPhasors(a, off), fb = faultPhasors(b, off);
        Phasors fa2 = faultPhasors(a, off + 0.25), fb2 = faultPhasors(b, off + 0.25);
        res.instability = Math.max(fa2 != null ? relChange(fa, fa2) : 0, fb2 != null ? relChange(fb, fb2) : 0);
        if (res.instability > 0.10) res.warnings.add(new Warning("UNSTABLE", res.instability * 100));

        res.endA.hasPrefault = a.hasPre; res.endA.pre = a.pre; res.endA.flt = fa;
        res.endB.hasPrefault = b.hasPre; res.endB.pre = b.pre; res.endB.flt = fb;
        res.endA.displayRefAngle = (a.hasPre ? a.pre.v[0] : fa.v1).arg();
        res.endB.displayRefAngle = (b.hasPre ? b.pre.v[0] : fb.v1).arg();

        double scaleV = Math.max(fa.v1.abs(), fb.v1.abs());
        if (scaleV <= 0 || maxI(fa) + maxI(fb) <= 0) { res.error = ERR_NO_SIGNAL; return res; }

        // ---- balanced or not: superimposed negative vs positive sequence current ----
        Cx dI1a = a.hasPre ? fa.i1.sub(a.pre.i1) : fa.i1, dI1b = b.hasPre ? fb.i1.sub(b.pre.i1) : fb.i1;
        Cx dI2a = a.hasPre ? fa.i2.sub(a.pre.i2) : fa.i2, dI2b = b.hasPre ? fb.i2.sub(b.pre.i2) : fb.i2;
        Cx dI0a = a.hasPre ? fa.i0.sub(a.pre.i0) : fa.i0, dI0b = b.hasPre ? fb.i0.sub(b.pre.i0) : fb.i0;
        boolean aStronger = dI1a.abs() + dI2a.abs() >= dI1b.abs() + dI2b.abs();
        Cx sI1 = aStronger ? dI1a : dI1b, sI2 = aStronger ? dI2a : dI2b, sI0 = aStronger ? dI0a : dI0b;
        boolean balanced = sI2.abs() < 0.12 * sI1.abs() && sI0.abs() < 0.12 * sI1.abs();
        int s = balanced ? 1 : 2;
        res.sequenceUsed = s;

        LineModel lm = new LineModel(line);
        res.distributedModel = lm.distributed;
        final double L = line.lengthKm;

        // ---- primary: unsynchronised |V_F| equality ----
        // A balanced fault has no negative sequence; its "pure fault" equivalent is the
        // superimposed positive sequence (fault minus pre-fault). Like V2/I2 it walks to a single,
        // well-conditioned crossing, whereas total V1 is dominated by the load voltage and its
        // two V-shaped |V_F| curves can touch or cross twice near a low-resistance fault.
        boolean superimposed = s == 1 && a.hasPre && b.hasPre;
        res.superimposed = superimposed;
        Solution sol = solvePrimary(fa, fb, a, b, s, superimposed, lm, L);
        double chosen = sol.m;
        if (sol.noRoot) res.warnings.add(new Warning("NO_ROOT"));
        res.mAlt = sol.alt;
        if (!Double.isNaN(sol.alt)) res.warnings.add(new Warning("TWO_ROOTS", sol.alt * L));

        // Same equation on the following windows of the steady fault period: the median rejects
        // a single noisy window and the spread is an honest "+/- km" for the user.
        List<Double> estimates = new ArrayList<>();
        estimates.add(chosen);
        List<Phasors[]> used = new ArrayList<>();
        used.add(new Phasors[]{fa, fb});
        Phasors refA = faultPhasors(a, off), refB = faultPhasors(b, off);
        for (int j = 1; j <= 8 && !sol.noRoot; j++) {
            double o = off + 0.125 * j;
            if (o > maxOff) break;
            Phasors pa = faultPhasors(a, o), pb = faultPhasors(b, o);
            if (pa == null || pb == null) break;
            if (maxI(pa) < 0.7 * maxI(refA) || maxI(pb) < 0.7 * maxI(refB)) break;
            if (relChange(refA, pa) > 0.15 || relChange(refB, pb) > 0.15) break;
            Solution sj = solvePrimary(pa, pb, a, b, s, superimposed, lm, L);
            if (sj.noRoot || Math.abs(sj.m - chosen) > 0.15) continue;
            estimates.add(sj.m);
            used.add(new Phasors[]{pa, pb});
        }
        java.util.Collections.sort(estimates);
        int ne = estimates.size();
        double median = ne % 2 == 1 ? estimates.get(ne / 2) : (estimates.get(ne / 2 - 1) + estimates.get(ne / 2)) / 2;
        res.windowsUsed = ne;
        res.spreadKm = ne > 1 ? (estimates.get(ne - 1) - estimates.get(0)) / 2 * L : Double.NaN;
        chosen = median;

        res.m = chosen;
        if (chosen < -0.05 || chosen > 1.05) res.warnings.add(new Warning("OUT_OF_LINE", chosen * 100));
        double mc = Math.min(1, Math.max(0, chosen));
        res.distA = mc * L;
        res.distB = (1 - mc) * L;

        final Cx vA = sol.vA, iA = sol.iA, vB = sol.vB, iB = sol.iB;
        // Sync angle = arg V_F(from A) - arg V_F(from B) at the solved fault point, circular mean
        // over the same windows as the median. Every window is referenced to its record's t = 0,
        // so they all estimate the one record-to-record rotation.
        double[] deltas = new double[used.size()];
        Cx dSum = Cx.ZERO;
        for (int j = 0; j < deltas.length; j++) {
            Phasors[] w = used.get(j);
            Cx[] q = seqPair(w[0], w[1], a, b, s, superimposed);
            Cx vfAj = lm.walk(q[0], q[1], chosen * L), vfBj = lm.walk(q[2], q[3], (1 - chosen) * L);
            deltas[j] = vfAj.arg() - vfBj.arg();
            dSum = dSum.add(Cx.polar(1, deltas[j]));
        }
        double delta = dSum.arg();
        double dSpread = 0;
        for (double d : deltas) dSpread = Math.max(dSpread, angDiff(Math.toDegrees(d), Math.toDegrees(delta)));
        res.syncAngleDeg = wrapDeg(Math.toDegrees(delta));
        res.syncAngleSpreadDeg = deltas.length > 1 ? dSpread : Double.NaN;
        Cx rot = Cx.polar(1, delta); // takes end-B phasors into end-A's frame

        // ---- cross-check: positive sequence, same unsynchronised principle ----
        if (s == 2) {
            final Cx v1A = fa.v1, i1A = fa.i1, v1B = fb.v1, i1B = fb.i1;
            List<Double> r1 = roots(m -> lm.walk(v1A, i1A, m * L).abs() - lm.walk(v1B, i1B, (1 - m) * L).abs(), -0.25, 1.25, 1500);
            double bestD = Double.MAX_VALUE;
            for (double r : r1) if (Math.abs(r - chosen) < bestD) { bestD = Math.abs(r - chosen); res.mPos = r; }
        }

        // ---- cross-check: time-synchronised (only meaningful with GPS/PTP-synced relays) ----
        computeSynchronised(res, a, b, off, s, lm, L);

        // ---- fault type at the fault point (both ends' superimposed currents, aligned by delta) ----
        Cx iF1 = dI1a.add(dI1b.mul(rot)), iF2 = dI2a.add(dI2b.mul(rot)), iF0 = dI0a.add(dI0b.mul(rot));
        res.faultType = classify(iF1, iF2, iF0);

        // ---- direction per end ----
        double thetaL = Math.atan2(line.x1, line.r1);
        res.dirA = direction(fa, a, s, thetaL);
        res.dirB = direction(fb, b, s, thetaL);
        if (res.dirA < 0) res.warnings.add(new Warning("DIR_A_REV"));
        if (res.dirB < 0) res.warnings.add(new Warning("DIR_B_REV"));

        // ---- single-ended Takagi from each end ----
        res.takagiA = takagi(fa, a, res.faultType, line);
        res.takagiB = takagi(fb, b, res.faultType, line);
        if (isGround(res.faultType) && !line.hasZ0()) res.warnings.add(new Warning("NO_Z0"));

        // ---- fault resistance (lumped, from end A side) ----
        res.rf = faultResistance(res.faultType, fa, mc, line, lm, iF1, iF2);

        // ---- pre-fault plausibility checks (CT/VT ratio, polarity, phase rotation) ----
        prefaultChecks(res, a, b, rot);

        // ---- pre-fault load flow: independent sync angle and measured Z1 ----
        loadFlowChecks(res, a, b, lm, line, delta, used, s, superimposed);

        // ---- |V_F| profile for the chart ----
        int steps = 100;
        res.profileKm = new double[steps + 1];
        res.profileA = new double[steps + 1];
        res.profileB = new double[steps + 1];
        for (int k = 0; k <= steps; k++) {
            double m = k / (double) steps;
            res.profileKm[k] = m * L;
            res.profileA[k] = lm.walk(vA, iA, m * L).abs() / 1000;
            res.profileB[k] = lm.walk(vB, iB, (1 - m) * L).abs() / 1000;
        }

        // ---- voltage-only vector synchronisation (adjustable afterwards with the slider) ----
        Session session = new Session(a, b, lm, s, off, L);
        res.session = session;
        boolean signals = !"T".equals(a.incSource) && !"T".equals(b.incSource);
        res.tauVoltage = session.autoAlignVoltage(signals);
        res.tauPre = session.tauFromSyncAngle(Math.toRadians(res.syncAnglePreDeg), signals);
        res.tauAuto = session.tauFromSyncAngle(delta, signals);
        res.alignment = session.alignAt(res.tauAuto);
        if (!res.alignment.valid) { // B's record does not cover that instant - fall back
            res.tauAuto = res.tauVoltage;
            res.alignment = session.alignAt(res.tauAuto);
        }
        res.tauInception = session.tauInception;
        res.tauTimestamp = session.tauTimestamp;
        res.alignRefTimeA = session.refTimeA;
        res.alignRefCurrentA = session.refCurrentA;
        return res;
    }

    private static class Solution {
        double m = Double.NaN, alt = Double.NaN;
        boolean noRoot;
        Cx vA, iA, vB, iB;
    }

    /** Solves |V_F walked from A| = |V_F walked from B| for one pair of fault windows. */
    private static Solution solvePrimary(Phasors fa, Phasors fb, End a, End b, int s, boolean superimposed, LineModel lm, double L) {
        Solution sol = new Solution();
        Cx[] q = seqPair(fa, fb, a, b, s, superimposed);
        sol.vA = q[0]; sol.iA = q[1]; sol.vB = q[2]; sol.iB = q[3];
        final Cx vA = sol.vA, iA = sol.iA, vB = sol.vB, iB = sol.iB;
        Fn f = m -> lm.walk(vA, iA, m * L).abs() - lm.walk(vB, iB, (1 - m) * L).abs();
        List<Double> rts = roots(f, -0.25, 1.25, 1500);

        List<Double> inRange = new ArrayList<>();
        for (double r : rts) if (distOutside(r) <= 0.05) inRange.add(r);
        if (!inRange.isEmpty()) {
            double bestResid = Double.MAX_VALUE;
            for (double r : inRange) {
                double resid = consistencyResidual(r, vA, iA, vB, iB, s, superimposed, fa, fb, a, b, lm, L);
                if (resid < bestResid) { bestResid = resid; sol.m = r; }
            }
            for (double r : inRange) if (Math.abs(r - sol.m) > 0.01) sol.alt = r;
        } else if (!rts.isEmpty()) {
            for (double r : rts) if (Double.isNaN(sol.m) || distOutside(r) < distOutside(sol.m)) sol.m = r;
        } else {
            // No crossing at all - take the closest approach and say so.
            sol.m = argMin(m -> Math.abs(f.f(m)), -0.25, 1.25, 1500);
            sol.noRoot = true;
        }
        return sol;
    }

    /** {vA, iA, vB, iB} of the sequence the primary method walks (superimposed V1/I1 or V(s)/I(s)). */
    private static Cx[] seqPair(Phasors fa, Phasors fb, End a, End b, int s, boolean superimposed) {
        if (superimposed) {
            return new Cx[]{fa.v1.sub(a.pre.v1), fa.i1.sub(a.pre.i1), fb.v1.sub(b.pre.v1), fb.i1.sub(b.pre.i1)};
        }
        return new Cx[]{fa.v(s), fa.i(s), fb.v(s), fb.i(s)};
    }

    private static double distOutside(double m) {
        if (Double.isNaN(m)) return Double.MAX_VALUE;
        return m < 0 ? -m : m > 1 ? m - 1 : 0;
    }

    /**
     * Second, independent equation to pick between two roots of the magnitude equation: the
     * synchronisation angle implied by a root must also line up the OTHER sequence's fault-point
     * voltage (positive sequence for an unbalanced fault, superimposed positive sequence for a
     * balanced one). The true root makes both agree.
     */
    private static double consistencyResidual(double m, Cx vA, Cx iA, Cx vB, Cx iB, int s, boolean superimposed,
                                              Phasors fa, Phasors fb, End a, End b, LineModel lm, double L) {
        Cx vfA = lm.walk(vA, iA, m * L), vfB = lm.walk(vB, iB, (1 - m) * L);
        Cx rot = Cx.polar(1, vfA.arg() - vfB.arg());
        Cx xA, xB;
        if (s == 2 || superimposed) {
            // the other quantity: total positive sequence
            xA = lm.walk(fa.v1, fa.i1, m * L);
            xB = lm.walk(fb.v1, fb.i1, (1 - m) * L);
        } else if (a.hasPre && b.hasPre) {
            xA = lm.walk(fa.v1.sub(a.pre.v1), fa.i1.sub(a.pre.i1), m * L);
            xB = lm.walk(fb.v1.sub(b.pre.v1), fb.i1.sub(b.pre.i1), (1 - m) * L);
        } else {
            return 0;
        }
        double ref = Math.max(xA.abs(), 1e-9);
        return xA.sub(xB.mul(rot)).abs() / ref;
    }

    private static void computeSynchronised(Result res, End a, End b, double off, int s, LineModel lm, double L) {
        if (a.rec.startMicros == Long.MIN_VALUE || b.rec.startMicros == Long.MIN_VALUE) return;
        double startDiff = (a.rec.startMicros - b.rec.startMicros) / 1e6; // add to A-relative time -> B-relative
        double incAbsDiff = (a.t[a.inc] - b.t[b.inc]) + startDiff;
        res.inceptionDiffMs = incAbsDiff * 1000;
        if (Math.abs(incAbsDiff) > 1.0) res.warnings.add(new Warning("TIME_GAP", incAbsDiff));

        int k0A = a.inc + (int) Math.round(off * a.N);
        if (k0A + a.N > a.n) return;
        double tA0 = a.t[k0A];
        double tB0 = tA0 + startDiff;
        int k0B = 0;
        while (k0B < b.n && b.t[k0B] < tB0) k0B++;
        if (k0B + b.N > b.n || tB0 < b.t[0]) return;
        Phasors pa = phasorsAt(a, k0A, tA0);
        Phasors pb = phasorsAt(b, k0B, tB0);
        if (pa == null || pb == null) return;
        final Cx vA = pa.v(s), iA = pa.i(s), vB = pb.v(s), iB = pb.i(s);
        res.mSync = argMin(m -> lm.walk(vA, iA, m * L).sub(lm.walk(vB, iB, (1 - m) * L)).abs(), -0.25, 1.25, 1500);
        // Both inceptions are the same physical instant (line transit < 1 ms), so their
        // timestamp gap is a direct read of the two relays' clock offset.
        boolean signalsBased = !"T".equals(a.incSource) && !"T".equals(b.incSource);
        res.syncTrusted = signalsBased && Math.abs(res.inceptionDiffMs) <= 2.0;
    }

    private static String classify(Cx iF1, Cx iF2, Cx iF0) {
        double m1 = iF1.abs(), m2 = iF2.abs(), m0 = iF0.abs();
        if (m1 <= 0) return "?";
        if (m2 < 0.15 * m1 && m0 < 0.15 * m1) return "ABC";
        boolean ground = m0 > 0.10 * Math.max(m2, 1e-12);
        double psi = Math.toDegrees(iF2.div(iF1).arg());
        if (!ground) {
            String[] names = {"BC", "AB", "CA"};
            double[] ang = {180, 60, -60};
            int best = 0;
            for (int k = 1; k < 3; k++) if (angDiff(psi, ang[k]) < angDiff(psi, ang[best])) best = k;
            return names[best];
        }
        double phi = Math.toDegrees(iF2.div(iF0).arg());
        double[] sector = {0, -120, 120};
        int sec = 0;
        for (int k = 1; k < 3; k++) if (angDiff(phi, sector[k]) < angDiff(phi, sector[sec])) sec = k;
        double[] psiSlg = {0, 120, -120};
        String[] slg = {"AG", "BG", "CG"}, ppg = {"BCG", "CAG", "ABG"};
        return angDiff(psi, psiSlg[sec]) < 90 ? slg[sec] : ppg[sec];
    }

    private static boolean isGround(String type) {
        return type.equals("AG") || type.equals("BG") || type.equals("CG");
    }

    /** Negative-sequence directional element (superimposed positive sequence for balanced faults). */
    private static int direction(Phasors f, End e, int s, double thetaL) {
        Cx v, i;
        if (s == 2) { v = f.v2; i = f.i2; }
        else if (e.hasPre) { v = f.v1.sub(e.pre.v1); i = f.i1.sub(e.pre.i1); }
        else return 0;
        if (i.abs() <= 1e-9 || v.abs() <= 1e-9) return 0;
        double torque = v.mul(i.mul(Cx.polar(1, thetaL)).conj()).re;
        double norm = v.abs() * i.abs();
        if (Math.abs(torque) < 0.2 * norm) return 0; // too close to the boundary to call
        return torque < 0 ? 1 : -1;
    }

    /** Index of phase letter. */
    private static int ph(char c) { return c - 'A'; }

    private static final Cx[] ROT = {Cx.ONE, Cx.A, Cx.A2}; // X2 of phase p = ROT[p] * X2a

    /**
     * Modified Takagi (negative-sequence current polarised reactance method, IEEE C37.114
     * §6.1.3): m = Im(V * Ipol*) / Im(Z1L * I * Ipol*). Returns km from this end, NaN when the
     * loop can't be formed (ground fault without Z0).
     */
    private static double takagi(Phasors f, End e, String type, LineParams lp) {
        Cx z1 = lp.z1Total();
        Cx v, i, pol;
        if (type.equals("ABC")) {
            v = f.v1;
            i = f.i1;
            pol = e.hasPre ? f.i1.sub(e.pre.i1) : f.i1;
        } else if (isGround(type)) {
            if (!lp.hasZ0()) return Double.NaN;
            int p = ph(type.charAt(0));
            Cx k0 = lp.z0Total().sub(z1).div(z1.mul(3));
            Cx in = f.i[0].add(f.i[1]).add(f.i[2]);
            v = f.v[p];
            i = f.i[p].add(k0.mul(in));
            pol = ROT[p].mul(f.i2);
        } else if (type.length() >= 2 && type.charAt(0) != '?') {
            int p = ph(type.charAt(0)), q = ph(type.charAt(1));
            v = f.v[p].sub(f.v[q]);
            i = f.i[p].sub(f.i[q]);
            pol = ROT[p].sub(ROT[q]).mul(f.i2);
        } else {
            return Double.NaN;
        }
        double den = z1.mul(i).mul(pol.conj()).im;
        if (Math.abs(den) < 1e-12) return Double.NaN;
        double m = v.mul(pol.conj()).im / den;
        return m * lp.lengthKm;
    }

    private static double faultResistance(String type, Phasors fa, double m, LineParams lp, LineModel lm, Cx iF1, Cx iF2) {
        double x = m * lp.lengthKm;
        Cx vF1 = lm.walk(fa.v1, fa.i1, x), vF2 = lm.walk(fa.v2, fa.i2, x);
        Cx ratio;
        if (type.equals("ABC")) {
            ratio = vF1.div(iF1);
        } else if (isGround(type)) {
            if (!lp.hasZ0()) return Double.NaN;
            int p = ph(type.charAt(0));
            Cx vF0 = fa.v0.sub(lp.z0PerKm().mul(fa.i0).mul(x));
            Cx vFp = vF0.add(ROT[p].conj().mul(vF1)).add(ROT[p].mul(vF2)); // Vp = V0 + a^-p V1 + a^p V2
            Cx iFp = ROT[p].mul(iF2).mul(3);
            ratio = vFp.div(iFp);
        } else if (type.length() == 2) {
            int p = ph(type.charAt(0)), q = ph(type.charAt(1));
            Cx vFp = ROT[p].conj().mul(vF1).add(ROT[p].mul(vF2));
            Cx vFq = ROT[q].conj().mul(vF1).add(ROT[q].mul(vF2));
            Cx iFp = ROT[p].conj().mul(iF1).add(ROT[p].mul(iF2));
            ratio = vFp.sub(vFq).div(iFp);
        } else {
            return Double.NaN;
        }
        double r = ratio.re;
        return Double.isNaN(r) ? Double.NaN : Math.max(0, r);
    }

    /** Ratio + phase error of one VT/CT chain (class 0.5: 0.5 %, ~0.5 deg) - per end, independent. */
    private static final double SIGMA_CHAIN = Math.hypot(0.005, Math.toRadians(0.5));

    /**
     * Pre-fault positive-sequence load flow against the line model.
     *
     * Sync angle: before the fault there is no fault point, so A's V1/I1 walked over the whole
     * line must reproduce B's V1. The angle between the two is a record-to-record rotation that
     * owes nothing to the fault equation; disagreement with the fault sync angle points at the
     * record pairing, a CT polarity or the line data.
     *
     * Z1: with B rotated into A's frame by the fault sync angle, the pi-equivalent series branch
     * is (V_A - V_B) / I_series. The fault sync angle itself leans slightly on the Z1 it was
     * solved with, so the measurement is iterated (re-solve the fault with the measured Z1,
     * re-measure) to its fixed point, which is the true Z1 whatever was entered. Only the drop
     * across the line carries information, so the result is accepted only when VT/CT and
     * sync-angle errors are small compared with that drop.
     */
    private static void loadFlowChecks(Result res, End a, End b, LineModel lm, LineParams lp, double delta,
                                       List<Phasors[]> used, int s, boolean superimposed) {
        if (!a.hasPre || !b.hasPre) return;
        double L = lp.lengthKm;
        Cx vA = a.pre.v1, iA = a.pre.i1, vB = b.pre.v1, iB = b.pre.i1;
        if (!(vA.abs() > 0) || !(vB.abs() > 0)) return;

        double pre = wrapDeg(Math.toDegrees(lm.walk(vA, iA, L).arg() - vB.arg()));
        res.syncAnglePreDeg = pre;
        double spread = Double.isNaN(res.syncAngleSpreadDeg) ? 0 : res.syncAngleSpreadDeg;
        double diff = wrapDeg(pre - Math.toDegrees(delta));
        if (Math.abs(diff) > 4 + 2 * spread) {
            res.warnings.add(new Warning("SYNC_PRE_MISMATCH", diff, diff / 360 * a.T * 1000));
        }

        double sDelta = Math.toRadians(Math.max(spread, 0.2));
        Cx z = lp.z1PerKm();
        LineModel lmIt = lm;
        double dIt = delta, unc = Double.NaN;
        for (int it = 0; it < 12; it++) {
            Cx rot = Cx.polar(1, dIt);
            Cx vBa = vB.mul(rot), iBa = iB.mul(rot);
            Cx[] pi = lmIt.pi(L);
            Cx dV = vA.sub(vBa);
            Cx iSeries = iA.sub(iBa).sub(pi[1].mul(dV)).mul(0.5);
            if (!(iSeries.abs() > 0) || !(dV.abs() > 0)) return;
            unc = Math.sqrt(2 * SIGMA_CHAIN * SIGMA_CHAIN + sDelta * sDelta) * vA.abs() / dV.abs();
            if (unc > 0.5) break; // light load: the drop is lost in transformer errors
            Cx zNew = z.mul(dV.div(iSeries).div(pi[0]));
            if (!(zNew.im > 0)) break;
            boolean done = zNew.sub(z).abs() < 1e-4 * z.abs();
            z = zNew;
            if (done) break;
            LineParams lpIt = lp.withZ1(z);
            lmIt = new LineModel(lpIt);
            double dNext = windowsSyncAngle(used, a, b, s, superimposed, lmIt, L);
            if (Double.isNaN(dNext)) break;
            dIt = dNext;
        }
        res.z1MeasUnc = unc;
        if (!(unc <= 0.5)) return;
        res.z1MeasR = z.re;
        res.z1MeasX = z.im;
        double devX = (z.im - lp.x1) / lp.x1;
        if (unc <= 0.10 && Math.abs(devX) > Math.max(0.10, 2 * unc)) {
            res.warnings.add(new Warning("Z1_MISMATCH", z.im, lp.x1, devX * 100));
        }
    }

    /**
     * Re-solves the primary equation on the given windows with another line model and returns
     * the circular-mean sync angle at their median location (NaN when a window has no root).
     */
    private static double windowsSyncAngle(List<Phasors[]> used, End a, End b, int s, boolean superimposed,
                                           LineModel lm, double L) {
        List<Double> ms = new ArrayList<>();
        for (Phasors[] w : used) {
            Solution sj = solvePrimary(w[0], w[1], a, b, s, superimposed, lm, L);
            if (sj.noRoot) return Double.NaN;
            ms.add(sj.m);
        }
        java.util.Collections.sort(ms);
        int n = ms.size();
        double m = n % 2 == 1 ? ms.get(n / 2) : (ms.get(n / 2 - 1) + ms.get(n / 2)) / 2;
        Cx sum = Cx.ZERO;
        for (Phasors[] w : used) {
            Cx[] q = seqPair(w[0], w[1], a, b, s, superimposed);
            sum = sum.add(Cx.polar(1, lm.walk(q[0], q[1], m * L).arg() - lm.walk(q[2], q[3], (1 - m) * L).arg()));
        }
        return sum.arg();
    }

    private static void prefaultChecks(Result res, End a, End b, Cx rot) {
        for (End e : new End[]{a, b}) {
            if (e.hasPre && e.pre.v2.abs() > e.pre.v1.abs()) {
                res.warnings.add(new Warning(e == a ? "PHASE_ROT_A" : "PHASE_ROT_B"));
            }
        }
        if (!a.hasPre || !b.hasPre) return;
        double vA = a.pre.v1.abs(), vB = b.pre.v1.abs();
        if (vA > 0 && vB > 0 && Math.max(vA, vB) / Math.min(vA, vB) > 1.15) {
            res.warnings.add(new Warning("PRE_V_MISMATCH", vA * Math.sqrt(3) / 1000, vB * Math.sqrt(3) / 1000));
        }
        double iA = a.pre.i1.abs(), iB = b.pre.i1.abs();
        double fltScale = Math.max(maxI(res.endA.flt), maxI(res.endB.flt));
        // Only judge load current that is clearly above noise/charging current.
        if (Math.min(iA, iB) > 0.03 * fltScale && Math.min(iA, iB) > 20) {
            if (Math.max(iA, iB) / Math.min(iA, iB) > 1.5) {
                res.warnings.add(new Warning("PRE_I_MISMATCH", iA, iB));
            }
            // Through-load leaves one end and enters the other: I_B (into line) ~ -I_A.
            double ang = Math.toDegrees(b.pre.i1.mul(rot).div(a.pre.i1).arg());
            if (Math.abs(wrapDeg(ang)) < 60) res.warnings.add(new Warning("PRE_POLARITY"));
        }
    }
}
