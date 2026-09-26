package com.opentooling.loggate.storage;

import com.opentooling.loggate.config.LogGateProperties;
import java.net.URI;
import org.springframework.boot.ssl.SslBundle;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.http.apache5.Apache5HttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ClientBuilder;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * Builds the S3 client and presigner from configuration, in one place, so that
 * what is tested is what runs.
 *
 * <p>Written for S3-compatible stores rather than for AWS alone: MinIO, NetApp
 * ONTAP S3 and StorageGRID all accept the operations LogGate uses, but they
 * differ from AWS at the edges, and the SDK's defaults follow AWS.
 */
public final class S3Clients {

  private S3Clients() {}

  /** The client that reads, writes and deletes export artifacts, with the JVM's trust. */
  public static S3Client client(LogGateProperties.Storage storage) {
    return client(storage, null);
  }

  /**
   * The client that reads, writes and deletes export artifacts.
   *
   * @param trust an SSL bundle whose trust store replaces the JVM's for storage
   *     calls, for a store behind an internal certificate authority, which is
   *     how an on-premises ONTAP or StorageGRID endpoint is usually presented;
   *     null keeps the JVM's
   */
  public static S3Client client(LogGateProperties.Storage storage, SslBundle trust) {
    return builder(storage, trust).build();
  }

  /**
   * The builder behind {@link #client}, for tests that need to observe the
   * requests it makes without changing how it makes them.
   */
  public static S3ClientBuilder builder(LogGateProperties.Storage storage) {
    return builder(storage, null);
  }

  static S3ClientBuilder builder(LogGateProperties.Storage storage, SslBundle trust) {
    Apache5HttpClient.Builder http = Apache5HttpClient.builder();
    if (trust != null) {
      http.tlsTrustManagersProvider(() -> trust.getManagers().getTrustManagers());
    }
    return S3Client.builder()
        .endpointOverride(URI.create(storage.endpoint()))
        .region(Region.of(storage.region()))
        // Static keys when configured, otherwise the default chain, so a
        // production deployment can use an IAM role instead of a secret.
        .credentialsProvider(credentials(storage))
        // MinIO and ONTAP S3 address buckets by path unless DNS is set up for
        // virtual-hosted buckets.
        .serviceConfiguration(
            S3Configuration.builder().pathStyleAccessEnabled(storage.pathStyle()).build())
        // Since SDK 2.30 every upload carries a CRC32 as a trailing checksum,
        // sent with aws-chunked encoding. AWS accepts it; stores that implement
        // an older S3 do not always, and ONTAP S3 documents only the signed
        // streaming payload. WHEN_REQUIRED keeps uploads plain unless the
        // operation itself demands a checksum.
        .requestChecksumCalculation(requestChecksums(storage.checksums()))
        .responseChecksumValidation(responseChecksums(storage.checksums()))
        .httpClientBuilder(http);
  }

  /** Signs download links, against the endpoint a browser can reach. */
  public static S3Presigner presigner(LogGateProperties.Storage storage) {
    String endpoint =
        storage.publicEndpoint().isBlank() ? storage.endpoint() : storage.publicEndpoint();
    return S3Presigner.builder()
        .endpointOverride(URI.create(endpoint))
        .region(Region.of(storage.region()))
        .credentialsProvider(credentials(storage))
        .serviceConfiguration(
            S3Configuration.builder().pathStyleAccessEnabled(storage.pathStyle()).build())
        .build();
  }

  static RequestChecksumCalculation requestChecksums(LogGateProperties.Storage.Checksums mode) {
    return mode == LogGateProperties.Storage.Checksums.WHEN_SUPPORTED
        ? RequestChecksumCalculation.WHEN_SUPPORTED
        : RequestChecksumCalculation.WHEN_REQUIRED;
  }

  static ResponseChecksumValidation responseChecksums(LogGateProperties.Storage.Checksums mode) {
    return mode == LogGateProperties.Storage.Checksums.WHEN_SUPPORTED
        ? ResponseChecksumValidation.WHEN_SUPPORTED
        : ResponseChecksumValidation.WHEN_REQUIRED;
  }

  private static AwsCredentialsProvider credentials(LogGateProperties.Storage storage) {
    return storage.accessKey().isBlank()
        ? DefaultCredentialsProvider.builder().build()
        : StaticCredentialsProvider.create(
            AwsBasicCredentials.create(storage.accessKey(), storage.secretKey()));
  }
}
