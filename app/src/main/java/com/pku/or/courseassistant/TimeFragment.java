package com.pku.or.courseassistant;

import android.os.Bundle;

import androidx.fragment.app.Fragment;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;


import android.widget.Button;
import android.widget.Toast;

import androidx.fragment.app.Fragment;

import com.pku.or.courseassistant.view.OnTimeSelectListener;
import com.pku.or.courseassistant.view.TimeSlot;
import com.pku.or.courseassistant.view.TimeTableView;

import java.util.List;

/**
 * A simple {@link Fragment} subclass.
 * Use the {@link TimeFragment#newInstance} factory method to
 * create an instance of this fragment.
 */
public class TimeFragment extends Fragment implements OnTimeSelectListener{

    private TimeTableView timeTableView;
    private Button clearButton;

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

        return view;
    }

    private void initViews(View view) {
        timeTableView = view.findViewById(R.id.timeTableView);
        clearButton = view.findViewById(R.id.clearButton);
    }

    private void setupListeners() {
        timeTableView.addOnTimeSelectListener(this);

        clearButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                timeTableView.clearTimeSlots();
                Toast.makeText(getContext(), "已清空所有时间段", Toast.LENGTH_SHORT).show();
            }
        });
    }

    @Override
    public void onTimeSlotSelected(TimeSlot timeSlot) {
        // 实现选择回调
    }

    @Override
    public void onTimeSlotChanged(List<TimeSlot> timeSlots) {
        // 时间段变化回调
        String message = "当前已选择 " + timeSlots.size() + " 个时间段";
        if (getActivity() != null) {
            Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onTimeSlotCreated(TimeSlot timeSlot) {
        String dayStr = getDayString(timeSlot.getDay());
        String message = "已选择周" + dayStr + " 第" + (timeSlot.getStartSection() + 1) +
                "-" + (timeSlot.getEndSection() + 1) + "节";
        Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onTimeSlotRemoved(TimeSlot timeSlot) {
        // 时间段移除回调
    }

    private String getDayString(int day) {
        String[] days = {"一", "二", "三", "四", "五", "六", "日"};
        if (day >= 0 && day < days.length) {
            return days[day];
        }
        return "未知";
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (timeTableView != null) {
            timeTableView.removeOnTimeSelectListener(this);
        }
    }
}