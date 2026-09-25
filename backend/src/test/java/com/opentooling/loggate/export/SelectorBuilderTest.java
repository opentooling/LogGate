package com.opentooling.loggate.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** The selector is generated, never supplied, so this is the injection boundary. */
class SelectorBuilderTest {

  private static ExportRequest request(
      List<String> namespaces, String pod, String container, String lineFilter) {
    return new ExportRequest(
        namespaces, pod, container, lineFilter, Instant.EPOCH, Instant.EPOCH.plusSeconds(60));
  }

  @Test
  void buildsAnExactMatcherForASingleNamespace() {
    assertThat(SelectorBuilder.build(request(List.of("platform-dev"), null, null, null)))
        .isEqualTo("{namespace=\"platform-dev\"}");
  }

  @Test
  void buildsAnAlternationForSeveralNamespaces() {
    assertThat(SelectorBuilder.build(request(List.of("platform-dev", "payments-dev"), null, null, null)))
        .isEqualTo("{namespace=~\"platform-dev|payments-dev\"}");
  }

  private static ExportRequest withPods(List<String> pods, String podPattern) {
    return new ExportRequest(
        List.of("platform-dev"), podPattern, null, null,
        Instant.parse("2026-09-20T00:00:00Z"), Instant.parse("2026-09-20T01:00:00Z"),
        List.of(), pods);
  }

  @Test
  void matchesOnePickedPodExactly() {
    assertThat(SelectorBuilder.build(withPods(List.of("api-7d9-x2"), null)))
        // After the stream selector, where it matches a pod carried as
        // structured metadata as well as one carried as an indexed label.
        .isEqualTo("{namespace=\"platform-dev\"} | pod=\"api-7d9-x2\"");
  }

  @Test
  void matchesSeveralPickedPodsAsLiteralsSortedAndOnce() {
    // A dot in a pod name is a literal dot, not "any character".
    assertThat(SelectorBuilder.build(withPods(List.of("worker-0", "api.v2-1", "worker-0"), "")))
        .isEqualTo("{namespace=\"platform-dev\"} | pod=~\"api\\\\.v2-1|worker-0\"");
  }

  @Test
  void refusesAnythingThatIsNotAPodName() {
    assertThatThrownBy(() -> SelectorBuilder.build(withPods(List.of("api\"}|x"), null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not a valid pod name");
    java.util.ArrayList<String> withNull = new java.util.ArrayList<>();
    withNull.add(null);
    assertThatThrownBy(() -> SelectorBuilder.build(withPods(withNull, null)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void refusesPickedPodsAndAPatternTogether() {
    assertThatThrownBy(() -> SelectorBuilder.build(withPods(List.of("api-1"), "api-*")))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("pick pods or give a pod pattern, not both");
  }

  @Test
  void addsPodAndContainerMatchers() {
    assertThat(SelectorBuilder.build(request(List.of("platform-dev"), "api-*", "app", null)))
        .isEqualTo("{namespace=\"platform-dev\", container=~\"app\"} | pod=~\"api\\\\-.*\"");
  }

  @Test
  void addsALiteralLineFilter() {
    assertThat(SelectorBuilder.build(request(List.of("platform-dev"), null, null, "timeout")))
        .isEqualTo("{namespace=\"platform-dev\"} |= \"timeout\"");
  }

  @Test
  void ignoresBlankOptionalFields() {
    assertThat(SelectorBuilder.build(request(List.of("platform-dev"), "  ", "", "   ")))
        .isEqualTo("{namespace=\"platform-dev\"}");
  }

  @Test
  void sizingUsesTheStreamSelectorWithoutTheLineFilter() {
    // A line filter reduces what is written but not what Loki reads, so the
    // honest number to quota against is the unfiltered one.
    assertThat(
            SelectorBuilder.buildStreamSelector(
                request(List.of("platform-dev"), "api-*", null, "timeout")))
        .isEqualTo("{namespace=\"platform-dev\"}");
    // The full query filters by line first, the cheaper of the two, then by pod.
    assertThat(SelectorBuilder.build(request(List.of("platform-dev"), "api-*", null, "timeout")))
        .isEqualTo("{namespace=\"platform-dev\"} |= \"timeout\" | pod=~\"api\\\\-.*\"");
  }

  @Test
  void escapesRegexMetacharactersInAGlob() {
    // Only * and ? are wildcards; everything else is a literal, which rules out
    // both LogQL injection and a pathological regex.
    assertThat(SelectorBuilder.globToRegex("a.b+c(d)")).isEqualTo("a\\\\.b\\\\+c\\\\(d\\\\)");
  }

  @Test
  void translatesGlobWildcards() {
    assertThat(SelectorBuilder.globToRegex("api-?-*")).isEqualTo("api\\\\-.\\\\-.*");
  }

  @Test
  void aQuoteInAGlobCannotEscapeTheStringLiteral() {
    String selector =
        SelectorBuilder.build(request(List.of("platform-dev"), "a\"} |= \"x", null, null));

    // The quote is escaped, so the stream selector is untouched and the
    // injected filter is inert text inside the pod filter's one string.
    assertThat(selector)
        .isEqualTo("{namespace=\"platform-dev\"} | pod=~\"a\\\\\"\\\\}\\\\ \\\\|\\\\=\\\\ \\\\\"x\"");
    assertThat(SelectorBuilder.streamSelectorEnd(selector)).isEqualTo(selector.indexOf('}'));
  }

  @Test
  void escapesQuotesAndBackslashesInALineFilter() {
    assertThat(SelectorBuilder.escapeStringLiteral("say \"hi\"\\")).isEqualTo("say \\\"hi\\\"\\\\");
  }

  @Test
  void rejectsSomethingThatIsNotANamespaceName() {
    assertThatThrownBy(() -> SelectorBuilder.build(request(List.of("Not A Namespace"), null, null, null)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not a valid namespace name");
  }

  @Test
  void rejectsANullNamespace() {
    List<String> withNull = new java.util.ArrayList<>();
    withNull.add(null);

    assertThatThrownBy(() -> SelectorBuilder.build(request(withNull, null, null, null)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsAnOverlongGlob() {
    assertThatThrownBy(() -> SelectorBuilder.globToRegex("a".repeat(257)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void rejectsAnOverlongLineFilter() {
    assertThatThrownBy(() -> SelectorBuilder.escapeStringLiteral("a".repeat(513)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static ExportRequest inClusters(List<String> namespaces, List<String> clusters) {
    return new ExportRequest(
        namespaces, null, null, null, Instant.EPOCH, Instant.EPOCH.plusSeconds(60), clusters);
  }

  @Test
  void matchesASingleClusterExactly() {
    assertThat(SelectorBuilder.build(inClusters(List.of("platform-dev"), List.of("edge-eu")), "cluster"))
        .isEqualTo("{cluster=\"edge-eu\", namespace=\"platform-dev\"}");
  }

  @Test
  void matchesSeveralClustersAsLiteralsInARegex() {
    // A dot is a regex wildcard; left unescaped, "eu.1" would also match "eux1".
    assertThat(
            SelectorBuilder.build(inClusters(List.of("platform-dev"), List.of("edge-eu", "eu.1")), "cluster"))
        .isEqualTo("{cluster=~\"edge-eu|eu\\\\.1\", namespace=\"platform-dev\"}");
  }

  @Test
  void escapesQuotesInAClusterNameSoTheyCannotEndTheString() {
    assertThat(SelectorBuilder.build(inClusters(List.of("a"), List.of("x\"} |= \"y")), "cluster"))
        .startsWith("{cluster=\"x\\\"} |= \\\"y\", namespace");
    assertThat(SelectorBuilder.regexLiteral("a\"b")).isEqualTo("a\\\"b");
  }

  @Test
  void findsTheEndOfTheStreamSelectorPastABraceInsideAClusterName() {
    // The stream selector is what the volume API sizes; cutting it at a brace
    // inside a quoted value would send Loki half a selector.
    String stream =
        SelectorBuilder.buildStreamSelector(
            new ExportRequest(
                List.of("a"), null, null, "needle", Instant.EPOCH, Instant.EPOCH.plusSeconds(60),
                List.of("odd}name")),
            "cluster");
    assertThat(stream).isEqualTo("{cluster=\"odd}name\", namespace=\"a\"}");
  }

  @Test
  void leavesClustersOutWhenThereIsNoClusterLabel() {
    assertThat(SelectorBuilder.build(inClusters(List.of("a"), List.of("edge-eu")), ""))
        .isEqualTo("{namespace=\"a\"}");
    assertThat(SelectorBuilder.build(inClusters(List.of("a"), List.of("edge-eu")), null))
        .isEqualTo("{namespace=\"a\"}");
  }

  @Test
  void selectsEveryNamespaceWhenNoneAreNamed() {
    assertThat(SelectorBuilder.build(inClusters(List.of(), List.of("edge-eu")), "cluster"))
        .isEqualTo("{cluster=\"edge-eu\", namespace=~\".+\"}");
  }

  @Test
  void refusesAClusterLabelThatIsNotALabelName() {
    assertThatThrownBy(() -> SelectorBuilder.clusterSelector("not a label", List.of("x")))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void refusesAnOverlongClusterName() {
    assertThatThrownBy(() -> SelectorBuilder.regexLiteral("x".repeat(257)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void reportsASelectorThatNeverCloses() {
    assertThatThrownBy(() -> SelectorBuilder.streamSelectorEnd("{namespace=\"a\""))
        .isInstanceOf(IllegalStateException.class);
  }
}
