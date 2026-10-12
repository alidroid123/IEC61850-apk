package com.alidev.dfrtools.dfr;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.alidev.dfrtools.R;
import com.alidev.dfrtools.dfr.DoubleEndedFaultLocator.Cx;
import com.alidev.dfrtools.dfr.DoubleEndedFaultLocator.Phasors;

/**
 * Relay-style phasor diagrams of GI A and GI B side by side at the same instant (after the
 * vector synchronisation): VR/VS/VT solid, IR/IS/IT dashed, both plots referenced to VR of GI A,
 * voltages scaled to the larger voltage of the two relays and currents likewise - so moving the
 * time slider visibly rotates B's diagram against A's.
 */
public class RelayPhasorView extends View {

    private static final String[] PHASES = {"R", "S", "T"};

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint titlePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint[] vPaint = new Paint[3], iPaint = new Paint[3], iHead = new Paint[3];

    private Phasors a, b;
    private String nameA = "A", nameB = "B";

    public RelayPhasorView(Context context) { this(context, null); }

    public RelayPhasorView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        int[] colors = {
                ContextCompat.getColor(context, R.color.status_danger),   // R
                ContextCompat.getColor(context, R.color.status_warning),  // S
                ContextCompat.getColor(context, R.color.status_info),     // T
        };
        for (int p = 0; p < 3; p++) {
            vPaint[p] = new Paint(Paint.ANTI_ALIAS_FLAG);
            vPaint[p].setColor(colors[p]);
            vPaint[p].setStrokeWidth(dp(2.6f));
            vPaint[p].setStrokeCap(Paint.Cap.ROUND);
            iPaint[p] = new Paint(vPaint[p]);
            iPaint[p].setStrokeWidth(dp(1.8f));
            iHead[p] = new Paint(iPaint[p]);
            iPaint[p].setPathEffect(new DashPathEffect(new float[]{dp(5), dp(3)}, 0));
        }
        gridPaint.setColor(ContextCompat.getColor(context, R.color.divider_strong));
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(1));
        titlePaint.setColor(ContextCompat.getColor(context, R.color.text_primary));
        titlePaint.setTextSize(sp(11));
        titlePaint.setFakeBoldText(true);
        titlePaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setTextSize(sp(9));
        labelPaint.setFakeBoldText(true);
        labelPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setRelays(@Nullable Phasors a, @Nullable Phasors b, String nameA, String nameB) {
        this.a = a;
        this.b = b;
        this.nameA = nameA;
        this.nameB = nameB;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (a == null || b == null) return;
        float w = getWidth(), h = getHeight();
        float r = Math.min(w / 4f - dp(8), (h - dp(40)) / 2f);
        float cy = dp(18) + r;
        double ref = a.v[0].arg();
        double vMax = 0, iMax = 0;
        for (int p = 0; p < 3; p++) {
            vMax = Math.max(vMax, Math.max(a.v[p].abs(), b.v[p].abs()));
            iMax = Math.max(iMax, Math.max(a.i[p].abs(), b.i[p].abs()));
        }
        drawRelay(c, w * 0.25f, cy, r, nameA, a, ref, vMax, iMax);
        drawRelay(c, w * 0.75f, cy, r, nameB, b, ref, vMax, iMax);

        // legend: V solid, I dashed
        float ly = h - dp(5);
        labelPaint.setColor(ContextCompat.getColor(getContext(), R.color.text_secondary));
        labelPaint.setFakeBoldText(false);
        labelPaint.setTextAlign(Paint.Align.LEFT);
        c.drawLine(w / 2f - dp(90), ly - dp(3), w / 2f - dp(70), ly - dp(3), vPaint[0]);
        c.drawText("V", w / 2f - dp(66), ly, labelPaint);
        c.drawLine(w / 2f + dp(10), ly - dp(3), w / 2f + dp(30), ly - dp(3), iPaint[0]);
        c.drawText("I", w / 2f + dp(34), ly, labelPaint);
        labelPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setFakeBoldText(true);
    }

    private void drawRelay(Canvas c, float cx, float cy, float r, String title, Phasors ph, double ref, double vMax, double iMax) {
        c.drawCircle(cx, cy, r, gridPaint);
        c.drawCircle(cx, cy, r / 2f, gridPaint);
        c.drawLine(cx - r, cy, cx + r, cy, gridPaint);
        c.drawLine(cx, cy - r, cx, cy + r, gridPaint);
        c.drawText(ellipsize(title, r * 2), cx, cy - r - dp(5), titlePaint);
        for (int p = 0; p < 3; p++) {
            if (iMax > 0) arrow(c, cx, cy, r * 0.92f, ph.i[p], ref, iMax, iPaint[p], iHead[p], "I" + PHASES[p]);
            if (vMax > 0) arrow(c, cx, cy, r, ph.v[p], ref, vMax, vPaint[p], vPaint[p], "V" + PHASES[p]);
        }
    }

    private void arrow(Canvas c, float cx, float cy, float r, Cx v, double ref, double scale, Paint line, Paint head, String label) {
        double ang = v.arg() - ref;
        float len = (float) (r * v.abs() / scale);
        if (len < dp(2)) return;
        float x = cx + (float) (len * Math.cos(ang)), y = cy - (float) (len * Math.sin(ang));
        c.drawLine(cx, cy, x, y, line);
        float hl = dp(6);
        double back = Math.atan2(cy - y, x - cx);
        c.drawLine(x, y, x - (float) (hl * Math.cos(back - 0.45)), y + (float) (hl * Math.sin(back - 0.45)), head);
        c.drawLine(x, y, x - (float) (hl * Math.cos(back + 0.45)), y + (float) (hl * Math.sin(back + 0.45)), head);
        labelPaint.setColor(line.getColor());
        float lx = cx + (float) ((len + dp(9)) * Math.cos(ang)), ly = cy - (float) ((len + dp(9)) * Math.sin(ang)) + dp(3);
        c.drawText(label, lx, ly, labelPaint);
    }

    private String ellipsize(String s, float max) {
        if (titlePaint.measureText(s) <= max) return s;
        String out = s;
        while (out.length() > 1 && titlePaint.measureText(out + "...") > max) out = out.substring(0, out.length() - 1);
        return out + "...";
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
    }
}
