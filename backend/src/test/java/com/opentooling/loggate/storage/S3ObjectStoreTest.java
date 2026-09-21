package com.opentooling.loggate.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MinIOContainer;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

/**
 * The storage path against a real S3 implementation.
 *
 * <p>Worth a container rather than a mock: the part-size rules, multipart
 * completion and prefix deletion are exactly the behaviour a mock would get
 * wrong in the same way the code might.
 */
class S3ObjectStoreTest {

  private static final String BUCKET = "loggate-exports";

  private static MinIOContainer minio;
  private static S3Client s3;
  private static software.amazon.awssdk.services.s3.presigner.S3Presigner presigner;
  private static S3ObjectStore store;

  @BeforeAll
  static void startStorage() {
    // Pinned, and from quay: MinIO's Docker Hub "latest" is no longer public,
    // and this is the same image the local chart deploys.
    minio =
        new MinIOContainer(
            org.testcontainers.utility.DockerImageName.parse(
                    "quay.io/minio/minio:RELEASE.2024-12-18T13-15-44Z")
                .asCompatibleSubstituteFor("minio/minio"));
    // The container runtime intermittently drops its API connection when the
    // machine is busy, which shows up as a container that never starts. Retry
    // rather than leave a test that fails every other run.
    minio.withStartupAttempts(3);
    minio.start();
    s3 =
        S3Client.builder()
            .endpointOverride(URI.create(minio.getS3URL()))
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(minio.getUserName(), minio.getPassword())))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build();
    s3.createBucket(CreateBucketRequest.builder().bucket(BUCKET).build());
    presigner =
        software.amazon.awssdk.services.s3.presigner.S3Presigner.builder()
            .endpointOverride(URI.create(minio.getS3URL()))
            .region(Region.US_EAST_1)
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(minio.getUserName(), minio.getPassword())))
            .serviceConfiguration(S3Configuration.builder().pathStyleAccessEnabled(true).build())
            .build();
    store = new S3ObjectStore(s3, presigner, BUCKET);
  }

  @AfterAll
  static void stopStorage() {
    if (presigner != null) {
      presigner.close();
    }
    if (s3 != null) {
      s3.close();
    }
    if (minio != null) {
      minio.stop();
    }
  }

  private String read(String key) {
    return s3.getObjectAsBytes(GetObjectRequest.builder().bucket(BUCKET).key(key).build())
        .asString(StandardCharsets.UTF_8);
  }

  @Test
  void writesSmallContentInASingleRequest() {
    String key = "test/" + UUID.randomUUID() + "/small.txt";

    long written = store.put(key, out -> out.write("hello".getBytes(StandardCharsets.UTF_8)));

    assertThat(written).isEqualTo(5);
    assertThat(read(key)).isEqualTo("hello");
  }

  @Test
  void writesNothingAsAnEmptyObject() {
    String key = "test/" + UUID.randomUUID() + "/empty.txt";

    assertThat(store.put(key, out -> {})).isZero();
    assertThat(read(key)).isEmpty();
  }

  @Test
  void streamsContentLargerThanOnePartWithoutBufferingItAll() {
    // Twelve megabytes crosses the 5 MiB part boundary twice, so this exercises
    // the multipart path the real windows take.
    String key = "test/" + UUID.randomUUID() + "/large.bin";
    byte[] chunk = new byte[1024 * 1024];
    java.util.Arrays.fill(chunk, (byte) 'x');

    long written =
        store.put(
            key,
            out -> {
              for (int i = 0; i < 12; i++) {
                out.write(chunk);
              }
            });

    assertThat(written).isEqualTo(12L * 1024 * 1024);
    assertThat(
            s3.headObject(b -> b.bucket(BUCKET).key(key)).contentLength())
        .isEqualTo(12L * 1024 * 1024);
  }

  @Test
  void replacesAnObjectRatherThanAppendingToIt() {
    // The idempotency the retry story depends on.
    String key = "test/" + UUID.randomUUID() + "/part.txt";

    store.put(key, out -> out.write("first".getBytes(StandardCharsets.UTF_8)));
    store.put(key, out -> out.write("second".getBytes(StandardCharsets.UTF_8)));

    assertThat(read(key)).isEqualTo("second");
  }

  @Test
  void leavesNothingBehindWhenWritingFails() {
    String key = "test/" + UUID.randomUUID() + "/doomed.bin";
    byte[] chunk = new byte[1024 * 1024];

    assertThatThrownBy(
            () ->
                store.put(
                    key,
                    out -> {
                      for (int i = 0; i < 6; i++) {
                        out.write(chunk);
                      }
                      throw new IOException("ran out of logs");
                    }))
        .isInstanceOf(UncheckedIOException.class);

    assertThatThrownBy(() -> read(key)).isInstanceOf(NoSuchKeyException.class);
  }

  @Test
  void mintsAUrlThatActuallyDownloadsTheObject() throws Exception {
    // The whole point of presigning is that the bytes never pass through the
    // control plane, so the URL has to work on its own.
    String key = "test/" + UUID.randomUUID() + "/presigned.txt";
    store.put(key, out -> out.write("downloadable".getBytes(StandardCharsets.UTF_8)));

    String url = store.presignedUrl(key, java.time.Duration.ofMinutes(5));
    var response =
        java.net.http.HttpClient.newHttpClient()
            .send(
                java.net.http.HttpRequest.newBuilder(java.net.URI.create(url)).build(),
                java.net.http.HttpResponse.BodyHandlers.ofString());

    assertThat(response.statusCode()).isEqualTo(200);
    assertThat(response.body()).isEqualTo("downloadable");
  }

  @Test
  void readsAnObjectBackAndReportsItsSize() throws IOException {
    String key = "test/" + UUID.randomUUID() + "/readable.txt";
    store.put(key, out -> out.write("read me".getBytes(StandardCharsets.UTF_8)));

    try (var in = store.open(key)) {
      assertThat(new String(in.readAllBytes(), StandardCharsets.UTF_8)).isEqualTo("read me");
    }
    assertThat(store.size(key)).isEqualTo(7);
  }

  @Test
  void letsTheWritersOwnFailureThroughUnchanged() {
    // The store must not disguise why the writer stopped. Wrapping it made a
    // quota failure look like a storage failure, which sends the user to the
    // platform team instead of telling them to narrow their export.
    String key = "test/" + UUID.randomUUID() + "/writer-stopped.bin";
    byte[] chunk = new byte[1024 * 1024];

    assertThatThrownBy(
            () ->
                store.put(
                    key,
                    out -> {
                      for (int i = 0; i < 6; i++) {
                        out.write(chunk);
                      }
                      throw new IllegalStateException("the writer decided to stop");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("the writer decided to stop");

    // And it still leaves nothing half-written behind.
    assertThatThrownBy(() -> read(key)).isInstanceOf(NoSuchKeyException.class);
  }

  @Test
  void deletesEverythingUnderAPrefix() {
    String prefix = "jobs/" + UUID.randomUUID() + "/";
    for (int i = 0; i < 3; i++) {
      s3.putObject(
          PutObjectRequest.builder().bucket(BUCKET).key(prefix + "parts/" + i).build(),
          RequestBody.fromString("part " + i));
    }

    store.deletePrefix(prefix);

    assertThat(
            s3.listObjectsV2(ListObjectsV2Request.builder().bucket(BUCKET).prefix(prefix).build())
                .contents())
        .isEmpty();
  }

  @Test
  void deletingAPrefixWithNothingUnderItIsHarmless() {
    store.deletePrefix("jobs/" + UUID.randomUUID() + "/");
  }
}
