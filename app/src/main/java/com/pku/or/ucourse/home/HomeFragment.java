package com.pku.or.ucourse.home;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ClipDrawable;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.PorterDuff;
import java.util.Calendar;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.util.Log;

import com.pku.or.ucourse.R;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Locale;
import java.util.Arrays;
import java.util.Iterator;
import java.util.Map;
import java.util.HashMap;
import android.database.Cursor;
import android.provider.OpenableColumns;

import com.pku.or.ucourse.CourseDetailFragment;
import com.pku.or.ucourse.home.MenuDialogFragment;

/**
 * 主页Fragment - 负责课程导入、展示和评分管理
 * 核心功能：
 * 1. 导入Excel/CSV文件并解析课程数据
 * 2. 展示课程列表（支持文件/Sheet/课程三级结构）
 * 3. 通过滑动手势为课程评分（0-10分）
 */
public class HomeFragment extends Fragment {
    // ViewModel：管理课程数据和状态
    private HomeViewModel vm;
    // 状态文本：显示已导入课程数量
    private TextView tvStatus;
    // 课程列表RecyclerView
    private RecyclerView rv;
    // 课程适配器：负责渲染课程列表
    private CourseAdapter adapter;

    // 设置开学日期按钮
    // 已移除设置第一周按钮，改为通过菜单实现
    // private Button btnSetStartDate;
    private static final String PREF_NAME = "ucourse_prefs";
    private static final String KEY_FIRST_WEEK_DATE = "first_week_date";

    // 文件选择器启动器：用于选择Excel/CSV文件
    private ActivityResultLauncher<Intent> openFileLauncher;
    // 后台线程池：用于文件解析和数据处理
    private final ExecutorService parserExecutor = Executors.newSingleThreadExecutor();
    // 上一次的列映射结果：用于"使用上次映射"功能
    private ColumnMapDialogFragment.MappingResult previousMapping = null;
    // 是否应用映射到所有Sheet：用于批量导入时的快捷操作
    private boolean applyMappingToAll = false;
    // 存储拖动开始时所有课程的原始分数（用于File/Sheet拖动时避免累积误差）
    private final Map<String, Integer> originalInterestMap = new HashMap<>();

    /**
     * 待处理的Sheet数据
     * 用于在导入Excel文件时暂存每个Sheet的数据，等待用户选择列映射
     */
    private static class PendingSheet {
        String name;        // Sheet名称
        List<String[]> rows; // Sheet的所有行数据
        String[] header;    // 表头行
        int[] autoMap;      // 自动映射结果（title, time, teacher, unit的列索引）
        int sheetIndex;     // Sheet索引（Excel中的顺序）
        
        PendingSheet(String name, int sheetIndex, List<String[]> rows) { 
            this(name, sheetIndex, rows, new int[]{-1, -1, -1, -1}); 
        }
        
        PendingSheet(String name, int sheetIndex, List<String[]> rows, int[] autoMap) { 
            this.name = name; 
            this.sheetIndex = sheetIndex;
            this.rows = rows; 
            this.header = rows.size() > 0 ? rows.get(0) : new String[0]; 
            this.autoMap = autoMap; 
        }
    }

    /**
     * Fragment创建时的初始化
     * 主要初始化ViewModel和文件选择器
     */
    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 初始化ViewModel
    vm = new ViewModelProvider(requireActivity()).get(HomeViewModel.class);

        // 注册文件选择器回调：当用户选择文件后，自动调用handleUri处理
        openFileLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), 
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    handleUri(result.getData().getData());
                }
            }
        );
        // 启用选项菜单
        setHasOptionsMenu(true);

    }

    /**
     * 创建Fragment的视图
     * 初始化UI组件并设置事件监听
     */
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_home, container, false);
        
        // 初始化UI组件
        // 已移除导入课程表按钮和设置第一周按钮，改为通过菜单实现
        tvStatus = v.findViewById(R.id.tv_status);
        rv = v.findViewById(R.id.rv_courses);
        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new CourseAdapter(new ArrayList<>());
        adapter.setViewModel(vm);
        rv.setAdapter(adapter);

        // 禁用RecyclerView动画以避免滑动评分时的视觉跳动
        androidx.recyclerview.widget.DefaultItemAnimator animator = new androidx.recyclerview.widget.DefaultItemAnimator();
        animator.setSupportsChangeAnimations(false);
        animator.setAddDuration(0);
        animator.setRemoveDuration(0);
        animator.setMoveDuration(0);
        animator.setChangeDuration(0);
        rv.setItemAnimator(animator);

        /**
         * 长按监听器：长按项目2秒后弹出操作菜单
         * 菜单选项：删除、重新映射、取消
         * 根据项目类型(文件/Sheet/课程)生成友好的显示标题
         */
        adapter.setOnItemLongPressListener((type, id, position) -> {
            if (getActivity() == null) return;
            
            // 根据项目类型(文件/Sheet/课程)计算友好的显示标题
            String title = "(项)";
            try {
                if (type == 0) {
                    // 文件：显示文件名
                    String full = id == null ? "(file)" : id;
                    int idx = full.lastIndexOf('/');
                    title = (idx >= 0 && idx < full.length()-1) ? full.substring(idx+1) : full;
                } else if (type == 1) {
                    // Sheet：id格式为 "文件路径|Sheet名称"
                    if (id != null && id.contains("|")) {
                        String[] parts = id.split("\\|", 2);
                        title = parts.length == 2 ? parts[1] : id;
                        // 清理Excel内部路径格式
                        if (title.startsWith("xl/worksheets/")) {
                            title = title.replaceFirst("^xl/worksheets/", "").replaceAll("\\.xml$", "");
                        }
                    } else {
                        title = id == null ? "(sheet)" : id;
                    }
                } else if (type == 2) {
                    // 课程：从ViewModel中查找课程标题
                    List<Course> cur = vm.courses.getValue();
                    if (cur != null) {
                        for (Course c : cur) {
                            if (c != null && c.id != null && c.id.equals(id)) {
                                title = c.title == null || c.title.isEmpty() ? "(未命名)" : c.title;
                                break;
                            }
                        }
                    }
                }
            } catch (Throwable _t) { 
                title = id == null ? "(项)" : id; 
            }

            // 弹出操作菜单：删除、重新映射、取消
            // Request 8: Beautify pop-ups using MenuDialogFragment
            List<String> options = new ArrayList<>();
            options.add("删除");
            options.add("重新映射");
            options.add("取消");
            
            MenuDialogFragment menu = MenuDialogFragment.newInstance("操作: " + title, options);
            menu.setOnOptionClickListener((pos, option) -> {
                if (pos == 0) { // 删除
                    // 删除操作：弹出确认对话框
                    android.app.AlertDialog.Builder c = new android.app.AlertDialog.Builder(getContext());
                    c.setTitle("确认删除");
                    c.setMessage("确定要删除该项及其子项吗？此操作不可撤销。");
                    c.setPositiveButton("删除", (dd, ww) -> performDeleteTitle(type, id));
                    c.setNegativeButton("取消", null);
                    c.show();
                } else if (pos == 1) { // 重新映射
                     performRemapTitle(type, id);
                }
            });
            menu.show(getParentFragmentManager(), "menu_dialog");
        });

        // 设置课程点击监听器：显示详情
        adapter.setOnCourseClickListener(this::showCourseDetail);

        /**
         * 监听课程数据变化，更新UI
         * 当课程列表变化时，自动刷新RecyclerView和状态文本
         */
        vm.courses.observe(getViewLifecycleOwner(), list -> {
            // 检查传播学研究方法课程是否存在于原始数据中
            boolean found = false;
            if (list != null) {
                for (Course c : list) {
                    if (c != null && c.title != null && c.title.contains("传播学研究方法")) {
                        found = true;
                        Log.d("UCourse_HomeFragment", "传播学研究方法课程存在于原始数据中: 标题=" + c.title + ", ID=" + c.id + ", 兴趣分=" + c.interest + ", 原始时间=" + c.rawTime);
                        break;
                    }
                }
                if (!found) {
                    Log.d("UCourse_HomeFragment", "传播学研究方法课程不存在于原始数据中");
                }
            }
            adapter.setCourses(list);
            tvStatus.setText("已导入课程: " + (list != null ? list.size() : 0));
        });

        /**
         * 设置自定义滑动手势（用于课程评分）
         * 使用RecyclerView.SimpleOnItemTouchListener实现稳定的触摸事件处理
         */
        setupSimpleSwipeGesture(rv);

        return v;
    }
    
    /**
     * 设置简单的滑动手势处理
     * 通过滑动为课程评分（0-10分），滑动屏幕宽度50%对应10分
     */
    private void setupSimpleSwipeGesture(RecyclerView recyclerView) {
        // 获取屏幕宽度，用于计算滑动灵敏度
        final int screenWidth = getResources().getDisplayMetrics().widthPixels;
        // 滑动屏幕宽度80%对应10分，即每8%屏幕宽度对应1分
        final float pixelsPerScore = screenWidth * 0.08f; // 滑动屏幕宽度80%对应10分
        
        recyclerView.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            // 触摸起始位置
            private float startX = 0;
            private float startY = 0;
            // 是否识别为滑动手势
            private boolean isSwipeGesture = false;
            // 当前操作的ViewHolder
            private RecyclerView.ViewHolder currentViewHolder = null;
            // 当前操作的课程ID
            private String currentItemId = null;
            // 滑动开始时的分数
            private int startScore = 0;
            
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv, @NonNull android.view.MotionEvent e) {
                switch (e.getAction()) {
                    case android.view.MotionEvent.ACTION_DOWN:
                        // 记录触摸起始位置
                        startX = e.getX();
                        startY = e.getY();
                        isSwipeGesture = false;
                        
                        Log.i("HomeFragment", "hui_debug: ACTION_DOWN - e.getX()=" + e.getX() + ", e.getY()=" + e.getY());
                        
                        // 查找被触摸的项目
                        View childView = rv.findChildViewUnder(e.getX(), e.getY());
                        if (childView != null) {
                            android.util.Log.d("CourseAdapterDebug", "ACTION_DOWN - found childView");
                            currentViewHolder = rv.getChildViewHolder(childView);
                            if (currentViewHolder != null) {
                                android.util.Log.d("CourseAdapterDebug", "ACTION_DOWN - found currentViewHolder");
                                int position = currentViewHolder.getAdapterPosition();
                                android.util.Log.d("CourseAdapterDebug", "ACTION_DOWN - position=" + position + ", NO_POSITION=" + RecyclerView.NO_POSITION);
                                if (position != RecyclerView.NO_POSITION && adapter != null) {
                                    android.util.Log.d("CourseAdapterDebug", "ACTION_DOWN - position is valid, adapter is not null");
                                    // 获取项目ID和类型，保存初始分数
                                    currentItemId = adapter.getIdForPosition(position);
                                    int type = adapter.getTypeForPosition(position);
                                    startScore = getCurrentInterest(type, currentItemId);
                                    android.util.Log.d("CourseAdapterDebug", "ACTION_DOWN - startScore=" + startScore + ", type=" + type + ", id=" + currentItemId);
                                    
                                    // 如果是File或Sheet，记录所有相关课程的原始分数
                                    if (type == 0 || type == 1) {
                                        originalInterestMap.clear();
                                        List<Course> courses = vm.courses.getValue();
                                        if (courses != null) {
                                            for (Course c : courses) {
                                                if (type == 0) {
                                                    String cf = c.fileId == null ? "<nofile>" : c.fileId;
                                                    if (cf.equals(currentItemId)) {
                                                        originalInterestMap.put(c.id, c.interest);
                                                    }
                                                } else if (type == 1) {
                                                    if (getCourseSheetId(c).equals(currentItemId)) {
                                                        originalInterestMap.put(c.id, c.interest);
                                                    }
                                                }
                                            }
                                        }
                                        android.util.Log.d("CourseAdapterDebug", "ACTION_DOWN - recorded " + originalInterestMap.size() + " courses in originalInterestMap");
                                    }
                                } else {
                                    android.util.Log.d("CourseAdapterDebug", "ACTION_DOWN - position is invalid or adapter is null");
                                    // 如果位置无效，清除当前状态
                                    currentItemId = null;
                                    startScore = 0;
                                }
                            } else {
                                currentViewHolder = null;
                                currentItemId = null;
                                startScore = 0;
                            }
                        } else {
                            currentViewHolder = null;
                            currentItemId = null;
                            startScore = 0;
                        }
                        return false;
                        
                    case android.view.MotionEvent.ACTION_MOVE:
                        if (currentViewHolder != null) {
                            float deltaX = e.getX() - startX;
                            float deltaY = e.getY() - startY;
                            
                            // 判断是否为水平滑动手势（横向位移>30像素，且横向/纵向比例>1.5）
                            if (!isSwipeGesture && Math.abs(deltaX) > 30 && Math.abs(deltaX) > Math.abs(deltaY) * 1.5) {
                                isSwipeGesture = true;
                                return true; // 拦截事件，进入onTouchEvent处理
                            }
                        }
                        return false;
                }
                return false;
            }
            
            @Override
            public void onTouchEvent(@NonNull RecyclerView rv, @NonNull android.view.MotionEvent e) {
                if (currentViewHolder == null) {
                    Log.i("HomeFragment", "hui_debug: onTouchEvent - currentViewHolder is null, returning");
                    return;
                }
                
                Log.i("HomeFragment", "hui_debug: onTouchEvent - currentViewHolder is not null, e.getAction()=" + e.getAction());
                
                // 确保在处理触摸事件时，我们有有效的项目ID和初始分数
                if (currentItemId == null) {
                    Log.i("HomeFragment", "hui_debug: onTouchEvent - currentItemId is null, trying to reinitialize");
                    try {
                        int position = currentViewHolder.getAdapterPosition();
                        Log.i("HomeFragment", "hui_debug: onTouchEvent - currentViewHolder.getAdapterPosition()=" + position);
                        if (position != RecyclerView.NO_POSITION && adapter != null) {
                            Log.i("HomeFragment", "hui_debug: onTouchEvent - position is valid, adapter is not null");
                            currentItemId = adapter.getIdForPosition(position);
                            Log.i("HomeFragment", "hui_debug: onTouchEvent - got currentItemId=" + currentItemId);
                            int type = adapter.getTypeForPosition(position);
                            Log.i("HomeFragment", "hui_debug: onTouchEvent - got type=" + type);
                            startScore = getCurrentInterest(type, currentItemId);
                            Log.i("HomeFragment", "hui_debug: onTouchEvent - reinitialized startScore=" + startScore + ", type=" + type + ", id=" + currentItemId);
                        } else {
                            Log.i("HomeFragment", "hui_debug: onTouchEvent - position is invalid or adapter is null, returning");
                            // 如果仍然无法获取位置，直接返回
                            return;
                        }
                    } catch (Exception ex) {
                        android.util.Log.d("CourseAdapterDebug", "onTouchEvent - exception when reinitializing: " + ex.getMessage());
                        // 如果获取失败，直接返回
                        return;
                    }
                } else {
                    android.util.Log.d("CourseAdapterDebug", "onTouchEvent - currentItemId is not null, id=" + currentItemId + ", startScore=" + startScore);
                }
                
                switch (e.getAction()) {
                    case android.view.MotionEvent.ACTION_MOVE:
                        if (isSwipeGesture) {
                            // 根据滑动距离计算实时分数变化
                            float deltaX = e.getX() - startX;
                            float scoreChangeFloat = deltaX / pixelsPerScore; // 可能为负
                            float newScoreFloat = Math.max(0, Math.min(10, startScore + scoreChangeFloat));
                            int currentScoreInt = Math.round(newScoreFloat);
                            
                            try {
                                android.util.Log.d("CourseAdapterDebug", "ACTION_MOVE: start=" + startScore + 
                                    " x=" + e.getX() + " startX=" + startX + " deltaX=" + deltaX + 
                                    " change=" + scoreChangeFloat + " newFloat=" + newScoreFloat + " newInt=" + currentScoreInt);
                            } catch (Throwable _t) {}

                            // 实时更新Item样式（背景、文字颜色等），确保拖动过程中0分变灰/10分发光即时生效
                            if (adapter != null) {
                                int type = adapter.getTypeForPosition(currentViewHolder.getAdapterPosition());
                                
                                if (type == 2) { // Course
                                    // Update the course itself
                                    adapter.updateItemStyle(currentViewHolder, currentScoreInt, type);
                                    // Update its parent headers (Sheet/File) based on hypothetical score
                                    adapter.updateParentHeaderForCourseDrag(rv, currentItemId, newScoreFloat);
                                } else { // Sheet or File
                                    // Update the header itself (based on children + delta)
                                    adapter.updateHeaderStyleForDrag(currentViewHolder, scoreChangeFloat);
                                }
                            }

                            // 更新视觉反馈（进度条动画）为绝对分数，保持现有行为
                            updateSwipeVisualFloat(currentViewHolder, newScoreFloat);

                            // 对于Sheet或File类型，右侧应显示增量（+n / -n）而不是绝对分数
                            try {
                                int pos = currentViewHolder.getAdapterPosition();
                                if (pos != RecyclerView.NO_POSITION && adapter != null) {
                                    int itType = adapter.getTypeForPosition(pos);
                                    android.widget.TextView tvScore = currentViewHolder.itemView.findViewById(R.id.tv_interest_score);
                                    if (tvScore != null && (itType == 1 || itType == 0)) { // Sheet or File
                                        int deltaDisplay = (int)scoreChangeFloat;
                                        String s = (deltaDisplay > 0 ? ("+" + deltaDisplay) : String.valueOf(deltaDisplay));
                                        tvScore.setText(s);
                                        tvScore.setVisibility(View.VISIBLE);
                                        // hide the count while delta is visible to avoid layout shift
                                        android.widget.TextView tvCount = currentViewHolder.itemView.findViewById(R.id.tv_count);
                                        if (tvCount != null) tvCount.setVisibility(View.GONE);
                                    }
                                }
                            } catch (Throwable _t) {}

                            // 如果是Sheet或File，实时更新可见课程的进度条（按各自原始分数 + scoreChangeFloat）
                            try {
                                int pos = currentViewHolder.getAdapterPosition();
                                if (pos != RecyclerView.NO_POSITION && adapter != null) {
                                    int itType = adapter.getTypeForPosition(pos);
                                    if (itType == 1 || itType == 0) { // Sheet or File
                                        adapter.previewChildrenForHeaderDrag(rv, pos, scoreChangeFloat, originalInterestMap);
                                    }
                                    
                                    if (itType == 0) {
                                        adapter.updateChildHeadersForFileDrag(rv, currentItemId, scoreChangeFloat);
                                    }
                                }
                            } catch (Throwable _t) {}
                        }
                        break;
                        
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        if (isSwipeGesture && currentViewHolder != null) {
                            // 松手时计算最终分数变化（delta）并立即应用到视图，随后异步保存模型
                            float finalDeltaX = e.getX() - startX;
                            float deltaFloat = finalDeltaX / pixelsPerScore;

                            // Fix for Issue 2: Use rounding to match preview logic instead of truncation
                            // Calculate final absolute score using the same logic as ACTION_MOVE
                            float finalScoreFloat = Math.max(0, Math.min(10, startScore + deltaFloat));
                            int finalScore = Math.round(finalScoreFloat);
                            
                            try {
                                android.util.Log.d("CourseAdapterDebug", "ACTION_UP: start=" + startScore + 
                                    " x=" + e.getX() + " startX=" + startX + " deltaX=" + finalDeltaX + 
                                    " change=" + deltaFloat + " finalFloat=" + finalScoreFloat + " finalInt=" + finalScore);
                            } catch (Throwable _t) {}

                            // 立即把视图更新为最终分数，避免异步保存带来的闪跳
                            try {
                                int type = adapter.getTypeForPosition(currentViewHolder.getAdapterPosition());
                                
                                if (type == 2) {
                                    // 单个课程：直接更新分数
                                    if (adapter != null) {
                                        int pos = currentViewHolder.getAdapterPosition();
                                        adapter.updateItemScore(rv, pos, finalScore);
                                    }
                                } else {
                                    // Sheet/File：显示增量，不调用updateItemScore
                                    android.widget.TextView tvScore = currentViewHolder.itemView.findViewById(R.id.tv_interest_score);
                                    if (tvScore != null) {
                                        tvScore.setVisibility(View.GONE);
                                        android.widget.TextView tvCount = currentViewHolder.itemView.findViewById(R.id.tv_count);
                                        if (tvCount != null) tvCount.setVisibility(View.VISIBLE);
                                    }
                                }
                            } catch (Throwable _t) {}

                            if (Math.abs(deltaFloat) >= 0.5f) {
                                int type = adapter.getTypeForPosition(currentViewHolder.getAdapterPosition());
                                if (type == 0 || type == 1) {
                                    java.util.Map<String, Integer> originalMapCopy = new java.util.HashMap<>(originalInterestMap);
                                    applyInterestDeltaForItemFloat(type, currentItemId, deltaFloat, originalMapCopy);
                                }
                            }
                            isSwipeGesture = false;
                            currentViewHolder = null;
                            currentItemId = null;
                            startScore = 0;
                            originalInterestMap.clear();
                            if (adapter != null) {
                                adapter.clearPreviewInterest();
                                adapter.notifyDataSetChanged();
                            }
                        }
                        break;
                }
            }
        });
    }

    /**
     * 合并解析的课程到ViewModel
     * 核心逻辑：
     * - 如果课程已存在（根据fileId|sheetName|title|teachers|unit匹配），则更新数据但保留用户评分
     * - 如果是新课程，则直接添加
     * - 新导入的文件默认折叠
     * 
     * @param parsed 新解析的课程列表
     */
    private void mergeParsedCourses(List<Course> parsed) {
        if (parsed == null || parsed.isEmpty()) return;

        List<Course> cur = vm.courses.getValue();
        if (cur == null) cur = new ArrayList<>();

        // 创建逻辑键映射：fileId|sheetName|title|teachers|unit
        // 使用队列来处理具有相同逻辑键的重复课程
        Map<String, java.util.Queue<Course>> existingByLogicalKey = new HashMap<>();
        for (Course ex : cur) {
            String logicalKey = (ex.fileId == null ? "<nofile>" : ex.fileId) + "|" +
                               (ex.sheetName == null ? "<nosheet>" : ex.sheetName) + "|" +
                               (ex.title == null ? "" : ex.title.trim()) + "|" +
                               (ex.teachers == null ? "" : ex.teachers.trim()) + "|" +
                               (ex.unit == null ? "" : ex.unit.trim());
            if (!existingByLogicalKey.containsKey(logicalKey)) {
                existingByLogicalKey.put(logicalKey, new java.util.LinkedList<>());
            }
            existingByLogicalKey.get(logicalKey).offer(ex);
        }

        List<Course> remaining = new ArrayList<>();
        List<Course> toAdd = new ArrayList<>();
        Set<Course> processedExisting = new HashSet<>();

        // 处理解析的课程：更新已存在的课程或添加新课程
        for (Course p : parsed) {
            String logicalKey = (p.fileId == null ? "<nofile>" : p.fileId) + "|" +
                               (p.sheetName == null ? "<nosheet>" : p.sheetName) + "|" +
                               (p.title == null ? "" : p.title.trim()) + "|" +
                               (p.teachers == null ? "" : p.teachers.trim()) + "|" +
                               (p.unit == null ? "" : p.unit.trim());

            java.util.Queue<Course> queue = existingByLogicalKey.get(logicalKey);
            Course existing = (queue != null && !queue.isEmpty()) ? queue.poll() : null;
            
            if (existing != null) {
                // 更新已存在课程的数据，但保留用户评分
                int preservedInterest = existing.interest;
                // 从解析的课程复制所有字段
                existing.title = p.title;
                existing.teachers = p.teachers;
                existing.unit = p.unit;
                existing.rawTime = p.rawTime;
                existing.groupKey = p.groupKey;
                // 保持现有ID以维持一致性
                existing.interest = preservedInterest; // 保留用户评分
                remaining.add(existing);
                processedExisting.add(existing);
            } else {
                // 新课程
                toAdd.add(p);
                remaining.add(p);
            }
        }

        // 添加未被替换的已存在课程（孤儿课程）
        for (Course ex : cur) {
            if (!processedExisting.contains(ex)) {
                remaining.add(ex);
            }
        }

        vm.save(remaining);

        // 导入完成后，检查是否需要提示设置开学日期
        checkFirstWeekDateState();

        // 确保新导入的文件默认折叠（提高用户体验）
        if (!toAdd.isEmpty() && adapter != null) {
            Set<String> newFiles = new HashSet<>();
            for (Course p : toAdd) {
                newFiles.add(p.fileId == null ? "<nofile>" : p.fileId);
            }
            adapter.collapseFiles(newFiles);
        }
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        // 关闭线程池，避免内存泄漏
        parserExecutor.shutdownNow();
    }

    // 在滑动Sheet时实时更新RecyclerView中可见的课程项的进度条
    private void updateVisibleCoursesForSheet(RecyclerView rv, String sheetId, float deltaFloat) {
        // Deprecated: Replaced by adapter.previewChildrenForHeaderDrag
    }

    // 在滑动File时实时更新RecyclerView中可见的课程项的进度条
    private void updateVisibleCoursesForFile(RecyclerView rv, String fileId, float deltaFloat) {
        // Deprecated: Replaced by adapter.previewChildrenForHeaderDrag
    }

    private void checkFirstWeekDateState() {
        // 检查开学日期是否已设置
        if (getContext() == null) return;
        SharedPreferences prefs = getContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String date = prefs.getString(KEY_FIRST_WEEK_DATE, null);
        // 由于已移除设置按钮，不再需要视觉提示
    }
    
    /**
     * 检查开学日期状态并更新菜单图标颜色
     * @param menu 菜单对象
     */
    private void checkFirstWeekDateState(android.view.Menu menu) {
        if (getContext() == null || menu == null) return;
        
        // 获取设置第一周的菜单项
        android.view.MenuItem item = menu.findItem(R.id.menu_set_start_date);
        if (item == null) return;
        
        // 检查开学日期是否已设置
        SharedPreferences prefs = getContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String date = prefs.getString(KEY_FIRST_WEEK_DATE, null);
        
        // 设置图标颜色：红色表示未设置，白色表示已设置
        Drawable icon = item.getIcon();
        if (icon != null) {
            int color = (date == null) ? Color.RED : Color.WHITE;
            icon.setColorFilter(color, PorterDuff.Mode.SRC_ATOP);
            item.setIcon(icon);
        }
    }


    





    
    private void showSetDateDialog() {
        if (getContext() == null) return;
        Calendar c = Calendar.getInstance();
        SharedPreferences prefs = getContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String saved = prefs.getString(KEY_FIRST_WEEK_DATE, null);
        
        if (saved != null) {
            try {
                String[] parts = saved.split("-");
                if (parts.length == 3) {
                    c.set(Calendar.YEAR, Integer.parseInt(parts[0]));
                    c.set(Calendar.MONTH, Integer.parseInt(parts[1]) - 1);
                    c.set(Calendar.DAY_OF_MONTH, Integer.parseInt(parts[2]));
                }
            } catch (Exception e) {}
        } else {
            // Default logic
            c = getDefaultFirstWeekDate();
        }

        new DatePickerDialog(getContext(), (view, year, month, dayOfMonth) -> {
            String dateStr = String.format(Locale.ROOT, "%04d-%02d-%02d", year, month + 1, dayOfMonth);
            prefs.edit().putString(KEY_FIRST_WEEK_DATE, dateStr).apply();
            checkFirstWeekDateState();
        }, c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).show();
    }

    private Calendar getDefaultFirstWeekDate() {
        Calendar now = Calendar.getInstance();
        int year = now.get(Calendar.YEAR);
        int month = now.get(Calendar.MONTH); // 0-11
        
        Calendar target = Calendar.getInstance();
        target.clear();
        
        if (month >= 8 || month <= 1) { // Sep(8) to Feb(1) -> Fall Semester (Sep 1st)
            int targetYear = (month <= 1) ? year - 1 : year;
            target.set(targetYear, Calendar.SEPTEMBER, 1);
        } else { // Mar(2) to Aug(7) -> Spring Semester (Mar 1st)
            target.set(year, Calendar.MARCH, 1);
        }
        
        // Find first Monday on or after target date
        while (target.get(Calendar.DAY_OF_WEEK) != Calendar.MONDAY) {
            target.add(Calendar.DAY_OF_MONTH, 1);
        }
        
        return target;
    }

    /**
     * 处理用户选择的文件URI
     * 支持CSV和Excel（.xlsx）文件
     * - CSV文件：直接显示列映射对话框
     * - Excel文件：遍历所有Sheet，依次显示列映射对话框（支持"应用到所有"快捷操作）
     * 
     * @param uri 文件URI
     */
    private void handleUri(Uri uri) {
        parserExecutor.submit(() -> {
            // 重置"应用到所有"标志（每次导入文件时重新询问）
            applyMappingToAll = false;
            
            try (InputStream is = getContext().getContentResolver().openInputStream(uri)) {
                if (is == null) return;
                
                // 优先通过ContentResolver获取显示名（OpenableColumns.DISPLAY_NAME），回退到lastPathSegment
                String displayName = null;
                Cursor cursor = null;
                try {
                    cursor = getContext().getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
                    if (cursor != null && cursor.moveToFirst()) {
                        int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (idx != -1) displayName = cursor.getString(idx);
                    }
                } catch (Throwable _t) {
                    // ignore
                } finally {
                    if (cursor != null) cursor.close();
                }

                String path = uri.getLastPathSegment();
                if (path != null && path.contains(":")) {
                    path = path.substring(path.lastIndexOf(":") + 1);
                }
                // 如果displayName看起来像数字（很多provider会返回文档ID如"35"），尝试使用DocumentFile获取更友好的name
                String chosenName = displayName;
                try {
                    if (chosenName == null || chosenName.matches("^\\d+$")) {
                        androidx.documentfile.provider.DocumentFile df = androidx.documentfile.provider.DocumentFile.fromSingleUri(getContext(), uri);
                        if (df != null) {
                            String dn = df.getName();
                            if (dn != null && !dn.isEmpty()) chosenName = dn;
                        }
                    }
                } catch (Throwable _t) {
                    // ignore
                }
                final String finalPath = (chosenName != null && !chosenName.isEmpty()) ? chosenName : path;
                List<Course> parsed = new ArrayList<>();
                
                // CSV文件处理
                if (finalPath != null && finalPath.toLowerCase().endsWith(".csv")) {
                    CsvParser cp = new CsvParser();
                    List<String[]> rows = cp.parse(is);
                    if (rows.size() > 0) {
                        String[] header = rows.get(0);
                        int[] map = autoMapHeader(header);
                        
                        List<String> headers = new ArrayList<>();
                        for (String h : header) headers.add(h == null ? "" : h);
                        List<String[]> dataRows = rows.size() > 1 ? rows.subList(1, rows.size()) : new ArrayList<>();
                        
                        getActivity().runOnUiThread(() -> {
                            ColumnMapDialogFragment dlg = new ColumnMapDialogFragment(headers, new ColumnMapDialogFragment.Listener() {
                                @Override
                                public void onMapping(ColumnMapDialogFragment.MappingResult result) {
                                    previousMapping = result;
                                    parserExecutor.submit(() -> {
                                        List<Course> p = Course.fromRows(finalPath, "sheet1", 0, dataRows, result.titleIdx, result.timeIdx, result.teacherIdx, result.unitIdx, -1);
                                        getActivity().runOnUiThread(() -> mergeParsedCourses(p));
                                        if (result.applyToAll) applyMappingToAll = true;
                                    });
                                }

                                @Override
                                public void onUsePrevious() {
                                    if (previousMapping != null) {
                                        parserExecutor.submit(() -> {
                                        List<Course> p = Course.fromRows(finalPath, "sheet1", 0, dataRows, previousMapping.titleIdx, previousMapping.timeIdx, previousMapping.teacherIdx, previousMapping.unitIdx, -1);
                                        getActivity().runOnUiThread(() -> mergeParsedCourses(p));
                                    });
                                    }
                                }
                            });
                            
                            // 预填充自动映射结果或上次的映射
                            if (map != null && !(map[0] == -1 && map[1] == -1 && map[2] == -1 && map[3] == -1)) {
                                dlg.setPrefillMapping(new ColumnMapDialogFragment.MappingResult(map[0], map[1], map[2], map[3], false));
                            } else if (previousMapping != null) {
                                dlg.setPrefillMapping(previousMapping);
                            }
                            
                            dlg.show(getParentFragmentManager(), "colmap");
                        });
                    }
                } else {
                    // Excel文件处理
                    XlsxLightParser xp = new XlsxLightParser();
                    XlsxLightParser.ParseResult pr = xp.parse(is);
                    
                    // 收集所有Sheet待处理
                    List<PendingSheet> pending = new ArrayList<>();
                    int sheetIdx = 0;
                    for (XlsxLightParser.Sheet s : pr.sheets) {
                        if (s.rows.size() > 0) {
                            String[] header = s.rows.get(0);
                            int[] map = autoMapHeader(header);
                            pending.add(new PendingSheet(s.name, sheetIdx, s.rows, map));
                        }
                        sheetIdx++;
                    }

                    // 依次处理每个Sheet
                    if (!pending.isEmpty()) {
                        getActivity().runOnUiThread(() -> processPendingSheets(pending, parsed, finalPath));
                    }
                }
                
                if (!parsed.isEmpty()) {
                    getActivity().runOnUiThread(() -> mergeParsedCourses(parsed));
                }
            } catch (Exception e) {
                // Ignore errors
            }
        });
    }

    /**
     * 处理文件选择结果
     * 当用户从文件选择器选择文件后调用
     * 
     * @param requestCode 请求码（1234表示文件选择请求）
     * @param resultCode  结果码（RESULT_OK表示成功）
     * @param data        包含文件URI的Intent
     */
    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1234 && resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            handleUri(uri);
        }
    }

    /**
     * 自动映射表头列
     * 根据列名关键字自动识别课程名称、时间、教师、单位等列
     * 优先匹配明确的关键字，如果找不到则使用启发式规则
     * 
     * @param header 表头数组
     * @return 映射结果数组[titleIdx, timeIdx, teacherIdx, unitIdx, locationIdx]，未找到的列返回-1
     */
    private int[] autoMapHeader(String[] header) {
        int titleIdx = -1, timeIdx = -1, teacherIdx = -1, unitIdx = -1, locationIdx = -1;
        if (header == null) return new int[]{-1, -1, -1, -1, -1};

        // 第一轮：明确的关键字匹配
        for (int i = 0; i < header.length; i++) {
            String h = header[i] == null ? "" : header[i].toLowerCase(Locale.ROOT).trim();
            // 检测标题列：优先匹配"课程名/课程名称/课名/名称/title/name"
            boolean looksLikeTitle = h.contains("课程名") || h.contains("课程名称") || h.contains("课名") || h.contains("名称") ||
                                   h.contains("title") || h.contains("name") || h.contains("课程") || h.contains("科目") ||
                                   h.contains("subject");
            // 避免数字ID/代码列，如"课程号"、"编号"、"序号"、"id"、"代码"
            boolean looksLikeId = h.contains("号") || h.contains("编号") || h.contains("序号") || h.contains("id") ||
                                h.contains("代码") || h.contains("code") || h.matches(".*\\bno\\b.*") ||
                                h.matches(".*\\bid\\b.*");

            if (titleIdx == -1 && looksLikeTitle && !looksLikeId) titleIdx = i;
            if (timeIdx == -1 && (h.contains("时间") || h.contains("上课") || h.contains("time") || h.contains("schedule") ||
                                h.contains("时段") || h.contains("period"))) timeIdx = i;
            if (teacherIdx == -1 && (h.contains("教师") || h.contains("讲师") || h.contains("老师") || h.contains("teacher") ||
                                   h.contains("lecturer") || h.contains("教授") || h.contains("导师"))) teacherIdx = i;
            if (unitIdx == -1 && (h.contains("学院") || h.contains("单位") || h.contains("系") || h.contains("department") ||
                                h.contains("学院") || h.contains("专业"))) unitIdx = i;
            if (locationIdx == -1 && (h.contains("地点") || h.contains("教室") || h.contains("location") || h.contains("room") ||
                                    h.contains("place") || h.contains("where"))) locationIdx = i;
        }

        // 第二轮：标题列的备用逻辑（如果第一轮未找到）
        if (titleIdx == -1) {
            // 检查前3列（标题通常在第一或第二列）
            for (int i = 0; i < Math.min(header.length, 3); i++) {
                if (titleIdx == -1) {
                    String h = header[i] == null ? "" : header[i].toLowerCase(Locale.ROOT).trim();
                    // 跳过ID列
                    boolean looksLikeId = h.contains("号") || h.contains("编号") || h.contains("序号") ||
                                        h.contains("id") || h.contains("代码") || h.matches(".*\\bno\\b.*");
                    if (!looksLikeId && !h.isEmpty()) {
                        titleIdx = i;
                    }
                }
            }
        }

        // 第三轮：如果仍未找到，默认使用第一列（最常见的位置）
        if (titleIdx == -1 && header.length > 0) {
            titleIdx = 0;
        }

        return new int[]{titleIdx, timeIdx, teacherIdx, unitIdx, locationIdx};
    }

    /**
     * 处理待映射的Sheet列表
     * 依次显示列映射对话框
     * 
     * @param pending 待处理的Sheet列表
     * @param parsed  已解析的课程列表（累积结果）
     * @param path    文件路径
     */
    private void processPendingSheets(List<PendingSheet> pending, List<Course> parsed, String path) {
        Iterator<PendingSheet> it = pending.iterator();
        // 顺序迭代处理每个Sheet
        processNextPending(it, parsed, path);
    }

    /**
     * 处理下一个待映射的Sheet
     * 显示列映射对话框，用户选择后解析数据并继续处理下一个
     * 支持"应用到所有"功能：使用相同映射处理剩余所有Sheet
     * 
     * @param it     Sheet迭代器
     * @param parsed 已解析的课程列表（累积结果）
     * @param path   文件路径
     */
    private void processNextPending(Iterator<PendingSheet> it, List<Course> parsed, String path) {
        if (!it.hasNext()) {
            if (parsed != null && !parsed.isEmpty()) {
                getActivity().runOnUiThread(() -> mergeParsedCourses(parsed));
            }
            return;
        }
        
        PendingSheet ps = it.next();
        List<String> headers = new ArrayList<>();
        for (String h : ps.header) headers.add(h == null ? "" : h);
        
        ColumnMapDialogFragment dlg = new ColumnMapDialogFragment(headers, new ColumnMapDialogFragment.Listener() {
            @Override
            public void onMapping(ColumnMapDialogFragment.MappingResult result) {
                previousMapping = result;
                if (result.applyToAll) applyMappingToAll = true;
                
                List<String[]> dataRows = ps.rows.size() > 1 ? ps.rows.subList(1, ps.rows.size()) : new ArrayList<>();
                
                parserExecutor.submit(() -> {
                        // 解析当前Sheet的数据
                        List<Course> p = Course.fromRows(path, ps.name, ps.sheetIndex, dataRows, result.titleIdx, result.timeIdx, result.teacherIdx, result.unitIdx, -1);
                        synchronized (parsed) { parsed.addAll(p); }
                    getActivity().runOnUiThread(() -> mergeParsedCourses(p));
                    
                    // 如果选择"应用到所有"，批量处理剩余Sheet
                    if (result.applyToAll && previousMapping != null) {
                        List<PendingSheet> remaining = new ArrayList<>();
                        while (it.hasNext()) remaining.add(it.next());
                        for (PendingSheet ps2 : remaining) {
                            List<String[]> dataRows2 = ps2.rows.size() > 1 ? ps2.rows.subList(1, ps2.rows.size()) : new ArrayList<>();
                            List<Course> p2 = Course.fromRows(path, ps2.name, ps2.sheetIndex, dataRows2, previousMapping.titleIdx, previousMapping.timeIdx, previousMapping.teacherIdx, previousMapping.unitIdx, -1);
                            synchronized (parsed) { parsed.addAll(p2); }
                            getActivity().runOnUiThread(() -> mergeParsedCourses(p2));
                        }
                    } else {
                        // 否则继续处理下一个Sheet
                        getActivity().runOnUiThread(() -> processNextPending(it, parsed, path));
                    }
                });
            }

            @Override
            public void onUsePrevious() {
                // 使用上次的映射结果
                if (previousMapping != null) {
                    List<String[]> dataRows = ps.rows.size() > 1 ? ps.rows.subList(1, ps.rows.size()) : new ArrayList<>();
                    parserExecutor.submit(() -> {
                        List<Course> p = Course.fromRows(path, ps.name, ps.sheetIndex, dataRows, previousMapping.titleIdx, previousMapping.timeIdx, previousMapping.teacherIdx, previousMapping.unitIdx, -1);
                        synchronized (parsed) { parsed.addAll(p); }
                        getActivity().runOnUiThread(() -> processNextPending(it, parsed, path));
                    });
                } else {
                    processNextPending(it, parsed, path);
                }
            }
        });
        
        // 预填充自动映射结果或上次的映射
        if (ps.autoMap != null && !(ps.autoMap[0] == -1 && ps.autoMap[1] == -1 && ps.autoMap[2] == -1 && ps.autoMap[3] == -1)) {
            dlg.setPrefillMapping(new ColumnMapDialogFragment.MappingResult(ps.autoMap[0], ps.autoMap[1], ps.autoMap[2], ps.autoMap[3], false));
        } else if (previousMapping != null) {
            dlg.setPrefillMapping(previousMapping);
        }
        
        dlg.show(getParentFragmentManager(), "colmap");
    }

    /**
     * 删除指定项目及其所有子项
     * - type=0（文件）：删除该文件下的所有课程
     * - type=1（Sheet）：删除该Sheet下的所有课程
     * - type=2（课程）：删除单个课程
     * 
     * @param type 项目类型：0=文件, 1=Sheet, 2=课程
     * @param id   项目ID
     */
    private void performDeleteTitle(int type, String id) {
        if (id == null) return;
        parserExecutor.submit(() -> {
            List<Course> cur = vm.courses.getValue();
            if (cur == null) cur = new ArrayList<>();
            List<Course> remaining = new ArrayList<>();
            String targetFile = null;
            String targetSheet = null;
            // 根据类型确定删除的范围
            if (type == 0) {
                // 文件ID
                targetFile = id;
            } else if (type == 1) {
                // Sheet ID格式：文件|Sheet
                String[] parts = id.split("\\|", 2);
                if (parts.length >= 1) targetFile = parts[0];
                if (parts.length == 2) targetSheet = parts[1];
            } else if (type == 2) {
                // 课程ID：查找课程以确定其所属文件和Sheet
                for (Course c : cur) {
                    if (c != null && c.id != null && c.id.equals(id)) {
                        targetFile = c.fileId == null ? "<nofile>" : c.fileId;
                        targetSheet = c.sheetName == null ? "<nosheet>" : c.sheetName;
                        break;
                    }
                }
            }
            // 筛选出不删除的课程
            // 筛选出不删除的课程
            for (Course c : cur) {
                String f = c.fileId == null ? "<nofile>" : c.fileId;
                String s = c.sheetName == null ? "<nosheet>" : c.sheetName;
                boolean drop = false;
                if (type == 0) {
                    // 删除整个文件
                    if (f.equals(targetFile)) drop = true;
                } else if (type == 1) {
                    // 删除整个Sheet
                    if (f.equals(targetFile) && targetSheet != null && s.equals(targetSheet)) drop = true;
                } else if (type == 2) {
                    // 仅删除单个课程
                    if (c.id != null && c.id.equals(id)) drop = true;
                }
                if (!drop) remaining.add(c);
            }
            vm.save(remaining);
        });
    }

    /**
     * 重新映射列（用于修改已导入文件的列映射关系）
     * - type=0（文件）：重新映射整个文件
     * - type=1（Sheet）：重新映射该Sheet
     * 注意：由于原始解析数据未缓存，需要用户重新导入文件
     * 
     * @param type 项目类型：0=文件, 1=Sheet, 2=课程（课程类型时获取所属文件）
     * @param id   项目ID
     */
    private void performRemapTitle(int type, String id) {
        // 尝试获取有用的文件名提示
        String fileHint = id;
        if (type == 1 && id != null && id.contains("|")) {
            fileHint = id.split("\\|", 2)[0];
        } else if (type == 2 && id != null) {
            // 尝试查找课程以显示文件名
            List<Course> cur = vm.courses.getValue();
            if (cur != null) {
                for (Course c : cur) {
                    if (c != null && c.id != null && c.id.equals(id)) {
                        fileHint = c.fileId == null ? fileHint : c.fileId;
                        break;
                    }
                }
            }
        }
        // 提示用户重新导入文件
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(getContext());
        b.setTitle("重新映射");
        b.setMessage("未缓存原始表格数据（文件: " + fileHint + "）。要重新映射，请重新导入该文件并在映射对话框中选择新的列映射。\n\n是否现在打开文件选择器重新导入？");
        b.setPositiveButton("重新导入", (d, w) -> {
            // 打开文件选择器
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            String[] mimeTypes = {"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/csv", "application/vnd.ms-excel"};
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
            if (openFileLauncher != null) openFileLauncher.launch(intent);
        });
        b.setNegativeButton("取消", null);
        b.show();
    }

    /**
     * 获取指定项目的当前兴趣分数
     * 
     * @param type 项目类型：0=文件, 1=Sheet, 2=课程
     * @param id   项目ID
     * @return 兴趣分数（0-10分），如果找不到则返回5分
     */
    private int getCurrentInterest(int type, String id) {
        // 优先使用adapter的rawCourses列表（如果adapter不为null），确保与显示的课程列表一致
        List<Course> cur = null;
        if (adapter != null) {
            cur = adapter.getRawCourses();
        }
        if (cur == null || cur.isEmpty()) {
            cur = vm.courses.getValue();
        }
        Log.i("HomeFragment", "hui_debug: getCurrentInterest - type=" + type + ", id=" + id);
        if (cur == null || cur.isEmpty()) {
            Log.i("HomeFragment", "hui_debug: getCurrentInterest - cur is null or empty, return 5");
            return 5;
        }
        
        if (type == 2) {
            // 课程类型：直接返回该课程的分数
            Log.i("HomeFragment", "hui_debug: getCurrentInterest - type=COURSE");
            Log.i("HomeFragment", "hui_debug: getCurrentInterest - looking for id=" + id + ", cur.size()=" + cur.size());
            // 记录rawCourses列表中的所有课程ID
            StringBuilder sb = new StringBuilder("hui_debug: getCurrentInterest - rawCourses list:");
            for (Course c : cur) {
                if (c != null && c.id != null) {
                    sb.append(" [id=").append(c.id).append(", interest=").append(c.interest).append("]");
                }
            }
            Log.i("HomeFragment", sb.toString());
            for (Course c : cur) {
                if (c != null && c.id != null) {
                    Log.i("HomeFragment", "hui_debug: getCurrentInterest - checking course id=" + c.id + ", interest=" + c.interest + ", equals? " + c.id.equals(id));
                    if (c.id.equals(id)) {
                        int interest = Math.max(0, Math.min(10, c.interest));
                        Log.i("HomeFragment", "hui_debug: getCurrentInterest - found course, return " + interest);
                        return interest;
                    }
                }
            }
            Log.i("HomeFragment", "hui_debug: getCurrentInterest - course not found, return 5");
            return 5;
        } else if (type == 1) {
            // Sheet类型：计算该Sheet下所有课程的平均分
            Log.i("HomeFragment", "hui_debug: getCurrentInterest - type=SHEET");
            String[] parts = id == null ? new String[]{"",""} : id.split("\\|", 2);
            String f = parts.length>0?parts[0].trim():"";
            String s = parts.length>1?parts[1].trim():"";
            Log.i("HomeFragment", "hui_debug: getCurrentInterest - Sheet file=" + f + ", sheet=" + s);
            
            int total = 0, count = 0;
            for (Course c : cur) {
                if (c != null) {
                    String cf = (c.fileId == null ? "<nofile>" : c.fileId).trim();
                    String cs = (c.sheetName == null ? "<nosheet>" : c.sheetName).trim();
                    Log.i("HomeFragment", "hui_debug: getCurrentInterest - Sheet checking course file=" + cf + ", sheet=" + cs + ", interest=" + c.interest);
                    if (cf.equals(f) && cs.equals(s)) {
                        total += Math.max(0, Math.min(10, c.interest));
                        count++;
                        Log.i("HomeFragment", "hui_debug: getCurrentInterest - Sheet matched course, total=" + total + ", count=" + count);
                    }
                }
            }
            
            if (count > 0) {
                int avg = total / count;
                Log.i("HomeFragment", "hui_debug: getCurrentInterest - Sheet average=" + avg + ", total=" + total + ", count=" + count);
                return avg;
            } else {
                Log.i("HomeFragment", "hui_debug: getCurrentInterest - Sheet no courses found, return 5");
                return 5;
            }
        } else if (type == 0) {
            // 文件类型：计算该文件下所有课程的平均分
            Log.i("HomeFragment", "hui_debug: getCurrentInterest - type=FILE");
            String target = id == null ? "<nofile>" : id.trim();
            Log.i("HomeFragment", "hui_debug: getCurrentInterest - File target=" + target);
            
            int total = 0, count = 0;
            for (Course c : cur) {
                if (c != null) {
                    String cf = c.fileId == null ? "<nofile>" : c.fileId;
                    Log.i("HomeFragment", "hui_debug: getCurrentInterest - File checking course file=" + cf + ", interest=" + c.interest);
                    if (cf.equals(target)) {
                        total += Math.max(0, Math.min(10, c.interest));
                        count++;
                        Log.i("HomeFragment", "hui_debug: getCurrentInterest - File matched course, total=" + total + ", count=" + count);
                    }
                }
            }
            
            if (count > 0) {
                int avg = total / count;
                Log.i("HomeFragment", "hui_debug: getCurrentInterest - File average=" + avg + ", total=" + total + ", count=" + count);
                return avg;
            } else {
                Log.i("HomeFragment", "hui_debug: getCurrentInterest - File no courses found, return 5");
                return 5;
            }
        }
        
        Log.i("HomeFragment", "hui_debug: getCurrentInterest - unknown type, return 5");
        return 5;
    }

    /**
     * 重置滑动视觉效果
     * 清除item的位移和透明度变化
     * 
     * @param viewHolder 要重置的ViewHolder
     */
    private void resetSwipeVisual(RecyclerView.ViewHolder viewHolder) {
        if (viewHolder != null && viewHolder.itemView != null) {
            viewHolder.itemView.setTranslationX(0);
            viewHolder.itemView.setAlpha(1.0f);
            
            // 重置进度条到实际分数（而不是0）
            View rootCard = viewHolder.itemView.findViewById(R.id.root_card);
            TextView tvScore = viewHolder.itemView.findViewById(R.id.tv_interest_score);
            
            if (rootCard != null && tvScore != null) {
                try {
                    // 获取当前项目的实际分数
                    int position = viewHolder.getAdapterPosition();
                    if (position != RecyclerView.NO_POSITION && adapter != null) {
                        String itemId = adapter.getIdForPosition(position);
                        int type = adapter.getTypeForPosition(position);
                        int actualScore = getCurrentInterest(type, itemId);
                        
                        // 更新进度条到实际分数
                        Drawable background = rootCard.getBackground();
                        if (background instanceof LayerDrawable) {
                            LayerDrawable layerDrawable = (LayerDrawable) background;
                            // 查找ClipDrawable层
                            ClipDrawable clipDrawable = null;
                            for (int i = 0; i < layerDrawable.getNumberOfLayers(); i++) {
                                Drawable d = layerDrawable.getDrawable(i);
                                if (d instanceof ClipDrawable) {
                                    clipDrawable = (ClipDrawable) d;
                                    break;
                                }
                            }
                            
                            if (clipDrawable != null) {
                                clipDrawable.setLevel(actualScore * 1000);
                                rootCard.invalidate();
                            }
                        }
                        
                        // Sheet的分数在滑动结束后隐藏
                        if (type == 1) { // Sheet类型
                            tvScore.setVisibility(View.GONE);
                            // 恢复旁边的tv_count可见性
                            android.widget.TextView tvCount = viewHolder.itemView.findViewById(R.id.tv_count);
                            if (tvCount != null) tvCount.setVisibility(View.VISIBLE);
                        }
                    }
                } catch (Throwable t) {
                    // 如果获取失败，重置为0
                    Drawable background = rootCard.getBackground();
                    if (background instanceof LayerDrawable) {
                        LayerDrawable layerDrawable = (LayerDrawable) background;
                        // 查找ClipDrawable层
                        ClipDrawable clipDrawable = null;
                        for (int i = 0; i < layerDrawable.getNumberOfLayers(); i++) {
                            Drawable d = layerDrawable.getDrawable(i);
                            if (d instanceof ClipDrawable) {
                                clipDrawable = (ClipDrawable) d;
                                break;
                            }
                        }
                        
                        if (clipDrawable != null) {
                            clipDrawable.setLevel(0);
                            rootCard.invalidate();
                        }
                    }
                }
            }
        }
    }
    
    /**
     * 更新滑动时的视觉反馈
     * 实时更新分数文本和进度条
     * - 分数转换为整数格子（0-10）
     * - 进度条使用ClipDrawable，level范围0-10000（每格1000）
     * 
     * @param viewHolder 要更新的ViewHolder
     * @param score      浮点分数（会四舍五入为整数）
     */
    private void updateSwipeVisualFloat(RecyclerView.ViewHolder viewHolder, float score) {
        if (viewHolder == null) return;
        
        View rootCard = viewHolder.itemView.findViewById(R.id.root_card);
        TextView tvScore = viewHolder.itemView.findViewById(R.id.tv_interest_score);
        
        if (rootCard == null) return;
        
        // 转换为整数格子（0-10）
        int displayScore = Math.max(0, Math.min(10, Math.round(score)));
        Log.i("HomeFragment", "hui_debug: displayScore 1: " + displayScore);
        // 更新分数文本（如果存在）
        if (tvScore != null) {
            tvScore.setText(String.valueOf(displayScore));
            // Sheet滑动时显示分数，平时隐藏
            tvScore.setVisibility(View.VISIBLE);
        }
        
        // 判断是否显示进度条（仅课程显示，标题不显示）
        boolean showProgressBar = true;
        if (adapter != null) {
            int pos = viewHolder.getAdapterPosition();
            if (pos != RecyclerView.NO_POSITION) {
                // 2 is COURSE. 0/1 are Header/Sheet.
                if (adapter.getItemViewType(pos) != 2) showProgressBar = false;
            }
        }
        
        // 更新进度条
        Drawable background = rootCard.getBackground();
        if (background instanceof LayerDrawable) {
            LayerDrawable layerDrawable = (LayerDrawable) background;
            // 查找ClipDrawable层
            ClipDrawable clipDrawable = null;
            for (int i = 0; i < layerDrawable.getNumberOfLayers(); i++) {
                Drawable d = layerDrawable.getDrawable(i);
                if (d instanceof ClipDrawable) {
                    clipDrawable = (ClipDrawable) d;
                    break;
                }
            }
            
            if (clipDrawable != null) {
                if (showProgressBar) {
                    //打印displayScore
                    Log.i("HomeFragment", "hui_debug: displayScore 2: " + displayScore);
                    clipDrawable.setLevel(displayScore * 1000);
                } else {
                    clipDrawable.setLevel(0);
                }
                rootCard.invalidate();
            }
        }
    }

    /**
     * 应用兴趣分数变化到指定项目
     * 根据项目类型，批量更新所有匹配的课程：
     * - type=0（文件）：更新该文件下的所有课程
     * - type=1（Sheet）：更新该Sheet下的所有课程
     * - type=2（课程）：更新单个课程
     * 
     * 更新后会刷新RecyclerView的对应项
     * 
     * @param type  项目类型：0=文件, 1=Sheet, 2=课程
     * @param id    项目ID
     * @param delta 分数变化量（可正可负）
     */
    private void applyInterestDeltaForItem(int type, String id, int delta) {
        parserExecutor.submit(() -> {
            List<Course> cur = vm.courses.getValue();
            if (cur == null) cur = new ArrayList<>();
            List<Course> updated = new ArrayList<>();
            String targetFile = null; 
            String targetSheet = null;
            
            // 根据类型确定影响范围
            if (type == 0) {
                targetFile = id;
            } else if (type == 1) {
                String[] parts = id == null ? new String[]{"",""} : id.split("\\|", 2);
                if (parts.length >= 1) targetFile = parts[0];
                if (parts.length == 2) targetSheet = parts[1];
            }
            
            StringBuilder changedIds = new StringBuilder();
            for (Course c : cur) {
                boolean match = false;
                // 判断课程是否在影响范围内
                if (type == 2) {
                    // 单个课程
                    if (c.id != null && c.id.equals(id)) match = true;
                } else if (type == 1) {
                    // Sheet下的所有课程
                    String sheetKey = ((c.fileId == null ? "<nofile>" : c.fileId).trim()) + "|" + ((c.sheetName == null ? "<nosheet>" : c.sheetName).trim());
                    String normId = id == null ? "" : id.trim();
                    if (!normId.isEmpty() && normId.equals(sheetKey)) match = true;
                } else if (type == 0) {
                    // 文件下的所有课程
                    String cf = c.fileId == null ? "<nofile>" : c.fileId;
                    if (cf.equals(targetFile)) match = true;
                }
                
                // 应用分数变化
                if (match) {
                    int before = c.interest;
                    int nv = Math.max(0, Math.min(10, before + delta));
                    if (nv != before) {
                        c.interest = nv;
                        changedIds.append(c.id).append("(").append(before).append("->").append(nv).append("),");
                    }
                }
                updated.add(c);
            }
            
            // 保存并刷新UI
            if (rv != null) {
                rv.post(() -> {
                    vm.save(updated);
                    // 等待保存完成后，刷新整个列表以确保所有课程分数更新
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (adapter != null) {
                            adapter.notifyDataSetChanged();
                        }
                    }, 100);
                });
            } else {
                vm.save(updated);
            }
        });
    }

    /**
     * 使用浮点增量对指定范围内的课程进行批量更新。
     * 与applyInterestDeltaForItem不同：这里基于每门课程的原始分数加上deltaFloat后取整，
     * 能保证视觉上滑动过程中显示的四舍五入与最终保存一致，避免+1/-1偏差。
     *
     * @param type      项目类型（0=文件，1=Sheet，2=课程）
     * @param id        项目标识
     * @param deltaFloat 浮点增量（可为负）
     */
    /**
     * Helper to generate Sheet ID consistent with CourseAdapter
     */
    private String getCourseSheetId(Course c) {
        String fileId = c.fileId == null ? "<nofile>" : c.fileId;
        String secondary = c.unit;
        if (secondary == null || secondary.trim().isEmpty()) {
            secondary = c.sheetName;
        }
        if (secondary == null || secondary.trim().isEmpty()) {
            secondary = "<unknown>";
        }
        return fileId + "|" + secondary;
    }
    
    private int getOriginalInterestForCourse(String courseId) {
        if (courseId == null) return 0;
        Integer original = originalInterestMap.get(courseId);
        return original != null ? original : 0;
    }

    private void applyInterestDeltaForItemFloat(int type, String id, float deltaFloat, java.util.Map<String, Integer> originalInterestMap) {
        Log.d("HomeFragment", "APPLY_INTEREST: type=" + type + ", id=" + id + ", delta=" + deltaFloat);
        parserExecutor.submit(() -> {
            List<Course> cur = vm.courses.getValue();
            if (cur == null) cur = new ArrayList<>();
            List<Course> updated = new ArrayList<>();
            String targetFile = null;
            String targetSheet = null;

            if (type == 0) {
                targetFile = id;
            } else if (type == 1) {
                String[] parts = id == null ? new String[]{"",""} : id.split("\\|", 2);
                if (parts.length >= 1) targetFile = parts[0];
                if (parts.length == 2) targetSheet = parts[1];
            }

            StringBuilder changedIds = new StringBuilder();
            for (Course c : cur) {
                boolean match = false;
                if (type == 2) {
                    if (c.id != null && c.id.equals(id)) match = true;
                } else if (type == 1) {
                    if (id != null && id.equals(getCourseSheetId(c))) match = true;
                } else if (type == 0) {
                    String cf = c.fileId == null ? "<nofile>" : c.fileId;
                    if (cf.equals(targetFile)) match = true;
                }

                if (match) {
                    int before = c.interest;
                    int nv;
                    if (type == 2) {
                        nv = Math.max(0, Math.min(10, Math.round(before + deltaFloat)));
                    } else {
                        Integer original = originalInterestMap.get(c.id);
                        int originalScore = (original != null) ? original : 0;
                        nv = Math.max(0, Math.min(10, Math.round(originalScore + deltaFloat)));
                    }
                    if (nv != before) {
                        c.interest = nv;
                        changedIds.append(c.id).append("(").append(before).append("->").append(nv).append("),");
                        Log.d("HomeFragment", "APPLY_INTEREST: Updated " + c.title + " (" + c.id + ") " + before + " -> " + nv);
                    }
                }
                updated.add(c);
            }

            Log.d("HomeFragment", "APPLY_INTEREST: Changed IDs: " + changedIds.toString());

            if (rv != null) {
                rv.post(() -> {
                    vm.save(updated);
                });
            } else {
                vm.save(updated);
            }
        });
    }

    private void showCourseDetail(Course c) {
        if (c == null) return;
        // Request 8: Beautify pop-ups using CourseDetailFragment
        try {
            CourseDetailFragment d = CourseDetailFragment.newInstance(c);
            d.show(getParentFragmentManager(), "course_detail");
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }
    
    /**
     * 创建选项菜单
     */
    @Override
    public void onCreateOptionsMenu(@NonNull Menu menu, @NonNull MenuInflater inflater) {
        inflater.inflate(R.menu.home_menu, menu);
        super.onCreateOptionsMenu(menu, inflater);
    }
    

    
    /**
     * 准备选项菜单（每次显示前调用）
     */
    @Override
    public void onPrepareOptionsMenu(@NonNull Menu menu) {
        // 设置"设置第一周"菜单项的图标颜色
        checkFirstWeekDateState(menu);
        super.onPrepareOptionsMenu(menu);
    }
    
    /**
     * 处理选项菜单点击事件
     */
    @Override
    public boolean onOptionsItemSelected(@NonNull MenuItem item) {
        // 直接处理菜单项点击
        if (item.getItemId() == R.id.menu_import) {
            // 导入课程表
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            String[] mimeTypes = {"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/csv", "application/vnd.ms-excel"};
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
            
            if (openFileLauncher != null) {
                openFileLauncher.launch(intent);
            }
            return true;
        } else if (item.getItemId() == R.id.menu_set_start_date) {
            // 设置第一周
            showSetDateDialog();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }
    
    /**
     * 检查第一周日期是否已设置
     */
    private boolean hasFirstWeekDate() {
        SharedPreferences prefs = getContext().getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE);
        String date = prefs.getString(KEY_FIRST_WEEK_DATE, null);
        return date != null && !date.isEmpty();
    }
}
