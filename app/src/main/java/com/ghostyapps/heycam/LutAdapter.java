package com.ghostyapps.heycam;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import java.io.File;
import java.util.List;

public class LutAdapter extends RecyclerView.Adapter<LutAdapter.LutViewHolder> {

    private List<File> lutFiles;
    private String selectedLutName;
    private final OnLutActionListener listener;

    public interface OnLutActionListener {
        void onLutSelected(File file); // null gelirse "No LUT" demektir
        void onLutDeleted(File file);
        void onLutRename(File file);
    }

    public LutAdapter(List<File> lutFiles, String selectedLutName, OnLutActionListener listener) {
        this.lutFiles = lutFiles;
        this.selectedLutName = selectedLutName;
        this.listener = listener;
    }

    @NonNull
    @Override
    public LutViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_lut, parent, false);
        return new LutViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull LutViewHolder holder, int position) {

        // --- POZİSYON 0: "NO LUT" SEÇENEĞİ ---
        if (position == 0) {
            holder.txtName.setText("No LUT (Original)");

            // "No LUT" seçeneği silinemez, çöp kutusunu gizle
            holder.btnDelete.setVisibility(View.GONE);

            // Uzun basmayı devre dışı bırak
            holder.itemView.setOnLongClickListener(null);

            // Eğer kayıtlı isim boşsa veya null ise, "No LUT" seçilidir
            boolean isNoLutSelected = (selectedLutName == null || selectedLutName.isEmpty());

            holder.imgCheck.setVisibility(isNoLutSelected ? View.VISIBLE : View.GONE);
            holder.itemContainer.setAlpha(isNoLutSelected ? 1.0f : 0.6f);

            holder.itemView.setOnClickListener(v -> {
                selectedLutName = ""; // Seçimi temizle
                notifyDataSetChanged();
                listener.onLutSelected(null); // Activity'e null gönder
            });

            return; // 0. pozisyon bitti, aşağıya inme
        }

        // --- POZİSYON 1 ve SONRASI: GERÇEK DOSYALAR ---
        // Listeden veri çekerken (position - 1) yapıyoruz çünkü 0. sırayı "No LUT" kaptı
        File file = lutFiles.get(position - 1);

        holder.txtName.setText(file.getName().replace(".cube", ""));
        holder.btnDelete.setVisibility(View.VISIBLE); // Dosyalar silinebilir

        boolean isSelected = file.getName().equals(selectedLutName);
        holder.imgCheck.setVisibility(isSelected ? View.VISIBLE : View.GONE);
        holder.itemContainer.setAlpha(isSelected ? 1.0f : 0.6f);

        holder.itemView.setOnClickListener(v -> {
            selectedLutName = file.getName();
            notifyDataSetChanged();
            listener.onLutSelected(file);
        });

        // --- YENİ EKLENEN: UZUN BASMA (RENAME) ---
        holder.itemView.setOnLongClickListener(v -> {
            listener.onLutRename(file);
            return true; // Olayı tükettik (Click çalışmasın)
        });

        holder.btnDelete.setOnClickListener(v -> listener.onLutDeleted(file));
    }

    @Override
    public int getItemCount() {
        // Dosya sayısı + 1 (No LUT seçeneği için)
        if (lutFiles == null) return 1;
        return lutFiles.size() + 1;
    }

    static class LutViewHolder extends RecyclerView.ViewHolder {
        TextView txtName;
        ImageButton btnDelete;
        ImageView imgCheck;
        View itemContainer;

        public LutViewHolder(@NonNull View itemView) {
            super(itemView);
            txtName = itemView.findViewById(R.id.txt_lut_name);
            btnDelete = itemView.findViewById(R.id.btn_delete_lut);
            imgCheck = itemView.findViewById(R.id.img_check);
            itemContainer = itemView.findViewById(R.id.item_container);
        }
    }
}