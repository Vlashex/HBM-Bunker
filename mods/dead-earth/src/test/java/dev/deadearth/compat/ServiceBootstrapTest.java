package dev.deadearth.compat;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class ServiceBootstrapTest {
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
