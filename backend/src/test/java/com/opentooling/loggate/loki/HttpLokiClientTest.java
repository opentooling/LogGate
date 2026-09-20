package com.opentooling.loggate.loki;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import com.opentooling.loggate.config.LogGateProperties;
import com.opentooling.loggate.config.TestProperties;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.json.JsonMapper;

class HttpLokiClientTest {

  private static final Instant FROM = Instant.parse("2026-09-20T00:00:00Z");
  private static final Instant TO = Instant.parse("2026-09-20T01:00:00Z");

  private final RestClient.Builder builder = RestClient.builder();
  private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
  private final List<Duration> slept = new ArrayList<>();

  private HttpLokiClient client(String tenantId) {
    return new HttpLokiClient(
        builder.baseUrl("http://loki.test").build(),
        JsonMapper.builder().build(),
        TestProperties.of(
            new LogGateProperties.Namespaces(true, "xyz.com/team", "ad-{team}-{env}", "dev"),
            new LogGateProperties.Loki("http://loki.test", tenantId, 5000, Duration.ofSeconds(30)),
            new LogGateProperties.Windows(1024, Duration.ofMinutes(1), Duration.ofHours(1), 5000)),
        slept::add);
  }

  @Test
  void readsBytesPerNamespaceFromTheVolumeApi() {
    server
        .expect(requestTo(Matchers.containsString("/loki/api/v1/index/volume")))
        .andExpect(queryParam("start", "1789862400000000000"))
        .andRespond(
            withSuccess(
                """
                {"status":"success","data":{"resultType":"vector","result":[
                  {"metric":{"namespace":"platform-dev"},"value":[1789948800,"349552"]},
                  {"metric":{"namespace":"payments-dev"},"value":[1789948800,"351330"]}]}}
                """,
                MediaType.APPLICATION_JSON));

    VolumeEstimate estimate = client("").volume("{namespace=~\"platform-dev|payments-dev\"}", FROM, TO);

    assertThat(estimate.bytesByNamespace())
        .containsEntry("platform-dev", 349_552L)
        .containsEntry("payments-dev", 351_330L);
    assertThat(estimate.totalBytes()).isEqualTo(700_882L);
    server.verify();
  }

  @Test
  void samplesHowManyBytesAQueryMatches() {
    server
        .expect(requestTo(Matchers.containsString("/loki/api/v1/query")))
        // The query arrives percent-encoded, so match on its parts rather than
        // on a literal that would only ever match after decoding.
        .andExpect(requestTo(Matchers.containsString("bytes_over_time")))
        .andExpect(requestTo(Matchers.containsString("300s")))
        .andRespond(
            withSuccess(
                "{\"data\":{\"result\":[{\"metric\":{},\"value\":[1,\"4096\"]}]}}",
                MediaType.APPLICATION_JSON));

    assertThat(
            client("")
                .sampleBytes("{namespace=\"platform-dev\"}", FROM, Duration.ofMinutes(5)))
        .hasValue(4096);
    server.verify();
  }

  @Test
  void reportsNoSampleWhenTheWindowHeldNothing() {
    // An empty window cannot support a conclusion about a filter.
    server
        .expect(requestTo(Matchers.containsString("/loki/api/v1/query")))
        .andRespond(withSuccess("{\"data\":{\"result\":[]}}", MediaType.APPLICATION_JSON));

    assertThat(client("").sampleBytes("{}", FROM, Duration.ofMinutes(5))).isEmpty();
  }

  @Test
  void skipsASampleRowThatCarriesNoValue() {
    server
        .expect(requestTo(Matchers.containsString("/loki/api/v1/query")))
        .andRespond(
            withSuccess(
                "{\"data\":{\"result\":[{\"metric\":{},\"value\":\"nonsense\"},{\"metric\":{},\"value\":[1,\"77\"]}]}}",
                MediaType.APPLICATION_JSON));

    assertThat(client("").sampleBytes("{}", FROM, Duration.ofMinutes(5))).hasValue(77);
  }

  @Test
  void treatsAnEmptyVolumeResultAsNothing() {
    server
        .expect(requestTo(Matchers.containsString("/index/volume")))
        .andRespond(withSuccess("{\"status\":\"success\",\"data\":{\"result\":[]}}", MediaType.APPLICATION_JSON));

    assertThat(client("").volume("{}", FROM, TO).totalBytes()).isZero();
  }

  @Test
  void mergesAndOrdersEntriesFromEveryStream() {
    // Loki returns one array per stream; the pager needs a single ordering.
    server
        .expect(requestTo(Matchers.containsString("/loki/api/v1/query_range")))
        .andExpect(queryParam("direction", "forward"))
        .andRespond(
            withSuccess(
                """
                {"status":"success","data":{"resultType":"streams","result":[
                  {"stream":{"pod":"api-1"},"values":[["300","third"],["100","first"]]},
                  {"stream":{"pod":"api-0"},"values":[["200","second"]]}]}}
                """,
                MediaType.APPLICATION_JSON));

    QueryPage page = client("").queryRange("{}", FROM, TO, 5000);

    assertThat(page.entries()).extracting(LogEntry::line).containsExactly("first", "second", "third");
    assertThat(page.entries().getFirst().labels()).containsEntry("pod", "api-1");
    assertThat(page.isFull()).isFalse();
  }

  @Test
  void sendsTheTenantHeaderWhenOneIsConfigured() {
    // Exports run against their own tenant so a large extraction cannot starve
    // interactive queries.
    server
        .expect(requestTo(Matchers.containsString("/query_range")))
        .andExpect(header("X-Scope-OrgID", "exports"))
        .andRespond(withSuccess("{\"data\":{\"result\":[]}}", MediaType.APPLICATION_JSON));

    client("exports").queryRange("{}", FROM, TO, 10);

    server.verify();
  }

  @Test
  void retriesAfterRateLimitingAndHonoursRetryAfter() {
    server
        .expect(requestTo(Matchers.containsString("/query_range")))
        .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "7"));
    server
        .expect(requestTo(Matchers.containsString("/query_range")))
        .andRespond(withSuccess("{\"data\":{\"result\":[]}}", MediaType.APPLICATION_JSON));

    QueryPage page = client("").queryRange("{}", FROM, TO, 10);

    assertThat(page.entries()).isEmpty();
    assertThat(slept).containsExactly(Duration.ofSeconds(7));
    server.verify();
  }

  @Test
  void backsOffExponentiallyWhenThereIsNoRetryAfter() {
    server
        .expect(ExpectedCount.times(2), requestTo(Matchers.containsString("/query_range")))
        .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
    server
        .expect(requestTo(Matchers.containsString("/query_range")))
        .andRespond(withSuccess("{\"data\":{\"result\":[]}}", MediaType.APPLICATION_JSON));

    client("").queryRange("{}", FROM, TO, 10);

    assertThat(slept).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2));
  }

  @Test
  void ignoresAnUnparseableRetryAfterAndFallsBackToBackoff() {
    server
        .expect(requestTo(Matchers.containsString("/query_range")))
        .andRespond(
            withStatus(HttpStatus.TOO_MANY_REQUESTS).header("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT"));
    server
        .expect(requestTo(Matchers.containsString("/query_range")))
        .andRespond(withSuccess("{\"data\":{\"result\":[]}}", MediaType.APPLICATION_JSON));

    client("").queryRange("{}", FROM, TO, 10);

    assertThat(slept).containsExactly(Duration.ofSeconds(1));
  }

  @Test
  void doesNotRetryARequestThatIsOurOwnFault() {
    // A bad query is not load, and retrying it just wastes the budget.
    server
        .expect(requestTo(Matchers.containsString("/query_range")))
        .andRespond(withStatus(HttpStatus.BAD_REQUEST));

    assertThatThrownBy(() -> client("").queryRange("{bad", FROM, TO, 10))
        .isInstanceOf(LokiException.class)
        .hasMessageContaining("400");
    assertThat(slept).isEmpty();
  }

  @Test
  void givesUpAfterTheRetryBudgetIsSpent() {
    server
        .expect(ExpectedCount.times(5), requestTo(Matchers.containsString("/query_range")))
        .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

    assertThatThrownBy(() -> client("").queryRange("{}", FROM, TO, 10))
        .isInstanceOf(LokiException.class)
        .hasMessageContaining("after 5 attempts");
  }

  @Test
  void rejectsATimestampThatIsNotANumber() {
    server
        .expect(requestTo(Matchers.containsString("/query_range")))
        .andRespond(
            withSuccess(
                "{\"data\":{\"result\":[{\"stream\":{},\"values\":[[\"not-a-number\",\"x\"]]}]}}",
                MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client("").queryRange("{}", FROM, TO, 10))
        .isInstanceOf(LokiException.class)
        .hasMessageContaining("not a number");
  }

  @Test
  void rejectsAVolumeThatIsNotANumber() {
    server
        .expect(requestTo(Matchers.containsString("/index/volume")))
        .andRespond(
            withSuccess(
                "{\"data\":{\"result\":[{\"metric\":{\"namespace\":\"x\"},\"value\":[1,\"huge\"]}]}}",
                MediaType.APPLICATION_JSON));

    assertThatThrownBy(() -> client("").volume("{}", FROM, TO))
        .isInstanceOf(LokiException.class)
        .hasMessageContaining("not a number");
  }

  @Test
  void skipsMalformedRowsRatherThanFailingTheWholePage() {
    server
        .expect(requestTo(Matchers.containsString("/query_range")))
        .andRespond(
            withSuccess(
                """
                {"data":{"result":[{"stream":{"pod":"api-0"},"values":[["100"],["200","kept"]]}]}}
                """,
                MediaType.APPLICATION_JSON));

    assertThat(client("").queryRange("{}", FROM, TO, 10).entries())
        .extracting(LogEntry::line)
        .containsExactly("kept");
  }

  @Test
  void treatsNoRetryAfterHeaderAtAllAsNoGuidance() {
    assertThat(HttpLokiClient.retryAfter(null)).isEmpty();
    assertThat(HttpLokiClient.retryAfter(new org.springframework.http.HttpHeaders())).isEmpty();
  }

  @Test
  void readsRetryAfterWhenPresent() {
    var headers = new org.springframework.http.HttpHeaders();
    headers.set("Retry-After", " 12 ");

    assertThat(HttpLokiClient.retryAfter(headers)).contains(Duration.ofSeconds(12));
  }

  @Test
  void failsClearlyWhenInterruptedWhileBackingOff() {
    // A worker being shut down mid-backoff must stop, not swallow the signal.
    var interrupting =
        new HttpLokiClient(
            builder.baseUrl("http://loki.test").build(),
            JsonMapper.builder().build(),
            TestProperties.of(
                new LogGateProperties.Namespaces(true, "xyz.com/team", "ad-{team}-{env}", "dev"),
                new LogGateProperties.Loki("http://loki.test", "", 5000, Duration.ofSeconds(30)),
                new LogGateProperties.Windows(1024, Duration.ofMinutes(1), Duration.ofHours(1), 5000)),
            duration -> {
              throw new InterruptedException("shutting down");
            });
    server
        .expect(requestTo(Matchers.containsString("/query_range")))
        .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS));

    assertThatThrownBy(() -> interrupting.queryRange("{}", FROM, TO, 10))
        .isInstanceOf(LokiException.class)
        .hasMessageContaining("interrupted");
    assertThat(Thread.interrupted()).isTrue();
  }

  @Test
  void treatsAnEmptyResponseBodyAsAnEmptyPage() {
    server
        .expect(requestTo(Matchers.containsString("/query_range")))
        .andRespond(withStatus(HttpStatus.OK));

    assertThat(client("").queryRange("{}", FROM, TO, 10).entries()).isEmpty();
  }

  @Test
  void skipsRowsWhoseValueIsNotAnArray() {
    server
        .expect(requestTo(Matchers.containsString("/query_range")))
        .andRespond(
            withSuccess(
                "{\"data\":{\"result\":[{\"stream\":{},\"values\":[\"nonsense\",[\"200\",\"kept\"]]}]}}",
                MediaType.APPLICATION_JSON));

    assertThat(client("").queryRange("{}", FROM, TO, 10).entries())
        .extracting(LogEntry::line)
        .containsExactly("kept");
  }

  @Test
  void skipsVolumeRowsWhoseValueIsMalformed() {
    server
        .expect(requestTo(Matchers.containsString("/index/volume")))
        .andRespond(
            withSuccess(
                "{\"data\":{\"result\":[{\"metric\":{\"namespace\":\"a\"},\"value\":\"oops\"},{\"metric\":{\"namespace\":\"b\"},\"value\":[1,\"3\"]}]}}",
                MediaType.APPLICATION_JSON));

    assertThat(client("").volume("{}", FROM, TO).bytesByNamespace())
        .containsExactly(java.util.Map.entry("b", 3L));
  }

  @Test
  void skipsVolumeRowsWithNoNamespace() {
    server
        .expect(requestTo(Matchers.containsString("/index/volume")))
        .andRespond(
            withSuccess(
                "{\"data\":{\"result\":[{\"metric\":{},\"value\":[1,\"5\"]},{\"metric\":{\"namespace\":\"x\"},\"value\":[1,\"7\"]}]}}",
                MediaType.APPLICATION_JSON));

    assertThat(client("").volume("{}", FROM, TO).bytesByNamespace()).containsExactly(java.util.Map.entry("x", 7L));
  }
}
