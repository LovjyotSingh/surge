package dev.surge.common.security;

import org.springframework.core.env.Environment;

/**
 * {@code surge.env} (env {@code SURGE_ENV}): exactly {@code dev} (the default, local runs and tests) or {@code prod}.
 * In {@code prod} nothing may fall back to a committed development default; anything else is refused at startup.
 */
public enum SurgeEnv {
    DEV, PROD;

    public static final String PROPERTY = "surge.env";

    public static SurgeEnv from(Environment environment) {
        return parse(environment.getProperty(PROPERTY, "dev"));
    }

    public static SurgeEnv parse(String value) {
        if ("dev".equals(value)) {
            return DEV;
        }
        if ("prod".equals(value)) {
            return PROD;
        }
        throw new IllegalStateException("SURGE_ENV must be exactly 'dev' or 'prod'");
    }
}
