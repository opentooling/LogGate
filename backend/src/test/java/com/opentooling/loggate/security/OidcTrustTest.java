package com.opentooling.loggate.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.Map;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.boot.ssl.pem.PemContent;
import org.springframework.core.io.support.SpringFactoriesLoader;

class OidcTrustTest {

  private static Path testCa() throws Exception {
    return Path.of(OidcTrustTest.class.getResource("/tls/test-ca.pem").toURI());
  }

  private static ApplicationEnvironmentPreparedEvent prepared(String caCertificate) {
    var environment = new StandardEnvironment();
    environment
        .getPropertySources()
        .addFirst(new MapPropertySource("test", Map.of(OidcTrust.PROPERTY, caCertificate)));
    return new ApplicationEnvironmentPreparedEvent(
        new DefaultBootstrapContext(), new SpringApplication(), new String[0], environment);
  }

  @Test
  void trustsTheProvidersAuthorityAlongsideThePlatformsRoots() throws Exception {
    // Alongside, not instead: every client without trust of its own uses this.
    var platform = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
    platform.init((KeyStore) null);
    X509Certificate[] roots = ((X509TrustManager) platform.getTrustManagers()[0]).getAcceptedIssuers();
    X509Certificate provider = PemContent.load(testCa()).getCertificates().getFirst();

    KeyStore store = OidcTrust.trustStore(testCa());

    assertThat(roots).isNotEmpty();
    assertThat(store.size()).isEqualTo(roots.length + 1);
    assertThat(store.getCertificateAlias(provider)).startsWith("oidc-");
    assertThat(store.getCertificateAlias(roots[0])).startsWith("platform-");
    assertThat(OidcTrust.withPlatformRoots(testCa()).getProtocol()).isEqualTo("TLS");
  }

  @Test
  void isRegisteredToRunBeforeTheContextExists() {
    // A bean would be too late: clients built before it would miss the CA.
    assertThat(SpringFactoriesLoader.forDefaultResourceLocation().load(ApplicationListener.class))
        .anyMatch(OidcTrust.class::isInstance);
  }

  @Test
  void changesNothingWhenNoAuthorityIsConfigured() throws Exception {
    SSLContext before = SSLContext.getDefault();

    new OidcTrust().onApplicationEvent(prepared(""));

    assertThat(SSLContext.getDefault()).isSameAs(before);
  }

  @Test
  void installsTheAuthorityAsTheJvmsDefault() throws Exception {
    SSLContext before = SSLContext.getDefault();
    try {
      new OidcTrust().onApplicationEvent(prepared(testCa().toString()));

      assertThat(SSLContext.getDefault()).isNotSameAs(before);
    } finally {
      SSLContext.setDefault(before);
    }
  }

  @Test
  void refusesAFileWithNoCertificates(@TempDir Path dir) throws IOException {
    Path empty = Files.writeString(dir.resolve("empty.pem"), "");

    assertThatThrownBy(() -> OidcTrust.withPlatformRoots(empty))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("empty.pem");
  }

  @Test
  void saysWhichFileItCouldNotRead(@TempDir Path dir) {
    assertThatThrownBy(() -> OidcTrust.withPlatformRoots(dir.resolve("missing.pem")))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("missing.pem");
  }
}
