package dev.surge.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import dev.surge.auth.support.IntegrationTest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

@IntegrationTest
class JwksTest {

    @Autowired
    MockMvc mvc;

    @Test
    void exposesExactlyOnePublicRsaKeyAndNoPrivateMaterial() throws Exception {
        String json = mvc.perform(get("/api/auth/.well-known/jwks.json")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Map<String, Object>> keys = JsonPath.read(json, "$.keys");
        assertThat(keys).hasSize(1);
        Map<String, Object> key = keys.get(0);
        assertThat(key).containsEntry("kty", "RSA").containsEntry("alg", "RS256").containsEntry("use", "sig");
        assertThat((String) key.get("kid")).isNotBlank();
        assertThat(key).doesNotContainKeys("d", "p", "q", "dp", "dq", "qi");
    }
}
