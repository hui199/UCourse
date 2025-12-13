package com.pku.or.ucourse.home;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

/**
 * 首页ViewModel
 * 管理课程数据的持久化和LiveData更新
 * 使用SharedPreferences存储课程列表的JSON数据
 */
public class HomeViewModel extends AndroidViewModel {
    private static final String PREF_KEY = "home_courses_json";
    private Gson gson = new Gson();
    // 课程列表的LiveData，UI监听此数据实现自动更新
    public MutableLiveData<List<Course>> courses = new MutableLiveData<>();

    public HomeViewModel(@NonNull Application application) {
        super(application);
        load();
    }

    /**
     * 从SharedPreferences加载课程数据
     * 反序列化JSON为Course对象列表
     */
    public void load() {
        String json = getApplication().getSharedPreferences("app_prefs", 0).getString(PREF_KEY, null);
        if (json == null) {
            courses.postValue(new ArrayList<>());
            return;
        }
        Type t = new TypeToken<List<Course>>(){}.getType();
        List<Course> list = gson.fromJson(json, t);
        courses.postValue(list != null ? list : new ArrayList<>());
    }

    /**
     * 保存课程列表到SharedPreferences
     * 序列化为JSON并同步更新LiveData
     * 
     * @param list 课程列表
     */
    public void save(List<Course> list) {
        android.util.Log.d("HomeViewModel", "SAVE: Saving " + (list != null ? list.size() : 0) + " courses.");
        if (list != null) {
            int nonZeroCount = 0;
            for (Course c : list) {
                if (c.interest > 0) {
                    nonZeroCount++;
                    if (c.title != null && c.title.contains("媒体与国际关系")) {
                        android.util.Log.d("HomeViewModel", "SAVE_CHECK: 媒体与国际关系 interest=" + c.interest);
                    }
                }
            }
            android.util.Log.d("HomeViewModel", "SAVE: Non-zero interest count: " + nonZeroCount);
        }
        
        String json = gson.toJson(list);
        getApplication().getSharedPreferences("app_prefs", 0).edit().putString(PREF_KEY, json).apply();
        
        // 如果在主线程，同步更新LiveData（确保调用者立即能读到最新值）
        // 否则使用postValue在主线程调度更新
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            courses.setValue(list);
        } else {
            courses.postValue(list);
        }
    }

    /**
     * 更新课程的兴趣分数
     * 
     * @param type   项目类型（保留参数，当前未使用）
     * @param itemId 课程ID
     * @param score  新的兴趣分数（0-10）
     */
    public void updateInterest(int type, String itemId, int score) {
        List<Course> list = courses.getValue();
        if (list == null || itemId == null) return;

        boolean updated = false;
        for (Course c : list) {
            if (c != null && itemId.equals(c.id)) {
                if (c.interest != score) {
                    c.interest = score;
                    updated = true;
                }
                break; // 找到课程后无需继续
            }
        }

        // 只有实际发生变化时才保存
        if (updated) {
            save(list);
        }
    }
}
