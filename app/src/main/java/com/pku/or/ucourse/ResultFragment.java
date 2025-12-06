package com.pku.or.ucourse;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Build;
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
import android.view.View;
import android.view.ViewGroup;
import com.pku.or.ucourse.utils.PerformanceLogger;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

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
        btnGenerate.setOnClickListener(view -> generateRecommendations());

        return v;
    }

    /**
     * 生成推荐方案
     */
    private void generateRecommendations() {
        // 1. 立即更新UI状态 (主线程)
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
        
        // 2. 延迟执行核心逻辑，确保UI有时间渲染进度条
        if (container != null) {
            container.postDelayed(this::processRecommendationsLogic, 50);
        } else {
            processRecommendationsLogic();
        }
    }

    /**
     * 处理推荐逻辑 (收集数据 -> 后台计算)
     */
    private void processRecommendationsLogic() {
        final long overallStartTime = System.currentTimeMillis();
        PerformanceLogger.logPerformancePoint("GENERATE_RECOMMENDATIONS", "逻辑处理开始");
        PerformanceLogger.logProgress("RECOMMENDATION_PROCESS", 0, 0);

        // 收集数据：课程 (主线程)
        List<Course> rawCourses = homeViewModel.courses.getValue();
        final List<Course> tempCourses = rawCourses == null ? new ArrayList<>() : new ArrayList<>(rawCourses);

        // 获取首周设置 (主线程)
        android.content.SharedPreferences prefs = requireContext().getSharedPreferences("ucourse_prefs", android.content.Context.MODE_PRIVATE);
        final String firstWeekStr = prefs.getString("first_week_date", null);

        PerformanceLogger.logPerformancePoint("COURSE_PROCESSING", "获取课程总数: " + tempCourses.size());

        // 获取TimeFragment中的WeekTimeData (主线程)
        long freeTimeStartTime = System.currentTimeMillis();
        WeekTimeData freeTimes = null;
        try {
            TimeFragment tf = (TimeFragment) getActivity().getSupportFragmentManager().findFragmentByTag("time");
            if (tf != null) {
                freeTimes = tf.getCurrentWeekData();
            } else {
                // 尝试从SharedPreferences加载
                android.content.SharedPreferences timePrefs = requireContext().getSharedPreferences("TimeTablePrefs", android.content.Context.MODE_PRIVATE);
                String json = timePrefs.getString("week_data", null);
                if (json != null) {
                    freeTimes = new com.google.gson.Gson().fromJson(json, WeekTimeData.class);
                }
            }
        } catch (Throwable _t) { /* ignore */ }
        if (freeTimes == null) freeTimes = new WeekTimeData();
        final WeekTimeData finalFreeTimes = freeTimes;
        PerformanceLogger.logPerformancePoint("TIME_PROCESSING", "获取空闲时间完成 - 耗时: " + (System.currentTimeMillis() - freeTimeStartTime) + "ms");

        // 运行求解器在后台线程
        final long startTime = System.currentTimeMillis();
        final int timeLimitMs = 20000; // 优化时间限制为20秒，更合理的估计
        
        PerformanceLogger.logPerformancePoint("PROGRESS_THREAD", "准备启动进度更新线程");
        // 启动进度更新线程，限制总时间为20秒
        startProgressUpdateThread(startTime, timeLimitMs);
        
        PerformanceLogger.logPerformancePoint("SOLVER_THREAD", "准备启动后台求解线程");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            PerformanceLogger.logPerformancePoint("SOLVER_THREAD", "后台求解线程开始执行");

            // Filter ended courses based on first week date (Background Thread)
            try {
                if (firstWeekStr != null) {
                    java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault());
                    java.util.Date firstWeekDate = sdf.parse(firstWeekStr);
                    java.util.Calendar currentCal = java.util.Calendar.getInstance();
                    // Strip time for date comparison
                    currentCal.set(java.util.Calendar.HOUR_OF_DAY, 0);
                    currentCal.set(java.util.Calendar.MINUTE, 0);
                    currentCal.set(java.util.Calendar.SECOND, 0);
                    currentCal.set(java.util.Calendar.MILLISECOND, 0);
                    long currentMillis = currentCal.getTimeInMillis();

                    java.util.Iterator<Course> it = tempCourses.iterator();
                    while (it.hasNext()) {
                        Course c = it.next();
                        try {
                            int[] weeks = com.pku.or.ucourse.result.CourseTimeParser.parseWeekRange(c.rawTime);
                            int endWeek = weeks[1];
                            
                            List<com.pku.or.ucourse.view.TimeSlot> slots = com.pku.or.ucourse.result.CourseTimeParser.parse(c.rawTime);
                            int maxDay = -1;
                            for (com.pku.or.ucourse.view.TimeSlot slot : slots) {
                                if (slot.getDay() > maxDay) maxDay = slot.getDay();
                            }
                            
                            if (maxDay >= 0) {
                                java.util.Calendar endCal = java.util.Calendar.getInstance();
                                endCal.setTime(firstWeekDate);
                                endCal.add(java.util.Calendar.WEEK_OF_YEAR, endWeek - 1);
                                endCal.add(java.util.Calendar.DAY_OF_MONTH, maxDay);
                                
                                // Strip time from endCal
                                endCal.set(java.util.Calendar.HOUR_OF_DAY, 0);
                                endCal.set(java.util.Calendar.MINUTE, 0);
                                endCal.set(java.util.Calendar.SECOND, 0);
                                endCal.set(java.util.Calendar.MILLISECOND, 0);
                                
                                // If current date is strictly after the end date, remove the course
                                if (currentMillis > endCal.getTimeInMillis()) {
                                    it.remove();
                                }
                            }
                        } catch (Exception e) {
                            // Ignore parsing errors, keep course
                        }
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            final List<Course> courses = tempCourses;
            
            // 筛选课程：排除没有课程名和没有课程时间的课程
            List<Course> filteredCourses = new ArrayList<>();
            long filterStartTime = System.currentTimeMillis();
            for (Course course : courses) {
                if (course != null && course.title != null && !course.title.trim().isEmpty() && 
                    course.rawTime != null && !course.rawTime.trim().isEmpty()) {
                    filteredCourses.add(course);
                }
            }
            PerformanceLogger.logPerformancePoint("COURSE_PROCESSING", "课程筛选完成 - 保留课程数: " + filteredCourses.size() + ", 耗时: " + 
                  (System.currentTimeMillis() - filterStartTime) + "ms");
            
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
                courseGroups.putIfAbsent(groupKey, new ArrayList<>());
                courseGroups.get(groupKey).add(course);
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
            RecommendationSolver solver = new RecommendationSolver(finalCourses, mapping, finalFreeTimes, 10, timeLimitMs);
            PerformanceLogger.logPerformancePoint("SOLVER_PROCESS", "求解器初始化完成 - 耗时: " + (System.currentTimeMillis() - solverInitStartTime) +
                    "ms");
            
            // 记录求解过程时间
            long solveStartTime = System.currentTimeMillis();
            List<RecommendationSolver.Solution> solutions = solver.solve();
            long solveEndTime = System.currentTimeMillis();
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

            // 在主线程更新UI
            new Handler(Looper.getMainLooper()).post(() -> {
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
                                    courseFrequency.put(c.id, courseFrequency.getOrDefault(c.id, 0) + 1);
                                }
                            }
                        }
                    }
                }
                
                // 立即处理完成状态，减少用户等待感
                long completeTime = System.currentTimeMillis();
                PerformanceLogger.logProgress("RECOMMENDATION_PROCESS", 100, (completeTime - startTime));
                
                // 停止进度更新线程
                if (progressThread != null) {
                    progressThread.interrupt();
                    progressThread = null;
                }
                
                // 设置进度条为100%并隐藏
                if (progressBar != null) {
                    progressBar.setProgress(100);
                    // 添加短暂延迟再隐藏，让用户看到100%完成状态
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (progressBar != null) {
                            progressBar.setVisibility(View.GONE);
                        }
                    }, 100); // 仅延迟100ms
                }
                
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
    private void startProgressUpdateThread(final long startTime, final int timeLimitMs) {
        PerformanceLogger.logPerformancePoint("PROGRESS_THREAD", "进度更新线程启动，时间限制设置为: " + timeLimitMs + "ms");
        
        // 如果已有进度线程在运行，先中断它
        if (progressThread != null && progressThread.isAlive()) {
            progressThread.interrupt();
        }
        
        progressThread = new Thread(() -> {
            try {
                int lastProgress = 0;
                while (!Thread.interrupted()) {
                    final long elapsed = System.currentTimeMillis() - startTime;
                    // 优化的进度计算算法：使用指数函数使进度前期增加更快，避免用户等待感
                    float progressRatio;
                    if (elapsed < timeLimitMs * 0.5f) {
                        // 前50%时间内，进度增长更快
                        progressRatio = 0.6f * (float)(1 - Math.exp(-1.5f * elapsed / (timeLimitMs * 0.5f)));
                    } else {
                        // 后50%时间内，逐渐接近99%
                        progressRatio = 0.6f + 0.39f * (elapsed - timeLimitMs * 0.5f) / (timeLimitMs * 0.5f);
                    }
                    
                    final int progress = Math.round(progressRatio * 100);
                    
                    // 记录进度变化
                    if (progress != lastProgress) {
                        PerformanceLogger.logProgress("RECOMMENDATION_PROCESS", progress, elapsed);
                        lastProgress = progress;
                    }
                    
                    // 在主线程更新进度条
                    requireActivity().runOnUiThread(() -> {
                        if (progressBar != null) {
                            progressBar.setProgress(progress);
                        }
                    });
                    
                    Thread.sleep(50); // 每50ms更新一次，使进度条更流畅
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
        
        // 显式设置DIFF按钮的默认背景和文字颜色
        if (btnDiff != null) {
            // 使用setBackgroundColor直接设置白色背景，确保不受主题影响
            btnDiff.setBackgroundColor(Color.WHITE);
            btnDiff.setText("DIFF");
            btnDiff.setTextColor(Color.BLACK); // Black text
            // 清除可能影响的背景色滤镜
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                btnDiff.setBackgroundTintList(null);
                btnDiff.setBackgroundTintMode(null);
            }
        }
        
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
                            
                            // 更新 Diff 摘要
                            if (tvDiff != null) {
                                tvDiff.setText(diffText);
                                // User requested to hide Region 1 (Diff Text)
                                // tvDiff.setVisibility(View.VISIBLE);
                                tvDiff.setVisibility(View.GONE);
                            }
                            
                            // 切换到恢复模式
                            btnDiff.setText("RESTORE");
                            // 设置更明显的高亮蓝色背景，确保清晰可见
                            btnDiff.setBackgroundColor(0xFF2196F3); // Standard Android Blue (high contrast)
                            btnDiff.setTextColor(Color.WHITE); // White text
                            // 确保清除所有可能影响的背景色滤镜
                            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                                btnDiff.setBackgroundTintList(null);
                                btnDiff.setBackgroundTintMode(null);
                            }
                            // 强制刷新按钮显示
                            btnDiff.invalidate();
                            btnDiff.requestLayout();
                            
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
                                // 恢复白色背景
                                btnDiff.setBackgroundColor(Color.WHITE);
                                btnDiff.setTextColor(Color.BLACK); // Black text
                                // 清除可能影响的背景色滤镜
                                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                                    btnDiff.setBackgroundTintList(null);
                                    btnDiff.setBackgroundTintMode(null);
                                }
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
                        // 额外检查：如果课程名称相同且在同一时间段，视为重复（避免数据源中的重复项）
                        if (xc.title != null && course.title != null && xc.title.trim().equals(course.title.trim())) {
                            exists = true;
                            break;
                        }
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
            params.height = (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 155, holder.itemView.getResources().getDisplayMetrics());
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
                holder.tvCourseName.setShadowLayer(12, 0, 0, 0xFFFFFFFF); // White glow
            } else if (status == 2) { // Removed
                holder.tvCourseName.setText("- " + title);
                holder.tvCourseName.setTextColor(0xFFFF5252); // Red
                holder.tvCourseName.setShadowLayer(12, 0, 0, 0xFFFFFFFF); // White glow
            } else {
                holder.tvCourseName.setText(title);
                holder.tvCourseName.setTextColor(0xFFFFFFFF); // White
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
