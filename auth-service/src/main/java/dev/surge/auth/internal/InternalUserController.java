package dev.surge.auth.internal;

import dev.surge.auth.api.AuthController;
import dev.surge.auth.api.UserLookup;
import dev.surge.auth.api.UserView;
import dev.surge.auth.error.UserNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** Service-to-service lookup; the gateway has no route to /internal. */
@RestController
public class InternalUserController {
    private final UserLookup lookup;

    InternalUserController(UserLookup lookup) {
        this.lookup = lookup;
    }

    @GetMapping("/internal/users/{id}")
    UserView get(@PathVariable String id) {
        if (!AuthController.USER_ID.matcher(id).matches()) {
            throw new UserNotFoundException();
        }
        return lookup.find(id);
    }
}
