package com.pku.or.courseassistant;

import androidx.appcompat.app.AppCompatActivity;

import android.os.Bundle;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import androidx.fragment.app.Fragment;

public class MainActivity extends AppCompatActivity {
    private BottomNavigationView bottomNav;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        bottomNav = findViewById(R.id.bottom_navigation);
        //default fragment
        if (savedInstanceState == null){
            getSupportFragmentManager().beginTransaction().replace(R.id.fragment_container, new HomeFragment()).commit();
        }
        //设置底部导航项的选中监听器
        bottomNav.setOnNavigationItemSelectedListener(item -> {
            Fragment selectedFragment = null;
            //select fragment according to the click action
            if (item.getItemId() == R.id.navigation_home){
                selectedFragment = new HomeFragment();
            }else if (item.getItemId() == R.id.navigation_time) {
                selectedFragment = new TimeFragment();
            }else if (item.getItemId() == R.id.navigation_result){
                selectedFragment = new ResultFragment();
            }
            if (selectedFragment != null){
                getSupportFragmentManager().beginTransaction().replace(R.id.fragment_container, selectedFragment).commit();
                return true;
            }
            return false;
        });
    }
}