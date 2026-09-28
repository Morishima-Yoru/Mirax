package com.secondscreen.receiver;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.HashMap;

/**
 * Sidebar holds status and actions until a picture arrives. During projection the side is closed
 * and the stream fills the panel.
 */
public class SetupActivity extends Activity implements VideoStage.Callback {
    private static final int INK = 0xFF12141A;
    private static final int SIDE = 0xFF181B22;
    private static final int PAPER = 0xFFF3F0E8;
    private static final int MUTED = 0xFFA39E94;
    private static final int LINE = 0xFF2E333D;
    private static final int OK = 0xFF8FCB9B;
    private static final int WARN = 0xFFE3B15A;
    private static final int BAD = 0xFFE08B78;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private TextView headline;
    private TextView detail;
    private TextView action;
    private TextView helperValue;
    private TextView wifiValue;
    private TextView wfdValue;
    private TextView nameValue;
    private TextView resolutionValue;
    private TextView fallbackValue;
    private TextView linkValue;
    private TextView command;
    private View endRow;
    private View sidePanel;
    private FrameLayout stage;
    private SurfaceView surfaceView;
    private boolean shellUp;
    private boolean discoverable;
    private boolean connected;
    private boolean frameSeen;
    private int videoW;
    private int videoH;

    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            refresh();
            handler.postDelayed(this, 900);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(build());
        ShellBridge.start(this);
        VideoStage.start();
        VideoStage.attach(surfaceView, this);
    }

    @Override
    public void onBackPressed() {
        if (connected || discoverable) {
            if (!Settings.canDrawOverlays(this)) {
                detail.setText("返回會把投影關掉。請允許「顯示在其他應用程式上層」，之後返回會留下懸浮球。");
                Intent intent = new Intent(
                        Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName()));
                startActivity(intent);
                return;
            }
            FloatingBallService.show(this);
            moveTaskToBack(true);
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onResume() {
        super.onResume();
        FloatingBallService.hide(this);
        handler.post(poll);
    }

    @Override
    protected void onPause() {
        handler.removeCallbacks(poll);
        super.onPause();
    }

    private View build() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setBackgroundColor(INK);

        sidePanel = sidebar();
        root.addView(sidePanel);
        root.addView(mainPane(), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
        stage.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom,
                    int oldLeft, int oldTop, int oldRight, int oldBottom) {
                if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) {
                    applyFit();
                }
            }
        });
        return root;
    }

    private View sidebar() {
        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        side.setBackgroundColor(SIDE);
        side.setPadding(dp(22), dp(28), dp(22), dp(22));
        side.setLayoutParams(new LinearLayout.LayoutParams(dp(300), LinearLayout.LayoutParams.MATCH_PARENT));

        TextView brand = new TextView(this);
        brand.setText("第二螢幕");
        brand.setTextColor(PAPER);
        brand.setTextSize(26);
        brand.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        side.addView(brand);
        side.addView(gap(6));
        TextView brandNote = muted("狀態與操作都在這裡");
        side.addView(brandNote);
        side.addView(gap(22));

        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.addView(row("Win + K", linkValue = paper("—")));
        list.addView(row("ADB 助手", helperValue = paper("確認中")));
        list.addView(row("Wi-Fi", wifiValue = paper("—")));
        list.addView(row("顯示權限", wfdValue = paper("尚未要求")));
        list.addView(row("裝置名稱", nameValue = paper("—")));
        list.addView(row("目前畫面", resolutionValue = paper("尚未連線")));
        list.addView(row("提供的畫面", fallbackValue = paper("2176×1812")));
        endRow = row("結束這次連線", muted("電腦會斷開，Win + K 仍找得到"));
        endRow.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (connected) {
                    ShellBridge.endSession();
                }
            }
        });
        endRow.setClickable(false);
        endRow.setAlpha(0.4f);
        list.addView(endRow);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(list);
        scroll.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        side.addView(scroll);

        command = new TextView(this);
        command.setTextColor(MUTED);
        command.setTextSize(11);
        command.setTypeface(Typeface.MONOSPACE);
        command.setTextIsSelectable(true);
        command.setVisibility(View.GONE);
        side.addView(gap(12));
        side.addView(command);
        return side;
    }

    private View mainPane() {
        FrameLayout pane = new FrameLayout(this);
        pane.setBackgroundColor(Color.BLACK);

        stage = new FrameLayout(this);
        stage.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));
        surfaceView = new SurfaceView(this);
        surfaceView.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER));
        stage.addView(surfaceView);
        pane.addView(stage);

        LinearLayout copy = new LinearLayout(this);
        copy.setOrientation(LinearLayout.VERTICAL);
        copy.setGravity(Gravity.CENTER);
        int pad = dp(36);
        copy.setPadding(pad, pad, pad, pad);
        copy.setBackgroundColor(0xF012141A);
        copy.setLayoutParams(new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT));

        headline = new TextView(this);
        headline.setText("Win + K 找不到這台手機");
        headline.setTextColor(PAPER);
        headline.setTextSize(32);
        headline.setGravity(Gravity.CENTER);
        headline.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        copy.addView(headline);

        detail = new TextView(this);
        detail.setText("手機沒有在對電腦廣播。打開之後，Win + K 才列得出來。");
        detail.setTextColor(MUTED);
        detail.setTextSize(16);
        detail.setGravity(Gravity.CENTER);
        detail.setPadding(0, dp(14), 0, dp(28));
        copy.addView(detail);

        action = new TextView(this);
        action.setText("讓 Win + K 找得到");
        action.setTextColor(0xFF12141A);
        action.setTextSize(18);
        action.setGravity(Gravity.CENTER);
        action.setPadding(dp(28), dp(16), dp(28), dp(16));
        GradientDrawable button = new GradientDrawable();
        button.setColor(PAPER);
        button.setCornerRadius(dp(28));
        action.setBackground(button);
        action.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!shellUp) {
                    return;
                }
                ShellBridge.setBroadcast(!discoverable);
            }
        });
        copy.addView(action, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(56)));
        pane.addView(copy);
        pane.setTag(copy);
        return pane;
    }

    private void refresh() {
        String line = ShellBridge.status();
        if (line.length() == 0) {
            renderOffline();
            return;
        }
        render(parse(line));
    }

    private void renderOffline() {
        shellUp = false;
        discoverable = false;
        connected = false;
        helperValue.setText("尚未連上");
        helperValue.setTextColor(BAD);
        wifiValue.setText("等助手回報");
        wifiValue.setTextColor(MUTED);
        wfdValue.setText("無法確認");
        wfdValue.setTextColor(MUTED);
        nameValue.setText("—");
        linkValue.setText("Win + K 找不到");
        linkValue.setTextColor(BAD);
        resolutionValue.setText("尚未連線");
        showCopy(true);
        applyProjection(false);
        headline.setText("還不能被電腦找到");
        detail.setText("ADB 的 shell 助手沒在跑。啟動之後，這裡才能打開 Win + K 的清單。");
        action.setText("助手未連上");
        action.setAlpha(0.45f);
        command.setVisibility(View.VISIBLE);
        command.setText("adb shell \"CLASSPATH=/data/local/tmp/miracast.jar app_process /system/bin com.secondscreen.receiver.MiracastReceiver\"");
        setEndEnabled(false);
    }

    private void render(HashMap<String, String> status) {
        shellUp = true;
        command.setVisibility(View.GONE);
        boolean uidOk = "2000".equals(status.get("uid"));
        helperValue.setText(uidOk ? "已連上 · uid 2000" : "uid " + status.get("uid"));
        helperValue.setTextColor(uidOk ? OK : WARN);

        boolean wifi = "1".equals(status.get("wifi"));
        wifiValue.setText(wifi ? "已開啟" : "未開啟");
        wifiValue.setTextColor(wifi ? OK : WARN);

        String wfd = status.get("wfd");
        if ("ok".equals(wfd)) {
            wfdValue.setText("系統已允許");
            wfdValue.setTextColor(OK);
        } else if ("denied".equals(wfd)) {
            wfdValue.setText("被拒絕");
            wfdValue.setTextColor(BAD);
        } else {
            wfdValue.setText("讓電腦找得到之後才要求");
            wfdValue.setTextColor(MUTED);
        }

        String name = status.get("name");
        nameValue.setText(name == null ? "—" : name.replace('_', ' '));

        discoverable = "1".equals(status.get("broadcast"));
        String link = status.get("link");
        connected = "connected".equals(link);
        String video = status.get("video");
        boolean hasPicture = connected && video != null && !"none".equals(video);
        resolutionValue.setText(hasPicture ? video : "尚未連線");
        resolutionValue.setTextColor(hasPicture ? OK : PAPER);

        action.setAlpha(uidOk ? 1f : 0.45f);
        if (!connected) {
            frameSeen = false;
        }
        boolean picture = hasPicture || frameSeen;
        showCopy(!picture);
        applyProjection(connected);
        setEndEnabled(connected);

        if (!uidOk) {
            linkValue.setText("權限不夠");
            linkValue.setTextColor(BAD);
            headline.setText("ADB 助手沒有 shell 權限");
            detail.setText("Win + K 需要 uid 2000 的助手才能把這台手機放進清單。");
            action.setText("無法開啟");
            return;
        }
        if (!discoverable) {
            linkValue.setText("找不到");
            linkValue.setTextColor(BAD);
            headline.setText("Win + K 找不到這台手機");
            detail.setText("手機現在沒有對電腦廣播。按下面的按鈕之後，清單裡才會出現。");
            action.setText("讓 Win + K 找得到");
            return;
        }
        if (connected) {
            linkValue.setText("已連線");
            linkValue.setTextColor(OK);
            headline.setText(hasPicture ? video : "電腦正在連接");
            detail.setText("這是 Windows 選到的解析度。比例不同時會留黑邊，不會被拉寬。");
            action.setText("不要再被 Win + K 找到");
            return;
        }
        linkValue.setText("找得到");
        linkValue.setTextColor(OK);
        String shown = name == null ? "這台手機" : name.replace('_', ' ');
        headline.setText("Win + K 可以找到");
        detail.setText("在電腦按 Win + K，點「" + shown + "」。畫面是 2176×1812。若這次連不上，下一輪會加上 1080p 與 720p。");
        action.setText("不要再被 Win + K 找到");
    }

    private void applyProjection(boolean on) {
        sidePanel.setVisibility(on ? View.GONE : View.VISIBLE);
        if (on) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        } else {
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
        WindowInsetsController controller = getWindow().getInsetsController();
        if (controller == null) {
            return;
        }
        int bars = WindowInsets.Type.statusBars() | WindowInsets.Type.navigationBars();
        if (on) {
            getWindow().setDecorFitsSystemWindows(false);
            controller.hide(bars);
            controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        } else {
            getWindow().setDecorFitsSystemWindows(true);
            controller.show(bars);
        }
    }

    private void showCopy(boolean show) {
        View copy = (View) ((FrameLayout) stage.getParent()).getChildAt(1);
        copy.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void setEndEnabled(boolean enabled) {
        endRow.setClickable(enabled);
        endRow.setAlpha(enabled ? 1f : 0.4f);
    }

    private void fitSurface() {
        if (videoW <= 0 || videoH <= 0 || stage.getWidth() <= 0) {
            stage.post(new Runnable() {
                @Override
                public void run() {
                    if (stage.getWidth() > 0 && videoW > 0) {
                        applyFit();
                    }
                }
            });
            return;
        }
        applyFit();
    }

    private void applyFit() {
        float scale = Math.min(stage.getWidth() / (float) videoW, stage.getHeight() / (float) videoH);
        int width = Math.max(2, Math.round(videoW * scale));
        int height = Math.max(2, Math.round(videoH * scale));
        surfaceView.setLayoutParams(new FrameLayout.LayoutParams(width, height, Gravity.CENTER));
        surfaceView.getHolder().setFixedSize(videoW, videoH);
    }

    @Override
    public void onFormat(final int width, final int height, final int fps) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                videoW = width;
                videoH = height;
                fitSurface();
            }
        });
    }

    @Override
    public void onPicture() {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                frameSeen = true;
                showCopy(false);
                applyProjection(true);
            }
        });
    }

    @Override
    public void onIdle(final String message) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                frameSeen = false;
                detail.setText(message);
                showCopy(true);
                applyProjection(false);
            }
        });
    }

    private static HashMap<String, String> parse(String line) {
        HashMap<String, String> map = new HashMap<String, String>();
        String[] parts = line.trim().split(" ");
        for (int i = 0; i < parts.length; i++) {
            int eq = parts[i].indexOf('=');
            if (eq > 0) {
                map.put(parts[i].substring(0, eq), parts[i].substring(eq + 1));
            }
        }
        return map;
    }

    private View row(String label, TextView value) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(12), 0, dp(12));
        TextView name = new TextView(this);
        name.setText(label);
        name.setTextColor(MUTED);
        name.setTextSize(12);
        name.setAllCaps(true);
        name.setLetterSpacing(0.06f);
        row.addView(name);
        value.setPadding(0, dp(4), 0, 0);
        row.addView(value);
        View rule = new View(this);
        rule.setBackgroundColor(LINE);
        LinearLayout.LayoutParams ruleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        ruleParams.topMargin = dp(12);
        row.addView(rule, ruleParams);
        return row;
    }

    private TextView paper(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(PAPER);
        view.setTextSize(16);
        return view;
    }

    private TextView muted(String text) {
        TextView view = paper(text);
        view.setTextColor(MUTED);
        view.setTextSize(13);
        return view;
    }

    private View gap(int size) {
        View view = new View(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(1, dp(size)));
        return view;
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
