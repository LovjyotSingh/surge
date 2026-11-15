package dev.surge.common.security;

import org.springframework.core.env.Environment;

/**
 * {@code surge.env} (env {@code SURGE_ENV}): exactly {@code dev} (the default, local runs and tests) or {@code prod}.
 * In {@code prod} nothing may fall back to a committed development default; anything else is refused at startup.
 * {@code surge.security.public-deployment=true} (env {@code SURGE_PUBLIC_DEPLOYMENT}, set by the prod overlay as a
 * belt-and-braces check) refuses {@code dev}. {@link SurgeEnvironmentAutoConfiguration} applies both rules to every
 * application that has {@code common} on its classpath.
 */
public enum SurgeEnv {
    DEV, PROD;

    public static final String PROPERTY = "surge.env";
    public static final String PUBLIC_DEPLOYMENT = "surge.security.public-deployment";

    public static SurgeEnv from(Environment environment) {
        SurgeEnv env = parse(environment.getProperty(PROPERTY, "dev"));
        if (env == DEV && environment.getProperty(PUBLIC_DEPLOYMENT, Boolean.class, false)) {
            throw new IllegalStateException("SURGE_ENV=dev is not allowed when surge.security.public-deployment=true; "
                    + "set SURGE_ENV=prod");
        }
        return env;
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
