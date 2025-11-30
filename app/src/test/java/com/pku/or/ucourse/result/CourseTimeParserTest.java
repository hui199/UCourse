package com.pku.or.ucourse.result;

import com.pku.or.ucourse.view.TimeSlot;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class CourseTimeParserTest {

    @Test
    public void parseSimpleRange() {
        List<TimeSlot> slots = CourseTimeParser.parse("周一7-9节");
        assertTrue(slots.size() >= 1);
        TimeSlot t = slots.get(0);
        assertEquals(0, t.getDay());
        assertEquals(6, t.getStartSection());
        assertEquals(8, t.getEndSection());
    }

    @Test
    public void parseListStyle() {
        List<TimeSlot> slots = CourseTimeParser.parse("周三7,8节");
        System.out.println("Parsed slots for '周三7,8节': " + slots);
        assertEquals("Should find exactly 2 slots", 2, slots.size());
        // ensure day index is 2 (周三)
        for (TimeSlot t : slots) assertEquals(2, t.getDay());
    }

    @Test
    public void parseWithParentheses() {
        List<TimeSlot> slots = CourseTimeParser.parse("(周一)5~6节");
        assertTrue(slots.size() >= 1);
        TimeSlot t = slots.get(0);
        assertEquals(0, t.getDay()); // Monday
        assertEquals(4, t.getStartSection()); // 5-1
        assertEquals(5, t.getEndSection()); // 6-1
    }

    @Test
    public void parseAmbiguousRangeShouldFail() {
        List<TimeSlot> slots = CourseTimeParser.parse("5~6节");
        assertTrue(slots.isEmpty());
    }
}
