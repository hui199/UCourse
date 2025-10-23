package com.pku.or.courseassistant.home;

import java.util.ArrayList;
import java.util.List;

public class Course {
    public String id;
    public String title;
    public String teachers;
    public String unit;
    public String rawTime;
    public int interest = 5; // default
    public String fileId;
    public String sheetName;
    public String groupKey; // e.g., 学院

    public Course() {}

    public static List<Course> fromRows(String fileId, String sheetName, List<String[]> rows, int titleIdx, int timeIdx, int teacherIdx, int unitIdx) {
        List<Course> out = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            String[] r = rows.get(i);
            if (r == null) continue;
            Course c = new Course();
            c.fileId = fileId;
            c.sheetName = sheetName;
            c.title = titleIdx >=0 && titleIdx < r.length ? r[titleIdx] : "";
            c.rawTime = timeIdx >=0 && timeIdx < r.length ? r[timeIdx] : "";
            c.teachers = teacherIdx >=0 && teacherIdx < r.length ? r[teacherIdx] : "";
            c.unit = unitIdx >=0 && unitIdx < r.length ? r[unitIdx] : "";
            c.groupKey = c.unit != null ? c.unit : "";
            c.id = Integer.toHexString((c.fileId + "|" + c.sheetName + "|" + c.title + "|" + c.rawTime).hashCode());
            out.add(c);
        }
        return out;
    }
}
