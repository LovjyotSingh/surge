package dev.surge.notification.config;

import dev.surge.common.security.SurgeEnv;
import java.net.URI;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
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

/**
 * S3 client (Adobe S3Mock locally, real S3 by changing env only) and the ticket bucket.
 *
 * <p>SEC-16 (S10): with SURGE_ENV=prod it refuses to start on the local placeholder keys ({@value #LOCAL_KEY}, which
 * S3Mock accepts) or on no keys; on a public deployment it also refuses a plain-HTTP endpoint (S3Mock, or any store
 * the keys and the ticket PDFs would cross unencrypted). Blank endpoint = AWS over HTTPS.
 */
@Configuration(proxyBeanMethods = false)
public class S3Config {

    private static final Logger log = LoggerFactory.getLogger(S3Config.class);
    /** application.yml's default for both keys: S3Mock accepts anything, real S3 nothing. */
    static final String LOCAL_KEY = "surge-local";

    @Bean
    S3Client s3Client(@Value("${surge.s3.endpoint:}") String endpoint,
                      @Value("${surge.s3.region:ap-south-1}") String region,
                      @Value("${surge.s3.access-key:surge-local}") String accessKey,
                      @Value("${surge.s3.secret-key:surge-local}") String secretKey,
                      Environment environment) {
        checkSettings(environment, endpoint, accessKey, secretKey);
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

    /** See the class comment. Messages name variables, never values. */
    static void checkSettings(Environment environment, String endpoint, String accessKey, String secretKey) {
        if (SurgeEnv.from(environment) != SurgeEnv.PROD) {
            return;
        }
        if (accessKey == null || accessKey.isBlank() || secretKey == null || secretKey.isBlank()
                || LOCAL_KEY.equals(accessKey.strip()) || LOCAL_KEY.equals(secretKey.strip())) {
            throw new IllegalStateException("S3_ACCESS_KEY and S3_SECRET_KEY must be set to real keys (not the local "
                    + "placeholder) when SURGE_ENV=prod");
        }
        boolean publicDeployment = environment.getProperty(SurgeEnv.PUBLIC_DEPLOYMENT, Boolean.class, false);
        if (publicDeployment && endpoint != null && !endpoint.isBlank()
                && !endpoint.strip().toLowerCase(java.util.Locale.ROOT).startsWith("https://")) {
            throw new IllegalStateException("S3_ENDPOINT must be blank (AWS) or an https:// URL when "
                    + "SURGE_PUBLIC_DEPLOYMENT=true");
        }
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
