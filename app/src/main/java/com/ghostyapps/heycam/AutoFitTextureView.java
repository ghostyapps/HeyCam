package com.ghostyapps.heycam;

import android.content.Context;
import android.util.AttributeSet;
import android.view.TextureView;

public class AutoFitTextureView extends TextureView {
    private int mRatioWidth = 0;
    private int mRatioHeight = 0;

    public AutoFitTextureView(Context context) {
        this(context, null);
    }

    public AutoFitTextureView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public AutoFitTextureView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    /**
     * View'ın en-boy oranını ayarlar.
     * Open Camera mantığına göre:
     * Telefon dik (Portrait) ise: setAspectRatio(3, 4) gönderilmeli (veya width < height).
     * Telefon yan (Landscape) ise: setAspectRatio(4, 3) gönderilmeli.
     */
    public void setAspectRatio(int width, int height) {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException("Size cannot be negative.");
        }
        mRatioWidth = width;
        mRatioHeight = height;
        requestLayout();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);

        if (0 == mRatioWidth || 0 == mRatioHeight) {
            setMeasuredDimension(width, height);
        } else {
            // OPEN CAMERA MANTIĞI:
            // Amaç: Aspect ratio'yu koruyarak, verilen alana (width/height) "sığdırmak" (Letterboxing).
            // Eğer ekranı tamamen doldurmak (Center Crop) istiyorsan mantık tersine döner.
            // Open Camera preview penceresini bozmadan sığdırır.

            if (width < height * mRatioWidth / mRatioHeight) {
                // Genişlik kısıtlıysa, yüksekliği orana göre ayarla
                setMeasuredDimension(width, width * mRatioHeight / mRatioWidth);
            } else {
                // Yükseklik kısıtlıysa, genişliği orana göre ayarla
                setMeasuredDimension(height * mRatioWidth / mRatioHeight, height);
            }
        }
    }
}