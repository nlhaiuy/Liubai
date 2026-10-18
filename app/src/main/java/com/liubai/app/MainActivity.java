package com.liubai.app;

import android.animation.ObjectAnimator;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 留白主页。
 * 顶部固定：标题 + 演示屏（下面是宽度微调），拖动设置时始终看得到遮罩的变化；
 * 下面单独滚动：授权提示、生效范围 / 视频比例 / 遮盖强度；底部一个大开关。
 * 横屏时左边是演示屏，右边是设置。所有权限都由你手动点“开启”，不会自动跳转。
 */
public class MainActivity extends Activity {
    private static final float[] RATIO_VALUES = {16f / 9f, 4f / 3f};
    private static final int[] STRENGTH_LAYERS = {1, 3};

    private SharedPreferences sp;
    private float density;
    private Vibrator vib;
    private boolean land;

    private View dot;
    private LinearLayout permBox;
    private TextView statusTv, appsLink, resetBtn;
    private PreviewView preview;
    private LinearLayout settingsCard;
    private LinearLayout.LayoutParams settingsLp;   // 设置卡的上边距：授权卡显示时 12dp，收起时补回 16dp
    private PowerButton power;
    private Slider slider;
    private final Handler repeat = new Handler(Looper.getMainLooper());

    // 权限：0 悬浮窗、1 通知、2 后台运行、3 无障碍
    private static final String[] PERM_NAMES = {"悬浮窗", "通知", "后台运行", "无障碍"};
    private boolean awaitingA11y = false;

    private int dp(float v) { return Math.round(v * density); }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        sp = getSharedPreferences(MaskService.PREFS, MODE_PRIVATE);
        density = getResources().getDisplayMetrics().density;
        vib = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        land = getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE;
        Ui.lightWindow(this);

        final int screenW = getResources().getDisplayMetrics().widthPixels;
        final int rightW = land ? Math.round(screenW * 0.54f) : screenW;

        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(Ui.BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(land ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        page.addView(root, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- 固定在顶部（横屏时在左边）：标题 + 演示屏 + 微调 ----
        LinearLayout top = new LinearLayout(this);
        top.setOrientation(LinearLayout.VERTICAL);
        if (land) top.setPadding(dp(20), dp(14), dp(8), dp(14));
        else top.setPadding(dp(20), dp(24), dp(20), dp(8));
        buildHeader(top);
        buildStage(top);

        if (land) {
            ScrollView left = new ScrollView(this);
            left.setVerticalScrollBarEnabled(false);
            left.setFillViewport(true);
            left.addView(top, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams llp = lp(0, ViewGroup.LayoutParams.MATCH_PARENT);
            llp.weight = 0.46f;
            root.addView(left, llp);
        } else {
            root.addView(top, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        // ---- 下面（横屏时在右边）单独滚动：授权 + 设置 ----
        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.setVerticalScrollBarEnabled(false);
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        if (land) col.setPadding(dp(8), dp(14), dp(20), dp(84));
        else col.setPadding(dp(20), 0, dp(20), dp(100));
        sv.addView(col, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        FrameLayout svWrap = new FrameLayout(this);
        svWrap.addView(sv, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        if (!land) {
            // 内容滚到演示卡下面时，边缘淡出，不要硬切
            View topFade = new View(this);
            topFade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                    new int[]{Ui.BG, 0x00F3F6FC}));
            svWrap.addView(topFade, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(14), Gravity.TOP));
        }
        LinearLayout.LayoutParams slp = lp(land ? 0 : ViewGroup.LayoutParams.MATCH_PARENT,
                land ? ViewGroup.LayoutParams.MATCH_PARENT : 0);
        slp.weight = land ? 0.54f : 1f;
        root.addView(svWrap, slp);

        buildPerms(col);

        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(dp(18), dp(4), dp(18), dp(4));
        Ui.card(card, 24);
        LinearLayout.LayoutParams clp = lp(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        clp.topMargin = dp(10);
        settingsLp = clp;
        settingsCard = card;
        col.addView(card, clp);

        buildModeRow(card);
        buildRatioRow(card);
        buildStrengthRow(card);

        // 底部渐隐，让大开关浮在内容上面
        View fade = new View(this);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00F3F6FC, Ui.BG}));
        page.addView(fade, new FrameLayout.LayoutParams(rightW, dp(120),
                land ? (Gravity.BOTTOM | Gravity.END) : Gravity.BOTTOM));

        // 总开关
        power = new PowerButton(this);
        power.setOnClickListener(v -> toggle());
        FrameLayout.LayoutParams pwl;
        if (land) {
            pwl = new FrameLayout.LayoutParams(rightW - dp(28), dp(54), Gravity.BOTTOM | Gravity.END);
            pwl.setMargins(0, 0, dp(20), dp(14));
        } else {
            pwl = new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(62), Gravity.BOTTOM);
            pwl.setMargins(dp(20), 0, dp(20), dp(22));
        }
        page.addView(power, pwl);

        setContentView(page);
        Ui.noClip(page);
        updateUi(false);
        refreshAuto();
    }

    // ---------------- 页面结构 ----------------

    private void buildHeader(LinearLayout parent) {
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);

        TextView title = new TextView(this);
        title.setText("留白");
        title.setTextSize(land ? 28 : 34);
        title.setTextColor(Ui.TITLE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(0.04f);
        title.setIncludeFontPadding(false);
        LinearLayout.LayoutParams tlp = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.weight = 1f;
        head.addView(title, tlp);

        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(11), dp(7), dp(13), dp(7));
        chip.setBackground(Ui.round(Ui.CARD, dp(20)));
        Ui.lift(chip, 3, 0x264F7CFF, 0x404F7CFF);
        dot = new View(this);
        chip.addView(dot, lp(dp(10), dp(10)));
        statusTv = new TextView(this);
        statusTv.setTextSize(13);
        statusTv.setPadding(dp(7), 0, 0, 0);
        chip.addView(statusTv);
        head.addView(chip, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        parent.addView(head, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /** 演示卡：屏幕在上，下面一行 − 滑条 +；屏幕右上角在微调不为 0 时出现“归零”。 */
    private void buildStage(LinearLayout parent) {
        LinearLayout stage = new LinearLayout(this);
        stage.setOrientation(LinearLayout.VERTICAL);
        stage.setPadding(dp(12), dp(12), dp(12), dp(12));
        Ui.card(stage, 28);

        FrameLayout shot = new FrameLayout(this);
        preview = new PreviewView(this);
        FrameLayout.LayoutParams plp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        // 预览控件四周留了画阴影的空间，用负边距抵消，屏幕本身仍和卡片内边距对齐
        plp.setMargins(-dp(PreviewView.PAD_X), -dp(PreviewView.PAD_T),
                -dp(PreviewView.PAD_X), -dp(PreviewView.PAD_B - 10));
        shot.addView(preview, plp);

        resetBtn = new TextView(this);
        resetBtn.setText("↺");
        resetBtn.setTextSize(19);
        resetBtn.setTextColor(0xFFFFFFFF);
        resetBtn.setTypeface(Typeface.DEFAULT_BOLD);
        resetBtn.setGravity(Gravity.CENTER);
        resetBtn.setContentDescription("归零");
        // 蓝色实心圆：在白边、黑边上都看得清；视觉 36dp，外面再留 4dp，点击范围 44dp
        GradientDrawable rb = Ui.gradient(dp(18));
        rb.setShape(GradientDrawable.OVAL);
        resetBtn.setBackground(new android.graphics.drawable.InsetDrawable(rb, dp(4)));
        Ui.lift(resetBtn, 4, 0x554F7CFF, 0x884F7CFF);
        resetBtn.setAlpha(0f);
        resetBtn.setClickable(false);
        resetBtn.setOnClickListener(v -> setOffset(0));
        Ui.press(resetBtn, 0.9f);
        FrameLayout.LayoutParams rlp = new FrameLayout.LayoutParams(dp(44), dp(44),
                Gravity.TOP | Gravity.END);
        rlp.setMargins(0, dp(4), dp(4), 0);
        shot.addView(resetBtn, rlp);
        stage.addView(shot, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout fine = new LinearLayout(this);
        fine.setOrientation(LinearLayout.HORIZONTAL);
        fine.setGravity(Gravity.CENTER_VERTICAL);
        TextView minus = stepButton("−", "减小", -1);
        TextView plus = stepButton("+", "增大", 1);

        slider = new Slider(this);
        slider.setContentDescription("宽度微调");
        applyRange();
        int off = Math.max(slider.getMin(), Math.min(Slider.MAX, sp.getInt(MaskService.KEY_OFFSET, 0)));
        slider.setValue(off);
        showOffset(off, false);
        slider.setOnChange((v, user) -> {
            sp.edit().putInt(MaskService.KEY_OFFSET, v).apply();
            showOffset(v, true);
            if (user) tick(v);
            changed();
        });

        fine.addView(minus, lp(dp(44), dp(44)));
        LinearLayout.LayoutParams slp = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        slp.weight = 1f;
        slp.setMargins(dp(2), 0, dp(2), 0);
        fine.addView(slider, slp);
        fine.addView(plus, lp(dp(44), dp(44)));
        stage.addView(fine, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout.LayoutParams sgp = lp(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sgp.topMargin = dp(land ? 12 : 18);
        parent.addView(stage, sgp);
    }

    /** −/+：点一下 ±1dp，按住不放则先慢后快地连续调。 */
    private TextView stepButton(String text, String desc, int delta) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(22);
        t.setTextColor(Ui.INK);
        t.setGravity(Gravity.CENTER);
        t.setContentDescription(desc);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(Ui.CHIP);
        t.setBackground(g);
        t.setClickable(true);
        // 读屏 / 键盘触发的点击
        t.setOnClickListener(v -> setOffset(slider.getValue() + delta));

        final int[] n = {0};
        final Runnable[] loop = new Runnable[1];
        loop[0] = () -> {
            setOffset(slider.getValue() + delta);
            n[0]++;
            repeat.postDelayed(loop[0], n[0] < 6 ? 130 : n[0] < 16 ? 75 : 40);
        };
        t.setOnTouchListener((v, e) -> {
            switch (e.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate().scaleX(0.92f).scaleY(0.92f).setDuration(80).start();
                    n[0] = 0;
                    repeat.removeCallbacksAndMessages(null);
                    setOffset(slider.getValue() + delta);
                    repeat.postDelayed(loop[0], 380);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate().scaleX(1f).scaleY(1f).setDuration(120).start();
                    repeat.removeCallbacksAndMessages(null);
                    return true;
                default:
                    return true;
            }
        });
        return t;
    }

    private TextView link(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(Ui.DIM);
        t.setPadding(dp(16), dp(4), dp(2), dp(4));
        return t;
    }

    private LinearLayout labelRow(String text, TextView right) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setTextColor(Ui.INK);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams tlp = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.weight = 1f;
        row.addView(t, tlp);
        if (right != null) row.addView(right);
        return row;
    }

    /** 卡片里的一行设置：除第一行外，上方有一条细线。 */
    private LinearLayout section(LinearLayout card, boolean first) {
        if (!first) {
            View v = new View(this);
            v.setBackgroundColor(Ui.LINE);
            card.addView(v, lp(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.7f))));
        }
        LinearLayout s = new LinearLayout(this);
        s.setOrientation(LinearLayout.VERTICAL);
        s.setPadding(0, dp(16), 0, dp(12));   // 分段选择器自带 4dp 上下留白，下面少 4dp 才看着对称
        card.addView(s, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return s;
    }

    private LinearLayout.LayoutParams below() {
        LinearLayout.LayoutParams p = lp(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(14);
        return p;
    }

    private void buildModeRow(LinearLayout card) {
        LinearLayout s = section(card, true);
        appsLink = link("");
        appsLink.setOnClickListener(v -> startActivity(new Intent(this, AppPickerActivity.class)));
        Ui.press(appsLink, 0.94f);
        // 这一行至少 44dp 高，比文字大一圈都能点到；用负边距抵消，版面不变
        LinearLayout.LayoutParams lrp = lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lrp.topMargin = -dp(10);
        lrp.bottomMargin = -dp(10);
        LinearLayout lr = labelRow("生效范围", appsLink);
        lr.setMinimumHeight(dp(44));
        s.addView(lr, lrp);

        Segmented seg = new Segmented(this, new String[]{"白名单", "全局"},
                AutoMode.isEnabled(this) ? 0 : 1);
        seg.setOnSelect(i -> {
            sp.edit().putBoolean(AutoMode.KEY_AUTO, i == 0).apply();
            AutoMode.evaluate(this);   // 切换后立刻按新模式显示 / 隐藏
            refreshAuto();
        });
        Ui.press(seg, 0.985f);
        s.addView(seg, below());
    }

    private void buildRatioRow(LinearLayout card) {
        LinearLayout s = section(card, false);
        s.addView(labelRow("视频比例", null));

        float cur = sp.getFloat(MaskService.KEY_RATIO, MaskService.DEFAULT_RATIO);
        int sel = Math.abs(RATIO_VALUES[1] - cur) < 0.001f ? 1 : 0;
        if (sel == 0) sp.edit().putFloat(MaskService.KEY_RATIO, RATIO_VALUES[0]).apply();
        Segmented seg = new Segmented(this, new String[]{"16:9", "4:3"}, sel, RATIO_VALUES);
        seg.setOnSelect(i -> {
            sp.edit().putFloat(MaskService.KEY_RATIO, RATIO_VALUES[i]).apply();
            preview.ratioChanged();
            applyRange();
            int cur2 = sp.getInt(MaskService.KEY_OFFSET, 0);
            if (cur2 < slider.getMin()) {      // 换画幅后黑边变窄，微调跟着收回到新的下限
                sp.edit().putInt(MaskService.KEY_OFFSET, slider.getMin()).apply();
                slider.setValue(slider.getMin());
                showOffset(slider.getMin(), false);
            }
            slider.invalidate();
            changed();
        });
        Ui.press(seg, 0.985f);
        s.addView(seg, below());
    }

    private void buildStrengthRow(LinearLayout card) {
        LinearLayout s = section(card, false);
        s.addView(labelRow("遮盖强度", null));

        int curLayers = sp.getInt(MaskService.KEY_LAYERS, MaskService.DEFAULT_LAYERS);
        Segmented seg = new Segmented(this, new String[]{"标准", "加强"}, curLayers >= 2 ? 1 : 0);
        seg.setOnSelect(i -> {
            sp.edit().putInt(MaskService.KEY_LAYERS, STRENGTH_LAYERS[i]).apply();
            changed();
        });
        Ui.press(seg, 0.985f);
        s.addView(seg, below());
    }

    // ---------------- 微调 ----------------

    private void showOffset(int v, boolean animate) {
        if (resetBtn == null) return;
        boolean show = v != 0;
        resetBtn.setClickable(show);
        resetBtn.setEnabled(show);
        float a = show ? 1f : 0f, s = show ? 1f : 0.8f;
        if (animate) {
            resetBtn.animate().alpha(a).scaleX(s).scaleY(s).setDuration(180).start();
        } else {
            resetBtn.setAlpha(a);
            resetBtn.setScaleX(s);
            resetBtn.setScaleY(s);
        }
    }

    /** 微调下限 = 当前画幅下一侧黑边的宽度：再往负，遮罩已经是 0 了，没有意义。 */
    private void applyRange() {
        float ratio = sp.getFloat(MaskService.KEY_RATIO, MaskService.DEFAULT_RATIO);
        slider.setMin(-Ui.baseBarDp(this, ratio));
    }

    private void setOffset(int v) {
        v = Math.max(slider.getMin(), Math.min(Slider.MAX, v));
        if (v == slider.getValue() && sp.getInt(MaskService.KEY_OFFSET, 0) == v) return;
        sp.edit().putInt(MaskService.KEY_OFFSET, v).apply();
        slider.setValue(v);
        showOffset(v, true);
        tick(v);
        changed();
    }

    // ---------------- 生命周期 ----------------

    @Override
    protected void onPause() {
        super.onPause();
        repeat.removeCallbacksAndMessages(null);
        AutoAccessibilityService.onStateChanged = null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (awaitingA11y) {
            awaitingA11y = false;
            // 回到留白：服务若已连上，自动跳回的标志早被它清掉了；没连上说明用户放弃或没开成功，标志作废
            AutoAccessibilityService.clearReturn(sp);
            // “允许受限制的设置”是 Android 13 才有的限制；更低版本没开成功只是用户没开，不弹说明
            if (Build.VERSION.SDK_INT >= 33 && applicable(3) && !AutoMode.isAccessibilityEnabled(this)) {
                showRestrictedHelp();
            }
        }
        AutoAccessibilityService.onStateChanged = () -> runOnUiThread(this::refreshPerms);
        AutoMode.evaluate(this);   // 重启后总开关还是开着，但遮罩没在运行：这里补上
        updateUi(false);
        refreshAuto();
    }

    // ---------------- 状态刷新 ----------------

    /** 开 → 演示屏两侧变白、按钮翻白；关 → 变回黑边。 */
    private void updateUi(boolean animate) {
        boolean on = AutoMode.isMaster(this);
        power.setOn(on, animate);
        preview.setOn(on);
        statusTv.setText(on ? "已开启" : "已关闭");
        statusTv.setTextColor(on ? Ui.INK : Ui.DIM);
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        if (on) {
            g.setColor(Ui.GREEN);
            g.setStroke(dp(2), 0x552EB67D);
        } else {
            g.setColor(0xFFC9CCD6);
        }
        dot.setBackground(g);
        refreshPerms();
    }

    /** 白名单入口：只在白名单模式显示，位置不变所以界面不跳动。 */
    private void refreshAuto() {
        boolean whitelist = AutoMode.isEnabled(this);
        int n = installedSelectedCount();
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder();
        if (n == 0) {                     // 白名单模式下一个都没选 = 哪里都不显示，用橙色提醒
            sb.append("未选择应用 ›");
            sb.setSpan(new android.text.style.ForegroundColorSpan(0xFFD97706), 0, sb.length(),
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        } else {
            sb.append("已选 ");
            int a = sb.length();
            sb.append(String.valueOf(n)).append(" 个应用 ›");
            int blueEnd = a + String.valueOf(n).length();
            sb.setSpan(new android.text.style.ForegroundColorSpan(Ui.PRI), a, blueEnd,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new android.text.style.ForegroundColorSpan(Ui.PRI), sb.length() - 1, sb.length(),
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
            sb.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), a, blueEnd,
                    android.text.Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        appsLink.setText(sb);
        appsLink.setEnabled(whitelist);
        appsLink.setClickable(whitelist);
        appsLink.animate().cancel();
        appsLink.animate().alpha(whitelist ? 1f : 0f).setDuration(180).start();
        updateUi(false);
    }

    private int installedSelectedCount() {
        PackageManager pm = getPackageManager();
        int n = 0;
        for (String pkg : AutoMode.selectedApps(this)) {
            if (pm.getLaunchIntentForPackage(pkg) != null) n++;
        }
        return n;
    }

    // ---------------- 总开关 ----------------

    /** 关闭 → 全部关闭；开启 → 按“白名单 / 全局”模式显示。没有悬浮窗权限时只提醒，不会自己跳转。 */
    private void toggle() {
        if (AutoMode.isMaster(this)) {
            AutoMode.setMaster(this, false);
            updateUi(true);
            return;
        }
        if (!Settings.canDrawOverlays(this)) {
            nudgePerms("先开启悬浮窗权限");
            return;
        }
        AutoMode.setMaster(this, true);
        updateUi(true);
    }

    /** 提醒：授权卡抖一下 + 一句提示 + 一下短震。 */
    private void nudgePerms(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        if (permBox != null && permBox.getVisibility() == View.VISIBLE) {
            ObjectAnimator.ofFloat(permBox, "translationX", 0, -dp(6), dp(6), -dp(4), dp(3), 0)
                    .setDuration(420).start();
        }
        if (vib != null && vib.hasVibrator()) {
            vib.vibrate(VibrationEffect.createWaveform(new long[]{0, 8, 40, 8}, -1));
        }
    }

    /** 设置有变化：让正在显示的遮罩立刻刷新。 */
    private void changed() {
        preview.invalidate();
        // 服务正在运行时直接让它原地更新（不重启、不重建），拖动微调才不会闪
        if (MaskService.running && !MaskService.refresh()) {
            startForegroundService(new Intent(this, MaskService.class));
        }
        AutoMode.evaluate(this);
    }

    /**
     * 拖动微调时的触感：每 1dp 一下很轻的短震，每 10dp 稍实一点，
     * 到 0 或者 ±100 时最明显，让人不用看也知道到了。
     */
    private void tick(int o) {
        boolean edge = o == 0 || o == Slider.MAX || o == slider.getMin();
        boolean major = o % 10 == 0;
        if (vib != null && vib.hasVibrator()) {
            if (Build.VERSION.SDK_INT >= 26 && vib.hasAmplitudeControl()) {
                vib.vibrate(VibrationEffect.createOneShot(
                        edge ? 10 : major ? 6 : 3, edge ? 70 : major ? 36 : 14));
                return;
            }
            if (Build.VERSION.SDK_INT >= 29 && (edge || major)) {
                vib.vibrate(VibrationEffect.createPredefined(
                        edge ? VibrationEffect.EFFECT_CLICK : VibrationEffect.EFFECT_TICK));
                return;
            }
        }
        slider.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
    }

    // ---------------- 权限（全部手动，一项一个“开启”） ----------------

    /** 这一项当前是否需要（通知仅 Android 13+，无障碍仅白名单模式）。 */
    private boolean applicable(int i) {
        if (i == 1) return Build.VERSION.SDK_INT >= 33;
        if (i == 3) return AutoMode.isEnabled(this);
        return true;
    }

    private boolean missing(int i) {
        switch (i) {
            case 0:
                return !Settings.canDrawOverlays(this);
            case 1:
                return Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission("android.permission.POST_NOTIFICATIONS")
                        != PackageManager.PERMISSION_GRANTED;
            case 2:
                return !((PowerManager) getSystemService(POWER_SERVICE))
                        .isIgnoringBatteryOptimizations(getPackageName());
            default:
                // 开关是开的但服务没连上（重启 / 更新后常见）也算没开好
                return !AutoMode.isAccessibilityEnabled(this) || !AutoAccessibilityService.connected;
        }
    }

    /** 必需的：悬浮窗；白名单模式下还有无障碍。其余是可选的。 */
    private boolean required(int i) {
        return i == 0 || i == 3;
    }

    private String permDesc(int i) {
        return i == 0 ? "在视频上方显示遮罩" : "白名单模式下，识别当前打开的视频软件";
    }

    private void buildPerms(LinearLayout col) {
        permBox = new LinearLayout(this);
        permBox.setOrientation(LinearLayout.VERTICAL);
        permBox.setBackground(Ui.round(Ui.WARN_BG, dp(22)));
        Ui.lift(permBox, 3, 0x337A5A12, 0x557A5A12);
        LinearLayout.LayoutParams plp = lp(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        plp.topMargin = dp(land ? 8 : 3);
        col.addView(permBox, plp);
    }

    /** 只列出还没开的权限；回到页面时刷新，开齐后整块收起。 */
    private void refreshPerms() {
        if (permBox == null) return;
        permBox.removeAllViews();
        List<Integer> req = new ArrayList<>(), opt = new ArrayList<>();
        for (int i = 0; i < PERM_NAMES.length; i++) {
            if (applicable(i) && missing(i)) (required(i) ? req : opt).add(i);
        }
        boolean none = req.isEmpty() && opt.isEmpty();
        if (settingsLp != null && !land) {
            int want = dp(none ? 16 : 12);
            if (settingsLp.topMargin != want) {
                settingsLp.topMargin = want;
                settingsCard.requestLayout();
            }
        }
        if (none) {
            permBox.setVisibility(View.GONE);
            return;
        }
        permBox.setVisibility(View.VISIBLE);
        for (int k = 0; k < req.size(); k++) permBox.addView(permRow(req.get(k), k > 0));
        if (!opt.isEmpty()) permBox.addView(optRow(opt, !req.isEmpty()));
    }

    private void divider(boolean show) {
        if (!show) return;
        View v = new View(this);
        v.setBackgroundColor(Ui.WARN_LINE);
        permBox.addView(v, lp(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.7f))));
    }

    private View permRow(int i, boolean divided) {
        divider(divided);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(14), dp(14), dp(14));

        boolean stale = i == 3 && AutoMode.isAccessibilityEnabled(this);
        LinearLayout text = new LinearLayout(this);
        text.setOrientation(LinearLayout.VERTICAL);
        TextView name = new TextView(this);
        name.setText(stale ? "无障碍服务未连接" : PERM_NAMES[i]);
        name.setTextSize(14);
        name.setTextColor(Ui.WARN_TX);
        name.setTypeface(Typeface.DEFAULT_BOLD);
        text.addView(name);
        TextView desc = new TextView(this);
        desc.setText(stale ? "在设置里关闭再打开即可；经常断开请允许留白自启动并锁定后台任务" : permDesc(i));
        desc.setTextSize(12);
        desc.setTextColor(Ui.WARN_SUB);
        desc.setPadding(0, dp(3), 0, 0);
        text.addView(desc);
        LinearLayout.LayoutParams tlp = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.weight = 1f;
        row.addView(text, tlp);

        TextView go = new TextView(this);
        go.setText(stale ? "去重开" : "开启");
        go.setTextSize(13);
        go.setTextColor(0xFFFFFFFF);
        go.setTypeface(Typeface.DEFAULT_BOLD);
        go.setGravity(Gravity.CENTER);
        go.setPadding(dp(16), dp(8), dp(16), dp(8));
        go.setBackground(Ui.gradient(dp(20)));
        Ui.lift(go, 3, 0x404F7CFF, 0x884F7CFF);
        go.setOnClickListener(v -> launch(i));
        Ui.press(go, 0.94f);
        LinearLayout.LayoutParams glp = lp(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        glp.leftMargin = dp(12);
        row.addView(go, glp);
        return wrapRow(row);
    }

    private View optRow(List<Integer> opt, boolean divided) {
        divider(divided);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(11), dp(16), dp(11));
        TextView label = new TextView(this);
        label.setText("可选");
        label.setTextSize(12);
        label.setTextColor(Ui.WARN_SUB);
        row.addView(label);
        for (int i : opt) {
            SpannableString s = new SpannableString("＋ " + PERM_NAMES[i]);
            s.setSpan(new ForegroundColorSpan(Ui.PRI), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            TextView chip = new TextView(this);
            chip.setText(s);
            chip.setTextSize(12);
            chip.setTextColor(Ui.WARN_TX);
            chip.setTypeface(Typeface.DEFAULT_BOLD);
            chip.setPadding(dp(11), dp(6), dp(12), dp(6));
            GradientDrawable cb = Ui.round(0xFFFFFFFF, dp(20));
            cb.setStroke(Math.max(1, dp(1)), 0x247A5A12);
            chip.setBackground(cb);
            Ui.lift(chip, 2, 0x337A5A12, 0x557A5A12);
            chip.setOnClickListener(v -> launch(i));
            Ui.press(chip, 0.94f);
            LinearLayout.LayoutParams clp = lp(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            clp.leftMargin = dp(8);
            row.addView(chip, clp);
        }
        return wrapRow(row);
    }

    private View wrapRow(LinearLayout row) {
        row.setLayoutParams(lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    /** 一项权限，直达对应的系统授权页。只在你点“开启”时才会跳。 */
    private void launch(int i) {
        try {
            switch (i) {
                case 0:
                    startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            Uri.parse("package:" + getPackageName())));
                    break;
                case 1:
                    requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 1);
                    break;
                case 2:
                    try {
                        startActivity(new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                                Uri.parse("package:" + getPackageName())));
                    } catch (Exception e) {
                        startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
                    }
                    break;
                default:
                    // 开启成功后，留白会自己跳回前台，不用手动返回
                    AutoAccessibilityService.markReturn(sp);
                    awaitingA11y = true;
                    openAccessibility();
            }
        } catch (Exception e) {
            // 实在打不开专用页面，就去应用信息页，让用户手动设置
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        refreshPerms();
    }

    /**
     * 无障碍直达：Android 14+ 直接打开“留白”自己的无障碍详情页，
     * 更低版本打开无障碍列表并把“留白”那一项高亮出来。
     */
    private void openAccessibility() {
        String flat = new ComponentName(this, AutoAccessibilityService.class).flattenToString();
        if (Build.VERSION.SDK_INT >= 34) {
            try {
                Intent direct = new Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS");
                direct.putExtra("android.intent.extra.COMPONENT_NAME", flat);
                startActivity(direct);
                return;
            } catch (Exception ignored) { /* 个别系统没有这个页面，退回列表 */ }
        }
        Intent list = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        Bundle args = new Bundle();
        args.putString(":settings:fragment_args_key", flat);
        list.putExtra(":settings:fragment_args_key", flat);
        list.putExtra(":settings:show_fragment_args", args);
        startActivity(list);
    }

    /** 无障碍没开成功：最常见的原因是系统对“非商店安装”的软件置灰了该选项。 */
    private void showRestrictedHelp() {
        new AlertDialog.Builder(this)
                .setTitle("无障碍选项是灰色的")
                .setMessage("如果刚才没能打开，且“留白”那一项是灰色的：\n"
                        + "1. 先回到无障碍页面，点一下灰色的“留白”（系统会提示受限制）\n"
                        + "2. 打开应用信息页，右上角 ⋮ → 允许受限制的设置\n"
                        + "3. 回到留白，再点一次“开启”")
                .setPositiveButton("应用信息", (d, w) -> startActivity(new Intent(
                        Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName()))))
                .setNegativeButton("取消", null)
                .show();
    }
}
