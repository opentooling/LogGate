package com.opentooling.loggate;

import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.ssl.pem.PemSslStoreBundle;
import org.springframework.boot.ssl.pem.PemSslStoreDetails;

/** An SSL bundle trusting the test CA alone, as {@code spring.ssl.bundle.pem} would make it. */
public final class TestTls {

  public static final String CA = "classpath:tls/test-ca.pem";

  private TestTls() {}

  public static SslBundle bundle() {
    return SslBundle.of(new PemSslStoreBundle(null, PemSslStoreDetails.forCertificate(CA)));
  }
}
