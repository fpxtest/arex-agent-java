package io.arex.inst.cache;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CacheModuleInstrumentationTest {
    @Test
    void testInstrumentationTypes() {
        assertEquals(2, new CacheModuleInstrumentation().instrumentationTypes().size());
    }

}