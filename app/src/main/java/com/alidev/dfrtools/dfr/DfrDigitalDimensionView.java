package com.alidev.dfrtools.dfr;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;

import androidx.core.content.ContextCompat;

import com.alidev.dfrtools.R;

import java.util.List;
import java.util.Locale;

/**
 * Draws one CAD-style dimension line per digital-channel hold segment: a horizontal line spanning
 * the segment's time range, a short vertical tick at each end, and the segment's duration
 * ("xx,x ms") centered over the line. Segments butt up against each other exactly at each status
 * change, so consecutive dimension lines meet with no gap.
 */
public class DfrDigitalDimensionView extends View {
    private List<DfrViewerActivity.HoldSegment> segments;
    private Matrix sharedMatrix;
    private float startTime, totalTime;
    private float leftOffset;
    private float exportScaleRatio = 1.0f;

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private float tickHalfHeight, labelPadding;

    public DfrDigitalDimensionView(Context context) { super(context); init(); }
    public DfrDigitalDimensionView(Context context, AttributeSet attrs) { super(context, attrs); init(); }

    private void init() {
        int color = ContextCompat.getColor(getContext(), R.color.dfr_dimension_line);
        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(2.0f);
        linePaint.setColor(color);

        textPaint.setColor(color);
        textPaint.setTextAlign(Paint.Align.CENTER);

        // Must match the analog DfrLineChart's own left viewport offset (see DfrViewerActivity's
        // "sharedLeftOffset") exactly, or the trigger/cursor lines land a few pixels apart between
        // analog and digital rows even though both use the same time value.
        float density = getResources().getDisplayMetrics().density;
        leftOffset = 40f * density;
        tickHalfHeight = 5f * density;
        labelPadding = 3f * density;
        textPaint.setTextSize(getResources().getDimension(R.dimen.text_size_micro));
    }

    public void setData(List<DfrViewerActivity.HoldSegment> segments, float startTime, float totalTime) {
        this.segments = segments;
        this.startTime = startTime;
        this.totalTime = totalTime;
        invalidate();
    }

    public void setSyncMatrix(Matrix matrix) {
        this.sharedMatrix = matrix;
        invalidate();
    }

    public void setExportScaleRatio(float ratio) {
        this.exportScaleRatio = ratio;
        invalidate();
    }

    private static String formatDurationMs(float ms) {
        return String.format(Locale.US, "%.1f ms", ms).replace('.', ',');
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (segments == null || segments.isEmpty() || sharedMatrix == null) return;

        float[] mValues = new float[9];
        sharedMatrix.getValues(mValues);
        float scaleX = mValues[Matrix.MSCALE_X];
        float transX = mValues[Matrix.MTRANS_X] * exportScaleRatio;

        float contentWidth = getWidth() - leftOffset;
        float timeRange = totalTime - startTime;
        if (timeRange <= 0f) return;
        float lineY = getHeight() / 2f;

        for (DfrViewerActivity.HoldSegment seg : segments) {
            float normStart = (seg.startTime - startTime) / timeRange;
            float normEnd = (seg.endTime - startTime) / timeRange;
            float x1 = leftOffset + (normStart * contentWidth * scaleX) + transX;
            float x2 = leftOffset + (normEnd * contentWidth * scaleX) + transX;
            if (x2 < 0 || x1 > getWidth() || x2 <= x1) continue;

            canvas.drawLine(x1, lineY - tickHalfHeight, x1, lineY + tickHalfHeight, linePaint);
            canvas.drawLine(x2, lineY - tickHalfHeight, x2, lineY + tickHalfHeight, linePaint);

            String label = formatDurationMs(seg.durationMs);
            float midX = (x1 + x2) / 2f;
            float labelHalfWidth = textPaint.measureText(label) / 2f + labelPadding;

            // Only break the line for the label where it actually fits within this segment - an
            // oversized label on a short segment would otherwise spill into the next segment and
            // get painted over by its (later-drawn) line/ticks.
            if (labelHalfWidth * 2f < (x2 - x1)) {
                if (midX - labelHalfWidth > x1) canvas.drawLine(x1, lineY, midX - labelHalfWidth, lineY, linePaint);
                if (midX + labelHalfWidth < x2) canvas.drawLine(midX + labelHalfWidth, lineY, x2, lineY, linePaint);
                float textY = lineY - ((textPaint.descent() + textPaint.ascent()) / 2f);
                canvas.drawText(label, midX, textY, textPaint);
            } else {
                canvas.drawLine(x1, lineY, x2, lineY, linePaint);
            }
        }
    }
}
