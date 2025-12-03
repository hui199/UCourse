package com.pku.or.ucourse.view;

import android.app.DatePickerDialog;
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
import android.view.ViewConfiguration;
import android.view.View;
import android.widget.DatePicker;
import android.widget.Toast;

import com.pku.or.ucourse.R;
import com.pku.or.ucourse.model.WeekTimeData;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class TimeTableView extends View {
    // 常量定义
    private static final int ROW_COUNT = 12; // 12节课
    private static final int COLUMN_COUNT = 7; // 7天
    private static final int MAX_SLOTS_PER_DAY = 6; // 每天最多6个条形
    private static final long LONG_PRESS_DURATION = 1500; // 长按触发时长（ms），表现为 1.5s

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
    private int morningBackgroundColor;
    private int afternoonBackgroundColor;
    private int eveningBackgroundColor;
    private int todayHighlightColor;
    private float timeTextSize;

    // 绘制工具
    private Paint gridPaint;
    private Paint timeSlotPaint;
    private TextPaint textPaint;
    private TextPaint headerTextPaint;
    private TextPaint timeTextPaint;
    private Paint backgroundPaint;
    private Paint todayBackgroundPaint;
    private Paint selectionPaint;
    private Paint longPressPaint;
    private Paint longPressRingPaint;
    private TextPaint longPressTextPaint;

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
    private boolean isCountingLongPress = false; // 正在计时但未到时的视觉反馈
    private Handler longPressHandler = new Handler();
    private Runnable longPressRunnable;
    private Runnable longPressTicker;
    private float downX = 0f, downY = 0f;
    private int touchSlop = 0; // 触摸容差，放在 init 中初始化

    // 日期相关
    private Calendar currentStartDate; // 当前显示的开始日期
    private Date[] headerDates = new Date[7]; // 存储每个标题的日期
    private SimpleDateFormat headerDateFormat;
    // 布局偏移与自适应字段
    private float leftVisualMargin = 0f; // 视觉上的左侧空白（px）
    private float rightVisualMargin = 0f; // 视觉上的右侧空白（px）
    private float contentOffsetX = 0f; // 绘制时的 X 偏移（px）

    // 星期标题
    private String[] weekDays = {"周一", "周二", "周三", "周四", "周五", "周六", "周日"};

    // 节次和时间信息
    private String[] sectionNumbers = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12"};
    private String[] timeRanges = {
            "8:00-8:50", "9:00-9:50", "10:00-10:50", "11:00-11:50",
            "14:00-14:50", "15:00-15:50", "16:00-16:50", "17:00-17:50",
            "19:00-19:50", "20:00-20:50", "21:00-21:50", "22:00-22:50"
    };

    // 数据存储
    private WeekTimeData currentWeekData;

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

        // 初始化日期格式
        headerDateFormat = new SimpleDateFormat("MM.dd", Locale.getDefault());

        // 初始化数据
        timeSlotsMap = new HashMap<>();
        for (int i = 0; i < COLUMN_COUNT; i++) {
            timeSlotsMap.put(i, new ArrayList<TimeSlot>());
        }
        listeners = new ArrayList<>();

        // 初始化当前周数据
        currentWeekData = new WeekTimeData();

        // 设置开始日期为今天（显示从今天开始的一周）
        setStartDate(Calendar.getInstance().getTime());

        // 初始化长按Runnable
        longPressRunnable = new Runnable() {
            @Override
            public void run() {
                handleLongPress();
            }
        };

        // ticker 用于在长按等待期间刷新视图（用于放大动画）
        longPressTicker = new Runnable() {
            @Override
            public void run() {
                if (isCountingLongPress) {
                    invalidate();
                    // repost for next frame
                    longPressHandler.postDelayed(this, 50);
                }
            }
        };

        // touch slop for move tolerance
        touchSlop = ViewConfiguration.get(getContext()).getScaledTouchSlop();

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
        cellWidth = ta.getDimension(R.styleable.TimeTableView_cellWidth,
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 45, metrics));
        cellHeight = ta.getDimension(R.styleable.TimeTableView_cellHeight,
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 40, metrics));
        headerHeight = ta.getDimension(R.styleable.TimeTableView_headerHeight,
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 40, metrics));
        sideBarWidth = ta.getDimension(R.styleable.TimeTableView_sideBarWidth,
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 60, metrics));
        sideBarTextSize = ta.getDimension(R.styleable.TimeTableView_sideBarTextSize,
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14, metrics));//第几节课的数字大小
        timeTextSize = ta.getDimension(R.styleable.TimeTableView_timeTextSize,
                TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 9, metrics));//每节课的小时text的大小

        // 背景颜色属性
        morningBackgroundColor = ta.getColor(R.styleable.TimeTableView_morningBackgroundColor,
                getResources().getColor(R.color.morning_background));
        afternoonBackgroundColor = ta.getColor(R.styleable.TimeTableView_afternoonBackgroundColor,
                getResources().getColor(R.color.afternoon_background));
        eveningBackgroundColor = ta.getColor(R.styleable.TimeTableView_eveningBackgroundColor,
                getResources().getColor(R.color.evening_background));
        todayHighlightColor = ta.getColor(R.styleable.TimeTableView_todayHighlightColor,
                getResources().getColor(R.color.today_highlight_color));

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

        // 侧边栏文字画笔（用于节次数字）
        textPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        textPaint.setColor(sideBarTextColor);
        textPaint.setTextSize(sideBarTextSize);
        textPaint.setTextAlign(Paint.Align.CENTER);

        // 时间文字画笔（用于时间范围）
        timeTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        timeTextPaint.setColor(sideBarTextColor);
        timeTextPaint.setTextSize(timeTextSize);
        timeTextPaint.setTextAlign(Paint.Align.CENTER);

        // 标题文字画笔
        headerTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        headerTextPaint.setColor(headerTextColor);
        headerTextPaint.setTextSize(headerTextSize);
        headerTextPaint.setTextAlign(Paint.Align.CENTER);

        // 背景画笔
        backgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        backgroundPaint.setStyle(Paint.Style.FILL);

        // 今天背景画笔
        todayBackgroundPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        todayBackgroundPaint.setColor(todayHighlightColor);
        todayBackgroundPaint.setStyle(Paint.Style.FILL);

        // 选择效果画笔
        selectionPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        selectionPaint.setColor(0x664CAF50);
        selectionPaint.setStyle(Paint.Style.FILL);

        // 长按效果画笔
        longPressPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        longPressPaint.setColor(0x66FF0000);
        longPressPaint.setStyle(Paint.Style.FILL);
    // need display metrics here
    DisplayMetrics metrics = getResources().getDisplayMetrics();

    longPressRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    longPressRingPaint.setStyle(Paint.Style.STROKE);
    longPressRingPaint.setColor(0xFF388E3C);
    longPressRingPaint.setStrokeWidth(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, 4, metrics));

    longPressTextPaint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    longPressTextPaint.setColor(0xFFFFFFFF);
    longPressTextPaint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12, metrics));
    longPressTextPaint.setTextAlign(Paint.Align.CENTER);
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
            if (width < desiredWidth) {
                cellWidth = (width - sideBarWidth - getPaddingLeft() - getPaddingRight()) / COLUMN_COUNT;
            }
        }

        if (heightMode == MeasureSpec.EXACTLY) {
            height = MeasureSpec.getSize(heightMeasureSpec);
        } else {
            height = Math.min(desiredHeight, MeasureSpec.getSize(heightMeasureSpec));
        }

        // 重新根据最终宽度计算每列宽度，确保七列平均分配剩余空间，避免最后一列被截断
        // 计算侧边栏宽度：确保能完整显示节次与 timeRanges（包裹内容）
        float maxSideTextWidth = 0f;
        for (String s : sectionNumbers) {
            maxSideTextWidth = Math.max(maxSideTextWidth, textPaint.measureText(s));
        }
        for (String t : timeRanges) {
            maxSideTextWidth = Math.max(maxSideTextWidth, timeTextPaint.measureText(t));
        }
        // 增加 padding
        float desiredSideBar = Math.max(sideBarWidth, maxSideTextWidth + dpToPx(12));

        // 计算视觉左右边距（取总宽的 6% 左右，但在小屏幕上不超过一定像素）
        leftVisualMargin = Math.max(dpToPx(8), width * 0.06f);
        rightVisualMargin = leftVisualMargin;

        // 计算可用于七列的宽度
        int totalPadding = getPaddingLeft() + getPaddingRight();
        float remainingForColumns = width - leftVisualMargin - rightVisualMargin - desiredSideBar - totalPadding;
        if (remainingForColumns < dpToPx(40) * COLUMN_COUNT) {
            // 保证最小列宽，如果不足则缩小左右边距
            float minTotalForColumns = dpToPx(40) * COLUMN_COUNT;
            float shortage = minTotalForColumns - remainingForColumns;
            float reduceEach = Math.min(shortage / 2f, leftVisualMargin - dpToPx(4));
            leftVisualMargin = Math.max(dpToPx(4), leftVisualMargin - reduceEach);
            rightVisualMargin = leftVisualMargin;
            remainingForColumns = width - leftVisualMargin - rightVisualMargin - desiredSideBar - totalPadding;
        }

        // 设置最终 sideBarWidth 与 cellWidth
        sideBarWidth = desiredSideBar;
        if (remainingForColumns > 0) {
            cellWidth = remainingForColumns / (float) COLUMN_COUNT;
        } else {
            cellWidth = dpToPx(40);
        }

        // 内容绘制偏移量（左侧视觉空白）
        contentOffsetX = leftVisualMargin;

        setMeasuredDimension(width, height);
    }

    private float dpToPx(float dp) {
        return TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, getResources().getDisplayMetrics());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // 将画布向右平移，保留左侧视觉空白
        canvas.save();
        canvas.translate(contentOffsetX, 0);

        drawGrid(canvas);
        drawHeaders(canvas);
        drawSideBarWithTime(canvas);
        drawTimeSlots(canvas);
        drawCurrentSelection(canvas);
        drawLongPressEffect(canvas);

        canvas.restore();
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
        float centerX, topY, bottomY;

        for (int i = 0; i < COLUMN_COUNT; i++) {
            centerX = sideBarWidth + i * cellWidth + cellWidth / 2;

            // 检查是否是今天，绘制特殊背景
            if (isToday(headerDates[i])) {
                RectF todayRect = new RectF(
                        centerX - cellWidth / 2, 0,
                        centerX + cellWidth / 2, headerHeight
                );
                canvas.drawRect(todayRect, todayBackgroundPaint);
                headerTextPaint.setColor(getResources().getColor(android.R.color.white));
            } else {
                headerTextPaint.setColor(headerTextColor);
            }

            // 第一行：星期几
            topY = headerHeight / 3;
            canvas.drawText(weekDays[i], centerX, topY + getTextBaseline(headerTextPaint), headerTextPaint);

            // 第二行：日期
            bottomY = headerHeight * 2 / 3;
            String dateStr = headerDateFormat.format(headerDates[i]);
            canvas.drawText(dateStr, centerX, bottomY + getTextBaseline(headerTextPaint), headerTextPaint);
        }
        headerTextPaint.setColor(headerTextColor);
    }

    private void drawSideBarWithTime(Canvas canvas) {
        float centerX = sideBarWidth / 2;

        for (int i = 0; i < ROW_COUNT; i++) {
            float cellTop = headerHeight + i * cellHeight;
            float cellBottom = cellTop + cellHeight;
            float cellCenterY = cellTop + cellHeight / 2;

            // 设置背景颜色基于节次
            int backgroundColor;
            if (i < 4) { // 上午: 0-3 (1-4节课)
                backgroundColor = morningBackgroundColor;
            } else if (i < 8) { // 下午: 4-7 (5-8节课)
                backgroundColor = afternoonBackgroundColor;
            } else { // 晚上: 8-11 (9-12节课)
                backgroundColor = eveningBackgroundColor;
            }

            // 绘制背景
            backgroundPaint.setColor(backgroundColor);
            RectF backgroundRect = new RectF(0, cellTop, sideBarWidth, cellBottom);
            canvas.drawRect(backgroundRect, backgroundPaint);

            // 绘制节次数（较大字体，居中偏上）
            float sectionNumberY = cellCenterY - cellHeight / 4;
            canvas.drawText(sectionNumbers[i], centerX, sectionNumberY + getTextBaseline(textPaint), textPaint);

            // 绘制时间段（较小字体，居中偏下）
            float timeY = cellCenterY + cellHeight / 4;
            canvas.drawText(timeRanges[i], centerX, timeY + getTextBaseline(timeTextPaint), timeTextPaint);
        }
    }

    private float getTextBaseline(TextPaint paint) {
        return (paint.descent() - paint.ascent()) / 2 - paint.descent();
    }

    private void drawTimeSlots(Canvas canvas) {
        for (int day = 0; day < COLUMN_COUNT; day++) {
            List<TimeSlot> daySlots = timeSlotsMap.get(day);
            if (daySlots != null) {
                for (TimeSlot slot : daySlots) {
                    drawTimeSlot(canvas, slot);
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
            int start = Math.min(currentStartSection, currentEndSection);
            int end = Math.max(currentStartSection, currentEndSection);
            float top = headerHeight + start * cellHeight;
            float bottom = headerHeight + (end + 1) * cellHeight;

            RectF rect = new RectF(left, top, right, bottom);

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
        if (longPressedSlot != null) {
            float left = sideBarWidth + longPressedSlot.getDay() * cellWidth + cellWidth * 0.1f;
            float right = left + cellWidth * 0.8f;
            float top = headerHeight + longPressedSlot.getStartSection() * cellHeight;
            float bottom = headerHeight + (longPressedSlot.getEndSection() + 1) * cellHeight;

            if (isLongPressTriggered) {
                RectF rect = new RectF(left, top, right, bottom);
                canvas.drawRoundRect(rect, 8, 8, longPressPaint);
            } else if (isCountingLongPress) {
                long elapsed = System.currentTimeMillis() - touchStartTime;
                float frac = Math.min(1f, (float) elapsed / (float) LONG_PRESS_DURATION);
                // keep earlier pad-based visual
                float maxPad = Math.min(cellWidth * 0.12f, 20f);
                float pad = maxPad * frac;
                RectF rect = new RectF(left - pad, top - pad, right + pad, bottom + pad);
                Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
                int base = 0xFF4CAF50;
                int alpha = 0x66 + (int) (0x99 * frac);
                int col = (alpha << 24) | (base & 0x00FFFFFF);
                fillPaint.setColor(col);
                fillPaint.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(rect, 12 + pad / 2f, 12 + pad / 2f, fillPaint);

                // draw circular progress at center of rect
                float cx = (rect.left + rect.right) / 2f;
                float cy = (rect.top + rect.bottom) / 2f;
                float radius = Math.min(rect.width(), rect.height()) / 4f;
                RectF arcRect = new RectF(cx - radius, cy - radius, cx + radius, cy + radius);
                longPressRingPaint.setAlpha(0xFF);
                canvas.drawArc(arcRect, -90, 360 * frac, false, longPressRingPaint);
                // percentage text
                String pct = (int) (frac * 100) + "%";
                float textY = cy - (longPressTextPaint.descent() + longPressTextPaint.ascent()) / 2;
                canvas.drawText(pct, cx, textY, longPressTextPaint);
                // draw remaining time text above the rect
                // (removed textual "松手取消" to declutter UI)
            }
        }
    }

    // ========== 日期相关方法 ==========

    /**
     * 设置开始日期并更新周次（显示从今天开始的一周）
     */
    public void setStartDate(Date selectedDate) {
        if (currentStartDate == null) {
            currentStartDate = Calendar.getInstance();
        }

        // 计算从今天开始的一周
        calculateWeekFromToday(selectedDate);
        updateDateHeaders();
        // 切换起始日期后，只把 currentWeekData 中与新 headerDates 匹配的日期加载到对应列，
        // 避免直接 clearTimeSlots() 导致所有数据消失。
        Map<Integer, List<TimeSlot>> newMap = new HashMap<>();
        for (int i = 0; i < COLUMN_COUNT; i++) {
            newMap.put(i, new ArrayList<TimeSlot>());
        }

        if (currentWeekData != null) {
            for (int i = 0; i < COLUMN_COUNT; i++) {
                Date d = headerDates[i];
                List<WeekTimeData.TimeRange> ranges = currentWeekData.getDateTimeRanges(d);
                if (ranges != null) {
                    for (WeekTimeData.TimeRange r : ranges) {
                        TimeSlot slot = new TimeSlot(i, r.getStartSection(), r.getEndSection());
                        newMap.get(i).add(slot);
                    }
                }
            }
        }

        // 替换映射并合并/更新状态
        timeSlotsMap = newMap;
        for (int i = 0; i < COLUMN_COUNT; i++) {
            mergeTimeSlots(i);
        }
        // 更新 currentWeekData（基于新的 headerDates）并通知监听器
        updateWeekData();
        notifyTimeSlotsChanged();
        invalidate();
    }

    /**
     * 计算从今天开始的一周（第一列显示今天，然后依次显示后续日期）
     */
    private void calculateWeekFromToday(Date selectedDate) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(selectedDate);

        // 设置开始日期为今天
        currentStartDate = cal;
    }

    /**
     * 更新日期标题（显示从今天开始的一周）
     */
    private void updateDateHeaders() {
        Calendar cal = Calendar.getInstance();
        cal.setTime(currentStartDate.getTime());

        // 修正日期计算逻辑：确保每周从周一开始
        // Calendar.DAY_OF_WEEK: 周日=1, 周一=2, ..., 周六=7
        int dayOfWeek = cal.get(Calendar.DAY_OF_WEEK);

        // 计算到周一的偏移量：将周日(1)转换为6，周一(2)转换为0，以此类推
        int offsetToMonday = (dayOfWeek + 5) % 7;

        // 设置一周的日期（周一到周日）
        for (int i = 0; i < 7; i++) {
            headerDates[(i+offsetToMonday)%7] = cal.getTime();
            cal.add(Calendar.DAY_OF_MONTH, 1);
        }
    }

    /**
     * 返回当前视图使用的开始日期（Date），若未设置则返回 null。
     */
    public java.util.Date getStartDate() {
        if (currentStartDate == null) return null;
        return currentStartDate.getTime();
    }

    /**
     * 检查日期是否是今天
     */
    private boolean isToday(Date date) {
        if (date == null) return false;

        Calendar today = Calendar.getInstance();
        Calendar target = Calendar.getInstance();
        target.setTime(date);

        return today.get(Calendar.YEAR) == target.get(Calendar.YEAR) &&
                today.get(Calendar.DAY_OF_YEAR) == target.get(Calendar.DAY_OF_YEAR);
    }

    /**
     * 显示日期选择对话框
     */
    public void showDatePickerDialog(Context context) {
        Calendar calendar = Calendar.getInstance();
        int year = calendar.get(Calendar.YEAR);
        int month = calendar.get(Calendar.MONTH);
        int day = calendar.get(Calendar.DAY_OF_MONTH);

        DatePickerDialog datePickerDialog = new DatePickerDialog(
                context,
                new DatePickerDialog.OnDateSetListener() {
                    @Override
                    public void onDateSet(DatePicker view, int year, int month, int dayOfMonth) {
                        Calendar selectedDate = Calendar.getInstance();
                        selectedDate.set(year, month, dayOfMonth);
                        setStartDate(selectedDate.getTime());

                        SimpleDateFormat sdf = new SimpleDateFormat("yyyy年MM月dd日", Locale.getDefault());
                        String message = "已切换到日期：" + sdf.format(selectedDate.getTime());
                        Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();
                    }
                },
                year, month, day
        );
        datePickerDialog.show();
    }

    // ========== 触摸事件处理 ==========

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
        float adjustedX = x - contentOffsetX;
        if (adjustedX < sideBarWidth || y < headerHeight) {
            return;
        }
        int day = (int) ((adjustedX - sideBarWidth) / cellWidth);
        int section = (int) ((y - headerHeight) / cellHeight);

        if (day >= 0 && day < COLUMN_COUNT && section >= 0 && section < ROW_COUNT) {
            TimeSlot clickedSlot = findTimeSlotAt(day, section);
            if (clickedSlot != null) {
                longPressedSlot = clickedSlot;
                touchStartTime = System.currentTimeMillis();
                // record down coordinates for slop tolerance
                downX = x;
                downY = y;
                // start counting long press and show slight visual enlargement
                isCountingLongPress = true;
                // prevent parent (e.g., scroll container) from intercepting touch during long-press
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                longPressHandler.postDelayed(longPressRunnable, LONG_PRESS_DURATION);
                // start ticker to animate
                longPressHandler.post(longPressTicker);
                invalidate();
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
        float adjustedX = x - contentOffsetX;
            if (longPressedSlot != null) {
                float dx = Math.abs(x - downX);
                float dy = Math.abs(y - downY);
                if (dx > touchSlop || dy > touchSlop) {
                    // considered a move -> cancel long press
                    longPressHandler.removeCallbacks(longPressRunnable);
                    longPressHandler.removeCallbacks(longPressTicker);
                    // after cancelling long press, start a normal selection from the current point
                    longPressedSlot = null;
                    isLongPressTriggered = false;
                    isCountingLongPress = false;
                    // allow parent to intercept again
                    if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
                    // initialize selection so user can expand from inside an existing slot
                    adjustedX = x - contentOffsetX;
                    int day = (int) ((adjustedX - sideBarWidth) / cellWidth);
                    int section = (int) ((y - headerHeight) / cellHeight);
                    day = Math.max(0, Math.min(day, COLUMN_COUNT - 1));
                    section = Math.max(0, Math.min(section, ROW_COUNT - 1));
                    currentDay = day;
                    currentStartSection = section;
                    currentEndSection = section;
                    isSelecting = true;
                    startX = x;
                    startY = y;
                    invalidate();
                }
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
        longPressHandler.removeCallbacks(longPressTicker);
        isCountingLongPress = false;

        if (isLongPressTriggered) {
            isLongPressTriggered = false;
            longPressedSlot = null;
            invalidate();
            return;
        }

        // If user released before long-press timeout on an existing slot, clear the pressed slot
        if (longPressedSlot != null) {
            longPressedSlot = null;
            invalidate();
            return;
        }

        if (!isSelecting || currentDay < 0) return;

        int start = Math.min(currentStartSection, currentEndSection);
        int end = Math.max(currentStartSection, currentEndSection);

        TimeSlot newSlot = new TimeSlot(currentDay, start, end);

        // Add and merge with adjacent/overlapping slots so slight overshoot still merges as expected
        addOrMergeTimeSlot(newSlot);

        resetSelection();
        if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
    }

    private void handleLongPress() {
        if (longPressedSlot != null) {
            isLongPressTriggered = true;
            isCountingLongPress = false;
            // stop ticker
            longPressHandler.removeCallbacks(longPressTicker);
            // provide haptic feedback
            try { performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS); } catch (Throwable _t) {}
            removeTimeSlot(longPressedSlot);

            String dayStr = getDayString(longPressedSlot.getDay());
            String message = "已删除周" + dayStr + " 第" + (longPressedSlot.getStartSection() + 1) +
                    "-" + (longPressedSlot.getEndSection() + 1) + "节";
            Toast.makeText(getContext(), message, Toast.LENGTH_SHORT).show();

            invalidate();
            if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(false);
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

    // ========== 时间段管理方法 ==========

    private boolean canAddTimeSlot(int day, TimeSlot newSlot) {
        List<TimeSlot> daySlots = timeSlotsMap.get(day);
        if (daySlots.size() >= MAX_SLOTS_PER_DAY) {
            return false;
        }

        for (TimeSlot existingSlot : daySlots) {
            if (newSlot.getStartSection() <= existingSlot.getEndSection() &&
                    newSlot.getEndSection() >= existingSlot.getStartSection()) {
                return false;
            }
        }

        return true;
    }

    public void addTimeSlot(TimeSlot timeSlot) {
        if (timeSlot == null) return;

        int day = timeSlot.getDay();
        List<TimeSlot> daySlots = timeSlotsMap.get(day);
        if (daySlots != null) {
            daySlots.add(timeSlot);
            mergeTimeSlots(day);
            notifyTimeSlotCreated(timeSlot);
            updateWeekData();
            invalidate();
        }
    }

    public void removeTimeSlot(TimeSlot timeSlot) {
        if (timeSlot == null) return;

        int day = timeSlot.getDay();
        List<TimeSlot> daySlots = timeSlotsMap.get(day);
        if (daySlots != null && daySlots.remove(timeSlot)) {
            mergeTimeSlots(day);
            notifyTimeSlotRemoved(timeSlot);
            updateWeekData();
            invalidate();
        }
    }

    /**
     * Add a timeslot and merge with adjacent/overlapping slots. This ensures slight overshoot
     * when the user lifts the finger still results in a merged continuous slot.
     */
    public void addOrMergeTimeSlot(TimeSlot newSlot) {
        if (newSlot == null) return;
        int day = newSlot.getDay();
        List<TimeSlot> daySlots = timeSlotsMap.get(day);
        if (daySlots == null) return;

        // Collect slots to merge with
        List<TimeSlot> toMerge = new ArrayList<>();
        for (TimeSlot s : daySlots) {
            if (s.overlaps(newSlot) || s.isAdjacent(newSlot) || newSlot.overlaps(s) || newSlot.isAdjacent(s)) {
                toMerge.add(s);
            }
        }

        // Merge into newSlot
        for (TimeSlot m : toMerge) {
            newSlot.mergeWith(m);
        }

        // Remove merged slots from daySlots
        daySlots.removeAll(toMerge);
        daySlots.add(newSlot);

        // Re-merge full list to normalize and sort
        mergeTimeSlots(day);
        notifyTimeSlotCreated(newSlot);
        updateWeekData();
        invalidate();
    }

    /**
     * Bulk add time slots and then merge and refresh once.
     */
    public void addTimeSlots(List<TimeSlot> slots) {
        if (slots == null || slots.isEmpty()) return;
        for (TimeSlot s : slots) {
            if (s == null) continue;
            List<TimeSlot> daySlots = timeSlotsMap.get(s.getDay());
            if (daySlots != null) daySlots.add(new TimeSlot(s));
        }
        for (int i = 0; i < COLUMN_COUNT; i++) mergeTimeSlots(i);
        notifyTimeSlotsChanged();
        updateWeekData();
        invalidate();
    }

    public void clearTimeSlots() {
        for (int i = 0; i < COLUMN_COUNT; i++) {
            timeSlotsMap.get(i).clear();
        }
        notifyTimeSlotsChanged();
        updateWeekData();
        invalidate();
    }

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

    // ========== 周数据管理 ==========

    /**
     * 更新当前周的空闲时间数据
     */
    private void updateWeekData() {
        // currentWeekData.clear(); // FIX: Do not clear all data, only update current view's dates
        // record the current week's start date (normalized to headerDates[0] which is Monday)
        if (headerDates != null && headerDates[0] != null) {
            currentWeekData.setWeekStartDate(headerDates[0]);
        }
        for (int day = 0; day < COLUMN_COUNT; day++) {
            // Remove old data for this date to avoid duplication/stale data
            if (headerDates[day] != null) {
                currentWeekData.removeDataForDate(headerDates[day]);
            }
            
            List<TimeSlot> daySlots = timeSlotsMap.get(day);
            if (daySlots != null) {
                for (TimeSlot slot : daySlots) {
                    currentWeekData.addTimeSlot(headerDates[day], slot.getStartSection(), slot.getEndSection());
                }
            }
        }
    }

    /**
     * 获取当前周的空闲时间数据
     */
    public WeekTimeData getCurrentWeekData() {
        return currentWeekData;
    }

    /**
     * 设置周数据（从其他Fragment恢复数据）
     */
    public void setWeekData(WeekTimeData weekData) {
        if (weekData == null) return;

        // 确保日期头部已经初始化
        if (headerDates[0] == null) {
            setStartDate(Calendar.getInstance().getTime());
        }

        clearTimeSlots();
        currentWeekData = new WeekTimeData(weekData); // 使用拷贝构造函数

        // 根据当前显示的日期加载数据
        for (int day = 0; day < COLUMN_COUNT; day++) {
            List<WeekTimeData.TimeRange> dateRanges = currentWeekData.getDateTimeRanges(headerDates[day]);
            if (dateRanges != null) {
                for (WeekTimeData.TimeRange range : dateRanges) {
                    TimeSlot slot = new TimeSlot(day, range.getStartSection(), range.getEndSection());
                    addTimeSlotWithoutMerge(slot);
                }
            }
        }

        // 合并时间段
        for (int i = 0; i < COLUMN_COUNT; i++) {
            mergeTimeSlots(i);
        }

        invalidate();
    }

    // ========== 辅助方法 ==========

    private void addTimeSlotWithoutMerge(TimeSlot timeSlot) {
        if (timeSlot == null) return;

        int day = timeSlot.getDay();
        List<TimeSlot> daySlots = timeSlotsMap.get(day);
        if (daySlots != null) {
            daySlots.add(timeSlot);
        }
    }

    // ========== 监听器管理 ==========

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

    public List<TimeSlot> getAllTimeSlots() {
        List<TimeSlot> allSlots = new ArrayList<>();
        for (int i = 0; i < COLUMN_COUNT; i++) {
            allSlots.addAll(timeSlotsMap.get(i));
        }
        return allSlots;
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        longPressHandler.removeCallbacksAndMessages(null);
    }
}