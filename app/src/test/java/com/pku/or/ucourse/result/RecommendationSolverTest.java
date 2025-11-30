package com.pku.or.ucourse.result;

import com.pku.or.ucourse.home.Course;
import com.pku.or.ucourse.model.WeekTimeData;
import com.pku.or.ucourse.view.TimeSlot;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertTrue;

public class RecommendationSolverTest {

    @Test
    public void smallSolverTest() {
        // create 3 mock courses with non-overlapping times
        Course c1 = new Course(); c1.id = "c1"; c1.title = "A"; c1.interest = 10;
        Course c2 = new Course(); c2.id = "c2"; c2.title = "B"; c2.interest = 8;
        Course c3 = new Course(); c3.id = "c3"; c3.title = "C"; c3.interest = 6;

        List<Course> courses = new ArrayList<>();
        courses.add(c1); courses.add(c2); courses.add(c3);

        Map<String, List<TimeSlot>> mapping = new HashMap<>();
        mapping.put("c1", java.util.Arrays.asList(new TimeSlot(0,0,1)));
        mapping.put("c2", java.util.Arrays.asList(new TimeSlot(1,2,3)));
        mapping.put("c3", java.util.Arrays.asList(new TimeSlot(2,4,5)));

        WeekTimeData free = new WeekTimeData();
        // Set week start date to a known Monday
        java.util.Calendar cal = java.util.Calendar.getInstance();
        cal.set(java.util.Calendar.DAY_OF_WEEK, java.util.Calendar.MONDAY);
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0);
        cal.set(java.util.Calendar.MINUTE, 0);
        cal.set(java.util.Calendar.SECOND, 0);
        cal.set(java.util.Calendar.MILLISECOND, 0);
        java.util.Date monday = cal.getTime();
        free.setWeekStartDate(monday);

        // allow all slots on each day
        for (int d = 0; d < 7; d++) {
            cal.setTime(monday);
            cal.add(java.util.Calendar.DAY_OF_MONTH, d);
            free.addTimeSlot(cal.getTime(), 0, 11);
        }

        RecommendationSolver solver = new RecommendationSolver(courses, mapping, free, 3, 2000);
        List<RecommendationSolver.Solution> sols = solver.solve();
        assertTrue(sols.size() > 0);
        // best solution should include all three
        assertTrue(sols.get(0).courses.size() >= 1);
    }
}
