package com.ghostyapps.heycam;

import android.Manifest;
import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.SurfaceTexture;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.CountDownTimer;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.SuperscriptSpan;
import android.util.Size;
import android.util.TypedValue;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.res.ResourcesCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.ghostyapps.heycam.camera.CameraCaps;
import com.ghostyapps.heycam.camera.CameraController;
import com.ghostyapps.heycam.camera.CaptureMeta;
import com.ghostyapps.heycam.camera.PhotoSaver;
import com.ghostyapps.heycam.ui.BatteryView;
import com.ghostyapps.heycam.ui.CountdownView;
import com.ghostyapps.heycam.ui.DialView;
import com.ghostyapps.heycam.ui.ExposureScale;
import com.ghostyapps.heycam.camera.QrScanner;
import com.ghostyapps.heycam.ui.HistogramView;
import com.ghostyapps.heycam.ui.ViewfinderOverlay;
import com.ghostyapps.heycam.ui.WheelPicker;

import java.io.File;
import java.util.Locale;

public class MainActivity extends AppCompatActivity implements CameraController.Listener, PhotoSaver.Listener {

    private static final int REQUEST_PERMISSIONS = 200;
    private static final String PREF_RAW = "format_raw";
    private static final String PREF_PROCESSING = "jpeg_processing";
    private static final String PREF_GRID = "grid";
    private static final String PREF_HISTOGRAM = "histogram";
    private static final String PREF_QR = "qr_scan";
    private static final String PREF_SOUND = "timer_sound_enabled";
    private static final String PREF_GLYPH = "glyph_enabled";
    private static final String PREF_INTERVAL = "timer_interval";
    private static final String PREF_SHOTS = "timer_shots";
    private static final String PREF_LUT = "current_lut_name";
    private static final String PREF_FRAME = "current_frame_name";

    private enum Dial { S, ISO, EV, FOCUS }

    /** Continuous AF, AF locked on a tapped point, or manual focus distance. */
    private enum Focus { AF, AFL, MF }

    private final Handler main = new Handler(Looper.getMainLooper());
    private SharedPreferences prefs;
    private PhotoSaver saver;
    private CameraController camera;
    private CameraCaps caps;
    private GlyphHelper glyph;
    private SoundManager sound;
    private OrientationEventListener orientationListener;
    private int deviceOrientation;

    // Views
    private TextureView texture;
    private ViewfinderOverlay overlay;
    private HistogramView histogram;
    private ImageView frameOverlay, thumb, demoImage;
    private Bitmap demoBitmap;
    private long demoExposureNs;
    private int demoIso;
    private float demoFocus = -1f;
    private View curtain, toast, menu, sheetScrim;
    private TextView qrChip;
    private TextView toastText, txtFormat, txtLut, txtTimer, txtFlash, txtCount, mode;
    private BatteryView battery;
    private DialView dial;
    private CountdownView countdown;
    private Cell cellS, cellIso, cellEv, cellFocus;
    private LinearLayout menuRows, lensPicker;
    private WheelPicker pickSeconds, pickShots;
    private TextView optSound, optGlyph, optTotal;

    // Exposure state
    private ExposureScale shutterScale = ExposureScale.shutter(null);
    private ExposureScale isoScale = ExposureScale.iso(null);
    private ExposureScale evScale = ExposureScale.ev(null, 1f / 3f);
    private boolean manual;
    private int shutterIdx = -1, isoIdx = -1, evIdx = -1;
    private Dial dialTarget = Dial.EV;
    private long liveExposureNs;
    private int liveIso;
    private ExposureScale focusScale = ExposureScale.focus(0);
    private int focusIdx = -1;
    private Focus focus = Focus.AF;
    private float liveFocus = -1f;
    private final Runnable endMagnify = () -> setMagnified(false);
    private boolean dialTouched;

    // Settings
    private boolean raw;
    private CameraController.Flash flash = CameraController.Flash.OFF;
    private CameraController.Processing processing = CameraController.Processing.MINIMAL;
    private boolean grid, histogramOn, soundOn, glyphOn, qrOn;

    // Interval timer
    private boolean timerRunning;
    private int timerShotsDone;
    private CountDownTimer timer;

    // Storage estimate
    private long jpegBytes = 5_500_000, dngBytes;
    private Bitmap histBitmap;

    // QR scanning
    private static final long QR_INTERVAL_MS = 350, QR_HOLD_MS = 1200;
    private static final int QR_FRAME_W = 480, QR_FRAME_H = 640;
    private QrScanner qrScanner;
    private Bitmap qrBitmap;
    private Uri qrLink;
    private long qrLastSeen;
    private int qrSession;
    private Runnable hideToast;
    private int menuSelected = -1;

    // ================================================================== lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) setRecentsScreenshotEnabled(false);

        View root = findViewById(R.id.root);
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        prefs = getSharedPreferences("HeyCamSettings", MODE_PRIVATE);
        raw = prefs.getBoolean(PREF_RAW, false);
        try {
            processing = CameraController.Processing.valueOf(prefs.getString(PREF_PROCESSING, "MINIMAL"));
        } catch (IllegalArgumentException e) {
            processing = CameraController.Processing.MINIMAL;
        }
        grid = prefs.getBoolean(PREF_GRID, false);
        histogramOn = prefs.getBoolean(PREF_HISTOGRAM, true);
        qrOn = prefs.getBoolean(PREF_QR, true);
        soundOn = prefs.getBoolean(PREF_SOUND, true);
        glyphOn = prefs.getBoolean(PREF_GLYPH, true);

        qrScanner = new QrScanner();
        saver = new PhotoSaver(this);
        saver.setListener(this);
        camera = new CameraController(this, this, saver);
        camera.setProcessing(processing);

        if (BuildConfig.FLAVOR.equals("nothing") && Build.VERSION.SDK_INT >= 34) {
            glyph = new GlyphHelper();
            glyph.prepare(this);
        }
        sound = new SoundManager();
        sound.init(this);
        sound.setSoundEnabled(soundOn);

        bindViews();
        loadDemoImage(getIntent());
        setupSheet();
        applyFormatUi();
        applyFlashUi();
        overlay.setGrid(grid);
        histogram.setVisibility(histogramOn ? View.VISIBLE : View.GONE);
        setManualUi(false);
        setDialTarget(Dial.EV);
        loadLatestThumbnail();

        AssetInstaller.install(this, prefs, "sample_luts", "luts", ".cube", "are_default_luts_installed");
        AssetInstaller.install(this, prefs, "sample_frames", "frames", ".png", "are_default_frames_installed");

        orientationListener = new OrientationEventListener(this) {
            @Override public void onOrientationChanged(int o) {
                if (o == ORIENTATION_UNKNOWN) return;
                deviceOrientation = ((o + 45) / 90 * 90) % 360;
            }
        };

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                if (sheetScrim.getVisibility() == View.VISIBLE) showSheet(false);
                else if (menu.getVisibility() == View.VISIBLE) showMenu(false);
                else { setEnabled(false); getOnBackPressedDispatcher().onBackPressed(); setEnabled(true); }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (orientationListener.canDetectOrientation()) orientationListener.enable();
        registerReceiver(batteryReceiver, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        refreshLookUi();
        updateCount();
        if (texture.isAvailable()) openCamera();
        main.post(histogramTick);
        main.postDelayed(qrTick, QR_INTERVAL_MS);
    }

    @Override
    protected void onPause() {
        qrSession++;
        if (timerRunning) cancelTimer(false);
        main.removeCallbacks(histogramTick);
        main.removeCallbacks(qrTick);
        showQrChip(null);
        main.removeCallbacks(endMagnify);
        orientationListener.disable();
        unregisterReceiver(batteryReceiver);
        camera.close();
        if (glyph != null) glyph.clearDisplay();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (glyph != null) glyph.disconnect();
        if (sound != null) sound.release();
        if (qrScanner != null) qrScanner.shutdown();
        super.onDestroy();
    }

    private void openCamera() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            String[] perms = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q
                    ? new String[]{Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE}
                    : new String[]{Manifest.permission.CAMERA};
            ActivityCompat.requestPermissions(this, perms, REQUEST_PERMISSIONS);
            return;
        }
        SurfaceTexture st = texture.getSurfaceTexture();
        if (st != null) camera.open(st);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_PERMISSIONS) return;
        if (results.length > 0 && results[0] == PackageManager.PERMISSION_GRANTED) {
            if (texture.isAvailable()) openCamera();
        } else {
            showToast("Camera permission required");
        }
    }

    // ================================================================== views

    private void bindViews() {
        texture = findViewById(R.id.texture);
        overlay = findViewById(R.id.overlay);
        histogram = findViewById(R.id.histogram);
        frameOverlay = findViewById(R.id.frame_overlay);
        demoImage = findViewById(R.id.demo_image);
        curtain = findViewById(R.id.curtain);
        toast = findViewById(R.id.toast);
        toastText = findViewById(R.id.toast_text);
        countdown = findViewById(R.id.countdown);
        txtFormat = findViewById(R.id.txt_format);
        txtLut = findViewById(R.id.txt_lut);
        txtTimer = findViewById(R.id.txt_timer);
        txtFlash = findViewById(R.id.txt_flash);
        txtCount = findViewById(R.id.txt_count);
        battery = findViewById(R.id.battery);
        dial = findViewById(R.id.dial);
        thumb = findViewById(R.id.thumb);
        mode = findViewById(R.id.mode);
        menu = findViewById(R.id.menu);
        menuRows = findViewById(R.id.menu_rows);
        lensPicker = findViewById(R.id.lens_picker);
        qrChip = findViewById(R.id.qr_chip);
        qrChip.setOnClickListener(v -> openQrLink());
        sheetScrim = findViewById(R.id.sheet_scrim);

        cellS = new Cell(findViewById(R.id.cell_s), "S");
        cellIso = new Cell(findViewById(R.id.cell_iso), "ISO");
        cellEv = new Cell(findViewById(R.id.cell_ev), "EV");
        cellFocus = new Cell(findViewById(R.id.cell_focus), "AF");
        cellFocus.root.setOnClickListener(v -> onFocusCellTapped());
        cellS.root.setOnClickListener(v -> onCellTapped(Dial.S));
        cellIso.root.setOnClickListener(v -> onCellTapped(Dial.ISO));
        cellEv.root.setOnClickListener(v -> onCellTapped(Dial.EV));

        texture.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override public void onSurfaceTextureAvailable(@NonNull SurfaceTexture s, int w, int h) { openCamera(); }
            @Override public void onSurfaceTextureSizeChanged(@NonNull SurfaceTexture s, int w, int h) { }
            @Override public boolean onSurfaceTextureDestroyed(@NonNull SurfaceTexture s) { return true; }
            @Override public void onSurfaceTextureUpdated(@NonNull SurfaceTexture s) { }
        });

        // Tap focuses and locks there; long-press returns to continuous AF. No double-tap: it would
        // swallow a quick second tap near the first one and leave the focus box behind.
        GestureDetector gestures = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDown(@NonNull MotionEvent e) { return true; }
            @Override public boolean onSingleTapUp(@NonNull MotionEvent e) {
                focusAt(e.getX(), e.getY());
                return true;
            }
            @Override public void onLongPress(@NonNull MotionEvent e) {
                texture.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
                setFocusMode(Focus.AF);
                showToast("Continuous AF");
            }
        });
        gestures.setOnDoubleTapListener(null);
        texture.setOnTouchListener((v, e) -> gestures.onTouchEvent(e));

        dial.setOnChangeListener(this::onDialChanged);
        dial.setOnTouchStateListener(down -> {
            dialTouched = down;
            if (dialTarget != Dial.FOCUS) return;
            // Focus aid stays on for as long as the finger rests on the focus ring.
            main.removeCallbacks(endMagnify);
            if (down) setMagnified(true);
            else main.postDelayed(endMagnify, 900);
        });

        findViewById(R.id.shutter).setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            takePicture();
        });
        mode.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            setManual(!manual, manual ? Dial.EV : Dial.S);
        });
        thumb.setOnClickListener(v -> openLastPhoto());
        findViewById(R.id.btn_menu).setOnClickListener(v -> showMenu(true));
        findViewById(R.id.menu_close).setOnClickListener(v -> showMenu(false));
        txtFormat.setOnClickListener(v -> toggleFormat());
        txtLut.setOnClickListener(v -> startActivity(new Intent(this, LutManagerActivity.class)));
        txtFlash.setOnClickListener(v -> cycleFlash());
        txtTimer.setOnClickListener(v -> {
            if (timerRunning) cancelTimer(true);
            else showSheet(true);
        });
    }

    /** One S / ISO / EV / F readout cell. */
    private final class Cell {
        final View root;
        final TextView value, key;
        final View underline;

        Cell(FrameLayout root, String label) {
            this.root = root;
            LayoutInflater.from(MainActivity.this).inflate(R.layout.hc_readout_cell, root, true);
            value = root.findViewById(R.id.cell_value);
            key = root.findViewById(R.id.cell_key);
            underline = root.findViewById(R.id.cell_underline);
            key.setText(label);
        }

        void set(CharSequence text, int valueColor, boolean dim, boolean selected) {
            value.setText(text);
            value.setTextColor(valueColor);
            key.setTextColor(dim ? colorDim() : color(R.color.hc_grey));
            underline.setVisibility(selected ? View.VISIBLE : View.INVISIBLE);
        }
    }

    private int color(int res) {
        return ContextCompat.getColor(this, res);
    }

    private int colorDim() {
        return color(R.color.hc_grey_dim);
    }

    // ================================================================== camera callbacks

    @Override
    public void onCameraCaps(CameraCaps c) {
        caps = c;
        // Carry exposure over by value: a different lens may have a different ISO/shutter range.
        double s = shutterIdx >= 0 ? shutterScale.get(shutterIdx).value : 1 / 125.0;
        double iso = isoIdx >= 0 ? isoScale.get(isoIdx).value : 100;
        double ev = evIdx >= 0 ? evScale.get(evIdx).value : 0;
        shutterScale = ExposureScale.shutter(c.exposureRange);
        isoScale = ExposureScale.iso(c.isoRange);
        evScale = ExposureScale.ev(c.evRange, c.evStepStops());
        shutterIdx = shutterScale.nearest(s, false);
        isoIdx = isoScale.nearest(iso, false);
        evIdx = evScale.nearest(ev, true);
        buildLensPicker();
        overlay.setLensInfo(c.focal35mm, c.aperture);
        double fd = focusIdx >= 0 ? focusScale.get(focusIdx).value : 1.0;
        focusScale = ExposureScale.focus(c.minFocusDiopters);
        focusIdx = focusScale.nearest(fd, true);
        if (c.minFocusDiopters <= 0 && focus == Focus.MF) setFocusMode(Focus.AF);
        if (focus == Focus.MF) camera.setManualFocus((float) focusScale.get(focusIdx).value);
        ((TextView) findViewById(R.id.menu_camera)).setText(String.format(Locale.US, "Camera %s · %d mm · f/%.1f",
                c.id, c.focal35mm, c.aperture));
        if (!c.raw && raw) { raw = false; applyFormatUi(); }
        if (!c.flash) { flash = CameraController.Flash.OFF; txtFlash.setVisibility(View.GONE); }
        if (!c.manualSensor) mode.setVisibility(View.INVISIBLE);
        pushExposure();
        camera.setManual(manual);
        camera.setFlash(flash);
        setDialTarget(dialTarget);
        updateCount();
        buildMenu();
    }

    @Override
    public void onCameraError(String message) {
        showToast(message);
    }

    @Override
    public void onLiveStatus(long exposureNs, int iso, float focusDiopters) {
        if (demoBitmap != null && demoExposureNs > 0) {
            // Demo screenshots: show readings that match the still image, not the room the phone is in.
            exposureNs = demoExposureNs;
            iso = demoIso;
            focusDiopters = demoFocus;
        }
        liveExposureNs = exposureNs;
        liveIso = iso;
        liveFocus = focusDiopters;
        if (!manual || focus != Focus.MF) updateReadout();
    }

    @Override
    public void onFocusLocked(boolean success) {
        overlay.setFocusLocked(success);
    }

    @Override
    public void onShutter(boolean isRaw) {
        curtain.animate().cancel();
        curtain.setAlpha(1f);
        curtain.setVisibility(View.VISIBLE);
        curtain.animate().alpha(0f).setDuration(160).withEndAction(() -> curtain.setVisibility(View.GONE)).start();
        Bitmap b = demoBitmap != null ? demoBitmap : texture.getBitmap(120, 160);
        if (b != null) thumb.setImageBitmap(b);
    }

    @Override
    public void onCaptureFailed() {
        showToast("Capture failed");
    }

    @Override
    public void onSaved(Uri uri, boolean isRaw, long bytes, long tookMs) {
        if (isRaw) dngBytes = bytes; else jpegBytes = bytes;
        showToast(String.format(Locale.US, "%s saved · %.1f MB", isRaw ? "DNG" : "JPG", bytes / 1_000_000f));
        updateCount();
    }

    @Override
    public void onSaveFailed(boolean isRaw) {
        showToast(isRaw ? "DNG save failed" : "JPG save failed");
    }

    // ================================================================== lenses

    private void buildLensPicker() {
        java.util.List<com.ghostyapps.heycam.camera.LensInventory.Lens> lenses = camera.lenses();
        lensPicker.removeAllViews();
        if (lenses.size() < 2) {
            lensPicker.setVisibility(View.GONE);
            return;
        }
        com.ghostyapps.heycam.camera.LensInventory.Lens current = camera.lens();
        for (com.ghostyapps.heycam.camera.LensInventory.Lens l : lenses) {
            boolean on = l.sameAs(current);
            LinearLayout item = new LinearLayout(this);
            item.setOrientation(LinearLayout.VERTICAL);
            item.setGravity(Gravity.CENTER_HORIZONTAL);
            item.setPadding(dp(12), dp(8), dp(12), dp(6));
            TextView t = new TextView(this);
            t.setText(l.label());
            t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
            t.setTypeface(ResourcesCompat.getFont(this, R.font.barlow_medium));
            t.setLetterSpacing(0.04f);
            t.setTextColor(on ? color(R.color.hc_white) : 0xA6F2F2F0);
            t.setShadowLayer(dp(6), 0, 0, 0x99000000);
            t.setIncludeFontPadding(false);
            item.addView(t, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            View dot = new View(this);
            dot.setBackgroundResource(R.drawable.hc_dot_red);
            dot.setVisibility(on ? View.VISIBLE : View.INVISIBLE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(4), dp(4));
            lp.topMargin = dp(5);
            item.addView(dot, lp);
            item.setOnClickListener(v -> switchLens(l));
            lensPicker.addView(item);
        }
        lensPicker.setVisibility(View.VISIBLE);
    }

    private void switchLens(com.ghostyapps.heycam.camera.LensInventory.Lens l) {
        if (l.sameAs(camera.lens()) || timerRunning) return;
        lensPicker.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        curtain.animate().cancel();
        curtain.setAlpha(1f);
        curtain.setVisibility(View.VISIBLE);
        overlay.clearFocus();
        if (focus == Focus.AFL) focus = Focus.AF;
        liveExposureNs = 0;
        liveFocus = -1f;
        camera.setLens(l);
        curtain.animate().alpha(0f).setStartDelay(250).setDuration(200)
                .withEndAction(() -> { curtain.setVisibility(View.GONE); curtain.animate().setStartDelay(0); }).start();
    }

    // ================================================================== exposure

    private void onCellTapped(Dial target) {
        if (caps == null) return;
        if (target == Dial.EV) {
            if (manual) setManual(false, Dial.EV);
            else setDialTarget(Dial.EV);
        } else if (caps.manualSensor) {
            // Tapping S or ISO in auto switches to manual, starting from what auto exposure chose.
            if (!manual) setManual(true, target);
            else setDialTarget(target);
        }
    }

    private void setManual(boolean on, Dial target) {
        if (on && caps != null && !caps.manualSensor) return;
        if (on && !manual && liveExposureNs > 0) {
            shutterIdx = shutterScale.nearest(liveExposureNs / 1e9, false);
            isoIdx = isoScale.nearest(liveIso, false);
        }
        manual = on;
        pushExposure();
        camera.setManual(on);
        setManualUi(on);
        setDialTarget(target);
    }

    private void setManualUi(boolean on) {
        mode.setText(on ? "M" : "A");
        mode.setBackgroundResource(on ? R.drawable.hc_box_red : R.drawable.hc_box);
    }

    private void setDialTarget(Dial t) {
        dialTarget = t;
        if (t != Dial.FOCUS) {
            main.removeCallbacks(endMagnify);
            setMagnified(false);
        }
        switch (t) {
            case S: dial.setScale(shutterScale, Math.max(0, shutterIdx)); break;
            case ISO: dial.setScale(isoScale, Math.max(0, isoIdx)); break;
            case EV: dial.setScale(evScale, evIdx < 0 ? evScale.nearest(0, true) : evIdx); break;
            case FOCUS: dial.setScale(focusScale, Math.max(0, focusIdx)); break;
        }
        updateReadout();
    }

    private void onDialChanged(int i) {
        switch (dialTarget) {
            case S: shutterIdx = i; break;
            case ISO: isoIdx = i; break;
            case EV: evIdx = i; break;
            case FOCUS:
                focusIdx = i;
                camera.setManualFocus((float) focusScale.get(i).value);
                if (!dialTouched) {
                    // Flings and taps keep turning after the finger lifts; hold the aid a moment longer.
                    setMagnified(true);
                    main.removeCallbacks(endMagnify);
                    main.postDelayed(endMagnify, 1200);
                }
                updateReadout();
                return;
        }
        pushExposure();
        updateReadout();
    }

    private void pushExposure() {
        if (caps == null) return;
        camera.setExposureNs((long) (shutterScale.get(shutterIdx).value * 1e9));
        camera.setIso((int) isoScale.get(isoIdx).value);
        camera.setEvIndex(ExposureScale.evToDeviceIndex(evScale.get(evIdx).value, caps.evStepStops()));
    }

    private void updateReadout() {
        int white = color(R.color.hc_white), auto = color(R.color.hc_auto), dim = colorDim();
        if (manual && caps != null) {
            cellS.set(shutterScale.get(shutterIdx).label, white, false, dialTarget == Dial.S);
            cellIso.set(isoScale.get(isoIdx).label, white, false, dialTarget == Dial.ISO);
            cellEv.set("—", dim, true, false);
        } else {
            cellS.set(autoValue(liveExposureNs > 0 ? ExposureScale.formatShutter(liveExposureNs) : "—"), auto, false, false);
            cellIso.set(autoValue(liveIso > 0 ? String.valueOf(liveIso) : "—"), auto, false, false);
            cellEv.set(evIdx >= 0 ? evScale.get(evIdx).label : "0", white, false, dialTarget == Dial.EV);
        }
        updateFocusCell(white, auto, dim);
    }

    // ================================================================== focus

    private void focusAt(float x, float y) {
        if (caps != null && caps.minFocusDiopters <= 0) { showToast("Fixed-focus lens"); return; }
        overlay.showFocus(x, y);
        camera.focusAt(x / texture.getWidth(), y / texture.getHeight());
        focus = Focus.AFL;
        leaveFocusDial();
        updateReadout();
    }

    private void onFocusCellTapped() {
        if (caps == null) return;
        if (caps.minFocusDiopters <= 0) { showToast("Fixed-focus lens"); return; }
        if (focus == Focus.MF && dialTarget == Dial.FOCUS) setFocusMode(Focus.AF);
        else if (focus == Focus.MF) setDialTarget(Dial.FOCUS);
        else setFocusMode(Focus.MF);
    }

    private void setFocusMode(Focus mode) {
        if (mode == Focus.MF) {
            // Start manual focus where the autofocus currently is.
            if (liveFocus >= 0) focusIdx = focusScale.nearest(liveFocus, true);
            if (focusIdx < 0) focusIdx = focusScale.size() - 1;
            overlay.clearFocus();
            camera.setManualFocus((float) focusScale.get(focusIdx).value);
            focus = Focus.MF;
            setDialTarget(Dial.FOCUS);
        } else {
            focus = mode;
            overlay.clearFocus();
            camera.setManualFocus(null);
            camera.resetFocus();
            leaveFocusDial();
        }
        updateReadout();
    }

    private void leaveFocusDial() {
        if (dialTarget == Dial.FOCUS) setDialTarget(manual ? Dial.S : Dial.EV);
    }

    private void updateFocusCell(int white, int auto, int dim) {
        cellFocus.key.setText(focus == Focus.MF ? "MF" : focus == Focus.AFL ? "AF-L" : "AF");
        if (caps != null && caps.minFocusDiopters <= 0) {
            cellFocus.set("∞", dim, true, false);
        } else if (focus == Focus.MF) {
            cellFocus.set(metres(focusScale.get(Math.max(0, focusIdx)).label), white, false, dialTarget == Dial.FOCUS);
        } else {
            String v = liveFocus >= 0 ? ExposureScale.formatFocus(liveFocus) : "—";
            cellFocus.set(autoValue(metres(v)), auto, false, false);
        }
    }

    /** Appends a small "m" to a distance; infinity and placeholders stay bare. */
    private CharSequence metres(CharSequence v) {
        String s = v.toString();
        if (s.equals("∞") || s.equals("—")) return v;
        SpannableStringBuilder sb = new SpannableStringBuilder(v).append("m");
        sb.setSpan(new RelativeSizeSpan(0.55f), sb.length() - 1, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private static final float FOCUS_AID_SCALE = 2.5f;

    /** Focus aid: enlarges the centre of the preview while the focus dial turns. */
    private void setMagnified(boolean on) {
        float s = on ? FOCUS_AID_SCALE : 1f;
        android.graphics.Matrix m = new android.graphics.Matrix();
        m.setScale(s, s, texture.getWidth() / 2f, texture.getHeight() / 2f);
        texture.setTransform(m);
        demoImage.setScaleX(s);
        demoImage.setScaleY(s);
        overlay.setMagnified(on);
    }

    /** Value with a small red A in front, marking what auto exposure picked. */
    private CharSequence autoValue(CharSequence v) {
        SpannableStringBuilder sb = new SpannableStringBuilder("A ").append(v);
        sb.setSpan(new ForegroundColorSpan(color(R.color.hc_red)), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new RelativeSizeSpan(0.52f), 0, 2, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new SuperscriptSpan(), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    // ================================================================== capture

    private void takePicture() {
        if (caps == null) return;
        int orientation = (caps.sensorOrientation + deviceOrientation) % 360;
        camera.capture(new CaptureMeta(raw && camera.isRawAvailable(), orientation,
                prefs.getString(PREF_LUT, null), prefs.getString(PREF_FRAME, null)));
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        // Volume keys, the camera key and Nothing's Essential key (scan code 1250) act as shutter.
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
                || keyCode == KeyEvent.KEYCODE_CAMERA || event.getScanCode() == 1250) {
            if (event.getRepeatCount() == 0) takePicture();
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    private void openLastPhoto() {
        Uri uri = saver.lastUri();
        if (uri == null) uri = queryLatest();
        if (uri == null) { showToast("No photos yet"); return; }
        Intent view = new Intent(Intent.ACTION_VIEW);
        view.setDataAndType(uri, getContentResolver().getType(uri));
        view.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(view);
        } catch (android.content.ActivityNotFoundException e) {
            showToast("No gallery app found");
        }
    }

    private Uri queryLatest() {
        String[] proj = {MediaStore.Images.Media._ID};
        try (Cursor c = getContentResolver().query(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, proj,
                // Photos taken before the rename are still named HeyCam_*.
                MediaStore.Images.Media.DISPLAY_NAME + " LIKE ? OR " + MediaStore.Images.Media.DISPLAY_NAME + " LIKE ?",
                new String[]{"Koshi_%", "HeyCam_%"},
                MediaStore.Images.Media.DATE_TAKEN + " DESC")) {
            if (c != null && c.moveToFirst()) {
                return ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, c.getLong(0));
            }
        } catch (Exception ignored) { }
        return null;
    }

    private void loadLatestThumbnail() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return;
        new Thread(() -> {
            Uri uri = queryLatest();
            if (uri == null) return;
            try {
                Bitmap b = getContentResolver().loadThumbnail(uri, new Size(160, 160), null);
                main.post(() -> { if (thumb.getDrawable() == null) thumb.setImageBitmap(b); });
            } catch (Exception ignored) { }
        }, "HeyCam-Thumb").start();
    }

    // ================================================================== strip

    private void toggleFormat() {
        if (caps != null && !camera.isRawAvailable()) { showToast("RAW not supported"); return; }
        raw = !raw;
        prefs.edit().putBoolean(PREF_RAW, raw).apply();
        applyFormatUi();
        String lut = prefs.getString(PREF_LUT, null), frame = prefs.getString(PREF_FRAME, null);
        if (raw && ((lut != null && !lut.isEmpty()) || (frame != null && !frame.isEmpty()))) {
            showToast("DNG · LUT and frame off");
        }
    }

    private void applyFormatUi() {
        txtFormat.setText(raw ? "DNG" : "JPG");
        txtFormat.setBackgroundResource(raw ? R.drawable.hc_box_red : R.drawable.hc_box);
        refreshLookUi();
        updateCount();
    }

    private void cycleFlash() {
        flash = CameraController.Flash.values()[(flash.ordinal() + 1) % 3];
        camera.setFlash(flash);
        applyFlashUi();
    }

    private void applyFlashUi() {
        txtFlash.setText(flash == CameraController.Flash.ON ? "ON" : flash == CameraController.Flash.TORCH ? "TORCH" : "");
        tintStrip(txtFlash, flash != CameraController.Flash.OFF);
    }

    private void tintStrip(TextView v, boolean on) {
        int c = on ? color(R.color.hc_white) : colorDim();
        v.setTextColor(c);
        for (Drawable d : v.getCompoundDrawablesRelative()) {
            if (d != null) d.mutate().setTint(c);
        }
    }

    private void refreshLookUi() {
        String lut = prefs.getString(PREF_LUT, null);
        boolean lutOn = lut != null && !lut.isEmpty() && !raw;
        if (lutOn) saver.preloadLut(lut);
        txtLut.setText(lutOn ? stripExt(lut).toUpperCase(Locale.US) : "LUT");
        tintStrip(txtLut, lutOn);

        String frame = prefs.getString(PREF_FRAME, null);
        File f = frame == null || frame.isEmpty() ? null : new File(getFilesDir(), "frames/" + frame);
        if (f != null && f.exists() && !raw) {
            frameOverlay.setImageBitmap(BitmapFactory.decodeFile(f.getAbsolutePath()));
            frameOverlay.setVisibility(View.VISIBLE);
        } else {
            frameOverlay.setImageDrawable(null);
            frameOverlay.setVisibility(View.GONE);
            // The selected frame file is gone (e.g. a retired sample): forget the selection.
            if (f != null && !f.exists()) prefs.edit().remove(PREF_FRAME).apply();
        }
        tintStrip(txtTimer, timerRunning);
        if (menu.getVisibility() == View.VISIBLE) buildMenu();
    }

    private static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private void updateCount() {
        long per = raw ? dngBytes : jpegBytes;
        if (raw && per == 0) {
            per = caps != null && caps.rawSize != null ? (long) caps.rawSize.getWidth() * caps.rawSize.getHeight() * 2 : 25_000_000;
        }
        try {
            long free = new StatFs(Environment.getExternalStorageDirectory().getPath()).getAvailableBytes();
            txtCount.setText(String.format(Locale.US, "%,d", free / Math.max(1, per)).replace(',', ' '));
        } catch (Exception e) {
            txtCount.setText("");
        }
    }

    private final BroadcastReceiver batteryReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            int level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            if (level >= 0) battery.setLevel(level / (float) scale);
        }
    };

    // ================================================================== viewfinder extras

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        loadDemoImage(intent);
    }

    /**
     * Debug builds only: {@code adb shell am start ... --es demo photo.jpg} shows
     * files/demo/photo.jpg (copied in with {@code run-as}) in the viewfinder instead of the live feed,
     * so screenshots can show a real scene. The rest of the UI stays live.
     */
    private void loadDemoImage(Intent intent) {
        if (!BuildConfig.DEBUG || intent == null) return;
        String name = intent.getStringExtra("demo");
        if (name == null) return;
        File f = new File(getFilesDir(), "demo/" + name);
        demoBitmap = f.exists() ? BitmapFactory.decodeFile(f.getAbsolutePath()) : null;
        demoImage.setImageBitmap(demoBitmap);
        demoImage.setVisibility(demoBitmap != null ? View.VISIBLE : View.GONE);
        if (demoBitmap != null) thumb.setImageBitmap(demoBitmap);
        // Optional matching readings: --es demo_s 1/250 --ei demo_iso 50 --ef demo_focus 0
        String sh = intent.getStringExtra("demo_s");
        demoExposureNs = sh == null ? 0 : (long) (1e9 / Double.parseDouble(sh.replace("1/", "")));
        demoIso = intent.getIntExtra("demo_iso", 100);
        demoFocus = intent.getFloatExtra("demo_focus", 0f);
    }

    private final Runnable histogramTick = new Runnable() {
        @Override public void run() {
            if (histogramOn && demoBitmap != null) {
                histogram.update(Bitmap.createScaledBitmap(demoBitmap, 64, 48, true));
            } else if (histogramOn && texture.isAvailable()) {
                if (histBitmap == null) histBitmap = Bitmap.createBitmap(64, 48, Bitmap.Config.ARGB_8888);
                Bitmap b = texture.getBitmap(histBitmap);
                if (b != null) histogram.update(b);
            }
            main.postDelayed(this, 250);
        }
    };

    // ================================================================== QR

    private final Runnable qrTick = new Runnable() {
        @Override public void run() {
            boolean idle = demoBitmap == null && !timerRunning && texture.isAvailable()
                    && menu.getVisibility() != View.VISIBLE && sheetScrim.getVisibility() != View.VISIBLE
                    && curtain.getVisibility() != View.VISIBLE;
            if (qrOn && idle) {
                if (!qrScanner.isBusy()) {
                    if (qrBitmap == null) qrBitmap = Bitmap.createBitmap(QR_FRAME_W, QR_FRAME_H, Bitmap.Config.ARGB_8888);
                    Bitmap b = texture.getBitmap(qrBitmap);
                    final int session = qrSession;
                    if (b != null) qrScanner.scan(b, link -> {
                        if (session == qrSession && qrOn && link != null) {
                            qrLastSeen = SystemClock.uptimeMillis();
                            if (!link.equals(qrLink)) showQrChip(link);
                        }
                    });
                }
                // Hide a moment after the code leaves the frame so the button doesn't flicker.
                if (qrLink != null && SystemClock.uptimeMillis() - qrLastSeen > QR_HOLD_MS) showQrChip(null);
            } else if (qrLink != null) {
                showQrChip(null);
            }
            main.postDelayed(this, QR_INTERVAL_MS);
        }
    };

    private void showQrChip(Uri link) {
        qrLink = link;
        if (link == null) {
            qrChip.setVisibility(View.GONE);
            return;
        }
        String label;
        if ("fido".equals(link.getScheme())) label = "Passkey sign-in";
        else if ("http".equals(link.getScheme()) || "https".equals(link.getScheme())) label = link.getHost();
        else label = link.getSchemeSpecificPart();
        qrChip.setText(label != null ? label : link.toString());
        if (qrChip.getVisibility() != View.VISIBLE) {
            qrChip.setAlpha(0f);
            qrChip.setVisibility(View.VISIBLE);
            qrChip.animate().alpha(1f).setDuration(140).start();
            qrChip.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
        }
    }

    private void openQrLink() {
        if (qrLink == null) return;
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, qrLink).addCategory(Intent.CATEGORY_BROWSABLE));
        } catch (ActivityNotFoundException e) {
            showToast("No app can open this link");
        }
    }

    private void showToast(String message) {
        toastText.setText(message);
        toast.animate().cancel();
        if (toast.getVisibility() != View.VISIBLE) {
            toast.setAlpha(0f);
            toast.setVisibility(View.VISIBLE);
        }
        toast.animate().alpha(1f).setDuration(140).start();
        if (hideToast != null) main.removeCallbacks(hideToast);
        hideToast = () -> toast.animate().alpha(0f).setDuration(220)
                .withEndAction(() -> toast.setVisibility(View.GONE)).start();
        main.postDelayed(hideToast, 1800);
    }

    // ================================================================== menu

    private void showMenu(boolean show) {
        if (show) {
            menuSelected = -1;
            buildMenu();
        }
        menu.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void buildMenu() {
        menuRows.removeAllViews();
        int[] idx = {0};
        group("Capture");
        row(idx, "File format", raw ? "DNG" : "JPG", true, null, this::toggleFormat);
        row(idx, "JPEG processing", processingLabel(), processing != CameraController.Processing.DEVICE,
                processingCaption(), () -> {
                    processing = CameraController.Processing.values()[(processing.ordinal() + 1) % 3];
                    prefs.edit().putString(PREF_PROCESSING, processing.name()).apply();
                    camera.setProcessing(processing);
                });
        if (caps == null || caps.flash) {
            row(idx, "Flash", flash == CameraController.Flash.ON ? "On" : flash == CameraController.Flash.TORCH ? "Torch" : "Off",
                    flash != CameraController.Flash.OFF, null, this::cycleFlash);
        }
        row(idx, "Interval timer", prefs.getInt(PREF_INTERVAL, 3) + " s × " + prefs.getInt(PREF_SHOTS, 1), true, null, () -> {
            showMenu(false);
            showSheet(true);
        });
        group("Look");
        String lut = prefs.getString(PREF_LUT, null);
        boolean hasLut = lut != null && !lut.isEmpty();
        row(idx, "LUT", hasLut ? stripExt(lut) : "None", hasLut, raw && hasLut ? "Not applied to DNG." : null,
                () -> startActivity(new Intent(this, LutManagerActivity.class)));
        String frame = prefs.getString(PREF_FRAME, null);
        boolean hasFrame = frame != null && !frame.isEmpty();
        row(idx, "Frame", hasFrame ? stripExt(frame) : "None", hasFrame, raw && hasFrame ? "Not applied to DNG." : null,
                () -> startActivity(new Intent(this, FrameManagerActivity.class)));
        row(idx, "Grid", grid ? "On" : "Off", grid, null, () -> {
            grid = !grid;
            prefs.edit().putBoolean(PREF_GRID, grid).apply();
            overlay.setGrid(grid);
        });
        row(idx, "Histogram", histogramOn ? "On" : "Off", histogramOn, null, () -> {
            histogramOn = !histogramOn;
            prefs.edit().putBoolean(PREF_HISTOGRAM, histogramOn).apply();
            histogram.setVisibility(histogramOn ? View.VISIBLE : View.GONE);
        });
        row(idx, "QR scan", qrOn ? "On" : "Off", qrOn, null, () -> {
            qrOn = !qrOn;
            prefs.edit().putBoolean(PREF_QR, qrOn).apply();
            if (!qrOn) showQrChip(null);
        });
        group("System");
        row(idx, "Timer sound", soundOn ? "On" : "Off", soundOn, null, this::toggleSound);
        if (glyph != null) row(idx, "Glyph matrix", glyphOn ? "On" : "Off", glyphOn, null, this::toggleGlyph);

        String version = "";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        ((TextView) findViewById(R.id.menu_version)).setText("Koshi " + version);
    }

    private String processingLabel() {
        switch (processing) {
            case OFF: return "Off";
            case DEVICE: return "Device";
            default: return "Minimal";
        }
    }

    private String processingCaption() {
        switch (processing) {
            case OFF: return "No noise reduction, sharpening or lens correction. Neutral tone curve.";
            case DEVICE: return "The phone's own processing on every JPEG.";
            default: return "No sharpening, minimal noise reduction.";
        }
    }

    private void group(String title) {
        TextView t = new TextView(this);
        t.setTextAppearance(R.style.HC_Caps);
        t.setTextColor(colorDim());
        t.setText(title);
        t.setPadding(dp(24), dp(20), dp(24), dp(6));
        menuRows.addView(t);
    }

    private void row(int[] counter, String key, String value, boolean on, String caption, Runnable action) {
        final int index = counter[0]++;
        FrameLayout row = new FrameLayout(this);
        row.setBackgroundResource(R.drawable.hc_row);
        row.setPadding(dp(24), dp(15), dp(24), dp(14));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        TextView k = new TextView(this);
        k.setText(key);
        k.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        k.setTypeface(ResourcesCompat.getFont(this, index == menuSelected ? R.font.barlow_regular : R.font.barlow_light));
        k.setTextColor(color(R.color.hc_white));
        k.setIncludeFontPadding(false);
        left.addView(k);
        if (index == menuSelected) {
            View line = new View(this);
            line.setBackgroundColor(color(R.color.hc_red));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(120), Math.max(2, dp(1.5f)));
            lp.topMargin = dp(7);
            left.addView(line, lp);
        }
        if (caption != null) {
            TextView cap = new TextView(this);
            cap.setText(caption);
            cap.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
            cap.setTextColor(color(R.color.hc_grey));
            cap.setPadding(0, dp(6), dp(90), 0);
            left.addView(cap);
        }
        row.addView(left);

        TextView v = new TextView(this);
        v.setText(value.toUpperCase(Locale.US));
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        v.setTypeface(ResourcesCompat.getFont(this, R.font.barlow_medium));
        v.setLetterSpacing(0.08f);
        v.setTextColor(on ? color(R.color.hc_white) : color(R.color.hc_grey));
        FrameLayout.LayoutParams vp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.TOP);
        vp.topMargin = dp(3);
        row.addView(v, vp);

        row.setOnClickListener(x -> {
            x.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
            menuSelected = index;
            action.run();
            if (menu.getVisibility() == View.VISIBLE) buildMenu();
        });
        if (index > 0) {
            View div = new View(this);
            div.setBackgroundColor(color(R.color.hc_line_faint));
            menuRows.addView(div, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        }
        menuRows.addView(row);
    }

    private void toggleSound() {
        soundOn = !soundOn;
        prefs.edit().putBoolean(PREF_SOUND, soundOn).apply();
        sound.setSoundEnabled(soundOn);
        updateSheetOptions();
    }

    private void toggleGlyph() {
        glyphOn = !glyphOn;
        prefs.edit().putBoolean(PREF_GLYPH, glyphOn).apply();
        updateSheetOptions();
    }

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    // ================================================================== interval timer

    private void setupSheet() {
        pickSeconds = findViewById(R.id.pick_seconds);
        pickShots = findViewById(R.id.pick_shots);
        optSound = findViewById(R.id.opt_sound);
        optGlyph = findViewById(R.id.opt_glyph);
        optTotal = findViewById(R.id.opt_total);
        pickSeconds.setOnChange(this::updateSheetOptions);
        pickShots.setOnChange(this::updateSheetOptions);
        optSound.setOnClickListener(v -> toggleSound());
        optGlyph.setOnClickListener(v -> toggleGlyph());
        optGlyph.setVisibility(glyph != null ? View.VISIBLE : View.INVISIBLE);
        sheetScrim.setOnClickListener(v -> showSheet(false));
        findViewById(R.id.sheet_close).setOnClickListener(v -> showSheet(false));
        findViewById(R.id.btn_start).setOnClickListener(v -> {
            prefs.edit().putInt(PREF_INTERVAL, pickSeconds.getValue()).putInt(PREF_SHOTS, pickShots.getValue()).apply();
            showSheet(false);
            startTimer();
        });
        showSheet(false);
    }

    private void updateSheetOptions() {
        optSound.setText(label("Sound", soundOn ? "On" : "Off"));
        optGlyph.setText(label("Glyph", glyphOn ? "On" : "Off"));
        int total = pickSeconds.getValue() * pickShots.getValue();
        optTotal.setText(label("Total", total >= 60 ? (total / 60) + " m " + (total % 60) + " s" : total + " s"));
    }

    private CharSequence label(String k, String v) {
        String head = k.toUpperCase(Locale.US);
        SpannableStringBuilder sb = new SpannableStringBuilder(head + "   " + v.toUpperCase(Locale.US));
        sb.setSpan(new ForegroundColorSpan(color(R.color.hc_grey)), 0, head.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new RelativeSizeSpan(0.85f), 0, head.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(color(R.color.hc_white)), head.length(), sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private void showSheet(boolean show) {
        if (show) {
            pickSeconds.setRange(1, 30, prefs.getInt(PREF_INTERVAL, 3));
            pickShots.setRange(1, 99, prefs.getInt(PREF_SHOTS, 1));
        }
        updateSheetOptions();
        sheetScrim.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void startTimer() {
        timerRunning = true;
        timerShotsDone = 0;
        if (glyph != null && glyphOn) glyph.connect();
        tintStrip(txtTimer, true);
        nextCountdown();
    }

    private void nextCountdown() {
        if (!timerRunning) return;
        int interval = prefs.getInt(PREF_INTERVAL, 3), shots = prefs.getInt(PREF_SHOTS, 1);
        if (timerShotsDone >= shots) { finishTimer(); return; }
        txtTimer.setText((timerShotsDone + 1) + "/" + shots);
        countdown.setVisibility(View.VISIBLE);
        overlay.setCrossVisible(false);
        timer = new CountDownTimer(interval * 1000L, 1000) {
            @Override public void onTick(long left) {
                int s = Math.max(1, (int) Math.ceil(left / 1000.0));
                countdown.tick(s, timerShotsDone + 1, shots);
                if (sound != null) sound.playTick();
                if (glyph != null && glyphOn) glyph.displayCounter(s, deviceOrientation);
            }

            @Override public void onFinish() {
                if (glyph != null && glyphOn) glyph.clearDisplay();
                if (sound != null) sound.playShutter();
                takePicture();
                timerShotsDone++;
                nextCountdown();
            }
        }.start();
    }

    private void finishTimer() {
        timerRunning = false;
        countdown.stop();
        countdown.setVisibility(View.GONE);
        overlay.setCrossVisible(true);
        txtTimer.setText("");
        tintStrip(txtTimer, false);
        if (sound != null) sound.playFinish();
        if (glyph != null && glyphOn) {
            glyph.displayFinishPattern(deviceOrientation);
            main.postDelayed(() -> {
                if (timerRunning) return;
                glyph.clearDisplay();
                main.postDelayed(() -> { if (!timerRunning) glyph.disconnect(); }, 200);
            }, 2000);
        }
        showToast("Sequence complete");
    }

    private void cancelTimer(boolean announce) {
        timerRunning = false;
        if (timer != null) timer.cancel();
        countdown.stop();
        countdown.setVisibility(View.GONE);
        overlay.setCrossVisible(true);
        txtTimer.setText("");
        tintStrip(txtTimer, false);
        if (glyph != null) glyph.disconnect();
        if (announce) showToast("Timer cancelled");
    }
}
