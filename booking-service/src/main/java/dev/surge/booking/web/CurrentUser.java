package dev.surge.booking.web;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** The signed-in user's id from the verified identity token ({@link CurrentUserArgumentResolver}). */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface CurrentUser {

    /**
     * Also require the gateway's statement that the user's email is verified (S5): 403 {@code email-unverified}
     * otherwise, before the request body is even read.
     */
    boolean verifiedEmail() default false;
}
