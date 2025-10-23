package com.pku.or.courseassistant.home;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.fragment.app.DialogFragment;
import android.util.Log;

import java.util.List;
import com.pku.or.courseassistant.R;

public class ColumnMapDialogFragment extends DialogFragment {
    public static class MappingResult {
        public int titleIdx, timeIdx, teacherIdx, unitIdx;
        public boolean applyToAll;
        public MappingResult(int t, int ti, int te, int u, boolean a) { titleIdx=t; timeIdx=ti; teacherIdx=te; unitIdx=u; applyToAll=a; }
    }

    public interface Listener { void onMapping(MappingResult result); void onUsePrevious(); }

    private List<String> headers;
    private Listener listener;
    private MappingResult prefill = null;

    public ColumnMapDialogFragment(List<String> headers, Listener l) { this.headers = headers; this.listener = l; }

    // optional: prefill spinner selections before showing dialog
    public void setPrefillMapping(MappingResult m) { this.prefill = m; }

    @NonNull
    @Override
    public Dialog onCreateDialog(@Nullable Bundle savedInstanceState) {
        View v = LayoutInflater.from(getContext()).inflate(R.layout.dialog_column_map, null);
        Spinner spTitle = v.findViewById(R.id.sp_title);
        Spinner spTime = v.findViewById(R.id.sp_time);
        Spinner spTeacher = v.findViewById(R.id.sp_teacher);
        Spinner spUnit = v.findViewById(R.id.sp_unit);
        android.widget.CheckBox cbApplyAll = v.findViewById(R.id.cb_apply_all);
        Button btnUsePrev = v.findViewById(R.id.btn_use_prev);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(getContext(), android.R.layout.simple_spinner_item, headers);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spTitle.setAdapter(adapter);
        spTime.setAdapter(adapter);
        spTeacher.setAdapter(adapter);
        spUnit.setAdapter(adapter);

        // apply prefill selections if provided
        try {
            if (prefill != null) {
                if (prefill.titleIdx >= 0 && prefill.titleIdx < headers.size()) spTitle.setSelection(prefill.titleIdx);
                if (prefill.timeIdx >= 0 && prefill.timeIdx < headers.size()) spTime.setSelection(prefill.timeIdx);
                if (prefill.teacherIdx >= 0 && prefill.teacherIdx < headers.size()) spTeacher.setSelection(prefill.teacherIdx);
                if (prefill.unitIdx >= 0 && prefill.unitIdx < headers.size()) spUnit.setSelection(prefill.unitIdx);
            }
    } catch (Exception ex) { /* suppressed in production */ }

        AlertDialog.Builder b = new AlertDialog.Builder(getContext()).setView(v).setTitle("列映射");
        AlertDialog dlg = b.create();
        Button ok = v.findViewById(R.id.btn_ok);
        Button cancel = v.findViewById(R.id.btn_cancel);

        ok.setOnClickListener(x -> {
            if (listener != null) {
                int t = spTitle.getSelectedItemPosition();
                int ti = spTime.getSelectedItemPosition();
                int te = spTeacher.getSelectedItemPosition();
                int u = spUnit.getSelectedItemPosition();
                MappingResult r = new MappingResult(t, ti, te, u, cbApplyAll.isChecked());
                // also log the selected spinner item strings to confirm what the user saw
                Object titleItem = spTitle.getSelectedItem();
                Object timeItem = spTime.getSelectedItem();
                Object teacherItem = spTeacher.getSelectedItem();
                Object unitItem = spUnit.getSelectedItem();
                // mapping selection confirmed
                listener.onMapping(r);
            }
            dlg.dismiss();
        });

        cancel.setOnClickListener(x -> dlg.dismiss());

        btnUsePrev.setOnClickListener(x -> {
            if (listener != null) listener.onUsePrevious();
            dlg.dismiss();
        });

        return dlg;
    }
}
