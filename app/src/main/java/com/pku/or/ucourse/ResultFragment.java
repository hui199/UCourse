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
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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

    private static final String TAG = "ResultFragment";
    
    // UI组件
    private Button btnGenerate;
    private LinearLayout container;
    private ProgressBar progressBar;
    private HomeViewModel homeViewModel;
    
    // 存储课程ID到时间段的映射
    private Map<String, List<TimeSlot>> lastCourseMapping = new HashMap<>();
    private Map<String, List<Course>> groupToOriginalCourses; // 组ID到原始课程列表的映射

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {
        // 加载布局
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
        // 清空之前的结果
        if (container != null) {
            container.removeAllViews();
        }
        if (btnGenerate != null) {
            btnGenerate.setEnabled(false);
        }
        // 显示并重置进度条
        if (progressBar != null) {
            progressBar.setVisibility(View.VISIBLE);
            progressBar.setProgress(0);
        }


        // 收集数据：课程和空闲时间
        List<Course> courses = homeViewModel.courses.getValue();
        if (courses == null) courses = new ArrayList<>();
        
        // 筛选课程：排除没有课程名和没有课程时间的课程
        List<Course> filteredCourses = new ArrayList<>();
        for (Course course : courses) {
            if (course != null && course.title != null && !course.title.trim().isEmpty() && 
                course.rawTime != null && !course.rawTime.trim().isEmpty()) {
                filteredCourses.add(course);
            }
        }
        
        // 创建课程分组：将相同时间和分数的课程分为一组
        Map<String, List<Course>> courseGroups = new HashMap<>();
        Map<String, List<TimeSlot>> tempMapping = new HashMap<>();
        
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

        // 获取TimeFragment中的WeekTimeData
        WeekTimeData freeTimes = null;
        try {
            TimeFragment tf = (TimeFragment) getActivity().getSupportFragmentManager().findFragmentByTag("time");
            if (tf != null) freeTimes = tf.getCurrentWeekData();
        } catch (Throwable _t) { /* ignore */ }
        if (freeTimes == null) freeTimes = new WeekTimeData();
        final WeekTimeData finalFreeTimes = freeTimes;

        // 使用分组后的映射
        final Map<String, List<TimeSlot>> mapping = groupMapping;
        // 保存映射供createSolutionCard使用
        ResultFragment.this.lastCourseMapping = mapping;

        // 运行求解器在后台线程
        final long startTime = System.currentTimeMillis();
        final int timeLimitMs = 10000; // 10秒时间限制
        
        // 创建进度更新handler
        final Handler progressHandler = new Handler(Looper.getMainLooper());
        
        // 启动进度更新线程
        final Thread progressThread = new Thread(() -> {
            try {
                while (!Thread.currentThread().isInterrupted()) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    int progress = (int) Math.min(95, (elapsed * 100) / timeLimitMs);
                    progressHandler.post(() -> {
                        if (progressBar != null) {
                            progressBar.setProgress(progress);
                        }
                    });
                    Thread.sleep(100); // 每100ms更新一次
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        progressThread.start();

        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            RecommendationSolver solver = new RecommendationSolver(finalCourses, mapping, finalFreeTimes, 10, timeLimitMs);
            List<RecommendationSolver.Solution> solutions = solver.solve();
            
            // 还原解决方案中的组课程为原始课程
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
                // 替换课程列表
                solution.courses = originalCourses;
            }

            // 中断进度更新线程
            progressThread.interrupt();
            
            // 在主线程更新UI
            new Handler(Looper.getMainLooper()).post(() -> {
                if (btnGenerate != null) {
                    btnGenerate.setEnabled(true);
                }
                // 更新进度条为满并设置为不可见
                if (progressBar != null) {
                    progressBar.setProgress(100);
                    progressBar.setVisibility(View.GONE); // 任务完成后隐藏进度条
                }

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

                // 创建解决方案卡片
                int idx = 1;
                for (RecommendationSolver.Solution s : solutions) {
                    View card = createSolutionCard(s, idx++);
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
    private View createSolutionCard(RecommendationSolver.Solution s, int rank) {
        LayoutInflater inf = LayoutInflater.from(requireContext());
        View row = inf.inflate(R.layout.item_solution_card, container, false);
        TextView tvTitle = row.findViewById(R.id.tv_solution_title);
        TextView tvScore = row.findViewById(R.id.tv_solution_score);
        RecyclerView rvSegments = row.findViewById(R.id.rv_segments);

        tvTitle.setText("方案 " + rank);
        tvScore.setText(String.valueOf(s.totalScore));

        // 构建时间段
        List<Segment> segments = buildSegmentsForSolution(s);

        // 设置水平RecyclerView
        if (rvSegments != null) {
            rvSegments.setLayoutManager(new LinearLayoutManager(requireContext(), LinearLayoutManager.HORIZONTAL, false));
            rvSegments.setAdapter(new SegmentAdapter(segments));
        }

        return row;
    }

    /**
     * 构建解决方案的时间段
     */
    private List<Segment> buildSegmentsForSolution(RecommendationSolver.Solution s) {
        Map<String, Segment> segMap = new HashMap<>();
        
        // 直接使用解决方案中的课程构建时间段
        if (s != null && s.courses != null) {
            for (Course course : s.courses) {
                if (course == null) continue;
                
                // 为每个课程解析时间段
                List<TimeSlot> slots = CourseTimeParser.parse(course.rawTime);
                if (slots == null) continue;
                
                for (TimeSlot ts : slots) {
                    String timeKey = ts.getDay() + ":" + ts.getStartSection() + "-" + ts.getEndSection();
                    int interestScore = course.interest;
                    String combinedKey = timeKey + ":" + interestScore;

                    Segment seg = segMap.get(combinedKey);
                    if (seg == null) {
                        seg = new Segment();
                        seg.id = combinedKey;
                        seg.day = ts.getDay();
                        seg.start = ts.getStartSection();
                        seg.end = ts.getEndSection();
                        segMap.put(combinedKey, seg);
                    }
                    
                    // 避免重复课程
                    boolean exists = false;
                    for (Course xc : seg.courses) { 
                        if (xc != null && xc.id != null && xc.id.equals(course.id)) { 
                            exists = true; 
                            break; 
                        } 
                    }
                    if (!exists) seg.courses.add(course);
                }
            }
        }

        List<Segment> out = new ArrayList<>(segMap.values());
        
        // 按天和开始时间排序
        out.sort((a, b) -> {
            int dayCompare = Integer.compare(a.day, b.day);
            if (dayCompare != 0) return dayCompare;
            return Integer.compare(a.start, b.start);
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
        
        SegmentAdapter(List<Segment> items) { 
            this.items = items == null ? new ArrayList<>() : items;
        }
        
        class SV extends RecyclerView.ViewHolder {
            RecyclerView rvCourses;
            
            SV(View v) { 
                super(v); 
                rvCourses = v.findViewById(R.id.rv_courses_in_segment); 
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
            LinearLayoutManager layoutManager = new LinearLayoutManager(requireContext(), LinearLayoutManager.VERTICAL, false);
            holder.rvCourses.setLayoutManager(layoutManager);
            holder.rvCourses.setAdapter(new CourseInSegmentAdapter(s.courses));
            // 设置固定高度，显示更多课程，同时启用滚动
            ViewGroup.LayoutParams params = holder.rvCourses.getLayoutParams();
            params.height = 500; // 增加高度以显示更多课程
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
        
        CourseInSegmentAdapter(List<Course> items) { 
            this.items = items == null ? new ArrayList<>() : items;
        }
        
        class CV extends RecyclerView.ViewHolder { 
            TextView tvCourseName;
            TextView tvCourseScore;
            
            CV(View v) { 
                super(v); 
                tvCourseName = v.findViewById(R.id.tv_course_name);
                tvCourseScore = v.findViewById(R.id.tv_course_score);
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
            // 课程名只显示前8个字，剩余用...表示
            String title = (c.title == null ? "(未命名)" : c.title);
            if (title.length() > 8) {
                title = title.substring(0, 8) + "...";
            }
            
            holder.tvCourseName.setText(title);
            
            if (c.interest > 0) {
                holder.tvCourseScore.setText(String.valueOf(c.interest));
                holder.tvCourseScore.setVisibility(View.VISIBLE);
            } else {
                holder.tvCourseScore.setVisibility(View.GONE);
            }
            
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
