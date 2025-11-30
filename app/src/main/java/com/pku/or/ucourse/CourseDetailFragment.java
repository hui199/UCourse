package com.pku.or.ucourse;

import android.app.Dialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.pku.or.ucourse.home.Course;

public class CourseDetailFragment extends BottomSheetDialogFragment {
    private static final String ARG_COURSE = "arg_course_json";

    public static CourseDetailFragment newInstance(Course c) {
        CourseDetailFragment f = new CourseDetailFragment();
        Bundle b = new Bundle();
        try {
            String j = new com.google.gson.Gson().toJson(c);
            b.putString(ARG_COURSE, j);
        } catch (Throwable _t) {}
        f.setArguments(b);
        return f;
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_course_detail, container, false);
        TextView tvAll = v.findViewById(R.id.tv_course_all);
        String js = getArguments() == null ? null : getArguments().getString(ARG_COURSE);
        if (js != null) {
            try {
                Course c = new com.google.gson.Gson().fromJson(js, Course.class);
                StringBuilder sb = new StringBuilder();
                sb.append("课程: ").append(c.title == null ? "" : c.title).append("\n\n");
                sb.append("教师: ").append(c.teachers == null ? "" : c.teachers).append("\n\n");
                sb.append("时间:\n").append(c.rawTime == null ? "" : c.rawTime).append("\n\n");
                sb.append("兴趣: ").append(c.interest).append("\n");
                sb.append("文件ID: ").append(c.fileId == null ? "" : c.fileId).append("\n");
                sb.append("Sheet: ").append(c.sheetName == null ? "" : c.sheetName).append("\n");
                tvAll.setText(sb.toString());
            } catch (Throwable _t) { tvAll.setText("(无法解析课程详情)"); }
        }
        return v;
    }
}
