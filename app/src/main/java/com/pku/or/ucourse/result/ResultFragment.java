package com.pku.or.ucourse.result;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

/**
 * 空实现，已被主ResultFragment替代
 */
public class ResultFragment extends Fragment {
    
    public ResultFragment() {}
    
    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        // 空实现
        return super.onCreateView(inflater, container, savedInstanceState);
    }
}