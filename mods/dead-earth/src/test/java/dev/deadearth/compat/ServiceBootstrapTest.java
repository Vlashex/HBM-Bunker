package dev.deadearth.compat;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ServiceBootstrapTest {
    @Test void serviceLookupFindsProviderWithRestrictedWorkerContextWithoutChangingIt() {
        var thread=Thread.currentThread();var before=thread.getContextClassLoader();var worker=ClassLoader.getPlatformClassLoader();thread.setContextClassLoader(worker);
        try {
            var type=dev.deadearth.compat.fixture.ProbeService.class;
            assertTrue(java.util.ServiceLoader.load(type).findFirst().isEmpty());
            assertEquals("available",ServiceBootstrap.services(type).findFirst().orElseThrow().toString());
            assertSame(worker,thread.getContextClassLoader());
        } finally {thread.setContextClassLoader(before);}
    }
    @Test void initializesServiceWithItsOwnLoaderAndRestoresWorkerContext() throws Exception {
        var worker = ClassLoader.getPlatformClassLoader();
        var original = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(worker);
        try {
            ServiceBootstrap.initialize("dev.deadearth.compat.fixture.ProbeService");
            var type = Class.forName("dev.deadearth.compat.fixture.ProbeService");
            assertEquals("available", type.getField("INSTANCE").get(null).toString());
            assertSame(worker, Thread.currentThread().getContextClassLoader());
        } finally { Thread.currentThread().setContextClassLoader(original); }
    }
}
