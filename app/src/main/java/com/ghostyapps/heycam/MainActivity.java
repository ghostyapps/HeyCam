package com.ghostyapps.heycam;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSnapHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SnapHelper;

import java.util.ArrayList;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private static final String TAG = "HeyCam";
    private static final int REQUEST_CAMERA_PERMISSION = 200;

    // --- 1. UI BİLEŞENLERİ ---
    private AutoFitTextureView textureView;
    private RecyclerView rulerRecycler;
    private TextView txtIsoInfo;
    private TextView btnAutoManual;

    // Butonlar & Seçiciler
    private TextView btnShutter, btnIso;
    private ImageButton btnTakePhoto;
    private ImageButton btnFormat; // JPEG/RAW Değiştirici

    // Görsel Efektler
    private View focusRing;
    private View wheelNeedle;
    private View shutterFlash;

    private ImageButton btnGallery;
    private ImageButton btnModeSwitch;

    // --- 2. DURUM YÖNETİMİ & VERİLER ---
    private enum WheelMode { SHUTTER, ISO }
    private WheelMode currentWheelMode = WheelMode.SHUTTER;
    private boolean isManualMode = false;
    private boolean isRawMode = false; // Varsayılan JPEG
    private boolean mContainsRawCapability = false; // Cihaz RAW destekliyor mu?

    // Cetvel Verileri
    private final double[] SHUTTER_VALUES = {10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1000.0};
    private final List<String> SHUTTER_STRINGS = Arrays.asList("1/10", "1/25", "1/50", "1/100", "1/250", "1/500", "1/1000");
    private int currentShutterIndex = 3;

    // YENİ SATIR EKLE:
    private List<String> ISO_STRINGS = new ArrayList<>(); // Dinamik olarak doldurulacak
    private int currentIsoIndex = 1;

    // Aktif Kamera Değerleri
    private int selectedIso = 100;
    private long selectedShutter = 10000000L;

    // --- 3. CAMERA2 API BİLEŞENLERİ ---
    private String cameraId;
    private CameraDevice cameraDevice;
    private CameraCaptureSession cameraCaptureSessions;
    private CaptureRequest.Builder captureRequestBuilder;
    private Size imageDimension;
    private int mSensorOrientation = 90; // Kameranın montaj açısı

    // Cihaz Limitleri
    private Range<Integer> isoRange;
    private Range<Long> shutterRange;
    private Range<Integer> bestFpsRange;
    private android.graphics.Rect sensorArraySize;

    // Fotoğraf Okuyucular & Metadata
    private android.media.ImageReader imageReader;    // JPEG için
    private android.media.ImageReader imageReaderRaw; // RAW için
    private TotalCaptureResult mLastResult;           // DNG oluşturmak için gerekli

    // Arka Plan İşlemleri
    private Handler mBackgroundHandler;
    private HandlerThread mBackgroundThread;


    private TextView customToast;
    private android.os.Handler toastHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private Runnable hideToastRunnable;



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Tam ekran modu - status bar ve navigation bar gizle
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );

        setContentView(R.layout.activity_main);

        customToast = findViewById(R.id.custom_toast);

        // --- 1. VIEW BAĞLANTILARI ---
        textureView = findViewById(R.id.texture_view);
        txtIsoInfo = findViewById(R.id.txt_iso_display);

        // Cetvel ve Görsel Efektler
        rulerRecycler = findViewById(R.id.shutter_recycler);
        focusRing = findViewById(R.id.focus_ring);
        wheelNeedle = findViewById(R.id.wheel_needle);
        shutterFlash = findViewById(R.id.shutter_flash);

        // Metin Seçiciler
        btnShutter = findViewById(R.id.btn_shutter);
        btnIso = findViewById(R.id.btn_iso);


        // Alt Kontrol Butonları
        btnTakePhoto = findViewById(R.id.take_photo);
        btnModeSwitch = findViewById(R.id.btn_mode_switch); // Auto/Manual

        // Üst Format Butonu
        btnFormat = findViewById(R.id.btn_format_indicator);

        // --- 2. YUVARLAK KÖŞE ZORLAMASI (FIX) ---
        // TextureView bazen CardView sınırlarını takmaz. Bu kod onu zorlar.
        androidx.cardview.widget.CardView card = findViewById(R.id.preview_card_container);
        card.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
        card.setClipToOutline(true);

        // --- 3. UI KURULUMU ---
        setupButtons();
        setupRuler();

        // Başlangıç Ayarı: Auto Mod
        toggleUiForMode(false);
        txtIsoInfo.setText("HeyCam");

        // --- 4. DOKUNMATİK ODAKLAMA ---
        textureView.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                float x = event.getX();
                float y = event.getY();
                showFocusRect(x, y);
                startFocus(x, y);
            }
            return true;
        });
    }
    // --- UI SETUP & LOGIC ---

    private void setupButtons() {
        // --- 1. GALERİ BUTONU ---
        ImageButton btnGallery = findViewById(R.id.btn_gallery);
        if (btnGallery != null) {
            btnGallery.setOnClickListener(v -> openLastPhoto());
        }

        // --- 2. FORMAT DEĞİŞTİRİCİ (JPEG / RAW) ---
        btnFormat.setOnClickListener(v -> {
            if (!mContainsRawCapability) {
                Toast.makeText(this, "This device does not support RAW", Toast.LENGTH_SHORT).show();
                return;
            }
            isRawMode = !isRawMode;
            if (isRawMode) {
                btnFormat.setImageResource(R.drawable.ic_raw_indicator);
                btnFormat.setAlpha(1.0f);
                //Toast.makeText(this, "Format: RAW (DNG)", Toast.LENGTH_SHORT).show();
            } else {
                btnFormat.setImageResource(R.drawable.ic_jpeg_indicator);
                btnFormat.setAlpha(0.9f);
                //Toast.makeText(this, "Format: JPEG", Toast.LENGTH_SHORT).show();
            }
        });

        // --- 3. AUTO / MANUAL MOD DEĞİŞTİRİCİ ---
        btnModeSwitch.setOnClickListener(v -> {
            boolean targetIsManual = !isManualMode;

            if (targetIsManual) {
                // -> MANUAL MODA GEÇİŞ
                isManualMode = true;

                // İKONU GÜNCELLE: "M" İkonu (Manual'de olduğumuzu gösterir)
                btnModeSwitch.setImageResource(R.drawable.ic_manual_indicator);

                toggleUiForMode(true); // Cetveli göster

                // Varsayılan Değerler
                selectedIso = 100;
                selectedShutter = 10_000_000L;
                currentIsoIndex = 1;
                currentShutterIndex = 3;
                updateInfoText();

                int targetIndex = (currentWheelMode == WheelMode.ISO) ? currentIsoIndex : currentShutterIndex;
                LinearLayoutManager layoutManager = (LinearLayoutManager) rulerRecycler.getLayoutManager();
                if (layoutManager != null) {
                    layoutManager.scrollToPositionWithOffset(targetIndex, 0);
                }
                updatePreview();

            } else {
                // -> AUTO MODA GEÇİŞ
                // İKONU GÜNCELLE: "A" İkonu (Auto'da olduğumuzu gösterir)
                btnModeSwitch.setImageResource(R.drawable.ic_auto_indicator);

                // Şok Tedavisi
                if (isoRange != null) selectedIso = isoRange.getLower();
                selectedShutter = 1_000_000L;
                updatePreview();

                new Handler().postDelayed(() -> {
                    if (isFinishing() || cameraDevice == null) return;
                    isManualMode = false;
                    toggleUiForMode(false); // Cetveli gizle
                    currentIsoIndex = 0;
                    rulerRecycler.scrollToPosition(0);
                    updatePreview();
                }, 100);
            }
        });

        // --- 4. FOTOĞRAF ÇEKME ---
        btnTakePhoto.setOnClickListener(v -> {
            shutterFlash.setAlpha(0.8f);
            shutterFlash.animate().alpha(0f).setDuration(150)
                    .start();
            takePicture();
        });

        // --- 5. TEKERLEK SEÇİMİ ---
        if (btnIso != null) btnIso.setOnClickListener(v -> switchWheelMode(WheelMode.ISO));
        if (btnShutter != null) btnShutter.setOnClickListener(v -> switchWheelMode(WheelMode.SHUTTER));
    }



    private void setupRuler() {
        LinearLayoutManager layoutManager = new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false);
        rulerRecycler.setLayoutManager(layoutManager);

        // Başlangıç verisi (Moda göre)
        List<String> initialData = (currentWheelMode == WheelMode.ISO) ? ISO_STRINGS : SHUTTER_STRINGS;
        RulerAdapter adapter = new RulerAdapter(initialData);
        rulerRecycler.setAdapter(adapter);

        // SnapHelper (Tekerlek mantığı: Öğeyi merkeze mıknatısla çeker)
        LinearSnapHelper snapHelper = new LinearSnapHelper();
        rulerRecycler.setOnFlingListener(null); // Çakışmaları önlemek için
        snapHelper.attachToRecyclerView(rulerRecycler);

        // Görünüm çizildikten sonra Padding hesapla (Merkeze oturtmak için)
        rulerRecycler.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                rulerRecycler.getViewTreeObserver().removeOnGlobalLayoutListener(this);

                float density = getResources().getDisplayMetrics().density;

                // YENİ XML GENİŞLİĞİ: 90dp
                int itemWidthPx = (int) (70 * density);

                // Ekranın yarısından, öğe genişliğinin yarısını çıkararak padding buluyoruz
                int padding = (rulerRecycler.getWidth() / 2) - (itemWidthPx / 2);

                rulerRecycler.setPadding(padding, 0, padding, 0);
                rulerRecycler.setClipToPadding(false);

                // İlk açılışta doğru indekse kaydır (Örn: ISO 100)
                int startIndex = (currentWheelMode == WheelMode.ISO) ? currentIsoIndex : currentShutterIndex;
                layoutManager.scrollToPositionWithOffset(startIndex, 0);

                // İlk görsel efekti uygula
                updateRulerVisuals(layoutManager);
            }
        });

        // Kaydırma Dinleyicisi
        rulerRecycler.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                super.onScrolled(recyclerView, dx, dy);
                // Her piksel kaydığında Kemer/Fade efektini güncelle
                updateRulerVisuals(layoutManager);
            }

            @Override
            public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                super.onScrollStateChanged(recyclerView, newState);

                // Kaydırma durduğunda (IDLE) seçilen değeri al
                if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                    View centerView = snapHelper.findSnapView(layoutManager);
                    if (centerView != null) {
                        int pos = layoutManager.getPosition(centerView);
                        if (pos != RecyclerView.NO_POSITION) {

                            // Değerleri Güncelle
                            if (currentWheelMode == WheelMode.ISO) {
                                currentIsoIndex = pos;
                                int rawIso = Integer.parseInt(ISO_STRINGS.get(pos));
                                if (isoRange != null) {
                                    selectedIso = Math.max(isoRange.getLower(), Math.min(isoRange.getUpper(), rawIso));
                                }
                            } else {
                                currentShutterIndex = pos;
                                double denominator = SHUTTER_VALUES[pos];
                                long rawShutterNs = (long) (1_000_000_000.0 / denominator);
                                if (shutterRange != null) {
                                    selectedShutter = Math.max(shutterRange.getLower(), Math.min(shutterRange.getUpper(), rawShutterNs));
                                }
                            }

                            updateInfoText();

                            // Manual moddaysa kamerayı anlık güncelle
                            if (isManualMode) {
                                updatePreview();
                            }
                            // Auto moddayken kullanıcı tekerleğe dokunursa Manual'e geçir
                            else if (newState == RecyclerView.SCROLL_STATE_DRAGGING) {
                                isManualMode = true;
                                btnAutoManual.setText("MANUAL");
                                toggleUiForMode(true);
                                updatePreview();
                            }
                        }
                    }
                }
            }
        });
    }
    private void updateRulerVisuals(LinearLayoutManager layoutManager) {
        int centerX = rulerRecycler.getWidth() / 2;
        float density = getResources().getDisplayMetrics().density;
        float maxDistance = rulerRecycler.getWidth() / 2.2f; // Extend to edges

        for (int i = 0; i < layoutManager.getChildCount(); i++) {
            View child = layoutManager.getChildAt(i);
            if (child == null) continue;

            int childCenterX = (child.getLeft() + child.getRight()) / 2;
            int distance = Math.abs(centerX - childCenterX);

            // Varsayılan değerler
            float scale = 0.8f;
            float alpha = 0.0f; // Kenardakiler tamamen görünmez başlasın
            float translationY = 0f;

            if (distance < maxDistance) {
                float ratio = (maxDistance - distance) / maxDistance;

                // Scale: Merkezde 1.2x, kenarda 0.8x
                scale = 0.8f + (ratio * 0.4f);

                // Alpha: Merkezde 1.0, kenarda 0.0 (daha agresif fade)
                // Üstel fonksiyon kullanarak kenarlara doğru daha hızlı fade out
                alpha = (float) Math.pow(ratio, 0.8); // 0.8 = daha yavaş fade (daha çok item görünür)

                // Kemer Efekti
                translationY = -(ratio * 18 * density);
            }

            TextView txt = child.findViewById(R.id.txt_wheel_value);
            View tick = child.findViewById(R.id.tick_view);

            if (txt != null) {
                txt.setScaleX(scale);
                txt.setScaleY(scale);
                txt.setTranslationY(translationY);

                if (distance < (50 * density)) {
                    txt.setTextColor(android.graphics.Color.WHITE);
                    txt.setAlpha(1.0f); // Seçili olan tamamen parlak
                } else {
                    txt.setTextColor(android.graphics.Color.GRAY);
                    txt.setAlpha(alpha); // Uzaktakiler fade olsun
                }
            }
            if (tick != null) {
                tick.setAlpha(alpha);
            }
        }
    }


    private void toggleUiForMode(boolean manual) {
        long duration = 300;
        float density = getResources().getDisplayMetrics().density;

        if (manual) {
            // MANUAL
            rulerRecycler.setVisibility(View.VISIBLE);
            rulerRecycler.animate().alpha(1f).setDuration(duration).start();

            ViewGroup.LayoutParams params = wheelNeedle.getLayoutParams();
            params.width = (int) (6 * density);
            params.height = (int) (28 * density);
            wheelNeedle.setLayoutParams(params);
        } else {
            // AUTO
            rulerRecycler.animate().alpha(0f).setDuration(duration).withEndAction(() ->
                    rulerRecycler.setVisibility(View.INVISIBLE)
            ).start();

            ViewGroup.LayoutParams params = wheelNeedle.getLayoutParams();
            int size = (int) (6 * density);
            params.width = size;
            params.height = size;
            wheelNeedle.setLayoutParams(params);
        }
    }

    private void updateInfoText() {
        String sVal = SHUTTER_STRINGS.get(currentShutterIndex);
        String iVal = ISO_STRINGS.get(currentIsoIndex);
        txtIsoInfo.setText("SHUTTER: " + sVal + "   ISO: " + iVal);
    }

    private void switchWheelMode(WheelMode mode) {
        currentWheelMode = mode;
        List<String> data = (mode == WheelMode.ISO) ? ISO_STRINGS : SHUTTER_STRINGS;
        RulerAdapter adapter = new RulerAdapter(data);
        rulerRecycler.setAdapter(adapter);

        // Pozisyonu koru/ayarla
        int targetIndex = (mode == WheelMode.ISO) ? currentIsoIndex : currentShutterIndex;
        LinearLayoutManager lm = (LinearLayoutManager) rulerRecycler.getLayoutManager();
        if(lm != null) lm.scrollToPositionWithOffset(targetIndex, 0);

        if (mode == WheelMode.ISO) {
            btnIso.setTextColor(android.graphics.Color.WHITE);
            btnShutter.setTextColor(android.graphics.Color.GRAY);
        } else {
            btnShutter.setTextColor(android.graphics.Color.WHITE);
            btnIso.setTextColor(android.graphics.Color.GRAY);
        }
        updateInfoText();
    }

    private void showFocusRect(float x, float y) {
        float ringWidth = focusRing.getWidth();
        float ringHeight = focusRing.getHeight();
        focusRing.setX(x - ringWidth / 2f);
        focusRing.setY(y - ringHeight / 2f);

        // 1. Önceki animasyonu iptal et
        focusRing.animate().cancel();

        // 2. Başlangıç durumunu ayarla
        focusRing.setVisibility(View.VISIBLE);
        focusRing.setAlpha(1f);
        focusRing.setScaleX(1.4f); // Biraz daha büyük başlasın ki küçüldüğü belli olsun
        focusRing.setScaleY(1.4f);

        // 3. Küçülme Animasyonu (SNAP!)
        focusRing.animate()
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(150)    // Çok hızlı küçül
                .setStartDelay(0)    // <--- İŞTE ÇÖZÜM: Eski gecikmeyi sıfırla!
                .setInterpolator(new android.view.animation.DecelerateInterpolator()) // "Tak" diye otursun
                .withEndAction(() -> {
                    // 4. Bekle ve Kaybol
                    focusRing.animate()
                            .alpha(0f)
                            .setDuration(500)
                            .setStartDelay(2000) // Buradaki gecikme bir sonrakini etkiliyordu
                            .setInterpolator(null) // Düz silinsin
                            .withEndAction(() -> focusRing.setVisibility(View.INVISIBLE))
                            .start();
                })
                .start();
    }



    // --- CAMERA2 OPERASYONLARI ---

    private final CameraCaptureSession.CaptureCallback captureCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
            super.onCaptureCompleted(session, request, result);
            if (!isManualMode) {
                Integer iso = result.get(CaptureResult.SENSOR_SENSITIVITY);
                Long exposureTime = result.get(CaptureResult.SENSOR_EXPOSURE_TIME);
                if (iso != null && exposureTime != null) {
                    long denominator = Math.round(1_000_000_000.0 / exposureTime);
                    String shutterText = "1/" + denominator;
                    runOnUiThread(() -> txtIsoInfo.setText("SHUTTER: " + shutterText + "   ISO: " + iso));
                }
            }
        }
    };

    private final TextureView.SurfaceTextureListener textureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(@NonNull SurfaceTexture surface, int width, int height) {
            openCamera();
        }
        @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture s, int w, int h) {}
        @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture s) { return false; }
        @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture s) {}
    };

    private final CameraDevice.StateCallback stateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(@NonNull CameraDevice camera) {
            cameraDevice = camera;
            createCameraPreview();
        }
        @Override
        public void onDisconnected(@NonNull CameraDevice camera) { cameraDevice.close(); }
        @Override
        public void onError(@NonNull CameraDevice camera, int error) {
            cameraDevice.close();
            cameraDevice = null;
        }
    };

    private void openCamera() {
        CameraManager manager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            String[] cameraIdList = manager.getCameraIdList();
            if (cameraIdList == null || cameraIdList.length == 0) return;
            cameraId = cameraIdList[0];
            CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

            // RAW Kontrolü
            int[] capabilities = characteristics.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            mContainsRawCapability = false;
            for (int cap : capabilities) {
                if (cap == CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) {
                    mContainsRawCapability = true;
                    break;
                }
            }

            Integer sensorDir = characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION);
            mSensorOrientation = (sensorDir != null) ? sensorDir : 90;

            // ÖNEMLİ: map değişkenini ÖNCE tanımla
            StreamConfigurationMap map = characteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            isoRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);

            // Dinamik ISO listesi oluştur
            if (isoRange != null) {
                int minIso = isoRange.getLower();
                int maxIso = isoRange.getUpper();

                android.util.Log.d("HeyCam", "Cihaz ISO aralığı: " + minIso + " - " + maxIso);

                // Standart ISO değerleri
                int[] standardIsos = {50, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600, 51200};

                ISO_STRINGS.clear();
                for (int iso : standardIsos) {
                    if (iso >= minIso && iso <= maxIso) {
                        ISO_STRINGS.add(String.valueOf(iso));
                    }
                }

                // Eğer liste boşsa (çok nadir), en azından min ve max'ı ekle
                if (ISO_STRINGS.isEmpty()) {
                    ISO_STRINGS.add(String.valueOf(minIso));
                    ISO_STRINGS.add(String.valueOf(maxIso));
                }

                android.util.Log.d("HeyCam", "Desteklenen ISO değerleri: " + ISO_STRINGS);

                // currentIsoIndex'i güvenli bir değere ayarla
                if (currentIsoIndex >= ISO_STRINGS.size()) {
                    currentIsoIndex = ISO_STRINGS.size() - 1;
                }

                // Başlangıç ISO'sunu ayarla
                if (currentIsoIndex < ISO_STRINGS.size()) {
                    selectedIso = Integer.parseInt(ISO_STRINGS.get(currentIsoIndex));
                }
            }

            shutterRange = characteristics.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            sensorArraySize = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);

            // FPS
            Range<Integer>[] availableFpsRanges = characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (availableFpsRanges != null && availableFpsRanges.length > 0) {
                bestFpsRange = availableFpsRanges[availableFpsRanges.length - 1];
            }

            // --- 1. PREVIEW BOYUTU SEÇİMİ (SADECE 4:3) ---
            Size previewSize = new Size(640, 480);
            if (map != null) {
                Size[] sizes = map.getOutputSizes(SurfaceTexture.class);
                if (sizes != null) {
                    for (Size sz : sizes) {
                        float ratio = (float) sz.getWidth() / sz.getHeight();
                        if (Math.abs(ratio - 1.3333f) < 0.05f) {
                            if (sz.getWidth() * sz.getHeight() > previewSize.getWidth() * previewSize.getHeight()) {
                                previewSize = sz;
                            }
                        }
                    }
                }
                imageDimension = previewSize;
            }

            // --- 2. JPEG IMAGEREADER (SADECE 4:3 VE EN BÜYÜK) ---
            Size largestJpeg = new Size(640, 480);
            if (map != null) {
                Size[] jpegSizes = map.getOutputSizes(android.graphics.ImageFormat.JPEG);
                if (jpegSizes != null) {
                    for (Size sz : jpegSizes) {
                        float ratio = (float) sz.getWidth() / sz.getHeight();
                        if (Math.abs(ratio - 1.3333f) < 0.05f) {
                            if ((long) sz.getWidth() * sz.getHeight() > (long) largestJpeg.getWidth() * largestJpeg.getHeight()) {
                                largestJpeg = sz;
                            }
                        }
                    }
                }
            }

            if (imageReader != null) imageReader.close();
            imageReader = android.media.ImageReader.newInstance(largestJpeg.getWidth(), largestJpeg.getHeight(), android.graphics.ImageFormat.JPEG, 2);
            imageReader.setOnImageAvailableListener(reader -> {
                try (android.media.Image image = reader.acquireNextImage()) {
                    if (image != null) {
                        java.nio.ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.remaining()];
                        buffer.get(bytes);
                        if (bytes.length > 0) save(bytes);
                    }
                } catch (Exception e) { e.printStackTrace(); }
            }, mBackgroundHandler);

            // --- 3. RAW READER (ARTIK map TANIMLI) ---
            if (mContainsRawCapability && map != null) {
                android.util.Log.d("HeyCam", "RAW desteği var, ImageReader kuruluyor...");
                Size[] rawSizes = map.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR);
                if (rawSizes != null && rawSizes.length > 0) {
                    Size largestRaw = rawSizes[0];
                    for (Size sz : rawSizes) {
                        if ((long) sz.getWidth() * sz.getHeight() > (long) largestRaw.getWidth() * largestRaw.getHeight()) {
                            largestRaw = sz;
                        }
                    }
                    if (imageReaderRaw != null) imageReaderRaw.close();
                    imageReaderRaw = android.media.ImageReader.newInstance(largestRaw.getWidth(), largestRaw.getHeight(), android.graphics.ImageFormat.RAW_SENSOR, 2);
                    imageReaderRaw.setOnImageAvailableListener(reader -> {
                        android.util.Log.d("HeyCam", "RAW ImageReader callback tetiklendi!");
                        try (android.media.Image image = reader.acquireNextImage()) {
                            android.util.Log.d("HeyCam", "RAW image alındı: " + (image != null));
                            android.util.Log.d("HeyCam", "mLastResult: " + (mLastResult != null));
                            if (image != null && mLastResult != null) {
                                android.util.Log.d("HeyCam", "saveRaw çağrılıyor...");
                                saveRaw(image, mLastResult, characteristics);
                            } else {
                                android.util.Log.e("HeyCam", "image null: " + (image == null) + ", mLastResult null: " + (mLastResult == null));
                            }
                        } catch (Exception e) {
                            android.util.Log.e("HeyCam", "RAW kayıt hatası: " + e.getMessage());
                            e.printStackTrace();
                        }
                    }, mBackgroundHandler);
                    android.util.Log.d("HeyCam", "RAW ImageReader kuruldu. Boyut: " + largestRaw.getWidth() + "x" + largestRaw.getHeight());
                } else {
                    android.util.Log.w("HeyCam", "RAW boyutları bulunamadı");
                }
            } else {
                android.util.Log.w("HeyCam", "RAW desteklenmiyor veya map null. mContainsRawCapability: " + mContainsRawCapability);
            }

            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
                return;
            }
            manager.openCamera(cameraId, stateCallback, mBackgroundHandler);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }


    private void createCameraPreview() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;

            // Buffer boyutunu ayarla (4:3 olarak seçtiğimiz imageDimension)
            texture.setDefaultBufferSize(imageDimension.getWidth(), imageDimension.getHeight());
            Surface surface = new Surface(texture);

            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(surface);

            // --- YÜZEY LİSTESİ (PREVIEW + JPEG + RAW) ---
            java.util.List<Surface> outputSurfaces = new java.util.ArrayList<>();
            outputSurfaces.add(surface); // Ekran

            // JPEG Kaydedici
            if (imageReader != null) {
                outputSurfaces.add(imageReader.getSurface());
            }

            // RAW Kaydedici (Cihaz destekliyorsa)
            if (mContainsRawCapability && imageReaderRaw != null) {
                outputSurfaces.add(imageReaderRaw.getSurface());
            }

            // --- OTURUM BAŞLATMA ---
            cameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    if (cameraDevice == null) return;

                    cameraCaptureSessions = cameraCaptureSession;

                    // --- KRİTİK DÜZELTME: TRANSFORM ---
                    // Görüntünün ekrana sığarken ezilmesini engellemek için
                    // TextureView'ın Matrix'ini (Döndürme/Ölçekleme) güncelliyoruz.
                    configureTransform(textureView.getWidth(), textureView.getHeight());

                    updatePreview();
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    Toast.makeText(MainActivity.this, "Camera preview failed", Toast.LENGTH_SHORT).show();
                }
            }, null);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }


    private void configureTransform(int viewWidth, int viewHeight) {
        if (null == textureView || null == imageDimension) {
            return;
        }
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        android.graphics.Matrix matrix = new android.graphics.Matrix();
        android.graphics.RectF viewRect = new android.graphics.RectF(0, 0, viewWidth, viewHeight);
        android.graphics.RectF bufferRect = new android.graphics.RectF(0, 0, imageDimension.getHeight(), imageDimension.getWidth());
        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();
        if (Surface.ROTATION_90 == rotation || Surface.ROTATION_270 == rotation) {
            bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
            matrix.setRectToRect(viewRect, bufferRect, android.graphics.Matrix.ScaleToFit.FILL);
            float scale = Math.max(
                    (float) viewHeight / imageDimension.getHeight(),
                    (float) viewWidth / imageDimension.getWidth());
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90 * (rotation - 2), centerX, centerY);
        } else if (Surface.ROTATION_180 == rotation) {
            matrix.postRotate(180, centerX, centerY);
        }
        textureView.setTransform(matrix);
    }


    private void updatePreview() {
        if (cameraDevice == null) return;
        try {
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;
            texture.setDefaultBufferSize(imageDimension.getWidth(), imageDimension.getHeight());
            Surface surface = new Surface(texture);
            captureRequestBuilder.addTarget(surface);

            if (isManualMode) {
                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
                captureRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, selectedIso);
                captureRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, selectedShutter);
                cameraCaptureSessions.setRepeatingRequest(captureRequestBuilder.build(), captureCallback, mBackgroundHandler);
            } else {
                captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
                if (bestFpsRange != null) captureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, bestFpsRange);
                captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

                // Şok Tedavisi Triggerları
                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_CANCEL);
                cameraCaptureSessions.capture(captureRequestBuilder.build(), null, mBackgroundHandler);
                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);
                cameraCaptureSessions.capture(captureRequestBuilder.build(), null, mBackgroundHandler);
                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);

                cameraCaptureSessions.setRepeatingRequest(captureRequestBuilder.build(), captureCallback, mBackgroundHandler);
            }
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }

    private void takePicture() {
        if (cameraDevice == null) return;
        try {
            final CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);

            // Hedef Seçimi (RAW veya JPEG)
            if (isRawMode && mContainsRawCapability && imageReaderRaw != null) {
                captureBuilder.addTarget(imageReaderRaw.getSurface());
                android.util.Log.d("HeyCam", "RAW MODUNDA FOTOĞRAF ÇEKİLİYOR");

                // ========================================
                // SIFIR PROCESS - TÜM OTOMATİK İŞLEMLERİ KAPAT
                // ========================================
                captureBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF);
                captureBuilder.set(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF);
                captureBuilder.set(CaptureRequest.HOT_PIXEL_MODE, CaptureRequest.HOT_PIXEL_MODE_OFF);
                captureBuilder.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX);
                captureBuilder.set(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_FAST);

                // Lens düzeltmelerini kapat (mevcut cihazda destekleniyorsa)
                captureBuilder.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_OFF);

                android.util.Log.d("HeyCam", "Tüm işleme ayarları kapatıldı - Sıfır process aktif");

            } else {
                captureBuilder.addTarget(imageReader.getSurface());
                android.util.Log.d("HeyCam", "JPEG MODUNDA FOTOĞRAF ÇEKİLİYOR");
                android.util.Log.d("HeyCam", "isRawMode: " + isRawMode + ", mContainsRawCapability: " + mContainsRawCapability + ", imageReaderRaw: " + (imageReaderRaw != null));
            }

            if (isManualMode) {
                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
                captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, selectedIso);
                captureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, selectedShutter);
            } else {
                captureBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
            }

            // Oryantasyon Hesaplama
            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            int surfaceRotation = 0;
            switch (rotation) {
                case Surface.ROTATION_0: surfaceRotation = 0; break;
                case Surface.ROTATION_90: surfaceRotation = 90; break;
                case Surface.ROTATION_180: surfaceRotation = 180; break;
                case Surface.ROTATION_270: surfaceRotation = 270; break;
            }
            int jpegOrientation = (mSensorOrientation - surfaceRotation + 360) % 360;
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, jpegOrientation);
            captureBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);

            cameraCaptureSessions.capture(captureBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                    mLastResult = result; // DNG için metadata sakla
                }
            }, mBackgroundHandler);
        } catch (CameraAccessException e) { e.printStackTrace(); }
    }


    private void startFocus(float touchX, float touchY) {
        if (cameraDevice == null || cameraCaptureSessions == null || sensorArraySize == null) return;

        try {
            // 1. Dokunma koordinatlarını 0.0 - 1.0 arasına sıkıştır (Normalize et)
            float normX = touchX / textureView.getWidth();
            float normY = touchY / textureView.getHeight();

            // 2. Sensör Oryantasyonuna göre koordinatları çevir
            // Çoğu arka kamera 90 derece montelidir.
            if (mSensorOrientation == 90) {
                // 90 derece dönüş: (x, y) -> (y, 1-x)
                float temp = normX;
                normX = normY;
                normY = 1.0f - temp;
            } else if (mSensorOrientation == 270) {
                // 270 derece dönüş
                float temp = normX;
                normX = 1.0f - normY;
                normY = temp;
            }

            // 3. Sensörün gerçek boyutlarına göre yeni koordinatları hesapla
            int sensorX = (int) (normX * sensorArraySize.width());
            int sensorY = (int) (normY * sensorArraySize.height());

            // 4. Odaklanılacak alanın boyutunu belirle
            // Sensör boyutunun %5'i kadar bir kare oluşturalım (Sabit 150px yerine oransal olsun)
            int halfAreaSize = (int) (Math.max(sensorArraySize.width(), sensorArraySize.height()) * 0.05);

            // Sınırların dışına taşmamasını sağla (Clamp)
            android.hardware.camera2.params.MeteringRectangle focusArea = new android.hardware.camera2.params.MeteringRectangle(
                    Math.max(0, Math.min(sensorX - halfAreaSize, sensorArraySize.width() - (halfAreaSize * 2))),
                    Math.max(0, Math.min(sensorY - halfAreaSize, sensorArraySize.height() - (halfAreaSize * 2))),
                    halfAreaSize * 2,
                    halfAreaSize * 2,
                    android.hardware.camera2.params.MeteringRectangle.METERING_WEIGHT_MAX - 1);

            android.hardware.camera2.params.MeteringRectangle[] meteringRectangles = {focusArea};

            // 5. İsteği Gönder (AF ve AE Bölgelerini Güncelle)
            cameraCaptureSessions.stopRepeating(); // Önizlemeyi duraklat

            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_REGIONS, meteringRectangles);
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_AUTO);
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);

            if (!isManualMode) {
                // Auto Moddaysak Pozlamayı (AE) da bu noktaya göre yap
                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_REGIONS, meteringRectangles);
                // Pozlama tetikleyiciyi başlat (Yeniden ölçüm yapsın)
                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            }

            cameraCaptureSessions.capture(captureRequestBuilder.build(), null, mBackgroundHandler);

            // 6. Normal akışa dön
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);

            cameraCaptureSessions.setRepeatingRequest(captureRequestBuilder.build(), captureCallback, mBackgroundHandler);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }


    // --- KAYDETME FONKSİYONLARI ---

    private void save(byte[] bytes) {
        android.content.ContentValues values = new android.content.ContentValues();
        long currentTime = System.currentTimeMillis();
        String fileName = "HeyCam_" + currentTime + ".jpg";

        values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(android.provider.MediaStore.Images.Media.DATE_ADDED, currentTime / 1000);
        values.put(android.provider.MediaStore.Images.Media.DATE_TAKEN, currentTime);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/HeyCam");
            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 1);
        }

        android.content.ContentResolver resolver = getContentResolver();
        android.net.Uri uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

        if (uri != null) {
            try (java.io.OutputStream output = resolver.openOutputStream(uri)) {
                if (output != null) output.write(bytes);
            } catch (java.io.IOException e) { e.printStackTrace(); }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                values.clear();
                values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(uri, values, null, null);
            }
            //runOnUiThread(() -> Toast.makeText(this, "JPEG Kaydedildi", Toast.LENGTH_SHORT).show());
        }
    }

    // MainActivity'e bu helper metodunu ekle:
    private void showCenteredMessage(String message) {
        runOnUiThread(() -> {
            if (customToast == null) return;

            // Önceki hide işlemini iptal et
            if (hideToastRunnable != null) {
                toastHandler.removeCallbacks(hideToastRunnable);
            }

            // Mesajı göster - fade in animasyonu
            customToast.setText(message);
            customToast.setVisibility(android.view.View.VISIBLE);
            customToast.setAlpha(0f);
            customToast.setScaleX(0.8f);
            customToast.setScaleY(0.8f);

            customToast.animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(200)
                    .setInterpolator(new android.view.animation.DecelerateInterpolator())
                    .start();

            // 1.5 saniye sonra gizle - fade out animasyonu
            hideToastRunnable = () -> {
                customToast.animate()
                        .alpha(0f)
                        .scaleX(0.8f)
                        .scaleY(0.8f)
                        .setDuration(200)
                        .setInterpolator(new android.view.animation.AccelerateInterpolator())
                        .withEndAction(() -> customToast.setVisibility(android.view.View.GONE))
                        .start();
            };
            toastHandler.postDelayed(hideToastRunnable, 1500);
        });
    }
// Şimdi saveRaw ve save metodlarında Toast.makeText yerine showCenteredToast kullan:

    private void saveRaw(android.media.Image image, TotalCaptureResult result, CameraCharacteristics characteristics) {
        showCenteredMessage("Saving RAW...");

        android.util.Log.d("HeyCam", "RAW kaydetme başladı: " + System.currentTimeMillis());

        android.hardware.camera2.DngCreator dngCreator = new android.hardware.camera2.DngCreator(characteristics, result);

        // Oryantasyon Hesaplama
        int rotation = getWindowManager().getDefaultDisplay().getRotation();
        int surfaceRotation = 0;
        switch (rotation) {
            case Surface.ROTATION_0: surfaceRotation = 0; break;
            case Surface.ROTATION_90: surfaceRotation = 90; break;
            case Surface.ROTATION_180: surfaceRotation = 180; break;
            case Surface.ROTATION_270: surfaceRotation = 270; break;
        }

        int jpegOrientation = (mSensorOrientation - surfaceRotation + 360) % 360;

        int exifOrientation;
        switch (jpegOrientation) {
            case 90:
                exifOrientation = android.media.ExifInterface.ORIENTATION_ROTATE_90;
                break;
            case 180:
                exifOrientation = android.media.ExifInterface.ORIENTATION_ROTATE_180;
                break;
            case 270:
                exifOrientation = android.media.ExifInterface.ORIENTATION_ROTATE_270;
                break;
            case 0:
            default:
                exifOrientation = android.media.ExifInterface.ORIENTATION_NORMAL;
                break;
        }

        dngCreator.setOrientation(exifOrientation);

        // Dosyayı Kaydet
        long currentTime = System.currentTimeMillis();
        String fileName = "HeyCam_" + currentTime + ".dng";

        android.content.ContentValues values = new android.content.ContentValues();
        values.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, fileName);
        values.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/x-adobe-dng");
        values.put(android.provider.MediaStore.Images.Media.DATE_ADDED, currentTime / 1000);
        values.put(android.provider.MediaStore.Images.Media.DATE_TAKEN, currentTime);

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            values.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, android.os.Environment.DIRECTORY_PICTURES + "/HeyCam");
            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 1);
        }

        android.content.ContentResolver resolver = getContentResolver();
        android.net.Uri uri = resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);

        boolean success = false;
        if (uri != null) {
            try (java.io.OutputStream output = resolver.openOutputStream(uri)) {
                if (output != null) {
                    android.util.Log.d("HeyCam", "RAW yazma başladı...");
                    dngCreator.writeImage(output, image);
                    output.flush();
                    android.util.Log.d("HeyCam", "RAW yazma tamamlandı");
                    success = true;
                }
            } catch (java.io.IOException e) {
                android.util.Log.e("HeyCam", "RAW yazma hatası: " + e.getMessage());
                e.printStackTrace();
            }

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                values.clear();
                values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(uri, values, null, null);
            }

            final boolean finalSuccess = success;
            android.util.Log.d("HeyCam", "RAW kaydetme bitti: " + System.currentTimeMillis());

            if (finalSuccess) {
                showCenteredMessage("RAW Saved");
            } else {
                showCenteredMessage("RAW Save Failed");
            }
        } else {
            android.util.Log.e("HeyCam", "URI oluşturulamadı");
            showCenteredMessage("RAW Save Error");
        }

        dngCreator.close();
    }

    // --- THREADING ---

    private void startBackgroundThread() {
        mBackgroundThread = new HandlerThread("CameraBackground");
        mBackgroundThread.start();
        mBackgroundHandler = new Handler(mBackgroundThread.getLooper());
    }

    private void stopBackgroundThread() {
        if (mBackgroundThread != null) {
            mBackgroundThread.quitSafely();
            try {
                mBackgroundThread.join();
                mBackgroundThread = null;
                mBackgroundHandler = null;
            } catch (InterruptedException e) { e.printStackTrace(); }
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        startBackgroundThread();
        if (textureView.isAvailable()) {
            openCamera();
        } else {
            textureView.setSurfaceTextureListener(textureListener);
        }
    }

    @Override
    protected void onPause() {
        closeCamera();
        stopBackgroundThread();
        super.onPause();
    }

    // Add this method to properly close the camera
    private void closeCamera() {
        if (null != cameraCaptureSessions) {
            cameraCaptureSessions.close();
            cameraCaptureSessions = null;
        }
        if (null != cameraDevice) {
            cameraDevice.close();
            cameraDevice = null;
        }
        if (null != imageReader) {
            imageReader.close();
            imageReader = null;
        }
        if (null != imageReaderRaw) {
            imageReaderRaw.close();
            imageReaderRaw = null;
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                if (textureView.isAvailable()) openCamera();
                else textureView.setSurfaceTextureListener(textureListener);
            } else {
                Toast.makeText(this, "Camera permission required!", Toast.LENGTH_SHORT).show();
                finish();
            }
        }
    }


    // Add this method to your MainActivity
// Add this method to your MainActivity
    private void openLastPhoto() {
        // Query MediaStore for the last photo from HeyCam
        String[] projection = {
                android.provider.MediaStore.Images.Media._ID,
                android.provider.MediaStore.Images.Media.DISPLAY_NAME,
                android.provider.MediaStore.Images.Media.DATE_ADDED
        };

        String selection = android.provider.MediaStore.Images.Media.DISPLAY_NAME + " LIKE ?";
        String[] selectionArgs = {"HeyCam_%"};
        String sortOrder = android.provider.MediaStore.Images.Media.DATE_ADDED + " DESC";

        android.database.Cursor cursor = getContentResolver().query(
                android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                selectionArgs,
                sortOrder
        );

        if (cursor != null && cursor.moveToFirst()) {
            long id = cursor.getLong(cursor.getColumnIndexOrThrow(android.provider.MediaStore.Images.Media._ID));
            android.net.Uri photoUri = android.content.ContentUris.withAppendedId(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                    id
            );
            cursor.close();

            // Try to open with Google Photos first, fallback to system gallery
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(photoUri, "image/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

            // Try Google Photos package
            intent.setPackage("com.google.android.apps.photos");

            try {
                startActivity(intent);
            } catch (android.content.ActivityNotFoundException e) {
                // Google Photos not installed, try default gallery
                intent.setPackage(null);
                try {
                    startActivity(intent);
                } catch (android.content.ActivityNotFoundException ex) {
                    Toast.makeText(this, "Gallery could not be opened!", Toast.LENGTH_SHORT).show();
                }
            }
        } else {
            Toast.makeText(this, "No photos found in the gallery!", Toast.LENGTH_SHORT).show();
            if (cursor != null) cursor.close();
        }
    }


}