package com.alidev.dfrtools.dfr;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.alidev.dfrtools.R;
import com.alidev.dfrtools.dfr.DoubleEndedFaultLocator.Bibo;
import com.alidev.dfrtools.dfr.DoubleEndedFaultLocator.DigitalEvent;
import com.alidev.dfrtools.dfr.DoubleEndedFaultLocator.EndSummary;
import com.alidev.dfrtools.dfr.DoubleEndedFaultLocator.PhaseRise;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * Renders the BI/BO (IO) analysis - current-rise timing + debounced digital events - identically
 * for the double-ended fault locator and the single-record DFR viewer. One source of truth so both
 * screens (and their PDFs) look the same.
 */
public final class BiboReport {

    private BiboReport() { }

    static final String[] PH = {"R", "S", "T"};

    public static int dec(double fs) { return fs >= 8000 ? 3 : fs >= 4000 ? 2 : 1; }

    static String fmt(double v, int d) {
        if (Double.isNaN(v)) return "-";
        return String.format(Locale.US, "%." + d + "f", v);
    }

    static String fmtPlain(double v) {
        return v == Math.rint(v) ? String.valueOf((long) v) : String.valueOf(v);
    }

    /** Signed millisecond text ("+1.20", "-0.30"), avoiding "-0.00"; en-dash for NaN. */
    static String fmtSigned(double ms, int d) {
        if (Double.isNaN(ms)) return "–";
        if (Math.abs(ms) < 0.5 * Math.pow(10, -d)) ms = 0;
        String s = String.format(Locale.US, "%." + d + "f", ms);
        return s.startsWith("-") ? s : "+" + s;
    }

    static String msTxt(double sec, int d) {
        return Double.isNaN(sec) ? "–" : fmtSigned(sec * 1000, d);
    }

    /** Wall-clock HH:MM:SS.mmm of a naive-epoch microsecond count (UTC fields, like the GAS parser). */
    static String fmtClock(long micros) {
        long ms = Math.floorDiv(micros, 1000L), msOfDay = Math.floorMod(ms, 86_400_000L);
        return String.format(Locale.US, "%02d:%02d:%02d.%03d",
                msOfDay / 3_600_000, (msOfDay / 60_000) % 60, (msOfDay / 1000) % 60, msOfDay % 1000);
    }

    static String clock(long startMicros, double t, int d) {
        if (startMicros != Long.MIN_VALUE && !Double.isNaN(t)) return fmtClock(startMicros + Math.round(t * 1e6));
        return Double.isNaN(t) ? "–" : "t=" + fmt(t * 1000, d) + " ms";
    }

    static int attrColor(Context c, int attr) {
        TypedValue tv = new TypedValue();
        c.getTheme().resolveAttribute(attr, tv, true);
        return tv.data;
    }

    static int dp(Context c, int v) {
        return (int) (v * c.getResources().getDisplayMetrics().density);
    }

    /** Which reference the per-channel times use, after falling back when the chosen one is missing. */
    public static class Ref {
        String key, refName, refNote;
        double refT;
    }

    public static Ref computeRef(Context c, Bibo b, String biboRef) {
        Ref o = new Ref();
        boolean trigOk = !Double.isNaN(b.trigger);
        o.key = "trig".equals(biboRef) ? "trig" : "rise";
        if (o.key.equals("rise") && b.rise == null && trigOk) { o.key = "trig"; o.refNote = c.getString(R.string.val_fl_bibo_note_rise_fallback); }
        if (o.key.equals("trig") && !trigOk && b.rise != null) { o.key = "rise"; o.refNote = c.getString(R.string.val_fl_bibo_note_trig_fallback); }
        o.refT = o.key.equals("trig") ? b.trigger : b.rise != null ? b.rise.t : Double.NaN;
        o.refName = c.getString(o.key.equals("trig") ? R.string.val_fl_bibo_ref_trig_name : R.string.val_fl_bibo_ref_rise_name);
        return o;
    }

    public static String refDisplayName(Context c, String biboRef) {
        return c.getString("trig".equals(biboRef) ? R.string.val_fl_bibo_ref_trig_name : R.string.val_fl_bibo_ref_rise_name);
    }

    public static boolean hasEvents(EndSummary end) {
        return end != null && end.bibo != null && !end.bibo.events.isEmpty();
    }

    private static TextView tv(Context c, String text, int color, boolean bold, boolean small, int topDp) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextColor(color);
        t.setTextSize(TypedValue.COMPLEX_UNIT_PX, c.getResources().getDimension(
                small ? R.dimen.text_size_small : R.dimen.text_size_body_small));
        if (bold) t.setTypeface(t.getTypeface(), Typeface.BOLD);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(c, topDp);
        t.setLayoutParams(lp);
        return t;
    }

    /** Builds one end's BI/BO block into {@code parent} (a vertical LinearLayout). */
    public static void render(Context c, LinearLayout parent, String name, EndSummary end, String biboRef) {
        if (end == null || end.bibo == null) return;
        Bibo b = end.bibo;
        int primary = attrColor(c, com.google.android.material.R.attr.colorPrimary);
        int secondary = ContextCompat.getColor(c, R.color.text_secondary);
        int textPrimary = ContextCompat.getColor(c, R.color.text_primary);
        int warn = attrColor(c, com.google.android.material.R.attr.colorError);
        int d = dec(b.fs);

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(c, 10), 0, dp(c, 4));
        parent.addView(box, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        box.addView(tv(c, name, primary, true, false, 0));
        box.addView(tv(c, c.getString(R.string.msg_fl_bibo_fs, fmt(b.fs, 0), fmt(1000.0 / b.fs, d)), secondary, false, true, 0));

        DoubleEndedFaultLocator.CurrentRise rise = b.rise;
        boolean trigOk = !Double.isNaN(b.trigger);
        Ref ref = computeRef(c, b, biboRef);

        if (rise != null && trigOk) {
            double dg = b.trigger - rise.t;
            String detail = Math.abs(dg) * 1000 < Math.pow(10, -d)
                    ? c.getString(R.string.val_fl_bibo_gap_coincident)
                    : c.getString(R.string.val_fl_bibo_gap_detail, fmt(Math.abs(dg) * 1000, d),
                        c.getString(dg > 0 ? R.string.val_fl_bibo_after : R.string.val_fl_bibo_before));
            box.addView(tv(c, c.getString(R.string.lbl_fl_bibo_gap) + ": " + msTxt(dg, d) + " ms  (" + detail + ")", textPrimary, false, false, 6));
        } else {
            String why = c.getString(rise != null ? R.string.val_fl_bibo_gap_no_trig : R.string.val_fl_bibo_gap_no_rise);
            box.addView(tv(c, c.getString(R.string.msg_fl_bibo_gap_na, why), secondary, false, false, 6));
        }

        if (rise != null) {
            String val = c.getString(R.string.val_fl_bibo_rise_val, clock(end.startMicros, rise.t, d), fmt(rise.t * 1000, d) + " ms", PH[rise.phase]);
            box.addView(tv(c, c.getString(R.string.lbl_fl_bibo_rise) + ": " + val, textPrimary, "rise".equals(ref.key), false, 6));
            StringBuilder ph = new StringBuilder();
            for (PhaseRise p : rise.phases) {
                if (ph.length() > 0) ph.append("  •  ");
                ph.append("I").append(PH[p.phase]).append(' ').append(p.idx >= 0 ? fmt(p.t * 1000, d) + " ms" : "–");
            }
            box.addView(tv(c, ph.toString(), secondary, false, true, 1));
            if (!(rise.snr >= 20)) box.addView(tv(c, c.getString(R.string.warn_fl_bibo_rise_low, fmt(rise.snr, 0)), warn, false, true, 1));
        } else {
            box.addView(tv(c, c.getString(R.string.lbl_fl_bibo_rise) + ": " + c.getString(R.string.val_fl_bibo_rise_none), secondary, false, false, 6));
        }
        if (trigOk) {
            box.addView(tv(c, c.getString(R.string.lbl_fl_bibo_trig) + ": "
                    + c.getString(R.string.val_fl_bibo_trig_val, clock(end.startMicros, b.trigger, d), fmt(b.trigger * 1000, d) + " ms"),
                    textPrimary, "trig".equals(ref.key), false, 3));
        } else {
            box.addView(tv(c, c.getString(R.string.lbl_fl_bibo_trig) + ": " + c.getString(R.string.val_fl_bibo_trig_none), secondary, false, false, 3));
        }
        if (ref.refNote != null) box.addView(tv(c, ref.refNote, warn, false, true, 3));

        LinkedHashMap<Integer, List<DigitalEvent>> byCh = new LinkedHashMap<>();
        for (DigitalEvent ev : b.events) {
            List<DigitalEvent> g = byCh.get(ev.idx);
            if (g == null) { g = new ArrayList<>(); byCh.put(ev.idx, g); }
            g.add(ev);
        }
        int no = 0;
        for (List<DigitalEvent> g : byCh.values()) {
            no++;
            DigitalEvent first = g.get(0);
            box.addView(tv(c, c.getString(R.string.lbl_fl_bibo_event, no, first.ch), primary, true, false, 8));
            box.addView(tv(c, c.getString(R.string.msg_fl_bibo_event_ref,
                    msTxt(first.t - ref.refT, d), ref.refName, clock(end.startMicros, first.t, d)), secondary, false, true, 1));
            for (int i = 0; i < g.size(); i++) {
                DigitalEvent ev = g.get(i);
                DigitalEvent next = i + 1 < g.size() ? g.get(i + 1) : null;
                double tEnd = next != null ? next.t : b.recordEnd;
                boolean up = ev.to == 1;
                String rTo = next != null ? msTxt(next.t - ref.refT, d) + " ms" : c.getString(R.string.val_fl_bibo_step_end);
                String dur = Double.isNaN(tEnd) ? "–" : (next == null ? "≥ " : "") + fmt((tEnd - ev.t) * 1000, d) + " ms";
                String clk = next != null
                        ? clock(end.startMicros, ev.t, d) + " → " + clock(end.startMicros, next.t, d)
                        : c.getString(R.string.val_fl_bibo_since, clock(end.startMicros, ev.t, d));
                box.addView(tv(c, (up ? "▲ " : "▼ ") + ev.from + "→" + ev.to + "   " + msTxt(ev.t - ref.refT, d) + " → " + rTo + "   " + dur,
                        up ? primary : textPrimary, false, false, 4));
                box.addView(tv(c, clk, secondary, false, true, 0));
            }
        }

        String note = b.channels > 0
                ? c.getString(R.string.msg_fl_bibo_note, b.changed, b.channels, ref.refName, fmtPlain(b.debounceMs))
                : c.getString(R.string.val_fl_bibo_no_digital);
        box.addView(tv(c, note, secondary, false, true, 6));
    }

    /** Appends one end's events to a plain-text share report (WhatsApp-style). */
    public static void appendEvents(Context c, StringBuilder sb, String name, EndSummary end, String biboRef) {
        if (!hasEvents(end)) return;
        Bibo b = end.bibo;
        int d = dec(b.fs);
        Ref ref = computeRef(c, b, biboRef);
        sb.append('\n').append(name).append('\n');
        if (b.rise != null && !Double.isNaN(b.trigger)) {
            double dg = b.trigger - b.rise.t;
            String detail = Math.abs(dg) * 1000 < Math.pow(10, -d) ? c.getString(R.string.val_fl_bibo_gap_coincident)
                    : c.getString(R.string.val_fl_bibo_gap_detail, fmt(Math.abs(dg) * 1000, d),
                        c.getString(dg > 0 ? R.string.val_fl_bibo_after : R.string.val_fl_bibo_before));
            sb.append(c.getString(R.string.lbl_fl_bibo_gap)).append(": ").append(msTxt(dg, d)).append(" ms (").append(detail).append(")\n");
        }
        if (ref.refNote != null) sb.append(ref.refNote).append('\n');
        LinkedHashMap<Integer, List<DigitalEvent>> byCh = new LinkedHashMap<>();
        for (DigitalEvent ev : b.events) {
            List<DigitalEvent> g = byCh.get(ev.idx);
            if (g == null) { g = new ArrayList<>(); byCh.put(ev.idx, g); }
            g.add(ev);
        }
        int no = 0;
        for (List<DigitalEvent> g : byCh.values()) {
            no++;
            DigitalEvent first = g.get(0);
            sb.append("▪️ ").append(c.getString(R.string.lbl_fl_bibo_event, no, first.ch)).append("  (")
                    .append(c.getString(R.string.msg_fl_bibo_event_ref, msTxt(first.t - ref.refT, d), ref.refName,
                            clock(end.startMicros, first.t, d))).append(")\n");
            for (int i = 0; i < g.size(); i++) {
                DigitalEvent ev = g.get(i);
                DigitalEvent next = i + 1 < g.size() ? g.get(i + 1) : null;
                double tEnd = next != null ? next.t : b.recordEnd;
                String rTo = next != null ? msTxt(next.t - ref.refT, d) + " ms" : c.getString(R.string.val_fl_bibo_step_end);
                String dur = Double.isNaN(tEnd) ? "–" : (next == null ? "≥ " : "") + fmt((tEnd - ev.t) * 1000, d) + " ms";
                sb.append("   ").append(ev.to == 1 ? "🟢 " : "🟠 ").append(ev.from).append("→").append(ev.to).append("  ")
                        .append(msTxt(ev.t - ref.refT, d)).append(" → ").append(rTo).append("  ").append(dur).append('\n');
            }
        }
    }
}
