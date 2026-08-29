package com.alidev.dfrtools.dfr;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.util.AttributeSet;

import androidx.core.content.ContextCompat;

import com.alidev.dfrtools.R;
import com.github.mikephil.charting.animation.ChartAnimator;
import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.highlight.Highlight;
import com.github.mikephil.charting.interfaces.dataprovider.LineDataProvider;
import com.github.mikephil.charting.renderer.LineChartRenderer;
import com.github.mikephil.charting.utils.ViewPortHandler;

public class DfrLineChart extends LineChart {
    public DfrLineChart(Context context) { super(context); }
    public DfrLineChart(Context context, AttributeSet attrs) { super(context, attrs); }
    public DfrLineChart(Context context, AttributeSet attrs, int defStyle) { super(context, attrs, defStyle); }

    @Override
    protected void init() {
        super.init();
        mRenderer = new DfrLineChartRenderer(this, mAnimator, mViewPortHandler);
    }

    private static class DfrLineChartRenderer extends LineChartRenderer {
        private final Paint mCursorPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint mTriggerPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        public DfrLineChartRenderer(LineDataProvider chart, ChartAnimator animator, ViewPortHandler viewPortHandler) {
            super(chart, animator, viewPortHandler);
            Context context = ((DfrLineChart)chart).getContext();
            mCursorPaint.setStyle(Paint.Style.STROKE);
            mCursorPaint.setStrokeWidth(3.0f);
            mTriggerPaint.setStyle(Paint.Style.STROKE);
            mTriggerPaint.setStrokeWidth(1.5f);
            mTriggerPaint.setColor(ContextCompat.getColor(context, R.color.dfr_chart_trigger));
            mTriggerPaint.setPathEffect(new android.graphics.DashPathEffect(new float[]{10, 10}, 0));
        }

        @Override
        public void drawExtras(Canvas c) {
            super.drawExtras(c);
            drawTriggerLine(c);
        }

        private void drawTriggerLine(Canvas c) {
            LineData lineData = mChart.getLineData();
            if (lineData == null || lineData.getDataSetCount() == 0) return;
            float xVal = 0f;
            float xPix = (float) mChart.getTransformer(lineData.getDataSetByIndex(0).getAxisDependency()).getPixelForValues(xVal, 0).x;
            if (mViewPortHandler.isInBoundsX(xPix)) {
                c.drawLine(xPix, mViewPortHandler.contentTop(), xPix, mViewPortHandler.contentBottom(), mTriggerPaint);
            }
        }

        @Override
        public void drawHighlighted(Canvas c, Highlight[] indices) {
            LineData lineData = mChart.getLineData();
            if (lineData == null || indices == null) return;
            Context context = ((DfrLineChart)mChart).getContext();
            for (Highlight high : indices) {
                int colorRes;
                int dataIdx = high.getDataIndex();
                if (dataIdx == 0) colorRes = R.color.dfr_chart_cursor_c1;
                else if (dataIdx == 1) colorRes = R.color.dfr_chart_cursor_c2;
                else colorRes = R.color.dfr_chart_cursor_time;
                mCursorPaint.setColor(ContextCompat.getColor(context, colorRes));
                float xVal = high.getX();
                float xPix = (float) mChart.getTransformer(lineData.getDataSetByIndex(0).getAxisDependency()).getPixelForValues(xVal, 0).x;
                if (mViewPortHandler.isInBoundsX(xPix)) {
                    c.drawLine(xPix, mViewPortHandler.contentTop(), xPix, mViewPortHandler.contentBottom(), mCursorPaint);
                }
            }
        }
    }
}
