package com.selfclink.ble.automation;
import java.util.*;
/** 纯逻辑求值器，不控制车辆。状态 null 为未知，任何相关未知条件都不放行。 */
public final class ConditionEngine {
    public interface States { String read(String key); }
    public static final class Decision {
        public final boolean allowed; public final String reason;
        Decision(boolean allowed,String reason){this.allowed=allowed;this.reason=reason;}
    }
    public static Decision check(List<ExecutionRule> rules,String device,String action,States states) {
        String blocked = null;
        for(ExecutionRule r:rules) {
            if(!r.enabled || !r.actions.contains(action))continue;
            // 无设备上下文（编排页测试）保守评估所有覆盖此动作的规则。
            if(device!=null && !r.devices.isEmpty() && !r.devices.contains(device))continue;
            try { r.validate(); } catch(RuntimeException ex) {
                return new Decision(false,r.name+"：规则无效");
            }
            boolean match=r.all;
            for(ExecutionRule.Condition c:r.conditions) {
                String actual=states.read(c.state);
                if(actual==null)return new Decision(false,r.name+"："+c.state+" 状态未知，暂不执行");
                boolean v;
                if("time".equals(c.state)) {
                    int now=ExecutionRule.minutes(actual), start=ExecutionRule.minutes(c.value.substring(0,5)),
                        end=ExecutionRule.minutes(c.value.substring(6));
                    v=start<end ? now>=start && now<end : now>=start || now<end;
                } else { v=actual.equals(c.value); if("ne".equals(c.op))v=!v; }
                match=r.all ? match && v : match || v;
            }
            if(r.deny && match)return new Decision(false,r.name+"：命中禁止条件");
            if(!r.deny && !match)blocked=r.name+"：不满足允许条件";
        }
        return new Decision(blocked==null,blocked==null?"自定义条件通过（仍需原有安全检查）":blocked);
    }
}
