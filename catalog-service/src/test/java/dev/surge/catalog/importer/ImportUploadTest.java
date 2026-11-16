package dev.surge.catalog.importer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.surge.catalog.support.Identity;
import dev.surge.catalog.support.IntegrationTest;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.Part;
import java.io.IOException;
import java.util.Collection;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MultipartHttpServletRequest;
import org.springframework.web.multipart.MultipartResolver;

/**
 * SEC-24: an upload is parsed only once the import controller asks for the file, after the identity chain and the
 * admin interceptor have let the caller through. A non-admin's (possibly huge) body is never parsed.
 */
@IntegrationTest
class ImportUploadTest {

    private static final String IMPORT = "/api/catalog/admin/events/import";
    private static final String MULTIPART = "multipart/form-data; boundary=surge-boundary";

    @Autowired
    MockMvc mvc;

    /** The context's resolver, a spy delegating to the real one (see IntegrationTest). */
    @Autowired
    MultipartResolver resolver;

    @Test
    void theMultipartResolverParsesOnlyWhenTheFileIsAskedFor() {
        PartCountingRequest request = new PartCountingRequest();
        assertThat(resolver.isMultipart(request)).isTrue();

        MultipartHttpServletRequest resolved = resolver.resolveMultipart(request);
        assertThat(request.partReads).as("nothing parsed while resolving (resolve-lazily)").isZero();

        resolved.getFileMap();
        assertThat(request.partReads).as("parsed on first access").isEqualTo(1);
    }

    @Test
    void aNonAdminUploadIsRefusedBeforeItsBodyIsTouched() throws Exception {
        byte[] overTheLimit = new byte[7 * 1024 * 1024]; // max-request-size is 6 MB
        mvc.perform(post(IMPORT).contentType(MULTIPART).content(overTheLimit).with(Identity.user("u-1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://surge.dev/problems/forbidden"));
        mvc.perform(post(IMPORT).contentType(MULTIPART).content(overTheLimit).with(Identity.anonymous()))
                .andExpect(status().isUnauthorized());
        verify(resolver, never()).resolveMultipart(any());
    }

    @Test
    void anAdminUploadReachesTheResolver() throws Exception {
        // the control for the test above: this request does reach multipart resolution (and lacks a file part)
        mvc.perform(post(IMPORT).contentType(MULTIPART).content(new byte[16]).with(Identity.admin()))
                .andExpect(status().isBadRequest());
        verify(resolver).resolveMultipart(any());
    }

    /** A raw multipart request that records every attempt to parse its parts. */
    private static final class PartCountingRequest extends MockHttpServletRequest {

        int partReads;

        PartCountingRequest() {
            super("POST", IMPORT);
            setContentType(MULTIPART);
        }

        @Override
        public Collection<Part> getParts() throws IOException, ServletException {
            partReads++;
            return super.getParts();
        }
    }
}
