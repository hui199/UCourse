package com.pku.or.courseassistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.google.gson.Gson;
import com.pku.or.courseassistant.model.WeekTimeData;
import com.pku.or.courseassistant.view.OnTimeSelectListener;
import com.pku.or.courseassistant.view.TimeSlot;
import com.pku.or.courseassistant.view.TimeTableView;

import java.util.List;

public class TimeFragment extends Fragment implements OnTimeSelectListener {

    private static final String PREFS_NAME = "TimeTablePrefs";
    private static final String KEY_WEEK_DATA = "week_data";
    private static final String KEY_CURRENT_DATE = "current_date";

    private TimeTableView timeTableView;
    private Button clearButton;
    private Button selectDateButton;

    private WeekTimeData currentWeekData;

    public TimeFragment() {
        // Required empty public constructor
    }

    public static TimeFragment newInstance() {
        return new TimeFragment();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.fragment_time, container, false);

        initViews(view);
        setupListeners();
        loadData();

        return view;
    }

    private void initViews(View view) {
        timeTableView = view.findViewById(R.id.timeTableView);
        clearButton = view.findViewById(R.id.clearButton);
        selectDateButton = view.findViewById(R.id.selectDateButton);

        currentWeekData = new WeekTimeData();
    }

    private void setupListeners() {
        timeTableView.addOnTimeSelectListener(this);

        clearButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                timeTableView.clearTimeSlots();
                currentWeekData.clear();
                saveData();
                Toast.makeText(getContext(), "已清空所有时间段", Toast.LENGTH_SHORT).show();
            }
        });

        selectDateButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                timeTableView.showDatePickerDialog(getContext());
            }
        });
    }

    private void loadData() {
        SharedPreferences prefs = getActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);

        // 加载周数据
        String weekDataJson = prefs.getString(KEY_WEEK_DATA, null);
        if (weekDataJson != null) {
            Gson gson = new Gson();
            currentWeekData = gson.fromJson(weekDataJson, WeekTimeData.class);
            timeTableView.setWeekData(currentWeekData);
        }

        // 加载当前日期
        long savedDate = prefs.getLong(KEY_CURRENT_DATE, -1);
        if (savedDate != -1) {
            timeTableView.setStartDate(new java.util.Date(savedDate));
        }
    }

    private void saveData() {
        SharedPreferences prefs = getActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        // 保存周数据
        Gson gson = new Gson();
        String weekDataJson = gson.toJson(currentWeekData);
        editor.putString(KEY_WEEK_DATA, weekDataJson);

        // 保存当前日期
        editor.putLong(KEY_CURRENT_DATE, System.currentTimeMillis());

        editor.apply();
    }

    @Override
    public void onTimeSlotSelected(TimeSlot timeSlot) {
        // 实现选择回调（可选）
    }

    @Override
    public void onTimeSlotChanged(List<TimeSlot> timeSlots) {
        // 时间段变化回调
        currentWeekData = timeTableView.getCurrentWeekData();
        saveData();
    }

    @Override
    public void onTimeSlotCreated(TimeSlot timeSlot) {
        String dayStr = getDayString(timeSlot.getDay());
        String message = "已选择周" + dayStr + " 第" + (timeSlot.getStartSection() + 1) +
                "-" + (timeSlot.getEndSection() + 1) + "节";
        Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();

        // 更新周数据
        currentWeekData = timeTableView.getCurrentWeekData();
        saveData();
    }

    @Override
    public void onTimeSlotRemoved(TimeSlot timeSlot) {
        String dayStr = getDayString(timeSlot.getDay());
        String message = "已删除周" + dayStr + " 第" + (timeSlot.getStartSection() + 1) +
                "-" + (timeSlot.getEndSection() + 1) + "节";
        Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();

        // 更新周数据
        currentWeekData = timeTableView.getCurrentWeekData();
        saveData();
    }

    /**
     * 获取当前周的空闲时间数据（供其他Fragment使用）
     */
    public WeekTimeData getCurrentWeekData() {
        return currentWeekData;
    }

    /**
     * 设置周数据（从其他Fragment恢复数据）
     */
    public void setWeekData(WeekTimeData weekData) {
        if (weekData != null) {
            timeTableView.setWeekData(weekData);
            currentWeekData = weekData;
            saveData();
        }
    }

    private String getDayString(int day) {
        String[] days = {"一", "二", "三", "四", "五", "六", "日"};
        if (day >= 0 && day < days.length) {
            return days[day];
        }
        return "未知";
    }

    @Override
    public void onPause() {
        super.onPause();
        saveData();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (timeTableView != null) {
            timeTableView.removeOnTimeSelectListener(this);
        }
        saveData();
    }
}