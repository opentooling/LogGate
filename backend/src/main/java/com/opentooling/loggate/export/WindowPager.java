package com.opentooling.loggate.export;

import com.opentooling.loggate.loki.LogEntry;
import com.opentooling.loggate.loki.LokiClient;
import com.opentooling.loggate.loki.LokiException;
import com.opentooling.loggate.loki.QueryPage;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * <p>So the pager carries forward how many times it has already emitted each
 * entry at the page's final timestamp, and skips exactly that many next time.
 * It advances only by timestamp, never by a count, because a count would
 * silently skip entries the server ordered differently.
 *
 * <p>Counting occurrences rather than remembering a set matters: two entries in
 * one stream can be genuinely identical - same nanosecond, same text - and a
 * set cannot tell the second from a repeat of the first, so it drops it. That
 * showed up as a 0.03% shortfall against Loki's own count on dense data, which
 * is exactly the kind of loss nobody would notice until they needed the line
 * that went missing.
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
    Map<LogEntry.EntryKey, Integer> emittedAtBoundary = new HashMap<>();
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
      // How many occurrences of each entry are still owed a skip on this page.
      Map<LogEntry.EntryKey, Integer> toSkip = new HashMap<>(emittedAtBoundary);
      Map<LogEntry.EntryKey, Integer> emittedAtPageMax = new HashMap<>();

      for (LogEntry entry : entries) {
        if (entry.timestampNanos() == boundaryNanos) {
          Integer remaining = toSkip.get(entry.key());
          if (remaining != null && remaining > 0) {
            // Already emitted on a previous page: the inclusive-start overlap.
            toSkip.put(entry.key(), remaining - 1);
            continue;
          }
        }
        sink.accept(entry);
        emitted++;
        emittedFromPage++;
        if (entry.timestampNanos() == pageMaxNanos) {
          emittedAtPageMax.merge(entry.key(), 1, Integer::sum);
        }
      }

      if (!page.isFull()) {
        // A short page means Loki had nothing more in this window.
        return emitted;
      }

      if (pageMaxNanos == boundaryNanos) {
        // The page did not advance in time: every entry shares the boundary
        // nanosecond. Remember the extra occurrences and try again.
        Map<LogEntry.EntryKey, Integer> carried = emittedAtBoundary;
        emittedAtPageMax.forEach((key, count) -> carried.merge(key, count, Integer::sum));
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
        emittedAtBoundary = emittedAtPageMax;
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


}
