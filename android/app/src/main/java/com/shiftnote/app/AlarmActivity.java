package com.shiftnote.app;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** 잠금 화면 위로 뜨는 전체 화면 알람. [끄기] 또는 [5분 뒤 다시 알림]을 눌러야 멈춤 */
public class AlarmActivity extends Activity {

    private String group = "";
    private final BroadcastReceiver stoppedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) { finish(); }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                | WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        Intent in = getIntent();
        group = in.getStringExtra(AlarmRingService.EXTRA_GROUP);
        String title = in.getStringExtra(AlarmRingService.EXTRA_TITLE);
        String body = in.getStringExtra(AlarmRingService.EXTRA_BODY);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER);
        root.setBackgroundColor(Color.parseColor("#0E1116"));
        int pad = dp(28);
        root.setPadding(pad, pad, pad, pad);

        TextView time = new TextView(this);
        time.setText(new SimpleDateFormat("HH:mm", Locale.KOREA).format(new Date()));
        time.setTextColor(Color.WHITE);
        time.setTextSize(72);
        time.setTypeface(Typeface.DEFAULT_BOLD);
        time.setGravity(Gravity.CENTER);
        root.addView(time);

        TextView t1 = new TextView(this);
        t1.setText(title != null ? title : "⏰ 교대노트 알람");
        t1.setTextColor(Color.parseColor("#8992A6"));
        t1.setTextSize(18);
        t1.setGravity(Gravity.CENTER);
        t1.setPadding(0, dp(8), 0, 0);
        root.addView(t1);

        TextView t2 = new TextView(this);
        t2.setText(body != null ? body : "");
        t2.setTextColor(Color.WHITE);
        t2.setTextSize(24);
        t2.setTypeface(Typeface.DEFAULT_BOLD);
        t2.setGravity(Gravity.CENTER);
        t2.setPadding(0, dp(6), 0, dp(48));
        root.addView(t2);

        Button dismiss = makeButton("끄기", "#37D6B0", "#0E1116");
        dismiss.setOnClickListener(v -> {
            startService(new Intent(this, AlarmRingService.class)
                .setAction(AlarmRingService.ACTION_DISMISS).putExtra(AlarmRingService.EXTRA_GROUP, group));
            finish();
        });
        root.addView(dismiss);

        Button snooze = makeButton("5분 뒤 다시 알림", "#2A3140", "#E7EAF0");
        snooze.setOnClickListener(v -> {
            startService(new Intent(this, AlarmRingService.class)
                .setAction(AlarmRingService.ACTION_SNOOZE).putExtra(AlarmRingService.EXTRA_GROUP, group));
            finish();
        });
        LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams) snooze.getLayoutParams();
        lp.topMargin = dp(14);
        root.addView(snooze, lp);

        setContentView(root);

        IntentFilter f = new IntentFilter(AlarmRingService.ACTION_STOPPED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(stoppedReceiver, f, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(stoppedReceiver, f);
        }
        // 이미 꺼진 뒤 화면만 뒤늦게 열린 경우
        if (!AlarmRingService.ringing) finish();
    }

    private Button makeButton(String text, String bg, String fg) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(20);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(Color.parseColor(fg));
        GradientDrawable d = new GradientDrawable();
        d.setColor(Color.parseColor(bg));
        d.setCornerRadius(dp(16));
        b.setBackground(d);
        b.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64)));
        return b;
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override
    public void onBackPressed() {
        // 뒤로 가기로는 닫히지 않음 (끄기 / 5분 뒤 중 하나를 눌러야 함)
    }

    @Override
    protected void onDestroy() {
        try { unregisterReceiver(stoppedReceiver); } catch (Exception ignored) {}
        super.onDestroy();
    }
}
