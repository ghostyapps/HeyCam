package com.ghostyapps.heycam;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

public class CubeLut {

    private int lutSize;
    private int[] lutData; // RGB verilerini tek boyutlu dizide tutacağız (Hız için)
    private boolean isLoaded = false;

    // Dosyayı yükleyip Parse eden metod
    public boolean load(File cubeFile) {
        if (!cubeFile.exists()) return false;

        try (BufferedReader br = new BufferedReader(new FileReader(cubeFile))) {
            String line;
            int dataIndex = 0;

            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) continue; // Yorumları atla

                if (line.startsWith("LUT_3D_SIZE")) {
                    String[] parts = line.split("\\s+");
                    lutSize = Integer.parseInt(parts[parts.length - 1]);
                    // Cube boyutu: Size * Size * Size * 3 (RGB) yok ama biz int color tutacağız
                    lutData = new int[lutSize * lutSize * lutSize];
                    continue;
                }

                if (line.startsWith("TITLE") || line.startsWith("DOMAIN")) continue;

                // Veri satırları (R G B)
                // Örnek: 0.000000 0.125000 0.500000
                if (lutData != null && dataIndex < lutData.length) {
                    String[] parts = line.split("\\s+");
                    if (parts.length >= 3) {
                        float r = Float.parseFloat(parts[0]);
                        float g = Float.parseFloat(parts[1]);
                        float b = Float.parseFloat(parts[2]);

                        // 0.0 - 1.0 aralığını 0 - 255 aralığına çek
                        int ir = (int) (r * 255);
                        int ig = (int) (g * 255);
                        int ib = (int) (b * 255);

                        // Clamp (Taşmaları önle)
                        ir = Math.min(255, Math.max(0, ir));
                        ig = Math.min(255, Math.max(0, ig));
                        ib = Math.min(255, Math.max(0, ib));

                        lutData[dataIndex++] = Color.rgb(ir, ig, ib);
                    }
                }
            }
            isLoaded = true;
            return true;

        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    // Bitmap'e LUT uygulayan metod
    public Bitmap apply(Bitmap src) {
        if (!isLoaded || lutData == null) return src;

        int width = src.getWidth();
        int height = src.getHeight();
        int[] pixels = new int[width * height];

        // 1. Tüm pikselleri diziye al
        src.getPixels(pixels, 0, width, 0, 0, width, height);

        // 2. Her pikseli dönüştür
        // NOT: Performans için Trilinear Interpolation yerine Nearest Neighbor kullanıyoruz.
        // Fotoğraf çözünürlüğü yüksek olduğu için gözle fark edilmesi zordur.

        float scale = (lutSize - 1) / 255f;

        for (int i = 0; i < pixels.length; i++) {
            int color = pixels[i];
            int r = (color >> 16) & 0xFF;
            int g = (color >> 8) & 0xFF;
            int b = color & 0xFF;

            // RGB değerini LUT koordinatına çevir
            int lr = Math.round(r * scale);
            int lg = Math.round(g * scale);
            int lb = Math.round(b * scale);

            // 3D Dizideki karşılığını bul: Index = r + g*size + b*size*size
            // Standart .cube formatında sıralama genelde R değişir, sonra G, sonra B
            int index = lr + (lg * lutSize) + (lb * lutSize * lutSize);

            if (index >= 0 && index < lutData.length) {
                pixels[i] = lutData[index];
            }
        }

        // 3. Yeni pikselleri bitmap'e yaz (Mutable olması lazım)
        // Orijinal bitmap'i bozmamak için kopyası üzerinde çalışmak daha iyidir ama bellek sorunu olabilir.
        // Şimdilik kaynak üzerine yazıyoruz (src mutable olmalı).
        src.setPixels(pixels, 0, width, 0, 0, width, height);
        return src;
    }
}