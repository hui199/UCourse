package com.pku.or.ucourse.result;

import com.pku.or.ucourse.home.Course;
import com.pku.or.ucourse.view.TimeSlot;
import com.pku.or.ucourse.model.WeekTimeData;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Clean, self-contained recommendation solver (branch-and-bound) with light stats.
 */
public class RecommendationSolver {
    public static class Solution {
        public List<Course> courses;
        public int totalScore;
        public int totalPeriods;
        public int daysUsed;
        public String explanation;
        public Set<Long> occupiedSlots;
        public Solution(List<Course> courses, int totalScore) {
            this.courses = courses;
            this.totalScore = totalScore;
            this.totalPeriods = 0;
            this.daysUsed = 0;
            this.explanation = "";
            this.occupiedSlots = new HashSet<>();
        }
        
        // 增强方法：构建详细说明，便于UI显示分段卡片内容
        public void enhanceExplanation(Map<String, List<TimeSlot>> courseTimeSlots) {
            if (courses == null || courses.isEmpty()) {
                this.explanation = "无课程安排";
                return;
            }
            
            StringBuilder sb = new StringBuilder();
            sb.append("总兴趣分: ").append(totalScore).append("\n");
            sb.append("课程数: ").append(courses.size()).append("\n");
            sb.append("总课时: ").append(totalPeriods).append("\n");
            sb.append("占用天数: ").append(daysUsed).append("\n\n");
            sb.append("详细安排:\n");
            
            // 按天分组课程
            Map<Integer, List<String>> dayToCourses = new HashMap<>();
            for (Course course : courses) {
                List<TimeSlot> slots = courseTimeSlots.get(course.id);
                if (slots != null) {
                    for (TimeSlot ts : slots) {
                        int day = ts.getDay();
                        dayToCourses.putIfAbsent(day, new ArrayList<>());
                        String courseInfo = course.title + " [周" + dayName(day) + ts.getStartSection() + 1;
                        if (ts.getEndSection() != ts.getStartSection()) {
                            courseInfo += "-" + (ts.getEndSection() + 1);
                        }
                        courseInfo += "]";
                        dayToCourses.get(day).add(courseInfo);
                    }
                }
            }
            
            // 按顺序输出每天的课程
            for (int i = 0; i < 7; i++) {
                if (dayToCourses.containsKey(i)) {
                    sb.append("周").append(dayName(i)).append(": ");
                    List<String> coursesOnDay = dayToCourses.get(i);
                    for (int j = 0; j < coursesOnDay.size(); j++) {
                        sb.append(coursesOnDay.get(j));
                        if (j < coursesOnDay.size() - 1) {
                            sb.append(", ");
                        }
                    }
                    sb.append("\n");
                }
            }
            
            this.explanation = sb.toString();
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
                default: return String.valueOf(d);
            }
        }
    }

    private final List<Course> allCourses;
    private final Map<String, List<TimeSlot>> courseTimeSlots;
    private final WeekTimeData freeTimes;
    private final int K;
    private final long timeLimitMs;

    // runtime stats/state
    private final AtomicLong nodesVisited = new AtomicLong(0);
    private final AtomicLong prunedCount = new AtomicLong(0);
    private volatile boolean timedOut = false;
    public volatile String lastSummary = "";

    public RecommendationSolver(List<Course> courses, Map<String, List<TimeSlot>> courseTimeSlots, WeekTimeData freeTimes, int K, long timeLimitMs) {
        this.allCourses = new ArrayList<>(courses == null ? Collections.emptyList() : courses);
        this.courseTimeSlots = courseTimeSlots;
        this.freeTimes = freeTimes;
        this.K = K <= 0 ? 3 : K;
        this.timeLimitMs = timeLimitMs;
    }

    public List<Solution> solve() {
        nodesVisited.set(0);
        prunedCount.set(0);
        timedOut = false;

        // prefilter feasible courses (drop interest==0)
        List<Course> feasible = new ArrayList<>();
        for (Course c : allCourses) {
            if (c == null) continue;
            if (c.interest == 0) continue; // never consider 0-interest courses
            List<TimeSlot> slots = courseTimeSlots == null ? null : courseTimeSlots.get(c.id);
            if (slots == null || slots.isEmpty()) continue;
            if (isWithinFreeTime(slots)) feasible.add(c);
        }

        // 移除课程数量硬限制，允许更多课程参与搜索
        final int HARD_CAP = 100; // 大幅增加课程数量限制，允许更多课程参与推荐计算
        if (feasible.size() > HARD_CAP) {
            // sort by interest desc and keep top HARD_CAP
            Collections.sort(feasible, (a,b) -> Integer.compare(b.interest, a.interest));
            feasible = new ArrayList<>(feasible.subList(0, Math.min(HARD_CAP, feasible.size())));
        }

        // sort by interest desc
        Collections.sort(feasible, (a,b) -> Integer.compare(b.interest, a.interest));
        // Feasible count=" + feasible.size() + "
        // Feasible courses removed from logging

        // attempt to greedily pre-include non-conflicting interest==10 courses
        List<Course> prefix = new ArrayList<>();
        Set<Long> occupiedPrefix = new HashSet<>();
        for (int i = 0; i < feasible.size(); i++) {
            Course c = feasible.get(i);
            if (c.interest == 10) {
                boolean conflict = false;
                List<TimeSlot> slots = courseTimeSlots.get(c.id);
                if (slots != null) {
                    for (TimeSlot ts : slots) {
                        for (int s = ts.getStartSection(); s <= ts.getEndSection(); s++) {
                            long key = (((long) ts.getDay()) << 32) | (s & 0xffffffffL);
                            if (occupiedPrefix.contains(key)) { conflict = true; break; }
                        }
                        if (conflict) break;
                    }
                }
                if (!conflict) {
                    prefix.add(c);
                    if (slots != null) {
                        for (TimeSlot ts : slots) {
                            for (int s = ts.getStartSection(); s <= ts.getEndSection(); s++) {
                                long key = (((long) ts.getDay()) << 32) | (s & 0xffffffffL);
                                occupiedPrefix.add(key);
                            }
                        }
                    }
                }
            }
        }

        // remove prefixed courses from feasible list to avoid duplication in DFS
        if (!prefix.isEmpty()) {
            feasible.removeAll(prefix);
        }

    // we will run DFS on the remaining feasible courses, but start with prefix pre-included
    int n = feasible.size();
        int[] interests = new int[n];
        for (int i = 0; i < n; i++) interests[i] = feasible.get(i).interest;

        // prepare masks
        List<Set<Long>> masks = new ArrayList<>();
        for (Course c : feasible) {
            Set<Long> m = new HashSet<>();
            List<TimeSlot> slots = courseTimeSlots.get(c.id);
            if (slots != null) {
                for (TimeSlot ts : slots) {
                    for (int s = ts.getStartSection(); s <= ts.getEndSection(); s++) {
                        long key = (((long) ts.getDay()) << 32) | (s & 0xffffffffL);
                        m.add(key);
                    }
                }
            }
            masks.add(m);
        }

        int[] cumsum = new int[n+1];
        for (int i = n-1; i >= 0; i--) cumsum[i] = cumsum[i+1] + interests[i];

        List<Solution> best = new ArrayList<>();
        AtomicLong start = new AtomicLong(System.currentTimeMillis());

    // initial chosen and occupied from prefix
    List<Course> initialChosen = new ArrayList<>(prefix);
    Set<Long> initialOccupied = new HashSet<>(occupiedPrefix);
    int initialScore = 0;
    for (Course pc : prefix) initialScore += pc.interest;

    dfs(0, feasible, masks, initialOccupied, initialChosen, initialScore, cumsum, best, start);

        long elapsed = System.currentTimeMillis() - start.get();
        StringBuilder sb = new StringBuilder();
        sb.append("nodes=").append(nodesVisited.get()).append(", pruned=").append(prunedCount.get())
                .append(", elapsed=").append(elapsed).append("ms, solutions=").append(best.size())
                .append(", timedOut=").append(timedOut);
        lastSummary = sb.toString();
        return best;
    }

    private void dfs(int idx, List<Course> courses, List<Set<Long>> masks, Set<Long> occupied, List<Course> chosen, int score, int[] cumsum, List<Solution> best, AtomicLong start) {
        nodesVisited.incrementAndGet();
        if (timeLimitMs > 0 && System.currentTimeMillis() - start.get() > timeLimitMs) { timedOut = true; return; }

        // 早期剪枝优化：如果当前方案不可能比已知的最差方案好，且已达到K个方案，直接返回
        int optimistic = score + cumsum[idx];
        if (best.size() >= K && optimistic <= best.get(best.size()-1).totalScore) {
            prunedCount.incrementAndGet();
            return;
        }

        // 方案完成时添加
        if (idx >= courses.size()) {
            if (chosen == null || chosen.isEmpty()) return;
            
            // 计算方案基本信息
            Set<Long> occ = new HashSet<>();
            Set<Integer> days = new HashSet<>();
            for (Course c : chosen) {
                List<TimeSlot> sl = courseTimeSlots.get(c.id);
                if (sl == null) continue;
                for (int i = 0; i < sl.size(); i++) {
                    TimeSlot t = sl.get(i);
                    for (int s = t.getStartSection(); s <= t.getEndSection(); s++) {
                        long key = (((long) t.getDay()) << 32) | (s & 0xffffffffL);
                        occ.add(key);
                        days.add(t.getDay());
                    }
                }
            }
            
            // 创建方案并添加详细信息
            Solution sol = new Solution(new ArrayList<>(chosen), score);
            sol.totalPeriods = occ.size();
            sol.daysUsed = days.size();
            sol.occupiedSlots = occ;
            sol.enhanceExplanation(courseTimeSlots);
            
            // 添加到结果集
            addSolution(best, sol);
            return;
        }

        // 尝试包含当前课程
        Set<Long> mask = masks.get(idx);
        boolean conflict = false;
        for (Long k : mask) if (occupied.contains(k)) { conflict = true; break; }
        
        if (!conflict) {
            // 提前检查：如果添加当前课程后，即使后续所有课程都能添加，也无法超过已知最优方案，则跳过
            int potentialScore = score + courses.get(idx).interest + (idx + 1 < cumsum.length ? cumsum[idx + 1] : 0);
            if (!(best.size() >= K && potentialScore <= best.get(best.size() - 1).totalScore)) {
                // 添加当前课程并继续搜索
                for (Long k : mask) occupied.add(k);
                chosen.add(courses.get(idx));
                dfs(idx + 1, courses, masks, occupied, chosen, score + courses.get(idx).interest, cumsum, best, start);
                chosen.remove(chosen.size() - 1);
                for (Long k : mask) occupied.remove(k);
            }
        }

        // 尝试不包含当前课程
        // 提前检查：如果不包含当前课程后，即使后续所有课程都能添加，也无法超过已知最优方案，则跳过
        int excludePotentialScore = score + (idx + 1 < cumsum.length ? cumsum[idx + 1] : 0);
        if (!(best.size() >= K && excludePotentialScore <= best.get(best.size() - 1).totalScore)) {
            dfs(idx + 1, courses, masks, occupied, chosen, score, cumsum, best, start);
        }
    }

    private boolean isSubset(Solution sub, Solution sup) {
        // 添加空值检查
        if (sub == null || sup == null || sub.courses == null || sup.courses == null) {
            return false;
        }
        
        // 首先检查基本条件：子方案课程数量必须小于父方案
        if (sub.courses.size() >= sup.courses.size() || sub.courses.isEmpty()) {
            return false;
        }
        
        // 优化：使用HashSet存储父方案的课程ID，避免嵌套循环
        Set<String> supCourseIds = new HashSet<>();
        for (Course c : sup.courses) {
            if (c.id != null) {
                supCourseIds.add(c.id);
            }
        }
        
        // 检查子方案的所有课程ID是否都在父方案中
        for (Course c : sub.courses) {
            if (c.id == null || !supCourseIds.contains(c.id)) {
                return false;
            }
        }
        
        return true;
    }

    private synchronized void addSolution(List<Solution> best, Solution s) {
        // 空值检查
        if (best == null || s == null || s.courses == null || s.courses.isEmpty()) {
            return;
        }
        
        // 生成阶段过滤：如果方案课程数过少，直接跳过（确保质量）
        if (s.courses.size() < 3) {
            return;
        }
        
        // 快速检查：如果best为空，直接添加并返回
        if (best.isEmpty()) {
            best.add(s);
            return;
        }
        
        // 高效剪枝：如果当前方案分数低于最差方案且已达到K个，直接跳过
        if (best.size() >= K) {
            Solution worstSolution = best.get(best.size() - 1);
            if (s.totalScore < worstSolution.totalScore || 
                (s.totalScore == worstSolution.totalScore && s.totalPeriods >= worstSolution.totalPeriods)) {
                return;
            }
        }
        
        // 检查重复方案
        for (Solution existing : best) {
            if (existing.totalScore == s.totalScore && existing.occupiedSlots.equals(s.occupiedSlots)) {
                return; // 找到重复，忽略当前方案
            }
        }
        
        // 收集需要移除的方案和判断是否应该添加当前方案
        List<Solution> toRemove = new ArrayList<>();
        boolean shouldAdd = true;
        
        // 批量处理冲突和子集关系检查
        for (int i = 0; i < best.size(); i++) {
            Solution existing = best.get(i);
            
            // 1. 检查子集关系 - 如果当前方案是现有方案的子集，则不添加
            if (isSubset(s, existing)) {
                shouldAdd = false;
                break;
            }
            
            // 2. 检查是否现有方案是当前方案的子集 - 如果是，移除现有方案
            if (isSubset(existing, s)) {
                toRemove.add(existing);
                continue;
            }
            
            // 3. 检查时间段冲突 - 对于冲突的方案，保留分数更高的
            if (hasTimeOverlap(s, existing)) {
                if (s.totalScore > existing.totalScore) {
                    toRemove.add(existing); // 当前方案更好，移除现有方案
                } else {
                    shouldAdd = false; // 现有方案更好，不添加当前方案
                    break;
                }
            }
        }
        
        // 如果不应该添加，直接返回
        if (!shouldAdd) {
            return;
        }
        
        // 移除标记的低质量方案
        best.removeAll(toRemove);
        
        // 按分数和时间段数量排序并插入当前方案
        int pos = 0;
        while (pos < best.size()) {
            Solution cur = best.get(pos);
            if (s.totalScore > cur.totalScore) break;
            if (s.totalScore == cur.totalScore && s.totalPeriods < cur.totalPeriods) break;
            pos++;
        }
        best.add(pos, s);
        
        // 严格控制方案数量，保留前K个最高分方案
        if (best.size() > K) {
            best.subList(K, best.size()).clear(); // 批量移除，更高效
        }
    }
    
    // 检查两个方案是否有时间段重叠
    private boolean hasTimeOverlap(Solution s1, Solution s2) {
        if (s1 == null || s2 == null || s1.occupiedSlots == null || s2.occupiedSlots == null) {
            return false;
        }
        // 如果两个方案的占用时间段集合有交集，则存在重叠
        for (Long slot : s1.occupiedSlots) {
            if (s2.occupiedSlots.contains(slot)) {
                return true;
            }
        }
        return false;
    }

    private boolean isWithinFreeTime(List<TimeSlot> slots) {
        if (freeTimes == null) return false;
        for (TimeSlot ts : slots) {
            List<WeekTimeData.TimeRange> ranges = freeTimes.getDateTimeRangesByDayIndex(ts.getDay());
            if (ranges == null || ranges.isEmpty()) return false;
            boolean ok = false;
            for (WeekTimeData.TimeRange r : ranges) {
                if (ts.getStartSection() >= r.getStartSection() && ts.getEndSection() <= r.getEndSection()) { ok = true; break; }
            }
            if (!ok) return false;
        }
        return true;
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
            default: return String.valueOf(d);
        }
    }
}

