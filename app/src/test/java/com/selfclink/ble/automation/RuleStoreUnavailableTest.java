package com.selfclink.ble.automation;
import android.content.Context;
import android.content.SharedPreferences;
import java.lang.reflect.Field;
import java.util.*;
import org.junit.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

/** 模拟密钥初始化永远未完成；不能等待、误报成功或用空读取覆盖数据。 */
public class RuleStoreUnavailableTest {
    private Object oldPrefs, oldStarted;
    private SharedPreferences p;
    private final List<String> failures=new ArrayList<>();
    private void set(String name,Object value)throws Exception {
        Field f=RuleStore.class.getDeclaredField(name);f.setAccessible(true);f.set(null,value);
    }
    private Object get(String name)throws Exception {
        Field f=RuleStore.class.getDeclaredField(name);f.setAccessible(true);return f.get(null);
    }
    @Before public void setup()throws Exception {
        oldPrefs=get("sPrefs");oldStarted=get("started");set("started",true);set("sPrefs",null);
        p=mock(SharedPreferences.class);
    }
    @After public void restore()throws Exception {set("sPrefs",oldPrefs);set("started",oldStarted);}
    private RuleStore store() {
        Context c=mock(Context.class);when(c.getApplicationContext()).thenReturn(c);
        return new RuleStore(c,failures::add);
    }
    @Test(timeout=1000) public void unavailableWritesReturnImmediatelyAndReportFailure() {
        RuleStore s=store();
        assertFalse(s.save(Collections.emptyList()));
        assertFalse(s.upsert(new BoundDevice("A","p","test",null)));
        assertFalse(s.remove("A"));
        assertEquals(3,failures.size());verifyNoInteractions(p);
    }
    @Test public void corruptedExistingDataCannotBeOverwritten()throws Exception {
        set("sPrefs",p);when(p.getString("devices","[]")).thenReturn("broken");
        assertFalse(store().upsert(new BoundDevice("A","p","test",null)));
        assertFalse(store().remove("A"));verify(p,never()).edit();
    }
    @Test public void successfulUpsertPreservesOtherDevices()throws Exception {
        set("sPrefs",p);
        BoundDevice other=new BoundDevice("B","p","other",null);
        when(p.getString("devices","[]")).thenReturn(new org.json.JSONArray().put(other.toJson()).toString());
        SharedPreferences.Editor e=mock(SharedPreferences.Editor.class);when(p.edit()).thenReturn(e);
        when(e.putString(anyString(),anyString())).thenReturn(e);
        assertTrue(store().upsert(new BoundDevice("A","p","new",null)));
        org.mockito.ArgumentCaptor<String> arg=org.mockito.ArgumentCaptor.forClass(String.class);
        verify(e).putString(eq("devices"),arg.capture());verify(e).apply();
        org.json.JSONArray saved=new org.json.JSONArray(arg.getValue());
        assertEquals(2,saved.length());assertEquals("B",saved.getJSONObject(0).getString("mac"));
    }
}
