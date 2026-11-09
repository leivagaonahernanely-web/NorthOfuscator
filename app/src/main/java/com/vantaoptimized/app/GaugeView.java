package com.vantaoptimized.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.SweepGradient;
import android.view.View;

/** Indicador circular en blanco y gris con degradado. */
public class GaugeView extends View {

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint valuePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint captionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private final float density;

    private float progress = 0f;
    private String value = "--";
    private String caption = "";

    public GaugeView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;

        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(10 * density);
        track.setColor(0x22FFFFFF);

        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setStrokeWidth(10 * density);

        valuePaint.setColor(Color.WHITE);
        valuePaint.setTextAlign(Paint.Align.CENTER);
        valuePaint.setTextSize(22 * density);
        valuePaint.setFakeBoldText(true);

        captionPaint.setColor(0xFF9E9E9E);
        captionPaint.setTextAlign(Paint.Align.CENTER);
        captionPaint.setTextSize(12 * density);
    }

    public void update(float fraction, String newValue, String newCaption) {
        progress = Math.max(0f, Math.min(1f, fraction));
        value = newValue;
        caption = newCaption;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        super.onSizeChanged(w, h, oldW, oldH);
        float pad = 8 * density + arc.getStrokeWidth() / 2f;
        oval.set(pad, pad, w - pad, h - pad);
        arc.setShader(new SweepGradient(w / 2f, h / 2f,
                new int[]{0xFF555555, 0xFFFFFFFF, 0xFFBBBBBB, 0xFF555555}, null));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        canvas.drawArc(oval, 0, 360, false, track);
        if (progress > 0f) {
            canvas.drawArc(oval, -90, 360f * progress, false, arc);
        }
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        canvas.drawText(value, cx, cy + valuePaint.getTextSize() / 3f, valuePaint);
        canvas.drawText(caption, cx, cy + valuePaint.getTextSize() * 1.2f + 8 * density, captionPaint);
    }
}
