package com.opentooling.loggate.storage;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.AbortMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompleteMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.CompletedMultipartUpload;
import software.amazon.awssdk.services.s3.model.CompletedPart;
import software.amazon.awssdk.services.s3.model.CreateMultipartUploadRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.UploadPartRequest;

/**
 * An {@link OutputStream} that uploads to S3 as it is written.
 *
 * <p>The content length of an export part is not known until it has been
 * produced, and a multi-gigabyte window must not be buffered to heap or staged
 * on disk to discover it. So bytes accumulate in one fixed buffer and each full
 * buffer becomes a multipart upload part.
 *
 * <p>The buffer is 5 MiB because that is S3's minimum part size for every part
 * but the last - the same minimum that later lets parts be concatenated
 * server-side with UploadPartCopy instead of being downloaded and rewritten.
 *
 * <p>Content small enough to fit in one buffer is written with a plain
 * PutObject instead, so short windows do not pay for a multipart round trip.
 */
final class MultipartUploadOutputStream extends OutputStream {

  static final int PART_SIZE = 5 * 1024 * 1024;

  private final S3Client s3;
  private final String bucket;
  private final String key;
  private final byte[] buffer = new byte[PART_SIZE];

  private int buffered;
  private long totalBytes;
  private String uploadId;
  private final List<CompletedPart> parts = new ArrayList<>();
  private boolean closed;

  MultipartUploadOutputStream(S3Client s3, String bucket, String key) {
    this.s3 = s3;
    this.bucket = bucket;
    this.key = key;
  }

  long totalBytes() {
    return totalBytes;
  }

  @Override
  public void write(int b) throws IOException {
    write(new byte[] {(byte) b}, 0, 1);
  }

  @Override
  public void write(byte[] source, int offset, int length) throws IOException {
    int written = 0;
    while (written < length) {
      int room = PART_SIZE - buffered;
      int chunk = Math.min(room, length - written);
      System.arraycopy(source, offset + written, buffer, buffered, chunk);
      buffered += chunk;
      written += chunk;
      totalBytes += chunk;
      if (buffered == PART_SIZE) {
        flushPart();
      }
    }
  }

  @Override
  public void close() throws IOException {
    if (closed) {
      return;
    }
    closed = true;
    try {
      if (uploadId == null) {
        // Everything fits in one buffer: a single PutObject is cheaper and
        // avoids leaving an incomplete multipart upload behind.
        s3.putObject(
            PutObjectRequest.builder().bucket(bucket).key(key).build(),
            RequestBody.fromBytes(java.util.Arrays.copyOf(buffer, buffered)));
        return;
      }
      if (buffered > 0) {
        flushPart();
      }
      s3.completeMultipartUpload(
          CompleteMultipartUploadRequest.builder()
              .bucket(bucket)
              .key(key)
              .uploadId(uploadId)
              .multipartUpload(CompletedMultipartUpload.builder().parts(parts).build())
              .build());
    } catch (RuntimeException e) {
      abortQuietly();
      throw new IOException("could not finish upload of " + key, e);
    }
  }

  /** Abandons an upload in progress, so a failed window leaves nothing behind. */
  void abort() {
    closed = true;
    abortQuietly();
  }

  private void abortQuietly() {
    if (uploadId == null) {
      return;
    }
    try {
      s3.abortMultipartUpload(
          AbortMultipartUploadRequest.builder()
              .bucket(bucket)
              .key(key)
              .uploadId(uploadId)
              .build());
    } catch (RuntimeException ignored) {
      // The upload is already gone, or the store is unreachable. Either way
      // there is nothing useful left to do, and the original failure matters more.
    } finally {
      uploadId = null;
    }
  }

  private void flushPart() throws IOException {
    try {
      if (uploadId == null) {
        uploadId =
            s3.createMultipartUpload(
                    CreateMultipartUploadRequest.builder().bucket(bucket).key(key).build())
                .uploadId();
      }
      int partNumber = parts.size() + 1;
      String etag =
          s3.uploadPart(
                  UploadPartRequest.builder()
                      .bucket(bucket)
                      .key(key)
                      .uploadId(uploadId)
                      .partNumber(partNumber)
                      .contentLength((long) buffered)
                      .build(),
                  RequestBody.fromBytes(java.util.Arrays.copyOf(buffer, buffered)))
              .eTag();
      parts.add(CompletedPart.builder().partNumber(partNumber).eTag(etag).build());
      buffered = 0;
    } catch (RuntimeException e) {
      abortQuietly();
      throw new IOException("could not upload part of " + key, e);
    }
  }
}
