package com.pku.or.ucourse;

import android.os.Bundle;
import android.util.Log;
import androidx.appcompat.app.AppCompatActivity;

import com.pku.or.ucourse.utils.PerformanceLogger;
import com.pku.or.ucourse.home.HomeFragment;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import com.google.android.material.bottomnavigation.BottomNavigationView;

/**
 * 主Activity
 * 使用底部导航栏管理三个Fragment：
 * - HomeFragment：课程导入和管理
 * - TimeFragment：时间选择
 * - ResultFragment：结果展示
 */
public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private FragmentManager fragmentManager;
    private Fragment homeFragment, timeFragment, resultFragment;
    private Fragment activeFragment;  // 当前显示的Fragment

    // Fragment的唯一标识TAG
    private static final String TAG_HOME = "home";
    private static final String TAG_TIME = "time";
    private static final String TAG_RESULT = "result";
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 使用统一的性能日志工具
        PerformanceLogger.logLifecycleEvent("UCourse_MAIN", "CREATED");
        
        setContentView(R.layout.activity_main);

        androidx.appcompat.widget.Toolbar toolbar = findViewById(R.id.toolbar);
        setSupportActionBar(toolbar);

        BottomNavigationView bottomNavigationView = findViewById(R.id.bottom_navigation);
        fragmentManager = getSupportFragmentManager();

        // 恢复或创建Fragments
        if (savedInstanceState == null) {
            // 首次创建：使用根包中的HomeFragment
            homeFragment = new HomeFragment();
            timeFragment = new TimeFragment();
            resultFragment = new ResultFragment();

            // 添加所有Fragment到容器，初始只显示timeFragment
            fragmentManager.beginTransaction()
                    .add(R.id.fragment_container, resultFragment, TAG_RESULT).hide(resultFragment)
                    .add(R.id.fragment_container, homeFragment, TAG_HOME).hide(homeFragment)
                    .add(R.id.fragment_container, timeFragment, TAG_TIME)
                    .commit();
            activeFragment = timeFragment;
        } else {
            // 配置变更后恢复：通过TAG查找已存在的Fragment
            homeFragment = fragmentManager.findFragmentByTag(TAG_HOME);
            timeFragment = fragmentManager.findFragmentByTag(TAG_TIME);
            resultFragment = fragmentManager.findFragmentByTag(TAG_RESULT);
            
            // 找到当前活动的Fragment（通过isHidden()判断）
            if (homeFragment != null && !homeFragment.isHidden()) activeFragment = homeFragment;
            else if (timeFragment != null && !timeFragment.isHidden()) activeFragment = timeFragment;
            else activeFragment = resultFragment;
        }


        // 设置底部导航栏监听器
        bottomNavigationView.setOnItemSelectedListener(item -> {
            int itemId = item.getItemId();
            if (itemId == R.id.navigation_home) {
                switchFragment(homeFragment);
                return true;
            } else if (itemId == R.id.navigation_time) {
                switchFragment(timeFragment);
                return true;
            } else if (itemId == R.id.navigation_result) {
                switchFragment(resultFragment);
                return true;
            }
            return false;
        });

        // 确保底部导航的初始高亮与当前显示的Fragment一致
        if (activeFragment == homeFragment) {
            bottomNavigationView.setSelectedItemId(R.id.navigation_home);
            if (getSupportActionBar() != null) getSupportActionBar().setTitle("课程选择");
        } else if (activeFragment == timeFragment) {
            bottomNavigationView.setSelectedItemId(R.id.navigation_time);
            if (getSupportActionBar() != null) getSupportActionBar().setTitle("时间设置");
        } else {
            bottomNavigationView.setSelectedItemId(R.id.navigation_result);
            if (getSupportActionBar() != null) getSupportActionBar().setTitle("推荐方案");
        }
    }
    
    /**
     * 切换显示的Fragment
     * 使用hide/show而非replace以保留Fragment状态
     * 
     * @param targetFragment 目标Fragment
     */
    private void switchFragment(Fragment targetFragment) {
        if (targetFragment == activeFragment) {
            return; // 如果点击的是当前Fragment，则不执行任何操作
        }
        
        if (getSupportActionBar() != null) {
            if (targetFragment instanceof HomeFragment) {
                getSupportActionBar().setTitle("课程选择");
            } else if (targetFragment instanceof TimeFragment) {
                getSupportActionBar().setTitle("时间设置");
            } else if (targetFragment instanceof ResultFragment) {
                getSupportActionBar().setTitle("推荐方案");
            }
        }
        
        // 使用统一的性能日志工具记录Fragment切换
        PerformanceLogger.logPerformancePoint("FRAGMENT_SWITCH", "切换到Fragment: " + targetFragment.getClass().getSimpleName());

        FragmentTransaction transaction = fragmentManager.beginTransaction();
        transaction.hide(activeFragment);
        transaction.show(targetFragment);
        transaction.commit();
        activeFragment = targetFragment;
    }
}