package com.pku.or.courseassistant.home;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.ClipDrawable;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.util.Log;

import com.pku.or.courseassistant.R;

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

    // 文件选择器启动器：用于选择Excel/CSV文件
    private ActivityResultLauncher<Intent> openFileLauncher;
    // 后台线程池：用于文件解析和数据处理
    private final ExecutorService parserExecutor = Executors.newSingleThreadExecutor();
    // 上一次的列映射结果：用于"使用上次映射"功能
    private ColumnMapDialogFragment.MappingResult previousMapping = null;
    // 是否应用映射到所有Sheet：用于批量导入时的快捷操作
    private boolean applyMappingToAll = false;

    /**
     * 待处理的Sheet数据
     * 用于在导入Excel文件时暂存每个Sheet的数据，等待用户选择列映射
     */
    private static class PendingSheet {
        String name;        // Sheet名称
        List<String[]> rows; // Sheet的所有行数据
        String[] header;    // 表头行
        int[] autoMap;      // 自动映射结果（title, time, teacher, unit的列索引）
        
        PendingSheet(String name, List<String[]> rows) { 
            this(name, rows, new int[]{-1, -1, -1, -1}); 
        }
        
        PendingSheet(String name, List<String[]> rows, int[] autoMap) { 
            this.name = name; 
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
        vm = new ViewModelProvider(this).get(HomeViewModel.class);

        // 注册文件选择器回调：当用户选择文件后，自动调用handleUri处理
        openFileLauncher = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), 
            result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null) {
                    handleUri(result.getData().getData());
                }
            }
        );
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
        Button btn = v.findViewById(R.id.btn_import);
        tvStatus = v.findViewById(R.id.tv_status);
        rv = v.findViewById(R.id.rv_courses);
        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new CourseAdapter(new ArrayList<>());
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
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(getContext());
            b.setTitle("操作: " + title);
            b.setItems(new String[]{"删除", "重新映射", "取消"}, (dialog, which) -> {
                if (which == 0) {
                    // 删除操作：弹出确认对话框
                    android.app.AlertDialog.Builder c = new android.app.AlertDialog.Builder(getContext());
                    c.setTitle("确认删除");
                    c.setMessage("确定要删除该项及其子项吗？此操作不可撤销。");
                    c.setPositiveButton("删除", (dd, ww) -> performDeleteTitle(type, id));
                    c.setNegativeButton("取消", null);
                    c.show();
                } else if (which == 1) {
                    // 重新映射操作
                    performRemapTitle(type, id);
                }
            });
            b.show();
        });

        /**
         * 导入按钮点击事件：打开文件选择器
         * 支持的文件类型：Excel（.xlsx）、CSV、旧版Excel（.xls）
         */
        btn.setOnClickListener(x -> {
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            // 支持的文件类型：Excel和CSV
            String[] mimeTypes = {"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/csv", "application/vnd.ms-excel"};
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
            
            if (openFileLauncher != null) {
                openFileLauncher.launch(intent);
            }
        });

        /**
         * 监听课程数据变化，更新UI
         * 当课程列表变化时，自动刷新RecyclerView和状态文本
         */
        vm.courses.observe(getViewLifecycleOwner(), list -> {
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
        // 滑动屏幕宽度50%对应10分，即每5%屏幕宽度对应1分
        final float pixelsPerScore = screenWidth * 0.05f; // 滑动屏幕宽度50%对应10分
        
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
                        
                        // 查找被触摸的项目
                        View childView = rv.findChildViewUnder(e.getX(), e.getY());
                        if (childView != null) {
                            currentViewHolder = rv.getChildViewHolder(childView);
                            if (currentViewHolder != null) {
                                int position = currentViewHolder.getAdapterPosition();
                                if (position != RecyclerView.NO_POSITION && adapter != null) {
                                    // 获取项目ID和类型，保存初始分数
                                    currentItemId = adapter.getIdForPosition(position);
                                    int type = adapter.getTypeForPosition(position);
                                    startScore = getCurrentInterest(type, currentItemId);
                                }
                            }
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
                if (currentViewHolder == null || currentItemId == null) return;
                
                switch (e.getAction()) {
                    case android.view.MotionEvent.ACTION_MOVE:
                        if (isSwipeGesture) {
                            // 根据滑动距离计算实时分数变化
                            float deltaX = e.getX() - startX;
                            float scoreChangeFloat = deltaX / pixelsPerScore;
                            float newScoreFloat = Math.max(0, Math.min(10, startScore + scoreChangeFloat));
                            // 更新视觉反馈（进度条动画）
                            updateSwipeVisualFloat(currentViewHolder, newScoreFloat);
                        }
                        break;
                        
                    case android.view.MotionEvent.ACTION_UP:
                    case android.view.MotionEvent.ACTION_CANCEL:
                        if (isSwipeGesture && currentViewHolder != null) {
                            // 松手时计算最终分数（整数）
                            float finalDeltaX = e.getX() - startX;
                            int scoreChange = (int)(finalDeltaX / pixelsPerScore);
                            int finalScore = Math.max(0, Math.min(10, startScore + scoreChange));
                            
                            if (finalScore != startScore) {
                                // 应用分数变化到数据模型
                                int delta = finalScore - startScore;
                                int type = adapter.getTypeForPosition(currentViewHolder.getAdapterPosition());
                                applyInterestDeltaForItem(type, currentItemId, delta);
                            }
                            
                            // 重置视觉效果
                            resetSwipeVisual(currentViewHolder);
                        }
                        
                        isSwipeGesture = false;
                        currentViewHolder = null;
                        currentItemId = null;
                        startScore = 0;
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
        // 此映射用于识别逻辑上相同的课程（即使ID不同）
        Map<String, Course> existingByLogicalKey = new HashMap<>();
        for (Course ex : cur) {
            String logicalKey = (ex.fileId == null ? "<nofile>" : ex.fileId) + "|" +
                               (ex.sheetName == null ? "<nosheet>" : ex.sheetName) + "|" +
                               (ex.title == null ? "" : ex.title.trim()) + "|" +
                               (ex.teachers == null ? "" : ex.teachers.trim()) + "|" +
                               (ex.unit == null ? "" : ex.unit.trim());
            existingByLogicalKey.put(logicalKey, ex);
        }

        List<Course> remaining = new ArrayList<>();
        List<Course> toAdd = new ArrayList<>();

        // 处理解析的课程：更新已存在的课程或添加新课程
        for (Course p : parsed) {
            String logicalKey = (p.fileId == null ? "<nofile>" : p.fileId) + "|" +
                               (p.sheetName == null ? "<nosheet>" : p.sheetName) + "|" +
                               (p.title == null ? "" : p.title.trim()) + "|" +
                               (p.teachers == null ? "" : p.teachers.trim()) + "|" +
                               (p.unit == null ? "" : p.unit.trim());

            Course existing = existingByLogicalKey.get(logicalKey);
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
            } else {
                // 新课程
                toAdd.add(p);
                remaining.add(p);
            }
        }

        // 添加未被替换的已存在课程
        for (Course ex : cur) {
            String logicalKey = (ex.fileId == null ? "<nofile>" : ex.fileId) + "|" +
                               (ex.sheetName == null ? "<nosheet>" : ex.sheetName) + "|" +
                               (ex.title == null ? "" : ex.title.trim()) + "|" +
                               (ex.teachers == null ? "" : ex.teachers.trim()) + "|" +
                               (ex.unit == null ? "" : ex.unit.trim());
            if (!existingByLogicalKey.containsKey(logicalKey) || !parsed.stream().anyMatch(p ->
                logicalKey.equals((p.fileId == null ? "<nofile>" : p.fileId) + "|" +
                                (p.sheetName == null ? "<nosheet>" : p.sheetName) + "|" +
                                (p.title == null ? "" : p.title.trim()) + "|" +
                                (p.teachers == null ? "" : p.teachers.trim()) + "|" +
                                (p.unit == null ? "" : p.unit.trim())))) {
                remaining.add(ex);
            }
        }

        vm.save(remaining);

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
                
                // 获取文件路径（用于标识导入来源）
                String path = uri.getLastPathSegment();
                List<Course> parsed = new ArrayList<>();
                
                // CSV文件处理
                if (path != null && path.toLowerCase().endsWith(".csv")) {
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
                                        List<Course> p = Course.fromRows(path, "sheet1", dataRows, result.titleIdx, result.timeIdx, result.teacherIdx, result.unitIdx);
                                        getActivity().runOnUiThread(() -> mergeParsedCourses(p));
                                        if (result.applyToAll) applyMappingToAll = true;
                                    });
                                }

                                @Override
                                public void onUsePrevious() {
                                    if (previousMapping != null) {
                                        parserExecutor.submit(() -> {
                                            List<Course> p = Course.fromRows(path, "sheet1", dataRows, previousMapping.titleIdx, previousMapping.timeIdx, previousMapping.teacherIdx, previousMapping.unitIdx);
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
                    for (XlsxLightParser.Sheet s : pr.sheets) {
                        if (s.rows.size() > 0) {
                            String[] header = s.rows.get(0);
                            int[] map = autoMapHeader(header);
                            pending.add(new PendingSheet(s.name, s.rows, map));
                        }
                    }

                    // 依次处理每个Sheet
                    if (!pending.isEmpty()) {
                        getActivity().runOnUiThread(() -> processPendingSheets(pending, parsed, path));
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
     * @return 映射结果数组[titleIdx, timeIdx, teacherIdx, unitIdx]，未找到的列返回-1
     */
    private int[] autoMapHeader(String[] header) {
        int titleIdx = -1, timeIdx = -1, teacherIdx = -1, unitIdx = -1;
        if (header == null) return new int[]{-1, -1, -1, -1};

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

        return new int[]{titleIdx, timeIdx, teacherIdx, unitIdx};
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
                    List<Course> p = Course.fromRows(path, ps.name, dataRows, result.titleIdx, result.timeIdx, result.teacherIdx, result.unitIdx);
                    synchronized (parsed) { parsed.addAll(p); }
                    getActivity().runOnUiThread(() -> mergeParsedCourses(p));
                    
                    // 如果选择"应用到所有"，批量处理剩余Sheet
                    if (result.applyToAll && previousMapping != null) {
                        List<PendingSheet> remaining = new ArrayList<>();
                        while (it.hasNext()) remaining.add(it.next());
                        for (PendingSheet ps2 : remaining) {
                            List<String[]> dataRows2 = ps2.rows.size() > 1 ? ps2.rows.subList(1, ps2.rows.size()) : new ArrayList<>();
                            List<Course> p2 = Course.fromRows(path, ps2.name, dataRows2, previousMapping.titleIdx, previousMapping.timeIdx, previousMapping.teacherIdx, previousMapping.unitIdx);
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
                        List<Course> p = Course.fromRows(path, ps.name, dataRows, previousMapping.titleIdx, previousMapping.timeIdx, previousMapping.teacherIdx, previousMapping.unitIdx);
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
        List<Course> cur = vm.courses.getValue();
        if (cur == null || cur.isEmpty()) return 5;
        if (type == 2) {
            // 课程类型：直接返回该课程的分数
            for (Course c : cur) if (c != null && c.id != null && c.id.equals(id)) return Math.max(0, Math.min(10, c.interest));
            return 5;
        } else if (type == 1) {
            // Sheet类型：计算该Sheet下所有课程的平均分
            String[] parts = id == null ? new String[]{"",""} : id.split("\\|", 2);
            String f = parts.length>0?parts[0].trim():"";
            String s = parts.length>1?parts[1].trim():"";
            int total = 0, count = 0;
            for (Course c : cur) {
                String cf = (c.fileId == null ? "<nofile>" : c.fileId).trim();
                String cs = (c.sheetName == null ? "<nosheet>" : c.sheetName).trim();
                if (cf.equals(f) && cs.equals(s)) {
                    total += Math.max(0, Math.min(10, c.interest));
                    count++;
                }
            }
            return count > 0 ? total / count : 5;
        } else if (type == 0) {
            // 文件类型：计算该文件下所有课程的平均分
            String target = id == null ? "<nofile>" : id.trim();
            int total = 0, count = 0;
            for (Course c : cur) {
                String cf = c.fileId == null ? "<nofile>" : c.fileId;
                if (cf.equals(target)) {
                    total += Math.max(0, Math.min(10, c.interest));
                    count++;
                }
            }
            return count > 0 ? total / count : 5;
        }
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
        
        // 更新分数文本
        if (tvScore != null) {
            tvScore.setText(String.valueOf(displayScore));
        }
        
        // 更新进度条
        Drawable background = rootCard.getBackground();
        if (background instanceof LayerDrawable) {
            LayerDrawable layerDrawable = (LayerDrawable) background;
            if (layerDrawable.getNumberOfLayers() > 1) {
                Drawable fillLayer = layerDrawable.getDrawable(1);
                if (fillLayer instanceof ClipDrawable) {
                    ClipDrawable clipDrawable = (ClipDrawable) fillLayer;
                    clipDrawable.setLevel(displayScore * 1000);
                    rootCard.invalidate();
                }
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
                    String[] parts = changedIds.toString().split(",");
                    // 刷新每个被修改的课程项
                    for (String part : parts) {
                        if (part != null && !part.isEmpty()) {
                            String cid = part.split("\\(")[0].trim();
                            if (!cid.isEmpty()) {
                                int p = adapter.findPositionById(cid);
                                if (p >= 0) {
                                    rv.post(() -> adapter.notifyItemChanged(p));
                                }
                            }
                        }
                    }
                });
            } else {
                vm.save(updated);
            }
        });
    }
}
