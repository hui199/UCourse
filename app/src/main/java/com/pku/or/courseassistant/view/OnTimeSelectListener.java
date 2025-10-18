package com.pku.or.courseassistant.view;

import java.util.List;

public interface OnTimeSelectListener {
    void onTimeSlotSelected(TimeSlot timeSlot);
    void onTimeSlotChanged(List<TimeSlot> timeSlots);
    void onTimeSlotCreated(TimeSlot timeSlot);
    void onTimeSlotRemoved(TimeSlot timeSlot);
}