package com.opentooling.loggate.pods;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.headerDoesNotExist;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.opentooling.loggate.config.LogGateProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class PrometheusPodSourceTest {

  private static final Instant FROM = Instant.parse("2026-09-20T00:00:30Z");
  private static final Instant TO = Instant.parse("2026-09-20T01:00:10Z");
  private static final String TWO_PODS =
      """
      {"status":"success","data":[
        {"__name__":"kube_pod_info","namespace":"payments-dev","pod":"api-7d9-x2"},
        {"__name__":"kube_pod_info","namespace":"platform-dev","pod":"worker-0"},
        {"__name__":"kube_pod_info","namespace":"platform-dev","pod":"api-5c-q1"},
        {"__name__":"kube_pod_info","namespace":"platform-dev","pod":"api-5c-q1","uid":"other"},
        {"__name__":"kube_pod_info","namespace":"platform-dev"}]}
      """;

  private final RestClient.Builder builder = RestClient.builder();
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
  private Instant now = Instant.parse("2026-09-20T02:00:00Z");
  private final Clock clock =
      new Clock() {
        @Override
        public ZoneOffset getZone() {
          return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
          return this;
        }

        @Override
        public Instant instant() {
          return now;
        }
      };

  private static LogGateProperties.Pods settings(
      String metric, String clusterLabel, String tenant, String tokenFile, int maxPods) {
    return new LogGateProperties.Pods(
        "http://prometheus.test", metric, "pod", "namespace", clusterLabel, tenant, tokenFile, "",
        Duration.ofSeconds(5), maxPods, Duration.ofSeconds(60), true);
  }

  private PrometheusPodSource source(LogGateProperties.Pods settings, String lokiClusterLabel) {
    return new PrometheusPodSource(
        builder.baseUrl("http://prometheus.test").build(),
        JsonMapper.builder().build(),
        settings,
        lokiClusterLabel,
        clock);
  }

  private PrometheusPodSource source() {
    return source(settings("kube_pod_info", "", "", "", 1000), "cluster");
  }

  @Test
  void listsPodsFromTheSeriesApiOverTheExportRange() {
    server
        .expect(requestTo(Matchers.containsString("/api/v1/series")))
        // As the server decodes it: the parameter name and the selector are
        // both percent-encoded on the wire.
        .andExpect(
            request ->
                assertThat(
                        java.net.URLDecoder.decode(
                            request.getURI().getRawQuery(), java.nio.charset.StandardCharsets.UTF_8))
                    .contains(
                        "match[]=kube_pod_info{namespace=~\"payments-dev|platform-dev\","
                            + " cluster=~\"edge-eu\"}&"))
        // Widened to whole minutes, so a range that moves with the clock is
        // still the same question.
        .andExpect(queryParam("start", "1789862400"))
        .andExpect(queryParam("end", "1789866060"))
        .andExpect(queryParam("limit", "1001"))
        .andExpect(headerDoesNotExist("X-Scope-OrgID"))
        .andExpect(headerDoesNotExist("Authorization"))
        .andRespond(withSuccess(TWO_PODS, MediaType.APPLICATION_JSON));

    PodListing listing =
        source().list(List.of("edge-eu"), List.of("platform-dev", "payments-dev"), FROM, TO);

    assertThat(listing.available()).isTrue();
    assertThat(listing.truncated()).isFalse();
    // Sorted, de-duplicated, and a series with no pod label ignored.
    assertThat(listing.pods())
        .containsExactly(
            new PodInfo("payments-dev", "api-7d9-x2"),
            new PodInfo("platform-dev", "api-5c-q1"),
            new PodInfo("platform-dev", "worker-0"));
    server.verify();
  }

  @Test
  void addsItsMatchersToTheOperatorsOwnSelector() {
    PrometheusPodSource withMatchers =
        source(settings("kube_pod_info{job=\"kube-state-metrics\"}", "", "", "", 10), "");
    assertThat(withMatchers.selector(List.of("edge-eu"), List.of("a")))
        // No cluster label anywhere, so no cluster matcher whatever is asked.
        .isEqualTo("kube_pod_info{job=\"kube-state-metrics\", namespace=~\"a\"}");

    PrometheusPodSource emptyBlock = source(settings("kube_pod_info{ }", "k8s_cluster", "", "", 10), "cluster");
    assertThat(emptyBlock.selector(List.of("b.c", "a"), List.of("a")))
        // Its own cluster label wins over Loki's, and values are regex literals.
        .isEqualTo("kube_pod_info{namespace=~\"a\", k8s_cluster=~\"a|b\\\\.c\"}");

    PrometheusPodSource nameOnly = source(settings("{__name__=\"up\"}", "", "", "", 10), null);
    assertThat(nameOnly.selector(List.of(), List.of("a", "a")))
        .isEqualTo("{__name__=\"up\", namespace=~\"a\"}");
  }

  @Test
  void refusesToListWithoutNamespacesOrWithAnInvalidOne() {
    assertThatThrownBy(() -> source().list(List.of(), List.of(), FROM, TO))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> source().list(List.of(), List.of("Not_A_Namespace"), FROM, TO))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Not_A_Namespace");
    java.util.ArrayList<String> withNull = new java.util.ArrayList<>();
    withNull.add(null);
    assertThatThrownBy(() -> source().list(List.of(), withNull, FROM, TO))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void saysWhenItCutTheListShort() {
    server
        .expect(requestTo(Matchers.containsString("/api/v1/series")))
        .andExpect(queryParam("limit", "3"))
        .andRespond(withSuccess(TWO_PODS, MediaType.APPLICATION_JSON));

    PodListing listing =
        source(settings("kube_pod_info", "", "", "", 2), "").list(List.of(), List.of("a"), FROM, TO);

    assertThat(listing.truncated()).isTrue();
    assertThat(listing.pods()).hasSize(2);
  }

  @Test
  void sendsTheTenantAndAFreshlyReadBearerToken(@TempDir Path dir) throws Exception {
    Path token = dir.resolve("token");
    Files.writeString(token, "first\n");
    PrometheusPodSource source =
        source(settings("kube_pod_info", "", "team-a", token.toString(), 10), "");
    server
        .expect(ExpectedCount.once(), requestTo(Matchers.containsString("/api/v1/series")))
        .andExpect(header("X-Scope-OrgID", "team-a"))
        .andExpect(header("Authorization", "Bearer first"))
        .andRespond(withSuccess(TWO_PODS, MediaType.APPLICATION_JSON));
    server
        .expect(ExpectedCount.once(), requestTo(Matchers.containsString("/api/v1/series")))
        .andExpect(header("Authorization", "Bearer rotated"))
        .andRespond(withSuccess(TWO_PODS, MediaType.APPLICATION_JSON));

    source.list(List.of(), List.of("a"), FROM, TO);
    Files.writeString(token, "rotated");
    source.list(List.of(), List.of("b"), FROM, TO);
    server.verify();
  }

  @Test
  void failsWhenTheTokenCannotBeRead(@TempDir Path dir) {
    PrometheusPodSource source =
        source(settings("kube_pod_info", "", "", dir.resolve("missing").toString(), 10), "");
    assertThatThrownBy(() -> source.list(List.of(), List.of("a"), FROM, TO))
        .isInstanceOf(MetricsException.class)
        .hasMessageContaining("bearer token");
  }

  @Test
  void reusesAnAnswerForAMinuteAndAStaleOneWhenTheEndpointFails() {
    PrometheusPodSource source = source();
    server
        .expect(ExpectedCount.once(), requestTo(Matchers.containsString("/api/v1/series")))
        .andRespond(withSuccess(TWO_PODS, MediaType.APPLICATION_JSON));
    server
        .expect(ExpectedCount.once(), requestTo(Matchers.containsString("/api/v1/series")))
        .andRespond(withServerError());

    PodListing first = source.list(List.of(), List.of("a"), FROM, TO);
    // Within the cache lifetime: no request at all.
    assertThat(source.list(List.of(), List.of("a"), FROM, TO)).isEqualTo(first);
    // Past it, the endpoint fails, and the last answer is better than none.
    now = now.plus(Duration.ofMinutes(2));
    assertThat(source.list(List.of(), List.of("a"), FROM, TO)).isEqualTo(first);
    server.verify();
  }

  @Test
  void failsWhenTheEndpointFailsWithNothingToFallBackOn() {
    server
        .expect(requestTo(Matchers.containsString("/api/v1/series")))
        .andRespond(withServerError());
    assertThatThrownBy(() -> source().list(List.of(), List.of("a"), FROM, TO))
        .isInstanceOf(MetricsException.class)
        .hasMessageContaining("did not answer");
  }

  @Test
  void startsAFreshCacheRatherThanGrowingForever() {
    PrometheusPodSource source = source();
    server
        .expect(ExpectedCount.times(257), requestTo(Matchers.containsString("/api/v1/series")))
        .andRespond(withSuccess(TWO_PODS, MediaType.APPLICATION_JSON));
    for (int i = 0; i <= 256; i++) {
      source.list(List.of(), List.of("ns-" + i), FROM, TO);
    }
    server.verify();
  }

  @Test
  void refusesAnswersThatAreNotASuccessfulSeriesListing() {
    PrometheusPodSource source = source();
    assertThatThrownBy(() -> source.parse("<html>"))
        .isInstanceOf(MetricsException.class)
        .hasMessageContaining("JSON");
    assertThatThrownBy(() -> source.parse(null)).isInstanceOf(MetricsException.class);
    assertThatThrownBy(() -> source.parse("{\"status\":\"error\",\"error\":\"bad match[]\"}"))
        .isInstanceOf(MetricsException.class)
        .hasMessageContaining("bad match[]");
    assertThatThrownBy(() -> source.parse("{\"status\":\"error\"}"))
        .hasMessageContaining("no reason given");
    assertThat(source.parse("{\"status\":\"success\"}").pods()).isEmpty();
  }

  @Test
  void refusesAConfigurationThatCannotMakeAQuery() {
    assertThatThrownBy(() -> source(settings(" ", "", "", "", 10), ""))
        .hasMessageContaining("loggate.pods.metric");
    assertThatThrownBy(() -> source(settings("rate(x[5m])", "", "", "", 10), ""))
        .hasMessageContaining("loggate.pods.metric");
    assertThatThrownBy(
            () ->
                source(
                    new LogGateProperties.Pods(
                        "http://p", "kube_pod_info", "pod name", "namespace", "", "", "", "",
                        Duration.ofSeconds(1), 10, Duration.ofSeconds(1), true),
                    ""))
        .hasMessageContaining("loggate.pods.pod-label");
    assertThatThrownBy(
            () ->
                source(
                    new LogGateProperties.Pods(
                        "http://p", "kube_pod_info", "pod", "1ns", "", "", "", "",
                        Duration.ofSeconds(1), 10, Duration.ofSeconds(1), true),
                    ""))
        .hasMessageContaining("loggate.pods.namespace-label");
    assertThatThrownBy(() -> source(settings("kube_pod_info", "bad-label", "", "", 10), ""))
        .hasMessageContaining("loggate.pods.cluster-label");
    assertThatThrownBy(() -> source(settings("kube_pod_info", "", "", "", 0), ""))
        .hasMessageContaining("max-pods");
  }

  @Test
  void noSourceListsNothing() {
    NoPodSource none = new NoPodSource();
    assertThat(none.enabled()).isFalse();
    assertThat(none.list(List.of(), List.of("a"), FROM, TO)).isEqualTo(PodListing.unavailable());
    assertThat(source().enabled()).isTrue();
    assertThat(new MetricsException("x").getMessage()).isEqualTo("x");
  }

  @Test
  void keepsABasePathSuchAsMimirsAndSendsItsTenant() {
    // Mimir serves the Prometheus API under /prometheus and requires a tenant
    // when multi-tenancy is on, which is its default.
    RestClient.Builder mimirBuilder = RestClient.builder();
    MockRestServiceServer mimir = MockRestServiceServer.bindTo(mimirBuilder).build();
    mimir
        .expect(requestTo(Matchers.startsWith("http://mimir.test/prometheus/api/v1/series?")))
        .andExpect(header("X-Scope-OrgID", "platform"))
        .andRespond(withSuccess(TWO_PODS, MediaType.APPLICATION_JSON));

    PrometheusPodSource source =
        new PrometheusPodSource(
            mimirBuilder.baseUrl("http://mimir.test/prometheus").build(),
            JsonMapper.builder().build(),
            settings("kube_pod_info", "", "platform", "", 10),
            "",
            clock);

    assertThat(source.list(List.of(), List.of("platform-dev"), FROM, TO).pods()).hasSize(3);
    mimir.verify();
  }
}
