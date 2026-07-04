package com.zm920.androidserver;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import androidx.core.content.ContextCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

public class SplashActivity extends AppCompatActivity {

    private static final int ANIM_DURATION = 700;
    private static final int DELAY_BEFORE_TEXT = 300;
    private static final int TOTAL_DELAY = 900;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 状态栏颜色与启动页背景一致
        getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.bg_light));
        WindowInsetsControllerCompat wic = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        if (wic != null) wic.setAppearanceLightStatusBars(true);
        setContentView(R.layout.activity_splash);

        ImageView logo = findViewById(R.id.iv_splash_logo);
        TextView title = findViewById(R.id.tv_splash_title);

        // 用 ViewPropertyAnimator 实现淡入 + 缩放，比 Animation/AnimationSet 更可靠
        logo.setAlpha(0f);
        logo.setScaleX(0.5f);
        logo.setScaleY(0.5f);
        logo.animate()
                .alpha(1f)
                .scaleX(1f)
                .scaleY(1f)
                .setDuration(ANIM_DURATION)
                .start();

        title.setAlpha(0f);
        title.animate()
                .alpha(1f)
                .setDuration(ANIM_DURATION)
                .setStartDelay(DELAY_BEFORE_TEXT)
                .start();

        // 动画结束后跳转到主界面
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            startActivity(new Intent(SplashActivity.this, MainActivity.class));
            overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out);
            finish();
        }, TOTAL_DELAY);
    }
}
