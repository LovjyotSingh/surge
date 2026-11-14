package dev.surge.notification.ticket;

import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/** Ticket PDFs in S3, one object per order. Putting the same order again overwrites it. */
@Component
public class TicketStore {

    private final S3Client s3;
    private final String bucket;

    public TicketStore(S3Client s3, @Value("${surge.s3.bucket:surge-tickets}") String bucket) {
        this.s3 = s3;
        this.bucket = bucket;
    }

    public String put(long orderId, byte[] pdf) {
        String key = key(orderId);
        s3.putObject(PutObjectRequest.builder().bucket(bucket).key(key).contentType("application/pdf").build(),
                RequestBody.fromBytes(pdf));
        return key;
    }

    public Optional<byte[]> get(long orderId) {
        try {
            return Optional.of(s3.getObjectAsBytes(
                    GetObjectRequest.builder().bucket(bucket).key(key(orderId)).build()).asByteArray());
        } catch (NoSuchKeyException e) {
            return Optional.empty();
        }
    }

    static String key(long orderId) {
        return "tickets/" + orderId + ".pdf";
    }
}
