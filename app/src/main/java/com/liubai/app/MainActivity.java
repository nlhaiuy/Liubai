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
 * 下面单独滚动：授权提示、生效范围 / 视频比例（开竖屏遮罩时是视频位置）/ 遮盖强度；
 * 右上角是总开关，底部大按钮是竖屏遮罩开关。
 * 横屏时左边是演示屏，右边是设置。所有权限都由你手动点“开启”，不会自动跳转。
 */
public class MainActivity extends Activity {
    private static final float[] RATIO_VALUES = {16f / 9f, 4f / 3f};
    private static final float[] POS_VALUES = {0.5f, MaskService.POS_TOP, MaskService.POS_STATUS};
    private static final int[] STRENGTH_LAYERS = {1, 3};

    private SharedPreferences sp;
    private float density;
    private Vibrator vib;
    private boolean land;

    private View swTrack, swKnob;                   // 右上角总开关
    private LinearLayout permBox;
    private TextView statusTv, appsLink, resetBtn;
    private PreviewView preview;
    private boolean editPortrait;                   // 设置页当前在编辑竖屏还是横屏
    private Segmented segL, segP;                   // 横屏比例 / 竖屏位置
    private LinearLayout stage, side, fine;        // 演示卡内部：屏幕那一块 + 微调那一块（− 滑条 +）
    private TextView minusBtn, plusBtn;
    private FrameLayout shot;
    private TextView ratioTitle;                    // “视频比例” / “视频位置”标题
    private TextView modeTitle;                     // “生效范围”标题（竖屏时带“· 竖屏”）
    private Segmented playSeg;                      // 显示时机：始终显示 / 仅播放时
    private TextView playHint;                      // 显示时机右边的识别方式名
    private LinearLayout settingsCard;
    private LinearLayout.LayoutParams settingsLp;   // 设置卡的上边距：授权卡显示时 12dp，收起时补回 16dp
    private PowerButton power;
    private Slider slider;
    private final Handler repeat = new Handler(Looper.getMainLooper());

    // 权限：0 悬浮窗、1 通知、2 后台运行、3 无障碍；4 通知使用权、5 Shizuku（只在“仅播放时显示”下可选）
    private static final String[] PERM_NAMES = {"悬浮窗", "通知", "后台运行", "无障碍", "通知使用权", "Shizuku"};
    private boolean awaitingA11y = false;

    private int dp(float v) { return Math.round(v * density); }

    /** 横屏 / 竖屏的设置分开存。 */
    private String offKey() { return editPortrait ? MaskService.KEY_OFFSET_P : MaskService.KEY_OFFSET; }
    private float curRatio() {
        return editPortrait ? MaskService.DEFAULT_RATIO
                : sp.getFloat(MaskService.KEY_RATIO, MaskService.DEFAULT_RATIO);
    }

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
        editPortrait = sp.getBoolean(MaskService.KEY_PORTRAIT, false);
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
        buildPlayRow(card);
        buildRatioRow(card);
        buildStrengthRow(card);

        // 底部渐隐，让大开关浮在内容上面
        View fade = new View(this);
        fade.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x00F3F6FC, Ui.BG}));
        page.addView(fade, new FrameLayout.LayoutParams(rightW, dp(120),
                land ? (Gravity.BOTTOM | Gravity.END) : Gravity.BOTTOM));

        // 竖屏遮罩开关
        power = new PowerButton(this);
        power.setLabels("开启竖屏遮罩", "关闭竖屏遮罩");   // 底部大按钮 = 竖屏开关
        power.setOnClickListener(v -> togglePortrait());
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

        // 右上角：总开关（点一下开 / 关）
        LinearLayout chip = new LinearLayout(this);
        chip.setOrientation(LinearLayout.HORIZONTAL);
        chip.setGravity(Gravity.CENTER_VERTICAL);
        chip.setPadding(dp(14), dp(7), dp(9), dp(7));
        chip.setBackground(Ui.round(Ui.CARD, dp(20)));
        chip.setContentDescription("总开关");
        chip.setClickable(true);
        chip.setOnClickListener(v -> toggle());
        Ui.press(chip, 0.95f);
        Ui.lift(chip, 3, 0x264F7CFF, 0x404F7CFF);
        statusTv = new TextView(this);
        statusTv.setTextSize(13);
        statusTv.setPadding(0, 0, dp(9), 0);
        chip.addView(statusTv);
        FrameLayout sw = new FrameLayout(this);
        swTrack = new View(this);
        sw.addView(swTrack, new FrameLayout.LayoutParams(dp(38), dp(22)));
        swKnob = new View(this);
        GradientDrawable kd = new GradientDrawable();
        kd.setShape(GradientDrawable.OVAL);
        kd.setColor(0xFFFFFFFF);
        swKnob.setBackground(kd);
        FrameLayout.LayoutParams klp = new FrameLayout.LayoutParams(dp(16), dp(16));
        klp.setMargins(dp(3), dp(3), 0, 0);
        sw.addView(swKnob, klp);
        chip.addView(sw, lp(dp(38), dp(22)));
        head.addView(chip, lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        parent.addView(head, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }

    /**
     * 演示卡。横屏：屏幕在上，下面一行 − 滑条 +。
     * 竖屏：一只小手机在左，右边是 − 滑条 +，横向并排，整张卡和横屏那张一样高（切换时页面不跳）。
     * 微调不为 0 时，卡片右上角出现“归零”。
     */
    private void buildStage(LinearLayout parent) {
        FrameLayout host = new FrameLayout(this);   // 白色卡片本体；归零按钮挂在它的右上角
        Ui.card(host, 28);
        stage = new LinearLayout(this);
        stage.setPadding(dp(12), dp(12), dp(12), dp(12));
        host.addView(stage, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        shot = new FrameLayout(this);
        preview = new PreviewView(this);
        preview.setPortraitHeightPx(portraitScreenHeightPx());
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
        rlp.setMargins(0, dp(16), dp(16), 0);   // 卡片内边距 12dp + 4dp，和原来挂在屏幕右上角时的位置一致
        host.addView(resetBtn, rlp);

        fine = new LinearLayout(this);
        minusBtn = stepButton("−", "减小", -1);
        plusBtn = stepButton("+", "增大", 1);

        slider = new Slider(this);
        slider.setContentDescription("宽度微调");
        applyRange();
        int off = Math.max(slider.getMin(), Math.min(Slider.MAX, sp.getInt(offKey(), 0)));
        slider.setValue(off);
        showOffset(off, false);
        slider.setOnChange((v, user) -> {
            sp.edit().putInt(offKey(), v).apply();
            showOffset(v, true);
            if (user) tick(v);
            changed();
        });

        side = new LinearLayout(this);          // 微调：横屏是屏幕下面一行，竖屏是小手机右边一列
        side.setOrientation(LinearLayout.VERTICAL);
        side.addView(fine);
        stage.addView(shot);
        stage.addView(side);
        updateStageLayout();

        LinearLayout.LayoutParams sgp = lp(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        sgp.topMargin = dp(land ? 12 : 18);
        parent.addView(host, sgp);
    }

    /**
     * 竖屏演示屏的高度：让整张卡和横屏那张一样高。
     * 横屏那张 = 演示屏 + 一行微调（滑条控件高 Slider.THICK_DP），竖屏把这一行的高度让给小手机。
     */
    private float portraitScreenHeightPx() {
        int screenW = getResources().getDisplayMetrics().widthPixels;
        float containerW = land ? screenW * 0.46f : screenW;          // 左栏 / 整屏宽度
        float inner = containerW - dp(land ? 20 + 8 : 20 + 20) - dp(24);   // 减去页边距和卡片内边距 = 横屏演示屏的宽度
        return inner / PreviewView.aspect(this) + dp(Slider.THICK_DP);
    }

    /** 横屏：屏幕在上、微调（− 滑条 +）在下；竖屏：小手机在左、微调（+ 滑条 −）竖着排在右边。 */
    private void updateStageLayout() {
        boolean compact = editPortrait;
        stage.setOrientation(compact ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        stage.setGravity(compact ? Gravity.CENTER : Gravity.NO_GRAVITY);
        slider.setVertical(compact);

        fine.removeAllViews();
        fine.setOrientation(compact ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        fine.setGravity(compact ? Gravity.CENTER_HORIZONTAL : Gravity.CENTER_VERTICAL);
        if (compact) {
            // 上 + ，中间滑条（下小上大），下 −
            fine.addView(plusBtn, lp(dp(44), dp(44)));
            LinearLayout.LayoutParams vlp = lp(dp(Slider.THICK_DP), 0);
            vlp.weight = 1f;
            fine.addView(slider, vlp);
            fine.addView(minusBtn, lp(dp(44), dp(44)));

            shot.setLayoutParams(lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout.LayoutParams slp = lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.MATCH_PARENT);
            slp.leftMargin = dp(6);   // 滑条在 56dp 宽的列里居中，再加上滑块本身的留白，这样手机到滑块约 18dp
            side.setLayoutParams(slp);
            fine.setLayoutParams(lp(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT));
        } else {
            fine.addView(minusBtn, lp(dp(44), dp(44)));
            LinearLayout.LayoutParams hlp = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT);
            hlp.weight = 1f;
            hlp.setMargins(dp(2), 0, dp(2), 0);
            fine.addView(slider, hlp);
            fine.addView(plusBtn, lp(dp(44), dp(44)));

            shot.setLayoutParams(lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            side.setLayoutParams(lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            fine.setLayoutParams(lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
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

    private LinearLayout labelRow(String text, View right) {
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
        // 横屏、竖屏各一份白名单：当前在编辑哪一侧（底部大按钮切换），就打开哪一份
        appsLink.setOnClickListener(v -> startActivity(new Intent(this, AppPickerActivity.class)
                .putExtra(AppPickerActivity.EXTRA_PORTRAIT, editPortrait)));
        Ui.press(appsLink, 0.94f);
        // 这一行至少 44dp 高，比文字大一圈都能点到；用负边距抵消，版面不变
        LinearLayout.LayoutParams lrp = lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lrp.topMargin = -dp(10);
        lrp.bottomMargin = -dp(10);
        LinearLayout lr = labelRow("生效范围", appsLink);
        modeTitle = (TextView) lr.getChildAt(0);
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

    /** 显示时机：白名单软件里一直显示，或者只在它真的在播放时显示（用不用 Shizuku 都行，有就更准）。 */
    private void buildPlayRow(LinearLayout card) {
        LinearLayout s = section(card, false);
        // 右边只放当前识别方式的名字（Shizuku / 媒体会话 / 系统音频），“始终显示”时不显示；
        // 行高和上面“生效范围”那一行一样，版面不跳
        playHint = link("");
        LinearLayout.LayoutParams lrp = lp(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lrp.topMargin = -dp(10);
        lrp.bottomMargin = -dp(10);
        LinearLayout lr = labelRow("显示时机", playHint);
        lr.setMinimumHeight(dp(44));
        s.addView(lr, lrp);

        playSeg = new Segmented(this, new String[]{"始终显示", "仅播放时"},
                AutoMode.isPlayOnly(this) ? 1 : 0);
        playSeg.setOnSelect(i -> {
            sp.edit().putBoolean(AutoMode.KEY_PLAY_ONLY, i == 1).apply();
            AutoMode.evaluate(this);   // 立刻开启 / 关闭播放检测并重新判断
            refreshPerms();            // “可选”里的通知使用权、Shizuku 跟着出现 / 收起
            refreshPlayHint();
        });
        Ui.press(playSeg, 0.985f);
        s.addView(playSeg, below());
    }

    /** 显示时机右边的识别方式名：开了“仅播放时”才显示；没开时留空（占位不变）。 */
    private void refreshPlayHint() {
        if (playHint == null) return;
        if (!AutoMode.isPlayOnly(this)) {
            playHint.setText("");
            return;
        }
        int level = PlaybackDetector.level();
        playHint.setText(level == 3 ? "Shizuku" : level == 2 ? "媒体会话" : "系统音频");
        playHint.setTextColor(Ui.PRI);
        playHint.setTypeface(Typeface.DEFAULT_BOLD);
    }

    /**
     * 视频比例：横屏是 16:9 / 4:3；右边的小链接切到竖屏，竖屏有两项：16:9 居中、视频靠上。
     * 横屏、竖屏的微调分开保存；切到竖屏就是开启竖屏遮罩，切回横屏就关闭。
     */
    private void buildRatioRow(LinearLayout card) {
        LinearLayout s = section(card, false);
        LinearLayout lr = labelRow("视频比例", null);
        ratioTitle = (TextView) lr.getChildAt(0);
        s.addView(lr);

        // 横屏
        float cur = sp.getFloat(MaskService.KEY_RATIO, MaskService.DEFAULT_RATIO);
        int selL = Math.abs(RATIO_VALUES[1] - cur) < 0.001f ? 1 : 0;
        if (selL == 0) sp.edit().putFloat(MaskService.KEY_RATIO, RATIO_VALUES[0]).apply();
        segL = new Segmented(this, new String[]{"16:9", "4:3"}, selL, RATIO_VALUES);
        segL.setOnSelect(i -> {
            sp.edit().putFloat(MaskService.KEY_RATIO, RATIO_VALUES[i]).apply();
            afterRatioChanged();
        });
        Ui.press(segL, 0.985f);
        s.addView(segL, below());

        // 竖屏：视频 16:9 撑满宽度；居中 / 靠上（直播间那种）/ 状态栏下（B 站、抖音那种视频顶边接着状态栏）
        float storedPos = sp.getFloat(MaskService.KEY_POS_P, 0.5f);
        int selP = storedPos < 0f ? 2 : storedPos < 0.4f ? 1 : 0;
        segP = new Segmented(this, new String[]{"16:9 居中", "视频靠上", "状态栏下"}, selP);
        segP.setOnSelect(i -> {
            sp.edit().putFloat(MaskService.KEY_POS_P, POS_VALUES[i]).apply();
            preview.invalidate();
            changed();
        });
        Ui.press(segP, 0.985f);
        s.addView(segP, below());

        updateModeUi();
    }

    /** 标题、链接、两排选项跟着当前是横屏还是竖屏。 */
    private void updateModeUi() {
        ratioTitle.setText(editPortrait ? "视频位置" : "视频比例");
        segL.setVisibility(editPortrait ? View.GONE : View.VISIBLE);
        segP.setVisibility(editPortrait ? View.VISIBLE : View.GONE);
    }

    /** 比例变了：演示屏平滑过渡，微调范围收回到新的下限。 */
    private void afterRatioChanged() {
        preview.ratioChanged();
        applyRange();
        int cur2 = sp.getInt(offKey(), 0);
        if (cur2 < slider.getMin()) {      // 换画幅后黑边变窄，微调跟着收回到新的下限
            sp.edit().putInt(offKey(), slider.getMin()).apply();
            slider.setValue(slider.getMin());
            showOffset(slider.getMin(), false);
        }
        slider.invalidate();
        changed();
    }

    /** 切到横屏 / 竖屏：微调、演示屏、微调、演示屏都换成对应一侧的设置。 */
    private void onTabChanged() {
        updateStageLayout();
        updateModeUi();
        applyRange();
        int off = Math.max(slider.getMin(), Math.min(Slider.MAX, sp.getInt(offKey(), 0)));
        slider.setValue(off);
        showOffset(off, false);
        preview.tabChanged();
        refreshAuto();   // 白名单入口也是横屏 / 竖屏各一份，数量和标题跟着换
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
        float ratio = curRatio();
        slider.setMin(-(editPortrait ? Ui.basePortraitBarDp(this, ratio) : Ui.baseBarDp(this, ratio)));
    }

    private void setOffset(int v) {
        v = Math.max(slider.getMin(), Math.min(Slider.MAX, v));
        if (v == slider.getValue() && sp.getInt(offKey(), 0) == v) return;
        sp.edit().putInt(offKey(), v).apply();
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
        PlaybackDetector.onStateChanged = null;
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
        // Shizuku 连上 / 授权、通知使用权变化时，刷新“可选”授权和识别方式说明
        PlaybackDetector.onStateChanged = () -> runOnUiThread(() -> {
            refreshPerms();
            refreshPlayHint();
        });
        AutoMode.evaluate(this);   // 重启后总开关还是开着，但遮罩没在运行：这里补上
        updateUi(false);
        refreshAuto();
    }

    // ---------------- 状态刷新 ----------------

    /** 开 → 演示屏两侧变白、按钮翻白；关 → 变回黑边。 */
    private void updateUi(boolean animate) {
        boolean on = AutoMode.isMaster(this);
        power.setOn(editPortrait, animate);
        preview.setOn(on);
        statusTv.setText(on ? "已开启" : "已关闭");
        statusTv.setTextColor(on ? Ui.INK : Ui.DIM);
        swTrack.setBackground(Ui.round(on ? Ui.GREEN : 0xFFC9CCD6, dp(11)));
        float kx = on ? dp(16) : 0f;
        if (animate) swKnob.animate().translationX(kx).setDuration(180).start();
        else swKnob.setTranslationX(kx);
        refreshPerms();
    }

    /** 白名单入口：只在白名单模式显示，位置不变所以界面不跳动。 */
    private void refreshAuto() {
        boolean whitelist = AutoMode.isEnabled(this);
        modeTitle.setText(editPortrait ? "生效范围 · 竖屏" : "生效范围 · 横屏");
        if (playSeg != null) playSeg.setAlpha(whitelist ? 1f : 0.4f);   // 全局模式下没有“哪个软件”可言，仅白名单生效
        refreshPlayHint();
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
        for (String pkg : AutoMode.selectedApps(this, editPortrait)) {
            if (pm.getLaunchIntentForPackage(pkg) != null) n++;
        }
        return n;
    }

    // ---------------- 总开关（右上角）/ 竖屏开关（底部） ----------------

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

    /** 底部大按钮：竖屏遮罩开 / 关。开着时，比例、微调、演示屏都是竖屏那一侧。 */
    private void togglePortrait() {
        editPortrait = !editPortrait;
        sp.edit().putBoolean(MaskService.KEY_PORTRAIT, editPortrait).apply();
        power.setOn(editPortrait, true);
        onTabChanged();
        changed();
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
        if (i == 4) return AutoMode.isEnabled(this) && AutoMode.isPlayOnly(this);   // 通知使用权
        // Shizuku：“仅播放时”要用它；另外只要还有权限没开，也提示它——授权后可以一键开启其余权限
        if (i == 5) return (AutoMode.isEnabled(this) && AutoMode.isPlayOnly(this)) || anyBasicMissing();
        return true;
    }

    /** 悬浮窗、通知、后台运行、无障碍里，是不是还有没开的。 */
    private boolean anyBasicMissing() {
        for (int i = 0; i <= 3; i++) {
            if (applicable(i) && missing(i)) return true;
        }
        return false;
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
            case 4:
                // Shizuku 已经就绪就不用再要通知使用权了
                return !PlaybackDetector.listenerEnabled(this) && PlaybackDetector.shizukuState(this) != 3;
            case 5:
                return PlaybackDetector.shizukuState(this) != 3;
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

    /**
     * 点“开启”：Shizuku 已经授权给留白时，直接由 Shizuku 替留白开启，不用跳系统设置页；
     * 没有 Shizuku、或者开启后没生效，就走原来的系统授权页。
     */
    private void launch(int i) {
        if (i <= 4 && PlaybackDetector.shizukuState(this) == 3) {
            ShizukuGrant.grant(this, i, error -> onShizukuGranted(i, error));
            return;
        }
        launchSystem(i);
    }

    private void onShizukuGranted(int i, String error) {
        if (isFinishing() || isDestroyed()) return;
        refreshPerms();
        if (error.isEmpty() && i == 3) {
            // 无障碍服务要等系统重新绑定，一两秒后才会显示“已连接”，这里不能立刻判定没生效
            Toast.makeText(this, "已通过 Shizuku 开启无障碍", Toast.LENGTH_SHORT).show();
            repeat.postDelayed(this::refreshPerms, 1200);
            repeat.postDelayed(this::refreshPerms, 3000);
            return;
        }
        if (error.isEmpty() && !missing(i)) {
            Toast.makeText(this, "已通过 Shizuku 开启" + PERM_NAMES[i], Toast.LENGTH_SHORT).show();
            return;
        }
        launchSystem(i);   // Shizuku 没能开启（个别系统限制），退回系统授权页
    }

    /** 一项权限，直达对应的系统授权页。只在你点“开启”时才会跳。 */
    private void launchSystem(int i) {
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
                case 4:
                    openListenerSettings();
                    break;
                case 5:
                    openShizuku();
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

    /** 通知使用权：Android 11+ 直达“留白”自己的页面，更低版本打开列表。留白不读取任何通知内容。 */
    private void openListenerSettings() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                Intent direct = new Intent("android.settings.NOTIFICATION_LISTENER_DETAIL_SETTINGS");
                direct.putExtra("android.provider.extra.NOTIFICATION_LISTENER_COMPONENT_NAME",
                        new ComponentName(this, PlaybackListenerService.class).flattenToString());
                startActivity(direct);
                return;
            } catch (Exception ignored) { /* 个别系统没有这个页面，退回列表 */ }
        }
        startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
    }

    /** Shizuku：没装 → 打开官网；没运行 → 打开 Shizuku 去启动；没授权 → 弹出授权窗口。 */
    private void openShizuku() {
        switch (PlaybackDetector.shizukuState(this)) {
            case 0:
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://shizuku.rikka.app/")));
                break;
            case 1:
                Intent open = getPackageManager().getLaunchIntentForPackage(PlaybackDetector.SHIZUKU_PKG);
                if (open != null) startActivity(open);
                break;
            default:
                PlaybackDetector.requestShizukuPermission(this);
        }
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
