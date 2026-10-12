package com.alidev.dfrtools.dfr;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.alidev.dfrtools.R;
import com.alidev.dfrtools.dfr.DoubleEndedFaultLocator.Cx;

import java.util.Locale;

/**
 * Side-by-side polar plots of V1 and V0 from both line ends after time alignment: end A solid,
 * end B dashed, every angle measured from V1 of end A. Two vectors lying on top of each other
 * mean the two records are lined up at the same instant of the fault.
 */
public class PhasorDiagramView extends View {

    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint aPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint aHead = new Paint(Paint.ANTI_ALIAS_FLAG), bHead = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();

    private Cx v1A, v1B, v0A, v0B;
    private boolean v0Used;
    private String legendA = "A", legendB = "B";

    public PhasorDiagramView(Context context) { this(context, null); }

    public PhasorDiagramView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        TypedValue tv = new TypedValue();
        context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true);
        gridPaint.setColor(ContextCompat.getColor(context, R.color.divider_strong));
        gridPaint.setStyle(Paint.Style.STROKE);
        gridPaint.setStrokeWidth(dp(1));
        aPaint.setColor(tv.data);
        aPaint.setStrokeWidth(dp(2.5f));
        aPaint.setStrokeCap(Paint.Cap.ROUND);
        aPaint.setStyle(Paint.Style.STROKE);
        bPaint.setColor(ContextCompat.getColor(context, R.color.unit_accent_2));
        bPaint.setStrokeWidth(dp(2.5f));
        bPaint.setStrokeCap(Paint.Cap.ROUND);
        bPaint.setStyle(Paint.Style.STROKE);
        aHead.set(aPaint);
        bHead.set(bPaint);
        bPaint.setPathEffect(new DashPathEffect(new float[]{dp(6), dp(4)}, 0));
        textPaint.setColor(ContextCompat.getColor(context, R.color.text_primary));
        textPaint.setTextSize(sp(11));
        textPaint.setFakeBoldText(true);
        textPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setColor(ContextCompat.getColor(context, R.color.text_secondary));
        labelPaint.setTextSize(sp(10));
        labelPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setLegend(String a, String b) {
        legendA = a;
        legendB = b;
        invalidate();
    }

    public void setVectors(@Nullable Cx v1A, @Nullable Cx v1B, @Nullable Cx v0A, @Nullable Cx v0B, boolean v0Used) {
        this.v1A = v1A;
        this.v1B = v1B;
        this.v0A = v0A;
        this.v0B = v0B;
        this.v0Used = v0Used;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        if (v1A == null || v1B == null) return;
        float w = getWidth(), h = getHeight();
        float legendH = dp(16);
        float r = Math.min(w / 4f, (h - legendH - dp(28)) / 2f) - dp(4);
        float cy = dp(16) + r;
        double ref = v1A.arg();
        drawPlot(c, w * 0.25f, cy, r, "V1", v1A, v1B, ref, true);
        drawPlot(c, w * 0.75f, cy, r, v0Used ? "V0" : "V0 (~0)", v0A, v0B, ref, v0Used);

        float ly = h - dp(4);
        float lx = w / 2f;
        c.drawLine(lx - dp(150), ly - dp(4), lx - dp(126), ly - dp(4), aPaint);
        labelPaint.setTextAlign(Paint.Align.LEFT);
        c.drawText(legendA, lx - dp(120), ly, labelPaint);
        c.drawLine(lx + dp(10), ly - dp(4), lx + dp(34), ly - dp(4), bPaint);
        c.drawText(legendB, lx + dp(40), ly, labelPaint);
        labelPaint.setTextAlign(Paint.Align.CENTER);
    }

    private void drawPlot(Canvas c, float cx, float cy, float r, String title, @Nullable Cx a, @Nullable Cx b,
                          double ref, boolean meaningful) {
        c.drawCircle(cx, cy, r, gridPaint);
        c.drawLine(cx - r, cy, cx + r, cy, gridPaint);
        c.drawLine(cx, cy - r, cx, cy + r, gridPaint);
        c.drawText(title, cx, cy - r - dp(4), textPaint);
        if (a == null || b == null) return;
        double scale = Math.max(a.abs(), b.abs());
        if (scale <= 0) return;
        drawVector(c, cx, cy, r, a, ref, scale, aPaint, aHead);
        drawVector(c, cx, cy, r, b, ref, scale, bPaint, bHead);
        if (meaningful) {
            double d = Math.toDegrees(b.arg() - a.arg());
            while (d > 180) d -= 360;
            while (d <= -180) d += 360;
            c.drawText(String.format(Locale.US, "Δ %.1f°", d), cx, cy + r + dp(14), labelPaint);
        }
    }

    private void drawVector(Canvas c, float cx, float cy, float r, Cx v, double ref, double scale, Paint p, Paint solid) {
        double ang = v.arg() - ref;
        float len = (float) (r * v.abs() / scale);
        float x = cx + (float) (len * Math.cos(ang));
        float y = cy - (float) (len * Math.sin(ang));
        path.reset();
        path.moveTo(cx, cy);
        path.lineTo(x, y);
        c.drawPath(path, p);
        float head = dp(7);
        double back = Math.atan2(cy - y, x - cx);
        c.drawLine(x, y, x - (float) (head * Math.cos(back - 0.45)), y + (float) (head * Math.sin(back - 0.45)), solid);
        c.drawLine(x, y, x - (float) (head * Math.cos(back + 0.45)), y + (float) (head * Math.sin(back + 0.45)), solid);
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
    }
}
