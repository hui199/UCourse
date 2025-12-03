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
 * 课程推荐求解器 - 基于运筹学分支定界算法 (Branch and Bound)
 * 
 * 核心算法逻辑：
 * 1. 预处理：
 *    - 过滤不可行课程（兴趣为0或不在空闲时间内）
 *    - 将课程按兴趣分降序排序（启发式策略，优先搜索高分课程）
 *    - 预先计算每门课程的冲突掩码（Bitmask）
 * 
 * 2. 搜索过程 (DFS):
 *    - 状态空间树搜索，每个节点代表一门课程是否被选择
 *    - 剪枝策略 (Pruning):
 *      - 乐观估值剪枝：当前分数 + 剩余所有课程最大可能分数 <= 当前第K优解的分数 -> 剪枝
 *      - 冲突检测：利用位运算快速检测时间冲突
 * 
 * 3. 解决方案管理：
 *    - 维护一个有序的大小为K的优先队列（List实现）
 *    - 仅当新方案优于队列中最后一名时才尝试插入
 */
public class RecommendationSolver {
    private static final String UCourse_TAG = "UCourse_SOLVER";
    
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
        
        // 增强方法：构建详细说明
        public void enhanceExplanation(Map<String, List<TimeSlot>> courseTimeSlots) {
            if (courses == null || courses.isEmpty()) {
                this.explanation = "无课程安排";
                return;
            }
            
            // 重新计算占用时间槽
            this.occupiedSlots = new HashSet<>();
            Set<Integer> days = new HashSet<>();
            
            for (Course c : courses) {
                List<TimeSlot> sl = courseTimeSlots.get(c.id);
                if (sl == null) continue;
                for (TimeSlot t : sl) {
                    days.add(t.getDay());
                    for (int s = t.getStartSection(); s <= t.getEndSection(); s++) {
                        long key = (((long) t.getDay()) << 32) | (s & 0xffffffffL);
                        this.occupiedSlots.add(key);
                    }
                }
            }
            this.totalPeriods = this.occupiedSlots.size();
            this.daysUsed = days.size();
            
            StringBuilder sb = new StringBuilder();
            sb.append("总兴趣分: ").append(totalScore).append("\n");
            sb.append("课程数: ").append(courses.size()).append("\n");
            sb.append("总课时: ").append(totalPeriods).append("\n");
            sb.append("占用天数: ").append(daysUsed).append("\n");
            this.explanation = sb.toString();
        }
    }

    private final List<Course> allCourses;
    private final Map<String, List<TimeSlot>> courseTimeSlots;
    private final WeekTimeData freeTimes;
    private final int K;
    private final long timeLimitMs;

    // 运行时状态
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
        Log.d(UCourse_TAG, "开始求解... 原始课程数: " + allCourses.size());
        nodesVisited.set(0);
        prunedCount.set(0);
        timedOut = false;

        // 1. 预处理与过滤
        long filterStart = System.currentTimeMillis();
        List<Course> feasible = new ArrayList<>();
        for (Course c : allCourses) {
            if (c == null) continue;
            if (c.interest <= 0) continue; // 排除无兴趣课程
            
            List<TimeSlot> slots = courseTimeSlots == null ? null : courseTimeSlots.get(c.id);
            if (slots == null || slots.isEmpty()) continue;
            
            if (isWithinFreeTime(slots)) {
                feasible.add(c);
            }
        }
        
        // 2. 启发式排序：优先考虑高兴趣分课程
        // 次级排序：课时少的优先（单位时间收益高），但这里简单起见只用兴趣分
        Collections.sort(feasible, (a, b) -> Integer.compare(b.interest, a.interest));
        
        Log.d(UCourse_TAG, "可行课程数: " + feasible.size() + ", 预处理耗时: " + (System.currentTimeMillis() - filterStart) + "ms");

        // 3. 准备Bitmasks加速冲突检测
        int n = feasible.size();
        int[] interests = new int[n];
        List<Set<Long>> masks = new ArrayList<>();
        
        for (int i = 0; i < n; i++) {
            Course c = feasible.get(i);
            interests[i] = c.interest;
            
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

        // 4. 计算后缀和（用于剪枝的乐观估值）
        int[] suffixSum = new int[n + 1];
        suffixSum[n] = 0;
        for (int i = n - 1; i >= 0; i--) {
            suffixSum[i] = suffixSum[i + 1] + interests[i];
        }

        // 5. 开始分支定界搜索
        List<Solution> bestSolutions = new ArrayList<>();
        AtomicLong startTime = new AtomicLong(System.currentTimeMillis());
        
        dfs(0, feasible, masks, new HashSet<>(), new ArrayList<>(), 0, suffixSum, bestSolutions, startTime);
        
        long elapsed = System.currentTimeMillis() - startTime.get();
        lastSummary = String.format("Nodes: %d, Pruned: %d, Time: %dms, Solutions: %d", 
                nodesVisited.get(), prunedCount.get(), elapsed, bestSolutions.size());
        Log.d(UCourse_TAG, "求解结束: " + lastSummary);
        
        return bestSolutions;
    }

    /**
     * 深度优先搜索 + 分支定界
     * 
     * @param idx 当前考虑的课程索引
     * @param courses 所有可行课程列表
     * @param masks 每门课程的时间掩码
     * @param occupied 当前已占用的时间槽
     * @param chosen 当前已选择的课程
     * @param currentScore 当前总分
     * @param suffixSum 后缀和数组，用于估算剩余最大可能分数
     * @param bestSolutions 当前最优解集合
     * @param startTime 开始时间
     */
    private void dfs(int idx, List<Course> courses, List<Set<Long>> masks, Set<Long> occupied, 
                     List<Course> chosen, int currentScore, int[] suffixSum, 
                     List<Solution> bestSolutions, AtomicLong startTime) {
        
        nodesVisited.incrementAndGet();
        
        // 超时检查 (每1000个节点检查一次，减少系统调用开销)
        if (nodesVisited.get() % 1000 == 0) {
            if (timeLimitMs > 0 && System.currentTimeMillis() - startTime.get() > timeLimitMs) {
                timedOut = true;
                return;
            }
        }
        if (timedOut) return;

        // 强力剪枝：乐观估值
        // 如果 (当前分数 + 剩余所有课程的分数) <= 第K优解的分数，则无需继续搜索
        if (bestSolutions.size() >= K) {
            int maxPossibleScore = currentScore + suffixSum[idx];
            int minScoreInBest = bestSolutions.get(bestSolutions.size() - 1).totalScore;
            
            // 如果只能达到平局且方案更复杂，也可以剪枝
            if (maxPossibleScore < minScoreInBest) {
                prunedCount.incrementAndGet();
                return;
            }
        }

        // 叶子节点：所有课程都已考虑
        if (idx >= courses.size()) {
            if (!chosen.isEmpty()) {
                addSolution(bestSolutions, new ArrayList<>(chosen), currentScore);
            }
            return;
        }

        // 分支1：尝试选择当前课程
        // 冲突检测
        Set<Long> currentMask = masks.get(idx);
        boolean conflict = false;
        for (Long slot : currentMask) {
            if (occupied.contains(slot)) {
                conflict = true;
                break;
            }
        }

        if (!conflict) {
            // 执行选择
            for (Long slot : currentMask) occupied.add(slot);
            chosen.add(courses.get(idx));
            
            // 递归下一层
            dfs(idx + 1, courses, masks, occupied, chosen, currentScore + courses.get(idx).interest, 
                suffixSum, bestSolutions, startTime);
            
            // 回溯
            chosen.remove(chosen.size() - 1);
            for (Long slot : currentMask) occupied.remove(slot);
        }

        // 分支2：不选择当前课程
        // 只有当"不选"仍有可能产生优解时才搜索
        // 这里的剪枝已经在函数开头的"乐观估值"中涵盖了，但为了更精细，可以在进入分支前再次检查
        if (bestSolutions.size() >= K) {
            int maxPossibleWithoutCurrent = currentScore + suffixSum[idx + 1];
            int minScoreInBest = bestSolutions.get(bestSolutions.size() - 1).totalScore;
            if (maxPossibleWithoutCurrent < minScoreInBest) {
                return; // 剪枝：不选这门课肯定没戏
            }
        }
        
        dfs(idx + 1, courses, masks, occupied, chosen, currentScore, suffixSum, bestSolutions, startTime);
    }

    /**
     * 将新方案尝试添加到结果集
     * 策略：保持Top K，去重，但不根据时间冲突互斥（不同方案可以时间重叠，只要课程组合不同）
     */
    private void addSolution(List<Solution> best, List<Course> courses, int score) {
        // 1. 质量过滤：课程太少通常不是好的排课方案
        if (courses.size() < 2) return; 

        // 2. 重复检测：如果完全相同的课程组合已存在，则忽略
        // 简单的O(K*N)检测，K很小所以没问题
        for (Solution s : best) {
            if (s.totalScore == score && s.courses.size() == courses.size()) {
                // 检查是否课程ID完全一致
                boolean match = true;
                Set<String> existingIds = new HashSet<>();
                for(Course c : s.courses) existingIds.add(c.id);
                
                for(Course c : courses) {
                    if(!existingIds.contains(c.id)) {
                        match = false;
                        break;
                    }
                }
                if (match) return; // 完全重复
            }
        }

        // 3. 插入排序
        Solution newSol = new Solution(courses, score);
        newSol.enhanceExplanation(courseTimeSlots); // 计算辅助信息
        
        int insertPos = 0;
        boolean added = false;
        
        for (int i = 0; i < best.size(); i++) {
            Solution s = best.get(i);
            // 排序规则：分数高的在前；分数相同，课程数多的在前
            if (score > s.totalScore) {
                best.add(i, newSol);
                added = true;
                break;
            } else if (score == s.totalScore) {
                if (courses.size() > s.courses.size()) { // 偏好更多课程
                    best.add(i, newSol);
                    added = true;
                    break;
                }
            }
        }
        
        if (!added && best.size() < K) {
            best.add(newSol);
        }

        // 4. 保持K的大小
        while (best.size() > K) {
            best.remove(best.size() - 1);
        }
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
}