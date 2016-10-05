package io.fabric8.agent.region;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class SubsystemTest {
    @Test
    public void requireFabricBundle() throws Exception {
        //given
        Subsystem subsystem = new Subsystem("ENTESB-5095");
        final String dependency = "true";
        final String startLevel = "50";
        final String start = "false";
        String requirement = "fabric:wrap:mvn:org.apache.poi/ooxml-security/1.0$Export-Package=org.etsi.uri.x01903.v14;version=1.0;;start=" + start + ";start-level=" + startLevel + ";dependency=" + dependency;
        assertTrue("Requirement is a fabric bundle!",Subsystem.FabricBundle.isFabricBundle(requirement));
        
        //when
        subsystem.require("bundle:"+requirement);
        
        //then
        List<Subsystem.FabricBundle> fabricBundles = subsystem.getFabricBundles();
        assertEquals(1,fabricBundles.size());
        Subsystem.FabricBundle fabricBundle = fabricBundles.get(0);
        assertEquals("wrap:mvn:org.apache.poi/ooxml-security/1.0$Export-Package=org.etsi.uri.x01903.v14;version=1.0",fabricBundle.getLocation());
        assertEquals(start,fabricBundle.getProperty("start"));
        assertEquals(startLevel,fabricBundle.getProperty("start-level"));
        assertEquals(dependency,fabricBundle.getProperty("dependency"));
    }
}