package com.alidev.dfrtools.dfr;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.util.AttributeSet;
import android.view.View;

import java.util.List;

/** Lightweight canvas-drawn trend line for a monitored point's recent value history. */
public class SparklineView extends View {
    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private List<Float> data;

    public SparklineView(Context context, AttributeSet attrs) {
        super(context, attrs);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(dp(2));
        linePaint.setStrokeJoin(Paint.Join.ROUND);
        linePaint.setStrokeCap(Paint.Cap.ROUND);
        linePaint.setColor(Color.GRAY);
        dotPaint.setStyle(Paint.Style.FILL);
        dotPaint.setColor(Color.GRAY);
    }

    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    public void setData(List<Float> data, int color) {
        this.data = data;
        linePaint.setColor(color);
        dotPaint.setColor(color);
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        List<Float> snapshot = data;
        if (snapshot == null || snapshot.size() < 2) return;

        float min = Float.MAX_VALUE, max = -Float.MAX_VALUE;
        for (float v : snapshot) {
            if (v < min) min = v;
            if (v > max) max = v;
        }
        float range = max - min;
        if (range == 0f) range = 1f; // flat line: keep it centered instead of dividing by zero

        int w = getWidth();
        int h = getHeight();
        float padding = dp(2);
        float usableH = h - padding * 2;
        int n = snapshot.size();
        float stepX = n > 1 ? (float) w / (n - 1) : 0;

        Path path = new Path();
        float lastX = 0, lastY = 0;
        for (int i = 0; i < n; i++) {
            float x = i * stepX;
            float norm = (snapshot.get(i) - min) / range;
            float y = padding + (1f - norm) * usableH;
            if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            lastX = x;
            lastY = y;
        }
        canvas.drawPath(path, linePaint);
        canvas.drawCircle(lastX, lastY, dp(2.5f), dotPaint);
    }
}
