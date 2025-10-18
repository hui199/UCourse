package com.pku.or.courseassistant.view;

import android.os.Parcel;
import android.os.Parcelable;

public class TimeSlot implements Parcelable, Comparable<TimeSlot> {
    private int day; // 0-6 代表周一到周日
    private int startSection; // 起始节次 0-11
    private int endSection; // 结束节次 0-11
    private boolean isSelected;

    public TimeSlot(int day, int startSection, int endSection) {
        this.day = day;
        this.startSection = startSection;
        this.endSection = endSection;
        this.isSelected = true;
    }

    protected TimeSlot(Parcel in) {
        day = in.readInt();
        startSection = in.readInt();
        endSection = in.readInt();
        isSelected = in.readByte() != 0;
    }

    public static final Creator<TimeSlot> CREATOR = new Creator<TimeSlot>() {
        @Override
        public TimeSlot createFromParcel(Parcel in) {
            return new TimeSlot(in);
        }

        @Override
        public TimeSlot[] newArray(int size) {
            return new TimeSlot[size];
        }
    };

    public int getDay() { return day; }
    public void setDay(int day) { this.day = day; }

    public int getStartSection() { return startSection; }
    public void setStartSection(int startSection) { this.startSection = startSection; }

    public int getEndSection() { return endSection; }
    public void setEndSection(int endSection) { this.endSection = endSection; }

    public boolean isSelected() { return isSelected; }
    public void setSelected(boolean selected) { isSelected = selected; }

    public boolean contains(int section) {
        return section >= startSection && section <= endSection;
    }

    public boolean overlaps(TimeSlot other) {
        if (this.day != other.day) return false;
        return this.startSection <= other.endSection && other.startSection <= this.endSection;
    }

    public boolean isAdjacent(TimeSlot other) {
        if (this.day != other.day) return false;
        return this.endSection + 1 == other.startSection || other.endSection + 1 == this.startSection;
    }

    public void mergeWith(TimeSlot other) {
        this.startSection = Math.min(this.startSection, other.startSection);
        this.endSection = Math.max(this.endSection, other.endSection);
    }

    public int getSectionCount() {
        return endSection - startSection + 1;
    }

    @Override
    public int compareTo(TimeSlot other) {
        if (this.day != other.day) {
            return Integer.compare(this.day, other.day);
        }
        return Integer.compare(this.startSection, other.startSection);
    }

    @Override
    public int describeContents() { return 0; }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeInt(day);
        dest.writeInt(startSection);
        dest.writeInt(endSection);
        dest.writeByte((byte) (isSelected ? 1 : 0));
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) return true;
        if (obj == null || getClass() != obj.getClass()) return false;
        TimeSlot timeSlot = (TimeSlot) obj;
        return day == timeSlot.day &&
                startSection == timeSlot.startSection &&
                endSection == timeSlot.endSection;
    }

    @Override
    public int hashCode() {
        int result = day;
        result = 31 * result + startSection;
        result = 31 * result + endSection;
        return result;
    }

    @Override
    public String toString() {
        return "TimeSlot{" +
                "day=" + day +
                ", startSection=" + startSection +
                ", endSection=" + endSection +
                ", isSelected=" + isSelected +
                '}';
    }
}