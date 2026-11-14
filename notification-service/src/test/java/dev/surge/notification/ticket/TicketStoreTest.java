package dev.surge.notification.ticket;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.surge.notification.support.IntegrationTest;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

@IntegrationTest
class TicketStoreTest {

    @Autowired
    TicketStore store;
    @Autowired
    S3Client s3;
    @Value("${surge.s3.bucket}")
    String bucket;

    @Test
    void bucketIsCreatedAtStartup() {
        s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
    }

    @Test
    void putThenGetRoundTrips() {
        byte[] pdf = "%PDF-fake-bytes".getBytes();

        String key = store.put(9001L, pdf);

        assertThat(key).isEqualTo("tickets/9001.pdf");
        assertThat(store.get(9001L)).hasValueSatisfying(b -> assertThat(b).isEqualTo(pdf));
        assertThat(s3.headObject(HeadObjectRequest.builder().bucket(bucket).key(key).build()).contentType())
                .isEqualTo("application/pdf");
    }

    @Test
    void missingKeyIsEmpty() {
        assertThat(store.get(987654321L)).isEqualTo(Optional.empty());
    }

    @Test
    void otherS3ErrorsAreNotSwallowed() {
        S3Client broken = mock(S3Client.class);
        when(broken.getObjectAsBytes(ArgumentMatchers.any(GetObjectRequest.class)))
                .thenThrow(S3Exception.builder().statusCode(500).message("boom").build());

        assertThatThrownBy(() -> new TicketStore(broken, bucket).get(1L)).isInstanceOf(S3Exception.class);
    }
}
