package com.opentooling.loggate.delivery;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What an export contains, recorded alongside it.
 *
 * <p>This is what makes an export evidentiary rather than merely delivered:
 * the exact selector and range it came from, how many entries and bytes each
 * part holds, and a checksum per part so a download can be shown to be intact.
 *
 * @param jobId the export
 * @param selector the LogQL the data came from
 * @param namespaces namespaces covered
 * @param from start of the range, inclusive
 * @param to end of the range, exclusive
 * @param entries total entries across every part
 * @param uncompressedBytes total uncompressed bytes
 * @param format how each part is encoded
 * @param parts the parts, in chronological order
 * @param caveats anything a reader should know before trusting the contents
 * @param createdAt when the manifest was written
 */
public record Manifest(
    UUID jobId,
    String selector,
    List<String> namespaces,
    Instant from,
    Instant to,
    long entries,
    long uncompressedBytes,
    String format,
    List<Part> parts,
    List<String> caveats,
    Instant createdAt) {

  /**
   * One part of an export.
   *
   * @param index window index, which is also the part's chronological position
   * @param key object key
   * @param from window start, inclusive
   * @param to window end, exclusive
   * @param entries entries in this part
   * @param uncompressedBytes uncompressed bytes in this part
   * @param compressedBytes stored size
   * @param sha256 checksum of the stored bytes
   */
  public record Part(
      int index,
      String key,
      Instant from,
      Instant to,
      long entries,
      long uncompressedBytes,
      long compressedBytes,
      String sha256) {}
}
