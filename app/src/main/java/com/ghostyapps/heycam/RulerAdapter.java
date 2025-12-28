package com.ghostyapps.heycam;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.List;

public class RulerAdapter extends RecyclerView.Adapter<RulerAdapter.RulerViewHolder> {

    private final List<String> dataList;

    public RulerAdapter(List<String> dataList) {
        this.dataList = dataList;
    }

    @NonNull
    @Override
    public RulerViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        // BURASI ÖNEMLİ: Yukarıda oluşturduğumuz 'item_ruler' dosyasını bağlıyoruz.
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_ruler, parent, false);
        return new RulerViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull RulerViewHolder holder, int position) {
        String text = dataList.get(position);
        holder.txtValue.setText(text);

        // Seçili olma/olmama durumuna göre renk değişimi burada yapılabilir
        // Şimdilik varsayılan gri, MainActivity içindeki onScrollListener
        // ile görsel efektler (Alpha/Scale) yönetiliyor.
    }

    @Override
    public int getItemCount() {
        return dataList.size();
    }

    public static class RulerViewHolder extends RecyclerView.ViewHolder {
        TextView txtValue;
        View tickView;

        public RulerViewHolder(@NonNull View itemView) {
            super(itemView);
            // Hata veren satırlar şimdi düzelmiş olmalı çünkü XML'de bu ID'leri tanımladık
            txtValue = itemView.findViewById(R.id.txt_wheel_value);
            tickView = itemView.findViewById(R.id.tick_view);
        }
    }
}