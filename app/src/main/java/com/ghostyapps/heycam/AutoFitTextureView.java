package com.ghostyapps.heycam;

import android.content.Context;
import android.util.AttributeSet;
import android.view.TextureView;

public class AutoFitTextureView extends TextureView {
    public AutoFitTextureView(Context context) { this(context, null); }
    public AutoFitTextureView(Context context, AttributeSet attrs) { this(context, attrs, 0); }
    public AutoFitTextureView(Context context, AttributeSet attrs, int defStyle) { super(context, attrs, defStyle); }

    public void setAspectRatio(int width, int height) {
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        // Portrait (Dik) 4:3 Oranı
        // Yükseklik = Genişlik * 4 / 3
        int height = width * 4 / 3;
        setMeasuredDimension(width, height);
    }
}