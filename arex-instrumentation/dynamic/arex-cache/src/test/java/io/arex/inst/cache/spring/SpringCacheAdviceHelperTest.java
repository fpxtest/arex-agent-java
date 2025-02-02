package io.arex.inst.cache.spring;

import static org.junit.jupiter.api.Assertions.*;

import io.arex.inst.runtime.config.ConfigBuilder;
import io.arex.inst.runtime.context.ContextManager;
import io.arex.inst.runtime.model.DynamicClassEntity;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class SpringCacheAdviceHelperTest {
    @BeforeAll
    static void setUp() {
        Mockito.mockStatic(ContextManager.class);
    }

    @AfterAll
    static void tearDown() {
        Mockito.clearAllCaches();
    }

    @Test
    void needRecordOrReplay() throws NoSuchMethodException {
        final Method method1 = SpringCacheAdviceHelperTest.class.getDeclaredMethod("method1");
        final ConfigBuilder configBuilder = ConfigBuilder.create("test");
        configBuilder.build();

        // not need record or replay
        Mockito.when(ContextManager.needRecordOrReplay()).thenReturn(false);
        final boolean needRecordOrReplay = SpringCacheAdviceHelper.needRecordOrReplay(method1);
        assertFalse(needRecordOrReplay);

        // null method
        final boolean nullMethod = SpringCacheAdviceHelper.needRecordOrReplay(null);
        assertFalse(nullMethod);

        Mockito.when(ContextManager.needRecordOrReplay()).thenReturn(true);
        // no dynamic class
        final boolean noDynamicClass = SpringCacheAdviceHelper.needRecordOrReplay(method1);
        assertFalse(noDynamicClass);

        // has dynamic class, but not contains method
        final List<DynamicClassEntity> entities = new ArrayList<>();
        entities.add(new DynamicClassEntity("io.arex.inst.cache.spring.SpringCacheAdviceHelperTest", null, null, null));
        configBuilder.dynamicClassList(entities).build();
        final boolean notContainsMethod = SpringCacheAdviceHelper.needRecordOrReplay(method1);
        assertFalse(notContainsMethod);

        // has no args dynamic class, and contains method
        entities.add(new DynamicClassEntity("io.arex.inst.cache.spring.SpringCacheAdviceHelperTest", "method1", null, null));
        configBuilder.dynamicClassList(entities).build();
        final boolean containsMethod = SpringCacheAdviceHelper.needRecordOrReplay(method1);
        assertTrue(containsMethod);

        // has args dynamic class, and contains method
        final Method method2 = SpringCacheAdviceHelperTest.class.getDeclaredMethod("method2",
                String.class);
        entities.add(new DynamicClassEntity("io.arex.inst.cache.spring.SpringCacheAdviceHelperTest", "method2", "java.lang.String", null));
        configBuilder.dynamicClassList(entities).build();
        final boolean containsMethod2 = SpringCacheAdviceHelper.needRecordOrReplay(method2);
        assertTrue(containsMethod2);
    }

    public void method1() {

    }

    public void method2(String arg1) {

    }
}