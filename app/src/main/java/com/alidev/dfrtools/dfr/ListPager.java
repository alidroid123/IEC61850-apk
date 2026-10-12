package com.alidev.dfrtools.dfr;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;

import com.alidev.dfrtools.R;

/**
 * Pages the DFR record lists (relay scan results, FL Double Ended's record pickers) PAGE_SIZE rows
 * at a time. The relay's file directory is still read in full - MMS can only continue a listing
 * forward in the relay's own (usually oldest-first) order, and newest-first sorting plus .cfg/.dat
 * pairing need every name - but only the rows of the page on screen are built, which is what made
 * long lists slow.
 */
final class ListPager {

    static final int PAGE_SIZE = 50;

    interface PageListener {
        void onPage(int page);
    }

    private ListPager() { }

    static int pageCount(int total) {
        return Math.max(1, (total + PAGE_SIZE - 1) / PAGE_SIZE);
    }

    static int pageOf(int index) {
        return Math.max(0, index) / PAGE_SIZE;
    }

    /** First index on `page`. */
    static int from(int page) {
        return page * PAGE_SIZE;
    }

    /** One past the last index on `page`. */
    static int to(int page, int total) {
        return Math.min(total, (page + 1) * PAGE_SIZE);
    }

    static int clamp(int page, int total) {
        return Math.max(0, Math.min(page, pageCount(total) - 1));
    }

    /**
     * "‹ Prev | Page 2/7 • 51–100 of 320 | Next ›". Tapping the middle opens a jump-to-page list.
     * Returns null when everything fits on one page.
     */
    static View build(Context c, int page, int total, PageListener listener) {
        int pages = pageCount(total);
        if (pages <= 1) return null;

        LinearLayout bar = new LinearLayout(c);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams barLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        barLp.topMargin = px(c, 4);
        barLp.bottomMargin = px(c, 4);
        bar.setLayoutParams(barLp);

        Button prev = navButton(c, R.string.btn_pager_prev, page > 0, () -> listener.onPage(page - 1));
        Button next = navButton(c, R.string.btn_pager_next, page < pages - 1, () -> listener.onPage(page + 1));

        TextView info = new TextView(c);
        info.setText(c.getString(R.string.lbl_pager_info, page + 1, pages, from(page) + 1, to(page, total), total));
        info.setTextColor(ContextCompat.getColor(c, R.color.brand_primary));
        info.setTextSize(11f);
        info.setGravity(Gravity.CENTER);
        info.setPadding(px(c, 4), px(c, 8), px(c, 4), px(c, 8));
        info.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        info.setOnClickListener(v -> showJumpDialog(c, page, total, listener));

        bar.addView(prev);
        bar.addView(info);
        bar.addView(next);
        return bar;
    }

    private static Button navButton(Context c, int text, boolean enabled, Runnable onClick) {
        Button b = new Button(c, null, com.google.android.material.R.attr.materialButtonOutlinedStyle);
        b.setText(text);
        b.setTextSize(10f);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setPadding(px(c, 8), 0, px(c, 8), 0);
        b.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, px(c, 36)));
        b.setEnabled(enabled);
        b.setAlpha(enabled ? 1f : 0.35f);
        b.setOnClickListener(v -> onClick.run());
        return b;
    }

    private static int px(Context c, int dp) {
        return Math.round(dp * c.getResources().getDisplayMetrics().density);
    }

    private static void showJumpDialog(Context c, int page, int total, PageListener listener) {
        int pages = pageCount(total);
        String[] labels = new String[pages];
        for (int p = 0; p < pages; p++) {
            labels[p] = c.getString(R.string.lbl_pager_jump_item, p + 1, from(p) + 1, to(p, total));
        }
        new AlertDialog.Builder(c, R.style.Theme_Comtrade_AlertDialog)
                .setTitle(R.string.ttl_pager_jump)
                .setSingleChoiceItems(labels, page, (d, which) -> {
                    d.dismiss();
                    if (which != page) listener.onPage(which);
                })
                .setNegativeButton(R.string.btn_all_cancel, null)
                .show();
    }
}
