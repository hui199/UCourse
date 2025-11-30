package com.pku.or.ucourse.model;

import java.io.Serializable;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 支持按日期存储的空闲时间数据
 */
public class WeekTimeData implements Serializable {
    private static final long serialVersionUID = 1L;

    // 按日期存储时间段数据（键为日期字符串"yyyy-MM-dd"）
    private Map<String, List<TimeRange>> dateTimeData;

    // 当前周的日期范围
    private Date weekStartDate;
    private Date weekEndDate;

    public WeekTimeData() {
        dateTimeData = new HashMap<>();
    }

    /**
     * 拷贝构造函数
     */
    public WeekTimeData(WeekTimeData other) {
        this();
        if (other != null) {
            // 深拷贝
            this.dateTimeData = new HashMap<>();
            for (Map.Entry<String, List<TimeRange>> entry : other.dateTimeData.entrySet()) {
                List<TimeRange> copiedList = new ArrayList<>();
                for (TimeRange range : entry.getValue()) {
                    copiedList.add(new TimeRange(range));
                }
                this.dateTimeData.put(entry.getKey(), copiedList);
            }
            this.weekStartDate = other.weekStartDate;
            this.weekEndDate = other.weekEndDate;
        }
    }

    /**
     * 添加时间段（按具体日期）
     */
    public void addTimeSlot(Date date, int startSection, int endSection) {
        if (date == null) return;

        String dateKey = getDateKey(date);
        if (!dateTimeData.containsKey(dateKey)) {
            dateTimeData.put(dateKey, new ArrayList<TimeRange>());
        }

        TimeRange newRange = new TimeRange(startSection, endSection);
        dateTimeData.get(dateKey).add(newRange);
        mergeTimeRanges(dateKey);
    }

    /**
     * 获取某天的空闲时间段
     */
    public List<TimeRange> getDateTimeRanges(Date date) {
        if (date == null) return new ArrayList<>();

        String dateKey = getDateKey(date);
        List<TimeRange> ranges = dateTimeData.get(dateKey);
        return ranges != null ? new ArrayList<>(ranges) : new ArrayList<TimeRange>();
    }

    /**
     * 设置当前 WeekTimeData 所对应的一周的起始日期（周一）。
     */
    public void setWeekStartDate(Date startDate) {
        this.weekStartDate = startDate;
    }

    /**
     * 获取本对象所记录的一周的起始日期（周一），若未设置则返回 null。
     */
    public Date getWeekStartDate() {
        return this.weekStartDate;
    }

    /**
     * 按 day index (0=周一 .. 6=周日) 获取对应的 TimeRange 列表。
     * 如果未设置 weekStartDate 或 dayIndex 越界则返回空列表。
     */
    public List<TimeRange> getDateTimeRangesByDayIndex(int dayIndex) {
        if (weekStartDate == null) return new ArrayList<>();
        if (dayIndex < 0 || dayIndex >= 7) return new ArrayList<>();
        Calendar cal = Calendar.getInstance();
        cal.setTime(weekStartDate);
        cal.add(Calendar.DAY_OF_MONTH, dayIndex);
        Date target = cal.getTime();
        return getDateTimeRanges(target);
    }

    /**
     * 清空所有数据
     */
    public void clear() {
        dateTimeData.clear();
        weekStartDate = null;
        weekEndDate = null;
    }

    /**
     * 合并相邻的时间段
     */
    private void mergeTimeRanges(String dateKey) {
        List<TimeRange> ranges = dateTimeData.get(dateKey);
        if (ranges == null || ranges.size() <= 1) return;
        // Ensure ranges are sorted by startSection before merging to avoid missing merges
        Collections.sort(ranges, (a, b) -> Integer.compare(a.getStartSection(), b.getStartSection()));

        List<TimeRange> merged = new ArrayList<>();
        TimeRange current = ranges.get(0);

        for (int i = 1; i < ranges.size(); i++) {
            TimeRange next = ranges.get(i);

            if (current.overlaps(next) || current.isAdjacent(next)) {
                current = new TimeRange(
                        Math.min(current.getStartSection(), next.getStartSection()),
                        Math.max(current.getEndSection(), next.getEndSection())
                );
            } else {
                merged.add(current);
                current = next;
            }
        }
        merged.add(current);

        dateTimeData.put(dateKey, merged);
    }

    /**
     * 获取日期键（yyyy-MM-dd格式）
     */
    private String getDateKey(Date date) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd", Locale.getDefault());
        return sdf.format(date);
    }

    // 重写 equals 和 hashCode 方法
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        WeekTimeData that = (WeekTimeData) o;
        return Objects.equals(dateTimeData, that.dateTimeData);
    }

    @Override
    public int hashCode() {
        return Objects.hash(dateTimeData);
    }

    /**
     * 时间段内部类
     */
    public static class TimeRange implements Serializable {
        private int startSection;
        private int endSection;

        public TimeRange(int startSection, int endSection) {
            this.startSection = startSection;
            this.endSection = endSection;
        }

        // 增加拷贝构造函数
        public TimeRange(TimeRange other) {
            this.startSection = other.startSection;
            this.endSection = other.endSection;
        }

        public int getStartSection() { return startSection; }
        public int getEndSection() { return endSection; }

        public boolean contains(int section) {
            return section >= startSection && section <= endSection;
        }

        public boolean overlaps(TimeRange other) {
            return this.startSection <= other.endSection && other.startSection <= this.endSection;
        }

        public boolean isAdjacent(TimeRange other) {
            return this.endSection + 1 == other.startSection || other.endSection + 1 == this.startSection;
        }

        @Override
        public String toString() {
            return "TimeRange{" +
                    "startSection=" + startSection +
                    ", endSection=" + endSection +
                    '}';
        }

        // 重写 equals 和 hashCode 方法
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            TimeRange timeRange = (TimeRange) o;
            return startSection == timeRange.startSection && endSection == timeRange.endSection;
        }

        @Override
        public int hashCode() {
            return Objects.hash(startSection, endSection);
        }
    }

    @Override
    public String toString() {
        return "WeekTimeData{" +
                "dateTimeData=" + dateTimeData +
                ", weekStartDate=" + weekStartDate +
                ", weekEndDate=" + weekEndDate +
                '}';
    }
}