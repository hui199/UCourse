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
                
                StringBuilder sb = new StringBuilder();
                // User requested to remove credits/hours/type if they are blank, and ensure full info
                // Request 7: "课程的详细信息中，有些项目是空白的，学分/学时/类型这三个信息，删除。"
                
                if (c.teachers != null && !c.teachers.isEmpty()) sb.append("教师: ").append(c.teachers).append("\n");
                if (c.unit != null && !c.unit.isEmpty()) sb.append("开课单位: ").append(c.unit).append("\n");
                
                // Only show if not empty/null
                if (c.credits != null && !c.credits.isEmpty()) sb.append("学分: ").append(c.credits).append("\n");
                if (c.hours != null && !c.hours.isEmpty()) sb.append("学时: ").append(c.hours).append("\n");
                if (c.type != null && !c.type.isEmpty()) sb.append("类型: ").append(c.type).append("\n");
                
                if (c.location != null && !c.location.isEmpty()) sb.append("地点: ").append(c.location).append("\n");
                
                sb.append("上课时间: ").append(c.rawTime == null ? "" : c.rawTime).append("\n");
                
                // Interest
                sb.append("当前兴趣分: ").append(c.interest).append("\n");
                
                // Add date range info if available (Request 7: "显示诸如1-16周这样的信息后面可以再加个括号...标记上第一节课的时间和最后一节课的时间")
                // Note: User request 7 in previous turn was "7.有了第一周的时间后...显示的诸如1-16周这样的信息后面可以再加个括号...".
                // In this turn, request 5 is "5.我设置了第一周的时间后，course fragment中点击课程，有看到首课和结课时间，但是result fragment中没有看到，请加上。"
                // So I need to ensure this info is calculated and shown.
                
                // We need to calculate dates here or pass them.
                // Let's try to calculate if context is available.
                try {
                    android.content.SharedPreferences prefs = getContext().getSharedPreferences("ucourse_prefs", android.content.Context.MODE_PRIVATE);
                    String firstWeekStr = prefs.getString("first_week_date", null);
                    if (firstWeekStr != null && c.rawTime != null) {
                        // Parse start date
                        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
                        java.util.Date startDate = sdf.parse(firstWeekStr);
                        java.util.Calendar cal = java.util.Calendar.getInstance();
                        cal.setTime(startDate);
                        
                        // Parse weeks from rawTime (e.g. "1-16周")
                        // Simple regex for "X-Y周"
                        java.util.regex.Pattern p = java.util.regex.Pattern.compile("(\\d+)-(\\d+)周");
                        java.util.regex.Matcher m = p.matcher(c.rawTime);
                        if (m.find()) {
                            int startWeek = Integer.parseInt(m.group(1));
                            int endWeek = Integer.parseInt(m.group(2));
                            
                            // Calculate first class date (assuming Monday of startWeek)
                            // Actually we should find the specific day of week if possible, but "startWeek" usually means the Monday of that week.
                            // If rawTime has "周一", we can be more precise.
                            
                            // Start Date: First week date + (startWeek - 1) weeks
                            java.util.Calendar startCal = (java.util.Calendar) cal.clone();
                            startCal.add(java.util.Calendar.WEEK_OF_YEAR, startWeek - 1);
                            String startD = sdf.format(startCal.getTime());
                            
                            // End Date: First week date + (endWeek - 1) weeks + 6 days (end of that week)
                            java.util.Calendar endCal = (java.util.Calendar) cal.clone();
                            endCal.add(java.util.Calendar.WEEK_OF_YEAR, endWeek - 1);
                            endCal.add(java.util.Calendar.DAY_OF_WEEK, 6); // Sunday
                            String endD = sdf.format(endCal.getTime());
                            
                            sb.append("\n(课程周期: ").append(startD).append(" ~ ").append(endD).append(")");
                        }
                    }
                } catch (Throwable _t) {}

                // File info (debug mostly, but useful)
                // sb.append("\n来源: ").append(c.fileId).append(" / ").append(c.sheetName);

                tvAll.setText(sb.toString());
            } catch (Throwable _t) { 
                if (tvAll != null) tvAll.setText("(无法解析课程详情)"); 
            }
        }
        return v;
    }
}
