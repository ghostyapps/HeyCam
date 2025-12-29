package com.ghostyapps.heycam;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public class LutManagerActivity extends AppCompatActivity {

    private static final int PICK_LUT_FILE = 101;
    private RecyclerView recyclerView;
    private LutAdapter adapter;
    private List<File> lutFiles = new ArrayList<>();
    private TextView emptyState;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // --- 1. TAM EKRAN VE GİZLİ BARLAR ---
        getWindow().setNavigationBarColor(android.graphics.Color.BLACK);

        // Sistem UI gizleme bayrakları (MainActivity ile aynı)
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_FULLSCREEN);

        setContentView(R.layout.activity_lut_manager);

        prefs = getSharedPreferences("HeyCamSettings", MODE_PRIVATE);

        recyclerView = findViewById(R.id.recycler_luts);
        emptyState = findViewById(R.id.txt_empty_state);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        findViewById(R.id.btn_import_lut).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("*/*"); // Tüm dosyaları aç, uzantıyı kodda kontrol ederiz
            startActivityForResult(intent, PICK_LUT_FILE);
        });

        // RESTORE BUTONU
        findViewById(R.id.btn_restore_defaults).setOnClickListener(v -> {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Restore Defaults?")
                    .setMessage("This will reinstall the original LUTs. Your custom imported LUTs will not be affected.")
                    .setPositiveButton("Restore", (dialog, which) -> restoreDefaultLuts())
                    .setNegativeButton("Cancel", null)
                    .show();
        });

        setupRecycler();
        loadLuts();
    }

    // --- YENİ METOD: RESTORE LOGIC ---
    private void restoreDefaultLuts() {
        new Thread(() -> {
            try {
                // Assets klasöründeki sample_luts'a eriş
                String[] files = getAssets().list("sample_luts");
                if (files == null) return;

                File destDir = new File(getFilesDir(), "luts");
                if (!destDir.exists()) destDir.mkdirs();

                int restoredCount = 0;

                for (String filename : files) {
                    if (!filename.toLowerCase().endsWith(".cube")) continue;

                    File outFile = new File(destDir, filename);

                    // Dosya yoksa kopyala (Varsa dokunma, belki kullanıcı değiştirmiştir)
                    if (!outFile.exists()) {
                        try (java.io.InputStream in = getAssets().open("sample_luts/" + filename);
                             java.io.FileOutputStream out = new java.io.FileOutputStream(outFile)) {

                            byte[] buffer = new byte[1024];
                            int read;
                            while ((read = in.read(buffer)) != -1) {
                                out.write(buffer, 0, read);
                            }
                            restoredCount++;
                        }
                    }
                }

                // UI İşlemleri (Toast ve Liste Yenileme)
                int finalRestoredCount = restoredCount;
                runOnUiThread(() -> {
                    if (finalRestoredCount > 0) {
                        Toast.makeText(this, finalRestoredCount + " LUTs restored.", Toast.LENGTH_SHORT).show();
                        loadLuts(); // Listeyi anında güncelle
                    } else {
                        Toast.makeText(this, "All default LUTs are already present.", Toast.LENGTH_SHORT).show();
                    }
                });

            } catch (Exception e) {
                e.printStackTrace();
            }
        }).start();
    }

    private void setupRecycler() {
        recyclerView.setLayoutManager(new LinearLayoutManager(this));
        String savedLut = prefs.getString("current_lut_name", "");

        adapter = new LutAdapter(lutFiles, savedLut, new LutAdapter.OnLutActionListener() {
            @Override
            public void onLutSelected(File file) {
                if (file == null) {
                    // "No LUT" SEÇİLDİ -> AYARI SİL
                    prefs.edit().remove("current_lut_name").apply();
                    Toast.makeText(LutManagerActivity.this, "LUT Disabled", Toast.LENGTH_SHORT).show();
                } else {
                    // DOSYA SEÇİLDİ -> AYARI KAYDET
                    prefs.edit().putString("current_lut_name", file.getName()).apply();
                    Toast.makeText(LutManagerActivity.this, "Active: " + file.getName(), Toast.LENGTH_SHORT).show();
                }
            }

            @Override
            public void onLutDeleted(File file) {
                if (file.delete()) {
                    String current = prefs.getString("current_lut_name", "");
                    if (current.equals(file.getName())) {
                        prefs.edit().remove("current_lut_name").apply();
                    }
                    loadLuts();
                }
            }

            // --- YENİ: RENAME İSTEĞİ GELDİ ---
            @Override
            public void onLutRename(File file) {
                showRenameDialog(file);
            }
        });
        recyclerView.setAdapter(adapter);
    }




    // --- YENİ METOD: İSİM DEĞİŞTİRME PENCERESİ ---
    private void showRenameDialog(File file) {
        MaterialAlertDialogBuilder builder = new MaterialAlertDialogBuilder(this);
        builder.setTitle("Rename LUT");

        // --- DİNAMİK CONTEXT ---
        // Püf Noktası: Dialog'un kendi context'ini kullanıyoruz.
        // Böylece Dialog açık renkse yazı koyu, Dialog koyuysa yazı açık olur.
        android.content.Context dialogContext = builder.getContext();

        // Container (Kenar Boşlukları İçin)
        android.widget.FrameLayout container = new android.widget.FrameLayout(dialogContext);
        android.widget.FrameLayout.LayoutParams params = new android.widget.FrameLayout.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = 60;
        params.rightMargin = 60;
        params.topMargin = 20; // Başlık ile yazı arasına biraz boşluk

        // --- MODERN INPUT ALANI (TextInputLayout) ---
        // Bu bileşen temaya göre renk değiştirir
        com.google.android.material.textfield.TextInputLayout textInputLayout =
                new com.google.android.material.textfield.TextInputLayout(dialogContext);

        // İstersen kutucuk stili verebilirsin (Outline veya Filled)
        // Varsayılan hali sadece alt çizgidir, dialog için en temizidir.
        textInputLayout.setHint("LUT Name");

        com.google.android.material.textfield.TextInputEditText input =
                new com.google.android.material.textfield.TextInputEditText(textInputLayout.getContext());

        input.setText(file.getName().replace(".cube", ""));
        input.setInputType(android.text.InputType.TYPE_CLASS_TEXT);
        input.setSelectAllOnFocus(true); // Açıldığında tüm metni seç (Hızlı silmek için)

        // Manuel renk atamalarını SİLDİK. Sistem halledecek.

        textInputLayout.addView(input);
        textInputLayout.setLayoutParams(params);
        container.addView(textInputLayout);

        builder.setView(container);

        // --- BUTONLAR ---
        builder.setPositiveButton("Save", (dialog, which) -> {
            String newName = input.getText().toString().trim();
            if (!newName.isEmpty()) {
                renameLutFile(file, newName);
            }
        });

        builder.setNegativeButton("Cancel", (dialog, which) -> dialog.cancel());

        // Dialog'u göster
        androidx.appcompat.app.AlertDialog dialog = builder.create();

        // Klavye otomatik açılsın diye (Opsiyonel ama iyi hissettirir)
        dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);

        dialog.show();
    }

    // --- YENİ METOD: DOSYA İŞLEMİ ---
    private void renameLutFile(File oldFile, String newName) {
        // Yeni dosya yolu (.cube uzantısını eklemeyi unutma)
        File newFile = new File(oldFile.getParent(), newName + ".cube");

        if (newFile.exists()) {
            Toast.makeText(this, "Name already exists!", Toast.LENGTH_SHORT).show();
            return;
        }

        if (oldFile.renameTo(newFile)) {
            // KRİTİK: Eğer isim değiştirdiğimiz dosya, o an AKTİF olan LUT ise,
            // SharedPreferences içindeki ismi de güncellememiz lazım.
            // Yoksa uygulama eski ismi arar, bulamaz ve LUT iptal olur.
            String currentSelected = prefs.getString("current_lut_name", "");
            if (currentSelected.equals(oldFile.getName())) {
                prefs.edit().putString("current_lut_name", newFile.getName()).apply();
            }

            Toast.makeText(this, "Renamed!", Toast.LENGTH_SHORT).show();
            loadLuts(); // Listeyi yenile
        } else {
            Toast.makeText(this, "Rename failed", Toast.LENGTH_SHORT).show();
        }
    }




    private void loadLuts() {
        // 1. Listeyi temizle
        lutFiles.clear();

        // 2. Dosyaları klasörden bul
        File dir = new File(getFilesDir(), "luts");
        if (!dir.exists()) dir.mkdirs();

        File[] files = dir.listFiles((d, name) -> name.toLowerCase().endsWith(".cube"));

        // 3. Dosyaları listeye ekle
        if (files != null) {
            lutFiles.addAll(Arrays.asList(files));
        }

        // 4. Görünürlüğü ayarla
        if (lutFiles.isEmpty()) {
            emptyState.setVisibility(View.VISIBLE);
            recyclerView.setVisibility(View.GONE);
        } else {
            emptyState.setVisibility(View.GONE);
            recyclerView.setVisibility(View.VISIBLE);
        }

        // --- DÜZELTME BURADA ---
        // Adapter'ı yeniden oluşturma (new LutAdapter...) HATALIYDI.
        // Adapter zaten yukarıdaki 'lutFiles' listesine bağlı.
        // Sadece "Veri değişti, kendini yenile" dememiz yeterli.
        if (adapter != null) {
            // Seçili olan ismini güncelle (Belki dosya silinmiştir vs.)
            String savedLut = prefs.getString("current_lut_name", "");
            // Adapter'a yeni seçili ismi göndermek için basit bir metod eklenebilir
            // ama şimdilik tüm listeyi yenilemek yeterli.
            adapter.notifyDataSetChanged();
        } else {
            // Eğer adapter null ise (ilk açılışta loadLuts çağrılırsa) setupRecycler kurmalı
            setupRecycler();
        }
    }


    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == PICK_LUT_FILE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri != null) {
                // Import işlemini yap
                File newFile = importLutFile(uri);

                // Eğer başarılıysa hemen isim değiştirme pencresini aç
                if (newFile != null) {
                    showRenameDialog(newFile);
                }
            }
        }
    }

    // Void yerine File döndürüyor
    private File importLutFile(Uri uri) {
        try {
            // 1. Gerçek dosya ismini bul
            String fileName = getFileNameFromUri(uri);

            File destDir = new File(getFilesDir(), "luts");
            if (!destDir.exists()) destDir.mkdirs();

            // Eğer aynı isimde dosya varsa sonuna (1), (2) ekle ki üzerine yazmasın
            File destFile = new File(destDir, fileName);
            int counter = 1;
            while (destFile.exists()) {
                String nameWithoutExt = fileName.replace(".cube", "");
                destFile = new File(destDir, nameWithoutExt + "(" + counter + ").cube");
                counter++;
            }

            // 2. Kopyala
            InputStream is = getContentResolver().openInputStream(uri);
            FileOutputStream fos = new FileOutputStream(destFile);

            byte[] buffer = new byte[1024];
            int length;
            while ((length = is.read(buffer)) > 0) {
                fos.write(buffer, 0, length);
            }

            is.close();
            fos.close();

            // Listeyi yenile ki dosya görünsün
            loadLuts();

            return destFile; // Kaydedilen dosyayı döndür

        } catch (Exception e) {
            e.printStackTrace();
            Toast.makeText(this, "Import Failed", Toast.LENGTH_SHORT).show();
            return null;
        }
    }

    // URI'den gerçek dosya ismini çeken yardımcı metod
    private String getFileNameFromUri(Uri uri) {
        String result = null;
        if (uri.getScheme().equals("content")) {
            try (android.database.Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                    if (index != -1) {
                        result = cursor.getString(index);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        if (result == null) {
            result = uri.getPath();
            int cut = result.lastIndexOf('/');
            if (cut != -1) {
                result = result.substring(cut + 1);
            }
        }
        // Eğer hala isim bulamadıysak timestamp kullan
        if (result == null || result.isEmpty()) {
            result = "imported_lut_" + System.currentTimeMillis() + ".cube";
        }
        // Uzantı .cube değilse veya yoksa ekle (güvenlik için)
        if (!result.toLowerCase().endsWith(".cube")) {
            result += ".cube";
        }
        return result;
    }
}