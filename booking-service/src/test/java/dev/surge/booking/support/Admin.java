package dev.surge.booking.support;

import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** Identity headers the gateway would set for an administrator. */
public final class Admin {
    private Admin() {
    }

    public static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request) {
        return request.header("X-User-Id", "u-admin").header("X-User-Roles", "USER,ADMIN");
    }
}
