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

  @Test
  void addsPodAndContainerMatchers() {
    assertThat(SelectorBuilder.build(request(List.of("platform-dev"), "api-*", "app", null)))
        .isEqualTo("{namespace=\"platform-dev\", pod=~\"api\\\\-.*\", container=~\"app\"}");
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
        .isEqualTo("{namespace=\"platform-dev\", pod=~\"api\\\\-.*\"}");
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

    // The quote is escaped, so the selector still has exactly one closing brace
    // and the injected filter is inert text.
    assertThat(selector).contains("\\\\\"").endsWith("\"}");
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
}
