package com.pku.or.ucourse;

import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.google.gson.Gson;
import com.pku.or.ucourse.model.WeekTimeData;

public class TimeViewModel extends AndroidViewModel {

    private static final String PREFS_NAME = "TimeTablePrefs";
    private static final String KEY_WEEK_DATA = "week_data";

    private final MutableLiveData<WeekTimeData> weekTimeData = new MutableLiveData<>();
    private final MutableLiveData<Integer> freeSlotCount = new MutableLiveData<>();
    private final SharedPreferences prefs;
    private final Gson gson = new Gson();

    public TimeViewModel(@NonNull Application application) {
        super(application);
        prefs = application.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        loadData();
    }

    public LiveData<WeekTimeData> getWeekTimeData() {
        return weekTimeData;
    }

    public LiveData<Integer> getFreeSlotCount() { return freeSlotCount; }

    private void loadData() {
        String weekDataJson = prefs.getString(KEY_WEEK_DATA, null);
        if (weekDataJson != null) {
            WeekTimeData loadedData = gson.fromJson(weekDataJson, WeekTimeData.class);
            weekTimeData.setValue(loadedData);
        } else {
            // 不要在没有数据时自动写入空对象，留给调用方决定何时初始化
            weekTimeData.setValue(null);
        }
    }

    private void saveData() {
        SharedPreferences.Editor editor = prefs.edit();
        WeekTimeData dataToSave = weekTimeData.getValue();
        if (dataToSave != null) {
            String weekDataJson = gson.toJson(dataToSave);
            editor.putString(KEY_WEEK_DATA, weekDataJson);
            editor.apply();
        }
    }

    /**
     * 更新时间数据，并自动保存
     * @param newData 新的 WeekTimeData
     */
    public void updateWeekTimeData(WeekTimeData newData) {
        // Always update LiveData to ensure observers get latest data (avoid missed updates due to equals)
        if (newData != null) {
            weekTimeData.setValue(newData);
            saveData();
        }
    }

    public void setFreeSlotCount(int count) {
        freeSlotCount.setValue(count);
    }

    /**
     * 清空数据，并自动保存
     */
    public void clearData() {
        WeekTimeData emptyData = new WeekTimeData();
        weekTimeData.setValue(emptyData);
        saveData();
    }
}