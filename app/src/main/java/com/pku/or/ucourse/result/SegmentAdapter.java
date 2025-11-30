package com.pku.or.ucourse.result;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.cardview.widget.CardView;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.pku.or.ucourse.R;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class SegmentAdapter extends RecyclerView.Adapter<SegmentAdapter.SegmentViewHolder> {

    private List<SegmentData> segments = new ArrayList<>();
    private Context context;
    private Random random = new Random();
    private static final int[] GRADIENT_COLORS = {
            R.drawable.segment_gradient,
            R.drawable.segment_gradient_purple,
            R.drawable.segment_gradient_blue,
            R.drawable.segment_gradient_green,
            R.drawable.segment_gradient_orange
    };

    public SegmentAdapter(Context context) {
        this.context = context;
    }

    public void setSegments(List<SegmentData> segments) {
        this.segments = segments;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public SegmentViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_segment, parent, false);
        return new SegmentViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull SegmentViewHolder holder, int position) {
        SegmentData segment = segments.get(position);

        
        // 设置课程列表RecyclerView
        holder.rvCoursesInSegment.setLayoutManager(new LinearLayoutManager(context));
        CourseAdapter courseAdapter = new CourseAdapter();
        courseAdapter.addCourse(new CourseAdapter.CourseItem(segment.courseName, segment.courseId));
        holder.rvCoursesInSegment.setAdapter(courseAdapter);

        // 随机分配不同的渐变背景，增加视觉多样性
        int gradientRes = GRADIENT_COLORS[random.nextInt(GRADIENT_COLORS.length)];
        holder.cardView.setCardBackgroundColor(context.getResources().getColor(R.color.purple_700));
        holder.cardView.setForeground(context.getResources().getDrawable(gradientRes));
    }

    @Override
    public int getItemCount() {
        return segments.size();
    }

    static class SegmentViewHolder extends RecyclerView.ViewHolder {
        RecyclerView rvCoursesInSegment;
        CardView cardView;

        public SegmentViewHolder(@NonNull View itemView) {
            super(itemView);
            rvCoursesInSegment = itemView.findViewById(R.id.rv_courses_in_segment);
            cardView = (CardView) itemView;
        }
    }

    // 分段数据类
    public static class SegmentData {
        String time;
        String courseName;
        String courseId;

        public SegmentData(String time, String courseName, String courseId) {
            this.time = time;
            this.courseName = courseName;
            this.courseId = courseId;
        }
    }
    
    // 内部适配器用于显示课程列表
    private static class CourseAdapter extends RecyclerView.Adapter<CourseAdapter.CourseViewHolder> {
        private List<CourseItem> courses = new ArrayList<>();
        
        public void addCourse(CourseItem course) {
            this.courses.add(course);
        }
        
        @NonNull
        @Override
        public CourseViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_course_in_segment, parent, false);
            return new CourseViewHolder(view);
        }
        
        @Override
        public void onBindViewHolder(@NonNull CourseViewHolder holder, int position) {
            CourseItem course = courses.get(position);
            holder.tvCourseName.setText(course.name);
            holder.tvCourseId.setText(course.id);
        }
        
        @Override
        public int getItemCount() {
            return courses.size();
        }
        
        static class CourseViewHolder extends RecyclerView.ViewHolder {
            TextView tvCourseName;
            TextView tvCourseId;
            
            public CourseViewHolder(@NonNull View itemView) {
                super(itemView);
                tvCourseName = itemView.findViewById(R.id.tv_course_name);
                tvCourseId = itemView.findViewById(R.id.tv_course_id);
            }
        }
        
        static class CourseItem {
            String name;
            String id;
            
            public CourseItem(String name, String id) {
                this.name = name;
                this.id = id;
            }
        }
    }
}