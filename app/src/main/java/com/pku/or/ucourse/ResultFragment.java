package com.pku.or.ucourse;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.BackgroundColorSpan;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.graphics.Color;
import android.graphics.Typeface;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import com.pku.or.ucourse.utils.PerformanceLogger;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.core.view.ViewCompat;

import com.pku.or.ucourse.home.Course;
import com.pku.or.ucourse.home.HomeViewModel;
import com.pku.or.ucourse.model.WeekTimeData;
import com.pku.or.ucourse.result.CourseTimeParser;
import com.pku.or.ucourse.result.RecommendationSolver;
import com.pku.or.ucourse.view.TimeSlot;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import java.util.concurrent.atomic.AtomicReference;

/**
 * 结果页面，用于显示课程推荐方案
 */
public class ResultFragment extends Fragment {

    // 使用标准的TAG命名方式
    public static final String TAG = "UCourse_ResultFragment";
    
    // UI组件
    private Button btnGenerate;
    private ProgressBar progressBar;
    private LinearLayout container;
    private HomeViewModel homeViewModel;
    private volatile Thread progressThread; // 进度更新线程
    
    // 存储课程ID到时间段的映射
    private Map<String, List<TimeSlot>> lastCourseMapping = new HashMap<>();
    private Map<String, List<Course>> groupToOriginalCourses; // 组ID到原始课程列表的映射
    private Map<String, Integer> courseFrequency = new HashMap<>(); // 课程频率统计
    private int totalSolutionsCount = 0; // 总方案数
    private int maxRecommendations = 10; // 默认最大推荐方案数量
    private static final int MIN_RECOMMENDATIONS = 1;
    private static final int MAX_RECOMMENDATIONS = 10;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true); // 启用菜单
        
        // 从SharedPreferences加载最大推荐方案数量
        loadMaxRecommendations();
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // 使用统一的性能日志工具
        PerformanceLogger.logLifecycleEvent(TAG, "CREATED");
        PerformanceLogger.logLifecycleEvent(TAG, "ON_CREATE_VIEW_CALLED");
        
        // Inflate the layout for this fragment
        View v = inflater.inflate(R.layout.fragment_result, container, false);
        
        // 初始化UI组件
        btnGenerate = v.findViewById(R.id.btn_generate);
        progressBar = v.findViewById(R.id.progress_bar);
        this.container = v.findViewById(R.id.sol_container);
        
        // 获取ViewModel
        homeViewModel = new ViewModelProvider(requireActivity()).get(HomeViewModel.class);
        
        // 设置按钮点击事件
        btnGenerate.setOnClickListener(view -> {
            // 记录点击前的输入数据
            Log.d(TAG, "BUTTON_CLICK: 生成推荐按钮点击 - 前");
            Log.d(TAG, "BUTTON_CLICK: 输入数据 - 课程数量: " + (homeViewModel != null && homeViewModel.courses.getValue() != null ? homeViewModel.courses.getValue().size() : 0));
            Log.d(TAG, "BUTTON_CLICK: 输入数据 - 最大推荐数: " + maxRecommendations);
            
            generateRecommendations();
            
            // 记录点击后的输出数据
            Log.d(TAG, "BUTTON_CLICK: 生成推荐按钮点击 - 后");
            // 推荐结果数量将在generateRecommendations方法内部记录
        });

        return v;
    }

    // 从SharedPreferences加载最大推荐方案数量
    private void loadMaxRecommendations() {
        try {
            android.content.SharedPreferences prefs = requireContext().getSharedPreferences("ucourse_prefs", android.content.Context.MODE_PRIVATE);
            int value = prefs.getInt("max_recommendations", 10);
            // 确保值在有效范围内
            maxRecommendations = Math.max(MIN_RECOMMENDATIONS, Math.min(MAX_RECOMMENDATIONS, value));
        } catch (Exception e) {
            // 如果加载失败，使用默认值
            maxRecommendations = 10;
            Log.e(TAG, "加载最大推荐方案数量失败", e);
        }
    }

    // 保存最大推荐方案数量到SharedPreferences
    private void saveMaxRecommendations(int value) {
        try {
            android.content.SharedPreferences prefs = requireContext().getSharedPreferences("ucourse_prefs", android.content.Context.MODE_PRIVATE);
            android.content.SharedPreferences.Editor editor = prefs.edit();
            editor.putInt("max_recommendations", value);
            editor.apply();
        } catch (Exception e) {
            Log.e(TAG, "保存最大推荐方案数量失败", e);
        }
    }

    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.result_menu, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }

    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        if (item.getItemId() == R.id.menu_set_max_plans) {
            showMaxRecommendationsDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // 显示最大推荐方案数量设置对话框
    private void showMaxRecommendationsDialog() {
        androidx.appcompat.app.AlertDialog.Builder builder = new androidx.appcompat.app.AlertDialog.Builder(requireContext());
        builder.setTitle("设置最大推荐方案数量");
        
        // 创建一个数字选择器
        final android.widget.NumberPicker numberPicker = new android.widget.NumberPicker(requireContext());
        numberPicker.setMinValue(MIN_RECOMMENDATIONS);
        numberPicker.setMaxValue(MAX_RECOMMENDATIONS);
        numberPicker.setValue(maxRecommendations);
        
        builder.setView(numberPicker);
        
        builder.setPositiveButton("确定", (dialog, which) -> {
            int newValue = numberPicker.getValue();
            maxRecommendations = newValue;
            saveMaxRecommendations(newValue);
            Toast.makeText(requireContext(), "最大推荐方案数量已设置为 " + newValue, Toast.LENGTH_SHORT).show();
        });
        
        builder.setNegativeButton("取消", null);
        
        builder.show();
    }

    /**
     * 生成推荐方案
     */
    private void generateRecommendations() {
        final long overallStartTime = System.currentTimeMillis();
        PerformanceLogger.logPerformancePoint("GENERATE_RECOMMENDATIONS", "按钮点击开始");
        
        // 清空之前的结果
        if (container != null) {
            container.removeAllViews();
        }
        if (btnGenerate != null) {
            btnGenerate.setEnabled(false);
            btnGenerate.setText("正在生成推荐方案...");
        }
        // 显示进度条并重置进度
        if (progressBar != null) {
            progressBar.setProgress(0);
            progressBar.setVisibility(View.VISIBLE);
        }
        PerformanceLogger.logProgress("RECOMMENDATION_PROCESS", 0, 0);

        final long startTime = System.currentTimeMillis();
        final int timeLimitMs = 20000; // 优化时间限制为20秒，更合理的估计
        
        // 用于在线程间共享Solver实例
        final AtomicReference<RecommendationSolver> solverRef = new AtomicReference<>();
        
        // 立即启动进度更新线程，使用真实的求解器进度
        startProgressUpdateThread(startTime, timeLimitMs, solverRef);

        // 将所有耗时操作移至后台线程
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            PerformanceLogger.logPerformancePoint("SOLVER_THREAD", "后台处理线程开始执行");

            // 1. 收集数据：课程 (原主线程逻辑移入后台)
            List<Course> rawCourses = null;
            if (homeViewModel != null && homeViewModel.courses != null) {
                rawCourses = homeViewModel.courses.getValue();
            }
            List<Course> tempCourses = rawCourses == null ? new ArrayList<>() : new ArrayList<>(rawCourses);

            // UCourse_DUMP: Print Input Courses
            Log.d(TAG, "DUMP_DEBUG: === INPUT COURSES START ===");
            if (tempCourses != null) {
                for (Course c : tempCourses) {
                    if (c != null) {
                        Log.d(TAG, String.format("DUMP_DEBUG: Course[id=%s, title=%s, interest=%d, time=%s]", 
                            c.id, c.title, c.interest, c.rawTime));
                    }
                }
            }
            Log.d(TAG, "DUMP_DEBUG: === INPUT COURSES END ===");

            // 记录传播学研究方法课程在过滤过期课程前的信息
            for (Course c : tempCourses) {
                if (c != null && c.title != null && c.title.contains("传播学研究方法")) {
                    Log.d(TAG, "SPECIAL_DEBUG: 过滤过期课程前 - 传播学研究方法课程信息: 标题=" + c.title + ", 兴趣分=" + c.interest + ", 原始时间=" + c.rawTime + ", ID=" + c.id);
                }
            }
            
            // 1. 获取TimeFragment中的WeekTimeData (提前获取用于过滤)
            long freeTimeStartTime = System.currentTimeMillis();
            WeekTimeData freeTimes = null;
            try {
                android.content.SharedPreferences prefs = requireContext().getSharedPreferences("TimeTablePrefs", android.content.Context.MODE_PRIVATE);
                String json = prefs.getString("week_data", null);
                if (json != null) {
                    freeTimes = new com.google.gson.Gson().fromJson(json, WeekTimeData.class);
                }
            } catch (Throwable _t) { /* ignore */ }
            if (freeTimes == null) freeTimes = new WeekTimeData();
            final WeekTimeData finalFreeTimes = freeTimes;
            
            // 记录空闲时间信息
            Log.d(TAG, "GENERATE_DEBUG: 空闲时间数据状态: " + (freeTimes.getWeekStartDate() != null ? "包含日期信息" : "无日期信息"));
            if (freeTimes != null) {
                String[] weekDays = {"一", "二", "三", "四", "五", "六", "日"};
                for (int day = 0; day < 7; day++) {
                    List<WeekTimeData.TimeRange> ranges = freeTimes.getDateTimeRangesByDayIndex(day);
                    if (ranges == null || ranges.isEmpty()) {
                        ranges = freeTimes.getGenericTimeRangesByDayIndex(day);
                    }
                    if (ranges != null && !ranges.isEmpty()) {
                        for (WeekTimeData.TimeRange range : ranges) {
                            Log.d(TAG, "GENERATE_DEBUG: 星期" + weekDays[day] + "空闲时间: " + range.getStartSection() + "-" + range.getEndSection());
                        }
                    }
                }
            }
            PerformanceLogger.logPerformancePoint("TIME_PROCESSING", "获取空闲时间完成 - 耗时: " + (System.currentTimeMillis() - freeTimeStartTime) + "ms");

            // Filter courses based on Interest, Target Week, and Free Time
            try {
                android.content.SharedPreferences prefs = requireContext().getSharedPreferences("ucourse_prefs", android.content.Context.MODE_PRIVATE);
                String firstWeekStr = prefs.getString("first_week_date", null);
                
                // 计算目标周次
                int targetWeekIndex = -1;
                if (firstWeekStr != null && finalFreeTimes.getWeekStartDate() != null) {
                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault());
                    java.util.Date firstWeekDate = sdf.parse(firstWeekStr);
                    java.util.Date targetDate = finalFreeTimes.getWeekStartDate();
                    
                    long diff = targetDate.getTime() - firstWeekDate.getTime();
                    long weeks = diff / (7L * 24 * 3600 * 1000);
                    targetWeekIndex = (int) weeks + 1;
                    
                    Log.d(TAG, "FILTER_DEBUG: 目标周计算 - 开学日期: " + firstWeekStr + ", 目标日期: " + sdf.format(targetDate) + ", 目标周次: " + targetWeekIndex);
                } else {
                    Log.d(TAG, "FILTER_DEBUG: 无法计算目标周次 - 缺少开学日期或目标周日期");
                }

                java.util.Iterator<Course> it = tempCourses.iterator();
                while (it.hasNext()) {
                    Course c = it.next();
                    boolean isCommCourse = (c != null && c.title != null && c.title.contains("传播学研究方法"));
                    
                    // 1. Check Interest (Must be > 0)
                if (c.interest <= 0) {
                    Log.d(TAG, "FILTER_DEBUG: 课程 " + c.title + " 因兴趣分(" + c.interest + ")<=0 被移除");
                    it.remove();
                    continue;
                }

                    // 2. Check Target Week
                    if (targetWeekIndex != -1) {
                         try {
                            int[] weeks = com.pku.or.ucourse.result.CourseTimeParser.parseWeekRange(c.rawTime);
                            int startWeek = weeks[0];
                            int endWeek = weeks[1];
                            
                            if (targetWeekIndex < startWeek || targetWeekIndex > endWeek) {
                                if (isCommCourse) Log.d(TAG, "FILTER_DEBUG: 传播学研究方法不在目标周(" + targetWeekIndex + ")范围内(" + startWeek + "-" + endWeek + ")");
                                Log.d(TAG, "FILTER_DEBUG: 课程 " + c.title + " 不在目标周(" + targetWeekIndex + ")范围内(" + startWeek + "-" + endWeek + ") 被移除");
                                it.remove();
                                continue;
                            }
                         } catch (Exception e) {
                             Log.d(TAG, "FILTER_DEBUG: 解析课程 " + c.title + " 周次失败: " + e.getMessage());
                         }
                    }

                    // 3. Check Free Time Compatibility
                    // Check if the course's time slots are FULLY contained in the Free Time slots for that day
                    try {
                        List<com.pku.or.ucourse.view.TimeSlot> slots = com.pku.or.ucourse.result.CourseTimeParser.parse(c.rawTime);
                        boolean isCompatible = true;
                        
                        for (com.pku.or.ucourse.view.TimeSlot slot : slots) {
                            int day = slot.getDay(); // 0-6 (Mon-Sun) -> Wait, parser returns 1-7?
                            // Parser usually maps 周一->1? No, let's check parser.
                            // Looking at TimeTableView, day index is 0-6.
                            // Looking at CourseTimeParser, toDayIndex("一") returns 0?
                            // Need to verify. Assuming 0-6 for now based on standard usage.
                            
                            // Let's assume standard day index 0-6.
                            // Use generic lookup to match by Day of Week regardless of week start date alignment
                            List<WeekTimeData.TimeRange> freeRanges = finalFreeTimes.getGenericTimeRangesByDayIndex(day);
                            
                            if (freeRanges == null || freeRanges.isEmpty()) {
                                // No free time set for this day -> Assume user is free all day (Consistent with RecommendationSolver)
                                // If list is empty, it means no constraints were set for this day.
                                Log.d(TAG, "FILTER_DEBUG: 课程 " + c.title + " 在星期" + day + "无空闲时间设置，默认允许");
                                continue;
                            }
                            
                            boolean slotCovered = false;
                            for (WeekTimeData.TimeRange free : freeRanges) {
                                if (slot.getStartSection() >= free.getStartSection() && slot.getEndSection() <= free.getEndSection()) {
                                    slotCovered = true;
                                    break;
                                }
                            }
                            
                            if (!slotCovered) {
                                isCompatible = false;
                                if (isCommCourse) Log.d(TAG, "FILTER_DEBUG: 传播学研究方法时间冲突: 星期" + day + " " + slot.getStartSection() + "-" + slot.getEndSection());
                                break;
                            }
                        }
                        
                        if (!isCompatible) {
                            Log.d(TAG, "FILTER_DEBUG: 课程 " + c.title + " 不在空闲时间范围内被移除");
                            it.remove();
                            continue;
                        }
                        
                    } catch (Exception e) {
                        Log.d(TAG, "FILTER_DEBUG: 解析课程 " + c.title + " 时间槽失败: " + e.getMessage());
                    }
                    
                    if (isCommCourse) {
                        Log.d(TAG, "FILTER_DEBUG: 传播学研究方法课程通过所有过滤保留");
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
                Log.d(TAG, "FILTER_DEBUG: 课程过滤出现异常: " + e.getMessage());
            }

            final List<Course> courses = tempCourses;
            Log.d(TAG, "GENERATE_DEBUG: 原始课程数: " + rawCourses.size() + ", 过滤后: " + courses.size());
            // 记录兴趣分>0的课程信息
            for (Course course : courses) {
                Log.d(TAG, "GENERATE_DEBUG: 保留课程: " + course.title + ", 兴趣分: " + course.interest + ", 时间: " + course.rawTime);
            }
            PerformanceLogger.logPerformancePoint("COURSE_PROCESSING", "获取课程总数: " + courses.size());

            // DUMP_DEBUG: Print Input Time (Moved logic to match format)
            Log.d(TAG, "DUMP_DEBUG: === INPUT TIME START ===");
            if (finalFreeTimes != null) {
                String[] weekDays = {"Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"};
                for (int day = 0; day < 7; day++) {
                    List<WeekTimeData.TimeRange> ranges = finalFreeTimes.getDateTimeRangesByDayIndex(day);
                    if (ranges == null || ranges.isEmpty()) {
                        ranges = finalFreeTimes.getGenericTimeRangesByDayIndex(day);
                    }
                    if (ranges != null && !ranges.isEmpty()) {
                        for (WeekTimeData.TimeRange range : ranges) {
                            Log.d(TAG, String.format("DUMP_DEBUG: Time[day=%s, start=%d, end=%d]", 
                                weekDays[day], range.getStartSection(), range.getEndSection()));
                        }
                    }
                }
            } else {
                Log.d(TAG, "DUMP_DEBUG: Time data is null");
            }
            Log.d(TAG, "DUMP_DEBUG: === INPUT TIME END ===");


            
            // 筛选课程：排除没有课程名和没有课程时间的课程
            List<Course> filteredCourses = new ArrayList<>();
            long filterStartTime = System.currentTimeMillis();
            for (Course course : courses) {
                boolean shouldAdd = false;
                if (course != null) {
                    // 特别记录传播学研究方法课程的信息，无论title是否为空
                    boolean isCommCourse = false;
                    if (course.title != null && course.title.contains("传播学研究方法")) {
                        isCommCourse = true;
                    } else if (course.id != null && course.id.contains("传播学研究方法")) {
                        isCommCourse = true;
                    }
                    
                    if (isCommCourse) {
                        Log.d(TAG, "SPECIAL_DEBUG: 传播学研究方法课程信息: 标题=" + course.title + ", ID=" + course.id + ", 兴趣分=" + course.interest + ", 原始时间=" + course.rawTime + ", 标题是否为空=" + (course.title == null || course.title.trim().isEmpty()) + ", 时间是否为空=" + (course.rawTime == null || course.rawTime.trim().isEmpty()));
                    }
                    
                    // 修改筛选逻辑：用户设置了兴趣分的课程即使rawTime为空也能被保留
                    // 这确保用户明确想要的课程不会因为时间信息缺失而被过滤掉
                    if ((course.title != null && !course.title.trim().isEmpty()) && 
                        (course.rawTime != null && !course.rawTime.trim().isEmpty() || course.interest > 0)) {
                        filteredCourses.add(course);
                        shouldAdd = true;
                        
                        if (isCommCourse) {
                            Log.d(TAG, "SPECIAL_DEBUG: 传播学研究方法课程被添加到筛选列表");
                        }
                    } else {
                        if (isCommCourse) {
                            Log.d(TAG, "SPECIAL_DEBUG: 传播学研究方法课程未被添加到筛选列表");
                        }
                    }
                }
            }
            PerformanceLogger.logPerformancePoint("COURSE_PROCESSING", "课程筛选完成 - 保留课程数: " + filteredCourses.size() + ", 耗时: " + 
                  (System.currentTimeMillis() - filterStartTime) + "ms");
            
            // 记录筛选后的课程信息
            Log.d(TAG, "GENERATE_DEBUG: 筛选后课程数: " + filteredCourses.size());
            for (Course course : filteredCourses) {
                Log.d(TAG, "GENERATE_DEBUG: 筛选后课程: " + course.title + ", 兴趣分: " + course.interest + ", 时间: " + course.rawTime + ", ID: " + course.id);
                // 特别记录传播学研究方法课程的筛选后信息
                if (course.title.contains("传播学研究方法")) {
                    Log.d(TAG, "SPECIAL_DEBUG: 传播学研究方法课程被成功筛选!");
                }
            }
            
            // 创建课程分组：将相同时间和分数的课程分为一组
            Map<String, List<Course>> courseGroups = new HashMap<>();
            Map<String, List<TimeSlot>> tempMapping = new HashMap<>();
            long groupingStartTime = System.currentTimeMillis();
            
            for (Course course : filteredCourses) {
                List<TimeSlot> slots = CourseTimeParser.parse(course.rawTime);
                tempMapping.put(course.id, slots);
                
                // 创建组键：分数 + 时间槽（作为唯一标识）
                StringBuilder keyBuilder = new StringBuilder();
                keyBuilder.append(course.interest).append("_");
                
                // 为时间槽生成唯一标识
                List<String> slotKeys = new ArrayList<>();
                for (TimeSlot slot : slots) {
                    slotKeys.add(slot.getDay() + "_" + slot.getStartSection() + "_" + slot.getEndSection());
                }
                Collections.sort(slotKeys); // 确保顺序一致
                for (String slotKey : slotKeys) {
                    keyBuilder.append(slotKey).append(";");
                }
                
                String groupKey = keyBuilder.toString();
                if (!courseGroups.containsKey(groupKey)) {
                    courseGroups.put(groupKey, new ArrayList<>());
                }
                courseGroups.get(groupKey).add(course);
                Log.d(TAG, "GROUP_DEBUG: 课程分组: " + course.title + " -> 组键: " + groupKey);
                
                // 特别记录传播学研究方法课程的分组信息
                if (course.title.contains("传播学研究方法")) {
                    Log.d(TAG, "SPECIAL_DEBUG: 传播学研究方法课程分组信息: 组键=" + groupKey + ", 解析的时间槽数量=" + slots.size() + ", 时间槽详情=" + slots.toString());
                }
            }
            
            // 记录分组结果
            Log.d(TAG, "GROUP_DEBUG: 分组数: " + courseGroups.size());
            for (Map.Entry<String, List<Course>> entry : courseGroups.entrySet()) {
                List<Course> group = entry.getValue();
                Log.d(TAG, "GROUP_DEBUG: 组键: " + entry.getKey() + ", 包含课程: " + group.size() + "个");
                for (Course course : group) {
                    Log.d(TAG, "GROUP_DEBUG:   - " + course.title + ", 兴趣分: " + course.interest);
                }
            }
            PerformanceLogger.logPerformancePoint("COURSE_PROCESSING", "课程分组完成 - 分组数: " + courseGroups.size() + ", 耗时: " +
                        (System.currentTimeMillis() - groupingStartTime) + "ms");
            
            // 创建组课程和组映射
            List<Course> groupedCourses = new ArrayList<>();
            Map<String, List<TimeSlot>> groupMapping = new HashMap<>();
            Map<String, List<Course>> groupToOriginalCourses = new HashMap<>(); // 保存组到原始课程的映射
            
            for (Map.Entry<String, List<Course>> entry : courseGroups.entrySet()) {
                List<Course> group = entry.getValue();
                // 创建组代表课程
                Course groupCourse = new Course();
                Course firstCourse = group.get(0);
                groupCourse.id = "group_" + entry.getKey().hashCode(); // 生成组ID
                groupCourse.title = firstCourse.title + " (" + group.size() + "个可选)";
                groupCourse.interest = firstCourse.interest;
                groupCourse.rawTime = firstCourse.rawTime;
                
                groupedCourses.add(groupCourse);
                groupMapping.put(groupCourse.id, tempMapping.get(firstCourse.id));
                groupToOriginalCourses.put(groupCourse.id, group);
                
                Log.d(TAG, "GROUP_DEBUG: 创建组代表课程: " + groupCourse.title + ", 兴趣分: " + groupCourse.interest + ", 时间: " + groupCourse.rawTime + ", 组ID: " + groupCourse.id);
                Log.d(TAG, "GROUP_DEBUG:   组代表基于: " + firstCourse.title + ", 原始兴趣分: " + firstCourse.interest);
                
                // 特别记录包含传播学研究方法课程的组信息
                boolean containsCommCourse = false;
                for (Course c : group) {
                    if (c.title.contains("传播学研究方法")) {
                        containsCommCourse = true;
                        break;
                    }
                }
                if (containsCommCourse) {
                    Log.d(TAG, "SPECIAL_DEBUG: 包含传播学研究方法的组信息: 组键=" + entry.getKey() + ", 组代表标题=" + groupCourse.title + ", 组包含课程数=" + group.size());
                    for (Course c : group) {
                        Log.d(TAG, "SPECIAL_DEBUG:   组内课程: " + c.title + ", 兴趣分=" + c.interest);
                    }
                }
            }
            
            // 记录最终用于求解的课程
            Log.d(TAG, "GROUP_DEBUG: 最终用于求解的课程数: " + groupedCourses.size());
            for (Course course : groupedCourses) {
                Log.d(TAG, "GROUP_DEBUG: 求解课程: " + course.title + ", 兴趣分: " + course.interest + ", 时间: " + course.rawTime + ", ID: " + course.id);
                // 特别记录包含传播学研究方法的组代表课程
                if (course.title.contains("传播学研究方法")) {
                    Log.d(TAG, "SPECIAL_DEBUG: 包含传播学研究方法的组代表课程被加入求解列表!");
                }
            }
            
            final List<Course> finalCourses = groupedCourses;
            // 保存组到原始课程的映射供后续使用
            ResultFragment.this.groupToOriginalCourses = groupToOriginalCourses;

            // 使用分组后的映射
            final Map<String, List<TimeSlot>> mapping = groupMapping;
            // 保存映射供createSolutionCard使用
            ResultFragment.this.lastCourseMapping = mapping;

            
            // 记录求解器初始化时间
            long solverInitStartTime = System.currentTimeMillis();
            RecommendationSolver solver = new RecommendationSolver(finalCourses, mapping, finalFreeTimes, maxRecommendations, timeLimitMs);
            solverRef.set(solver); // Share solver instance for progress tracking
            
            PerformanceLogger.logPerformancePoint("SOLVER_PROCESS", "求解器初始化完成 - 耗时: " + (System.currentTimeMillis() - solverInitStartTime) +
                    "ms");
            
            // 记录求解过程时间
            long solveStartTime = System.currentTimeMillis();
            List<RecommendationSolver.Solution> rawSolutions = solver.solve();
            long solveEndTime = System.currentTimeMillis();
            
            // 过滤子集方案 (Subset Filtering)
            // 如果方案A的所有课程ID集合是方案B的子集，且A的分数<=B (通常是<)，则A是多余的。
            // 由于求解器返回的方案通常按分数降序排列，我们保留分数高的超集。
            List<RecommendationSolver.Solution> solutions = new ArrayList<>();
            if (rawSolutions != null) {
                // 先按分数降序排序 (Solver应该已经排好了，但为了保险)
                Collections.sort(rawSolutions, (a, b) -> Integer.compare(b.totalScore, a.totalScore));
                
                for (int i = 0; i < rawSolutions.size(); i++) {
                    RecommendationSolver.Solution candidate = rawSolutions.get(i);
                    boolean isSubset = false;
                    Set<String> candidateIds = new HashSet<>();
                    for(Course c : candidate.courses) candidateIds.add(c.id);
                    
                    for (int j = 0; j < rawSolutions.size(); j++) {
                        if (i == j) continue;
                        RecommendationSolver.Solution other = rawSolutions.get(j);
                        
                        // 如果candidate是other的真子集 (即所有candidate的课程都在other中，且other有更多课程)
                        // 或者如果完全相同 (去重)
                        Set<String> otherIds = new HashSet<>();
                        for(Course c : other.courses) otherIds.add(c.id);
                        
                        if (otherIds.containsAll(candidateIds)) {
                            // candidate is subset or equal
                            if (otherIds.size() > candidateIds.size()) {
                                isSubset = true; // Strict subset
                                break;
                            } else if (otherIds.size() == candidateIds.size()) {
                                // Equal sets. Keep only the one with lower index (first one encountered)
                                if (j < i) {
                                    isSubset = true; // Duplicate
                                    break;
                                }
                            }
                        }
                    }
                    
                    if (!isSubset) {
                        solutions.add(candidate);
                    }
                }
            }

            PerformanceLogger.logPerformancePoint("SOLVER_PROCESS", "求解算法完成 - 耗时: " + (solveEndTime - solveStartTime) + "ms, 生成方案数: " + (solutions != null ? solutions.size() : 0));
            
            // 记录课程还原时间
            long restoreStartTime = System.currentTimeMillis();
            // 还原解决方案中的组课程为原始课程
            if (solutions != null) {
                for (RecommendationSolver.Solution solution : solutions) {
                    List<Course> originalCourses = new ArrayList<>();
                    for (Course groupCourse : solution.courses) {
                        if (groupCourse.id.startsWith("group_")) {
                            // 从映射中获取原始课程列表并添加所有课程
                            List<Course> groupOriginals = groupToOriginalCourses.get(groupCourse.id);
                            if (groupOriginals != null && !groupOriginals.isEmpty()) {
                                // 添加所有原始课程，而不仅仅是第一个
                                originalCourses.addAll(groupOriginals);
                            }
                        } else {
                            // 非组课程直接添加
                            originalCourses.add(groupCourse);
                        }
                    }
                    // 替换课程列表 - 放在内部循环结束后
                    solution.courses = originalCourses;
                }
            }
            PerformanceLogger.logPerformancePoint("SOLVER_PROCESS", "课程还原完成 - 耗时: " + (System.currentTimeMillis() - restoreStartTime) +
                    "ms");

            // DUMP_DEBUG: Print Output Results
            Log.d(TAG, "DUMP_DEBUG: === OUTPUT RESULTS START ===");
            if (solutions != null) {
                for (int i = 0; i < solutions.size(); i++) {
                    RecommendationSolver.Solution sol = solutions.get(i);
                    Log.d(TAG, String.format("DUMP_DEBUG: Solution[%d] Score=%d", i + 1, sol.totalScore));
                    if (sol.courses != null) {
                        for (Course c : sol.courses) {
                            if (c != null) {
                                Log.d(TAG, String.format("DUMP_DEBUG:   -> Course[title=%s, interest=%d, time=%s]", 
                                    c.title, c.interest, c.rawTime));
                            }
                        }
                    }
                }
            } else {
                Log.d(TAG, "DUMP_DEBUG: No solutions found");
            }
            Log.d(TAG, "DUMP_DEBUG: === OUTPUT RESULTS END ===");

            // 在主线程更新UI
            new Handler(Looper.getMainLooper()).post(() -> {
                // Stop progress thread immediately
                if (progressThread != null) {
                    progressThread.interrupt();
                    progressThread = null;
                }

                // Show 100% then hide
                if (progressBar != null) {
                    progressBar.setProgress(100);
                    progressBar.postDelayed(() -> progressBar.setVisibility(View.GONE), 200);
                }

               final long uiUpdateStartTime = System.currentTimeMillis();
                PerformanceLogger.logPerformancePoint("UI_UPDATE", "开始UI更新");

                // 计算课程频率
                courseFrequency.clear();
                totalSolutionsCount = solutions != null ? solutions.size() : 0;
                if (solutions != null) {
                    for (RecommendationSolver.Solution solution : solutions) {
                        if (solution.courses != null) {
                            for (Course c : solution.courses) {
                                if (c != null) {
                                    Integer count = courseFrequency.get(c.id);
                                    courseFrequency.put(c.id, (count != null ? count : 0) + 1);
                                }
                            }
                        }
                    }
                }
                
                // 立即处理完成状态，减少用户等待感
                long completeTime = System.currentTimeMillis();
                PerformanceLogger.logProgress("RECOMMENDATION_PROCESS", 100, (completeTime - startTime));
                
                // 此处不隐藏进度条，等待卡片完全渲染后再隐藏
                
                // 设置按钮为完成状态
                if (btnGenerate != null) {
                    btnGenerate.setEnabled(true);
                    btnGenerate.setText("生成推荐方案");
                }
                
                PerformanceLogger.logPerformancePoint("GENERATE_RECOMMENDATIONS", "整个推荐过程完成 - 总耗时: " + (System.currentTimeMillis() - overallStartTime) +
                "ms");

                // 完全移除所有方法说明和求解信息，直接处理解决方案

                // 如果没有解决方案
                if (solutions == null || solutions.isEmpty()) {
                    TextView emptyText = new TextView(requireContext());
                    emptyText.setText("没有找到合适的推荐方案，请尝试调整空闲时间或课程选择");
                    emptyText.setTextSize(16);
                    emptyText.setPadding(20, 20, 20, 20);
                    if (container != null) {
                        container.addView(emptyText);
                    }
                    return;
                }

                PerformanceLogger.logPerformancePoint("UI_UPDATE", "开始创建解决方案卡片");
            // 创建解决方案卡片
            int idx = 1;
            RecommendationSolver.Solution bestSolution = solutions.get(0);
            final List<RecommendationSolver.Solution> allSolutions = solutions;
            for (RecommendationSolver.Solution s : solutions) {
                    View card = createSolutionCard(s, idx++, bestSolution, allSolutions);
                    if (container != null) {
                        // 添加动画效果
                        card.setAlpha(0f);
                        card.setScaleX(0.97f);
                        card.setScaleY(0.97f);
                        container.addView(card);
                        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(280).start();
                    }
                }
            });
        });
    }

    /**
     * 创建解决方案卡片
     */
    /**
     * 启动进度更新线程，实时更新进度条
     */
    private void startProgressUpdateThread(final long startTime, final int timeLimitMs, final AtomicReference<RecommendationSolver> solverRef) {
        PerformanceLogger.logPerformancePoint("PROGRESS_THREAD", "进度更新线程启动，时间限制设置为: " + timeLimitMs + "ms");
        
        // 如果已有进度线程在运行，先中断它
        if (progressThread != null && progressThread.isAlive()) {
            progressThread.interrupt();
        }
        
        progressThread = new Thread(() -> {
            try {
                // Phase 1: Waiting for solver initialization (0-5%)
                int initProgress = 0;
                while (solverRef.get() == null && !Thread.interrupted()) {
                    if (initProgress < 5) {
                        initProgress++;
                        final int p = initProgress;
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                if (progressBar != null) {
                                    progressBar.setVisibility(View.VISIBLE);
                                    progressBar.setProgress(p);
                                }
                            });
                        }
                    }
                    Thread.sleep(100);
                }

                // Phase 2: Solver running (5-99%)
                while (!Thread.interrupted()) {
                    RecommendationSolver solver = solverRef.get();
                    if (solver != null) {
                        int realProgress = solver.getProgress(); // 0-100
                        // Map 0-100 to 5-99
                        int displayProgress = 5 + (int)(realProgress * 0.94);
                        if (displayProgress > 99) displayProgress = 99;
                        
                        final int progress = displayProgress;
                        
                        if (getActivity() != null) {
                            getActivity().runOnUiThread(() -> {
                                if (progressBar != null) {
                                    progressBar.setProgress(progress);
                                }
                            });
                        }
                    }
                    
                    Thread.sleep(50); 
                }
            } catch (InterruptedException e) {
                // 线程中断，正常退出
                PerformanceLogger.logPerformancePoint("PROGRESS_THREAD", "进度更新线程中断");
                Thread.currentThread().interrupt();
            }
        });
        progressThread.start();
    }
    

    
    /**
     * 计算方案差异
     */
    private CharSequence calculateDiff(RecommendationSolver.Solution current, RecommendationSolver.Solution reference) {
        if (current == reference || reference == null) {
            return "综合评分最高的方案";
        }
        
        // 找出新增的课程（在当前方案中但不在参考方案中）
        List<String> added = new ArrayList<>();
        Set<String> refIds = new HashSet<>();
        for (Course c : reference.courses) if (c != null) refIds.add(c.id);
        
        for (Course c : current.courses) {
            if (c != null && !refIds.contains(c.id)) {
                String title = c.title == null ? "未知课程" : c.title;
                if (title.length() > 8) title = title.substring(0, 8) + "...";
                added.add(title);
            }
        }
        
        // 找出移除的课程（在参考方案中但不在当前方案中）
        List<String> removed = new ArrayList<>();
        Set<String> curIds = new HashSet<>();
        for (Course c : current.courses) if (c != null) curIds.add(c.id);
        
        for (Course c : reference.courses) {
            if (c != null && !curIds.contains(c.id)) {
                String title = c.title == null ? "未知课程" : c.title;
                if (title.length() > 8) title = title.substring(0, 8) + "...";
                removed.add(title);
            }
        }
        
        android.text.SpannableStringBuilder ssb = new android.text.SpannableStringBuilder();
        
        if (!added.isEmpty()) {
            for (int i = 0; i < added.size(); i++) {
                if (i > 0) ssb.append("  ");
                String item = "+ " + added.get(i);
                int start = ssb.length();
                ssb.append(item);
                ssb.setSpan(new android.text.style.ForegroundColorSpan(0xFF4CAF50), start, start + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); // Green +
            }
        }
        
        if (!removed.isEmpty()) {
            if (ssb.length() > 0) ssb.append("\n");
            for (int i = 0; i < removed.size(); i++) {
                if (i > 0) ssb.append("  ");
                String item = "- " + removed.get(i);
                int start = ssb.length();
                ssb.append(item);
                ssb.setSpan(new android.text.style.ForegroundColorSpan(0xFFFF5252), start, start + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE); // Red -
            }
        }
        
        if (ssb.length() == 0) return "与该方案课程一致";
        return ssb;
    }

    private View createSolutionCard(RecommendationSolver.Solution s, int rank, RecommendationSolver.Solution bestSolution, List<RecommendationSolver.Solution> allSolutions) {
        LayoutInflater inf = LayoutInflater.from(requireContext());
        View row = inf.inflate(R.layout.item_solution_card, container, false);
        TextView tvTitle = row.findViewById(R.id.tv_solution_title);
        TextView tvScore = row.findViewById(R.id.tv_solution_score);
        TextView tvDiff = row.findViewById(R.id.tv_solution_diff);
        RecyclerView rvSegments = row.findViewById(R.id.rv_segments);
        Button btnDiff = row.findViewById(R.id.btn_diff);

        tvTitle.setText("方案 " + rank);
        tvScore.setText(String.valueOf(s.totalScore));
        
        // 初始状态下隐藏 Diff 摘要
        if (tvDiff != null) {
            tvDiff.setVisibility(View.GONE);
        }

        // Setup Diff Button
        if (allSolutions != null && allSolutions.size() > 1) {
            btnDiff.setVisibility(View.VISIBLE);
            
            // 定义点击事件处理逻辑
            View.OnClickListener diffClickListener = new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String[] items = new String[allSolutions.size() - 1];
                    final RecommendationSolver.Solution[] targets = new RecommendationSolver.Solution[allSolutions.size() - 1];
                    int k = 0;
                    for (int i = 0; i < allSolutions.size(); i++) {
                        if (allSolutions.get(i) != s) {
                            items[k] = "方案 " + (i + 1);
                            targets[k] = allSolutions.get(i);
                            k++;
                        }
                    }
                    
                    new androidx.appcompat.app.AlertDialog.Builder(requireContext())
                        .setTitle("与哪个方案对比？")
                        .setItems(items, (dialog, which) -> {
                            RecommendationSolver.Solution target = targets[which];
                            CharSequence diffText = calculateDiff(s, target);
                            
                            // 更新 Diff 摘要 - 用户要求移除此区域 (Area 1)
                            /* 
                            if (tvDiff != null) {
                                tvDiff.setText(diffText);
                                tvDiff.setVisibility(View.VISIBLE);
                            }
                            */
                            if (tvDiff != null) {
                                tvDiff.setVisibility(View.GONE);
                            }
                            
                            // 切换到恢复模式
                            btnDiff.setText("REST");
                            // 使用白色字体配合淡蓝色背景
                            btnDiff.setTextColor(android.graphics.Color.WHITE);
                            // 清除背景Tint，避免主题accent影响淡蓝色
                            try { ViewCompat.setBackgroundTintList(btnDiff, null); } catch (Throwable _t) {}
                            // 保存Padding防止背景重置导致变形
                            int pLeft = btnDiff.getPaddingLeft();
                            int pTop = btnDiff.getPaddingTop();
                            int pRight = btnDiff.getPaddingRight();
                            int pBottom = btnDiff.getPaddingBottom();
                            btnDiff.setBackgroundResource(R.drawable.bg_diff_button_restore);
                            btnDiff.setPadding(pLeft, pTop, pRight, pBottom);
                            
                            // 构建 Diff 数据
                            Map<String, Integer> diffStatus = new HashMap<>();
                            List<Course> combinedCourses = new ArrayList<>();
                            Set<String> processedIds = new HashSet<>();
                            
                            // 1. 添加当前方案的课程 (Added or Same)
                            Set<String> targetIds = new HashSet<>();
                            for (Course c : target.courses) if (c != null) targetIds.add(c.id);
                            
                            for (Course c : s.courses) {
                                if (c != null) {
                                    if (!targetIds.contains(c.id)) {
                                        diffStatus.put(c.id, 1); // Added
                                    } else {
                                        diffStatus.put(c.id, 0); // Same
                                    }
                                    combinedCourses.add(c);
                                    processedIds.add(c.id);
                                }
                            }
                            
                            // 2. 添加目标方案中有但当前方案没有的课程 (Removed)
                            for (Course c : target.courses) {
                                if (c != null && !processedIds.contains(c.id)) {
                                    diffStatus.put(c.id, 2); // Removed
                                    combinedCourses.add(c);
                                }
                            }
                            
                            // 重新构建并显示时间段
                            List<Segment> diffSegments = buildSegmentsForSolution(combinedCourses, diffStatus);
                            if (rvSegments != null) {
                                rvSegments.setAdapter(new SegmentAdapter(diffSegments, diffStatus));
                            }
                            
                            // 设置恢复点击事件
                            btnDiff.setOnClickListener(v2 -> {
                                // 恢复初始状态
                                btnDiff.setText("DIFF");
                                // 保存Padding
                                int pL = btnDiff.getPaddingLeft();
                                int pT = btnDiff.getPaddingTop();
                                int pR = btnDiff.getPaddingRight();
                                int pB = btnDiff.getPaddingBottom();
                                btnDiff.setBackgroundResource(0); // 透明背景
                                btnDiff.setPadding(pL, pT, pR, pB);
                                btnDiff.setTextColor(0xFF666666);
                                try { ViewCompat.setBackgroundTintList(btnDiff, null); } catch (Throwable _t) {}
                                if (tvDiff != null) tvDiff.setVisibility(View.GONE);
                                
                                // 恢复原始课程列表
                                List<Segment> originalSegments = buildSegmentsForSolution(s.courses, null);
                                if (rvSegments != null) {
                                    rvSegments.setAdapter(new SegmentAdapter(originalSegments, null));
                                }
                                
                                // 恢复 Diff 按钮点击事件
                                btnDiff.setOnClickListener(this);
                            });
                        })
                        .show();
                }
            };
            
            btnDiff.setOnClickListener(diffClickListener);
        } else {
            btnDiff.setVisibility(View.GONE);
        }

        // 构建时间段 (初始显示)
        List<Segment> segments = buildSegmentsForSolution(s.courses, null);

        // 设置水平RecyclerView
        if (rvSegments != null) {
            rvSegments.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
            rvSegments.setAdapter(new SegmentAdapter(segments, null));
        }

        return row;
    }

    /**
     * 构建解决方案的时间段
     * @param courses 课程列表
     * @param diffStatus 差异状态 (null表示正常显示，非null表示Diff模式)
     */
    private List<Segment> buildSegmentsForSolution(List<Course> courses, Map<String, Integer> diffStatus) {
        // 使用Map数据结构：Key(Day_Start_End) -> Segment
        Map<String, Segment> segmentMap = new HashMap<>();
        
        if (courses != null) {
            // 先收集所有相关课程（Diff模式下过滤）
            List<Course> coursesToShow = new ArrayList<>();
            if (diffStatus != null) {
                // Diff模式：只显示有差异的课程 (Added or Removed)
                for (Course c : courses) {
                    if (c != null && diffStatus.containsKey(c.id)) {
                         int status = diffStatus.get(c.id);
                         if (status == 1 || status == 2) {
                             coursesToShow.add(c);
                         }
                    }
                }
            } else {
                // 正常模式：显示所有课程
                coursesToShow.addAll(courses);
            }

            for (Course course : coursesToShow) {
                if (course == null) continue;
                
                List<TimeSlot> slots = CourseTimeParser.parse(course.rawTime);
                if (slots == null) continue;
                
                for (TimeSlot ts : slots) {
                    int day = ts.getDay();
                    int start = ts.getStartSection();
                    int end = ts.getEndSection();
                    
                    // 使用唯一Key: day_start_end
                    String key = day + "_" + start + "_" + end;
                    
                    if (!segmentMap.containsKey(key)) {
                        Segment seg = new Segment();
                        seg.id = key;
                        seg.day = day;
                        seg.start = start;
                        seg.end = end;
                        segmentMap.put(key, seg);
                    }
                    
                    Segment seg = segmentMap.get(key);
                    // 避免重复添加同一课程
                    boolean exists = false;
                    for (Course xc : seg.courses) {
                        if (xc.id.equals(course.id)) { exists = true; break; }
                    }
                    if (!exists) seg.courses.add(course);
                }
            }
        }

        // 构建输出列表并排序
        List<Segment> out = new ArrayList<>(segmentMap.values());
        Collections.sort(out, (o1, o2) -> {
            if (o1.day != o2.day) return Integer.compare(o1.day, o2.day);
            return Integer.compare(o1.start, o2.start);
        });

        return out;
    }

    /**
     * 根据ID查找课程
     */
    private Course findCourseById(String courseId) {
        List<Course> all = homeViewModel.courses.getValue();
        if (all != null) {
            for (Course cc : all) if (cc != null && courseId.equals(cc.id)) { return cc; }
        }
        return null;
    }

    /**
     * 时间段类
     */
    private static class Segment {
        String id;
        int day;
        int start;
        int end;
        List<Course> courses = new ArrayList<>();
        
        String title() {
            String dStr = dayName(day);
            String sStr = String.valueOf(start + 1);
            if (end > start) {
                sStr += "-" + (end + 1);
            }
            return "周" + dStr + " " + sStr + "节";
        }
        
        private String dayName(int d) {
            switch (d) {
                case 0: return "一";
                case 1: return "二";
                case 2: return "三";
                case 3: return "四";
                case 4: return "五";
                case 5: return "六";
                case 6: return "日";
                default: return String.valueOf(d + 1);
            }
        }
    }

    /**
     * 时间段适配器
     */
    private class SegmentAdapter extends RecyclerView.Adapter<SegmentAdapter.SV> {
        private final List<Segment> items;
        private final Map<String, Integer> diffStatus;
        
        SegmentAdapter(List<Segment> items, Map<String, Integer> diffStatus) { 
            this.items = items == null ? new ArrayList<>() : items;
            this.diffStatus = diffStatus;
        }
        
        class SV extends RecyclerView.ViewHolder {
            RecyclerView rvCourses;
            TextView tvWeekday;
            TextView tvSection;
            TextView tvHeaderScore;
            
            SV(View v) { 
                super(v); 
                rvCourses = v.findViewById(R.id.rv_courses_in_segment);
                tvWeekday = v.findViewById(R.id.tv_weekday);
                tvSection = v.findViewById(R.id.tv_section);
                tvHeaderScore = v.findViewById(R.id.tv_header_score);
            }
        }
        
        @Override 
        public SV onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_segment, parent, false);
            return new SV(v);
        }
        
        @Override 
        public void onBindViewHolder(SV holder, int position) {
            Segment s = items.get(position);
            // 显示星期几
            holder.tvWeekday.setText("星期" + s.dayName(s.day));
            // 显示节次，支持m-n节格式
            if (s.start == s.end) {
                holder.tvSection.setText((s.start + 1) + "节");
            } else {
                holder.tvSection.setText((s.start + 1) + "-" + (s.end + 1) + "节");
            }
            
            // 显示分数
            if (s.courses != null && !s.courses.isEmpty()) {
                // 如果是Diff模式，且有课程，显示分数可能不准确（混合了Added/Removed），这里暂时隐藏或只显示第一个
                // 用户未明确要求Diff模式下的Segment分数显示规则，保持原样
                if (!s.courses.isEmpty()) {
                    int score = s.courses.get(0).interest;
                    if (score > 0) {
                        if (holder.tvHeaderScore != null) {
                            holder.tvHeaderScore.setText(String.valueOf(score));
                            holder.tvHeaderScore.setVisibility(View.VISIBLE);
                        }
                    } else {
                        if (holder.tvHeaderScore != null) holder.tvHeaderScore.setVisibility(View.GONE);
                    }
                }
            } else {
                if (holder.tvHeaderScore != null) holder.tvHeaderScore.setVisibility(View.GONE);
            }
            
            // 根据星期几设置不同的背景颜色
            LinearLayout segmentLayout = (LinearLayout) holder.itemView.findViewById(R.id.segment_gradient_layout);
            if (segmentLayout != null) {
                switch (s.day) {
                    case 0: // 星期一
                        segmentLayout.setBackgroundResource(R.drawable.segment_gradient_monday);
                        break;
                    case 1: // 星期二
                        segmentLayout.setBackgroundResource(R.drawable.segment_gradient_tuesday);
                        break;
                    case 2: // 星期三
                        segmentLayout.setBackgroundResource(R.drawable.segment_gradient_wednesday);
                        break;
                    case 3: // 星期四
                        segmentLayout.setBackgroundResource(R.drawable.segment_gradient_thursday);
                        break;
                    case 4: // 星期五
                        segmentLayout.setBackgroundResource(R.drawable.segment_gradient_friday);
                        break;
                    case 5: // 星期六
                        segmentLayout.setBackgroundResource(R.drawable.segment_gradient_saturday);
                        break;
                    case 6: // 星期日
                        segmentLayout.setBackgroundResource(R.drawable.segment_gradient_sunday);
                        break;
                    default:
                        segmentLayout.setBackgroundResource(R.drawable.segment_gradient_orange);
                }
            }
            
            LinearLayoutManager layoutManager = new LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false);
            holder.rvCourses.setLayoutManager(layoutManager);
            holder.rvCourses.setAdapter(new CourseInSegmentAdapter(s.courses, s.start + 1, diffStatus));
            // 设置固定高度，显示5节课，同时启用滚动
            ViewGroup.LayoutParams params = holder.rvCourses.getLayoutParams();
            // 175dp大约足够显示5个课程 (每个~35dp)
            params.height = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 138, holder.itemView.getResources().getDisplayMetrics());
            holder.rvCourses.setLayoutParams(params);
            holder.rvCourses.setNestedScrollingEnabled(true);
            holder.rvCourses.setHasFixedSize(true);
        }
        
        @Override 
        public int getItemCount() { 
            return items.size(); 
        }
    }

    /**
     * 课程适配器
     */
    private class CourseInSegmentAdapter extends RecyclerView.Adapter<CourseInSegmentAdapter.CV> {
        private final List<Course> items;
        private final int sectionNumber;
        private final Map<String, Integer> diffStatus;
        
        CourseInSegmentAdapter(List<Course> items, int sectionNumber, Map<String, Integer> diffStatus) { 
            this.items = items == null ? new ArrayList<>() : items;
            this.sectionNumber = sectionNumber;
            this.diffStatus = diffStatus;
        }
        
        class CV extends RecyclerView.ViewHolder { 
            TextView tvCourseName;
            TextView tvCourseScore;
            TextView tvCourseSection;
            android.widget.ImageView ivUniqueIcon;
            
            CV(View v) { 
                super(v); 
                tvCourseName = v.findViewById(R.id.tv_course_name);
                tvCourseScore = v.findViewById(R.id.tv_course_score);
                tvCourseSection = v.findViewById(R.id.tv_course_section);
                ivUniqueIcon = v.findViewById(R.id.iv_unique_icon);
            } 
        }
        
        @Override 
        public CV onCreateViewHolder(ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_course, parent, false);
            // 确保课程项高度适当
            ViewGroup.LayoutParams params = new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 
                ViewGroup.LayoutParams.WRAP_CONTENT
            );
            v.setLayoutParams(params);
            return new CV(v);
        }
        
        @Override 
        public void onBindViewHolder(CV holder, int position) {
            Course c = items.get(position);
            
            // 移除课程节次显示
            holder.tvCourseSection.setVisibility(View.GONE);
            
            // UCourse_TAG - 确保课程名显示前8个字，剩余用...表示
            String title = (c.title == null ? "(未命名)" : c.title);
            if (title.length() > 8) {
                title = title.substring(0, 8) + "...";
            }
            
            // 处理Diff状态
            int status = 0;
            if (diffStatus != null && c.id != null && diffStatus.containsKey(c.id)) {
                status = diffStatus.get(c.id);
            }
            
            if (status == 1) { // Added
                holder.tvCourseName.setText("+ " + title);
                holder.tvCourseName.setTextColor(0xFF4CAF50); // Green
                holder.tvCourseName.setShadowLayer(8, 0, 0, android.graphics.Color.WHITE);
            } else if (status == 2) { // Removed
                holder.tvCourseName.setText("- " + title);
                holder.tvCourseName.setTextColor(0xFFFF5252); // Red
                holder.tvCourseName.setShadowLayer(8, 0, 0, android.graphics.Color.WHITE);
            } else {
                holder.tvCourseName.setText(title);
                holder.tvCourseName.setTextColor(android.graphics.Color.WHITE); // White
                holder.tvCourseName.setShadowLayer(0, 0, 0, 0);
            }
            
            // 显示独特课程图标
            if (holder.ivUniqueIcon != null) {
                // 在Diff模式下隐藏独特图标，避免视觉混乱
                if (diffStatus != null) {
                    holder.ivUniqueIcon.setVisibility(View.GONE);
                } else {
                    Integer freq = courseFrequency.get(c.id);
                    if (freq != null) {
                        if (freq == 1) {
                            // 唯一存在于此方案 (Solid)
                            holder.ivUniqueIcon.setVisibility(View.VISIBLE);
                            holder.ivUniqueIcon.setImageResource(R.drawable.ic_circle_solid);
                        } else if (freq < totalSolutionsCount) {
                            // 在部分方案中缺失 (Hollow)
                            holder.ivUniqueIcon.setVisibility(View.VISIBLE);
                            holder.ivUniqueIcon.setImageResource(R.drawable.ic_circle_hollow);
                        } else {
                            holder.ivUniqueIcon.setVisibility(View.GONE);
                        }
                    } else {
                        holder.ivUniqueIcon.setVisibility(View.GONE);
                    }
                }
            }
            
            // 统一在Header显示分数，此处隐藏
            holder.tvCourseScore.setVisibility(View.GONE);
            
            // 移除背景设置，使用透明背景（继承Segment颜色）
            holder.itemView.setBackgroundResource(0);
            
            // 设置点击事件，显示课程详情
            holder.itemView.setOnClickListener(v -> {
                try {
                    CourseDetailFragment d = CourseDetailFragment.newInstance(c);
                    d.show(getParentFragmentManager(), "course_detail");
                } catch (Throwable _t) {
                    Log.e(TAG, "显示课程详情失败", _t);
                }
            });
        }
        
        @Override 
        public int getItemCount() { 
            // 返回所有课程数量，确保可以滚动查看更多课程
            return items.size(); 
        }
    }
}
