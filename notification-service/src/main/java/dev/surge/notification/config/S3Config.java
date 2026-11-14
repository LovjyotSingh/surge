package dev.surge.notification.config;

import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.model.BucketAlreadyExistsException;
import software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

/** S3 client (Adobe S3Mock locally, real S3 by changing env only) and the ticket bucket. */
@Configuration(proxyBeanMethods = false)
public class S3Config {

    private static final Logger log = LoggerFactory.getLogger(S3Config.class);

    @Bean
    S3Client s3Client(@Value("${surge.s3.endpoint:}") String endpoint,
                      @Value("${surge.s3.region:ap-south-1}") String region,
                      @Value("${surge.s3.access-key:surge-local}") String accessKey,
                      @Value("${surge.s3.secret-key:surge-local}") String secretKey) {
        S3ClientBuilder builder = S3Client.builder()
                .httpClientBuilder(UrlConnectionHttpClient.builder())
                .region(Region.of(region))
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .forcePathStyle(true)
                // S3-compatible servers do not all understand the SDK's default trailing checksums.
                .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
        if (!endpoint.isBlank()) {
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    /** Creates the bucket if it is missing. Startup fails if S3 is unreachable (the service is useless without it). */
    @Bean
    InitializingBean ticketBucket(S3Client s3, @Value("${surge.s3.bucket:surge-tickets}") String bucket) {
        return () -> {
            try {
                s3.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            } catch (S3Exception e) {
                if (e.statusCode() != 404) {
                    throw e;
                }
                try {
                    s3.createBucket(CreateBucketRequest.builder().bucket(bucket).build());
                    log.info("Created S3 bucket {}", bucket);
                } catch (BucketAlreadyOwnedByYouException | BucketAlreadyExistsException already) {
                    log.debug("S3 bucket {} already exists", bucket);
                }
            }
        };
    }
}
