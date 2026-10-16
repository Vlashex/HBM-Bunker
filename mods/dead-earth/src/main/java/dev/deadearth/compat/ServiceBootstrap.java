package dev.deadearth.compat;

public final class ServiceBootstrap {
    private ServiceBootstrap() {}
    public static void initialize(String name) throws ClassNotFoundException {
        var type = Class.forName(name, false, ServiceBootstrap.class.getClassLoader());
        var thread = Thread.currentThread();
        var previous = thread.getContextClassLoader();
        try {
            thread.setContextClassLoader(type.getClassLoader());
            Class.forName(name, true, type.getClassLoader());
        } finally { thread.setContextClassLoader(previous); }
    }
}
