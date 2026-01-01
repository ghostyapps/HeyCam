package com.ghostyapps.heycam;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.SoundPool;

public class SoundManager {
    private SoundPool soundPool;
    private int soundTickId;
    private int soundPhotoId;
    private int soundFinishId;
    private boolean isLoaded = false;
    private boolean isSoundEnabled = true; // Varsayılan açık/kapalı durumu

    public void init(Context context) {
        // Ses Ayarları (Oyun/Medya sesi olarak ayarla)
        AudioAttributes audioAttributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();

        soundPool = new SoundPool.Builder()
                .setMaxStreams(3) // Aynı anda çalabilecek ses sayısı
                .setAudioAttributes(audioAttributes)
                .build();

        // Sesleri Yükle (Asenkron)
        try {
            soundTickId = soundPool.load(context, R.raw.sound_timer_tick, 1);
            soundPhotoId = soundPool.load(context, R.raw.sound_take_photo, 1);
            soundFinishId = soundPool.load(context, R.raw.sound_sequence_complete, 1);
        } catch (Exception e) {
            e.printStackTrace();
        }

        soundPool.setOnLoadCompleteListener((soundPool, sampleId, status) -> {
            isLoaded = true;
        });
    }

    public void setSoundEnabled(boolean enabled) {
        this.isSoundEnabled = enabled;
    }

    public void playTick() {
        if (isLoaded && isSoundEnabled) {
            soundPool.play(soundTickId, 1f, 1f, 1, 0, 1f);
        }
    }

    public void playShutter() {
        if (isLoaded && isSoundEnabled) {
            soundPool.play(soundPhotoId, 1f, 1f, 1, 0, 1f);
        }
    }

    public void playFinish() {
        if (isLoaded && isSoundEnabled) {
            soundPool.play(soundFinishId, 1f, 1f, 1, 0, 1f);
        }
    }

    public void release() {
        if (soundPool != null) {
            soundPool.release();
            soundPool = null;
        }
    }
}