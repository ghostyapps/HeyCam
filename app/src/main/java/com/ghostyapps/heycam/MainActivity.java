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
import android.widget.ImageView;
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

import com.nothing.ketchum.BuildConfig;

import java.util.ArrayList;

import java.io.File;

import java.io.ByteArrayOutputStream;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;


public class MainActivity extends AppCompatActivity {

    private View previewCardParent;

    private View modeSelectorContainer; // Shutter | ISO yazılarının kutusu

    private ImageButton btnLut;
    private android.content.SharedPreferences prefs;

    // MainActivity.java
    private ImageView frameOverlay;

    // Tekerlek titreşimi için son pozisyonu tutar
    private int lastHapticPos = -1;

    private static final String TAG = "HeyCam";
    private static final int REQUEST_CAMERA_PERMISSION = 200;

    // --- 1. UI BİLEŞENLERİ ---
    private AutoFitTextureView textureView;
    private RecyclerView rulerRecycler;
    private TextView btnAutoManual;

    // Butonlar & Seçiciler
    private TextView btnShutter, btnIso;
    private ImageButton btnTakePhoto;
    private ImageButton btnFormat; // JPEG/RAW Değiştirici

    // Görsel Efektler
    private View focusRing;
    private View wheelNeedle;
    private View shutterFlash;

    // --- FLASH MODU DEĞİŞKENLERİ ---
    private ImageButton btnFlash;
    // 0: OFF, 1: ON (Fotoğrafta Patla), 2: TORCH (Fener)
    private int currentFlashMode = 0;

    private ImageButton btnGallery;
    private ImageButton btnModeSwitch;

    // --- 2. DURUM YÖNETİMİ & VERİLER ---
    private enum WheelMode { SHUTTER, ISO }
    private WheelMode currentWheelMode = WheelMode.SHUTTER;
    private boolean isManualMode = false;
    private boolean isRawMode = false; // Varsayılan JPEG
    private boolean mContainsRawCapability = false; // Cihaz RAW destekliyor mu?

    private SoundManager soundManager;
    private boolean isSoundEnabled = true; // Kayıtlı ayar

    // Cetvel Verileri
    private final double[] SHUTTER_VALUES = {10.0, 25.0, 50.0, 100.0, 250.0, 500.0, 1000.0};
    private final List<String> SHUTTER_STRINGS = Arrays.asList("1/10", "1/25", "1/50", "1/100", "1/250", "1/500", "1/1000");
    private int currentShutterIndex = 3;

    // EV UI Bileşenleri
    private View evControlContainer;
    private TextView txtEvValue;
    private ImageButton btnEvPlus, btnEvMinus;

    // EV (Pozlama) Mantığı için gerekli değişkenler
    private android.util.Range<Integer> evRange;
    private android.util.Rational evStep;
    private int mEvIndex = 0;
    private int stepsPerClick = 1; // Her tıklamada kaç birim atlayacağız?

    // UI Bileşenleri
    private View shutterWheelContainer; // Tekerleğin olduğu kutu

    // EV Mantığı
    private float currentEvStep = 0f; // Şu anki EV değeri (Örn: 0.33)
    private float stepSize = 1.0f / 3.0f; // 1/3 Adım
    private float maxEv = 2.0f; // Genelde +2
    private float minEv = -2.0f; // Genelde -2
    // mEvIndex: Kameraya gidecek tam sayı değeri (Camera2 API basamak sayısı ister)
    private TextView txtEvLabel; // Yeni eklenen EV yazısı

    // YENİ SATIR EKLE:
    private List<String> ISO_STRINGS = new ArrayList<>(); // Dinamik olarak doldurulacak
    private int currentIsoIndex = 1;

    // Aktif Kamera Değerleri
    private int selectedIso = 100;
    private long selectedShutter = 10000000L;

    // Sensör Tabanlı Oryantasyon Takibi
    private android.view.OrientationEventListener orientationEventListener;
    private int currentDeviceOrientation = 0;

    // Sınıfın başındaki tanımlarda:
    private ImageButton btnFrames; // View yerine ImageButton yap


    private Surface mPreviewSurface;


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


    private ImageButton btnTimer;
    private TextView txtCountdown;
    private GlyphHelper glyphHelper;

    // Sayaç Durumu
    private boolean isTimerRunning = false;
    private int selectedInterval = 3; // Varsayılan 3 sn
    private int selectedPhotoCount = 1; // Varsayılan 1 foto
    private int photosTakenInBurst = 0;
    private android.os.CountDownTimer activeTimer;

    private androidx.cardview.widget.CardView previewCard;

    // Sınıf seviyesindeki değişken
    private TextView txtCountdownOverlay;

    private void setupCountdownOverlay() {
        // 1. TextView'i kodla oluştur
        txtCountdownOverlay = new TextView(this);
        txtCountdownOverlay.setText("3");
        txtCountdownOverlay.setTextSize(120); // Boyut
        txtCountdownOverlay.setTextColor(android.graphics.Color.WHITE);
        txtCountdownOverlay.setTypeface(null, android.graphics.Typeface.BOLD);

        // Gölgelendirme (Okunabilirlik için)
        txtCountdownOverlay.setShadowLayer(30, 0, 0, android.graphics.Color.BLACK);

        // Başlangıçta gizle
        txtCountdownOverlay.setVisibility(View.GONE);

        // 2. Konumlandırma Ayarları (Tam Orta)
        android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
        );
        params.gravity = android.view.Gravity.CENTER;

        // 3. EN KRİTİK NOKTA: Uygulamanın en tepesindeki pencere katmanına ekle
        // Bu katman XML'den bağımsızdır ve her şeyin üzerindedir.
        ViewGroup rootWindow = (ViewGroup) getWindow().getDecorView();
        rootWindow.addView(txtCountdownOverlay, params);
    }

    @Override
    protected void onDestroy() {
        if (glyphHelper != null) {
            // ESKİSİ: glyphHelper.stop();
            // YENİSİ:
            glyphHelper.disconnect();
        }
        if (soundManager != null) soundManager.release();
        super.onDestroy();
    }

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

        // --- DEĞİŞİKLİK BAŞLANGICI ---
        // GlyphHelper'ı sadece 'nothing' varyantıysa VE Android 14+ ise başlatıyoruz.
        // Diğer durumlarda glyphHelper 'null' kalır ve uygulamanın geri kalanı bunu güvenle yok sayar.
        // Başına paket ismini ekleyerek karışıklığı önlüyoruz
        if (com.ghostyapps.heycam.BuildConfig.FLAVOR.equals("nothing") && android.os.Build.VERSION.SDK_INT >= 34) {
            glyphHelper = new GlyphHelper();
            glyphHelper.prepare(this);
        }
        // --- DEĞİŞİKLİK BİTİŞİ ---

        soundManager = new SoundManager();
        soundManager.init(this);

        txtCountdown = findViewById(R.id.txt_countdown);

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

        txtCountdown = findViewById(R.id.txt_countdown);


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
        //txtIsoInfo = findViewById(R.id.txt_iso_display);

        // Cetvel ve Görsel Efektler
        rulerRecycler = findViewById(R.id.shutter_recycler);
        focusRing = findViewById(R.id.focus_ring);
        previewCardParent = findViewById(R.id.preview_card_container);
        wheelNeedle = findViewById(R.id.wheel_needle);
        shutterFlash = findViewById(R.id.shutter_flash);

        evControlContainer = findViewById(R.id.ev_control_container);
        txtEvValue = findViewById(R.id.txt_ev_value);
        btnEvPlus = findViewById(R.id.btn_ev_plus);
        btnEvMinus = findViewById(R.id.btn_ev_minus);
        // Diğer findViewById'lerin yanına:
        txtEvLabel = findViewById(R.id.txt_ev_label);

        // ARTI BUTONU
        btnEvPlus.setOnClickListener(v -> {
            changeEv(1); // +1 adım
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        });

        // EKSİ BUTONU
        btnEvMinus.setOnClickListener(v -> {
            changeEv(-1); // -1 adım
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        });

        // Metin Seçiciler
        btnShutter = findViewById(R.id.btn_shutter);
        btnIso = findViewById(R.id.btn_iso);


        // Alt Kontrol Butonları
        btnTakePhoto = findViewById(R.id.take_photo);
        btnModeSwitch = findViewById(R.id.btn_mode_switch); // Auto/Manual

        // Üst Format Butonu
        btnFormat = findViewById(R.id.btn_format_indicator);

        // onCreate içinde diğer findViewById'lerin yanına ekle:
        modeSelectorContainer = findViewById(R.id.mode_selector_container);

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
        // Hazır çerçeveleri kopyala
        copyDefaultFrames();

        // Başlangıç Ayarı: Auto Mod
        toggleUiForMode(false);
        //txtIsoInfo.setText("HeyCam");



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

        setupCountdownOverlay();



    }
    // --- UI SETUP & LOGIC ---



    private void changeEv(int direction) {
        if (evRange == null || evStep == null) return;

        // DÜZELTME: direction (+1 veya -1) ile stepsPerClick'i çarpıyoruz
        // Böylece tek basışta 0.33'lük bir sıçrama yapıyoruz.
        int jump = direction * stepsPerClick;
        int newIndex = mEvIndex + jump;

        // Sınır Kontrolü
        if (newIndex >= evRange.getLower() && newIndex <= evRange.getUpper()) {
            mEvIndex = newIndex;

            float val = mEvIndex * evStep.floatValue();

            // Formatlama (Kullanıcı dostu 0.3, 0.7, 1.0 gösterimi)
            String label;
            if (Math.abs(val) < 0.05f) label = "0.0"; // 0.0'a çok yakınsa 0 de
            else if (val > 0) label = String.format("+%.1f", val);
            else label = String.format("%.1f", val);

            txtEvValue.setText(label);

            updatePreview();
        }
    }




    private void setupButtons() {
        shutterWheelContainer = findViewById(R.id.shutter_wheel_container);
        prefs = getSharedPreferences("HeyCamSettings", MODE_PRIVATE);

        // --- 1. LUT BUTONU ---
        btnLut = findViewById(R.id.btn_lut_switch);
        btnLut.setOnClickListener(v -> {
            startActivity(new android.content.Intent(MainActivity.this, LutManagerActivity.class));
        });

        // --- 2. FRAME BUTONU ---
        frameOverlay = findViewById(R.id.frame_overlay);
        btnFrames = findViewById(R.id.btn_frames);
        btnFrames.setOnClickListener(v -> {
            startActivity(new android.content.Intent(MainActivity.this, FrameManagerActivity.class));
        });

        // --- 3. TIMER BUTONU ---
        btnTimer = findViewById(R.id.btn_timer);
        btnTimer.setOnClickListener(v -> showTimerDialog());

        ImageButton btnGallery = findViewById(R.id.btn_gallery);
        if (btnGallery != null) {
            // Karmaşık izin kontrolünü sildik, direkt açıyoruz.
            btnGallery.setOnClickListener(v -> openLastPhoto());
        }

        // --- 5. FORMAT DEĞİŞTİRİCİ (RAW/JPEG) - SADELEŞTİRİLDİ ---
        // Bak burası artık tek satır oldu, çünkü tüm işi 'updateUiForFormat' yapıyor.
        btnFormat.setOnClickListener(v -> {
            if (!mContainsRawCapability) {
                Toast.makeText(this, "This device does not support RAW", Toast.LENGTH_SHORT).show();
                return;
            }

            isRawMode = !isRawMode;
            updateUiForFormat(); // SİHİRLİ SATIR BURASI
        });

        // --- 6. AUTO / MANUAL MOD DEĞİŞTİRİCİ ---
        btnModeSwitch.setOnClickListener(v -> {
            boolean targetIsManual = !isManualMode;

            if (targetIsManual) {
                // -> MANUAL MOD
                isManualMode = true;
                btnModeSwitch.setImageResource(R.drawable.ic_manual_indicator);
                toggleUiForMode(true);

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
                // -> AUTO MOD
                btnModeSwitch.setImageResource(R.drawable.ic_auto_indicator);
                isManualMode = false;
                toggleUiForMode(false);

                if (isoRange != null) selectedIso = isoRange.getLower();
                selectedShutter = 1_000_000L;

                updatePreview();
            }
        });

        // MainActivity.java -> setupButtons() içine ekle:

        btnFlash = findViewById(R.id.btn_flash_toggle);
        btnFlash.setOnClickListener(v -> {
            // Modu döngüye sok: 0 -> 1 -> 2 -> 0
            currentFlashMode = (currentFlashMode + 1) % 3;

            // İkonu ve UI'ı güncelle
            updateFlashIcon();

            // Kamerayı yeni moda göre güncelle (Özellikle Torch için anlık tepki gerekir)
            updatePreview();

            // Titreşim ver
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
        });

        // --- 7. FOTOĞRAF ÇEKME ---
        btnTakePhoto.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY);
            triggerShutterAnimation();
            takePicture();
        });

        // --- 8. TEKERLEK SEÇİMİ ---
        if (btnIso != null) btnIso.setOnClickListener(v -> switchWheelMode(WheelMode.ISO));
        if (btnShutter != null) btnShutter.setOnClickListener(v -> switchWheelMode(WheelMode.SHUTTER));
    }

    private void updateFlashIcon() {
        if (btnFlash == null) return;

        switch (currentFlashMode) {
            case 0: // OFF
                btnFlash.setImageResource(R.drawable.ic_flash_off);
                btnFlash.setAlpha(0.6f); // Kapalıyken biraz sönük olsun
                break;
            case 1: // ON (FLASH)
                btnFlash.setImageResource(R.drawable.ic_flash_on);
                btnFlash.setAlpha(1.0f);
                break;
            case 2: // TORCH
                btnFlash.setImageResource(R.drawable.ic_flash_torch);
                btnFlash.setAlpha(1.0f);
                break;
        }
    }


    private void showTimerDialog() {
        if (isTimerRunning) {
            cancelTimerSequence();
            return;
        }

        android.app.Dialog dialog = new android.app.Dialog(this);
        dialog.setContentView(R.layout.dialog_interval_timer);

        if(dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT));
        }

        android.widget.NumberPicker pkSec = dialog.findViewById(R.id.picker_seconds);
        android.widget.NumberPicker pkCount = dialog.findViewById(R.id.picker_count);
        com.google.android.material.button.MaterialButton btnStart = dialog.findViewById(R.id.btn_start_timer);

        // --- DÜZELTME BURADA: TÜRÜ DEĞİŞTİRİYORUZ ---
        // MaterialSwitch yerine SwitchCompat kullanıyoruz
        androidx.appcompat.widget.SwitchCompat switchSound = dialog.findViewById(R.id.switch_sound);


        // --- AYARLARI YÜKLE ---
        pkSec.setMinValue(1); pkSec.setMaxValue(10); pkSec.setValue(selectedInterval);
        pkCount.setMinValue(1); pkCount.setMaxValue(10); pkCount.setValue(selectedPhotoCount);

        // Kayıtlı ses ayarını çek (Varsayılan: True)
        isSoundEnabled = prefs.getBoolean("timer_sound_enabled", true);
        switchSound.setChecked(isSoundEnabled);

        // Klavye Engelleme
        pkSec.setDescendantFocusability(android.widget.NumberPicker.FOCUS_BLOCK_DESCENDANTS);
        pkCount.setDescendantFocusability(android.widget.NumberPicker.FOCUS_BLOCK_DESCENDANTS);

        btnStart.setOnClickListener(v -> {
            pkSec.clearFocus();
            pkCount.clearFocus();

            selectedInterval = pkSec.getValue();
            selectedPhotoCount = pkCount.getValue();

            // --- AYARI KAYDET VE UYGULA ---
            isSoundEnabled = switchSound.isChecked();
            prefs.edit().putBoolean("timer_sound_enabled", isSoundEnabled).apply();
            soundManager.setSoundEnabled(isSoundEnabled); // Yöneticiye bildir

            dialog.dismiss();
            startTimerSequence();
        });

        dialog.show();
    }

    private void startTimerSequence() {
        isTimerRunning = true;
        photosTakenInBurst = 0;

        // --- BAĞLAN ---
        if (glyphHelper != null) {
            glyphHelper.connect();
        }

        btnTimer.setImageResource(R.drawable.ic_close);
        runNextCountdown();
    }


    private void runNextCountdown() {
        if (!isTimerRunning) return;

        // Hedefe ulaştık mı?
        if (photosTakenInBurst >= selectedPhotoCount) {
            finishTimerSequence();
            return;
        }

        // --- SAYACI HAZIRLA ---
        if (txtCountdownOverlay != null) {
            txtCountdownOverlay.setVisibility(View.VISIBLE);
            txtCountdownOverlay.setText("");
            txtCountdownOverlay.bringToFront();
        }

        // Timer Başlıyor
        activeTimer = new android.os.CountDownTimer(selectedInterval * 1000L, 1000) {
            @Override
            public void onTick(long millisUntilFinished) {
                // Matematiksel yuvarlama (0 görünmesin diye)
                int secondsLeft = (int) Math.ceil(millisUntilFinished / 1000.0);
                if (secondsLeft < 1) secondsLeft = 1; // Garanti olsun

                // 1. Ekrana Yaz
                if (txtCountdownOverlay != null) {
                    txtCountdownOverlay.setText(String.valueOf(secondsLeft));

                    // Kalp Atışı Animasyonu
                    txtCountdownOverlay.setScaleX(0.5f);
                    txtCountdownOverlay.setScaleY(0.5f);
                    txtCountdownOverlay.setAlpha(0f);

                    txtCountdownOverlay.animate()
                            .scaleX(1f).scaleY(1f)
                            .alpha(1f)
                            .setDuration(300)
                            .setInterpolator(new android.view.animation.OvershootInterpolator())
                            .start();
                }

                // 2. Ses (Bip)
                if (soundManager != null) soundManager.playTick();

                // 3. Matrix (Rakam)
                if (glyphHelper != null) {
                    glyphHelper.displayCounter(secondsLeft, currentDeviceOrientation);
                }
            }

            @Override
            public void onFinish() {
                // --- SÜRE BİTTİ ---

                // 1. Sayacı ve Işığı Temizle (0 görünmesin)
                if (txtCountdownOverlay != null) txtCountdownOverlay.setVisibility(View.GONE);
                if (glyphHelper != null) glyphHelper.clearDisplay();

                // 2. Çekim Efektleri (SES ve GÖRÜNTÜ)
                if (soundManager != null) soundManager.playShutter(); // ŞAK sesi
                triggerShutterAnimation(); // Siyah perde

                // 3. Fotoğrafı Çek
                takePicture();
                photosTakenInBurst++; // Sayacı artır

                // 4. KARAR ANI
                if (photosTakenInBurst >= selectedPhotoCount) {
                    // HEDEF TAMAMLANDI
                    // Fotoğrafın kaydedilmesi için yarım saniye pay ver, sonra bitiriş efektine geç
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        finishTimerSequence();
                    }, 500);
                } else {
                    // DEVAM EDECEK
                    // Bir sonraki poz için 2 saniye bekle
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        runNextCountdown();
                    }, 2000);
                }
            }
        }.start();
    }


    private void finishTimerSequence() {
        isTimerRunning = false;
        btnTimer.setImageResource(R.drawable.ic_timer);

        if (txtCountdownOverlay != null) txtCountdownOverlay.setVisibility(View.GONE);

        // --- DÜZELTME BURADA ---
        // Sesi dışarı aldık, artık her versiyonda çalar.
        if (soundManager != null) {
            soundManager.playFinish();
        }

        // --- FİNAL DESENİ VE KAPANIŞ (Sadece Nothing için) ---
        if (glyphHelper != null) {
            // GÖRÜNTÜ: Deseni gönder
            glyphHelper.displayFinishPattern(currentDeviceOrientation);

            // SÜRE: Işık ne kadar açık kalsın? (2000ms = 2 Saniye)
            new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                if (!isTimerRunning) {
                    glyphHelper.clearDisplay();
                    new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> {
                        glyphHelper.disconnect();
                    }, 200);
                }
            }, 2000);
        }

        showCenteredMessage("Sequence Complete");
    }




    private void cancelTimerSequence() {
        isTimerRunning = false;
        if (activeTimer != null) activeTimer.cancel();
        btnTimer.setImageResource(R.drawable.ic_timer);

        if (txtCountdownOverlay != null) txtCountdownOverlay.setVisibility(View.GONE);

        // --- BAĞLANTIYI KOPAR ---
        if (glyphHelper != null) {
            glyphHelper.disconnect();
        }

        showCenteredMessage("Timer Cancelled");
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
        long duration = 250;

        if (manual) {
            // --- MANUAL MODA GEÇİŞ ---

            // 1. Auto Öğelerini Gizle
            if (evControlContainer != null) {
                evControlContainer.animate().alpha(0f).setDuration(duration)
                        .withEndAction(() -> evControlContainer.setVisibility(View.GONE))
                        .start();
            }
            if (txtEvLabel != null) {
                txtEvLabel.animate().alpha(0f).setDuration(duration)
                        .withEndAction(() -> txtEvLabel.setVisibility(View.GONE))
                        .start();
            }

            // 2. Manual Öğelerini Göster
            if (shutterWheelContainer != null) {
                shutterWheelContainer.setVisibility(View.VISIBLE);
                shutterWheelContainer.setAlpha(0f);
                shutterWheelContainer.animate().alpha(1f).setDuration(duration).start();
            }

            if (modeSelectorContainer != null) {
                modeSelectorContainer.setVisibility(View.VISIBLE);
                modeSelectorContainer.setAlpha(0f);
                modeSelectorContainer.animate().alpha(1f).setDuration(duration).start();
            }

        } else {
            // --- AUTO MODA GEÇİŞ ---

            // 1. Manual Öğelerini Gizle
            if (shutterWheelContainer != null) {
                shutterWheelContainer.animate().alpha(0f).setDuration(duration)
                        .withEndAction(() -> shutterWheelContainer.setVisibility(View.GONE))
                        .start();
            }
            if (modeSelectorContainer != null) {
                modeSelectorContainer.animate().alpha(0f).setDuration(duration)
                        .withEndAction(() -> modeSelectorContainer.setVisibility(View.INVISIBLE))
                        .start();
            }

            // --- DÜZELTME BURADA ---
            // Hedef parlaklığı belirle: RAW açıksa sönük (0.3), değilse parlak (1.0)
            float targetAlpha = isRawMode ? 0.3f : 1.0f;

            // 2. Auto Öğelerini Göster (Hedef parlaklığa göre)
            if (evControlContainer != null) {
                evControlContainer.setVisibility(View.VISIBLE);
                evControlContainer.setAlpha(0f);
                evControlContainer.animate().alpha(targetAlpha).setDuration(duration).start();
            }

            if (txtEvLabel != null) {
                txtEvLabel.setVisibility(View.VISIBLE);
                txtEvLabel.setAlpha(0f);
                txtEvLabel.animate().alpha(targetAlpha).setDuration(duration).start();
            }
        }
    }

    private void updateInfoText() {
        String sVal = SHUTTER_STRINGS.get(currentShutterIndex);
        String iVal = ISO_STRINGS.get(currentIsoIndex);
        //txtIsoInfo.setText("SHUTTER: " + sVal + "   ISO: " + iVal);
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

            // --- EV (POZLAMA) HESAPLAMA ---
            evRange = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
            evStep = characteristics.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);

            if (evRange != null && evStep != null) {
                mEvIndex = 0;
                float stepVal = evStep.floatValue();
                float targetStep = 1.0f / 3.0f;
                stepsPerClick = Math.max(1, Math.round(targetStep / stepVal));
                android.util.Log.d("HeyCam", "Native Step: " + stepVal + ", Steps Per Click: " + stepsPerClick);
            }

            shutterRange = characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE);
            sensorArraySize = characteristics.get(android.hardware.camera2.CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);

            // FPS
            android.util.Range<Integer>[] availableFpsRanges = characteristics.get(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
            if (availableFpsRanges != null && availableFpsRanges.length > 0) {
                bestFpsRange = availableFpsRanges[availableFpsRanges.length - 1];
            }

            // --- 1. PREVIEW BOYUTU (4:3 Öncelikli) ---
            android.util.Size previewSize = new android.util.Size(640, 480);
            if (map != null) {
                android.util.Size[] sizes = map.getOutputSizes(android.graphics.SurfaceTexture.class);
                if (sizes != null) {
                    for (android.util.Size sz : sizes) {
                        float ratio = (float) sz.getWidth() / sz.getHeight();
                        if (Math.abs(ratio - 1.3333f) < 0.05f) {
                            if ((long) sz.getWidth() * sz.getHeight() > (long) previewSize.getWidth() * previewSize.getHeight()) {
                                previewSize = sz;
                            }
                        }
                    }
                }
            }

            imageDimension = previewSize;

            // --- ASPECT RATIO AYARI ---
            if (imageDimension != null) {
                if (getResources().getConfiguration().orientation == android.content.res.Configuration.ORIENTATION_PORTRAIT) {
                    textureView.setAspectRatio(imageDimension.getHeight(), imageDimension.getWidth());
                } else {
                    textureView.setAspectRatio(imageDimension.getWidth(), imageDimension.getHeight());
                }
            }

            // --- 2. JPEG IMAGEREADER ---
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

            // --- GÜNCELLENMİŞ LISTENER (DOĞRU FRAME MANTIĞI) ---
            imageReader.setOnImageAvailableListener(reader -> {
                try (android.media.Image image = reader.acquireNextImage()) {
                    if (image != null) {
                        java.nio.ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                        byte[] bytes = new byte[buffer.remaining()];
                        buffer.get(bytes);

                        String currentLutName = prefs.getString("current_lut_name", null);
                        String currentFrameName = prefs.getString("current_frame_name", null);

                        boolean hasLut = (currentLutName != null && !currentLutName.isEmpty());
                        boolean hasFrame = (currentFrameName != null && !currentFrameName.isEmpty());

                        if (!hasLut && !hasFrame) {
                            save(bytes);
                            return;
                        }

                        showCenteredMessage("Processing...");

                        // --- A. BITMAP OLUŞTUR VE DÖNDÜR (ANA FOTOĞRAF) ---
                        android.graphics.BitmapFactory.Options opt = new android.graphics.BitmapFactory.Options();
                        opt.inMutable = true;
                        android.graphics.Bitmap bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, opt);

                        if (bitmap != null) {
                            try {
                                android.media.ExifInterface exif = new android.media.ExifInterface(new java.io.ByteArrayInputStream(bytes));
                                int orientation = exif.getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, android.media.ExifInterface.ORIENTATION_NORMAL);

                                int rotationInDegrees = 0;
                                if (orientation == android.media.ExifInterface.ORIENTATION_ROTATE_90) rotationInDegrees = 90;
                                else if (orientation == android.media.ExifInterface.ORIENTATION_ROTATE_180) rotationInDegrees = 180;
                                else if (orientation == android.media.ExifInterface.ORIENTATION_ROTATE_270) rotationInDegrees = 270;

                                // Ana fotoğrafı düzelt (Dikse dik, Yataysa yatay hale getir)
                                if (rotationInDegrees != 0) {
                                    android.graphics.Matrix matrix = new android.graphics.Matrix();
                                    matrix.preRotate(rotationInDegrees);
                                    android.graphics.Bitmap rotatedBitmap = android.graphics.Bitmap.createBitmap(
                                            bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), matrix, true);

                                    if (bitmap != rotatedBitmap) {
                                        bitmap.recycle();
                                        bitmap = rotatedBitmap;
                                    }
                                }
                            } catch (Exception e) { e.printStackTrace(); }

                            // --- B. LUT UYGULA ---
                            if (hasLut) {
                                java.io.File lutFile = new java.io.File(getFilesDir(), "luts/" + currentLutName);
                                if (lutFile.exists()) {
                                    CubeLut lutProcessor = new CubeLut();
                                    if (lutProcessor.load(lutFile)) {
                                        lutProcessor.apply(bitmap);
                                    }
                                }
                            }

                            // --- C. ÇERÇEVE UYGULA (DÜZELTİLDİ) ---
                            if (hasFrame) {
                                java.io.File frameFile = new java.io.File(getFilesDir(), "frames/" + currentFrameName);
                                if (frameFile.exists()) {
                                    android.graphics.Bitmap frameBitmap = android.graphics.BitmapFactory.decodeFile(frameFile.getAbsolutePath());

                                    if (frameBitmap != null) {

                                        // 1. Ana fotoğrafın son haline bak: Yatay mı? (Genişlik > Yükseklik)
                                        boolean isBitmapLandscape = bitmap.getWidth() > bitmap.getHeight();

                                        // 2. Çerçeveye bak: Dik mi? (Yükseklik > Genişlik)
                                        boolean isFramePortrait = frameBitmap.getHeight() > frameBitmap.getWidth();

                                        // Eğer Fotoğraf YATAY ama Çerçeve DİK ise -> Çerçeveyi çevir!
                                        if (isBitmapLandscape && isFramePortrait) {
                                            android.graphics.Matrix frameMatrix = new android.graphics.Matrix();
                                            // -90 derece çevirerek yatay yapıyoruz
                                            frameMatrix.postRotate(-90);

                                            android.graphics.Bitmap rotatedFrame = android.graphics.Bitmap.createBitmap(
                                                    frameBitmap, 0, 0, frameBitmap.getWidth(), frameBitmap.getHeight(), frameMatrix, true);

                                            if (frameBitmap != rotatedFrame) {
                                                frameBitmap.recycle();
                                                frameBitmap = rotatedFrame;
                                            }
                                        }

                                        // NOT: Eğer Fotoğraf DİK ise (Portrait), çerçeve de zaten DİK olduğu için
                                        // hiçbir şey yapmıyoruz. Olduğu gibi çiziyoruz.

                                        android.graphics.Canvas canvas = new android.graphics.Canvas(bitmap);
                                        android.graphics.Rect destRect = new android.graphics.Rect(0, 0, bitmap.getWidth(), bitmap.getHeight());
                                        canvas.drawBitmap(frameBitmap, null, destRect, new android.graphics.Paint(android.graphics.Paint.FILTER_BITMAP_FLAG));

                                        frameBitmap.recycle();
                                    }
                                }
                            }

                            // --- D. KAYDET ---
                            try (java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream()) {
                                bitmap.compress(android.graphics.Bitmap.CompressFormat.JPEG, 95, out);
                                byte[] newBytes = out.toByteArray();
                                save(newBytes);
                            } catch (Exception e) { e.printStackTrace(); }

                            bitmap.recycle();
                        }
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

            // İzin Kontrolü
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

            if (imageDimension != null) {
                texture.setDefaultBufferSize(imageDimension.getWidth(), imageDimension.getHeight());
            }

            // --- DÜZELTME BURADA (Arkadaşının 2. Uyarısı) ---
            // Asla "varsa release et" demiyoruz. Varsa KULLAN diyoruz.
            // Sadece null ise yeni oluşturuyoruz.
            if (mPreviewSurface == null) {
                mPreviewSurface = new Surface(texture);
            }

            // Builder oluştur
            captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            captureRequestBuilder.addTarget(mPreviewSurface);

            // --- YÜZEY LİSTESİ ---
            java.util.List<Surface> outputSurfaces = new java.util.ArrayList<>();
            outputSurfaces.add(mPreviewSurface);

            if (imageReader != null) outputSurfaces.add(imageReader.getSurface());
            if (mContainsRawCapability && imageReaderRaw != null) outputSurfaces.add(imageReaderRaw.getSurface());

            cameraDevice.createCaptureSession(outputSurfaces, new CameraCaptureSession.StateCallback() {
                @Override
                public void onConfigured(@NonNull CameraCaptureSession cameraCaptureSession) {
                    if (cameraDevice == null) return;
                    cameraCaptureSessions = cameraCaptureSession;
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
            // Builder yoksa oluştur (Yüzey kontrolüyle birlikte)
            if (captureRequestBuilder == null) {
                captureRequestBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);

                // Yüzey kazara null olduysa (çok nadir), mecburen oluştur
                if (mPreviewSurface == null) {
                    SurfaceTexture texture = textureView.getSurfaceTexture();
                    if (texture != null) {
                        if (imageDimension != null) texture.setDefaultBufferSize(imageDimension.getWidth(), imageDimension.getHeight());
                        mPreviewSurface = new Surface(texture);
                    }
                }
                if (mPreviewSurface != null) captureRequestBuilder.addTarget(mPreviewSurface);
            }

            // Trigger Temizliği
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
            captureRequestBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);

            // --- DÜZELTME BURADA (Arkadaşının 1. Uyarısı) ---
            // Lensi fiziksel olarak "KİLİTLİ/AKTİF" tutmak için OIS açıyoruz.
            // Bu, lensin motorlarını canlı tutar ve düşmesini engeller.
            captureRequestBuilder.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON);

            // Diğer Ayarlar
            if (sensorArraySize != null) {
                captureRequestBuilder.set(CaptureRequest.SCALER_CROP_REGION, sensorArraySize);
            }
            captureRequestBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            if (isManualMode) {
                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
                captureRequestBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, selectedIso);
                captureRequestBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, selectedShutter);
            } else {
                captureRequestBuilder.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);
                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
                captureRequestBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, mEvIndex);
            }

            if (currentFlashMode == 2) {
                captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
            } else {
                captureRequestBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
            }

            cameraCaptureSessions.setRepeatingRequest(captureRequestBuilder.build(), captureCallback, mBackgroundHandler);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }




    // --- SHUTTER EFEKTİ METODU ---
    private void triggerShutterAnimation() {
        if (shutterFlash != null) {
            // Görünür yap ve en öne getir
            shutterFlash.setVisibility(View.VISIBLE);
            shutterFlash.bringToFront();

            // Başlangıç durumu (Simsiyah)
            shutterFlash.setAlpha(1f);

            // Animasyon (Hızla yok ol)
            shutterFlash.animate()
                    .alpha(0f)
                    .setDuration(150)
                    .setStartDelay(50)
                    .withEndAction(() -> {
                        shutterFlash.setVisibility(View.GONE);
                    })
                    .start();
        }
    }

    private void takePicture() {
        if (cameraDevice == null || cameraCaptureSessions == null) return;

        try {
            // 1. İsteği oluştur
            final CaptureRequest.Builder captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);

            // 2. HEDEF BELİRLEME
            Surface targetSurface = null;
            if (isRawMode && mContainsRawCapability && imageReaderRaw != null) {
                targetSurface = imageReaderRaw.getSurface();
                captureBuilder.set(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_OFF);
                captureBuilder.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO);
                captureBuilder.set(CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE, CaptureRequest.STATISTICS_LENS_SHADING_MAP_MODE_ON);
            } else if (imageReader != null) {
                targetSurface = imageReader.getSurface();
            }

            if (targetSurface == null) return;
            captureBuilder.addTarget(targetSurface);

            // 3. AYARLARI UYGULA
            if (sensorArraySize != null) {
                captureBuilder.set(CaptureRequest.SCALER_CROP_REGION, sensorArraySize);
            }
            captureBuilder.set(CaptureRequest.DISTORTION_CORRECTION_MODE, CaptureRequest.DISTORTION_CORRECTION_MODE_OFF);

            if (isManualMode) {
                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_OFF);
                captureBuilder.set(CaptureRequest.SENSOR_SENSITIVITY, selectedIso);
                captureBuilder.set(CaptureRequest.SENSOR_EXPOSURE_TIME, selectedShutter);
            } else {
                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
                captureBuilder.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, mEvIndex);
            }

            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, getJpegOrientation());
            // ÖNEMLİ: Çekim sırasında odaklamayı kilitleme veya değiştirme, olduğu gibi kalsın.
            captureBuilder.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE);

            // --- FLASH MANTIĞI ---
            if (currentFlashMode == 2) {
                // TORCH: Fener gibi sürekli yanarak çek
                captureBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
                // Torch modunda AE modu değiştirmeye gerek yok, mevcut ışıkla çeker.

            } else if (currentFlashMode == 1) {
                // FLASH ON: Zorla Patlat

                // 1. AE Modunu "Her Zaman Patla"ya zorla (Manual ayarları ezer, Auto'ya geçer)
                captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH);

                // 2. Flash Modunu Tekli Yap
                captureBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_SINGLE);

                // 3. KRİTİK EKLEME: Tetikleyici (Trigger)
                // Kameraya "Flash sekansını şimdi başlat" emri verir. Bunu koymazsak flash patlamaz.
                captureBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);

            } else {
                // OFF: Asla yakma
                captureBuilder.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);

                // Eğer manual modda değilsek ve Flash OFF ise normal Auto moda dön
                if (!isManualMode) {
                    captureBuilder.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
                }
            }

            // 4. ÇEKİMİ BAŞLAT
            cameraCaptureSessions.capture(captureBuilder.build(), new CameraCaptureSession.CaptureCallback() {
                @Override
                public void onCaptureCompleted(@NonNull CameraCaptureSession session, @NonNull CaptureRequest request, @NonNull TotalCaptureResult result) {
                    super.onCaptureCompleted(session, request, result);
                    mLastResult = result;

                    // --- SORUNUN ÇÖZÜLDÜĞÜ YER ---
                    // Burada eskiden "CONTROL_AF_TRIGGER_CANCEL" komutu vardı.
                    // O komut lensi B pozisyonuna (Sıfır noktasına) fırlatıyordu.
                    // O kodu SİLDİK.

                    // Sadece preview'i güncellememiz yeterli.
                    // Lens zaten doğru yerdeyse (A pozisyonunda) kıpırdamadan orada kalacaktır.
                    try {
                        updatePreview();
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }, mBackgroundHandler);

        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }


    // RAW/JPEG Moduna göre arayüzü güncelleyen metod
    private void updateUiForFormat() {
        // 1. Format İkonunu Değiştir
        if (isRawMode) {
            btnFormat.setImageResource(R.drawable.ic_raw_indicator);
            btnFormat.setAlpha(1.0f);
        } else {
            btnFormat.setImageResource(R.drawable.ic_jpeg_indicator);
            btnFormat.setAlpha(0.9f);
        }

        // RAW Modunda Özellikler Kapalı (False), JPEG'de Açık (True)
        boolean areFeaturesEnabled = !isRawMode;

        // 2. LUT Butonu Yönetimi
        if (btnLut != null) {
            btnLut.setEnabled(areFeaturesEnabled);
            if (areFeaturesEnabled) {
                updateLutIconState();
            } else {
                btnLut.setAlpha(0.3f);
            }
        }

        // 3. FRAME Yönetimi (DÜZELTME BURADA)
        if (btnFrames != null) {
            btnFrames.setEnabled(areFeaturesEnabled);

            if (areFeaturesEnabled) {
                // JPEG: Varsa çerçeveyi geri getir ve ikonu düzelt
                updateFrameState();
            } else {
                // RAW: Butonu söndür VE ekrandaki çerçeveyi gizle
                btnFrames.setAlpha(0.3f);
                if (frameOverlay != null) {
                    frameOverlay.setVisibility(View.GONE);
                }
            }
        }

        // 4. EV (Pozlama) Kontrolleri Yönetimi
        if (btnEvPlus != null) btnEvPlus.setEnabled(areFeaturesEnabled);
        if (btnEvMinus != null) btnEvMinus.setEnabled(areFeaturesEnabled);

        float targetAlpha = areFeaturesEnabled ? 1.0f : 0.3f;

        if (evControlContainer != null) {
            evControlContainer.animate().alpha(targetAlpha).setDuration(200).start();
        }
        if (txtEvLabel != null) {
            txtEvLabel.animate().alpha(targetAlpha).setDuration(200).start();
        }

        // 5. Kullanıcıya Mesaj
        if (isRawMode) {
            showCenteredMessage("RAW: All Effects Disabled");
        }
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



        // 1. Güvenlik Modunu Kapat
        getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);

        // 2. Perdeyi Kaldır (Animasyon)
        if (shutterFlash != null) {
            shutterFlash.setVisibility(View.VISIBLE);
            shutterFlash.setAlpha(1f);
            shutterFlash.animate()
                    .alpha(0f)
                    .setDuration(300)
                    .withEndAction(() -> shutterFlash.setVisibility(View.GONE))
                    .start();
        }

        // --- DÜZELTME BURADA: EV DEĞERLERİNİ SIFIRLA ---
        mEvIndex = 0; // Mantığı sıfırla
        if (txtEvValue != null) {
            txtEvValue.setText("0.0"); // Görünümü sıfırla
        }

        // --- DÜZELTME BURADA: GLYPH DURUMUNU SIFIRLA ---
        if (glyphHelper != null) {
            glyphHelper.clearDisplay(); // Varsa ışığı söndür
            glyphHelper.reset();        // Bağlantı bilgisini unut (Taze başlangıç için)
        }
        // ----------------------------------------------

        // Thread Başlat
        startBackgroundThread();

        // Sensörü Aç
        if (orientationEventListener != null && orientationEventListener.canDetectOrientation()) {
            orientationEventListener.enable();
        }

        // Kamerayı Aç
        if (textureView.isAvailable()) {
            openCamera();
            // Dönüşte texture bozulmasını önlemek için gecikmeli ayar
            textureView.postDelayed(() -> {
                configureTransform(textureView.getWidth(), textureView.getHeight());
            }, 500);
        } else {
            textureView.setSurfaceTextureListener(textureListener);
        }


        updateUiForFormat();
    }


    private void updateFrameState() {
        // Önce butonu XML'den bulduğumuza emin olalım (Eğer tanımlı değilse)
        // Eğer sınıf başında 'private ImageButton btnFrames;' tanımlı değilse tanımla
        // ve onCreate içinde 'btnFrames = findViewById(R.id.btn_frames);' yap.
        // Ama şimdilik view üzerinden gidelim:

        if (btnFrames == null) btnFrames = findViewById(R.id.btn_frames); // Garanti olsun

        String currentFrame = prefs.getString("current_frame_name", "");

        if (!currentFrame.isEmpty()) {
            File file = new File(getFilesDir(), "frames/" + currentFrame);
            if (file.exists()) {
                // Dosyayı Bitmap olarak yükle ve Overlay'e koy
                android.graphics.Bitmap frameBmp = android.graphics.BitmapFactory.decodeFile(file.getAbsolutePath());
                frameOverlay.setImageBitmap(frameBmp);
                frameOverlay.setVisibility(View.VISIBLE);

                // --- İKON DEĞİŞİMİ: AKTİF ---
                btnFrames.setImageResource(R.drawable.ic_frame_icon_selected);
                btnFrames.setAlpha(1.0f);
            } else {
                frameOverlay.setVisibility(View.GONE);
                // Dosya bulunamadı, varsayılan ikon
                btnFrames.setImageResource(R.drawable.ic_frame_icon);
                btnFrames.setAlpha(0.7f);
            }
        } else {
            // Frame Yok, Varsayılan ikon
            frameOverlay.setVisibility(View.GONE);

            // --- İKON DEĞİŞİMİ: PASİF ---
            btnFrames.setImageResource(R.drawable.ic_frame_icon);
            btnFrames.setAlpha(0.7f);
        }
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
            // Uygulamaya geri dönüldü veya Dialog kapandı
            getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE);

            if (shutterFlash != null) {
                shutterFlash.setVisibility(View.GONE);
            }
        } else {
            // Odak gitti. Eğer Timer Dialogu gibi bir şey açıksa
            // perdeyi indirme, ama uygulama tamamen arka plana gidiyorsa indir.

            // Önemli: Timer Dialogu açıldığında isTimerRunning henüz true olmayabilir
            // veya kullanıcı sadece seçim yapıyordur.
            // En temiz yol: Uygulama tamamen durdurulmadıysa (isFinishing/isPaused gibi) perdeyi çekme.

            // Buradaki kontrolü "isFinishing" veya başka bir flag ile destekleyebilirsin
            // ama Timer Dialogu için en garanti çözüm şudur:
            if (!isFinishing()) {
                // Dialog açıldı ama uygulama hala aktif, perdeyi çekme
                return;
            }

            // Uygulama gerçekten arka plana gidiyor (Recents ekranı vb.)
            getWindow().setFlags(
                    android.view.WindowManager.LayoutParams.FLAG_SECURE,
                    android.view.WindowManager.LayoutParams.FLAG_SECURE
            );

            if (shutterFlash != null) {
                shutterFlash.animate().cancel();
                shutterFlash.setAlpha(1f);
                shutterFlash.setVisibility(View.VISIBLE);
            }
        }
    }


    @Override
    protected void onPause() {

        // 1. Işıkları Söndür (Zombi ışık kalmasın)
        if (glyphHelper != null) {
            glyphHelper.clearDisplay(); // Sadece temizle, bağlantıyı koparma
        }

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
            // 1. OTURUMU KAPAT VE LENSI SAKİNLEŞTİR
            if (null != cameraCaptureSessions) {
                try {
                    // AF İptal Emri: Lensi kapatmadan önce son bir "Rahatla" komutu.
                    // Eğer captureRequestBuilder duruyorsa kullanıyoruz.
                    if (captureRequestBuilder != null) {
                        captureRequestBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
                        cameraCaptureSessions.capture(captureRequestBuilder.build(), null, mBackgroundHandler);
                    }

                    // Yayını ve yakalamayı durdur (Lensi park et)
                    cameraCaptureSessions.stopRepeating();
                    cameraCaptureSessions.abortCaptures();

                } catch (Exception e) {
                    // Kapanırken hata alsa bile yut, devam et.
                    e.printStackTrace();
                }

                cameraCaptureSessions.close();
                cameraCaptureSessions = null;
            }

            // 2. KAMERA CİHAZINI KAPAT
            if (null != cameraDevice) {
                cameraDevice.close();
                cameraDevice = null;
            }

            // 3. OKUYUCULARI TEMİZLE (Memory Leak Önlemi)
            if (null != imageReader) {
                imageReader.close();
                imageReader = null;
            }
            if (null != imageReaderRaw) {
                imageReaderRaw.close();
                imageReaderRaw = null;
            }

            // --- 4. KRİTİK EKLENTİ: YÜZEYİ SERBEST BIRAK ---
            // Arkadaşının bahsettiği "Sadece onPause/Çıkışta yap" kuralı burasıdır.
            // Bunu yapmazsak uygulama tekrar açıldığında eski yüzeyle çakışır ve lens düşer.
            if (mPreviewSurface != null) {
                mPreviewSurface.release();
                mPreviewSurface = null;
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);

        // Mevcut Kamera izni kontrolün burada duruyor...
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

    // --- HAZIR ÇERÇEVELERİ YÜKLEME ---
    private void copyDefaultFrames() {
        boolean isFramesInstalled = prefs.getBoolean("are_default_frames_installed", false);
        if (isFramesInstalled) return;

        new Thread(() -> {
            try {
                String[] files = getAssets().list("sample_frames");
                if (files == null) return;

                File destDir = new File(getFilesDir(), "frames");
                if (!destDir.exists()) destDir.mkdirs();

                for (String filename : files) {
                    if (!filename.toLowerCase().endsWith(".png")) continue;

                    File outFile = new File(destDir, filename);
                    if (outFile.exists()) continue;

                    try (java.io.InputStream in = getAssets().open("sample_frames/" + filename);
                         java.io.FileOutputStream out = new java.io.FileOutputStream(outFile)) {

                        byte[] buffer = new byte[1024];
                        int read;
                        while ((read = in.read(buffer)) != -1) {
                            out.write(buffer, 0, read);
                        }
                    }
                }

                prefs.edit().putBoolean("are_default_frames_installed", true).apply();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
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