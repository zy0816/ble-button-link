package com.selfclink.ble.vehicle;
import com.selfclink.ble.automation.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class SceneStateDecoderTest {
    @Test public void observedOnOffPair() {
        assertEquals("on", SceneStateDecoder.camping("3"));
        assertEquals("off", SceneStateDecoder.camping("0"));
    }
    @Test public void unknownMustNotLookClosed() {
        for (String s : Arrays.asList(null,"","1","2","4","255","-1","bad"))
            assertNull(SceneStateDecoder.camping(s));
    }
    @Test public void campingDoorProtectionUsesDecodedState() {
        ExecutionRule r=new ExecutionRule();r.actions.add("car_door_fl");
        r.conditions.add(new ExecutionRule.Condition("camp","eq","on"));
        for(String value : Arrays.asList("3","1","2","255")) {
            assertFalse(ConditionEngine.check(Arrays.asList(r),"test","car_door_fl",
                    key->SceneStateDecoder.camping(value)).allowed);
        }
        assertTrue(ConditionEngine.check(Arrays.asList(r),"test","car_door_fl",
                key->SceneStateDecoder.camping("0")).allowed);
        assertTrue(ConditionEngine.check(Arrays.asList(r),"test","sys_volume_up",
                key->SceneStateDecoder.camping("3")).allowed);
    }
}
