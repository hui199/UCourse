package com.pku.or.ucourse.utils;

import android.util.Log;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 性能监控日志工具类
 * 提供统一的性能监控和日志记录功能
 */
public class PerformanceLogger {

    private static final String PERF_TAG = "UCourse_Performance";
    private static final String DEBUG_TAG = "UCourse_DEBUG";
    private static final boolean ENABLE_PERF_LOGS = true; // 可通过配置文件或构建变体控制
    private static final boolean ENABLE_DEBUG_LOGS = true;
    
    // 存储各个操作的开始时间
    private static final Map<String, Long> startTimeMap = new ConcurrentHashMap<>();
    // 存储操作的统计信息
    private static final Map<String, PerformanceStats> statsMap = new HashMap<>();
    
    /**
     * 记录操作开始时间
     * @param operationName 操作名称，用于标识不同的性能监控点
     */
    public static void startOperation(String operationName) {
        if (ENABLE_PERF_LOGS) {
            startTimeMap.put(operationName, System.currentTimeMillis());
            log("操作开始: " + operationName + " - " + System.currentTimeMillis());
        }
    }
    
    /**
     * 记录操作结束时间并计算耗时
     * @param operationName 操作名称，必须与startOperation中的名称一致
     * @return 操作耗时（毫秒），如果操作未开始则返回-1
     */
    public static long endOperation(String operationName) {
        if (!ENABLE_PERF_LOGS) {
            return -1;
        }
        
        Long startTime = startTimeMap.remove(operationName);
        if (startTime == null) {
            log("警告: 未找到操作开始时间 - " + operationName);
            return -1;
        }
        
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        
        // 记录性能统计
        synchronized (statsMap) {
            PerformanceStats stats = statsMap.get(operationName);
            if (stats == null) {
                stats = new PerformanceStats();
                statsMap.put(operationName, stats);
            }
            stats.record(duration);
        }
        
        log(String.format("操作完成: %s - 耗时: %dms (平均耗时: %.1fms, 调用次数: %d) - %d", 
                operationName, duration, getAverageTime(operationName), getCallCount(operationName), endTime));
        return duration;
    }
    
    /**
     * 记录关键性能点
     * @param pointName 性能点名称
     * @param message 附加信息
     */
    public static void logPerformancePoint(String pointName, String message) {
        if (ENABLE_PERF_LOGS) {
            log(pointName + ": " + message + " - " + System.currentTimeMillis());
        }
    }
    
    /**
     * 记录进度信息
     * @param operationName 操作名称
     * @param progress 进度值（0-100）
     * @param elapsedMs 已用时（毫秒）
     */
    public static void logProgress(String operationName, int progress, long elapsedMs) {
        if (ENABLE_PERF_LOGS) {
            log(String.format("进度更新[%s]: %d%%, 已耗时: %dms - %d", 
                    operationName, progress, elapsedMs, System.currentTimeMillis()));
        }
    }
    
    /**
     * 记录调试信息
     * @param message 调试消息
     */
    public static void debug(String message) {
        if (ENABLE_DEBUG_LOGS) {
            Log.e(DEBUG_TAG, message);
        }
    }
    
    /**
     * 记录错误信息
     * @param message 错误消息
     * @param throwable 异常对象
     */
    public static void error(String message, Throwable throwable) {
        Log.e(DEBUG_TAG, message, throwable);
    }
    
    /**
     * 记录组件生命周期事件
     * @param componentName 组件名称
     * @param eventName 事件名称
     */
    public static void logLifecycleEvent(String componentName, String eventName) {
        if (ENABLE_DEBUG_LOGS) {
            Log.e(DEBUG_TAG, "!!!!!!!!! " + componentName + " " + eventName + " !!!!!!!!!");
            Log.e(componentName, "Current timestamp: " + System.currentTimeMillis());
        }
    }
    
    /**
     * 获取操作的平均耗时
     * @param operationName 操作名称
     * @return 平均耗时（毫秒）
     */
    private static double getAverageTime(String operationName) {
        PerformanceStats stats = statsMap.get(operationName);
        return stats != null ? stats.getAverageTime() : 0;
    }
    
    /**
     * 获取操作的调用次数
     * @param operationName 操作名称
     * @return 调用次数
     */
    private static int getCallCount(String operationName) {
        PerformanceStats stats = statsMap.get(operationName);
        return stats != null ? stats.getCallCount() : 0;
    }
    
    /**
     * 内部日志方法，统一处理日志输出
     * @param message 日志消息
     */
    private static void log(String message) {
        Log.e(PERF_TAG, message);
    }
    
    /**
     * 性能统计数据类
     */
    private static class PerformanceStats {
        private int callCount = 0;
        private long totalDuration = 0;
        private long minDuration = Long.MAX_VALUE;
        private long maxDuration = Long.MIN_VALUE;
        
        public synchronized void record(long duration) {
            callCount++;
            totalDuration += duration;
            if (duration < minDuration) minDuration = duration;
            if (duration > maxDuration) maxDuration = duration;
        }
        
        public synchronized double getAverageTime() {
            return callCount > 0 ? (double) totalDuration / callCount : 0;
        }
        
        public synchronized int getCallCount() {
            return callCount;
        }
        
        public synchronized long getMinDuration() {
            return minDuration == Long.MAX_VALUE ? 0 : minDuration;
        }
        
        public synchronized long getMaxDuration() {
            return maxDuration == Long.MIN_VALUE ? 0 : maxDuration;
        }
    }
}