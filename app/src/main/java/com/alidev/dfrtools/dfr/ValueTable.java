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
    private final int primary, textPrimary, textSecondary, headerBg, zebraBg, dividerColor;
    private int bodyRows;

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
    }

    /** A header row; spans[i] is how many columns cells[i] covers (null = 1 each). */
    ValueTable header(String[] cells, int[] spans) {
        TableRow row = new TableRow(c);
        row.setBackgroundColor(headerBg);
        for (int i = 0; i < cells.length; i++) {
            TextView t = cell(cells[i], i == 0 ? Gravity.START : Gravity.CENTER);
            t.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            t.setTextColor(primary);
            TableRow.LayoutParams lp = new TableRow.LayoutParams();
            if (spans != null) lp.span = spans[i];
            row.addView(t, lp);
        }
        table.addView(row);
        return this;
    }

    /** A body row: bold label, then right-aligned values. */
    ValueTable row(String label, String... values) {
        TableRow row = new TableRow(c);
        if (bodyRows++ % 2 == 1) row.setBackgroundColor(zebraBg);
        TextView l = cell(label, Gravity.START);
        l.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        row.addView(l);
        for (String v : values) row.addView(cell(v, Gravity.END));
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
