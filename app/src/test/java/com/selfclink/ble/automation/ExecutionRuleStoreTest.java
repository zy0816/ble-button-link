package com.selfclink.ble.automation;
import android.content.Context;
import android.content.SharedPreferences;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
public class ExecutionRuleStoreTest {
    private ExecutionRuleStore store(String json,boolean commit) {
        Context c=mock(Context.class);SharedPreferences p=mock(SharedPreferences.class);
        SharedPreferences.Editor e=mock(SharedPreferences.Editor.class);
        when(c.getApplicationContext()).thenReturn(c);when(c.getSharedPreferences(anyString(),anyInt())).thenReturn(p);
        when(p.getString("rules","[]")).thenReturn(json);when(p.edit()).thenReturn(e);
        when(e.putString(anyString(),anyString())).thenReturn(e);when(e.commit()).thenReturn(commit);
        return new ExecutionRuleStore(c);
    }
    @Test public void emptyStoreHasNoRestrictions(){assertTrue(store("[]",true).load().isEmpty());}
    @Test public void corruptStoreNeverBecomesEmpty() {
        for(String s:Arrays.asList("bad json","[{}]","[null]")) {
            try{store(s,true).load();fail(s);}catch(IllegalStateException expected){}
        }
    }
    @Test public void saveFailureReported(){try{store("[]",false).save(Collections.emptyList());fail();}
        catch(IllegalStateException expected){}}
    @Test public void draftMayRestoreButCannotSave() throws Exception {
        ExecutionRule r=new ExecutionRule();
        ExecutionRule draft=ExecutionRule.fromJson(r.toJson(),false);
        assertTrue(draft.conditions.isEmpty());
        try{store("[]",true).save(Arrays.asList(draft));fail();}catch(IllegalArgumentException expected){}
    }
}
