package com.opentooling.loggate.storage;

import com.opentooling.loggate.config.LogGateProperties;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
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

  /** The client that reads, writes and deletes export artifacts. */
  public static S3Client client(LogGateProperties.Storage storage) {
    return builder(storage).build();
  }

  /**
   * The builder behind {@link #client}, for tests that need to observe the
   * requests it makes without changing how it makes them.
   */
  public static S3ClientBuilder builder(LogGateProperties.Storage storage) {
    Apache5HttpClient.Builder http = Apache5HttpClient.builder();
    if (!storage.caCertificate().isBlank()) {
      TrustManager[] trust = trustManagers(Path.of(storage.caCertificate()));
      http.tlsTrustManagersProvider(() -> trust);
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

  /**
   * Trust for a store behind an internal certificate authority, which is how
   * an on-premises ONTAP or StorageGRID endpoint is usually presented. The
   * certificates in the file are trusted for storage calls only, in place of
   * the JVM's defaults, and nothing else in the application is affected.
   */
  static TrustManager[] trustManagers(Path pem) {
    try (InputStream in = Files.newInputStream(pem)) {
      Collection<? extends Certificate> certificates =
          CertificateFactory.getInstance("X.509").generateCertificates(in);
      if (certificates.isEmpty()) {
        throw new IllegalStateException("no certificates in " + pem);
      }
      KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
      store.load(null, null);
      int i = 0;
      for (Certificate certificate : certificates) {
        store.setCertificateEntry("storage-ca-" + i++, certificate);
      }
      TrustManagerFactory factory =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      factory.init(store);
      return factory.getTrustManagers();
    } catch (IOException e) {
      throw new UncheckedIOException("could not read the storage CA certificate " + pem, e);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("could not load the storage CA certificate " + pem, e);
    }
  }
}
