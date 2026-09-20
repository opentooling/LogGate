package com.opentooling.loggate.storage;

import java.io.IOException;
import java.io.UncheckedIOException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;

/** {@link ObjectStore} over anything S3-compatible, which locally is MinIO. */
public class S3ObjectStore implements ObjectStore {

  private final S3Client s3;
  private final String bucket;

  public S3ObjectStore(S3Client s3, String bucket) {
    this.s3 = s3;
    this.bucket = bucket;
  }

  @Override
  public long put(String key, ContentWriter writer) {
    MultipartUploadOutputStream out = new MultipartUploadOutputStream(s3, bucket, key);
    try {
      writer.writeTo(out);
      out.close();
      return out.totalBytes();
    } catch (IOException | RuntimeException e) {
      // Leave nothing half-written: a retried window must start from nothing.
      out.abort();
      throw new UncheckedIOException(
          new IOException("could not write " + key + " to " + bucket, e));
    }
  }

  /**
   * Deletes one object at a time rather than in batches.
   *
   * <p>The batch DeleteObjects call needs a Content-MD5 header that the SDK no
   * longer sends by default, and MinIO rejects it without one. Deleting
   * individually costs one request per part, which for a purge that happens
   * once per cancelled or expired job is a fair price for working the same way
   * against every S3 implementation.
   */
  @Override
  public void deletePrefix(String prefix) {
    String continuation = null;
    do {
      var response =
          s3.listObjectsV2(
              ListObjectsV2Request.builder()
                  .bucket(bucket)
                  .prefix(prefix)
                  .continuationToken(continuation)
                  .build());
      for (var object : response.contents()) {
        s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(object.key()).build());
      }
      continuation =
          Boolean.TRUE.equals(response.isTruncated()) ? response.nextContinuationToken() : null;
    } while (continuation != null);
  }
}
