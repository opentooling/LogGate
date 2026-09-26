package com.opentooling.loggate.security;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.boot.ssl.pem.PemContent;
import org.springframework.context.ApplicationListener;

/**
 * Trusts the identity provider's certificate authority across the JVM, before
 * any bean exists.
 *
 * <p>Across the JVM, because Spring Security discovers the provider from its
 * issuer URI through a {@code RestTemplate} of its own that nothing can
 * configure; the default {@link SSLContext} is the only trust it consults. The
 * provider's CA is added to the platform's roots rather than replacing them,
 * since every client without trust of its own uses this context too.
 *
 * <p>Before any bean, because an HTTP client captures the default context
 * when it is built. Installed from a bean, whether a client saw the CA would
 * depend on the order beans happened to be created in. Registered in {@code
 * META-INF/spring.factories}, so it runs as the environment is ready and
 * before the application context exists.
 */
public final class OidcTrust implements ApplicationListener<ApplicationEnvironmentPreparedEvent> {

  /** A PEM file of certificates to trust for the provider; empty trusts the platform's alone. */
  static final String PROPERTY = "loggate.oidc.ca-certificate";

  @Override
  public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
    String pem = event.getEnvironment().getProperty(PROPERTY, "");
    if (!pem.isBlank()) {
      SSLContext.setDefault(withPlatformRoots(Path.of(pem)));
    }
  }

  /** A context trusting the platform's roots and every certificate in {@code pem}. */
  static SSLContext withPlatformRoots(Path pem) {
    try {
      TrustManagerFactory trust =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      trust.init(trustStore(pem));
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, trust.getTrustManagers(), null);
      return context;
    } catch (IOException e) {
      throw new UncheckedIOException("could not read the OIDC CA certificate " + pem, e);
    } catch (GeneralSecurityException | IllegalStateException e) {
      throw new IllegalStateException(
          "could not trust the OIDC CA certificate " + pem + ": " + e.getMessage(), e);
    }
  }

  /** The platform's roots, and every certificate in {@code pem}. */
  static KeyStore trustStore(Path pem) throws IOException, GeneralSecurityException {
    // Boot's parser refuses a file holding no certificates.
    List<X509Certificate> authorities = PemContent.load(pem).getCertificates();
    KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
    store.load(null, null);
    int i = 0;
    for (X509Certificate root : platformTrust().getAcceptedIssuers()) {
      store.setCertificateEntry("platform-" + i++, root);
    }
    for (X509Certificate authority : authorities) {
      store.setCertificateEntry("oidc-" + i++, authority);
    }
    return store;
  }

  /** The platform's own trust, as it stands before anything here changes it. */
  private static X509TrustManager platformTrust() throws GeneralSecurityException {
    TrustManagerFactory platform =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    platform.init((KeyStore) null);
    // PKIX, the default algorithm, always offers exactly one, and an X.509 one.
    return (X509TrustManager) platform.getTrustManagers()[0];
  }
}
