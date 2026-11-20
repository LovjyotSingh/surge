package dev.surge.smoke;

import java.time.Duration;

/** One end-to-end check. {@link #run} returns a short detail on success and throws {@link CheckFailure} otherwise. */
public interface Check {

    String name();

    /** The whole check is abandoned (and fails) after this long. */
    default Duration timeout() {
        return Duration.ofSeconds(60);
    }

    String run(SmokeContext ctx) throws Exception;
}
