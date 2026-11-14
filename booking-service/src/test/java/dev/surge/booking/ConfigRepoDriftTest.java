package dev.surge.booking;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

/** config-repo/booking-service.yml wins when the config server is up; booking's own file is the fallback. They must not drift. */
class ConfigRepoDriftTest {

    private static Map<String, Object> flat(String path) throws IOException {
        Map<String, Object> out = new HashMap<>();
        for (PropertySource<?> source : new YamlPropertySourceLoader().load("yml", new FileSystemResource(Path.of(path)))) {
            EnumerablePropertySource<?> enumerable = (EnumerablePropertySource<?>) source;
            for (String name : enumerable.getPropertyNames()) {
                out.put(name, String.valueOf(enumerable.getProperty(name)));
            }
        }
        return out;
    }

    @Test
    void everySurgeKeyInConfigRepoMatchesBookingsOwnDefaults() throws IOException {
        Map<String, Object> repo = flat("../config-repo/booking-service.yml");
        Map<String, Object> local = flat("src/main/resources/application.yml");

        Map<String, Object> surgeRepo = new HashMap<>();
        repo.forEach((k, v) -> {
            if (k.startsWith("surge.")) {
                surgeRepo.put(k, v);
            }
        });

        assertThat(surgeRepo).isNotEmpty();
        surgeRepo.forEach((key, value) -> assertThat(local).as("key %s in booking's application.yml", key).containsEntry(key, value));
    }
}
