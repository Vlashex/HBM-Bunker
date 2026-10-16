package dev.deadearth.compat.fixture;

import java.util.ServiceLoader;

public interface ProbeService {
    ProbeService INSTANCE = ServiceLoader.load(ProbeService.class).findFirst().orElseThrow();
}
