package dev.surge.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;

class ProblemsTest {

    @AfterEach
    void clearMdc() {
        MDC.remove(RequestIdFilter.MDC_KEY);
    }

    @Test
    void ofSetsTypeTitleAndRequestId() {
        MDC.put(RequestIdFilter.MDC_KEY, "r-1");
        ProblemDetail pd = Problems.of(HttpStatus.CONFLICT, "seat-taken", "Seat taken", "gone");
        assertThat(pd.getType()).isEqualTo(URI.create("https://surge.dev/problems/seat-taken"));
        assertThat(pd.getTitle()).isEqualTo("Seat taken");
        assertThat(pd.getProperties()).containsEntry("requestId", "r-1");
    }

    @Test
    void conformFillsABlankTypeAndTheRequestId() {
        MDC.put(RequestIdFilter.MDC_KEY, "r-2");
        ProblemDetail pd = ProblemDetail.forStatus(HttpStatus.NOT_FOUND); // type defaults to about:blank
        Problems.conform(pd, "not-found");
        assertThat(pd.getType()).isEqualTo(URI.create("https://surge.dev/problems/not-found"));
        assertThat(pd.getProperties()).containsEntry("requestId", "r-2");
    }

    @Test
    void conformKeepsAnExistingType() {
        ProblemDetail pd = Problems.of(HttpStatus.BAD_REQUEST, "malformed-request", "Malformed", "x");
        Problems.conform(pd, "validation-failed");
        assertThat(pd.getType()).isEqualTo(URI.create("https://surge.dev/problems/malformed-request"));
        assertThat(pd.getProperties()).containsEntry("requestId", "");
    }
}
