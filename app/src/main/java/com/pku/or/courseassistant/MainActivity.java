package com.pku.or.courseassistant;

import android.os.Bundle;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.fragment.app.FragmentTransaction;
import com.google.android.material.bottomnavigation.BottomNavigationView;

public class MainActivity extends AppCompatActivity {
    private FragmentManager fragmentManager;
    private Fragment homeFragment, timeFragment, resultFragment;
    private Fragment activeFragment;

    // 为Fragment定义唯一的TAG
    private static final String TAG_HOME = "home";
    private static final String TAG_TIME = "time";
    private static final String TAG_RESULT = "result";
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        BottomNavigationView bottomNavigationView = findViewById(R.id.bottom_navigation);
        fragmentManager = getSupportFragmentManager();

        // 恢复或创建Fragments
        if (savedInstanceState == null) {
            homeFragment = new HomeFragment();
            timeFragment = new TimeFragment();
            resultFragment = new ResultFragment();

            fragmentManager.beginTransaction()
                    .add(R.id.fragment_container, resultFragment, TAG_RESULT).hide(resultFragment)
                    .add(R.id.fragment_container, timeFragment, TAG_TIME).hide(timeFragment)
                    .add(R.id.fragment_container, homeFragment, TAG_HOME)
                    .commit();
            activeFragment = homeFragment;
        } else {
            homeFragment = fragmentManager.findFragmentByTag(TAG_HOME);
            timeFragment = fragmentManager.findFragmentByTag(TAG_TIME);
            resultFragment = fragmentManager.findFragmentByTag(TAG_RESULT);
            // 找到当前活动的Fragment
            if (homeFragment != null && !homeFragment.isHidden()) activeFragment = homeFragment;
            else if (timeFragment != null && !timeFragment.isHidden()) activeFragment = timeFragment;
            else activeFragment = resultFragment;
        }


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

        // 确保底部导航的初始高亮与当前显示的 Fragment 一致
        if (activeFragment == homeFragment) {
            bottomNavigationView.setSelectedItemId(R.id.navigation_home);
        } else if (activeFragment == timeFragment) {
            bottomNavigationView.setSelectedItemId(R.id.navigation_time);
        } else {
            bottomNavigationView.setSelectedItemId(R.id.navigation_result);
        }
    }
    private void switchFragment(Fragment targetFragment) {
        if (targetFragment == activeFragment) {
            return; // 如果点击的是当前Fragment，则不执行任何操作
        }

        FragmentTransaction transaction = fragmentManager.beginTransaction();
        transaction.hide(activeFragment);
        transaction.show(targetFragment);
        transaction.commit();
        activeFragment = targetFragment;
    }
}