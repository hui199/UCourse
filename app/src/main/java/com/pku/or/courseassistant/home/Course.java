package com.pku.or.courseassistant.home;

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
    public String rawTime;     // 上课时间（原始文本）
    public int interest = 5;   // 兴趣分数（0-10分，默认5分）
    public String fileId;      // 来源文件ID
    public String sheetName;   // 来源Sheet名称
    public int sheetIndex = 0; // Sheet索引（用于排序显示）
    public String groupKey;    // 分组键（通常为学院名称）

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
     * @return 课程列表
     */
    public static List<Course> fromRows(String fileId, String sheetName, int sheetIndex, List<String[]> rows, int titleIdx, int timeIdx, int teacherIdx, int unitIdx) {
        List<Course> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            String[] r = rows.get(i);
            if (r == null) continue;
            Course c = new Course();
            c.fileId = fileId;
            c.sheetName = sheetName;
            c.sheetIndex = sheetIndex;
            c.title = titleIdx >=0 && titleIdx < r.length ? r[titleIdx] : "";
            c.rawTime = timeIdx >=0 && timeIdx < r.length ? r[timeIdx] : "";
            c.teachers = teacherIdx >=0 && teacherIdx < r.length ? r[teacherIdx] : "";
            c.unit = unitIdx >=0 && unitIdx < r.length ? r[unitIdx] : "";
            c.groupKey = c.unit != null ? c.unit : "";
            
            // 生成绝对唯一的ID（包含时间戳和随机数）
            // 格式：fileId|sheetName|rowIndex|timestamp|random
            long timestamp = System.currentTimeMillis();
            int random = (int) (Math.random() * Integer.MAX_VALUE);
            c.id = fileId + "|" + sheetName + "|" + i + "|" + timestamp + "|" + Integer.toHexString(random);
            out.add(c);
        }
        return out;
    }
}
