package com.zm920.androidserver.ui.widget;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;

import com.zm920.androidserver.R;

public class CircularProgressView extends View {

    private Paint bgPaint, pgPaint, textPaint, labelPaint;
    private RectF arcRect;

    private float progress = 0f;
    private float maxProgress = 100f;
    private String centerText = "0%";
    private String labelText = "";
    private String subText = "";

    private int strokeWidth;
    private int bgColor = Color.parseColor("#FFE3F2FD");
    private int pgColor = Color.parseColor("#FF1976D2");

    public CircularProgressView(Context context) {
        super(context);
        init(null);
    }

    public CircularProgressView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        init(attrs);
    }

    private void init(@Nullable AttributeSet attrs) {
        if (attrs != null) {
            TypedArray a = getContext().obtainStyledAttributes(attrs, R.styleable.CircularProgressView);
            progress = a.getFloat(R.styleable.CircularProgressView_progress, 0);
            maxProgress = a.getFloat(R.styleable.CircularProgressView_maxProgress, 100);
            strokeWidth = a.getDimensionPixelSize(R.styleable.CircularProgressView_strokeWidth, dp(8));
            bgColor = a.getColor(R.styleable.CircularProgressView_backgroundColor, bgColor);
            pgColor = a.getColor(R.styleable.CircularProgressView_progressColor, pgColor);
            labelText = a.getString(R.styleable.CircularProgressView_labelText) != null
                    ? a.getString(R.styleable.CircularProgressView_labelText) : "";
            a.recycle();
        } else {
            strokeWidth = dp(8);
        }

        bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        bgPaint.setColor(bgColor);
        bgPaint.setStyle(Paint.Style.STROKE);
        bgPaint.setStrokeWidth(strokeWidth);
        bgPaint.setStrokeCap(Paint.Cap.ROUND);

        pgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        pgPaint.setColor(pgColor);
        pgPaint.setStyle(Paint.Style.STROKE);
        pgPaint.setStrokeWidth(strokeWidth);
        pgPaint.setStrokeCap(Paint.Cap.ROUND);

        textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(Color.parseColor("#FF1C1B1F"));
        textPaint.setTextAlign(Paint.Align.CENTER);
        textPaint.setFakeBoldText(true);

        labelPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        labelPaint.setColor(Color.parseColor("#FF8A8A8A"));
        labelPaint.setTextAlign(Paint.Align.CENTER);

        arcRect = new RectF();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int size = Math.min(
                MeasureSpec.getSize(widthMeasureSpec),
                MeasureSpec.getSize(heightMeasureSpec));
        setMeasuredDimension(size, size);
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        int size = Math.min(w, h);
        int pad = strokeWidth / 2 + 2;
        arcRect.set(pad, pad, size - pad, size - pad);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        int size = Math.min(getWidth(), getHeight());
        float cx = size / 2f;
        float cy = size / 2f;

        // 背景圆环
        canvas.drawArc(arcRect, 270, 360, false, bgPaint);

        // 进度弧
        float sweep = (maxProgress > 0) ? (progress / maxProgress) * 360f : 0f;
        canvas.drawArc(arcRect, 270, sweep, false, pgPaint);

        // 百分比（圆心正中间偏上）
        float pctSize = size * 0.22f;
        textPaint.setTextSize(pctSize);
        Paint.FontMetrics fm = textPaint.getFontMetrics();
        float textH = fm.descent - fm.ascent;
        float textY = cy - dp(2);
        canvas.drawText(centerText, cx, textY, textPaint);

        // 标签（百分比下方）
        float labelY = 0;
        if (!labelText.isEmpty()) {
            float lblSize = size * 0.09f;
            labelPaint.setTextSize(lblSize);
            labelY = textY + textH * 0.6f + dp(2);
            canvas.drawText(labelText, cx, labelY, labelPaint);
        }

        // 子文字（标签下方，间距）
        if (!subText.isEmpty()) {
            float subSize = size * 0.065f;
            if (subText.length() > 15) subSize = size * 0.055f;
            labelPaint.setTextSize(subSize);
            Paint.FontMetrics sfm = labelPaint.getFontMetrics();
            float subH = sfm.descent - sfm.ascent;
            // 位于标签下方，留出间距
            float subY = labelY > 0 ? labelY + subH + dp(2) : textY + textH + dp(4);
            canvas.drawText(subText, cx, subY, labelPaint);
        }
    }

    public void setProgress(float p) {
        this.progress = p;
        this.centerText = Math.round(p) + "%";
        invalidate();
    }

    public void setProgressAnimated(float target) {
        ValueAnimator anim = ValueAnimator.ofFloat(progress, target);
        anim.setDuration(600);
        anim.addUpdateListener(a -> {
            float v = (float) a.getAnimatedValue();
            progress = v;
            centerText = Math.round(v) + "%";
            invalidate();
        });
        anim.start();
    }

    public void setLabelText(String s) { labelText = s; invalidate(); }
    public void setSubText(String s)   { subText = s; invalidate(); }

    public void setThemeColors(int bgColor, int pgColor) {
        this.bgColor = bgColor;
        this.pgColor = pgColor;
        bgPaint.setColor(bgColor);
        pgPaint.setColor(pgColor);
        invalidate();
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v + 0.5f);
    }
}
