package com.alidev.dfrtools.dfr;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;

import com.alidev.dfrtools.R;

/**
 * One-line diagram for the double-ended fault locator result: GI A busbar on the left, GI B
 * busbar on the right, the line between them, and a fault marker (with its +/- uncertainty band)
 * at the located distance.
 */
public class FaultLineView extends View {

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint busPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint faultPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bandPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path bolt = new Path();

    private String nameA = "A", nameB = "B";
    private double fraction = Double.NaN, spreadFraction = Double.NaN;
    private String faultLabel = "";

    public FaultLineView(Context context) { this(context, null); }

    public FaultLineView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        TypedValue tv = new TypedValue();
        context.getTheme().resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true);
        int primary = tv.data;
        int danger = ContextCompat.getColor(context, R.color.status_danger);

        linePaint.setColor(primary);
        linePaint.setStrokeWidth(dp(3));
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        busPaint.setColor(ContextCompat.getColor(context, R.color.text_primary));
        busPaint.setStrokeWidth(dp(5));
        busPaint.setStrokeCap(Paint.Cap.ROUND);
        faultPaint.setColor(danger);
        faultPaint.setStyle(Paint.Style.FILL);
        bandPaint.setColor((danger & 0x00FFFFFF) | 0x33000000);
        textPaint.setColor(danger);
        textPaint.setTextSize(sp(12));
        textPaint.setFakeBoldText(true);
        textPaint.setTextAlign(Paint.Align.CENTER);
        labelPaint.setColor(ContextCompat.getColor(context, R.color.text_secondary));
        labelPaint.setTextSize(sp(11));
        labelPaint.setFakeBoldText(true);
    }

    public void setEnds(String a, String b) {
        nameA = a;
        nameB = b;
        invalidate();
    }

    /** @param fraction 0..1 from end A; spread is the +/- uncertainty as a fraction (NaN = none). */
    public void setFault(double fraction, double spreadFraction, String label) {
        this.fraction = fraction;
        this.spreadFraction = spreadFraction;
        this.faultLabel = label;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float w = getWidth(), h = getHeight();
        float pad = dp(16);
        float lineY = h * 0.52f;
        float x0 = pad, x1 = w - pad;

        c.drawLine(x0, lineY, x1, lineY, linePaint);
        c.drawLine(x0, lineY - dp(14), x0, lineY + dp(14), busPaint);
        c.drawLine(x1, lineY - dp(14), x1, lineY + dp(14), busPaint);

        float labelY = lineY + dp(30);
        labelPaint.setTextAlign(Paint.Align.LEFT);
        c.drawText(ellipsize(nameA, (x1 - x0) / 2 - dp(8)), x0 - dp(4), labelY, labelPaint);
        labelPaint.setTextAlign(Paint.Align.RIGHT);
        c.drawText(ellipsize(nameB, (x1 - x0) / 2 - dp(8)), x1 + dp(4), labelY, labelPaint);

        if (Double.isNaN(fraction)) return;
        float f = (float) Math.max(0, Math.min(1, fraction));
        float fx = x0 + (x1 - x0) * f;

        if (!Double.isNaN(spreadFraction) && spreadFraction > 0) {
            float half = (float) ((x1 - x0) * spreadFraction);
            float bl = Math.max(x0, fx - half), br = Math.min(x1, fx + half);
            c.drawRoundRect(new RectF(bl, lineY - dp(7), br, lineY + dp(7)), dp(4), dp(4), bandPaint);
        }

        // lightning bolt marker centred on the fault point
        float s = dp(13);
        float top = lineY - s * 1.3f;
        bolt.reset();
        bolt.moveTo(fx + s * 0.25f, top);
        bolt.lineTo(fx - s * 0.45f, lineY + s * 0.05f);
        bolt.lineTo(fx - s * 0.02f, lineY + s * 0.05f);
        bolt.lineTo(fx - s * 0.25f, lineY + s * 1.05f);
        bolt.lineTo(fx + s * 0.45f, lineY - s * 0.2f);
        bolt.lineTo(fx + s * 0.02f, lineY - s * 0.2f);
        bolt.close();
        c.drawPath(bolt, faultPaint);

        float textY = top - dp(5);
        float tw = textPaint.measureText(faultLabel) / 2;
        float tx = Math.max(x0 + tw, Math.min(x1 - tw, fx));
        c.drawText(faultLabel, tx, textY, textPaint);
    }

    private String ellipsize(String s, float max) {
        if (labelPaint.measureText(s) <= max) return s;
        String out = s;
        while (out.length() > 1 && labelPaint.measureText(out + "…") > max) out = out.substring(0, out.length() - 1);
        return out + "…";
    }

    private float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    private float sp(float v) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, getResources().getDisplayMetrics());
    }
}
