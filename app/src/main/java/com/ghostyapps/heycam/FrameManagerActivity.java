package com.ghostyapps.heycam;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FrameManagerActivity extends AppCompatActivity {

    private RecyclerView recyclerView;
    private FrameAdapter adapter;
    private List<File> frameFiles = new ArrayList<>();
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Tam Ekran Ayarları
        getWindow().setNavigationBarColor(android.graphics.Color.BLACK);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        );
        setContentView(R.layout.activity_frame_manager);

        prefs = getSharedPreferences("HeyCamSettings", MODE_PRIVATE);
        recyclerView = findViewById(R.id.recycler_frames);

        // Geri Butonu
        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        setupRecycler();
        loadFrames();
    }

    private void setupRecycler() {
        recyclerView.setLayoutManager(new GridLayoutManager(this, 2));
        String savedFrame = prefs.getString("current_frame_name", "");

        adapter = new FrameAdapter(frameFiles, savedFrame, file -> {
            if (file == null) {
                // No Frame
                prefs.edit().remove("current_frame_name").apply();
            } else {
                // Seçilen Frame
                prefs.edit().putString("current_frame_name", file.getName()).apply();
            }
        });
        recyclerView.setAdapter(adapter);
    }

    private void loadFrames() {
        frameFiles.clear();
        File dir = new File(getFilesDir(), "frames");

        // Eğer klasör yoksa (MainActivity henüz kopyalamadıysa) oluştur
        if (!dir.exists()) dir.mkdirs();

        File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".png"));
        if (files != null) frameFiles.addAll(Arrays.asList(files));

        if (adapter != null) adapter.notifyDataSetChanged();
    }
}