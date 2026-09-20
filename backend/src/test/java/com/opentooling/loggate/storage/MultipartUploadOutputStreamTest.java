package com.opentooling.loggate.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.io.IOException;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadResponse;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;
import software.amazon.awssdk.services.s3.model.UploadPartResponse;

/** The upload's own behaviour, including the paths a real store rarely takes. */
class MultipartUploadOutputStreamTest {

  private final S3Client s3 = mock(S3Client.class);

  private MultipartUploadOutputStream stream() {
    return new MultipartUploadOutputStream(s3, "bucket", "key");
  }

  private void expectMultipart() {
    when(s3.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
        .thenReturn(CreateMultipartUploadResponse.builder().uploadId("upload-1").build());
    when(s3.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
        .thenReturn(UploadPartResponse.builder().eTag("etag").build());
  }

  @Test
  void writesSmallContentWithASinglePutRatherThanAMultipartRoundTrip() throws IOException {
    try (var out = stream()) {
      out.write('a');
      out.write(new byte[] {'b', 'c'});
    }

    verify(s3).putObject(any(PutObjectRequest.class), any(RequestBody.class));
    verify(s3, never()).createMultipartUpload(any(CreateMultipartUploadRequest.class));
  }

  @Test
  void switchesToMultipartOnceTheBufferFills() throws IOException {
    expectMultipart();
    var out = stream();

    // Two full parts plus a remainder, so both the in-flight and final flush
    // paths run.
    out.write(new byte[MultipartUploadOutputStream.PART_SIZE * 2 + 16]);
    out.close();

    verify(s3, org.mockito.Mockito.times(3))
        .uploadPart(any(UploadPartRequest.class), any(RequestBody.class));
    verify(s3).completeMultipartUpload(any(CompleteMultipartUploadRequest.class));
    assertThat(out.totalBytes()).isEqualTo(MultipartUploadOutputStream.PART_SIZE * 2L + 16);
  }

  @Test
  void closingTwiceUploadsOnlyOnce() throws IOException {
    var out = stream();
    out.write(new byte[] {'x'});

    out.close();
    out.close();

    verify(s3, org.mockito.Mockito.times(1))
        .putObject(any(PutObjectRequest.class), any(RequestBody.class));
  }

  @Test
  void abandonsTheUploadWhenAPartFails() {
    when(s3.createMultipartUpload(any(CreateMultipartUploadRequest.class)))
        .thenReturn(CreateMultipartUploadResponse.builder().uploadId("upload-1").build());
    when(s3.uploadPart(any(UploadPartRequest.class), any(RequestBody.class)))
        .thenThrow(S3Exception.builder().message("no room").build());
    var out = stream();

    assertThatThrownBy(() -> out.write(new byte[MultipartUploadOutputStream.PART_SIZE]))
        .isInstanceOf(IOException.class)
        .hasMessageContaining("could not upload part");

    // Nothing half-written is left for a retry to trip over.
    verify(s3).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
  }

  @Test
  void abandonsTheUploadWhenCompletionFails() {
    expectMultipart();
    when(s3.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
        .thenThrow(S3Exception.builder().message("gone").build());
    var out = stream();

    assertThatThrownBy(
            () -> {
              out.write(new byte[MultipartUploadOutputStream.PART_SIZE + 1]);
              out.close();
            })
        .isInstanceOf(IOException.class)
        .hasMessageContaining("could not finish upload");

    verify(s3).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
  }

  @Test
  void abortingBeforeAnyPartWasUploadedDoesNothing() {
    var out = stream();

    out.abort();

    verify(s3, never()).abortMultipartUpload(any(AbortMultipartUploadRequest.class));
  }

  @Test
  void survivesAFailureWhileAbandoningTheUpload() {
    // The original failure is what matters; a failing abort must not replace it.
    expectMultipart();
    when(s3.abortMultipartUpload(any(AbortMultipartUploadRequest.class)))
        .thenThrow(S3Exception.builder().message("already gone").build());
    when(s3.completeMultipartUpload(any(CompleteMultipartUploadRequest.class)))
        .thenThrow(S3Exception.builder().message("gone").build());
    var out = stream();

    assertThatThrownBy(
            () -> {
              out.write(new byte[MultipartUploadOutputStream.PART_SIZE + 1]);
              out.close();
            })
        .isInstanceOf(IOException.class)
        .hasMessageContaining("could not finish upload");
  }
}
