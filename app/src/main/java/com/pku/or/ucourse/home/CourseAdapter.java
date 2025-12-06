package com.pku.or.ucourse.home;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Color;
import androidx.transition.AutoTransition;
import androidx.transition.TransitionManager;
import android.util.TypedValue;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.ImageView;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.pku.or.ucourse.R;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * 课程列表适配器
 * 管理课程的三级展示结构：文件 -> Sheet -> 课程
 * 支持展开/折叠、长按操作、滑动评分视觉反馈
 */
public class CourseAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {
    // 项目类型常量
    private static final int TYPE_FILE = 0;   // 文件类型
    private static final int TYPE_SHEET = 1;  // Sheet类型
    private static final int TYPE_COURSE = 2; // 课程类型
    
    // Sheet信息辅助类（用于排序）
    private static class SheetInfo {
        String sheetName;
        int minSheetIndex;  // 该Sheet中最小的课程sheetIndex
        List<Course> courses = new ArrayList<>();
        
        SheetInfo(String name, int index) {
            this.sheetName = name;
            this.minSheetIndex = index;
        }
    }

    // 扁平化的显示列表（包含所有可见的文件/Sheet/课程）
    private final List<Item> display = new ArrayList<>();
    // 原始课程列表（保留完整数据，用于重建display）
    private List<Course> rawCourses = new ArrayList<>();
    // 展开状态存储（跨数据更新保留状态）
    private final Set<String> expandedFiles = new HashSet<>();
    private final Set<String> expandedSheets = new HashSet<>();
    // 缓存每个文件的Sheet数量（重建display时计算）
    private final Map<String, Integer> sheetCountByFile = new java.util.HashMap<>();
    // 缓存每个Sheet的课程数量
    private final Map<String, Integer> courseCountBySheet = new java.util.HashMap<>();
    
    // 统计信息用于样式展示
    private final Map<String, HeaderStats> statsMap = new java.util.HashMap<>();

    private static class HeaderStats {
        boolean allZero = true;
        boolean allTen = true;
        boolean hasTen = false;
        
        void update(Course c) {
            if (c == null) return;
            if (c.interest > 0) allZero = false;
            if (c.interest < 10) allTen = false;
            if (c.interest == 10) hasTen = true;
        }
        
        void merge(HeaderStats other) {
            if (!other.allZero) allZero = false;
            if (!other.allTen) allTen = false;
            if (other.hasTen) hasTen = true;
        }
    }
    
    /**
     * 长按监听器接口
     * 用于响应用户长按文件/Sheet/课程的操作
     */
    public interface OnItemLongPressListener {
        void onItemLongPress(int type, String id, int position);
    }
    
    public interface OnCourseClickListener {
        void onCourseClick(Course course);
    }

    private OnItemLongPressListener longPressListener = null;
    private OnCourseClickListener courseClickListener = null;

    public void setOnItemLongPressListener(OnItemLongPressListener l) { this.longPressListener = l; }
    public void setOnCourseClickListener(OnCourseClickListener l) { this.courseClickListener = l; }

    public CourseAdapter(List<Course> items) {
        setHasStableIds(true);
        setCourses(items);
    }

    /**
     * 显示项包装类
     * 将文件/Sheet/课程统一为Item，便于RecyclerView扁平化显示
     */
    private static class Item {
        int type;        // 项目类型：TYPE_FILE、TYPE_SHEET、TYPE_COURSE
        String id;       // 项目ID：文件=fileId, Sheet=fileId|sheetName, 课程=course.id
        String title;    // 显示标题
        Course course;   // 课程对象（仅课程类型时有值）
        String parentId; // 父ID：Sheet的父=fileId, 课程的父=fileId|sheetName
        String fileId;   // 文件ID（仅文件和Sheet类型时有值）
        String sheetName; // Sheet名称（仅Sheet类型时有值）
        String sheetFileId; // Sheet所属的文件ID（仅Sheet类型时有值）
    }

    /**
     * 设置课程列表（公共API）
     * 根据fileId和sheetName自动分组，构建三级结构
     * 
     * @param courses 原始课程列表
     */
    public void setCourses(List<Course> courses) {
        rawCourses = courses == null ? new ArrayList<>() : new ArrayList<>(courses);
        // 重建显示列表并应用DiffUtil计算差异（启用动画效果）
        List<Item> newDisplay = buildDisplayFromRaw();
        applyDisplayDiff(newDisplay);
    }
    
    /**
     * 从原始课程列表构建扁平化的显示列表
     * 按文件->Sheet->课程的层级结构组织数据
     * 根据展开状态决定是否显示子项
     * 
     * @return 扁平化的Item列表，用于RecyclerView显示
     */
    private List<Item> buildDisplayFromRaw() {
        List<Item> out = new ArrayList<>();
        if (rawCourses == null || rawCourses.isEmpty()) return out;
        
        // === 重建统计信息 ===
        statsMap.clear();
        for (Course c : rawCourses) {
            String fileId = c.fileId == null ? "<nofile>" : c.fileId;
            String sheetId = fileId + "|" + (c.sheetName == null ? "<nosheet>" : c.sheetName);
            
            if (!statsMap.containsKey(fileId)) statsMap.put(fileId, new HeaderStats());
            statsMap.get(fileId).update(c);
            
            if (!statsMap.containsKey(sheetId)) statsMap.put(sheetId, new HeaderStats());
            statsMap.get(sheetId).update(c);
        }
        
        // 第一步：按文件和Sheet分组
        // 使用临时结构存储Sheet信息（包括最小的sheetIndex）
        Map<String, Map<String, SheetInfo>> byFile = new LinkedHashMap<>();
        for (Course c : rawCourses) {
            String f = c.fileId == null ? "<nofile>" : c.fileId;
            String s = c.sheetName == null ? "<nosheet>" : c.sheetName;
            byFile.putIfAbsent(f, new LinkedHashMap<>());
            Map<String, SheetInfo> fm = byFile.get(f);
            if (!fm.containsKey(s)) {
                fm.put(s, new SheetInfo(s, c.sheetIndex));
            }
            fm.get(s).courses.add(c);
            // 更新最小sheetIndex（用于排序）
            if (c.sheetIndex < fm.get(s).minSheetIndex) {
                fm.get(s).minSheetIndex = c.sheetIndex;
            }
        }
        
        // 第二步：构建扁平化显示列表
        for (Map.Entry<String, Map<String, SheetInfo>> fe : byFile.entrySet()) {
            String fileId = fe.getKey();
            // 记录该文件的Sheet数量（用于徽章显示）
            try { sheetCountByFile.put(fileId, fe.getValue() == null ? 0 : fe.getValue().size()); } catch (Throwable _t) { sheetCountByFile.put(fileId, 0); }
            
            // 添加文件项
            Item fileItem = new Item();
            fileItem.type = TYPE_FILE;
            fileItem.id = fileId;
            fileItem.title = fileId;
            fileItem.fileId = fileId;
            out.add(fileItem);
            
            Map<String, SheetInfo> sheets = fe.getValue();
            // 只有当文件展开时才显示Sheet
            if (expandedFiles.contains(fileId)) {
                // 按sheetIndex排序Sheet
                List<SheetInfo> sortedSheets = new ArrayList<>(sheets.values());
                java.util.Collections.sort(sortedSheets, (a, b) -> Integer.compare(a.minSheetIndex, b.minSheetIndex));
                
                // 添加Sheet项
                for (SheetInfo sheetInfo : sortedSheets) {
                    String sheetName = sheetInfo.sheetName;
                    String sheetId = fileId + "|" + sheetName;
                    
                    // 记录该Sheet的课程数量
                    List<Course> coursesInSheet = sheetInfo.courses;
                    courseCountBySheet.put(sheetId, coursesInSheet != null ? coursesInSheet.size() : 0);
                    
                    Item sheetItem = new Item();
                    sheetItem.type = TYPE_SHEET;
                    sheetItem.id = sheetId;
                    sheetItem.title = sheetName;
                    sheetItem.sheetName = sheetName;
                    sheetItem.sheetFileId = fileId;
                    sheetItem.parentId = fileId;
                    out.add(sheetItem);
                    
                    // 只有当Sheet展开时才显示课程
                    if (expandedSheets.contains(sheetItem.id)) {
                        for (Course c : coursesInSheet) {
                            Item ci = new Item();
                            ci.type = TYPE_COURSE;
                            ci.id = c.id;
                            ci.title = c.title;
                            ci.course = c;
                            ci.parentId = sheetItem.id;
                            out.add(ci);
                        }
                    }
                }
            }
        }
        return out;
    }

    /**
     * 创建ViewHolder
     * 根据viewType加载不同的布局（文件、Sheet、课程）
     */
    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inf = LayoutInflater.from(parent.getContext());
        View v;
        if (viewType == TYPE_FILE) {
            v = inf.inflate(R.layout.item_course_file, parent, false);
        } else if (viewType == TYPE_SHEET) {
            v = inf.inflate(R.layout.item_course_sheet, parent, false);
        } else {
            v = inf.inflate(R.layout.item_course_simple, parent, false);
        }
        return new VH(v);
    }

    /**
     * 绑定数据到ViewHolder
     * 根据项目类型（文件/Sheet/课程）显示不同内容：
     * - 文件：显示文件名、Sheet数量、展开箭头、渐变色条
     * - Sheet：显示Sheet名称、课程数量、展开箭头、浅色背景
     * - 课程：显示课程详细信息、评分进度条、滑动视觉反馈
     */
    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        Item it = display.get(position);
        VH vh = (VH) holder;
        final int pos = position;
        
        if (it.type == TYPE_FILE) {
            // === 文件类型绑定 ===
            // 提取文件名（去除路径）
            String full = it.title == null ? "(file)" : it.title;
            String base = full;
            int idx = full.lastIndexOf('/');
            if (idx >= 0 && idx < full.length()-1) base = full.substring(idx+1);
            
            // 去除文件扩展名和括号编号（例如：file(1).xlsx → file）
            base = base.replaceAll("\\.[a-zA-Z0-9]+$", ""); // 去除扩展名（.xlsx, .csv等）
            base = base.replaceAll("\\(\\d+\\)$", "");      // 去除括号编号（(1), (2)等）
            base = base.trim();
            
            // 使用缓存的Sheet数量（在构建display时计算）
            // Prefer computing sheet count from current visible display to reflect expand/collapse state
            int sheetCount = computeVisibleSheetCountForFile(it.id == null ? "<nofile>" : it.id);
            // compute raw total from rawCourses for diagnostics
            int cachedCount = sheetCountByFile.getOrDefault(it.id == null ? "<nofile>" : it.id, 0);
            int rawCount = 0;
            try {
                java.util.Set<String> names = new java.util.HashSet<>();
                for (Course c : rawCourses) {
                    String f = c.fileId == null ? "<nofile>" : c.fileId;
                    if (!f.equals(it.id)) continue;
                    names.add(c.sheetName == null ? "<nosheet>" : c.sheetName);
                }
                rawCount = names.size();
            } catch (Throwable _t) { rawCount = cachedCount; }
            // Debug log to help diagnose incorrect counts shown in UI
            try { android.util.Log.d("CourseAdapter", "bind file='" + it.id + "' visible=" + sheetCount + " cached=" + cachedCount + " raw=" + rawCount + " expanded=" + expandedFiles.contains(it.id)); } catch (Throwable _t) {}
            // 只显示文件名，不在标题内重复显示数量（数量已在右侧[ N ] 显示）
            String disp = maybeTruncate(base);
            vh.tvTitle.setText(disp);
            vh.tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            vh.tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
            vh.tvTitle.setTextColor(ContextCompat.getColor(vh.itemView.getContext(), R.color.black));
            
            // 绑定数量标签（显示为[N]）
            try {
                android.widget.TextView tvCount = vh.itemView.findViewById(R.id.tv_count);
                if (tvCount != null) {
                    // 防御性约束：避免展示异常大或负数
                    int safeCount = sheetCount;
                    if (safeCount < 0) safeCount = 0;
                    if (safeCount > 999) safeCount = 999; // cap to reasonable upper bound
                    tvCount.setText(String.valueOf(safeCount));
                    // 使用强调色渲染数量标签
                    int accent = generateAccentEndForKey(it.id == null ? "<nofile>" : it.id);
                    tvCount.setTextColor(accent);
                    tvCount.setVisibility(View.VISIBLE); // Ensure visible
                    // 在contentDescription中保留原始诊断信息，便于辅助调试
                    try {
                        String desc = "s=" + sheetCount + ",c=" + cachedCount + ",r=" + rawCount + ",exp=" + expandedFiles.contains(it.id);
                        tvCount.setContentDescription(desc);
                    } catch (Throwable _t2) { }
                }
                // 隐藏分数显示（只在滑动时显示），并重置状态
                android.widget.TextView tvScore = vh.itemView.findViewById(R.id.tv_interest_score);
                if (tvScore != null) {
                    tvScore.setVisibility(View.GONE);
                }
            } catch (Throwable _t) {}
            vh.tvMeta.setText("");
            
            // 确保标题单行显示，超出部分省略
            vh.tvTitle.setSingleLine(true);
            vh.tvTitle.setEllipsize(TextUtils.TruncateAt.END);
            
            // 根据展开状态旋转箭头（展开90度，折叠0度）
            if (vh.arrow != null) {
                vh.arrow.setRotation(expandedFiles.contains(it.id) ? 90f : 0f);
                // 设置箭头颜色
                try {
                    if (vh.arrow instanceof ImageView) ((ImageView)vh.arrow).setColorFilter(ContextCompat.getColor(vh.itemView.getContext(), R.color.gray_700));
                } catch (Throwable _t) {}
            }
            
            // 设置文件渐变色条
            try {
                if (vh.accent != null) {
                    int color = generateAccentForKey(it.id == null ? "<nofile>" : it.id);
                    int endColor = generateAccentEndForKey(it.id == null ? "<nofile>" : it.id);
                    GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{color, endColor});
                    float radius = vh.itemView.getResources().getDisplayMetrics().density * 4f;
                    gd.setCornerRadius(radius);
                    // 文件色条稍宽
                    ViewGroup.LayoutParams lp = vh.accent.getLayoutParams();
                    lp.width = (int)(vh.itemView.getResources().getDisplayMetrics().density * 10);
                    vh.accent.setLayoutParams(lp);
                    vh.accent.setBackground(gd);
                    applyHeaderStyle(vh.rootCard, statsMap.get(it.id), R.color.background_color);
                }
            } catch (Throwable _t) {}
            
            // Apply score-based visual state (Shadow/Gray)
            applyHeaderStyle(vh.rootCard, statsMap.get(it.id), R.color.white);

            // 短按切换展开/折叠
            vh.itemView.setOnClickListener(v -> toggleFile(it.id, vh));
            // 添加2秒长按检测
            if (longPressListener != null) {
                setupLongPressDetector(vh.itemView, () -> longPressListener.onItemLongPress(it.type, it.id, pos));
            } else {
                vh.itemView.setOnTouchListener(null);
            }
            
        } else if (it.type == TYPE_SHEET) {
            // === Sheet类型绑定 ===
            // 美化Sheet标题：如果名称为xl/worksheets/sheet1.xml格式，尝试提取原始Sheet名
            String raw = it.title == null ? "(sheet)" : it.title;
            String pretty = raw;
            if (raw.startsWith("xl/worksheets/")) {
                // 可能是路径格式；移除前缀和扩展名
                pretty = raw.replaceFirst("^xl/worksheets/", "").replaceAll("\\.xml$", "");
            }
            String disp = maybeTruncate(pretty);
            vh.tvTitle.setText(disp);
            vh.tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
            vh.tvTitle.setTypeface(Typeface.DEFAULT);
            vh.tvTitle.setTextColor(ContextCompat.getColor(vh.itemView.getContext(), R.color.text_secondary));
            
            // 显示课程数量
            int courseCount = courseCountBySheet.getOrDefault(it.id, 0);
            try {
                android.widget.TextView tvCount = vh.itemView.findViewById(R.id.tv_count);
                if (tvCount != null) {
                    tvCount.setText(String.valueOf(courseCount));
                    tvCount.setVisibility(View.VISIBLE);
                }
            } catch (Throwable _t) {}
            
            // 隐藏分数显示（只在滑动时显示）
            try {
                android.widget.TextView tvScore = vh.itemView.findViewById(R.id.tv_interest_score);
                if (tvScore != null) {
                    tvScore.setVisibility(View.GONE);
                }
            } catch (Throwable _t) {}
            
            vh.tvMeta.setText("");
            vh.tvMeta.setTextColor(ContextCompat.getColor(vh.itemView.getContext(), R.color.gray_600));
            vh.tvTitle.setSingleLine(true);
            vh.tvTitle.setEllipsize(TextUtils.TruncateAt.END);
            
            // 箭头根据展开状态旋转
            if (vh.arrow != null) {
                vh.arrow.setRotation(expandedSheets.contains(it.id) ? 90f : 0f);
                try {
                    if (vh.arrow instanceof ImageView) ((ImageView)vh.arrow).setColorFilter(ContextCompat.getColor(vh.itemView.getContext(), R.color.gray_600));
                } catch (Throwable _t) {}
            }
            try {
                if (vh.accent != null) {
                    String key = it.parentId != null ? it.parentId : (it.id != null ? it.id : "<nofile>");
                    int color = generateAccentForKey(key);
                    int endColor = generateAccentEndForKey(key);
                    GradientDrawable gd = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, new int[]{color, endColor});
                    float radius = vh.itemView.getResources().getDisplayMetrics().density * 4f;
                    gd.setCornerRadius(radius);
                    // sheets use thinner accent and lower elevation
                    ViewGroup.LayoutParams lp = vh.accent.getLayoutParams();
                    lp.width = (int)(vh.itemView.getResources().getDisplayMetrics().density * 6);
                    vh.accent.setLayoutParams(lp);
                    vh.accent.setBackground(gd);
                    applyHeaderStyle(vh.rootCard, statsMap.get(it.id), R.color.sheet_row_bg);
                }
            } catch (Throwable _t) {}
            vh.itemView.setOnClickListener(v -> toggleSheet(it.id, vh));
            if (longPressListener != null) {
                setupLongPressDetector(vh.itemView, () -> longPressListener.onItemLongPress(it.type, it.id, pos));
            } else {
                vh.itemView.setOnTouchListener(null);
            }
        } else {
            // === 课程类型绑定 ===
            Course c = it.course;
            vh.tvTitle.setText(c.title == null || c.title.isEmpty() ? "(未命名)" : c.title);
            
            // 格式化课程信息：教师 · 时间（精简版）
            String metaText = formatCourseInfo(c);
            vh.tvMeta.setText(metaText);
            
            // set subtle grouping background based on parent sheet id to save horizontal space
            if (it.parentId != null) {
                // derive a stable but simple hash to alternate color per sheet
                int h = Math.abs(it.parentId.hashCode());
                boolean alt = (h % 2) == 0;
                Context ctx = vh.itemView.getContext();
                int color = ContextCompat.getColor(ctx, alt ? R.color.sheet_group_bg_even : R.color.sheet_group_bg_odd);
                vh.itemView.setBackgroundColor(color);
            } else {
                vh.itemView.setBackgroundColor(ContextCompat.getColor(vh.itemView.getContext(), android.R.color.transparent));
            }
            vh.itemView.setOnClickListener(v -> {
                if (courseClickListener != null) courseClickListener.onCourseClick(c);
            });
            if (longPressListener != null) {
                setupLongPressDetector(vh.itemView, () -> longPressListener.onItemLongPress(it.type, c.id, pos));
            } else {
                vh.itemView.setOnTouchListener(null);
            }
            vh.tvTitle.setSingleLine(true);
            vh.tvTitle.setEllipsize(TextUtils.TruncateAt.END);
            
            // Apply style based on score (Gray / Bright Shadow)
            applyCourseStyle(vh, c);
            
            // Render interest score fill and text
            renderInterestSegments(vh, c.interest);
            applyCourseStyle(vh, c);
        }
    }

    // Compute the number of visible sheet items under a file using current display (reflects expand/collapse)
    private int computeVisibleSheetCountForFile(String fileId) {
        if (fileId == null) fileId = "<nofile>";
        try {
            int cnt = 0;
            for (Item it : display) {
                if (it == null) continue;
                if (it.type == TYPE_SHEET && fileId.equals(it.parentId)) cnt++;
            }
            // If no sheets are currently visible (file collapsed), fall back to cached total sheet count
            if (cnt == 0) return sheetCountByFile.getOrDefault(fileId, 0);
            return cnt;
        } catch (Throwable _t) {
            return sheetCountByFile.getOrDefault(fileId, 0);
        }
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position, @NonNull List<Object> payloads) {
        if (payloads.isEmpty()) {
            // Full bind
            onBindViewHolder(holder, position);
        } else {
            // Partial update for interest changes
            Item it = display.get(position);
            VH vh = (VH) holder;
            if (it != null && it.type == TYPE_COURSE && it.course != null) {
                renderInterestSegments(vh, it.course.interest);
            }
        }
    }

    /**
     * 格式化课程信息为简洁格式
     * 显示：教师 · 时间（如：周一7~9节）· 地点
     * 
     * @param c 课程对象
     * @return 格式化后的字符串
     */
    /**
     * 格式化课程信息为简洁的显示格式
     * 只显示：教师名（无title） · 时间（精简） · 地点
     * 
     * @param c 课程对象
     * @return 格式化后的信息字符串
     */
    private String formatCourseInfo(Course c) {
        StringBuilder sb = new StringBuilder();
        
        // 1. 教师（移除"教师："前缀和括号内的职称）
        String teacher = c.teachers == null ? "" : c.teachers.trim();
        if (!teacher.isEmpty()) {
            // 移除可能的"教师："前缀
            teacher = teacher.replaceAll("^(教师|老师)[:：]?\\s*", "");
            // 移除括号及括号内的内容（如"(教授)"、"（副教授）"等）
            teacher = teacher.replaceAll("[\\(（][^\\)）]*[\\)）]", "");
            teacher = teacher.trim();
            if (!teacher.isEmpty()) {
                sb.append(teacher);
            }
        }
        
        // 2. 时间信息（精简版）
        String timeInfo = parseTimeInfo(c.rawTime);
        if (!timeInfo.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(timeInfo);
        }
        
        // 3. 地点
        String loc = c.location == null ? "" : c.location.trim();
        if (!loc.isEmpty()) {
            if (sb.length() > 0) sb.append(" · ");
            sb.append(loc);
        }
        
        return sb.toString();
    }
    
    /**
     * 解析时间字符串，提取关键信息
     * 输入示例："1-16周 每周一7-9节 二教301"
     * 输出示例："周一7~9节 · 二教301"
     * 
     * @param rawTime 原始时间字符串
     * @return 精简后的时间信息
     */
    private String parseTimeInfo(String rawTime) {
        if (rawTime == null || rawTime.trim().isEmpty()) {
            return "";
        }
        
        String time = rawTime.trim();
        
        // 移除周次信息（如"1-16周"、"1~16周"）
        time = time.replaceAll("\\d+[-~]\\d+周\\s*", "");
        // 移除"每周"
        time = time.replaceAll("每周", "");
        // 移除单独的"周"字后面的空格
        time = time.replaceAll("周\\s+", "周");
        // 规范化空格
        time = time.replaceAll("\\s+", " ");
        
        // 将"7-9节"改为"7~9节"（更简洁）
        time = time.replaceAll("(\\d+)-(\\d+)节", "$1~$2节");
        
        // 如果包含地点信息，用" · "分隔时间和地点
        // 尝试识别常见的地点模式（如"二教301"、"理教205"等）
        time = time.replaceAll("([节])\\s*([^\\s]+[教室楼馆]\\d+)", "$1 · $2");
        
        return time.trim();
    }

    // helper: truncate rule — if text too long (>40 chars) show first half
    private String maybeTruncate(String s) {
        if (s == null) return s;
        if (s.length() <= 40) return s;
        int half = s.length() / 2;
        return s.substring(0, half);
    }

    // helper: set up 2s long press detection on view; runnable will be executed on long press
    private void setupLongPressDetector(View view, Runnable onLongPress) {
        final Handler handler = new Handler(Looper.getMainLooper());
        final Runnable[] longR = new Runnable[1];
        longR[0] = () -> {
            try { onLongPress.run(); } catch (Throwable _t) {}
        };
        view.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    handler.postDelayed(longR[0], 2000);
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    handler.removeCallbacks(longR[0]);
                    break;
            }
            // return false so click listeners still receive click events (short tap)
            return false;
        });
    }

    @Override
    public int getItemCount() { return display.size(); }

    @Override
    public long getItemId(int position) {
        Item it = display.get(position);
        if (it == null) return position;
        String sid = it.id == null ? ("type" + it.type + "_idx" + position) : it.id;
        long h = (long) sid.hashCode() & 0xffffffffL;
        // mix in type to reduce collisions
        return ( ((long)it.type) << 56 ) ^ h;
    }

    @Override
    public int getItemViewType(int position) {
        return display.get(position).type;
    }

    // Public accessors for external helpers (e.g., swipe actions) to map adapter position -> item id/type
    public int getTypeForPosition(int position) {
        if (position < 0 || position >= display.size()) return -1;
        return display.get(position).type;
    }

    public String getIdForPosition(int position) {
        if (position < 0 || position >= display.size()) return null;
        return display.get(position).id;
    }

    public String getParentIdForPosition(int position) {
        if (position < 0 || position >= display.size()) return null;
        return display.get(position).parentId;
    }

    // Find position of item with given id
    public int findPositionById(String id) {
        if (id == null) return -1;
        for (int i = 0; i < display.size(); i++) {
            Item it = display.get(i);
            if (it != null && id.equals(it.id)) return i;
        }
        return -1;
    }

    // Programmatic collapse helpers: ensure files/sheets are collapsed (used after import)
    public void collapseFile(String fileId) {
        if (fileId == null) return;
        if (expandedFiles.remove(fileId)) {
            // also remove any expanded sheets under this file
            java.util.Iterator<String> it = expandedSheets.iterator();
            String prefix = fileId + "|";
            while (it.hasNext()) {
                String s = it.next();
                if (s != null && s.startsWith(prefix)) it.remove();
            }
            // refresh display
            List<Item> newDisplay = buildDisplayFromRaw();
            applyDisplayDiff(newDisplay);
        }
    }

    public void collapseFiles(java.util.Collection<String> fileIds) {
        if (fileIds == null || fileIds.isEmpty()) return;
        boolean changed = false;
        for (String fileId : fileIds) {
            if (fileId == null) continue;
            if (expandedFiles.remove(fileId)) changed = true;
            String prefix = fileId + "|";
            java.util.Iterator<String> it = expandedSheets.iterator();
            while (it.hasNext()) {
                String s = it.next();
                if (s != null && s.startsWith(prefix)) { it.remove(); changed = true; }
            }
        }
        if (changed) {
            List<Item> newDisplay = buildDisplayFromRaw();
            applyDisplayDiff(newDisplay);
        }
    }



    // Collapse all files
    public void collapseAllFiles() {
        if (expandedFiles.isEmpty()) return;
        expandedFiles.clear();
        List<Item> newDisplay = buildDisplayFromRaw();
        applyDisplayDiff(newDisplay);
    }

    static class VH extends RecyclerView.ViewHolder {
        TextView tvTitle, tvMeta, tvInterestScore;
        View arrow;
        View accent;
        View rootCard;
        VH(@NonNull View itemView) {
            super(itemView);
            tvTitle = itemView.findViewById(R.id.tv_title);
            tvMeta = itemView.findViewById(R.id.tv_meta);
            tvInterestScore = itemView.findViewById(R.id.tv_interest_score);
            // arrow may or may not exist depending on layout
            arrow = itemView.findViewById(R.id.iv_arrow);
            accent = itemView.findViewById(R.id.v_accent);
            rootCard = itemView.findViewById(R.id.root_card);
        }
    }

    // Toggle file expanded state and update the adapter with fine-grained notifications
    private void toggleFile(String fileId, VH vh) {
        boolean expand = !expandedFiles.contains(fileId);
        if (expand) expandedFiles.add(fileId); else expandedFiles.remove(fileId);
        List<Item> newDisplay = buildDisplayFromRaw();
        applyDisplayDiff(newDisplay);
        // animate arrow
        if (vh != null && vh.arrow != null) vh.arrow.animate().rotation(expand ? 90f : 0f).setDuration(200).start();
    }

    // Toggle sheet expanded state and update adapter
    private void toggleSheet(String sheetId, VH vh) {
        boolean expand = !expandedSheets.contains(sheetId);
        if (expand) expandedSheets.add(sheetId); else expandedSheets.remove(sheetId);
        List<Item> newDisplay = buildDisplayFromRaw();
        applyDisplayDiff(newDisplay);
        if (vh != null && vh.arrow != null) vh.arrow.animate().rotation(expand ? 90f : 0f).setDuration(200).start();
    }

    // Very small diff: compute first index where lists differ, then compute removed/inserted tail lengths
    private void applyDisplayDiff(List<Item> newDisplay) {
        // Use DiffUtil to compute minimal changes between display and newDisplay
        final List<Item> oldList = new ArrayList<>(display);
        final List<Item> newList = new ArrayList<>(newDisplay);
        DiffUtil.DiffResult diff = DiffUtil.calculateDiff(new ItemDiffCallback(oldList, newList));
        // update internal list and dispatch updates
        display.clear();
        display.addAll(newList);
        diff.dispatchUpdatesTo(this);
    }

    private static class ItemDiffCallback extends DiffUtil.Callback {
        private final List<Item> oldList;
        private final List<Item> newList;

        ItemDiffCallback(List<Item> oldList, List<Item> newList) {
            this.oldList = oldList;
            this.newList = newList;
        }

        @Override
        public int getOldListSize() { return oldList.size(); }

        @Override
        public int getNewListSize() { return newList.size(); }

        @Override
        public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
            Item a = oldList.get(oldItemPosition);
            Item b = newList.get(newItemPosition);
            if (a == null || b == null) return a == b;
            if (a.type != b.type) return false;
            if (a.id == null) return b.id == null;
            return a.id.equals(b.id);
        }

        @Override
        public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
            Item a = oldList.get(oldItemPosition);
            Item b = newList.get(newItemPosition);
            if (a == b) return true;
            if (a == null || b == null) return false;
            if (a.type != b.type) return false;
            // For file and sheet rows, identity is sufficient (title rarely changes). For course rows compare key fields.
            // Also compare stats for headers to trigger style updates
            if (a.type != TYPE_COURSE) {
                // Header rows: check if title or stats changed
                // Stats are stored in the adapter's statsMap, but here we can check if we should refresh.
                // Since statsMap is updated before diff calculation, and onBind reads from it,
                // we should consider content different if stats might have changed.
                // However, stats are derived from underlying courses.
                // Simplest way: always return false for headers if we want to ensure style updates, 
                // but that's inefficient.
                // Better: rely on 'notifyItemChanged' with payload from partial updates, 
                // or just compare the relevant stat properties if we had them in Item.
                // For now, let's assume if title is same, content is same, UNLESS we force update elsewhere.
                // BUT, to support real-time header styling updates during swipe, we need to be careful.
                // Actually, swipe updates are handled via direct view manipulation or notifyItemChanged(payload).
                // So here standard equality is fine.
                return java.util.Objects.equals(a.title, b.title);
            }

            if (a.type == TYPE_COURSE && b.type == TYPE_COURSE) {
                Course ca = a.course;
                Course cb = b.course;
                if (ca == cb) return true;
                if (ca == null || cb == null) return false;
                if (!safeEquals(ca.title, cb.title)) return false;
                if (!safeEquals(ca.teachers, cb.teachers)) return false;
                if (!safeEquals(ca.unit, cb.unit)) return false;
                if (!safeEquals(ca.rawTime, cb.rawTime)) return false;
                if (ca.interest != cb.interest) return false; // Include interest in comparison
                return true;
            }
            // fallback: compare titles
            return safeEquals(a.title, b.title);
        }

        private boolean safeEquals(Object x, Object y) {
            if (x == y) return true;
            if (x == null || y == null) return false;
            return x.equals(y);
        }
    }

    private boolean itemEquals(Item a, Item b) {
        if (a == b) return true;
        if (a == null || b == null) return false;
        if (a.type != b.type) return false;
        if (a.id == null) return b.id == null;
        return a.id.equals(b.id);
    }

    // simple deterministic color generator from key string
    private int generateAccentForKey(String key) {
        int h = Math.abs(key.hashCode());
        float hue = (h % 360);
        float[] hsv = new float[]{hue, 0.6f, 0.95f};
        return Color.HSVToColor(hsv);
    }

    private int generateAccentEndForKey(String key) {
        int h = Math.abs((key + "end").hashCode());
        float hue = (h % 360);
        // shift hue slightly for end color
        hue = (hue + 25) % 360;
        float[] hsv = new float[]{hue, 0.6f, 0.85f};
        return Color.HSVToColor(hsv);
    }

    // Render interest score visualization: background fill and score text
    private void renderInterestSegments(VH vh, int finalScore) {
        if (vh == null) return;

        // Update score text
        if (vh.tvInterestScore != null) {
            vh.tvInterestScore.setText(String.valueOf(finalScore));
        }

        // Update background fill using ClipDrawable
        if (vh.rootCard != null) {
            try {
                android.graphics.drawable.Drawable bg = vh.rootCard.getBackground();
                if (bg instanceof android.graphics.drawable.LayerDrawable) {
                    android.graphics.drawable.LayerDrawable ld = (android.graphics.drawable.LayerDrawable) bg;
                    int layers = ld.getNumberOfLayers();
                    android.graphics.drawable.Drawable fillLayer = null;
                    
                    // Determine which layer is the ClipDrawable based on drawable type
                    // bg_card_glow_10 has 3 layers, clip is at index 2
                    // bg_item_card has 2 layers, clip is at index 1
                    if (layers >= 3) {
                        fillLayer = ld.getDrawable(2);
                    } else if (layers >= 2) {
                        fillLayer = ld.getDrawable(1);
                    }
                    
                    if (fillLayer instanceof android.graphics.drawable.ClipDrawable) {
                        android.graphics.drawable.ClipDrawable cd = (android.graphics.drawable.ClipDrawable) fillLayer;
                        // Calculate level: 0-10000, where 10000 = 10.0 score
                        int level = (int) (10000f * (finalScore / 10.0f));
                        cd.setLevel(level);
                        cd.setAlpha(255);
                        vh.rootCard.invalidate();
                    }
                }
            } catch (Throwable _t) {
                // Fallback: ignore rendering errors
            }
        }
    }

    // === Styling Helpers ===

    private void applyHeaderStyle(View rootView, HeaderStats stats, int defaultColorRes) {
        if (rootView == null) return;
        Context ctx = rootView.getContext();
        
        // Reset elevation and state list animator
        rootView.setElevation(0f);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            rootView.setStateListAnimator(null);
        }

        int color = ContextCompat.getColor(ctx, defaultColorRes);
        
        if (stats != null) {
            if (stats.allZero) {
                // Rule A: Gray: All courses 0 -> Gray background, no shadow
                rootView.setBackgroundResource(R.drawable.bg_item_card);
                setCardLayerColor(rootView, 0, Color.parseColor("#EEEEEE"));
            } else if (stats.allTen) {
                // Rule B: Bright Glow: All 10 -> bg_card_glow_10
                // Glow drawable has glow at layer 0, background at layer 1
                rootView.setBackgroundResource(R.drawable.bg_card_glow_10);
                setCardLayerColor(rootView, 1, color);
            } else if (stats.hasTen) {
                // Rule E: Faint Shadow: Has 10 but not all 10 -> bg_card_glow_faint
                // Faint glow drawable has glow at layer 0, background at layer 1
                rootView.setBackgroundResource(R.drawable.bg_card_glow_faint);
                setCardLayerColor(rootView, 1, color);
            } else {
                // Rule D: No 10, not all 0 -> No shadow (Standard)
                rootView.setBackgroundResource(R.drawable.bg_item_card);
                setCardLayerColor(rootView, 0, color);
            }
        } else {
            // Default
            rootView.setBackgroundResource(R.drawable.bg_item_card);
            setCardLayerColor(rootView, 0, color);
        }
        
        // Reset progress bar level to 0 for headers (remove green bar)
        try {
            android.graphics.drawable.Drawable bg = rootView.getBackground();
            if (bg instanceof android.graphics.drawable.LayerDrawable) {
                android.graphics.drawable.LayerDrawable ld = (android.graphics.drawable.LayerDrawable) bg;
                // Check all layers for ClipDrawable and reset it
                for (int i = 0; i < ld.getNumberOfLayers(); i++) {
                     android.graphics.drawable.Drawable d = ld.getDrawable(i);
                     if (d instanceof android.graphics.drawable.ClipDrawable) {
                         ((android.graphics.drawable.ClipDrawable) d).setLevel(0);
                         d.setAlpha(0);
                     }
                }
            }
        } catch (Throwable _t) {}
    }

    private void applyCourseStyle(VH vh, Course c) {
        if (vh == null || vh.rootCard == null) return;
        View rootView = vh.rootCard;
        Context ctx = rootView.getContext();
        float density = ctx.getResources().getDisplayMetrics().density;
        
        // Default: No elevation to remove "gray area" artifacts
        float elevation = 0f;
        int titleColor = ContextCompat.getColor(ctx, R.color.header_text_color);
        int metaColor = ContextCompat.getColor(ctx, R.color.text_secondary);
        
        if (c != null) {
            if (c.interest == 10) {
                // Use special glow drawable
                rootView.setBackgroundResource(R.drawable.bg_card_glow_10);
                elevation = 0f; // Glow is in the drawable
                
                // Reset outline provider to default as we don't need clipping anymore
                rootView.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
                rootView.setClipToOutline(false);
            } else {
                // Standard card
                rootView.setBackgroundResource(R.drawable.bg_item_card);
                
                if (c.interest == 0) {
                    // Gray text for 0 score
                    titleColor = Color.parseColor("#9E9E9E"); // Lighter gray
                    metaColor = Color.parseColor("#BDBDBD");
                }
                rootView.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
                rootView.setClipToOutline(false);
            }
            
            // Update text colors
            if (vh.tvTitle != null) vh.tvTitle.setTextColor(titleColor);
            if (vh.tvMeta != null) vh.tvMeta.setTextColor(metaColor);
            
            // Update progress bar immediately
            renderInterestSegments(vh, c.interest);
            
            // Apply elevation (which is 0 now)
            rootView.setElevation(elevation);
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
                rootView.setStateListAnimator(null);
            }
        }
    }

    private void setCardLayerColor(View view, int layerIndex, int color) {
        if (view == null) return;
        try {
            android.graphics.drawable.Drawable bg = view.getBackground();
            if (bg instanceof android.graphics.drawable.LayerDrawable) {
                android.graphics.drawable.LayerDrawable ld = (android.graphics.drawable.LayerDrawable) bg;
                if (ld.getNumberOfLayers() > layerIndex) {
                    android.graphics.drawable.Drawable l = ld.getDrawable(layerIndex);
                    if (l instanceof GradientDrawable) {
                        ((GradientDrawable) l).setColor(color);
                    } else {
                        l.setTint(color);
                    }
                }
            } else if (layerIndex == 0) {
                if (bg instanceof GradientDrawable) {
                    ((GradientDrawable) bg).setColor(color);
                } else {
                    view.setBackgroundTintList(android.content.res.ColorStateList.valueOf(color));
                }
            }
        } catch (Throwable _t) {}
    }

    private void setCardBackground(View view, int color) {
        setCardLayerColor(view, 0, color);
    }

    /**
     * Temporarily update item style based on a drag score (visual only, no model update)
     * Used for real-time drag feedback
     */
    public void updateItemStyle(RecyclerView.ViewHolder vh, int score, int type) {
        if (vh == null || !(vh instanceof VH)) return;
        VH holder = (VH) vh;
        View rootView = holder.rootCard;
        if (rootView == null) return;
        
        Context ctx = rootView.getContext();
        
        if (type == TYPE_COURSE) {
            if (score == 10) {
                rootView.setBackgroundResource(R.drawable.bg_card_glow_10);
                rootView.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
                rootView.setClipToOutline(false);
                // Normal text
                int titleColor = ContextCompat.getColor(ctx, R.color.header_text_color);
                int metaColor = ContextCompat.getColor(ctx, R.color.text_secondary);
                if (holder.tvTitle != null) holder.tvTitle.setTextColor(titleColor);
                if (holder.tvMeta != null) holder.tvMeta.setTextColor(metaColor);
            } else {
                rootView.setBackgroundResource(R.drawable.bg_item_card);
                rootView.setOutlineProvider(android.view.ViewOutlineProvider.BACKGROUND);
                rootView.setClipToOutline(false);
                
                if (score == 0) {
                    // Gray text
                    int titleColor = Color.parseColor("#9E9E9E");
                    int metaColor = Color.parseColor("#BDBDBD");
                    if (holder.tvTitle != null) holder.tvTitle.setTextColor(titleColor);
                    if (holder.tvMeta != null) holder.tvMeta.setTextColor(metaColor);
                } else {
                    // Normal text
                    int titleColor = ContextCompat.getColor(ctx, R.color.header_text_color);
                    int metaColor = ContextCompat.getColor(ctx, R.color.text_secondary);
                    if (holder.tvTitle != null) holder.tvTitle.setTextColor(titleColor);
                    if (holder.tvMeta != null) holder.tvMeta.setTextColor(metaColor);
                }
            }
            // Update progress bar
            renderInterestSegments(holder, score);
            
        } else {
            // Header (File or Sheet)
            int defaultColorRes = (type == TYPE_SHEET) ? R.color.sheet_row_bg : R.color.background_color;
            int color = ContextCompat.getColor(ctx, defaultColorRes);
            
            if (score == 10) {
                // All 10 -> Glow
                rootView.setBackgroundResource(R.drawable.bg_card_glow_10);
                // Glow drawable has glow at layer 0, background at layer 1
                setCardLayerColor(rootView, 1, color);
            } else if (score == 0) {
                // All 0 -> Gray
                rootView.setBackgroundResource(R.drawable.bg_item_card);
                // Standard card has background at layer 0
                setCardLayerColor(rootView, 0, Color.parseColor("#EEEEEE"));
            } else if (score == -1) {
                // Mixed with 10 -> Faint Glow
                rootView.setBackgroundResource(R.drawable.bg_card_glow_faint);
                // Faint glow drawable has glow at layer 0, background at layer 1
                setCardLayerColor(rootView, 1, color);
            } else {
                // Mixed -> Standard
                if (type == TYPE_SHEET) {
                    rootView.setBackgroundResource(R.drawable.bg_sheet_card);
                } else {
                    rootView.setBackgroundResource(R.drawable.bg_item_card);
                }
                // Standard/Sheet card has background at layer 0
                setCardLayerColor(rootView, 0, color);
            }
            
            // Reset header progress bar (always 0)
            try {
                android.graphics.drawable.Drawable bg = rootView.getBackground();
                if (bg instanceof android.graphics.drawable.LayerDrawable) {
                    android.graphics.drawable.LayerDrawable ld = (android.graphics.drawable.LayerDrawable) bg;
                    for (int i = 0; i < ld.getNumberOfLayers(); i++) {
                         android.graphics.drawable.Drawable d = ld.getDrawable(i);
                         if (d instanceof android.graphics.drawable.ClipDrawable) {
                             ((android.graphics.drawable.ClipDrawable) d).setLevel(0);
                             d.setAlpha(0);
                         }
                    }
                }
            } catch (Throwable _t) {}
        }
        
        rootView.setElevation(0f);
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.LOLLIPOP) {
            rootView.setStateListAnimator(null);
        }
    }

    /**
     * Update header style based on a drag delta applied to all its children.
     * This calculates the hypothetical state of children and updates the header visual.
     * Also updates the parent File header if the dragged item is a Sheet.
     */
    public void updateHeaderStyleForDrag(RecyclerView rv, RecyclerView.ViewHolder vh, float delta) {
        if (vh == null) return;
        int pos = vh.getAdapterPosition();
        if (pos == RecyclerView.NO_POSITION) return;
        if (pos < 0 || pos >= display.size()) return;
        
        Item item = display.get(pos);
        if (item.type == TYPE_COURSE) return;
        
        String fileId = null;
        String sheetName = null;
        
        if (item.type == TYPE_SHEET) {
             fileId = item.sheetFileId;
             sheetName = item.sheetName;
        } else if (item.type == TYPE_FILE) {
             fileId = item.fileId;
        }
        
        if (fileId == null) return;
        
        // 1. Update the dragged header itself
        // Calculate stats based on rawCourses
        boolean allZero = true;
        boolean allTen = true;
        boolean hasTen = false;
        boolean hasAny = false;
        
        for (Course c : rawCourses) {
             boolean match = false;
             if (item.type == TYPE_SHEET) {
                 match = (c.fileId != null && c.fileId.equals(fileId)) && 
                         (c.sheetName != null && c.sheetName.equals(sheetName));
             } else {
                 match = (c.fileId != null && c.fileId.equals(fileId));
             }
             
             if (match) {
                 hasAny = true;
                 // Calculate hypothetical score
                 float newScore = Math.max(0, Math.min(10, c.interest + delta));
                 
                 // Use epsilon for float comparison
                 if (newScore < 9.9f) allTen = false;
                 if (newScore > 0.1f) allZero = false;
                 if (newScore >= 9.9f) hasTen = true;
             }
        }
        
        if (hasAny) {
            // Determine state code
            int stateScore;
            if (allTen) stateScore = 10;
            else if (allZero) stateScore = 0;
            else if (hasTen) stateScore = -1; // Faint Glow
            else stateScore = 5; // Standard
            
            updateItemStyle(vh, stateScore, item.type);
        }

        // 2. If it is a Sheet, update the parent File header
        if (item.type == TYPE_SHEET && rv != null) {
             boolean fAllZero = true;
             boolean fAllTen = true;
             boolean fHasTen = false;
             boolean fHasAny = false;
             
             for (Course c : rawCourses) {
                 String f = c.fileId == null ? "<nofile>" : c.fileId;
                 if (f.equals(fileId)) {
                     fHasAny = true;
                     float score = c.interest;
                     // If this course belongs to the dragged Sheet, apply delta
                     String s = c.sheetName == null ? "<nosheet>" : c.sheetName;
                     if (s.equals(sheetName)) {
                         score = Math.max(0, Math.min(10, c.interest + delta));
                     }
                     
                     if (score < 9.9f) fAllTen = false;
                     if (score > 0.1f) fAllZero = false;
                     if (score >= 9.9f) fHasTen = true;
                 }
             }
             
             if (fHasAny) {
                 int stateScore;
                 if (fAllTen) stateScore = 10;
                 else if (fAllZero) stateScore = 0;
                 else if (fHasTen) stateScore = -1;
                 else stateScore = 5;
                 
                 int filePos = findPositionById(fileId);
                 if (filePos != -1) {
                     RecyclerView.ViewHolder fVh = rv.findViewHolderForAdapterPosition(filePos);
                     if (fVh != null) {
                         updateItemStyle(fVh, stateScore, TYPE_FILE);
                     }
                 }
             }
        }
    }

    /**
     * Update parent header style based on a single course's hypothetical drag score.
     * Used for real-time feedback when dragging a course.
     */
    public void updateParentHeaderForCourseDrag(RecyclerView rv, String courseId, float hypotheticalScore) {
        if (courseId == null || rv == null) return;
        
        // Find the course object
        Course targetCourse = null;
        for (Course c : rawCourses) {
            if (c.id != null && c.id.equals(courseId)) {
                targetCourse = c;
                break;
            }
        }
        if (targetCourse == null) return;
        
        String fileId = targetCourse.fileId == null ? "<nofile>" : targetCourse.fileId;
        String sheetName = targetCourse.sheetName == null ? "<nosheet>" : targetCourse.sheetName;
        String sheetId = fileId + "|" + sheetName;
        
        // 1. Calculate hypothetical Sheet Stats
        boolean sAllZero = true;
        boolean sAllTen = true;
        boolean sHasTen = false;
        boolean sHasAny = false;
        
        for (Course c : rawCourses) {
            String f = c.fileId == null ? "<nofile>" : c.fileId;
            if (!f.equals(fileId)) continue;
            String s = c.sheetName == null ? "<nosheet>" : c.sheetName;
            if (s.equals(sheetName)) {
                sHasAny = true;
                float score;
                if (c.id.equals(courseId)) {
                    score = hypotheticalScore;
                } else {
                    score = c.interest;
                }
                
                if (score < 9.9f) sAllTen = false;
                if (score > 0.1f) sAllZero = false;
                if (score >= 9.9f) sHasTen = true;
            }
        }
        
        if (sHasAny) {
            int stateScore;
            if (sAllTen) stateScore = 10;
            else if (sAllZero) stateScore = 0;
            else if (sHasTen) stateScore = -1;
            else stateScore = 5;
            
            int sheetPos = findPositionById(sheetId);
            if (sheetPos != -1) {
                RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(sheetPos);
                updateItemStyle(vh, stateScore, TYPE_SHEET);
            }
        }
        
        // 2. Calculate hypothetical File Stats
        boolean fAllZero = true;
        boolean fAllTen = true;
        boolean fHasTen = false;
        boolean fHasAny = false;
        
        for (Course c : rawCourses) {
            String f = c.fileId == null ? "<nofile>" : c.fileId;
            if (f.equals(fileId)) {
                fHasAny = true;
                float score;
                if (c.id.equals(courseId)) {
                    score = hypotheticalScore;
                } else {
                    score = c.interest;
                }
                
                if (score < 9.9f) fAllTen = false;
                if (score > 0.1f) fAllZero = false;
                if (score >= 9.9f) fHasTen = true;
            }
        }
        
        if (fHasAny) {
            int stateScore;
            if (fAllTen) stateScore = 10;
            else if (fAllZero) stateScore = 0;
            else if (fHasTen) stateScore = -1;
            else stateScore = 5;
            
            int filePos = findPositionById(fileId);
            if (filePos != -1) {
                RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(filePos);
                updateItemStyle(vh, stateScore, TYPE_FILE);
            }
        }
    }

    /**
     * Update item score and refresh visuals (including headers) in real-time
     * Used for drag interactions
     */
    public void updateItemScore(RecyclerView rv, int position, int newScore) {
        if (position < 0 || position >= display.size()) return;
        Item item = display.get(position);
        if (item.type != TYPE_COURSE || item.course == null) return;
        
        // 1. Update Course Interest
        item.course.interest = newScore;
        
        // 2. Update stats for parent File and Sheet
        String fileId = item.course.fileId == null ? "<nofile>" : item.course.fileId;
        String sheetName = item.course.sheetName == null ? "<nosheet>" : item.course.sheetName;
        String sheetId = fileId + "|" + sheetName;
        
        // Re-calculate Sheet Stats
        HeaderStats sheetStats = new HeaderStats();
        for (Course c : rawCourses) {
            String f = c.fileId == null ? "<nofile>" : c.fileId;
            if (!f.equals(fileId)) continue;
            String s = c.sheetName == null ? "<nosheet>" : c.sheetName;
            if (s.equals(sheetName)) {
                 sheetStats.update(c);
            }
        }
        statsMap.put(sheetId, sheetStats);
        
        // Re-calculate File Stats
        HeaderStats fileStats = new HeaderStats();
        for (Course c : rawCourses) {
            String f = c.fileId == null ? "<nofile>" : c.fileId;
            if (f.equals(fileId)) {
                fileStats.update(c);
            }
        }
        statsMap.put(fileId, fileStats);
        
        // 3. Update Visuals
        // Update Course Item
        RecyclerView.ViewHolder vh = rv.findViewHolderForAdapterPosition(position);
        if (vh instanceof VH) {
             applyCourseStyle((VH)vh, item.course);
             renderInterestSegments((VH)vh, newScore); 
        }
        
        // Update Sheet Header
        int sheetPos = findPositionById(sheetId);
        if (sheetPos != -1) {
            RecyclerView.ViewHolder shVh = rv.findViewHolderForAdapterPosition(sheetPos);
            if (shVh instanceof VH) {
                applyHeaderStyle(((VH)shVh).rootCard, sheetStats, R.color.sheet_row_bg);
            }
        }
        
        // Update File Header
        int filePos = findPositionById(fileId);
        if (filePos != -1) {
             RecyclerView.ViewHolder fVh = rv.findViewHolderForAdapterPosition(filePos);
             if (fVh instanceof VH) {
                 applyHeaderStyle(((VH)fVh).rootCard, fileStats, R.color.background_color);
             }
        }
    }
}
