package com.pku.or.ucourse.result;

import com.pku.or.ucourse.view.TimeSlot;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enhanced parser for Course.rawTime. Handles multiple segments, ranges and single sections,
 * tolerates common noise like week ranges (1-16周)、"每周"、地点等。
 * Examples supported:
 *  - "1-16周 每周一7-9节 二教301"
 *  - "周二3-4节/周四7节"
 *  - "周三7~8节、周五9-10节"
 *  - "周一7节"
 */
public class CourseTimeParser {

    // 支持的段：如 "周一7-9节" 或 "一7-9节" 或 "周1 7-9节"
    // require optional '周' prefix (use (?:周)? ), avoid empty alternation that allows stray digits to match as day
    // exclude range separators from the gap to avoid matching "5~6节" as Day=5, Gap=~, Start=6
    // also exclude list separators from gap to avoid matching "7,8节" as Day=7, Gap=,, Start=8
    private static final Pattern SEG_PAT = Pattern.compile("(?:周)?([一二三四五六日天1-7])[^\\d零一二三四五六七八九十0-9\\-~\\u2013\\u2014,，;；、]*?(\\d+)(?:[-~\\u2013\\u2014](\\d+))?节");

    // 额外：支持单独的节次列举，如 "周一7,8节" 或 "周一7、8节"
    // same fix for list-style pattern
    private static final Pattern LIST_PAT = Pattern.compile("(?:周)?([一二三四五六日天1-7])[^\\d零一二三四五六七八九十0-9\\-~\\u2013\\u2014]*?((?:\\d+[,、，;；\\s])*(?:\\d+))节");

    public static List<TimeSlot> parse(String raw) {
        Set<String> seen = new HashSet<>();
        List<TimeSlot> out = new ArrayList<>();
        if (raw == null) return out;

        String s = raw.trim();
        if (s.isEmpty()) return out;

        // Unwrap (周一) -> 周一 (MUST be done before removing parenthesized content)
        s = s.replaceAll("[（(](周[一二三四五六日天1-7])[)）]", "$1");

        // remove week ranges and "每周" and parenthesized location info to reduce noise
        s = s.replaceAll("\\d+[-~]\\d+周", "");
        s = s.replaceAll("每周", "");
        s = s.replaceAll("\"|\'", "");
        s = s.replaceAll("（[^）]*）", "");
        s = s.replaceAll("\\([^)]*\\)", "");

        // normalize common separators to comma for simpler splitting
        s = s.replaceAll("[；;\\uFF0C\\u3001]", ","); // chinese commas and semicolons
        s = s.replaceAll("\\s*/\\s*", ",");
        s = s.replaceAll("\\s+", " ");

        // First match explicit ranges like 周一7-9节 or 一7~9节
        Matcher m = SEG_PAT.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            boolean keep = false;
            String d = m.group(1);
            String a = m.group(2);
            String b = m.group(3);
            int day = toDayIndex(d);
            if (day >= 0) {
                try {
                    int start = Integer.parseInt(a) - 1;
                    int end = (b != null && !b.isEmpty()) ? Integer.parseInt(b) - 1 : start;
                    if (start >= 0 && end >= start) {
                        // clamp to reasonable section range (1..12 -> indexes 0..11)
                        if (start > 11) start = 11;
                        if (end > 11) end = 11;
                        String key = day + ":" + start + ":" + end;
                        if (seen.add(key)) out.add(new TimeSlot(day, start, end));
                        keep = true;
                    }
                } catch (NumberFormatException _e) { }
            }
            // If matched and parsed successfully, remove it from string to prevent LIST_PAT from matching parts of it
            // If not valid (e.g. invalid day), we still remove it if it matched the pattern structure, 
            // but here the pattern is specific enough that we should probably remove it to be safe.
            // Actually, replace with space to avoid concatenating surrounding text
            m.appendReplacement(sb, " ");
        }
        m.appendTail(sb);
        s = sb.toString();

        // Then match list style like 周一7,8节
        Matcher ml = LIST_PAT.matcher(s);
        while (ml.find()) {
            String d = ml.group(1);
            String list = ml.group(2);
            int day = toDayIndex(d);
            if (day < 0) continue;
            String[] parts = list.split("[,、，;；\\s]+");
            for (String p : parts) {
                if (p == null || p.trim().isEmpty()) continue;
                try {
                    int sec = Integer.parseInt(p.trim()) - 1;
                    if (sec < 0) continue;
                    if (sec > 11) sec = 11;
                    String key = day + ":" + sec + ":" + sec;
                    if (seen.add(key)) out.add(new TimeSlot(day, sec, sec));
                } catch (NumberFormatException _e) { }
            }
        }

        // Fallback: also try a weaker pattern for any "7-9节" with preceding day word within nearby chars
        // (handled by SEG_PAT in most cases) - no extra fallback for now.

        return out;
    }

    private static int toDayIndex(String d) {
        if (d == null) return -1;
        d = d.trim();
        switch (d) {
            case "一": case "1": return 0;
            case "二": case "2": return 1;
            case "三": case "3": return 2;
            case "四": case "4": return 3;
            case "五": case "5": return 4;
            case "六": case "6": return 5;
            case "日": case "天": case "7": return 6;
            default:
                // sometimes input includes full-width digits or chinese numerals - normalize
                if (d.length() == 1) {
                    char c = d.charAt(0);
                    if (c >= '1' && c <= '7') return (c - '1');
                }
        }
        return -1;
    }
}
