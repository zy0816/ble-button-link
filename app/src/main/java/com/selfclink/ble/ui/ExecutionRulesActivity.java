package com.selfclink.ble.ui;
import android.os.Bundle;
import android.graphics.Typeface;
import android.view.View;
import android.widget.*;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.widget.SwitchCompat;
import com.selfclink.ble.R;
import com.selfclink.ble.automation.*;
import com.selfclink.ble.vehicle.ConditionStates;
import java.util.*;

/** 大触控分组编辑器：规则列表、条件组合、范围、动作和只读试算。 */
public final class ExecutionRulesActivity extends BackBarActivity {
    private ExecutionRuleStore store;
    private final List<ExecutionRule> rules=new ArrayList<>();
    private LinearLayout content;
    private ExecutionRule draft;
    private EditText name;
    private boolean broken;
    private boolean busy;
    private final java.util.concurrent.ExecutorService io =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);screen("执行条件");text(content,"正在读取规则…",20,false);
        busy = true;
        io.execute(() -> {
            List<ExecutionRule> loaded = new ArrayList<>();
            boolean failed = false;
            try {store=new ExecutionRuleStore(this);loaded.addAll(store.load());}
            catch(RuntimeException ex){failed=true;}
            final boolean error=failed;
            main.post(() -> {
                if(isDestroyed() || isFinishing())return;
                busy=false;broken=error;rules.addAll(loaded);showList();
                if(!broken && b!=null && b.containsKey("draft"))try{
                    edit(ExecutionRule.fromJson(new org.json.JSONObject(b.getString("draft")),false));
                }catch(Exception ignored){}
            });
        });
    }
    private int dp(int v){return (int)(getResources().getDisplayMetrics().density*v);}
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private void screen(String title) {
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true);scroll.setBackgroundColor(getColor(R.color.bg));
        content=column();content.setPadding(dp(28),dp(24),dp(28),dp(28));scroll.addView(content);
        setContentView(scroll);
        Button back=button("‹ 返回",()->{if(busy || draft==null)finish();else confirmLeave();});
        back.setId(R.id.btn_back); // 程序化页面显式管理返回，不由基类覆盖编辑返回。
        content.addView(back);text(content,title,30,true); 
    }
    private void text(LinearLayout parent,String s,int size,boolean bold) {
        TextView t=new TextView(this);t.setText(s);t.setTextSize(size);t.setTextColor(getColor(R.color.txt));
        if(bold)t.setTypeface(null,Typeface.BOLD);t.setPadding(0,dp(10),0,dp(10));parent.addView(t);
    }
    private Button button(String label,Runnable run) {
        Button btn=new Button(this);btn.setText(label);btn.setTextSize(19);btn.setAllCaps(false);
        btn.setTextColor(getColor(R.color.txt));btn.setBackgroundResource(R.drawable.btn_ghost);
        btn.setMinHeight(dp(60));btn.setPadding(dp(16),dp(14),dp(16),dp(14));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(10);btn.setLayoutParams(p);
        btn.setOnClickListener(v->run.run());return btn;
    }
    private LinearLayout card(String title) {
        LinearLayout l=column();l.setBackgroundResource(R.drawable.card_bg);l.setPadding(dp(24),dp(16),dp(24),dp(20));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(18);content.addView(l,p);
        text(l,title,22,true);return l;
    }
    private void notifyError(Exception e){Toast.makeText(this,e.getMessage(),Toast.LENGTH_LONG).show();}
    private void setInputsEnabled(View v, boolean enabled) {
        if(v.getId()!=R.id.btn_back)v.setEnabled(enabled);
        if(v instanceof android.view.ViewGroup) {
            android.view.ViewGroup g=(android.view.ViewGroup)v;
            for(int i=0;i<g.getChildCount();i++)setInputsEnabled(g.getChildAt(i),enabled);
        }
    }
    private void readOnly(java.util.concurrent.Callable<String> task, String title) {
        if(busy)return;
        busy=true;setInputsEnabled(content,false);
        Toast.makeText(this,"正在后台读取，请稍候…",Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            String value;
            try{value=task.call();}catch(Exception ex){value="读取失败，请稍后重试";}
            final String result=value;
            main.post(() -> {
                if(isDestroyed() || isFinishing())return;
                busy=false;setInputsEnabled(content,true);
                new AlertDialog.Builder(this).setTitle(title).setMessage(result)
                        .setPositiveButton("知道了",null).show();
            });
        });
    }
    /** 先生成独立快照，后台 commit 成功后才更新列表与成功提示。 */
    private void persist(List<ExecutionRule> next, Runnable success, Runnable failure) {
        if(busy)return;
        final List<ExecutionRule> snapshot=new ArrayList<>();
        try{for(ExecutionRule r:next)snapshot.add(ExecutionRule.fromJson(r.toJson()));}
        catch(Exception ex){notifyError(ex);failure.run();return;}
        busy=true;setInputsEnabled(content,false);
        Toast.makeText(this,"正在保存规则…",Toast.LENGTH_SHORT).show();
        io.execute(() -> {
            Exception error=null;
            try{store.save(snapshot);}catch(Exception ex){error=ex;}
            final Exception result=error;
            main.post(() -> {
                if(isDestroyed() || isFinishing())return;
                busy=false;setInputsEnabled(content,true);
                if(result!=null){notifyError(result);failure.run();return;}
                rules.clear();rules.addAll(snapshot);
                Toast.makeText(this,"规则已保存",Toast.LENGTH_SHORT).show();success.run();
            });
        });
    }
    private void showList() {
        draft=null;screen("执行条件");
        text(content,"禁止优先 · 所有允许规则都需满足 · 状态未知时不执行受保护动作",17,false);
        if(broken) {
            text(content,"规则配置读取失败。为防止绕过限制，动作暂不执行。",20,true);
            content.addView(button("重置损坏的条件配置",()->new AlertDialog.Builder(this).setTitle("重置执行条件？")
                .setMessage("将删除所有条件规则，不会删除设备或按键动作绑定。")
                .setNegativeButton("取消",null).setPositiveButton("重置",(d,w)->
                    persist(Collections.emptyList(),()->{broken=false;showList();},()->{})).show()));
            return;
        }
        content.addView(button("＋ 新建自定义规则",()->edit(new ExecutionRule())));
        content.addView(button("使用预设：露营防误触",()->{
            ExecutionRule r=new ExecutionRule();r.name="露营防误触";
            r.conditions.add(new ExecutionRule.Condition("camp","eq","on"));
            r.actions.addAll(Arrays.asList("car_door_fl","car_door_fr","car_door_rl","car_door_rr"));edit(r);
        }));
        if(rules.isEmpty())text(content,"尚未添加规则。保存预设后才会生效。",18,false);
        for(ExecutionRule r:rules) {
            LinearLayout c=card(r.name);
            text(c,summary(r),18,false);
            SwitchCompat enabled=new SwitchCompat(this);enabled.setText("启用此规则");enabled.setTextSize(20);
            enabled.setMinHeight(dp(56));enabled.setChecked(r.enabled);c.addView(enabled);
            enabled.setOnCheckedChangeListener((v,on)->{boolean old=r.enabled;r.enabled=on;
                persist(new ArrayList<>(rules),this::showList,()->{r.enabled=old;showList();});});
            c.addView(button("编辑规则",()->{try{edit(ExecutionRule.fromJson(r.toJson()));}catch(Exception ex){notifyError(ex);}}));
            c.addView(button("删除规则",()->new AlertDialog.Builder(this).setTitle("删除 "+r.name+"？")
                .setMessage("只删除此条件规则，不删除设备和动作。").setNegativeButton("取消",null)
                .setPositiveButton("删除",(d,w)->{List<ExecutionRule> next=new ArrayList<>(rules);
                    next.remove(r);persist(next,this::showList,()->{});}).show()));
        }
    }
    private void rememberName(){if(name!=null && draft!=null)draft.name=name.getText().toString().trim();}
    private void edit(ExecutionRule r) {
        draft=r;screen("编辑执行条件");
        LinearLayout head=card("规则名称");
        name=new EditText(this);name.setText(r.name);name.setTextSize(22);name.setSingleLine(true);name.setMinHeight(dp(60));head.addView(name);
        SwitchCompat enabled=new SwitchCompat(this);enabled.setText("启用此规则");enabled.setTextSize(20);enabled.setMinHeight(dp(56));
        enabled.setChecked(r.enabled);head.addView(enabled);enabled.setOnCheckedChangeListener((v,on)->r.enabled=on);
        LinearLayout behavior=card("如何限制动作");
        RadioGroup mode=new RadioGroup(this);
        radio(mode,"条件满足时禁止",1,r.deny);radio(mode,"仅条件满足时允许",2,!r.deny);behavior.addView(mode);
        mode.setOnCheckedChangeListener((g,id)->r.deny=id==1);
        RadioGroup logic=new RadioGroup(this);
        radio(logic,"全部满足（且 / AND）",3,r.all);radio(logic,"任一满足（或 / OR）",4,!r.all);behavior.addView(logic);
        logic.setOnCheckedChangeListener((g,id)->r.all=id==3);
        LinearLayout cs=card("当这些条件");
        for(ExecutionRule.Condition c:new ArrayList<>(r.conditions)) {
            cs.addView(button(label(c)+"  ·  编辑",()->{rememberName();chooseCondition(c);}));
            cs.addView(button("移除此条件",()->{rememberName();r.conditions.remove(c);edit(r);}));
        }
        cs.addView(button("＋ 添加条件",()->{rememberName();chooseCondition(null);}));
        LinearLayout scope=card("设备与动作");
        scope.addView(button(r.devices.isEmpty()?"设备：全部蓝牙设备":"设备：已选择 "+r.devices.size()+" 台",()->{rememberName();chooseDevices();}));
        scope.addView(button("选择受限制动作 · 已选 "+r.actions.size()+" 项",()->{rememberName();chooseActions();}));
        for(String a:r.actions)text(scope,CustomAction.label(this,a),18,false);
        LinearLayout notes=card("检查规则（不控制车辆）");
        text(notes,"禁止只作用于选中的动作，其它动作照常。状态缺失或读取异常时不放行；原有驻车限制不能绕过。",18,false);
        text(notes,"车辆状态接口可能随系统版本变化，请先检查读值。露营/休憩只读取原车状态，不负责启动场景。",17,false);
        notes.addView(button("查看当前状态",()->{
            readOnly(() -> {
            ConditionStates s=new ConditionStates(this);
            return "露营："+display(s.read("camp"))+
                "\n休憩："+display(s.read("rest"))+"\n挡位："+display(s.read("gear"))+"\n车机时间："+display(s.read("time"));
            }, "当前读取结果");
        }));
        notes.addView(button("只读检查所选动作",()->checkDraft()));
        content.addView(button("保存规则",()->saveDraft()));
    }
    private String display(String s){return s==null?"未知 / 读取失败":"on".equals(s)?"开启":"off".equals(s)?"关闭":s;}
    private void radio(RadioGroup g,String text,int id,boolean selected) {
        RadioButton b=new RadioButton(this);b.setId(id);b.setText(text);b.setTextSize(20);b.setTextColor(getColor(R.color.txt));
        b.setMinHeight(dp(60));g.addView(b);if(selected)g.check(id);
    }
    private String label(ExecutionRule.Condition c) {
        String state="camp".equals(c.state)?"露营模式":"rest".equals(c.state)?"休憩模式":"gear".equals(c.state)?"挡位":"时间段";
        return state+("ne".equals(c.op)?" 不是 ":"time".equals(c.state)?" 位于 ":" 是 ")+display(c.value);
    }
    private String summary(ExecutionRule r){
        List<String> labels=new ArrayList<>();for(ExecutionRule.Condition c:r.conditions)labels.add(label(c));
        return String.join(r.all?" 且 ":" 或 ",labels)+" → "+(r.deny?"禁止":"仅满足时允许")+" "+r.actions.size()+" 个动作";
    }
    private void chooseCondition(ExecutionRule.Condition original) {
        String[] ids={"camp","rest","gear","time"}, labels={"露营模式","休憩模式","挡位（P / D / R / N）","时间段（支持跨午夜）"};
        new AlertDialog.Builder(this).setTitle("选择状态").setItems(labels,(d,index)->{
            String state=ids[index];
            if("time".equals(state)) {
                EditText input=new EditText(this);input.setText(original!=null&&"time".equals(original.state)?original.value:"22:00-07:00");
                input.setTextSize(22);
                AlertDialog dialog=new AlertDialog.Builder(this).setTitle("时间段 · HH:mm-HH:mm").setView(input)
                    .setNegativeButton("取消",null).setPositiveButton("确定",null).create();
                dialog.setOnShowListener(x->dialog.getButton(-1).setOnClickListener(v->{
                    ExecutionRule.Condition c=new ExecutionRule.Condition(state,"between",input.getText().toString().trim());
                    ExecutionRule test=new ExecutionRule();test.actions.add("test");test.conditions.add(c);
                    try{test.validate();replaceCondition(original,c);dialog.dismiss();}catch(Exception ex){notifyError(ex);}
                }));dialog.show();
            }else new AlertDialog.Builder(this).setTitle("比较方式").setItems(new String[]{"是","不是"},(d2,op)->{
                String[] vals="gear".equals(state)?new String[]{"P","D","R","N"}:new String[]{"on","off"};
                String[] names="gear".equals(state)?vals:new String[]{"开启","关闭"};
                new AlertDialog.Builder(this).setTitle("选择目标状态").setItems(names,(d3,v)->
                    replaceCondition(original,new ExecutionRule.Condition(state,op==0?"eq":"ne",vals[v]))).show();
            }).show();
        }).show();
    }
    private void replaceCondition(ExecutionRule.Condition old,ExecutionRule.Condition next) {
        if(old==null)draft.conditions.add(next);else draft.conditions.set(draft.conditions.indexOf(old),next);edit(draft);
    }
    private void chooseDevices() {
        List<BoundDevice> ds=new RuleStore(this).load();
        LinkedHashMap<String,String> labels=new LinkedHashMap<>();
        for(BoundDevice d:ds)labels.put(d.mac,d.name);
        for(String id:draft.devices)labels.putIfAbsent(id,"已移除设备 "+id);
        List<String> ids=new ArrayList<>(labels.keySet());String[] names=labels.values().toArray(new String[0]);
        boolean[] selected=new boolean[ids.size()];for(int i=0;i<ids.size();i++)selected[i]=draft.devices.contains(ids.get(i));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("设备范围 · 不选表示全部")
            .setMultiChoiceItems(names,selected,(d,i,on)->selected[i]=on).setNegativeButton("取消",null)
            .setPositiveButton("确定",(d,w)->{draft.devices.clear();for(int i=0;i<ids.size();i++)if(selected[i])draft.devices.add(ids.get(i));edit(draft);}).create();
        dialog.show();
    }
    private void chooseActions() {
        LinkedHashMap<String,String> options=new LinkedHashMap<>();
        for(ActionDef a:ActionCatalog.all())options.put(a.key,a.category+" · "+a.name);
        for(BoundDevice d:new RuleStore(this).load())for(List<String> list:d.gestureActions.values())for(String k:list)
            options.putIfAbsent(k,CustomAction.label(this,k));
        for(String k:draft.actions)options.putIfAbsent(k,CustomAction.label(this,k));
        List<String> ids=new ArrayList<>(options.keySet());boolean[] selected=new boolean[ids.size()];
        for(int i=0;i<ids.size();i++)selected[i]=draft.actions.contains(ids.get(i));
        new AlertDialog.Builder(this).setTitle("受限制的动作（可多选）")
            .setMultiChoiceItems(options.values().toArray(new String[0]),selected,(d,i,on)->selected[i]=on)
            .setNegativeButton("取消",null).setPositiveButton("确定",(d,w)->{
                draft.actions.clear();for(int i=0;i<ids.size();i++)if(selected[i])draft.actions.add(ids.get(i));edit(draft);
            }).show();
    }
    private List<ExecutionRule> withDraft() {
        List<ExecutionRule> next=new ArrayList<>();for(ExecutionRule r:rules)if(!r.id.equals(draft.id))next.add(r);next.add(draft);return next;
    }
    private void checkDraft() {
        rememberName();
        try{draft.validate();}catch(Exception ex){notifyError(ex);return;}
        final List<ExecutionRule> checkRules=withDraft();
        final List<String> checkActions=new ArrayList<>(draft.actions);
        readOnly(() -> {
        StringBuilder result=new StringBuilder();
        ConditionStates states=new ConditionStates(this);
        for(String a:checkActions) {
            ConditionEngine.Decision d=ConditionEngine.check(checkRules,null,a,states);
            result.append(CustomAction.label(this,a)).append("：").append(d.allowed?"条件通过":"不执行").append("\n").append(d.reason).append("\n\n");
        }
        return "无设备上下文，保守检查所有设备范围的规则；原有安全限制仍有效。\n\n"+result;
        }, "只读检查 · 不代表车辆已执行");
    }
    private void saveDraft() {
        rememberName();
        try {draft.validate();persist(withDraft(),this::showList,()->{});
        }catch(Exception ex){notifyError(ex);}
    }
    private void confirmLeave(){new AlertDialog.Builder(this).setTitle("放弃未保存的修改？")
        .setNegativeButton("继续编辑",null).setPositiveButton("放弃",(d,w)->showList()).show();}
    @Override public void onBackPressed(){if(busy)finish();else if(draft!=null)confirmLeave();else super.onBackPressed();}
    @Override protected void onDestroy(){io.shutdown();super.onDestroy();}
    @Override protected void onSaveInstanceState(Bundle out) {
        rememberName();
        if(draft!=null)try{out.putString("draft",draft.toJson().toString());}catch(Exception ignored){}
        super.onSaveInstanceState(out);
    }
}
