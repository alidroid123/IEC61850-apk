package com.alidev.dfrtools.dfr;

import java.util.Locale;

/**
 * One row of the Line Database (Database Saluran): a transmission line seen from one relay bay,
 * with its positive- and zero-sequence impedance. Negative sequence is not stored separately -
 * for a (transposed) line Z2 = Z1.
 *
 * Numeric fields are NaN when unknown. Per-km R/X are the source of truth; the whole-line Z and
 * angle columns that utility spreadsheets carry are kept only to fill R/X in when a row has
 * nothing else (see completeDerived()).
 */
public class LineData {
    public String id = "";
    public String no = "", upt = "", ultg = "", gi = "", giUip2b = "", bay = "", giLawan = "", grade = "";
    public String merk = "", tipe = "", konduktor = "", standarKonduktor = "";
    public double kv = Double.NaN, ccc = Double.NaN, lengthKm = Double.NaN;
    public double r1 = Double.NaN, x1 = Double.NaN, z1 = Double.NaN, ang1 = Double.NaN;
    public double r0 = Double.NaN, x0 = Double.NaN, z0 = Double.NaN, ang0 = Double.NaN;
    /** Positive-sequence susceptance, uS/km - optional, enables the long-line model. */
    public double b1 = Double.NaN;

    public boolean hasZ1() { return lengthKm > 0 && x1 > 0 && r1 >= 0; }
    public boolean hasZ0() { return x0 > 0 && r0 >= 0; }

    /** Identity used to update/merge rows: the same bay at the same substation towards the same remote end. */
    public String key() {
        return norm(gi) + "|" + norm(bay) + "|" + norm(giLawan);
    }

    /**
     * Fills per-km R/X from whole-line |Z| + angle when the source only has those, and the other
     * way round, so every row ends up with both representations.
     */
    public void completeDerived() {
        if ((Double.isNaN(r1) || Double.isNaN(x1)) && z1 > 0 && !Double.isNaN(ang1) && lengthKm > 0) {
            r1 = z1 / lengthKm * Math.cos(Math.toRadians(ang1));
            x1 = z1 / lengthKm * Math.sin(Math.toRadians(ang1));
        }
        if ((Double.isNaN(r0) || Double.isNaN(x0)) && z0 > 0 && !Double.isNaN(ang0) && lengthKm > 0) {
            r0 = z0 / lengthKm * Math.cos(Math.toRadians(ang0));
            x0 = z0 / lengthKm * Math.sin(Math.toRadians(ang0));
        }
        if (Double.isNaN(z1) && hasZ1()) z1 = Math.hypot(r1, x1) * lengthKm;
        if (Double.isNaN(ang1) && hasZ1()) ang1 = Math.toDegrees(Math.atan2(x1, r1));
        if (Double.isNaN(z0) && hasZ0() && lengthKm > 0) z0 = Math.hypot(r0, x0) * lengthKm;
        if (Double.isNaN(ang0) && hasZ0()) ang0 = Math.toDegrees(Math.atan2(x0, r0));
    }

    /**
     * "ADIPALA 500 KV – KESUGIHAN 500 KV". Only glyphs every phone font has: Infinix's TOS font,
     * for one, has no arrows/angle/star signs and draws them as garbage.
     */
    public String routeLabel() {
        return gi + (giLawan.isEmpty() ? "" : " – " + giLawan);
    }

    /** "KESUGIHAN 1 - MAIN A • 500 kV • NR PCS-931" */
    public String detailLabel() {
        StringBuilder sb = new StringBuilder(bay);
        if (!Double.isNaN(kv)) sb.append(sb.length() > 0 ? " • " : "").append(fmt(kv, 0)).append(" kV");
        String relay = (merk + " " + tipe).trim();
        if (!relay.isEmpty()) sb.append(sb.length() > 0 ? " • " : "").append(relay);
        return sb.toString();
    }

    /** "13.83 km • Z1 3.179 Ω / 85.0° • Z0 9.787 Ω / 76.1°" */
    public String impedanceLabel() {
        StringBuilder sb = new StringBuilder();
        sb.append(fmt(lengthKm, 2)).append(" km");
        if (!Double.isNaN(z1)) sb.append(" • Z1 ").append(fmt(z1, 3)).append(" Ω / ").append(fmt(ang1, 1)).append("°");
        if (!Double.isNaN(z0)) sb.append(" • Z0 ").append(fmt(z0, 3)).append(" Ω / ").append(fmt(ang0, 1)).append("°");
        return sb.toString();
    }

    public String searchText() {
        return (gi + " " + giUip2b + " " + bay + " " + giLawan + " " + merk + " " + tipe + " " + konduktor + " "
                + standarKonduktor + " " + upt + " " + ultg).toUpperCase(Locale.ROOT);
    }

    static String fmt(double v, int decimals) {
        if (Double.isNaN(v)) return "-";
        return String.format(Locale.US, "%." + decimals + "f", v);
    }

    /**
     * Name normalisation for matching relay-database names against line-database names: drops
     * "GI/GIS/GITET" prefixes, voltage suffixes like "150 KV" and punctuation, so "GI Adipala" and
     * "ADIPALA 500 KV" compare equal.
     */
    public static String norm(String s) {
        if (s == null) return "";
        String u = s.toUpperCase(Locale.ROOT);
        u = u.replaceAll("\\b\\d{2,3}\\s*KV\\b", " ");
        u = u.replaceAll("[^A-Z0-9]+", " ");
        u = " " + u.trim().replaceAll("\\s+", "  ") + " "; // doubled spaces: adjacent words each keep a delimiter
        u = u.replaceAll(" (GI|GIS|GITET|GARDU|INDUK|SUBSTATION) ", " ");
        return u.trim().replaceAll("\\s+", " ");
    }
}
