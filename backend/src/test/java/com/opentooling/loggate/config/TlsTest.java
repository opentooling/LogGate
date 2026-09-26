package com.opentooling.loggate.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.opentooling.loggate.TestTls;
import java.util.Map;
import javax.net.ssl.SSLContext;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.ssl.DefaultSslBundleRegistry;
import org.springframework.boot.ssl.SslBundles;

class TlsTest {

  private static org.springframework.beans.factory.ObjectProvider<SslBundles> registry() {
    return new StaticListableBeanFactory(
            Map.of("sslBundles", new DefaultSslBundleRegistry("loki", TestTls.bundle())))
        .getBeanProvider(SslBundles.class);
  }

  @Test
  void findsTheNamedBundle() {
    assertThat(Tls.bundle(registry(), "loki")).isNotNull();
  }

  @Test
  void looksNothingUpWhenNoBundleIsNamed() {
    // Not even whether there are bundles at all: a web test slice has none.
    var none = new StaticListableBeanFactory().getBeanProvider(SslBundles.class);
    assertThat(Tls.bundle(none, "")).isNull();
  }

  @Test
  void aClientWithABundleTrustsItRatherThanTheJvm() throws Exception {
    assertThat(Tls.httpClient(TestTls.bundle()).build().sslContext())
        .isNotSameAs(SSLContext.getDefault());
    assertThat(Tls.httpClient(null).build().sslContext()).isSameAs(SSLContext.getDefault());
  }
}
