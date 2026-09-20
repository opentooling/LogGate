package com.opentooling.loggate.namespaces;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class UnavailableNamespaceCatalogTest {

  private final UnavailableNamespaceCatalog catalog = new UnavailableNamespaceCatalog();

  @Test
  void isNeverReadySoEverythingIsRefused() {
    assertThat(catalog.isReady()).isFalse();
  }

  @Test
  void resolvesNothing() {
    assertThat(catalog.find("platform-dev")).isEmpty();
    assertThat(catalog.all()).isEmpty();
  }
}
