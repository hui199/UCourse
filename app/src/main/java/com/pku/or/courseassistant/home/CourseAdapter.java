package com.pku.or.courseassistant.home;

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

import com.pku.or.courseassistant.R;

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

    // 扁平化的显示列表（包含所有可见的文件/Sheet/课程）
    private final List<Item> display = new ArrayList<>();
    // 原始课程列表（保留完整数据，用于重建display）
    private List<Course> rawCourses = new ArrayList<>();
    // 展开状态存储（跨数据更新保留状态）
    private final Set<String> expandedFiles = new HashSet<>();
    private final Set<String> expandedSheets = new HashSet<>();
    // 缓存每个文件的Sheet数量（重建display时计算）
    private final Map<String, Integer> sheetCountByFile = new java.util.HashMap<>();
    
    /**
     * 长按监听器接口
     * 用于响应用户长按文件/Sheet/课程的操作
     */
    public interface OnItemLongPressListener {
        void onItemLongPress(int type, String id, int position);
    }

    private OnItemLongPressListener longPressListener = null;

    public void setOnItemLongPressListener(OnItemLongPressListener l) { this.longPressListener = l; }

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
        
        // 第一步：按文件和Sheet分组
        Map<String, Map<String, List<Course>>> byFile = new LinkedHashMap<>();
        for (Course c : rawCourses) {
            String f = c.fileId == null ? "<nofile>" : c.fileId;
            String s = c.sheetName == null ? "<nosheet>" : c.sheetName;
            byFile.putIfAbsent(f, new LinkedHashMap<>());
            Map<String, List<Course>> fm = byFile.get(f);
            fm.putIfAbsent(s, new ArrayList<>());
            fm.get(s).add(c);
        }
        
        // 第二步：构建扁平化显示列表
        for (Map.Entry<String, Map<String, List<Course>>> fe : byFile.entrySet()) {
            String fileId = fe.getKey();
            // 记录该文件的Sheet数量（用于徽章显示）
            try { sheetCountByFile.put(fileId, fe.getValue() == null ? 0 : fe.getValue().size()); } catch (Throwable _t) { sheetCountByFile.put(fileId, 0); }
            
            // 添加文件项
            Item fileItem = new Item();
            fileItem.type = TYPE_FILE;
            fileItem.id = fileId;
            fileItem.title = fileId;
            out.add(fileItem);
            
            Map<String, List<Course>> sheets = fe.getValue();
            // 只有当文件展开时才显示Sheet
            if (expandedFiles.contains(fileId)) {
                // 添加Sheet项
                for (Map.Entry<String, List<Course>> se : sheets.entrySet()) {
                    String sheetName = se.getKey();
                    Item sheetItem = new Item();
                    sheetItem.type = TYPE_SHEET;
                    sheetItem.id = fileId + "|" + sheetName;
                    sheetItem.title = sheetName;
                    sheetItem.parentId = fileId;
                    out.add(sheetItem);
                    
                    // 只有当Sheet展开时才显示课程
                    if (expandedSheets.contains(sheetItem.id)) {
                        for (Course c : se.getValue()) {
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
            
            // 使用缓存的Sheet数量（在构建display时计算）
            int sheetCount = 0;
            try { sheetCount = sheetCountByFile.getOrDefault(it.id == null ? "<nofile>" : it.id, 0); } catch (Throwable _t) { sheetCount = 0; }
            String titleWithCount = base + " (" + sheetCount + ")";
            
            // 截断过长的标题（>40字符时显示前半部分）
            String disp = maybeTruncate(titleWithCount);
            vh.tvTitle.setText(disp);
            vh.tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
            vh.tvTitle.setTypeface(Typeface.DEFAULT_BOLD);
            vh.tvTitle.setTextColor(ContextCompat.getColor(vh.itemView.getContext(), R.color.black));
            
            // 绑定数量标签（显示为[N]）
            try {
                android.widget.TextView tvCount = vh.itemView.findViewById(R.id.tv_count);
                if (tvCount != null) {
                    tvCount.setText("[" + sheetCount + "]");
                    // 使用强调色渲染数量标签
                    int accent = generateAccentEndForKey(it.id == null ? "<nofile>" : it.id);
                    tvCount.setTextColor(accent);
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
                    if (vh.rootCard != null) {
                        vh.rootCard.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(vh.itemView.getContext(), R.color.background_color)));
                    }
                }
            } catch (Throwable _t) {}
            
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
                // if the parser provided a workbook mapping later we may replace this with the user-friendly name
            }
            String disp = maybeTruncate(pretty);
            vh.tvTitle.setText(disp);
            vh.tvTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            vh.tvTitle.setTypeface(Typeface.DEFAULT);
            // increase left indent for sheet rows to emphasize hierarchy
            vh.tvTitle.setPadding((int)(vh.itemView.getResources().getDisplayMetrics().density * 12), 0, 0, 0);
            vh.tvTitle.setTextColor(ContextCompat.getColor(vh.itemView.getContext(), R.color.gray_900));
            // do not show expand/collapse text for sheet rows
            vh.tvMeta.setText("");
            vh.tvMeta.setTextColor(ContextCompat.getColor(vh.itemView.getContext(), R.color.gray_600));
            vh.tvTitle.setSingleLine(true);
            vh.tvTitle.setEllipsize(TextUtils.TruncateAt.END);
            // title background removed for sheet rows as well
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
                    if (vh.rootCard != null) {
                        vh.rootCard.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ContextCompat.getColor(vh.itemView.getContext(), R.color.sheet_row_bg)));
                    }
                }
            } catch (Throwable _t) {}
            vh.itemView.setOnClickListener(v -> toggleSheet(it.id, vh));
            if (longPressListener != null) {
                setupLongPressDetector(vh.itemView, () -> longPressListener.onItemLongPress(it.type, it.id, pos));
            } else {
                vh.itemView.setOnTouchListener(null);
            }
        } else {
            // course
            Course c = it.course;
            vh.tvTitle.setText(c.title == null || c.title.isEmpty() ? "(未命名)" : c.title);
            vh.tvMeta.setText((c.teachers == null ? "" : c.teachers) + " · " + (c.unit == null ? "" : c.unit) + " · " + (c.rawTime == null ? "" : c.rawTime));
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
            vh.itemView.setOnClickListener(null);
            vh.tvTitle.setSingleLine(true);
            vh.tvTitle.setEllipsize(TextUtils.TruncateAt.END);
            // Render interest score fill and text
            renderInterestSegments(vh, c.interest);
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
                    android.graphics.drawable.Drawable fillLayer = ld.getDrawable(1); // ClipDrawable is at index 1 after LayerList reordering
                    if (fillLayer instanceof android.graphics.drawable.ClipDrawable) {
                        android.graphics.drawable.ClipDrawable cd = (android.graphics.drawable.ClipDrawable) fillLayer;
                        // Calculate level: 0-10000, where 10000 = 10.0 score
                        int level = (int) (10000f * (finalScore / 10.0f));
                        cd.setLevel(level);
                    }
                }
            } catch (Throwable _t) {
                // Fallback: ignore rendering errors
            }
        }
    }
}
