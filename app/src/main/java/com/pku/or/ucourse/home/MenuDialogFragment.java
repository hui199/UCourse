package com.pku.or.ucourse.home;

import android.app.Dialog;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.pku.or.ucourse.R;

import java.util.ArrayList;
import java.util.List;

public class MenuDialogFragment extends DialogFragment {

    private String title;
    private List<String> options;
    private OnOptionClickListener listener;

    public interface OnOptionClickListener {
        void onOptionClick(int position, String option);
    }

    public static MenuDialogFragment newInstance(String title, List<String> options) {
        MenuDialogFragment f = new MenuDialogFragment();
        f.title = title;
        f.options = options;
        return f;
    }

    public void setOnOptionClickListener(OnOptionClickListener l) {
        this.listener = l;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // We can reuse a simple layout or create one programmatically
        // Let's use a simple card-like layout
        // Since we don't have a specific layout xml for this, let's rely on a simple RecyclerView inside a Card
        // But for simplicity and beauty, let's use a custom layout if possible.
        // I'll assume we can use 'bg_dialog.xml' for background.
        
        View v = inflater.inflate(R.layout.dialog_menu, container, false);
        TextView tvTitle = v.findViewById(R.id.tv_menu_title);
        RecyclerView rv = v.findViewById(R.id.rv_menu_options);

        tvTitle.setText(title);
        
        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        rv.setAdapter(new MenuAdapter());
        
        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            getDialog().getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        }
        
        return v;
    }

    private class MenuAdapter extends RecyclerView.Adapter<MenuAdapter.VH> {
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            // Use a simple text item layout
            View v = LayoutInflater.from(parent.getContext()).inflate(android.R.layout.simple_list_item_1, parent, false);
            // Customize text color/size if needed
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            TextView tv = (TextView) holder.itemView;
            tv.setText(options.get(position));
            tv.setTextColor(Color.parseColor("#333333"));
            tv.setOnClickListener(v -> {
                if (listener != null) listener.onOptionClick(position, options.get(position));
                dismiss();
            });
        }

        @Override
        public int getItemCount() {
            return options == null ? 0 : options.size();
        }

        class VH extends RecyclerView.ViewHolder {
            VH(View v) { super(v); }
        }
    }
}
