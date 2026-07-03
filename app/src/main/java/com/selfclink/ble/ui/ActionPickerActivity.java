package com.selfclink.ble.ui;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.text.InputType;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.selfclink.ble.R;
import com.selfclink.ble.automation.ActionDef;
import com.selfclink.ble.automation.ActionCatalog;
import com.selfclink.ble.automation.CustomAction;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 动作选择器：按分类（自定义/空调/座椅/车身/其它/系统）列出可绑定动作，多选后回传给编排页。
 * 选中顺序即执行顺序。自定义动作（打开应用 / 打开网址 / 执行命令）在顶部按需添加。
 */
public final class ActionPickerActivity extends BackBarActivity {

    public static final String EXTRA_TITLE = "title";
    public static final String EXTRA_KEYS = "keys";

    /** 保持选择顺序。 */
    private final Set<String> selected = new LinkedHashSet<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_action_picker);

        String title = getIntent().getStringExtra(EXTRA_TITLE);
        if (title != null) {
            ((TextView) findViewById(R.id.picker_title)).setText(title + " · 选择动作");
        }
        String[] pre = getIntent().getStringArrayExtra(EXTRA_KEYS);
        if (pre != null) {
            Collections.addAll(selected, pre);
        }

        buildList();
        findViewById(R.id.btn_confirm).setOnClickListener(v -> confirm());
    }

    private void buildList() {
        LinearLayout root = findViewById(R.id.action_container);
        root.removeAllViews();

        // ---- 自定义动作 ----
        root.addView(header("自定义"));
        root.addView(addButton("＋ 打开应用", v -> pickApp()));
        root.addView(addButton("＋ 打开网址 / 导航", v -> inputUrl()));
        root.addView(addButton("＋ 执行命令", v -> inputShell()));
        for (String key : new ArrayList<>(selected)) {
            if (CustomAction.isCustom(key)) {
                root.addView(makeCustomRow(key));
            }
        }

        // ---- 固定动作 ----
        Map<String, List<ActionDef>> grouped = ActionCatalog.grouped();
        for (Map.Entry<String, List<ActionDef>> e : grouped.entrySet()) {
            root.addView(header(e.getKey()));
            for (ActionDef def : e.getValue()) {
                root.addView(makeRow(def));
            }
        }
    }

    private TextView header(String text) {
        TextView header = new TextView(this);
        header.setText(text);
        header.setTextColor(getColor(R.color.accent));
        header.setTextSize(17);
        header.setTypeface(header.getTypeface(), android.graphics.Typeface.BOLD);
        header.setPadding(0, dp(18), 0, dp(8));
        return header;
    }

    private TextView addButton(String text, android.view.View.OnClickListener l) {
        TextView row = new TextView(this);
        row.setText(text);
        row.setTextSize(18);
        row.setTextColor(getColor(R.color.accent));
        row.setBackgroundResource(R.drawable.btn_ghost);
        int p = dp(18);
        row.setPadding(p, dp(16), p, dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        row.setLayoutParams(lp);
        row.setOnClickListener(l);
        return row;
    }

    private TextView makeCustomRow(String key) {
        TextView row = new TextView(this);
        row.setTextSize(18);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(18);
        row.setPadding(p, dp(16), p, dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        row.setLayoutParams(lp);
        row.setBackgroundResource(R.drawable.btn_accent);
        row.setTextColor(getColor(R.color.accent_ink));
        row.setText("✓ " + CustomAction.label(this, key));
        row.setOnClickListener(v -> {
            selected.remove(key);
            buildList();
        });
        return row;
    }

    private TextView makeRow(ActionDef def) {
        TextView row = new TextView(this);
        row.setText(def.name);
        row.setTextSize(18);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int p = dp(18);
        row.setPadding(p, dp(16), p, dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(10);
        row.setLayoutParams(lp);
        applyState(row, def, selected.contains(def.key));
        row.setOnClickListener(v -> {
            boolean now = !selected.contains(def.key);
            if (now) {
                selected.add(def.key);
            } else {
                selected.remove(def.key);
            }
            applyState(row, def, now);
        });
        return row;
    }

    private void applyState(TextView row, ActionDef def, boolean on) {
        row.setBackgroundResource(on ? R.drawable.btn_accent : R.drawable.chip_bg);
        row.setTextColor(getColor(on ? R.color.accent_ink : R.color.txt));
        String mark = on ? "✓ " : "";
        String guard = def.parkGuard ? "  · P 档" : "";
        row.setText(mark + def.name + guard);
        row.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, 0, 0);
    }

    /** 列出可启动应用，选中后加入 app_open: 键。 */
    private void pickApp() {
        PackageManager pm = getPackageManager();
        Intent main = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        List<ResolveInfo> infos = pm.queryIntentActivities(main, 0);
        final List<String> labels = new ArrayList<>();
        final List<String> pkgs = new ArrayList<>();
        List<ResolveInfo> sorted = new ArrayList<>(infos);
        Collections.sort(sorted, new Comparator<ResolveInfo>() {
            @Override
            public int compare(ResolveInfo a, ResolveInfo b) {
                return a.loadLabel(pm).toString().compareToIgnoreCase(b.loadLabel(pm).toString());
            }
        });
        Set<String> seen = new LinkedHashSet<>();
        for (ResolveInfo ri : sorted) {
            String pkg = ri.activityInfo.packageName;
            if (!seen.add(pkg)) {
                continue;
            }
            labels.add(ri.loadLabel(pm).toString() + "\n" + pkg);
            pkgs.add(pkg);
        }
        new AlertDialog.Builder(this)
                .setTitle("选择应用")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    selected.add(CustomAction.APP + pkgs.get(which));
                    buildList();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void inputUrl() {
        final EditText et = new EditText(this);
        et.setHint("如 amapuri://route/plan/ 或 https://…");
        et.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        padDialog(et);
        new AlertDialog.Builder(this)
                .setTitle("打开网址 / 导航")
                .setView(et)
                .setPositiveButton("确定", (d, w) -> {
                    String s = et.getText().toString().trim();
                    if (!s.isEmpty()) {
                        selected.add(CustomAction.URL + s);
                        buildList();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void inputShell() {
        final EditText et = new EditText(this);
        et.setHint("如 input keyevent 26");
        padDialog(et);
        new AlertDialog.Builder(this)
                .setTitle("执行命令（sh -c）")
                .setView(et)
                .setPositiveButton("确定", (d, w) -> {
                    String s = et.getText().toString().trim();
                    if (!s.isEmpty()) {
                        selected.add(CustomAction.SHELL + s);
                        buildList();
                    }
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void padDialog(EditText et) {
        int p = dp(20);
        et.setPadding(p, dp(12), p, dp(12));
    }

    private void confirm() {
        Intent out = new Intent();
        out.putExtra(EXTRA_KEYS, new ArrayList<>(selected).toArray(new String[0]));
        setResult(RESULT_OK, out);
        finish();
    }

    private int dp(int v) {
        return (int) (getResources().getDisplayMetrics().density * v);
    }
}
