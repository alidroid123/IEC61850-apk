package com.alidev.dfrtools.dfr;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.DashPathEffect;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.alidev.dfrtools.R;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.highlight.Highlight;

import java.util.List;

public class DfrDigitalView extends View {
    private List<Entry> entries;
    private Matrix sharedMatrix;
    private float startTime, totalTime;
    private Highlight[] cursors;
    private float leftOffset;
    private float exportScaleRatio = 1.0f;

    private final Paint blockPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint cursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint triggerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public DfrDigitalView(Context context) { super(context); init(); }
    public DfrDigitalView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        blockPaint.setStyle(Paint.Style.FILL);
        blockPaint.setColor(ContextCompat.getColor(getContext(), R.color.dfr_digital_block));
        blockPaint.setAlpha(180);

        cursorPaint.setStyle(Paint.Style.STROKE);
        cursorPaint.setStrokeWidth(3.0f);

        gridPaint.setColor(ContextCompat.getColor(getContext(), R.color.dfr_chart_grid));
        gridPaint.setStrokeWidth(1.0f);

        triggerPaint.setStyle(Paint.Style.STROKE);
        triggerPaint.setStrokeWidth(1.5f);
        triggerPaint.setColor(ContextCompat.getColor(getContext(), R.color.dfr_chart_trigger));
        triggerPaint.setPathEffect(new DashPathEffect(new float[]{10, 10}, 0));
        
        float density = getResources().getDisplayMetrics().density;
        leftOffset = 45f * density;
    }

    public void setData(List<Entry> entries, float startTime, float totalTime) {
        this.entries = entries;
        this.startTime = startTime;
        this.totalTime = totalTime;
        invalidate();
    }

    public void setSyncState(Matrix matrix, Highlight[] cursors) {
        this.sharedMatrix = matrix;
        this.cursors = cursors;
        invalidate();
    }

    public void setExportScaleRatio(float ratio) {
        this.exportScaleRatio = ratio;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (entries == null || entries.isEmpty() || sharedMatrix == null) return;

        float[] mValues = new float[9];
        sharedMatrix.getValues(mValues);
        float scaleX = mValues[Matrix.MSCALE_X];
        float transX = mValues[Matrix.MTRANS_X] * exportScaleRatio;

        float contentWidth = getWidth() - leftOffset;
        float height = getHeight();
        float timeRange = totalTime - startTime;

        for (int i = 0; i < entries.size() - 1; i++) {
            Entry current = entries.get(i);
            if (current.getY() > 0.5f) {
                Entry next = entries.get(i + 1);
                float normStart = (current.getX() - startTime) / timeRange;
                float normEnd = (next.getX() - startTime) / timeRange;
                float drawXStart = leftOffset + (normStart * contentWidth * scaleX) + transX;
                float drawXEnd = leftOffset + (normEnd * contentWidth * scaleX) + transX;
                if (drawXEnd < 0 || drawXStart > getWidth()) continue;
                canvas.drawRect(Math.max(leftOffset, drawXStart), 0, Math.min(getWidth(), drawXEnd), height, blockPaint);
            }
        }

        float normTrigger = (0f - startTime) / timeRange;
        float drawXTrigger = leftOffset + (normTrigger * contentWidth * scaleX) + transX;
        if (drawXTrigger >= leftOffset && drawXTrigger <= getWidth()) {
            canvas.drawLine(drawXTrigger, 0, drawXTrigger, height, triggerPaint);
        }

        if (cursors != null) {
            for (Highlight h : cursors) {
                if (h == null) continue;
                int colorRes;
                int dataIdx = h.getDataIndex();
                if (dataIdx == 0) colorRes = R.color.dfr_chart_cursor_c1;
                else if (dataIdx == 1) colorRes = R.color.dfr_chart_cursor_c2;
                else colorRes = R.color.dfr_chart_cursor_time;
                cursorPaint.setColor(ContextCompat.getColor(getContext(), colorRes));
                float normCursor = (h.getX() - startTime) / timeRange;
                float drawXCursor = leftOffset + (normCursor * contentWidth * scaleX) + transX;
                if (drawXCursor >= leftOffset && drawXCursor <= getWidth()) {
                    canvas.drawLine(drawXCursor, 0, drawXCursor, height, cursorPaint);
                }
            }
        }
        canvas.drawLine(leftOffset, 0, leftOffset, height, gridPaint);
        canvas.drawLine(0, height - 1, getWidth(), height - 1, gridPaint);
    }
}
