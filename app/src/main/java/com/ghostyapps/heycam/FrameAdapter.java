package com.ghostyapps.heycam;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import android.os.Handler;
import android.os.Looper;

public class FrameAdapter extends RecyclerView.Adapter<FrameAdapter.FrameViewHolder> {

    private List<File> files;
    private String selectedName;
    private OnFrameSelectedListener listener;

    // --- 1. HAFIZA ÖNBELLEĞİ (RAM) ---
    // Resimleri bir kere yükleyip burada saklayacağız. Diskten tekrar okumayacağız.
    private LruCache<String, Bitmap> memoryCache;

    // Arka plan işlemleri için Executor (Thread Pool)
    private ExecutorService executorService = Executors.newFixedThreadPool(4);
    private Handler mainHandler = new Handler(Looper.getMainLooper());

    public interface OnFrameSelectedListener {
        void onFrameSelected(File file);
    }

    public FrameAdapter(List<File> files, String selectedName, OnFrameSelectedListener listener) {
        this.files = files;
        this.selectedName = selectedName;
        this.listener = listener;

        // Cache Boyutu: Mevcut RAM'in 1/8'i kadar yer ayır
        final int maxMemory = (int) (Runtime.getRuntime().maxMemory() / 1024);
        final int cacheSize = maxMemory / 8;

        memoryCache = new LruCache<String, Bitmap>(cacheSize) {
            @Override
            protected int sizeOf(String key, Bitmap bitmap) {
                return bitmap.getByteCount() / 1024;
            }
        };
    }

    @NonNull
    @Override
    public FrameViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_frame, parent, false);
        return new FrameViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull FrameViewHolder holder, int position) {
        // --- POZİSYON 0: NO FRAME ---
        if (position == 0) {
            holder.imgThumb.setImageResource(R.drawable.ic_block); // Boş ikon
            holder.imgThumb.setScaleType(ImageView.ScaleType.CENTER_INSIDE);

            boolean isNone = (selectedName == null || selectedName.isEmpty());
            holder.selectionBorder.setVisibility(isNone ? View.VISIBLE : View.GONE);

            holder.itemView.setOnClickListener(v -> updateSelection(null, holder.getAdapterPosition()));
            return;
        }

        // --- DİĞERLERİ: DOSYALAR ---
        File file = files.get(position - 1);
        String filePath = file.getAbsolutePath();

        // 2. Cache Kontrolü: Resim hafızada var mı?
        Bitmap cachedBitmap = getBitmapFromMemCache(filePath);

        if (cachedBitmap != null) {
            // VARSA: Direkt göster (Anlık olur)
            holder.imgThumb.setImageBitmap(cachedBitmap);
        } else {
            // YOKSA: Arka planda yükle, o sırada boş göster
            holder.imgThumb.setImageDrawable(null); // veya placeholder
            loadBitmapAsync(filePath, holder.imgThumb);
        }

        holder.imgThumb.setScaleType(ImageView.ScaleType.FIT_CENTER);

        // Seçim Durumu
        boolean isSelected = file.getName().equals(selectedName);
        holder.selectionBorder.setVisibility(isSelected ? View.VISIBLE : View.GONE);

        holder.itemView.setOnClickListener(v -> updateSelection(file, holder.getAdapterPosition()));
    }

    // --- AKILLI SEÇİM GÜNCELLEME ---
    // Tüm listeyi yenilemek yerine (notifyDataSetChanged), sadece değişen 2 satırı yeniler.
    private void updateSelection(File newFile, int newPosition) {
        String newName = (newFile == null) ? "" : newFile.getName();

        // Eğer aynı şeye tıklandıysa işlem yapma
        if ((selectedName == null && newName.isEmpty()) || newName.equals(selectedName)) return;

        // Eski seçili olanın pozisyonunu bul
        int oldPosition = 0; // Varsayılan "No Frame"
        if (selectedName != null && !selectedName.isEmpty()) {
            for (int i = 0; i < files.size(); i++) {
                if (files.get(i).getName().equals(selectedName)) {
                    oldPosition = i + 1;
                    break;
                }
            }
        }

        // Değerleri güncelle
        selectedName = newName;
        listener.onFrameSelected(newFile);

        // Sadece etkilenen satırları güncelle (Animasyonlu ve Hızlı)
        notifyItemChanged(oldPosition); // Eskisinin kenarlığını kaldır
        notifyItemChanged(newPosition); // Yenisine kenarlık koy
    }

    @Override
    public int getItemCount() {
        return files.size() + 1;
    }

    // --- ASYNC YÜKLEME SİSTEMİ ---

    private void addBitmapToMemoryCache(String key, Bitmap bitmap) {
        if (getBitmapFromMemCache(key) == null) {
            memoryCache.put(key, bitmap);
        }
    }

    private Bitmap getBitmapFromMemCache(String key) {
        return memoryCache.get(key);
    }

    private void loadBitmapAsync(String path, ImageView imageView) {
        // Tag kullanarak resmin doğru yere gitmesini sağla (Hızlı kaydırmada karışmaması için)
        imageView.setTag(path);

        executorService.execute(() -> {
            // Arka Planda Diskten Oku
            Bitmap bitmap = decodeSampledBitmapFromFile(path, 150, 150);

            // Cache'e ekle
            if (bitmap != null) {
                addBitmapToMemoryCache(path, bitmap);
            }

            // Ana Ekrana Dön ve Göster
            mainHandler.post(() -> {
                if (path.equals(imageView.getTag())) {
                    imageView.setImageBitmap(bitmap);
                    imageView.setAlpha(0f);
                    imageView.animate().alpha(1f).setDuration(200).start(); // Ufak bir fade-in
                }
            });
        });
    }

    private static Bitmap decodeSampledBitmapFromFile(String path, int reqWidth, int reqHeight) {
        final BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(path, options);

        options.inSampleSize = calculateInSampleSize(options, reqWidth, reqHeight);

        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeFile(path, options);
    }

    private static int calculateInSampleSize(BitmapFactory.Options options, int reqWidth, int reqHeight) {
        final int height = options.outHeight;
        final int width = options.outWidth;
        int inSampleSize = 1;

        if (height > reqHeight || width > reqWidth) {
            final int halfHeight = height / 2;
            final int halfWidth = width / 2;
            while ((halfHeight / inSampleSize) >= reqHeight && (halfWidth / inSampleSize) >= reqWidth) {
                inSampleSize *= 2;
            }
        }
        return inSampleSize;
    }

    static class FrameViewHolder extends RecyclerView.ViewHolder {
        ImageView imgThumb;
        View selectionBorder;

        public FrameViewHolder(@NonNull View itemView) {
            super(itemView);
            imgThumb = itemView.findViewById(R.id.img_frame_thumb);
            selectionBorder = itemView.findViewById(R.id.view_selection_border);
        }
    }
}