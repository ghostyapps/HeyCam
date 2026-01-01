package com.ghostyapps.heycam;

import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import androidx.annotation.Nullable;

// Bu servis sadece "Ben bir Glyph Toy'um" demek için var.
// Işık kontrolünü yine Activity üzerinden yapacağız.
public class HeyCamToyService extends Service {
    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}