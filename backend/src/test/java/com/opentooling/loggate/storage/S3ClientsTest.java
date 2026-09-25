package com.opentooling.loggate.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.opentooling.loggate.config.LogGateProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import software.amazon.awssdk.core.interceptor.Context;
import software.amazon.awssdk.core.interceptor.ExecutionAttributes;
import software.amazon.awssdk.core.interceptor.ExecutionInterceptor;
import software.amazon.awssdk.core.interceptor.SdkExecutionAttribute;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * What the storage client actually puts on the wire.
 *
 * <p>S3-compatible stores are compatible with the S3 of some year. Since SDK
 * 2.30 the client, by default, sends every upload with a trailing CRC32 in
 * aws-chunked encoding, which AWS accepts and which NetApp ONTAP S3 does not
 * list among the payload signing modes it supports. These tests record the
 * headers of real uploads, made through the same factory production uses,
 * and check that none of that encoding is sent unless asked for.
 */
class S3ClientsTest {

  private static final String BUCKET = "loggate-exports";
  private static S3TestServer server;

  @BeforeAll
  static void startStorage() {
    server = new S3TestServer();
    server.start();
  }

  @AfterAll
  static void stopStorage() {
    server.stop();
  }

  private static LogGateProperties.Storage storage(
      LogGateProperties.Storage.Checksums checksums, String caCertificate) {
    return new LogGateProperties.Storage(
        server.endpoint(),
        "us-east-1",
        BUCKET,
        S3TestServer.ACCESS_KEY,
        S3TestServer.SECRET_KEY,
        true,
        "",
        Duration.ofMinutes(30),
        checksums,
        caCertificate);
  }

  /** Records the headers of every request, by operation. */
  private static final class Recorder implements ExecutionInterceptor {
    final List<Map.Entry<String, Map<String, List<String>>>> requests =
        Collections.synchronizedList(new ArrayList<>());

    @Override
    public void beforeTransmission(Context.BeforeTransmission context, ExecutionAttributes attributes) {
      requests.add(
          Map.entry(
              attributes.getAttribute(SdkExecutionAttribute.OPERATION_NAME),
              context.httpRequest().headers()));
    }

    List<Map<String, List<String>>> uploads() {
      return requests.stream()
          .filter(r -> r.getKey().equals("PutObject") || r.getKey().equals("UploadPart"))
          .map(Map.Entry::getValue)
          .toList();
    }
  }

  /** Uploads through the store the way an export does, and directly, recording both. */
  private static Recorder upload(LogGateProperties.Storage.Checksums checksums) throws IOException {
    Recorder recorder = new Recorder();
    try (S3Client client =
        S3Clients.builder(storage(checksums, ""))
            .overrideConfiguration(o -> o.addExecutionInterceptor(recorder))
            .build()) {
      try {
        client.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
      } catch (software.amazon.awssdk.services.s3.model.BucketAlreadyOwnedByYouException ignored) {
        // Created by the other test.
      }
      S3ObjectStore store = new S3ObjectStore(client, S3Clients.presigner(storage(checksums, "")), BUCKET);
      // More than one 5 MiB part, so this is a real multipart upload.
      byte[] chunk = "x".repeat(1024 * 1024).getBytes(StandardCharsets.UTF_8);
      store.put(
          "jobs/checksums/" + checksums + "/part",
          out -> {
            for (int i = 0; i < 11; i++) {
              out.write(chunk);
            }
          });
      client.putObject(
          PutObjectRequest.builder().bucket(BUCKET).key("jobs/checksums/" + checksums + "/manifest").build(),
          RequestBody.fromString("{}"));
    }
    return recorder;
  }

  /**
   * The payload modes ONTAP S3 documents: a plain SigV4 hash of the body,
   * UNSIGNED-PAYLOAD as presigned requests use, and the signed chunked upload
   * it lists from 9.11.1. The trailer variants are the ones it does not.
   */
  private static final java.util.regex.Pattern DOCUMENTED_PAYLOAD =
      java.util.regex.Pattern.compile(
          "[0-9a-f]{64}|UNSIGNED-PAYLOAD|STREAMING-AWS4-HMAC-SHA256-PAYLOAD");

  private static String header(Map<String, List<String>> headers, String name) {
    return headers.entrySet().stream()
        .filter(e -> e.getKey().equalsIgnoreCase(name))
        .map(e -> String.join(",", e.getValue()))
        .findFirst()
        .orElse(null);
  }

  private static boolean usesTrailingChecksums(Map<String, List<String>> headers) {
    return header(headers, "x-amz-trailer") != null
        || header(headers, "x-amz-sdk-checksum-algorithm") != null
        || headers.keySet().stream().anyMatch(h -> h.toLowerCase().startsWith("x-amz-checksum-"))
        || !DOCUMENTED_PAYLOAD.matcher(String.valueOf(header(headers, "x-amz-content-sha256"))).matches();
  }

  @Test
  void sendsPlainUploadsByDefault() throws IOException {
    Recorder recorder = upload(LogGateProperties.Storage.Checksums.WHEN_REQUIRED);

    assertThat(recorder.uploads()).hasSizeGreaterThanOrEqualTo(3);
    assertThat(recorder.uploads()).noneMatch(S3ClientsTest::usesTrailingChecksums);
  }

  @Test
  void sendsTrailingChecksumsOnlyWhenAskedTo() throws IOException {
    // The control: without it, the test above could pass because nothing
    // this detects is ever sent, rather than because it was switched off.
    Recorder recorder = upload(LogGateProperties.Storage.Checksums.WHEN_SUPPORTED);

    assertThat(recorder.uploads()).anyMatch(S3ClientsTest::usesTrailingChecksums);
  }

  @Test
  void mapsEachSettingToTheSdksOwn() {
    assertThat(S3Clients.requestChecksums(LogGateProperties.Storage.Checksums.WHEN_REQUIRED))
        .isEqualTo(RequestChecksumCalculation.WHEN_REQUIRED);
    assertThat(S3Clients.requestChecksums(LogGateProperties.Storage.Checksums.WHEN_SUPPORTED))
        .isEqualTo(RequestChecksumCalculation.WHEN_SUPPORTED);
    assertThat(S3Clients.responseChecksums(LogGateProperties.Storage.Checksums.WHEN_REQUIRED))
        .isEqualTo(ResponseChecksumValidation.WHEN_REQUIRED);
    assertThat(S3Clients.responseChecksums(LogGateProperties.Storage.Checksums.WHEN_SUPPORTED))
        .isEqualTo(ResponseChecksumValidation.WHEN_SUPPORTED);
  }

  @Test
  void trustsAnInternalAuthorityForStorageCalls() throws Exception {
    Path pem = Path.of(getClass().getResource("/tls/test-ca.pem").toURI());

    X509TrustManager trust = (X509TrustManager) S3Clients.trustManagers(pem)[0];

    assertThat(trust.getAcceptedIssuers())
        .extracting(c -> c.getSubjectX500Principal().getName())
        .containsExactly("CN=LogGate test storage CA");
    // And a client can be built with it.
    try (S3Client client =
        S3Clients.client(storage(LogGateProperties.Storage.Checksums.WHEN_REQUIRED, pem.toString()))) {
      assertThat(client).isNotNull();
    }
  }

  @Test
  void refusesACertificateFileWithNoCertificates(@TempDir Path dir) throws IOException {
    Path empty = Files.writeString(dir.resolve("empty.pem"), "");

    assertThatThrownBy(() -> S3Clients.trustManagers(empty)).isInstanceOf(IllegalStateException.class);
  }

  @Test
  void saysWhichCertificateFileItCouldNotRead(@TempDir Path dir) {
    Path missing = dir.resolve("missing.pem");

    assertThatThrownBy(() -> S3Clients.trustManagers(missing))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("missing.pem");
  }

  @Test
  void signsDownloadLinksForTheEndpointABrowserCanReach() {
    LogGateProperties.Storage storage =
        new LogGateProperties.Storage(
            "http://minio.internal:9000", "us-east-1", BUCKET, "k", "s", true,
            "https://s3.example.com", Duration.ofMinutes(5),
            LogGateProperties.Storage.Checksums.WHEN_REQUIRED, "");
    try (var presigner = S3Clients.presigner(storage)) {
      String url =
          presigner
              .presignGetObject(
                  r -> r.signatureDuration(Duration.ofMinutes(5))
                      .getObjectRequest(g -> g.bucket(BUCKET).key("k")))
              .url()
              .toString();
      assertThat(url).startsWith("https://s3.example.com/" + BUCKET + "/k?");
    }
  }

  @Test
  void usesTheDefaultCredentialChainWhenNoKeysAreConfigured() {
    LogGateProperties.Storage storage =
        new LogGateProperties.Storage(
            "http://minio.internal:9000", "us-east-1", BUCKET, "", "", true, "",
            Duration.ofMinutes(5), LogGateProperties.Storage.Checksums.WHEN_REQUIRED, "");
    try (S3Client client = S3Clients.client(storage)) {
      assertThat(client).isNotNull();
    }
  }
}
