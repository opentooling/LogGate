package com.opentooling.loggate.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CaCertificatesTest {

  @Test
  void loadsCertificatesFromPemFile() throws Exception {
    Path pem = Path.of(getClass().getResource("/tls/test-ca.pem").toURI());

    TrustManager[] managers = CaCertificates.trustManagers(pem);
    assertThat(managers).isNotEmpty();

    X509TrustManager trust = (X509TrustManager) managers[0];
    assertThat(Arrays.stream(trust.getAcceptedIssuers())
            .map(c -> c.getSubjectX500Principal().getName()))
        .contains("CN=LogGate test storage CA");
  }

  @Test
  void loadsCertificatesExclusivelyWhenRequested() throws Exception {
    Path pem = Path.of(getClass().getResource("/tls/test-ca.pem").toURI());

    TrustManager[] managers = CaCertificates.trustManagers(pem, false);
    assertThat(managers).isNotEmpty();

    X509TrustManager trust = (X509TrustManager) managers[0];
    assertThat(trust.getAcceptedIssuers())
        .extracting(c -> c.getSubjectX500Principal().getName())
        .containsExactly("CN=LogGate test storage CA");
  }

  @Test
  void refusesACertificateFileWithNoCertificates(@TempDir Path dir) throws IOException {
    Path empty = Files.writeString(dir.resolve("empty.pem"), "");

    assertThatThrownBy(() -> CaCertificates.trustManagers(empty))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("no certificates in");
  }

  @Test
  void saysWhichCertificateFileItCouldNotRead(@TempDir Path dir) {
    Path missing = dir.resolve("missing.pem");

    assertThatThrownBy(() -> CaCertificates.trustManagers(missing))
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("missing.pem");
  }

  @Test
  void configuresDefaultSslContext() throws Exception {
    Path pem = Path.of(getClass().getResource("/tls/test-ca.pem").toURI());
    CaCertificates.configureDefaultSslContext(pem);

    SSLContext defaultContext = SSLContext.getDefault();
    assertThat(defaultContext).isNotNull();
  }
}
