package com.selfclink.ble.automation;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public final class ConditionEngineTest {
    private ExecutionRule rule(boolean deny,boolean all,String... states) {
        ExecutionRule r=new ExecutionRule();r.deny=deny;r.all=all;r.actions.add("door");
        for(String s:states)r.conditions.add(new ExecutionRule.Condition(s,"eq","camp".equals(s)||"rest".equals(s)?"on":"P"));
        return r;
    }
    private ConditionEngine.Decision check(List<ExecutionRule> rs,String device,String action,String camp,String gear) {
        return ConditionEngine.check(rs,device,action,key->"camp".equals(key)?camp:"gear".equals(key)?gear:null);
    }
    @Test public void campingProtectsDoorsNotVolume() {
        ExecutionRule r=rule(true,true,"camp");
        assertFalse(check(Arrays.asList(r),"A","door","on","P").allowed);
        assertTrue(check(Arrays.asList(r),"A","door","off","P").allowed);
        assertTrue(check(Arrays.asList(r),"A","volume","on","P").allowed);
    }
    @Test public void allVersusAny() {
        ExecutionRule r=rule(true,true,"camp","gear");
        assertTrue(check(Arrays.asList(r),"A","door","on","D").allowed);
        r.all=false;assertFalse(check(Arrays.asList(r),"A","door","on","D").allowed);
    }
    @Test public void onlyAllowWhenMatching() {
        ExecutionRule r=rule(false,true,"gear");
        assertTrue(check(Arrays.asList(r),"A","door","off","P").allowed);
        assertFalse(check(Arrays.asList(r),"A","door","off","D").allowed);
    }
    @Test public void denyOverridesAllowInEitherOrder() {
        ExecutionRule d=rule(true,true,"camp"), a=rule(false,true,"gear");
        assertFalse(check(Arrays.asList(a,d),"A","door","on","P").allowed);
        assertFalse(check(Arrays.asList(d,a),"A","door","on","P").allowed);
    }
    @Test public void everyAllowRuleMustMatch() {
        assertFalse(check(Arrays.asList(rule(false,true,"gear"),rule(false,true,"camp")),"A","door","off","P").allowed);
    }
    @Test public void unknownNeverBecomesFalseOrBypassesOr() {
        ExecutionRule r=rule(true,false,"camp","gear");
        assertFalse(check(Arrays.asList(r),"A","door",null,"D").allowed);
        r.deny=false;assertFalse(check(Arrays.asList(r),"A","door",null,"P").allowed);
        r.conditions.get(0).op="ne";assertFalse(check(Arrays.asList(r),"A","door",null,"P").allowed);
    }
    @Test public void deviceScopeAndConservativeNoContext() {
        ExecutionRule r=rule(true,true,"camp");r.devices.add("A");
        assertFalse(check(Arrays.asList(r),"A","door","on","P").allowed);
        assertTrue(check(Arrays.asList(r),"B","door","on","P").allowed);
        assertFalse(check(Arrays.asList(r),null,"door","on","P").allowed);
    }
    @Test public void disabledAndNoRulesDontAlterLegacyBehavior() {
        ExecutionRule r=rule(true,true,"camp");r.enabled=false;
        assertTrue(check(Arrays.asList(r),"A","door",null,null).allowed);
        assertTrue(check(Collections.emptyList(),"A","door",null,null).allowed);
    }
    @Test public void overnightTimeIncludesStartExcludesEnd() {
        ExecutionRule r=rule(true,true);r.conditions.add(new ExecutionRule.Condition("time","between","22:00-07:00"));
        for(String t:Arrays.asList("22:00","23:59","00:00","06:59"))
            assertFalse(ConditionEngine.check(Arrays.asList(r),"A","door",k->t).allowed);
        for(String t:Arrays.asList("07:00","12:00","21:59"))
            assertTrue(ConditionEngine.check(Arrays.asList(r),"A","door",k->t).allowed);
    }
    @Test public void daytimeAndNotEqual() {
        ExecutionRule r=rule(true,true);r.conditions.add(new ExecutionRule.Condition("time","between","09:00-17:00"));
        assertTrue(ConditionEngine.check(Arrays.asList(r),"A","door",k->"08:59").allowed);
        assertFalse(ConditionEngine.check(Arrays.asList(r),"A","door",k->"12:00").allowed);
        assertTrue(ConditionEngine.check(Arrays.asList(r),"A","door",k->"17:00").allowed);
        r=rule(true,true,"gear");r.conditions.get(0).op="ne";
        assertFalse(check(Arrays.asList(r),"A","door","off","D").allowed);
        assertTrue(check(Arrays.asList(r),"A","door","off","P").allowed);
    }
    @Test public void invalidRulesFailClosed() {
        ExecutionRule r=rule(true,true);
        assertFalse(check(Arrays.asList(r),"A","door","on","P").allowed);
        r.conditions.add(new ExecutionRule.Condition("invented","eq","on"));
        assertFalse(check(Arrays.asList(r),"A","door","on","P").allowed);
    }
    @Test public void serializationPreservesAllSelections() throws Exception {
        ExecutionRule r=rule(false,false,"camp","gear");r.name="自定义";r.devices.add("A");r.actions.add("volume");
        ExecutionRule copy=ExecutionRule.fromJson(new org.json.JSONObject(r.toJson().toString()));
        assertEquals(r.id,copy.id);assertEquals(r.name,copy.name);assertEquals(r.devices,copy.devices);
        assertEquals(r.actions,copy.actions);assertFalse(copy.deny);assertFalse(copy.all);
        assertEquals(2,copy.conditions.size());
    }
    @Test public void invalidTimesRejected() {
        for(String time:Arrays.asList("24:00-07:00","22:60-07:00","07:00-07:00","x","7:00-09:00")) {
            ExecutionRule r=rule(true,true);r.conditions.add(new ExecutionRule.Condition("time","between",time));
            try{r.validate();fail(time);}catch(IllegalArgumentException expected){}
        }
    }
}
