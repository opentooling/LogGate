package com.opentooling.loggate.export;

import com.opentooling.loggate.loki.LogEntry;
import com.opentooling.loggate.loki.LokiClient;
import com.opentooling.loggate.loki.LokiException;
import com.opentooling.loggate.loki.QueryPage;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Pages one window out of Loki, oldest first, emitting each entry exactly once.
 *
 * <p>This is the highest-risk code in the system, and the reason is one detail
 * of Loki's API: {@code start} is <em>inclusive</em>, and many entries can share
 * a single nanosecond. Paging forward by the last timestamp therefore returns
 * the entries at that timestamp again on the next page. At a few hundred lines
 * per second per pod that is not a rare edge case - it is most pages.
 *
 * <p>So the pager carries forward the identity of every entry seen at the
 * page's final timestamp and skips exactly those next time. It advances only by
 * timestamp, never by a count, because a count would silently skip entries the
 * server ordered differently.
 *
 * <p>Nothing accumulates: entries are handed to the sink as they arrive, and
 * the only state kept between pages is the identity set for one nanosecond.
 *
 * <p><strong>The page limit must exceed the number of entries that can share a
 * single nanosecond.</strong> Loki's API has no offset, so a query starting at
 * that nanosecond returns the same first {@code limit} entries every time: if
 * they all fit in one page there is no way to reach the rest. The pager detects
 * that it has stopped advancing and fails, rather than silently truncating the
 * export. At the default limit of 5000 this needs 5000 lines in the same
 * nanosecond from one selector, which is implausible - but it is a real
 * constraint, not a theoretical one, so it fails loudly if it ever happens.
 */
public class WindowPager {

  private final LokiClient client;
  private final int pageLimit;

  public WindowPager(LokiClient client, int pageLimit) {
    if (pageLimit <= 0) {
      throw new IllegalArgumentException("page limit must be positive");
    }
    this.client = client;
    this.pageLimit = pageLimit;
  }

  /**
   * Emits every entry in {@code window} to {@code sink}, in timestamp order.
   *
   * @return how many entries were emitted
   */
  public long forEachEntry(String selector, ExportWindow window, Consumer<LogEntry> sink) {
    long start = window.fromNanos();
    long boundaryNanos = Long.MIN_VALUE;
    Set<LogEntry.EntryKey> seenAtBoundary = new HashSet<>();
    long emitted = 0;

    while (true) {
      QueryPage page =
          client.queryRange(selector, Timestamps.fromNanos(start), window.to(), pageLimit);
      List<LogEntry> entries = page.entries();
      if (entries.isEmpty()) {
        return emitted;
      }

      long pageMaxNanos = maxTimestamp(entries);
      long emittedFromPage = 0;
      for (LogEntry entry : entries) {
        if (entry.timestampNanos() == boundaryNanos && seenAtBoundary.contains(entry.key())) {
          // Already emitted on a previous page: this is the inclusive-start overlap.
          continue;
        }
        sink.accept(entry);
        emitted++;
        emittedFromPage++;
      }

      if (!page.isFull()) {
        // A short page means Loki had nothing more in this window.
        return emitted;
      }

      Set<LogEntry.EntryKey> atPageMax = keysAt(entries, pageMaxNanos);
      if (pageMaxNanos == boundaryNanos) {
        // The page did not advance in time: every entry shares the boundary
        // nanosecond. Remember the wider set and try again.
        seenAtBoundary.addAll(atPageMax);
        if (emittedFromPage == 0) {
          // A full page containing nothing new means more entries share this
          // nanosecond than a page can hold, so paging cannot advance. Failing
          // loudly beats silently truncating the export.
          throw new LokiException(
              ("more than %d entries share timestamp %d, so paging cannot advance; "
                      + "raise the query page limit for this export")
                  .formatted(pageLimit, pageMaxNanos));
        }
      } else {
        boundaryNanos = pageMaxNanos;
        seenAtBoundary = atPageMax;
      }
      start = boundaryNanos;
    }
  }

  private static long maxTimestamp(List<LogEntry> entries) {
    long max = Long.MIN_VALUE;
    for (LogEntry entry : entries) {
      max = Math.max(max, entry.timestampNanos());
    }
    return max;
  }

  private static Set<LogEntry.EntryKey> keysAt(List<LogEntry> entries, long nanos) {
    Set<LogEntry.EntryKey> keys = new HashSet<>();
    for (LogEntry entry : entries) {
      if (entry.timestampNanos() == nanos) {
        keys.add(entry.key());
      }
    }
    return keys;
  }
}
