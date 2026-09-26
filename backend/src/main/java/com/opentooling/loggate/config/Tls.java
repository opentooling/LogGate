package com.opentooling.loggate.config;

import java.net.http.HttpClient;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.SslBundles;

/**
 * Trust for the clients that can be given their own: Loki, the metrics store
 * and object storage, each from a named SSL bundle ({@code spring.ssl.bundle.*}).
 *
 * <p>A bundle's trust store replaces the JVM's for that client alone, which is
 * what an endpoint behind an internal certificate authority needs, and nothing
 * else in the application is affected. With no bundle named, a client keeps the
 * JVM's default trust.
 */
final class Tls {

  private Tls() {}

  /** The named bundle, or null when none is named. */
  static SslBundle bundle(ObjectProvider<SslBundles> bundles, String name) {
    return name.isBlank() ? null : bundles.getObject().getBundle(name);
  }

  /** A JDK HTTP client builder trusting {@code bundle}, or the JVM's default when it is null. */
  static HttpClient.Builder httpClient(SslBundle bundle) {
    HttpClient.Builder client = HttpClient.newBuilder();
    return bundle == null ? client : client.sslContext(bundle.createSslContext());
  }
}
