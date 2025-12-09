package com.pku.or.ucourse;

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

import com.pku.or.ucourse.home.Course;

import java.util.Locale;

public class CourseDetailFragment extends DialogFragment {
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
    
    // 辅助方法：添加带样式的字段（冒号左侧加粗加下划线，右侧默认）
    private void addStyledField(android.text.SpannableStringBuilder ssb, String label, String value) {
        // 即使值为空，也显示标签（确保样式始终应用）
        String displayValue = value == null || value.isEmpty() ? "" : value;
        
        String fullLabel = label + ": ";
        int start = ssb.length();
        int end = start + fullLabel.length();
        
        ssb.append(fullLabel).append(displayValue).append("\n");
        
        // 为冒号左侧的标签添加样式 (Bold + Underline for ALL labels)
        ssb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        ssb.setSpan(new android.text.style.UnderlineSpan(), start, end, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        if (getDialog() != null && getDialog().getWindow() != null) {
            getDialog().getWindow().setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            getDialog().getWindow().requestFeature(Window.FEATURE_NO_TITLE);
        }

        View v = inflater.inflate(R.layout.fragment_course_detail, container, false);
        TextView tvAll = v.findViewById(R.id.tv_course_all);
        TextView tvTitle = v.findViewById(R.id.tv_detail_title);

        String js = getArguments() == null ? null : getArguments().getString(ARG_COURSE);
        if (js != null) {
            try {
                Course c = new com.google.gson.Gson().fromJson(js, Course.class);
                if (tvTitle != null) tvTitle.setText(c.title == null ? "课程详情" : c.title);
                
                // 使用SpannableStringBuilder来设置不同的文本样式
                android.text.SpannableStringBuilder ssb = new android.text.SpannableStringBuilder();
                // User requested to show all available course information, including sheet name and row number
                
                // 辅助方法：添加带样式的字段
                addStyledField(ssb, "教师", c.teachers);
                addStyledField(ssb, "开课单位", c.unit);
                addStyledField(ssb, "学分", c.credits);
                addStyledField(ssb, "学时", c.hours);
                addStyledField(ssb, "类型", c.type);
                addStyledField(ssb, "地点", c.location);
                addStyledField(ssb, "上课时间", c.rawTime);
                addStyledField(ssb, "时间", c.timeStr);
                addStyledField(ssb, "分组键", c.groupKey);
                
                // Interest
                addStyledField(ssb, "当前兴趣分", String.valueOf(c.interest));
                
                // 添加来源信息：Sheet名称和行数
                if (c.sheetName != null && !c.sheetName.isEmpty()) {
                    ssb.append("\n");
                    addStyledField(ssb, "来源", c.sheetName);
                }
                
                // 从ID中提取行号信息
                if (c.id != null && !c.id.isEmpty()) {
                    String[] idParts = c.id.split("\\|");
                    if (idParts.length >= 3) {
                        String rowIndex = idParts[2];
                        addStyledField(ssb, "行数", rowIndex);
                    }
                }
                
                // Use CourseTimeParser for robust week range parsing
                try {
                    android.content.SharedPreferences prefs = getContext().getSharedPreferences("ucourse_prefs", android.content.Context.MODE_PRIVATE);
                    String firstWeekStr = prefs.getString("first_week_date", null);
                    if (firstWeekStr != null && c.rawTime != null) {
                        // Parse start date
                        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
                        java.util.Date startDate = sdf.parse(firstWeekStr);
                        java.util.Calendar cal = java.util.Calendar.getInstance();
                        cal.setTime(startDate);
                        
                        // Use centralized parser
                        int[] weeks = com.pku.or.ucourse.result.CourseTimeParser.parseWeekRange(c.rawTime);
                        int startWeek = weeks[0];
                        int endWeek = weeks[1];
                        
                        // Calculate first class date (assuming Monday of startWeek)
                        // Start Date: First week date + (startWeek - 1) weeks
                        java.util.Calendar startCal = (java.util.Calendar) cal.clone();
                        startCal.add(java.util.Calendar.WEEK_OF_YEAR, startWeek - 1);
                        String startD = sdf.format(startCal.getTime());
                        
                        // End Date: First week date + (endWeek - 1) weeks + 6 days (end of that week)
                        java.util.Calendar endCal = (java.util.Calendar) cal.clone();
                        endCal.add(java.util.Calendar.WEEK_OF_YEAR, endWeek - 1);
                        endCal.add(java.util.Calendar.DAY_OF_WEEK, 6); // Sunday
                        String endD = sdf.format(endCal.getTime());
                        
                        ssb.append("\n");
                        addStyledField(ssb, "课程周期", startD + " ~ " + endD + " (" + startWeek + "-" + endWeek + "周)");
                    }
                } catch (Throwable _t) {}

                // File info (debug mostly, but useful)
                // sb.append("\n来源: ").append(c.fileId).append(" / ").append(c.sheetName);

                tvAll.setText(ssb);
            } catch (Throwable _t) { 
                if (tvAll != null) tvAll.setText("(无法解析课程详情)"); 
            }
        }
        return v;
    }
}
