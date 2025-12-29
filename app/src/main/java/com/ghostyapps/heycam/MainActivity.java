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
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.util.Size;
import android.graphics.Matrix;
import android.graphics.RectF;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.LinearSnapHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.SnapHelper;

import java.util.ArrayList;

import java.io.File;

import java.io.ByteArrayOutputStream;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class MainActivity extends AppCompatActivity {

    private View previewCardParent;

    private ImageButton btnLut;
    private android.content.SharedPreferences prefs;

    // Tekerlek titreşimi için son pozisyonu tutar
    private int lastHapticPos = -1;

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

    // Sensör Tabanlı Oryantasyon Takibi
    private android.view.OrientationEventListener orientationEventListener;
    private int currentDeviceOrientation = 0;


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

    private androidx.cardview.widget.CardView previewCard;

    // --- FİZİKSEL TUŞLARI VE ÖZEL SCANCODE'U DİNLEME ---
    @Override
    public boolean onKeyDown(int keyCode, android.view.KeyEvent event) {

        // 1. Özel Tuş Kontrolü (ScanCode: 1250)
        // 2. Standart Ses Tuşları (Volume Up / Down)
        // 3. Standart Kamera Tuşu (Bazı cihazlarda bulunur)
        if (event.getScanCode() == 1250 ||
                keyCode == android.view.KeyEvent.KEYCODE_VOLUME_UP ||
                keyCode == android.view.KeyEvent.KEYCODE_VOLUME_DOWN ||
                keyCode == android.view.KeyEvent.KEYCODE_CAMERA) {

            // getRepeatCount() == 0 kontrolü çok önemlidir.
            // Bu kontrol, tuşa basılı tuttuğunuzda uygulamanın "seri çekim" yapıp
            // çökmesini veya donmasını engeller. Sadece ilk basışta çalışır.
            if (event.getRepeatCount() == 0) {
                if (btnTakePhoto != null) {
                    // Butona sanal olarak tıkla (Animasyon ve ses çalışır)
                    btnTakePhoto.performClick();
                }
            }

            // "True" döndürerek sistemin bu tuşu başka amaçla (ses kısma vb.)
            // kullanmasını engelliyoruz.
            return true;
        }

        // Diğer tuşlar (Geri tuşu, Home vb.) normal çalışsın
        return super.onKeyDown(keyCode, event);
    }

    // Ekran açısına değil, fiziksel tutuş açısına göre hesap yapar
    private int getJpegOrientation() {
        // Arka kamera için formül: (Sensör Açısı + Cihaz Açısı) % 360
        // Sensör genelde 90 derecedir.
        // Cihaz 0 (Dik) -> (90 + 0) = 90 (Doğru)
        // Cihaz 270 (Sola Yatık) -> (90 + 270) = 360 -> 0 (Doğru, yatay kayıt)
        return (mSensorOrientation + currentDeviceOrientation + 360) % 360;
    }

    private void updateCardViewSize(Size previewSize) {
        if (previewSize == null || previewCard == null) return;

        runOnUiThread(() -> {
            // 1. Ekran Genişliğini Al
            android.util.DisplayMetrics metrics = getResources().getDisplayMetrics();
            int screenWidth = metrics.widthPixels;

            // 2. Sensör Oranını Hesapla (Genellikle Width > Height gelir, yani Landscape)
            // Telefon dik olduğu için biz (Uzun Kenar / Kısa Kenar) oranını bulmalıyız.
            float ratio = (float) Math.max(previewSize.getWidth(), previewSize.getHeight()) /
                    (float) Math.min(previewSize.getWidth(), previewSize.getHeight());

            // 3. Yeni Yüksekliği Hesapla: Genişlik * Oran (Örn: Genişlik * 1.333)
            int newHeight = Math.round(screenWidth * ratio);

            // 4. CardView Boyutunu Güncelle
            android.view.ViewGroup.LayoutParams params = previewCard.getLayoutParams();
            params.width = screenWidth; // Genişlik ekran kadar olsun
            params.height = newHeight;  // Yükseklik hesaplanan oran kadar
            previewCard.setLayoutParams(params);

            android.util.Log.d("HeyCam", "CardView güncellendi: " + screenWidth + "x" + newHeight + " (Oran: " + ratio + ")");
        });
    }



    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // EĞER TELEFON ANDROID 13 VE ÜZERİYSE BU TEK SATIR YETERLİDİR:
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            setRecentsScreenshotEnabled(false);
        }

        setContentView(R.layout.activity_main);

        prefs = getSharedPreferences("HeyCamSettings", MODE_PRIVATE);
        btnLut = findViewById(R.id.btn_lut_switch);

        btnLut.setOnClickListener(v -> {
            // LUT Yöneticisini Aç
            startActivity(new android.content.Intent(MainActivity.this, LutManagerActivity.class));
        });


        // Navigasyon barını siyah yap
        getWindow().setNavigationBarColor(android.graphics.Color.BLACK);

// Eğer barın üzerindeki çizginin (pill) çok parlak olmasını istemiyorsan:
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            // Barın içindeki simgeleri/çizgiyi beyaz (light) tutar, arka plan siyah kalır
            getWindow().getDecorView().setSystemUiVisibility(0);
        }



        // Tam ekran modu - status bar ve navigation bar gizle
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN


        );

        setContentView(R.layout.activity_main);

        customToast = findViewById(R.id.custom_toast);

        // --- 1. VIEW BAĞLANTILARI ---
        textureView = findViewById(R.id.texture_view);
        txtIsoInfo = findViewById(R.id.txt_iso_display);

        // Cetvel ve Görsel Efektler
        rulerRecycler = findViewById(R.id.shutter_recycler);
        focusRing = findViewById(R.id.focus_ring);
        previewCardParent = findViewById(R.id.preview_card_container);
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

        // --- HAZIR LUTLARI KOPYALA ---
        copyDefaultLuts();

        // Başlangıç Ayarı: Auto Mod
        toggleUiForMode(false);
        txtIsoInfo.setText("HeyCam");

        // --- 4. DOKUNMATİK ODAKLAMA ---
        textureView.setOnTouchListener((v, event) -> {
            if (event.getAction() == android.view.MotionEvent.ACTION_UP) {
                float x = event.getX();
                float y = event.getY();

                // Artık Focus Ring de kartın içinde olduğu için
                // dokunma koordinatı (x,y) ile halkanın yeri aynıdır.
                showFocusRect(x, y);
                startFocus(x, y);
            }
            return true;
        });

        // --- 5. ORYANTASYON SENSÖRÜNÜ BAŞLAT ---
        orientationEventListener = new android.view.OrientationEventListener(this) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (orientation == android.view.OrientationEventListener.ORIENTATION_UNKNOWN) return;

                // Açıyı 90 derecelik katlara yuvarla (Snap)
                // Örn: 85 -> 90, 10 -> 0
                int newOrientation = (orientation + 45) / 90 * 90;

                // 360 dereceyi 0 yap
                if (newOrientation == 360) newOrientation = 0;

                currentDeviceOrientation = newOrientation;
            }
        };

        if (orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable();
        }
    }
    // --- UI SETUP & LOGIC ---

    private void setupButtons() {

        // 1. SharedPreferences Başlat (Ayarları okumak için)
        prefs = getSharedPreferences("HeyCamSettings", MODE_PRIVATE);

        // 2. LUT Butonunu Bul ve Tıklama Ver
        btnLut = findViewById(R.id.btn_lut_switch);
        btnLut.setOnClickListener(v -> {
            // Import sayfasına git
            android.content.Intent intent = new android.content.Intent(MainActivity.this, LutManagerActivity.class);
            startActivity(intent);
        });

        // --- 1. GALERİ BUTONU ---
        ImageButton btnGallery = findViewById(R.id.btn_gallery);
        if (btnGallery != null) {
            btnGallery.setOnClickListener(v -> openLastPhoto());
        }

        // --- FORMAT DEĞİŞTİRİCİ (JPEG / RAW) ---
        btnFormat.setOnClickListener(v -> {
            if (!mContainsRawCapability) {
                Toast.makeText(this, "This device does not support RAW", Toast.LENGTH_SHORT).show();
                return;
            }
            isRawMode = !isRawMode;

            if (isRawMode) {
                // RAW MODU AÇILDI
                btnFormat.setImageResource(R.drawable.ic_raw_indicator);
                btnFormat.setAlpha(1.0f);

                // LUT Butonunu Pasif Yap ve Gizle (veya Alpha düşür)
                btnLut.setEnabled(false);
                btnLut.setAlpha(0.3f);
                showCenteredMessage("RAW Mode: LUTs Disabled");

            } else {
                // JPEG MODU AÇILDI
                btnFormat.setImageResource(R.drawable.ic_jpeg_indicator);
                btnFormat.setAlpha(0.9f);

                // LUT Butonunu Tekrar Aktif Et
                btnLut.setEnabled(true);
                updateLutIconState(); // Eski rengine (Aktif/Pasif) geri dönsün
                // showCenteredMessage("JPEG Mode");
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
            // EKLENEN SATIR: Haptic Feedback (Titreşim)
            // VIRTUAL_KEY: Standart Android tuş titreşimi verir.
            // Alternatif olarak KEYBOARD_TAP deneyebilirsin.
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            if (shutterFlash != null) {
                // 1. Önce görünür yap (Çünkü onWindowFocusChanged bunu GONE yapmıştı)
                shutterFlash.setVisibility(View.VISIBLE);

                // 2. En üste getir (Z-Order garantisi)
                shutterFlash.bringToFront();

                // 3. Simsiyah başla
                shutterFlash.setAlpha(1f);

                // 4. Hızlıca yok ol (Shutter Efekti)
                shutterFlash.animate()
                        .alpha(0f)
                        .setDuration(150)
                        .setStartDelay(50) // 50ms siyah kalsın ("Tak" hissi için)
                        .withEndAction(() -> {
                            // İş bitince tekrar GONE yap ki boşuna GPU çizmesin
                            shutterFlash.setVisibility(View.GONE);
                        })
                        .start();
            }

            takePicture();
        });

        // --- 5. TEKERLEK SEÇİMİ ---
        if (btnIso != null) btnIso.setOnClickListener(v -> switchWheelMode(WheelMode.ISO));
        if (btnShutter != null) btnShutter.setOnClickListener(v -> switchWheelMode(WheelMode.SHUTTER));
    }



    // --- HAZIR LUT'LARI YÜKLEME ---
    private void copyDefaultLuts() {
        // Daha önce yükledik mi kontrol et (Her açılışta tekrar yapmasın)
        boolean isLutsInstalled = prefs.getBoolean("are_default_luts_installed", false);
        if (isLutsInstalled) return;

        // Arka planda çalıştıralım ki açılışı yavaşlatmasın
        new Thread(() -> {
            try {
                // Assets/sample_luts klasöründeki dosyaları listele
                String[] files = getAssets().list("sample_luts");
                if (files == null) return;

                File destDir = new File(getFilesDir(), "luts");
                if (!destDir.exists()) destDir.mkdirs();

                for (String filename : files) {
                    // Sadece .cube dosyalarını al
                    if (!filename.toLowerCase().endsWith(".cube")) continue;

                    File outFile = new File(destDir, filename);

                    // Eğer dosya zaten varsa üzerine yazma (Kullanıcı belki silmiştir, tekrar gelmesin)
                    if (outFile.exists()) continue;

                    try (java.io.InputStream in = getAssets().open("sample_luts/" + filename);
                         java.io.FileOutputStream out = new java.io.FileOutputStream(outFile)) {

                        byte[] buffer = new byte[1024];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                    }
                }

                // İşlem bitti, bir daha yapma diye işaretle
                prefs.edit().putBoolean("are_default_luts_installed", true).apply();

                // (Opsiyonel) Log düş
                android.util.Log.d("HeyCam", "Default LUTs copied successfully.");

            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
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
                // --- EKLENEN KISIM: MEKANİK TİTREŞİM ---
                // Tekerlek dönerken her yeni sayı merkeze geldiğinde titret
                View centerView = snapHelper.findSnapView(layoutManager);
                if (centerView != null) {
                    int pos = layoutManager.getPosition(centerView);
                    // Eğer pozisyon değiştiyse (yeni sayı geldiyse) ve -1 değilse
                    if (pos != RecyclerView.NO_POSITION && pos != lastHapticPos) {
                        // Hafif bir "tık" titreşimi (KEYBOARD_TAP daha hafiftir)
                        recyclerView.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
                        lastHapticPos = pos;
                    }
                }
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

        @Override
        public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture s, int w, int h) {
            // EKSİK OLAN PARÇA BU: Boyut değişince görüntüyü tekrar ortala
            configureTransform(w, h);
        }

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
        android.hardware.camera2.CameraManager manager = (android.hardware.camera2.CameraManager) getSystemService(android.content.Context.CAMERA_SERVICE);
        try {
            String[] cameraIdList = manager.getCameraIdList();
            if (cameraIdList == null || cameraIdList.length == 0) return;
            cameraId = cameraIdList[0];
            android.hardware.camera2.CameraCharacteristics characteristics = manager.getCameraCharacteristics(cameraId);

            // RAW Kontrolü
            int[] capabilities = characteristics.get(android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
            mContainsRawCapability = false;
            if (capabilities != null) {
                for (int cap : capabilities) {
                    if (cap == android.hardware.camera2.CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW) {
                        mContainsRawCapability = true;
                        break;
                    }
                }
            }

            Integer sensorDir = characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_ORIENTATION);
            mSensorOrientation = (sensorDir != null) ? sensorDir : 90;

            // Harita ve ISO Aralığı
            android.hardware.camera2.params.StreamConfigurationMap map = characteristics.get(android.hardware.camera2.CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
            isoRange = characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE);

            // Dinamik ISO Listesi
            if (isoRange != null) {
                int minIso = isoRange.getLower();
                int maxIso = isoRange.getUpper();
                android.util.Log.d("HeyCam", "Cihaz ISO aralığı: " + minIso + " - " + maxIso);

                int[] standardIsos = {50, 100, 200, 400, 800, 1600, 3200, 6400, 12800, 25600, 51200};
                ISO_STRINGS.clear();
                for (int iso : standardIsos) {
                    if (iso >= minIso && iso <= maxIso) {
                        ISO_STRINGS.add(String.valueOf(iso));
                    }
                }
                if (ISO_STRINGS.isEmpty()) {
                    ISO_STRINGS.add(String.valueOf(minIso));
                    ISO_STRINGS.add(String.valueOf(maxIso));
                }
                if (currentIsoIndex >= ISO_STRINGS.size()) {
                    currentIsoIndex = ISO_STRINGS.size() - 1;
                }
                if (currentIsoIndex < ISO_STRINGS.size()) {
                    selectedIso = Integer.parseInt(ISO_STRINGS.get(currentIsoIndex));
                }
            }

            shutterRange = characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            sensorArraySize = characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);

            // FPS
            android.util.Range<Integer>[] availableFpsRanges = characteristics.get(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (availableFpsRanges != null && availableFpsRanges.length > 0) {
                bestFpsRange = availableFpsRanges[availableFpsRanges.length - 1];
            }


            // --- 1. PREVIEW BOYUTU (4:3 Öncelikli) ---
            android.util.Size previewSize = new android.util.Size(640, 480); // Varsayılan
            if (map != null) {
                android.util.Size[] sizes = map.getOutputSizes(android.graphics.SurfaceTexture.class);
                if (sizes != null) {
                    for (android.util.Size sz : sizes) {
                        float ratio = (float) sz.getWidth() / sz.getHeight();
                        // 4:3 (1.33) oranına yakın olanları al
                        if (Math.abs(ratio - 1.3333f) < 0.05f) {
                            if ((long) sz.getWidth() * sz.getHeight() > (long) previewSize.getWidth() * previewSize.getHeight()) {
                                previewSize = sz;
                            }
                        }
                    }
                }
            }


            // Hesaplanan boyutu global değişkene ata
            imageDimension = previewSize;

            // --- ASPECT RATIO AYARI ---
            if (imageDimension != null) {
                // Telefon DİK (Portrait) tutuluyorsa:
                if (getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
                    // 3:4 oranı için (Genişlik < Yükseklik)
                    textureView.setAspectRatio(imageDimension.getHeight(), imageDimension.getWidth());
                } else {
                    // Yan tutuluyorsa
                    textureView.setAspectRatio(imageDimension.getWidth(), imageDimension.getHeight());
                }
            }

            // --- 2. JPEG IMAGEREADER (4:3 ve En Büyük) ---
            android.util.Size largestJpeg = new android.util.Size(640, 480);
            if (map != null) {
                android.util.Size[] jpegSizes = map.getOutputSizes(android.graphics.ImageFormat.JPEG);
                if (jpegSizes != null) {
                    for (android.util.Size sz : jpegSizes) {
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

                        // --- LUT KONTROLÜ ---
                        String currentLutName = prefs.getString("current_lut_name", null);

                        if (currentLutName != null && !currentLutName.isEmpty()) {
                            File lutFile = new File(getFilesDir(), "luts/" + currentLutName);
                            if (lutFile.exists()) {
                                showCenteredMessage("Applying LUT...");

                                // 1. JPEG Bytes -> Bitmap (Mutable)
                                android.graphics.BitmapFactory.Options opt = new android.graphics.BitmapFactory.Options();
                                opt.inMutable = true;
                                android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opt);

                                if (bitmap != null) {
                                    // --- DÜZELTME BAŞLANGICI: ROTASYON ---
                                    // Gelen fotoğrafın EXIF bilgisini oku
                                    try {
                                        android.media.ExifInterface exif = new android.media.ExifInterface(new java.io.ByteArrayInputStream(bytes));
                                        int orientation = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);

                                        int rotationInDegrees = 0;
                                        if (orientation == android.media.ExifInterface.ORIENTATION_ROTATE_90) rotationInDegrees = 90;
                                        else if (orientation == android.media.ExifInterface.ORIENTATION_ROTATE_180) rotationInDegrees = 180;
                                        else if (orientation == android.media.ExifInterface.ORIENTATION_ROTATE_270) rotationInDegrees = 270;

                                        // Eğer dönüş gerekliyse Bitmap'i fiziksel olarak döndür
                                        if (rotationInDegrees != 0) {
                                            android.graphics.Matrix matrix = new android.graphics.Matrix();
                                            matrix.preRotate(rotationInDegrees);

                                            // Eski bitmap'i döndürülmüş yeni bitmap ile değiştir
                                            android.graphics.Bitmap rotatedBitmap = android.graphics.Bitmap.createBitmap(
                                                    bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);

                                            // Eskisini temizle, yenisini kullan
                                            if (bitmap != rotatedBitmap) {
                                                bitmap.recycle();
                                                bitmap = rotatedBitmap;
                                            }
                                        }
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                    // --- DÜZELTME BİTİŞİ ---

                                    // 2. LUT Uygula (Artık düzgün bitmap üzerindeyiz)
                                    CubeLut lutProcessor = new CubeLut();
                                    if (lutProcessor.load(lutFile)) {
                                        lutProcessor.apply(bitmap);
                                    }

                                    // 3. Bitmap -> JPEG ve Kaydet
                                    try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                                        bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out);
                                        byte[] newBytes = out.toByteArray();

                                        save(newBytes);
                                        bitmap.recycle();
                                        return;
                                    } catch (Exception e) {
                                        e.printStackTrace();
                                    }
                                }
                            }
                        }

                        // LUT yoksa normal kaydet (Burada EXIF korunur çünkü byte'lara dokunmuyoruz)
                        save(bytes);
                    }
                } catch (Exception e) { e.printStackTrace(); }
            }, mBackgroundHandler);

            // --- 3. RAW IMAGEREADER ---
            if (mContainsRawCapability && map != null) {
                android.util.Size[] rawSizes = map.getOutputSizes(android.graphics.ImageFormat.RAW_SENSOR);
                if (rawSizes != null && rawSizes.length > 0) {
                    android.util.Size largestRaw = rawSizes[0];
                    for (android.util.Size sz : rawSizes) {
                        if ((long) sz.getWidth() * sz.getHeight() > (long) largestRaw.getWidth() * largestRaw.getHeight()) {
                            largestRaw = sz;
                        }
                    }

                    if (imageReaderRaw != null) imageReaderRaw.close();
                    imageReaderRaw = android.media.ImageReader.newInstance(largestRaw.getWidth(), largestRaw.getHeight(), android.graphics.ImageFormat.RAW_SENSOR, 2);
                    imageReaderRaw.setOnImageAvailableListener(reader -> {
                        try (android.media.Image image = reader.acquireNextImage()) {
                            if (image != null && mLastResult != null) {
                                saveRaw(image, mLastResult, characteristics);
                            }
                        } catch (Exception e) {
                            e.printStackTrace();
                        }
                    }, mBackgroundHandler);
                }
            }

            // İzin Kontrolü ve Kamerayı Açma
            if (androidx.core.app.ActivityCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                androidx.core.app.ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION);
                return;
            }
            manager.openCamera(cameraId, stateCallback, mBackgroundHandler);

        } catch (android.hardware.camera2.CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void createCameraPreview() {
        try {
            SurfaceTexture texture = textureView.getSurfaceTexture();
            assert texture != null;

            // Buffer boyutunu ayarla
            if (imageDimension != null) {
                texture.setDefaultBufferSize(imageDimension.getWidth(), imageDimension.getHeight());
            }

            Surface surface = new Surface(texture);

            // DÜZELTME: 'RequestBuilder' yerine 'captureRequestBuilder' kullanıyoruz
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

                    // Matrix ayarını yap
                    configureTransform(textureView.getWidth(), textureView.getHeight());

                    updatePreview();
                }

                @Override
                public void onConfigureFailed(@NonNull CameraCaptureSession cameraCaptureSession) {
                    Toast.makeText(MainActivity.this, "Camera preview failed", Toast.LENGTH_SHORT).show();
                }
            }, mBackgroundHandler);

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

        // 1. Sensör verisini (Buffer) ve Ekranı (View) Tanımla
        // PÜF NOKTA: BufferRect için (Height, Width) ters veriyoruz.
        // Bu sayede Matrix, yatay gelen sensör verisini dikey ekrana otomatik eşliyor.
        android.graphics.RectF viewRect = new android.graphics.RectF(0, 0, viewWidth, viewHeight);
        android.graphics.RectF bufferRect = new android.graphics.RectF(0, 0, imageDimension.getHeight(), imageDimension.getWidth());

        float centerX = viewRect.centerX();
        float centerY = viewRect.centerY();

        // 2. Buffer'ı merkeze taşı ve View içine oturt
        bufferRect.offset(centerX - bufferRect.centerX(), centerY - bufferRect.centerY());
        matrix.setRectToRect(viewRect, bufferRect, android.graphics.Matrix.ScaleToFit.CENTER);

        // 3. Ölçekleme (Doluluk) Hesabı
        // Görüntünün 3:4 çerçeveyi tam doldurması (boşluk kalmaması) için scale hesaplıyoruz.
        float scale = Math.max(
                (float) viewHeight / imageDimension.getWidth(),
                (float) viewWidth / imageDimension.getHeight());

        // 4. Rotasyon ve Ölçeklemeyi Uygula
        if (android.view.Surface.ROTATION_0 == rotation) {
            // Telefon DİK (Normal Tutuş)
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(0, centerX, centerY); // Dönüş yok, setRectToRect zaten hizaladı
        }
        else if (android.view.Surface.ROTATION_180 == rotation) {
            // Telefon Baş Aşağı
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(180, centerX, centerY);
        }
        else if (android.view.Surface.ROTATION_90 == rotation) {
            // Sola Yatık (Landscape)
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(90, centerX, centerY);
        }
        else if (android.view.Surface.ROTATION_270 == rotation) {
            // Sağa Yatık (Landscape Ters)
            matrix.postScale(scale, scale, centerX, centerY);
            matrix.postRotate(270, centerX, centerY);
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

            // --- YENİ KOD ---
            // Doğrudan sensörden gelen açıyı kullan
            int correctOrientation = getJpegOrientation();
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, correctOrientation);

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

        // --- YENİ KOD ---
        int jpegOrientation = getJpegOrientation();

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
                mBackgroundThread.join(); // Thread tamamen bitene kadar bekle
                mBackgroundThread = null;
                mBackgroundHandler = null;
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
    }
    @Override
    protected void onResume() {
        super.onResume();

        startBackgroundThread();



        // Sensörü aç
        if (orientationEventListener != null && orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable();
        }

        if (textureView.isAvailable()) {
            openCamera();
            // EKLENEN KISIM: Geri dönüşte uzama sorununu önlemek için ayarı tazele
            textureView.postDelayed(() -> {
                configureTransform(textureView.getWidth(), textureView.getHeight());
            }, 500); // Yarım saniye bekle ve düzelt
        } else {
            textureView.setSurfaceTextureListener(textureListener);
        }

        updateLutIconState();
    }

    // Bu yardımcı metodu da sınıfın içine bir yere ekle
    private void updateLutIconState() {
        String currentLut = prefs.getString("current_lut_name", null);
        if (currentLut != null && !currentLut.isEmpty()) {
            // LUT Seçili -> Renkli/Aktif İkon
            btnLut.setImageResource(R.drawable.ic_lut_enabled);
            btnLut.setAlpha(1.0f);
        } else {
            // LUT Yok -> Sönük/Pasif İkon
            btnLut.setImageResource(R.drawable.ic_lut_disabled);
            btnLut.setAlpha(0.6f);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);

        if (hasFocus) {
            // 1. ODAK GELDİ (Uygulamadasın)
            // Ekran görüntüsü almaya izin ver
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);

            // Perdeyi kaldır
            if (shutterFlash != null) {
                shutterFlash.setVisibility(View.GONE);
            }

        } else {
            // 2. ODAK KAYBOLDU (Bildirim paneli, Recents ekranı veya Ana Menüye dönülüyor)
            // HEMEN Gizlilik Modunu Aç
            getWindow().setFlags(
                    android.view.WindowManager.LayoutParams.FLAG_SECURE,
                    android.view.WindowManager.LayoutParams.FLAG_SECURE
            );

            // Garanti olsun diye Siyah Perdeyi de indir (Sistem bayrağı yetişemezse bu yetişir)
            if (shutterFlash != null) {
                shutterFlash.animate().cancel(); // Varsa animasyonu durdur
                shutterFlash.setAlpha(1f);       // Tam opak
                shutterFlash.setVisibility(View.VISIBLE);
            }
        }
    }

    @Override
    protected void onPause() {
        // 1. Oryantasyon sensörünü sustur
        if (orientationEventListener != null) {
            orientationEventListener.disable();
        }

        // 2. Kamerayı ve oturumu kapat
        closeCamera();

        // 3. İşlemciyi yoran thread'i durdur
        stopBackgroundThread();

        super.onPause();
    }

    // Add this method to properly close the camera
    private void closeCamera() {
        try {
            // 1. Önce oturumu kapat
            if (null != cameraCaptureSessions) {
                cameraCaptureSessions.stopRepeating(); // Yayını durdur
                cameraCaptureSessions.close();
                cameraCaptureSessions = null;
            }
            // 2. Kamera cihazını kapat
            if (null != cameraDevice) {
                cameraDevice.close();
                cameraDevice = null;
            }
            // 3. ImageReader'ları temizle (Bellek sızıntısı için kritik)
            if (null != imageReader) {
                imageReader.close();
                imageReader = null;
            }
            if (null != imageReaderRaw) {
                imageReaderRaw.close();
                imageReaderRaw = null;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
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