package com.pku.or.ucourse.home;

import java.util.ArrayList;
import java.util.List;

/**
 * Course.java - 课程数据模型
 * 存储从CSV/Excel导入的课程信息
 */
public class Course {
    public String id;          // 唯一ID：格式为"fileId|sheetName|rowIndex|timestamp|random"
    public String title;       // 课程名称
    public String teachers;    // 教师名单
    public String unit;        // 所属单位/学院
    public String rawTime;     // 上课时间（原始文本，多个时间用分号分隔）
    public List<String> timeSlots = new ArrayList<>(); // 分离的上课时间列表
    public int interest = 0;   // 兴趣分数（0-10分，默认0分）
    public String fileId;      // 来源文件ID
    public String sheetName;   // 来源Sheet名称
    public int sheetIndex = 0; // Sheet索引（用于排序显示）
    public String groupKey;    // 分组键（通常为学院名称）
    public String credits;     // 学分
    public String hours;       // 学时
    public String type;        // 课程类型
    public String location;    // 上课地点
    public String timeStr;     // 格式化的时间字符串

    // Transient fields for UI display (Diff/Frequency)
    public String diffStatus = "normal"; // "normal", "added", "removed"
    public int frequency = 0;  // Frequency of this course across all solutions


    public Course() {}

    /**
     * 从表格行数据批量创建Course对象
     * 
     * @param fileId     文件ID
     * @param sheetName  Sheet名称
     * @param sheetIndex Sheet索引（用于排序）
     * @param rows       数据行列表（不含表头）
     * @param titleIdx   标题列索引
     * @param timeIdx    时间列索引
     * @param teacherIdx 教师列索引
     * @param unitIdx    单位列索引
     * @param locationIdx 地点列索引
     * @return 课程列表
     */
    public static List<Course> fromRows(String fileId, String sheetName, int sheetIndex, List<String[]> rows, int titleIdx, int timeIdx, int teacherIdx, int unitIdx, int locationIdx) {
        List<Course> out = new ArrayList<>();
        Course lastCourse = null;
        for (int i = 0; i < rows.size(); i++) {
            String[] r = rows.get(i);
            if (r == null) continue;
            
            String title = titleIdx >=0 && titleIdx < r.length ? r[titleIdx] : "";
            String time = timeIdx >=0 && timeIdx < r.length ? r[timeIdx] : "";
            String teacher = teacherIdx >= 0 && teacherIdx < r.length ? r[teacherIdx] : "";
            String unit = unitIdx >=0 && unitIdx < r.length ? r[unitIdx] : "";
            String location = locationIdx >= 0 && locationIdx < r.length ? r[locationIdx] : "";
            
            // Check for merged row
            boolean isMergedRow = false;
            
            if (title == null || title.trim().isEmpty()) {
                // Task 1: If title is empty, check if it has course time or location
                // Relaxed condition: If it has time or location, assume it's a continuation row
                // regardless of other fields (often teacher/unit are empty or repeated in continuation rows)
                boolean hasContent = (time != null && !time.trim().isEmpty()) || (location != null && !location.trim().isEmpty());
                
                if (hasContent) {
                    if (lastCourse != null) {
                        isMergedRow = true;
                    } else {
                        // First row has no title but has time -> Discard per user request
                        continue;
                    }
                } else {
                    // No title, no time/location -> Discard (empty row)
                    continue;
                }
            } else {
                // Case 2: Same Title AND Same Teacher (merged cell or split time slot)
                if (lastCourse != null) {
                    boolean sameTitle = title.trim().equals(lastCourse.title != null ? lastCourse.title.trim() : "");
                    String t1 = teacher != null ? teacher.trim() : "";
                    String t2 = lastCourse.teachers == null ? "" : lastCourse.teachers.trim();
                    boolean sameTeacher = t1.equals(t2);
                    
                    if (sameTitle && sameTeacher) {
                        isMergedRow = true;
                    }
                }
            }

            if (isMergedRow) {
                // Merge with last course
                if (time != null && !time.trim().isEmpty()) {
                    // Avoid duplicate time strings
                    if (!lastCourse.rawTime.contains(time)) {
                        lastCourse.rawTime = lastCourse.rawTime + "; " + time;
                        lastCourse.timeSlots.add(time);
                    }
                }
                
                // Merge location if present
                if (location != null && !location.trim().isEmpty()) {
                    if (lastCourse.location == null || lastCourse.location.isEmpty()) {
                        lastCourse.location = location;
                    } else if (!lastCourse.location.contains(location)) {
                        lastCourse.location = lastCourse.location + "; " + location;
                    }
                }
                // Continue to next row without creating new course
                continue;
            }
            
            Course c = new Course();
            c.fileId = fileId;
            c.sheetName = sheetName;
            c.sheetIndex = sheetIndex;
            c.title = title;
            c.rawTime = time;
            if (c.rawTime != null && !c.rawTime.isEmpty()) {
                c.timeSlots.add(c.rawTime);
            }
            c.teachers = teacher;
            c.unit = unitIdx >=0 && unitIdx < r.length ? r[unitIdx] : "";
            c.location = locationIdx >= 0 && locationIdx < r.length ? r[locationIdx] : "";
            c.groupKey = c.unit != null ? c.unit : "";
            
            // 生成绝对唯一的ID（包含时间戳和随机数）
            // 格式：fileId|sheetName|rowIndex|timestamp|random
            // 行号从2开始，因为Excel中第一行是表头，第二行开始是数据
            long timestamp = System.currentTimeMillis();
            int random = (int) (Math.random() * Integer.MAX_VALUE);
            c.id = fileId + "|" + sheetName + "|" + (i + 2) + "|" + timestamp + "|" + Integer.toHexString(random);
            out.add(c);
            lastCourse = c;
        }
        return out;
    }
}
