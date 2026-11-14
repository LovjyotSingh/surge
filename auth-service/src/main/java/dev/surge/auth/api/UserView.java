package dev.surge.auth.api;

import java.util.List;

public record UserView(String id, String email, String displayName, List<String> roles) {
}
