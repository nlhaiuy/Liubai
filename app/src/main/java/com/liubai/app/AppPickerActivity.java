package com.liubai.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import java.text.Collator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 白名单：选择“在哪些软件里自动开启留白”，支持搜索，可同时选多个。
 * 和主页同一套颜色：浅蓝白底、白色卡片、蓝色渐变的勾选。
 * 顶部有两个快捷操作：勾选推荐应用（常见视频软件）、取消全部勾选（可撤销）。
 */
public class AppPickerActivity extends Activity {
    /** true = 编辑竖屏白名单，缺省 / false = 横屏白名单。 */
    public static final String EXTRA_PORTRAIT = "portrait";

    private boolean portrait;

    private static class Item {
        String pkg;
        CharSequence label;
        String key;      // 小写的名称 + 包名，用来搜索
        Drawable icon;
        ResolveInfo ri;          // 图标按需再加载
        boolean loading;
        boolean checked;
        boolean rec;     // 是不是推荐的视频软件
    }

    private final List<Item> all = new ArrayList<>();
    private final List<Item> shown = new ArrayList<>();
    private ListView list;
    private BaseAdapter adapter;
    private EditText search;
    private TextView countTv, recBtn, clrBtn;
    private LinearLayout snack;
    private TextView snackMsg, snackUndo;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable hideSnack;
    private float density;

    private int dp(float v) {
        return Math.round(v * density);
    }

    private LinearLayout.LayoutParams lp(int w, int h) {
        return new LinearLayout.LayoutParams(w, h);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        portrait = getIntent().getBooleanExtra(EXTRA_PORTRAIT, false);
        density = getResources().getDisplayMetrics().density;
        Ui.lightWindow(this);
        getWindow().setSoftInputMode(
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);

        FrameLayout page = new FrameLayout(this);
        page.setBackgroundColor(Ui.BG);

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        page.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ---- 顶栏：返回 · 标题 · 已选数量 ----
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.VERTICAL);
        head.setPadding(dp(20), dp(22), dp(20), dp(12));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);

        ImageView back = new ImageView(this);
        back.setImageResource(R.drawable.ic_back);
        back.setScaleType(ImageView.ScaleType.CENTER);
        back.setContentDescription("返回");
        GradientDrawable bb = new GradientDrawable();
        bb.setShape(GradientDrawable.OVAL);
        bb.setColor(Ui.CARD);
        back.setBackground(bb);
        Ui.lift(back, 3, 0x264F7CFF, 0x404F7CFF);
        back.setOnClickListener(v -> finish());
        Ui.press(back, 0.9f);
        bar.addView(back, lp(dp(40), dp(40)));

        TextView title = new TextView(this);
        title.setText(portrait ? "竖屏白名单" : "横屏白名单");
        title.setTextSize(28);
        title.setTextColor(Ui.TITLE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(0.04f);
        title.setIncludeFontPadding(false);
        LinearLayout.LayoutParams tlp = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT);
        tlp.weight = 1f;
        tlp.leftMargin = dp(12);
        bar.addView(title, tlp);

        countTv = new TextView(this);
        countTv.setTextSize(13);
        countTv.setTextColor(Ui.DIM);
        countTv.setPadding(dp(12), dp(6), dp(12), dp(6));
        countTv.setBackground(Ui.round(Ui.CARD, dp(20)));
        Ui.lift(countTv, 3, 0x264F7CFF, 0x404F7CFF);
        bar.addView(countTv);
        head.addView(bar, lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---- 搜索框 ----
        LinearLayout sbox = new LinearLayout(this);
        sbox.setOrientation(LinearLayout.HORIZONTAL);
        sbox.setGravity(Gravity.CENTER_VERTICAL);
        sbox.setPadding(dp(14), 0, dp(14), 0);
        sbox.setBackground(Ui.round(Ui.CARD, dp(16)));
        Ui.lift(sbox, 3, 0x264F7CFF, 0x404F7CFF);
        ImageView mag = new ImageView(this);
        mag.setImageResource(R.drawable.ic_search);
        sbox.addView(mag, lp(dp(20), dp(20)));
        search = new EditText(this);
        search.setHint("搜索应用");
        search.setSingleLine(true);
        search.setTextSize(15);
        search.setTextColor(Ui.INK);
        search.setHintTextColor(0xFF8A94AA);
        search.setBackground(null);
        search.setPadding(dp(10), 0, 0, 0);
        LinearLayout.LayoutParams elp = lp(0, ViewGroup.LayoutParams.MATCH_PARENT);
        elp.weight = 1f;
        sbox.addView(search, elp);
        LinearLayout.LayoutParams slp = lp(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        slp.topMargin = dp(14);
        head.addView(sbox, slp);

        // ---- 快捷操作 ----
        LinearLayout acts = new LinearLayout(this);
        acts.setOrientation(LinearLayout.HORIZONTAL);
        recBtn = actionButton("勾选推荐应用", true);
        clrBtn = actionButton("取消全部勾选", false);
        LinearLayout.LayoutParams a1 = lp(0, dp(42));
        a1.weight = 1f;
        a1.rightMargin = dp(5);
        LinearLayout.LayoutParams a2 = lp(0, dp(42));
        a2.weight = 1f;
        a2.leftMargin = dp(5);
        acts.addView(recBtn, a1);
        acts.addView(clrBtn, a2);
        LinearLayout.LayoutParams alp = lp(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = dp(12);
        head.addView(acts, alp);
        col.addView(head);

        // ---- 列表：一张白色圆角卡片 ----
        FrameLayout card = new FrameLayout(this);
        Ui.card(card, 24);
        card.setClipToOutline(true);
        list = new ListView(this);
        list.setDividerHeight(0);
        list.setVerticalScrollBarEnabled(false);
        list.setPadding(dp(16), dp(4), dp(16), dp(4));
        list.setClipToPadding(true);
        list.setSelector(android.R.color.transparent);
        card.addView(list, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        LinearLayout.LayoutParams clp = lp(ViewGroup.LayoutParams.MATCH_PARENT, 0);
        clp.weight = 1f;
        clp.setMargins(dp(20), dp(2), dp(20), dp(20));
        col.addView(card, clp);

        // ---- 撤销提示 ----
        snack = new LinearLayout(this);
        snack.setOrientation(LinearLayout.HORIZONTAL);
        snack.setGravity(Gravity.CENTER_VERTICAL);
        snack.setPadding(dp(18), dp(11), dp(18), dp(11));
        snack.setBackground(Ui.round(0xEB1F2638, dp(22)));
        Ui.lift(snack, 8, 0x551F2638, 0x881F2638);
        snackMsg = new TextView(this);
        snackMsg.setTextSize(13);
        snackMsg.setTextColor(0xFFFFFFFF);
        snack.addView(snackMsg);
        snackUndo = new TextView(this);
        snackUndo.setText("撤销");
        snackUndo.setTextSize(13);
        snackUndo.setTextColor(0xFFA9BEFF);
        snackUndo.setTypeface(Typeface.DEFAULT_BOLD);
        snackUndo.setPadding(dp(16), dp(4), 0, dp(4));
        snack.addView(snackUndo);
        snack.setVisibility(View.GONE);
        FrameLayout.LayoutParams snlp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        snlp.bottomMargin = dp(40);
        page.addView(snack, snlp);

        setContentView(page);
        Ui.noClip(page);
        updateCount();

        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void afterTextChanged(Editable s) { refilter(); }
        });
        Ui.press(recBtn, 0.96f);
        Ui.press(clrBtn, 0.96f);
        recBtn.setOnClickListener(v -> {
            if (!recBtn.isEnabled()) return;
            final boolean[] before = snapshot();
            for (Item it : all) if (it.rec) it.checked = true;
            changedAll("已勾选推荐的视频应用", before);
        });
        clrBtn.setOnClickListener(v -> {
            if (!clrBtn.isEnabled()) return;
            final boolean[] before = snapshot();
            for (Item it : all) it.checked = false;
            changedAll("已取消全部勾选", before);
        });

        new Thread(this::load).start();
    }

    private TextView actionButton(String text, boolean primary) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setClickable(true);
        GradientDrawable g = Ui.round(primary ? Ui.PRI_SOFT : Ui.CARD, dp(14));
        if (primary) g.setStroke(Math.max(1, dp(1)), 0x334F7CFF);
        t.setBackground(g);
        t.setTextColor(primary ? Ui.PRI : Ui.DIM);
        if (!primary) Ui.lift(t, 3, 0x264F7CFF, 0x404F7CFF);
        return t;
    }

    private void setActive(TextView t, boolean on) {
        t.setEnabled(on);
        t.setAlpha(on ? 1f : 0.42f);
    }

    private void load() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> ris = pm.queryIntentActivities(main, 0);
        Set<String> sel = AutoMode.selectedApps(this, portrait);
        Map<String, Item> map = new HashMap<>();
        for (ResolveInfo ri : ris) {
            String pkg = ri.activityInfo.packageName;
            if (pkg.equals(getPackageName()) || map.containsKey(pkg)) continue;
            Item it = new Item();
            it.pkg = pkg;
            it.label = ri.loadLabel(pm);
            it.key = (it.label.toString() + " " + pkg).toLowerCase(Locale.ROOT);
            it.ri = ri;
            it.checked = sel.contains(pkg);
            it.rec = AutoMode.isRecommended(pkg);
            map.put(pkg, it);
        }
        runOnUiThread(() -> {
            all.clear();
            all.addAll(map.values());
            adapter = new Adapter();
            list.setAdapter(adapter);
            list.setOnItemClickListener((p, v, pos, id) -> {
                Item it = shown.get(pos);
                it.checked = !it.checked;   // 只改状态，不重排，避免列表乱跳
                Object tag = v.getTag();
                if (tag instanceof Holder) ((Holder) tag).dot.setOn(it.checked, true);   // 圆点弹一下
                save();
            });
            refilter();
        });
    }

    /** 按搜索词过滤；已勾选的排前面，其次是推荐的，其余按名称排序。 */
    private void refilter() {
        String q = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        shown.clear();
        for (Item it : all) {
            if (q.isEmpty() || it.key.contains(q)) shown.add(it);
        }
        Collator col = Collator.getInstance(Locale.CHINA);
        shown.sort((a, c) -> {
            if (a.checked != c.checked) return a.checked ? -1 : 1;
            if (a.rec != c.rec) return a.rec ? -1 : 1;
            return col.compare(a.label.toString(), c.label.toString());
        });
        if (adapter != null) adapter.notifyDataSetChanged();
        updateCount();
    }

    private boolean[] snapshot() {
        boolean[] s = new boolean[all.size()];
        for (int i = 0; i < s.length; i++) s[i] = all.get(i).checked;
        return s;
    }

    /** 批量改动：保存、重新排序，并给出“撤销”。 */
    private void changedAll(String msg, final boolean[] before) {
        save();
        refilter();
        list.setSelection(0);
        list.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK);
        showSnack(msg, () -> {
            for (int i = 0; i < before.length && i < all.size(); i++) all.get(i).checked = before[i];
            save();
            refilter();
        });
    }

    private void showSnack(String msg, Runnable undo) {
        snackMsg.setText(msg);
        snackUndo.setOnClickListener(v -> {
            undo.run();
            hideSnackNow();
        });
        if (hideSnack != null) handler.removeCallbacks(hideSnack);
        snack.animate().cancel();
        snack.setAlpha(0f);
        snack.setTranslationY(dp(10));
        snack.setVisibility(View.VISIBLE);
        snack.animate().alpha(1f).translationY(0).setDuration(220).start();
        hideSnack = this::hideSnackNow;
        handler.postDelayed(hideSnack, 4200);
    }

    private void hideSnackNow() {
        if (hideSnack != null) handler.removeCallbacks(hideSnack);
        snack.animate().cancel();
        snack.animate().alpha(0f).translationY(dp(10)).setDuration(200)
                .withEndAction(() -> snack.setVisibility(View.GONE)).start();
    }

    private void save() {
        Set<String> s = new HashSet<>();
        for (Item it : all) if (it.checked) s.add(it.pkg);
        SharedPreferences sp = getSharedPreferences(MaskService.PREFS, Context.MODE_PRIVATE);
        sp.edit().putStringSet(portrait ? AutoMode.KEY_APPS_P : AutoMode.KEY_APPS, s).apply();
        updateCount();
    }

    private void updateCount() {
        int n = 0;
        boolean recLeft = false;
        for (Item it : all) {
            if (it.checked) n++;
            if (it.rec && !it.checked) recLeft = true;
        }
        String num = String.valueOf(n);
        SpannableString s = new SpannableString("已选 " + num + " 个");
        s.setSpan(new ForegroundColorSpan(Ui.PRI), 3, 3 + num.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        s.setSpan(new StyleSpan(Typeface.BOLD), 3, 3 + num.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        countTv.setText(s);
        setActive(recBtn, recLeft);     // 推荐的都勾上了就不用再点
        setActive(clrBtn, n > 0);
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    /** 勾选圈：没选是一圈浅灰细线，选中是蓝色渐变 + 白色对勾。 */
    private static class CheckDot extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path tick = new Path();
        private final Path part = new Path();
        private boolean on;
        private float p;                       // 0 = 没选，1 = 选中（带一点回弹）
        private android.animation.ValueAnimator va;

        CheckDot(Context c) {
            super(c);
            setLayerType(LAYER_TYPE_SOFTWARE, null);
        }

        void setOn(boolean v, boolean animate) {
            on = v;
            float to = v ? 1f : 0f;
            if (va != null) va.cancel();
            if (!animate || !isLaidOut()) {
                p = to;
                invalidate();
                return;
            }
            va = android.animation.ValueAnimator.ofFloat(p, to);
            va.setDuration(v ? 280 : 160);
            va.setInterpolator(v ? new android.view.animation.OvershootInterpolator(2.4f)
                    : new android.view.animation.DecelerateInterpolator());
            va.addUpdateListener(a -> { p = (Float) a.getAnimatedValue(); invalidate(); });
            va.start();
        }

        @Override
        protected void onDraw(Canvas cv) {
            Context c = getContext();
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) / 2f - Ui.dp(c, 6);   // 四周留出画阴影的空间
            float cx = w / 2f, cy = h / 2f;
            float f = Math.max(0f, Math.min(1f, p));

            // 没选的浅灰细圈：选中时淡出
            if (f < 0.999f) {
                paint.setShader(null);
                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(Ui.dp(c, 1.5f));
                paint.setColor(0xFFC5D0E6);
                paint.setAlpha(Math.round(255 * (1f - f)));
                cv.drawCircle(cx, cy, r, paint);
                paint.setAlpha(255);
            }
            if (p <= 0.01f) return;

            // 选中的蓝色圆：从小弹到位
            float s = 0.7f + 0.3f * p;
            cv.save();
            cv.scale(s, s, cx, cy);
            paint.setStyle(Paint.Style.FILL);
            paint.setShader(new LinearGradient(0, 0, w, h, Ui.PRI2, Ui.PRI, Shader.TileMode.CLAMP));
            paint.setAlpha(Math.round(255 * f));
            paint.setShadowLayer(Ui.dp(c, 4), 0, Ui.dp(c, 2), ((int) (0x88 * f) << 24) | 0x4F7CFF);
            cv.drawCircle(cx, cy, r, paint);
            paint.clearShadowLayer();
            paint.setShader(null);
            paint.setAlpha(255);

            // 对勾：像被画出来一样
            float u = r / 12f;
            tick.reset();
            tick.moveTo(cx - 5.2f * u, cy + 0.2f * u);
            tick.lineTo(cx - 1.4f * u, cy + 4.0f * u);
            tick.lineTo(cx + 5.6f * u, cy - 3.8f * u);
            android.graphics.PathMeasure pm = new android.graphics.PathMeasure(tick, false);
            float draw = Math.max(0f, Math.min(1f, (p - 0.2f) / 0.8f)) * pm.getLength();
            part.reset();
            pm.getSegment(0, draw, part, true);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Ui.dp(c, 2.2f));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setColor(0xFFFFFFFF);
            cv.drawPath(part, paint);
            cv.restore();
        }
    }

    private static final java.util.concurrent.ExecutorService ICONS =
            java.util.concurrent.Executors.newFixedThreadPool(3);

    private class Holder {
        View line;
        ImageView icon;
        TextView name, tag;
        CheckDot dot;
    }

    private class Adapter extends BaseAdapter {
        @Override public int getCount() { return shown.size(); }
        @Override public Object getItem(int i) { return shown.get(i); }
        @Override public long getItemId(int i) { return i; }

        private View create(Holder h) {
            LinearLayout wrap = new LinearLayout(AppPickerActivity.this);
            wrap.setOrientation(LinearLayout.VERTICAL);

            h.line = new View(AppPickerActivity.this);
            h.line.setBackgroundColor(Ui.LINE);
            wrap.addView(h.line, lp(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(0.7f))));

            LinearLayout row = new LinearLayout(AppPickerActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, dp(8));

            h.icon = new ImageView(AppPickerActivity.this);
            row.addView(h.icon, lp(dp(42), dp(42)));

            LinearLayout nameBox = new LinearLayout(AppPickerActivity.this);
            nameBox.setOrientation(LinearLayout.HORIZONTAL);
            nameBox.setGravity(Gravity.CENTER_VERTICAL);
            nameBox.setPadding(dp(14), 0, dp(8), 0);
            h.name = new TextView(AppPickerActivity.this);
            h.name.setTextSize(15);
            h.name.setTextColor(Ui.INK);
            h.name.setSingleLine(true);
            h.name.setEllipsize(android.text.TextUtils.TruncateAt.END);
            nameBox.addView(h.name, lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            h.tag = new TextView(AppPickerActivity.this);
            h.tag.setText("推荐");
            h.tag.setTextSize(11);
            h.tag.setTextColor(Ui.PRI);
            h.tag.setTypeface(Typeface.DEFAULT_BOLD);
            h.tag.setPadding(dp(7), dp(1), dp(7), dp(1));
            h.tag.setBackground(Ui.round(Ui.PRI_SOFT, dp(6)));
            LinearLayout.LayoutParams tg = lp(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            tg.leftMargin = dp(8);
            nameBox.addView(h.tag, tg);
            LinearLayout.LayoutParams nlp = lp(0, ViewGroup.LayoutParams.WRAP_CONTENT);
            nlp.weight = 1f;
            row.addView(nameBox, nlp);

            h.dot = new CheckDot(AppPickerActivity.this);
            row.addView(h.dot, lp(dp(36), dp(36)));

            wrap.addView(row, lp(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));
            wrap.setTag(h);
            return wrap;
        }

        @Override
        public View getView(int i, View convert, ViewGroup parent) {
            Item it = shown.get(i);
            View v = convert;
            Holder h;
            if (v == null) {
                h = new Holder();
                v = create(h);
            } else {
                h = (Holder) v.getTag();
            }
            h.line.setVisibility(i > 0 ? View.VISIBLE : View.GONE);
            h.name.setText(it.label);
            h.tag.setVisibility(it.rec ? View.VISIBLE : View.GONE);
            h.dot.setOn(it.checked, false);
            bindIcon(h.icon, it);
            return v;
        }
    }

    /** 图标慢慢补：先给一个浅色占位，后台加载好再换上，列表不用等所有图标。 */
    private void bindIcon(final ImageView iv, final Item it) {
        iv.setTag(it.pkg);
        if (it.icon != null) {
            iv.setBackground(null);
            iv.setImageDrawable(it.icon);
            return;
        }
        iv.setImageDrawable(null);
        iv.setBackground(Ui.round(Ui.CHIP, dp(11)));
        if (it.loading) return;
        it.loading = true;
        final PackageManager pm = getPackageManager();
        ICONS.execute(() -> {
            Drawable d;
            try { d = it.ri.loadIcon(pm); } catch (Exception e) { d = pm.getDefaultActivityIcon(); }
            final Drawable icon = d;
            it.icon = icon;
            runOnUiThread(() -> {
                if (it.pkg.equals(iv.getTag())) {
                    iv.setBackground(null);
                    iv.setImageDrawable(icon);
                } else if (adapter != null) {
                    adapter.notifyDataSetChanged();
                }
            });
        });
    }
}
