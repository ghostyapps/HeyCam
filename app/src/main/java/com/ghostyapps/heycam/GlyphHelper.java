package com.ghostyapps.heycam;

import android.content.ComponentName;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.nothing.ketchum.GlyphMatrixManager;
import com.nothing.ketchum.Glyph;

import java.util.Arrays;

public class GlyphHelper {

    private GlyphMatrixManager mGM;
    private boolean isConnected = false;
    private Context mContext;

    // Güvenlik Bayrağı
    private boolean isSupported = true;

    public void prepare(Context context) {
        this.mContext = context;
    }

    // --- BAĞLANTI KUR ---
    public void connect() {
        if (!isSupported || mContext == null) return;

        try {
            if (mGM == null) {
                mGM = GlyphMatrixManager.getInstance(mContext);
            }

            if (mGM == null) {
                isSupported = false;
                Log.e("GlyphHelper", "Device not supported (Manager is null)");
                return;
            }

            GlyphMatrixManager.Callback mCallback = new GlyphMatrixManager.Callback() {
                @Override
                public void onServiceConnected(ComponentName componentName) {
                    isConnected = true;
                    try {
                        mGM.register(Glyph.DEVICE_23112);
                        Log.d("GlyphHelper", "Connection Established");

                        int[] wakeUpFrame = new int[625];
                        Arrays.fill(wakeUpFrame, 0);
                        mGM.setAppMatrixFrame(wakeUpFrame);

                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void onServiceDisconnected(ComponentName componentName) {
                    isConnected = false;
                }
            };

            if (!isConnected) {
                mGM.init(mCallback);
            } else {
                try { mGM.register(Glyph.DEVICE_23112); } catch (Exception e) {}
            }

        } catch (Exception e) {
            isSupported = false;
            Log.e("GlyphHelper", "Glyph init failed: " + e.getMessage());
            e.printStackTrace();
        }
    }

    // --- SIFIRLAMA ---
    public void reset() {
        if (!isSupported) return;
        try {
            if (mGM != null) {
                mGM.closeAppMatrix();
                mGM.unInit();
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        mGM = null;
        isConnected = false;
        Log.d("GlyphHelper", "State Reset");
    }

    // --- SAYAÇ ---
    public void displayCounter(int number, int rotation) {
        if (!isSupported) return;

        if (!isConnected || mGM == null) {
            connect();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if(isConnected) displayCounter(number, rotation);
            }, 150);
            return;
        }

        try {
            int[] frameData = new int[625];
            Arrays.fill(frameData, 0);

            int[][] digitMap = GlyphDigits.getMap(number);
            int SCALE = 3;
            int startX = 8;
            int startY = 5;

            for (int r = 0; r < 5; r++) {
                for (int c = 0; c < 3; c++) {
                    if (digitMap[r][c] == 1) {
                        for (int sr = 0; sr < SCALE; sr++) {
                            for (int sc = 0; sc < SCALE; sc++) {
                                int x = startX + (c * SCALE) + sc;
                                int y = startY + (r * SCALE) + sr;
                                int index = getRotatedIndex(x, y, rotation);
                                if (index >= 0 && index < 625) frameData[index] = 255;
                            }
                        }
                    }
                }
            }
            mGM.setMatrixFrame(frameData);

        } catch (Exception e) { e.printStackTrace(); }
    }

    // --- FİNAL DESENİ ---
    public void displayFinishPattern(int rotation) {
        if (!isSupported) return;

        if (!isConnected || mGM == null) {
            connect();
            new Handler(Looper.getMainLooper()).postDelayed(() -> {
                if(isConnected) displayFinishPattern(rotation);
            }, 100);
            return;
        }

        try {
            int[] frameData = new int[625];
            Arrays.fill(frameData, 0);

            int[][] map = {
                    {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1},
                    {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1},
                    {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1},
                    {0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 0},
                    {1, 1, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 1, 0, 0},
                    {1, 1, 1, 1, 0, 0, 0, 0, 1, 1, 1, 1, 1, 0, 0, 0},
                    {1, 1, 1, 1, 1, 0, 0, 1, 1, 1, 1, 1, 0, 0, 0, 0},
                    {0, 1, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0},
                    {0, 0, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0},
                    {0, 0, 0, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0},
                    {0, 0, 0, 0, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0},
                    {0, 0, 0, 0, 0, 1, 1, 0, 0, 0, 0, 0, 0, 0, 0, 0}
            };

            int SCALE = 1;
            int startX = 5;
            int startY = 6;
            int rows = map.length;
            int cols = map[0].length;

            for (int r = 0; r < rows; r++) {
                for (int c = 0; c < cols; c++) {
                    if (map[r][c] == 1) {
                        for (int sr = 0; sr < SCALE; sr++) {
                            for (int sc = 0; sc < SCALE; sc++) {
                                int x = startX + (c * SCALE) + sc;
                                int y = startY + (r * SCALE) + sr;
                                int index = getRotatedIndex(x, y, rotation);
                                if (index >= 0 && index < 625) frameData[index] = 255;
                            }
                        }
                    }
                }
            }
            mGM.setMatrixFrame(frameData);
            Log.d("GlyphHelper", "Custom Pattern Sent!");

        } catch (Exception e) { e.printStackTrace(); }
    }

    public void clearDisplay() {
        if (!isSupported) return;
        if (mGM != null) {
            try {
                int[] empty = new int[625];
                mGM.setMatrixFrame(empty);
            } catch (Exception e) {}
        }
    }

    public void disconnect() {
        if (!isSupported) return;
        try {
            if (mGM != null) {
                if (isConnected) mGM.closeAppMatrix();
                mGM.unInit();
                mGM = null;
                isConnected = false;
            }
        } catch (Exception e) {}
    }

    // --- HELPER METODLAR ---
    private int getRotatedIndex(int x, int y, int rotation) {
        int finalX = x; int finalY = y; int MAX = 24;
        switch (rotation) {
            case 90: finalX = MAX - y; finalY = x; break;
            case 180: finalX = MAX - x; finalY = MAX - y; break;
            case 270: finalX = y; finalY = MAX - x; break;
            default: finalX = x; finalY = y; break;
        }
        return (finalY * 25) + finalX;
    }

    public void updateProgress(int i) {}
}