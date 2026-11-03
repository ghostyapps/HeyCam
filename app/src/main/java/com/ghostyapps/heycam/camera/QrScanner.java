package com.ghostyapps.heycam.camera;

import android.graphics.Bitmap;
import android.net.Uri;

import androidx.annotation.Nullable;

import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.LuminanceSource;
import com.google.zxing.RGBLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

import java.util.Collections;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Looks for a QR code in viewfinder frames, off the UI thread. One frame at a time: callers feed
 * frames from a tick and a frame is dropped while the previous one is still being decoded.
 */
public final class QrScanner {

    public interface Callback {
        /** Called on the main thread; {@code link} is null when the frame holds no openable QR code. */
        void onResult(@Nullable Uri link);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> new Thread(r, "HeyCam-Qr"));
    private final AtomicBoolean busy = new AtomicBoolean();
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private final QRCodeReader reader = new QRCodeReader();
    private final Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);

    public QrScanner() {
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Collections.singletonList(BarcodeFormat.QR_CODE));
    }

    public boolean isBusy() {
        return busy.get();
    }

    /** Decodes {@code frame}; it must not be touched again until the callback fires. */
    public void scan(Bitmap frame, Callback cb) {
        if (!busy.compareAndSet(false, true)) return;
        final int w = frame.getWidth(), h = frame.getHeight();
        final int[] pixels = new int[w * h];
        frame.getPixels(pixels, 0, w, 0, 0, w, h);
        executor.execute(() -> {
            Uri link = null;
            try {
                link = parse(decode(new RGBLuminanceSource(w, h, pixels)));
            } catch (Throwable ignored) {
                // No code in this frame.
            }
            final Uri result = link;
            busy.set(false);
            main.post(() -> cb.onResult(result));
        });
    }

    public void shutdown() {
        executor.shutdownNow();
    }

    private String decode(LuminanceSource src) throws Exception {
        try {
            Result r = reader.decode(new BinaryBitmap(new HybridBinarizer(src)), hints);
            return r.getText();
        } finally {
            reader.reset();
        }
    }

    /** Only http(s), mailto, tel and fido (passkey sign-in) are openable; a bare "www." or "example.com/x" is read as https. */
    @Nullable
    static Uri parse(@Nullable String text) {
        if (text == null) return null;
        String t = text.trim();
        if (t.isEmpty() || t.length() > 2048 || t.contains("\n") || t.contains(" ")) return null;
        String lower = t.toLowerCase(Locale.ROOT);
        if (lower.startsWith("http://") || lower.startsWith("https://")
                || lower.startsWith("mailto:") || lower.startsWith("tel:") || lower.startsWith("fido:")) {
            // Lower-case the scheme: "FIDO:/..." must match the handler's intent filter.
            Uri u = Uri.parse(t).normalizeScheme();
            return u.getScheme() != null ? u : null;
        }
        if (lower.startsWith("www.")) return Uri.parse("https://" + t);
        return null;
    }
}
