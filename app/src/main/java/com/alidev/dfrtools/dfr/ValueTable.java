package com.alidev.dfrtools.dfr;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;

import com.alidev.dfrtools.R;

/**
 * Fills a TableLayout with a small numeric table (FL Double Ended's phasor and angle values):
 * shaded header rows that can span columns, zebra body rows, a bold label column and
 * right-aligned tabular digits. Columns stretch to the card width, so the table also fits the PDF
 * report, which is drawn from the same card.
 */
final class ValueTable {

    private final Context c;
    private final TableLayout table;
    private final int primary, textPrimary, textSecondary, headerBg, zebraBg, dividerColor, highlightBg, highlightText;
    private int bodyRows;
    /** Fixed proportional column widths (opt-in); null keeps the default stretch-to-fit behaviour. */
    private float[] colWeights;
    /** Max lines for the label column before it ellipsizes (0 = unlimited). */
    private int labelMaxLines;

    ValueTable(Context c, TableLayout table) {
        this.c = c;
        this.table = table;
        table.removeAllViews();
        table.setStretchAllColumns(true);
        table.setShrinkAllColumns(true);
        TypedValue tv = new TypedValue();
        c.getTheme().resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true);
        primary = tv.data;
        textPrimary = ContextCompat.getColor(c, R.color.text_primary);
        textSecondary = ContextCompat.getColor(c, R.color.text_secondary);
        headerBg = ContextCompat.getColor(c, R.color.bg_navy_dim);
        dividerColor = ContextCompat.getColor(c, R.color.divider);
        zebraBg = ColorUtils.setAlphaComponent(dividerColor, 70);
        highlightText = ContextCompat.getColor(c, R.color.status_danger);
        highlightBg = ColorUtils.setAlphaComponent(highlightText, 38);
    }

    /** Fixes proportional column widths (one weight per column), so a long cell can't crowd the rest. */
    ValueTable columns(float... weights) {
        colWeights = weights;
        table.setStretchAllColumns(false);
        table.setShrinkAllColumns(false);
        return this;
    }

    /** Ellipsizes the label column after this many lines (so long names wrap then end in "..."). */
    ValueTable labelLines(int maxLines) {
        labelMaxLines = maxLines;
        return this;
    }

    /** A header row; spans[i] is how many columns cells[i] covers (null = 1 each). */
    ValueTable header(String[] cells, int[] spans) {
        TableRow row = new TableRow(c);
        row.setBackgroundColor(headerBg);
        row.setTag(FaultLocatorActivity.PDF_KEEP_WITH_NEXT); // never the last row on a PDF page
        for (int i = 0; i < cells.length; i++) {
            TextView t = cell(cells[i], i == 0 ? Gravity.START : Gravity.CENTER);
            t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            t.setTextColor(primary);
            TableRow.LayoutParams lp = new TableRow.LayoutParams();
            if (spans != null) lp.span = spans[i];
            else applyWeight(lp, i);
            row.addView(t, lp);
        }
        table.addView(row);
        return this;
    }

    private void applyWeight(TableRow.LayoutParams lp, int col) {
        if (colWeights != null && col < colWeights.length) {
            lp.width = 0;
            lp.weight = colWeights[col];
        }
    }

    /** A body row: bold label, then right-aligned values. */
    ValueTable row(String label, String... values) {
        return row(false, label, values);
    }

    /** A body row; `highlight` tints it red (e.g. a phase involved in the fault). */
    ValueTable row(boolean highlight, String label, String... values) {
        TableRow row = new TableRow(c);
        bodyRows++;
        if (highlight) row.setBackgroundColor(highlightBg);
        else if (bodyRows % 2 == 0) row.setBackgroundColor(zebraBg);
        TextView l = cell(label, Gravity.START);
        l.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        if (highlight) l.setTextColor(highlightText);
        if (labelMaxLines > 0) {
            l.setMaxLines(labelMaxLines);
            l.setEllipsize(android.text.TextUtils.TruncateAt.END);
        }
        TableRow.LayoutParams llp = new TableRow.LayoutParams();
        applyWeight(llp, 0);
        row.addView(l, llp);
        for (int i = 0; i < values.length; i++) {
            TextView t = cell(values[i], Gravity.END);
            if (highlight) {
                t.setTextColor(highlightText);
                t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            }
            TableRow.LayoutParams vlp = new TableRow.LayoutParams();
            applyWeight(vlp, i + 1);
            row.addView(t, vlp);
        }
        table.addView(row);
        return this;
    }

    /** A thin rule between row groups (e.g. phase values and sequence values). */
    ValueTable divider() {
        View line = new View(c);
        line.setBackgroundColor(dividerColor);
        table.addView(line, new TableLayout.LayoutParams(TableLayout.LayoutParams.MATCH_PARENT, px(1)));
        return this;
    }

    private TextView cell(String text, int gravity) {
        TextView t = new TextView(c);
        t.setText(text);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        t.setTextColor(gravity == Gravity.END ? textPrimary : textSecondary);
        t.setGravity(gravity | Gravity.CENTER_VERTICAL);
        t.setFontFeatureSettings("tnum"); // equal-width digits keep the columns aligned
        t.setPadding(px(4), px(3), px(4), px(3));
        return t;
    }

    private int px(int dp) {
        return Math.round(dp * c.getResources().getDisplayMetrics().density);
    }
}
