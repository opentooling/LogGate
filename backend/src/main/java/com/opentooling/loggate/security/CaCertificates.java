package com.opentooling.loggate.security;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;

/**
 * Loads X.509 certificates from a PEM file into a {@link TrustManager} and
 * allows configuring the JVM's default {@link SSLContext}.
 */
public final class CaCertificates {

  private CaCertificates() {}

  /**
   * Configures the JVM's default {@link SSLContext} with the certificates in
   * {@code pem} alongside the default system truststore.
   */
  public static void configureDefaultSslContext(Path pem) {
    try {
      TrustManager[] trustManagers = trustManagers(pem, true);
      SSLContext sslContext = SSLContext.getInstance("TLS");
      sslContext.init(null, trustManagers, null);
      SSLContext.setDefault(sslContext);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("could not configure default SSLContext with " + pem, e);
    }
  }

  /**
   * Builds trust managers for a PEM certificate file, including system root CAs
   * by default.
   */
  public static TrustManager[] trustManagers(Path pem) {
    return trustManagers(pem, true);
  }

  /**
   * Builds trust managers for a PEM certificate file.
   *
   * @param pem the path to the PEM bundle
   * @param includeSystemTrust whether to also trust the JVM's default cacerts
   */
  public static TrustManager[] trustManagers(Path pem, boolean includeSystemTrust) {
    try {
      KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
      if (includeSystemTrust) {
        String cacertsPath = System.getProperty("javax.net.ssl.trustStore");
        if (cacertsPath == null || cacertsPath.isBlank()) {
          cacertsPath =
              Path.of(System.getProperty("java.home"), "lib", "security", "cacerts").toString();
        }
        Path path = Path.of(cacertsPath);
        if (Files.exists(path)) {
          char[] password =
              System.getProperty("javax.net.ssl.trustStorePassword", "changeit").toCharArray();
          try (InputStream is = Files.newInputStream(path)) {
            store.load(is, password);
          }
        } else {
          store.load(null, null);
        }
      } else {
        store.load(null, null);
      }

      try (InputStream in = Files.newInputStream(pem)) {
        Collection<? extends Certificate> certificates =
            CertificateFactory.getInstance("X.509").generateCertificates(in);
        if (certificates.isEmpty()) {
          throw new IllegalStateException("no certificates in " + pem);
        }
        int i = 0;
        for (Certificate certificate : certificates) {
          store.setCertificateEntry("custom-ca-" + i++, certificate);
        }
      }

      TrustManagerFactory factory =
          TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      factory.init(store);
      return factory.getTrustManagers();
    } catch (IOException e) {
      throw new UncheckedIOException("could not read CA certificate " + pem, e);
    } catch (GeneralSecurityException e) {
      throw new IllegalStateException("could not load CA certificate " + pem, e);
    }
  }
}
