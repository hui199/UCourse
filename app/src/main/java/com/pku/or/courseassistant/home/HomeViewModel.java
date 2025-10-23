package com.pku.or.courseassistant.home;

import android.app.Application;
import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.MutableLiveData;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;

public class HomeViewModel extends AndroidViewModel {
    private static final String PREF_KEY = "home_courses_json";
    private Gson gson = new Gson();
    public MutableLiveData<List<Course>> courses = new MutableLiveData<>();

    public HomeViewModel(@NonNull Application application) {
        super(application);
        load();
    }

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

    public void save(List<Course> list) {
        String json = gson.toJson(list);
        getApplication().getSharedPreferences("app_prefs", 0).edit().putString(PREF_KEY, json).apply();
        courses.postValue(list);
    }
}
