package com.pku.or.courseassistant.view;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.text.TextPaint;
import android.util.AttributeSet;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Toast;

import com.pku.or.courseassistant.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class TimeTableView extends View {
    // 常量定义
    private static final int ROW_COUNT = 12; // 12节课
    private static final int COLUMN_COUNT = 7; // 7天
    private static final int MAX_SLOTS_PER_DAY = 6; // 每天最多6个条形
    private static final long LONG_PRESS_DURATION = 3000; // 长按3秒

    // 属性变量
    private int gridLineColor;
    private float gridLineWidth;
    private int timeSlotColor;
    private int timeSlotStrokeColor;
    private float timeSlotStrokeWidth;
    private int headerTextColor;
    private float headerTextSize;
    private int sideBarTextColor;
    private float sideBarTextSize;
    private float cellWidth;
    private float cellHeight;
    private float headerHeight;
    private float sideBarWidth;
    private int morningTextColor;
    private int afternoonTextColor;
    private float timeTextSize;

    // 绘制工具
    private Paint gridPaint;
    private Paint timeSlotPaint;
    private TextPaint textPaint;
    private TextPaint headerTextPaint;
    private TextPaint timeTextPaint;
    private Paint longPressPaint;

    // 数据
    private Map<Integer, List<TimeSlot>> timeSlotsMap;
    private List<OnTimeSelectListener> listeners;

    // 触摸相关
    private int currentDay = -1;
    private int currentStartSection = -1;
    private int currentEndSection = -1;
    private boolean isSelecting = false;
    private float startX, startY;
    private long touchStartTime = 0;
    private boolean isLongPressTriggered = false;
    private TimeSlot longPressedSlot = null;
    private Handler longPressHandler = new Handler();
    private Runnable longPressRunnable;

    // 星期标题
    private String[] weekDays = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    // 节次和时间信息 - 每行包含节次数和时间段
    private String[] sectionNumbers = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"};
    private String[] timeRanges = {
            "8:00-8:50", "9:00-9:50", "10:00-10:50", "11:00-11:50",
            "14:00-14:50", "15:00-15:50", "16:00-16:50", "17:00-17:50",
            "19:00-19:50", "20:00-20:50", "21:00-21:50", "22:00-22:50"
    };

    public TimeTableView(Context context) {
        this(context, null);
    }

    public TimeTableView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public TimeTableView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init(context, attrs);
    }

    private void init(Context context, AttributeSet attrs) {
        // 初始化属性
        initAttributes(context, attrs);

        // 初始化绘制工具
        initPaints();

        // 初始化数据
        timeSlotsMap = new HashMap<>();
        for (int i = 0; i < COLUMN_COUNT; i++) {
            timeSlotsMap.put(i, new ArrayList<TimeSlot>());
        }
        listeners = new ArrayList<>();

        // 初始化长按Runnable
        longPressRunnable = new Runnable() {
            @Override
            public void run() {
                handleLongPress();
            }
        };

        setClickable(true);
    }

    private void initAttributes(Context context, AttributeSet attrs) {
        DisplayMetrics metrics = context.getResources().getDisplayMetrics();
        TypedArray ta = context.obtainStyledAttributes(attrs, R.styleable.TimeTableView);

        gridLineColor = ta.getColor(R.styleable.TimeTableView_gridLineColor,
                getResources().getColor(R.color.grid_line_color));
        gridLineWidth = ta.getDimension(R.styleable.TimeTableView_gridLineWidth,
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, metrics));
        timeSlotColor = ta.getColor(R.styleable.TimeTableView_timeSlotColor,
                getResources().getColor(R.color.time_slot_color));
        timeSlotStrokeColor = ta.getColor(R.styleable.TimeTableView_timeSlotStrokeColor,
                getResources().getColor(R.color.time_slot_stroke_color));
        timeSlotStrokeWidth = ta.getDimension(R.styleable.TimeTableView_timeSlotStrokeWidth,
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 1, metrics));
        headerTextColor = ta.getColor(R.styleable.TimeTableView_headerTextColor,
                getResources().getColor(R.color.header_text_color));
        headerTextSize = ta.getDimension(R.styleable.TimeTableView_headerTextSize,
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14, metrics));
        sideBarTextColor = ta.getColor(R.styleable.TimeTableView_sideBarTextColor,
                getResources().getColor(R.color.side_bar_text_color));
        sideBarTextSize = ta.getDimension(R.styleable.TimeTableView_sideBarTextSize,
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12, metrics));

        // 减小列宽以适应屏幕
        cellWidth = ta.getDimension(R.styleable.TimeTableView_cellWidth,
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 45, metrics)); // 从50dp减小到45dp

        cellHeight = ta.getDimension(R.styleable.TimeTableView_cellHeight,
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 40, metrics));
        headerHeight = ta.getDimension(R.styleable.TimeTableView_headerHeight,
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 30, metrics));
        sideBarWidth = ta.getDimension(R.styleable.TimeTableView_sideBarWidth,
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 60, metrics)); // 增加侧边栏宽度以显示时间

        // 新增属性
        morningTextColor = ta.getColor(R.styleable.TimeTableView_morningTextColor,
                getResources().getColor(R.color.morning_text_color));
        afternoonTextColor = ta.getColor(R.styleable.TimeTableView_afternoonTextColor,
                getResources().getColor(R.color.afternoon_text_color));
        timeTextSize = ta.getDimension(R.styleable.TimeTableView_timeTextSize,
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 10, metrics));

        ta.recycle();
    }

    private void initPaints() {
        // 网格线画笔
        gridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        gridPaint.setColor(gridLineColor);
        gridPaint.setStrokeWidth(gridLineWidth);
        gridPaint.setStyle(Paint.Style.STROKE);

        // 时间段画笔
        timeSlotPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        timeSlotPaint.setColor(timeSlotColor);
        timeSlotPaint.setStyle(Paint.Style.FILL);

        // 侧边栏文字画笔
        textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(sideBarTextColor);
        textPaint.setTextSize(sideBarTextSize);
        textPaint.setTextAlign(Paint.Align.CENTER);

        // 时间文字画笔
        timeTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        timeTextPaint.setColor(sideBarTextColor);
        timeTextPaint.setTextSize(timeTextSize);
        timeTextPaint.setTextAlign(Paint.Align.CENTER);

        // 标题文字画笔
        headerTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        headerTextPaint.setColor(headerTextColor);
        headerTextPaint.setTextSize(headerTextSize);
        headerTextPaint.setTextAlign(Paint.Align.CENTER);

        // 长按效果画笔
        longPressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        longPressPaint.setColor(0x66FF0000);
        longPressPaint.setStyle(Paint.Style.FILL);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        int heightMode = MeasureSpec.getMode(heightMeasureSpec);

        int desiredWidth = (int) (sideBarWidth + COLUMN_COUNT * cellWidth + getPaddingLeft() + getPaddingRight());
        int desiredHeight = (int) (headerHeight + ROW_COUNT * cellHeight + getPaddingTop() + getPaddingBottom());

        int width, height;

        if (widthMode == MeasureSpec.EXACTLY) {
            width = MeasureSpec.getSize(widthMeasureSpec);
        } else {
            width = Math.min(desiredWidth, MeasureSpec.getSize(widthMeasureSpec));
            // 如果宽度仍然太大，进一步调整列宽
            if (width < desiredWidth) {
                cellWidth = (width - sideBarWidth - getPaddingLeft() - getPaddingRight()) / COLUMN_COUNT;
            }
        }

        if (heightMode == MeasureSpec.EXACTLY) {
            height = MeasureSpec.getSize(heightMeasureSpec);
        } else {
            height = Math.min(desiredHeight, MeasureSpec.getSize(heightMeasureSpec));
        }

        setMeasuredDimension(width, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        drawGrid(canvas);
        drawHeaders(canvas);
        drawSideBarWithTime(canvas); // 修改：使用新的侧边栏绘制方法
        drawTimeSlots(canvas);
        drawCurrentSelection(canvas);
        drawLongPressEffect(canvas);
    }

    private void drawGrid(Canvas canvas) {
        float startX = sideBarWidth;
        float startY = headerHeight;
        float endX = startX + COLUMN_COUNT * cellWidth;
        float endY = startY + ROW_COUNT * cellHeight;

        // 绘制外边框
        canvas.drawRect(startX, startY, endX, endY, gridPaint);

        // 绘制列线
        for (int i = 0; i <= COLUMN_COUNT; i++) {
            float x = startX + i * cellWidth;
            canvas.drawLine(x, startY, x, endY, gridPaint);
        }

        // 绘制行线
        for (int i = 0; i <= ROW_COUNT; i++) {
            float y = startY + i * cellHeight;
            canvas.drawLine(startX, y, endX, y, gridPaint);
        }
    }

    private void drawHeaders(Canvas canvas) {
        // 绘制星期标题
        float centerX, centerY = headerHeight / 2;
        for (int i = 0; i < COLUMN_COUNT; i++) {
            centerX = sideBarWidth + i * cellWidth + cellWidth / 2;
            canvas.drawText(weekDays[i], centerX, centerY + headerTextSize / 3, headerTextPaint);
        }
    }

    private void drawSideBarWithTime(Canvas canvas) {
        float centerX = sideBarWidth / 2;

        for (int i = 0; i < ROW_COUNT; i++) {
            float cellTop = headerHeight + i * cellHeight;
            float cellCenterY = cellTop + cellHeight / 2;

            // 设置颜色：前4节用上午颜色，后面的用下午颜色
            int textColor = (i < 4) ? morningTextColor : afternoonTextColor;
            textPaint.setColor(textColor);
            timeTextPaint.setColor(textColor);

            // 绘制节次数（较大字体，居中偏上）
            float sectionNumberY = cellCenterY - cellHeight / 4;
            canvas.drawText(sectionNumbers[i], centerX, sectionNumberY + getTextBaseline(textPaint), textPaint);

            // 绘制时间段（较小字体，居中偏下）
            float timeY = cellCenterY + cellHeight / 4;
            canvas.drawText(timeRanges[i], centerX, timeY + getTextBaseline(timeTextPaint), timeTextPaint);
        }
    }

    /**
     * 获取文字的基线位置（用于垂直居中）
     */
    private float getTextBaseline(TextPaint paint) {
        return (paint.descent() - paint.ascent()) / 2 - paint.descent();
    }

    private void drawTimeSlots(Canvas canvas) {
        for (int day = 0; day < COLUMN_COUNT; day++) {
            List<TimeSlot> daySlots = timeSlotsMap.get(day);
            if (daySlots != null) {
                for (TimeSlot slot : daySlots) {
                    if (slot.isSelected()) {
                        drawTimeSlot(canvas, slot);
                    }
                }
            }
        }
    }

    private void drawTimeSlot(Canvas canvas, TimeSlot slot) {
        float left = sideBarWidth + slot.getDay() * cellWidth + cellWidth * 0.1f;
        float right = left + cellWidth * 0.8f;
        float top = headerHeight + slot.getStartSection() * cellHeight;
        float bottom = headerHeight + (slot.getEndSection() + 1) * cellHeight;

        RectF rect = new RectF(left, top, right, bottom);
        canvas.drawRoundRect(rect, 8, 8, timeSlotPaint);

        // 绘制边框
        timeSlotPaint.setStyle(Paint.Style.STROKE);
        timeSlotPaint.setStrokeWidth(timeSlotStrokeWidth);
        timeSlotPaint.setColor(timeSlotStrokeColor);
        canvas.drawRoundRect(rect, 8, 8, timeSlotPaint);

        // 恢复填充模式
        timeSlotPaint.setStyle(Paint.Style.FILL);
        timeSlotPaint.setColor(timeSlotColor);
    }

    private void drawCurrentSelection(Canvas canvas) {
        if (isSelecting && currentDay >= 0 && currentStartSection >= 0 && currentEndSection >= 0) {
            float left = sideBarWidth + currentDay * cellWidth + cellWidth * 0.1f;
            float right = left + cellWidth * 0.8f;
            float top = headerHeight + currentStartSection * cellHeight;
            float bottom = headerHeight + (currentEndSection + 1) * cellHeight;

            RectF rect = new RectF(left, top, right, bottom);

            Paint selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            selectionPaint.setColor(0x664CAF50);
            selectionPaint.setStyle(Paint.Style.FILL);
            canvas.drawRoundRect(rect, 8, 8, selectionPaint);

            selectionPaint.setStyle(Paint.Style.STROKE);
            selectionPaint.setStrokeWidth(2);
            selectionPaint.setColor(0xFF4CAF50);
            canvas.drawRoundRect(rect, 8, 8, selectionPaint);
        }
    }

    private void drawLongPressEffect(Canvas canvas) {
        if (isLongPressTriggered && longPressedSlot != null) {
            float left = sideBarWidth + longPressedSlot.getDay() * cellWidth + cellWidth * 0.1f;
            float right = left + cellWidth * 0.8f;
            float top = headerHeight + longPressedSlot.getStartSection() * cellHeight;
            float bottom = headerHeight + (longPressedSlot.getEndSection() + 1) * cellHeight;

            RectF rect = new RectF(left, top, right, bottom);
            canvas.drawRoundRect(rect, 8, 8, longPressPaint);
        }
    }

    // ... 其他方法（触摸事件处理、时间段管理等）保持不变，与之前相同 ...
    // 这里省略了部分重复代码以节省空间，实际使用时请保持完整的触摸事件处理逻辑

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled()) {
            return false;
        }

        float x = event.getX();
        float y = event.getY();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                handleActionDown(x, y);
                return true;

            case MotionEvent.ACTION_MOVE:
                handleActionMove(x, y);
                return true;

            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                handleActionUp();
                return true;
        }

        return super.onTouchEvent(event);
    }

    private void handleActionDown(float x, float y) {
        if (x < sideBarWidth || y < headerHeight) {
            return;
        }

        int day = (int) ((x - sideBarWidth) / cellWidth);
        int section = (int) ((y - headerHeight) / cellHeight);

        if (day >= 0 && day < COLUMN_COUNT && section >= 0 && section < ROW_COUNT) {
            TimeSlot clickedSlot = findTimeSlotAt(day, section);
            if (clickedSlot != null) {
                longPressedSlot = clickedSlot;
                touchStartTime = System.currentTimeMillis();
                longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_DURATION);
                return;
            }

            currentDay = day;
            currentStartSection = section;
            currentEndSection = section;
            isSelecting = true;
            startX = x;
            startY = y;
            invalidate();
        }
    }

    private void handleActionMove(float x, float y) {
        if (longPressedSlot != null) {
            longPressHandler.removeCallbacks(longPressRunnable);
            longPressedSlot = null;
            isLongPressTriggered = false;
            invalidate();
        }

        if (!isSelecting || currentDay < 0) return;

        int section = (int) ((y - headerHeight) / cellHeight);
        section = Math.max(0, Math.min(section, ROW_COUNT - 1));

        if (section != currentEndSection) {
            currentEndSection = section;
            invalidate();
        }
    }

    private void handleActionUp() {
        longPressHandler.removeCallbacks(longPressRunnable);

        if (isLongPressTriggered) {
            isLongPressTriggered = false;
            longPressedSlot = null;
            invalidate();
            return;
        }

        if (!isSelecting || currentDay < 0) return;

        int start = Math.min(currentStartSection, currentEndSection);
        int end = Math.max(currentStartSection, currentEndSection);

        TimeSlot newSlot = new TimeSlot(currentDay, start, end);

        if (canAddTimeSlot(currentDay, newSlot)) {
            addTimeSlot(newSlot);
        }

        resetSelection();
    }

    private void handleLongPress() {
        if (longPressedSlot != null) {
            isLongPressTriggered = true;
            removeTimeSlot(longPressedSlot);

            String dayStr = getDayString(longPressedSlot.getDay());
            String message = "已删除周" + dayStr + " 第" + (longPressedSlot.getStartSection() + 1) +
                    "-" + (longPressedSlot.getEndSection() + 1) + "节";
            Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();

            invalidate();
        }
    }

    private TimeSlot findTimeSlotAt(int day, int section) {
        List<TimeSlot> daySlots = timeSlotsMap.get(day);
        if (daySlots != null) {
            for (TimeSlot slot : daySlots) {
                if (slot.contains(section)) {
                    return slot;
                }
            }
        }
        return null;
    }

    // ... 时间段合并和管理方法保持不变 ...
    // 这里省略了合并算法等重复代码

    private void mergeTimeSlots(int day) {
        List<TimeSlot> daySlots = timeSlotsMap.get(day);
        if (daySlots == null || daySlots.size() <= 1) {
            return;
        }

        Collections.sort(daySlots);

        List<TimeSlot> merged = new ArrayList<>();
        TimeSlot current = daySlots.get(0);

        for (int i = 1; i < daySlots.size(); i++) {
            TimeSlot next = daySlots.get(i);

            if (current.overlaps(next) || current.isAdjacent(next)) {
                current.mergeWith(next);
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);

        timeSlotsMap.put(day, merged);
    }

    private boolean canAddTimeSlot(int day, TimeSlot newSlot) {
        List<TimeSlot> daySlots = timeSlotsMap.get(day);
        if (daySlots.size() >= MAX_SLOTS_PER_DAY) {
            // 检查合并后是否还能添加
            List<TimeSlot> testSlots = new ArrayList<>(daySlots);
            testSlots.add(newSlot);
            Collections.sort(testSlots);

            List<TimeSlot> testMerged = new ArrayList<>();
            TimeSlot current = testSlots.get(0);

            for (int i = 1; i < testSlots.size(); i++) {
                TimeSlot next = testSlots.get(i);
                if (current.overlaps(next) || current.isAdjacent(next)) {
                    current.mergeWith(next);
                } else {
                    testMerged.add(current);
                    current = next;
                }
            }
            testMerged.add(current);

            return testMerged.size() <= MAX_SLOTS_PER_DAY;
        }

        return true;
    }

    public void addTimeSlot(TimeSlot timeSlot) {
        if (timeSlot == null) return;

        int day = timeSlot.getDay();
        List<TimeSlot> daySlots = timeSlotsMap.get(day);
        if (daySlots != null) {
            daySlots.add(timeSlot);
            // 添加后立即合并相邻时间段[2](@ref)
            mergeTimeSlots(day);
            notifyTimeSlotCreated(timeSlot);
            invalidate();
        }
    }

    public void removeTimeSlot(TimeSlot timeSlot) {
        if (timeSlot == null) return;

        int day = timeSlot.getDay();
        List<TimeSlot> daySlots = timeSlotsMap.get(day);
        if (daySlots != null && daySlots.remove(timeSlot)) {
            // 删除后重新合并剩余时间段
            mergeTimeSlots(day);
            notifyTimeSlotRemoved(timeSlot);
            invalidate();
        }
    }

    public void clearTimeSlots() {
        for (int i = 0; i < COLUMN_COUNT; i++) {
            timeSlotsMap.get(i).clear();
        }
        notifyTimeSlotsChanged();
        invalidate();
    }

    public List<TimeSlot> getAllTimeSlots() {
        List<TimeSlot> allSlots = new ArrayList<>();
        for (int i = 0; i < COLUMN_COUNT; i++) {
            allSlots.addAll(timeSlotsMap.get(i));
        }
        return allSlots;
    }

    public List<TimeSlot> getTimeSlotsByDay(int day) {
        if (day >= 0 && day < COLUMN_COUNT) {
            return new ArrayList<>(timeSlotsMap.get(day));
        }
        return new ArrayList<>();
    }

    private void resetSelection() {
        isSelecting = false;
        currentDay = -1;
        currentStartSection = -1;
        currentEndSection = -1;
        invalidate();
    }

    private String getDayString(int day) {
        String[] days = {"一", "二", "三", "四", "五", "六", "日"};
        if (day >= 0 && day < days.length) {
            return days[day];
        }
        return "未知";
    }

    // 监听器管理
    public void addOnTimeSelectListener(OnTimeSelectListener listener) {
        if (listener != null && !listeners.contains(listener)) {
            listeners.add(listener);
        }
    }

    public void removeOnTimeSelectListener(OnTimeSelectListener listener) {
        listeners.remove(listener);
    }

    private void notifyTimeSlotCreated(TimeSlot timeSlot) {
        for (OnTimeSelectListener listener : listeners) {
            listener.onTimeSlotCreated(timeSlot);
        }
        notifyTimeSlotsChanged();
    }

    private void notifyTimeSlotRemoved(TimeSlot timeSlot) {
        for (OnTimeSelectListener listener : listeners) {
            listener.onTimeSlotRemoved(timeSlot);
        }
        notifyTimeSlotsChanged();
    }

    private void notifyTimeSlotsChanged() {
        List<TimeSlot> allSlots = getAllTimeSlots();
        for (OnTimeSelectListener listener : listeners) {
            listener.onTimeSlotChanged(allSlots);
        }
    }

    // Getter和Setter方法
    public Map<Integer, List<TimeSlot>> getTimeSlotsMap() { return timeSlotsMap; }
    public void setTimeSlotsMap(Map<Integer, List<TimeSlot>> timeSlotsMap) {
        this.timeSlotsMap = timeSlotsMap;
        // 设置新数据后合并所有天的时间段
        for (int i = 0; i < COLUMN_COUNT; i++) {
            mergeTimeSlots(i);
        }
        invalidate();
    }

    public int getGridLineColor() { return gridLineColor; }
    public void setGridLineColor(int gridLineColor) {
        this.gridLineColor = gridLineColor;
        gridPaint.setColor(gridLineColor);
        invalidate();
    }

    public int getTimeSlotColor() { return timeSlotColor; }
    public void setTimeSlotColor(int timeSlotColor) {
        this.timeSlotColor = timeSlotColor;
        timeSlotPaint.setColor(timeSlotColor);
        invalidate();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        // 清除所有Handler回调，防止内存泄漏
        longPressHandler.removeCallbacksAndMessages(null);
    }
}