package com.opentooling.loggate.loki;

import java.util.List;

/**
 * One page of {@code query_range} results, already merged across streams and
 * sorted ascending by timestamp.
 *
 * @param entries the page's entries, oldest first
 * @param limit the limit that produced this page; a full page means there is
 *     probably more to fetch
 */
public record QueryPage(List<LogEntry> entries, int limit) {

  /** Whether the page came back full, which is how paging decides to continue. */
  public boolean isFull() {
    return entries.size() >= limit;
  }
}
