package dev.surge.notification.user;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** The slice of auth-service's user view that notification needs. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record UserView(String id, String email) {
}
