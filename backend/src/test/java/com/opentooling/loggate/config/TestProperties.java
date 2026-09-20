package com.opentooling.loggate.config;

import java.time.Duration;

/**
 * Builds {@link LogGateProperties} for tests.
 *
 * <p>Tests care about one section at a time, and without this every new
 * configuration section would mean editing every test that ever constructed
 * properties.
 */
public final class TestProperties {

  private TestProperties() {}

  /** Defaults for every section. */
  public static LogGateProperties defaults() {
    return of(namespaces(), loki(), windows());
  }

  /** The three sections tests usually vary, with defaults for the rest. */
  public static LogGateProperties of(
      LogGateProperties.Namespaces namespaces,
      LogGateProperties.Loki loki,
      LogGateProperties.Windows windows) {
    return new LogGateProperties(namespaces, loki, windows, quotas(), execution(), storage());
  }

  /** Defaults with a different namespace section. */
  public static LogGateProperties withNamespaces(LogGateProperties.Namespaces namespaces) {
    return of(namespaces, loki(), windows());
  }

  /** Defaults with a different quota section. */
  public static LogGateProperties withQuotas(LogGateProperties.Quotas quotas) {
    return new LogGateProperties(namespaces(), loki(), windows(), quotas, execution(), storage());
  }

  /** Defaults with a different storage section. */
  public static LogGateProperties withStorage(LogGateProperties.Storage storage) {
    return new LogGateProperties(namespaces(), loki(), windows(), quotas(), execution(), storage);
  }

  public static LogGateProperties.Namespaces namespaces() {
    return new LogGateProperties.Namespaces(true, "xyz.com/team", "ad-{team}-{env}", "dev");
  }

  public static LogGateProperties.Loki loki() {
    return new LogGateProperties.Loki("http://loki.test", "", 5000, Duration.ofSeconds(30));
  }

  public static LogGateProperties.Windows windows() {
    return new LogGateProperties.Windows(
        256L * 1024 * 1024, Duration.ofMinutes(1), Duration.ofHours(1), 5000);
  }

  public static LogGateProperties.Quotas quotas() {
    return new LogGateProperties.Quotas(
        Duration.ofDays(2),
        50L * 1024 * 1024 * 1024,
        2,
        10,
        1.25,
        64L * 1024 * 1024,
        500L * 1024 * 1024 * 1024,
        Duration.ofHours(24));
  }

  public static LogGateProperties.Execution execution() {
    return new LogGateProperties.Execution(
        false, 2, Duration.ofMinutes(2), 3, Duration.ofSeconds(2), Duration.ofHours(48));
  }

  public static LogGateProperties.Storage storage() {
    return new LogGateProperties.Storage(
        "http://minio.test:9000",
        "us-east-1",
        "loggate-exports",
        "key",
        "secret",
        true,
        "",
        Duration.ofMinutes(30));
  }
}
