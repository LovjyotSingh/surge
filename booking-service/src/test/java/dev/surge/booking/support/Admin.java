package dev.surge.booking.support;

import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/** The identity the gateway would sign for an administrator ({@code u-admin}, roles USER and ADMIN). */
public final class Admin {
    private Admin() {
    }

    public static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request) {
        return request.with(Identity.admin());
    }
}
