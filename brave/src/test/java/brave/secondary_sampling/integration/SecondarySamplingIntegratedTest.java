/*
 * Copyright 2019-2020 The OpenZipkin Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package brave.secondary_sampling.integration;

import brave.Tracing;
import brave.handler.MutableSpan;
import brave.propagation.B3SinglePropagation;
import brave.propagation.Propagation;
import brave.sampler.RateLimitingSampler;
import brave.sampler.Sampler;
import brave.secondary_sampling.FakeHttpRequest;
import brave.secondary_sampling.MutableSecondarySamplingState;
import brave.secondary_sampling.SamplerController;
import brave.secondary_sampling.SecondarySampling;
import brave.secondary_sampling.SecondarySamplingState;
import brave.secondary_sampling.TraceForwarder;
import brave.test.TestSpanHandler;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.After;
import org.junit.Test;
import zipkin2.DependencyLink;
import zipkin2.Endpoint;
import zipkin2.Span;
import zipkin2.internal.DependencyLinker;
import zipkin2.storage.GroupByTraceId;

import static brave.http.HttpRequestMatchers.pathStartsWith;
import static brave.rpc.RpcRequestMatchers.methodEquals;
import static brave.secondary_sampling.SecondarySamplers.passive;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * This is a <a href="https://github.com/openzipkin-contrib/zipkin-secondary-sampling/tree/master/docs/design.md">Secondary
 * Sampling</a> proof of concept.
 */
// intentionally not in package brave.secondary_sampling to ensure we exposed what we need
public class SecondarySamplingIntegratedTest {
  TestSpanHandler zipkin = new TestSpanHandler();
  TestSpanHandler gatewayplay = new TestSpanHandler();
  TestSpanHandler authcache = new TestSpanHandler();
  TestSpanHandler license = new TestSpanHandler();

  TraceForwarder traceForwarder = new TraceForwarder()
    .configureSamplingKey("b3", zipkin)
    .configureSamplingKey("gatewayplay", gatewayplay)
    .configureSamplingKey("authcache", authcache)
    .configureSamplingKey("license100pct", license);

  Propagation.Factory b3 = B3SinglePropagation.FACTORY;

  SamplerController gatewayplaySampler = new SamplerController.Default()
    .putSecondaryHttpRule("gateway", "gatewayplay",
      pathStartsWith("/play"), RateLimitingSampler.create(50)
    )
    .putSecondaryRule("playback", "gatewayplay",
      passive()
    );

  SamplerController authcacheSampler = new SamplerController.Default()
    .putSecondaryRpcRule("auth", "authcache",
      methodEquals("GetToken"), RateLimitingSampler.create(100), 1 // ttl
    );

  SamplerController licenseSampler = new SamplerController.Default()
    .putProvisioner("license", (request, callback) -> callback.addSamplingState(
      SecondarySamplingState.create(
        MutableSecondarySamplingState.create("license100pct")),
      true
    ))
    .putSecondaryRule("cache", "license100pct",
      passive()
    )
    .putSecondaryRule("licensedb", "license100pct",
      passive()
    );

  SamplerController allSampler = new SamplerController.Default()
    .putSecondaryHttpRule("gateway", "gatewayplay",
      pathStartsWith("/play"), RateLimitingSampler.create(50)
    )
    .putSecondaryRule("playback", "gatewayplay",
      passive()
    ).putSecondaryRpcRule("auth", "authcache",
      methodEquals("GetToken"), RateLimitingSampler.create(100), 1
    );

  Function<String, SecondarySampling> configureGatewayPlay = localServiceName ->
    SecondarySampling.newBuilder()
      .propagationFactory(b3)
      .httpServerSampler(gatewayplaySampler.primaryHttpSampler(localServiceName))
      .secondarySampler(gatewayplaySampler.secondarySampler(localServiceName)).build();

  Function<String, SecondarySampling> configureAuthCache = localServiceName ->
    SecondarySampling.newBuilder()
      .propagationFactory(b3)
      .httpServerSampler(authcacheSampler.primaryHttpSampler(localServiceName))
      .secondarySampler(authcacheSampler.secondarySampler(localServiceName)).build();

  Function<String, SecondarySampling> configureLicense = localServiceName ->
    SecondarySampling.newBuilder()
      .propagationFactory(b3)
      .secondarySampler(licenseSampler.secondarySampler(localServiceName))
      .provisioner(licenseSampler.secondaryProvisioner(localServiceName)).build();

  Function<String, SecondarySampling> configureAllSampling = localServiceName ->
    SecondarySampling.newBuilder()
      .propagationFactory(b3)
      .httpServerSampler(allSampler.primaryHttpSampler(localServiceName))
      .secondarySampler(allSampler.secondarySampler(localServiceName)).build();

  // gatewayplay -> api -> auth -> cache  -> authdb
  //                -> recommendations -> cache -> recodb
  //                -> playback -> license -> cache -> licensedb
  //                            -> moviemetadata
  //                            -> streams
  TracedNode serviceRoot;

  /**
   * Base case tests explicitly specify B3 headers.
   *
   * <p>All other tests use {@link SamplerController} for B3 propagation rules.
   */
  @Test public void baseCase_nothingToZipkinWhenB3Unsampled() {
    serviceRoot = TracedNode.createServiceGraph(s -> null, zipkin);

    Stream.of("/recommend", "/play").forEach(path -> {
        FakeHttpRequest.Client request = new FakeHttpRequest.Client(path);
        request.header("b3", "0");

        serviceRoot.execute(request);
      }
    );

    assertThat(zipkin).isEmpty();
    assertThat(gatewayplay).isEmpty();
    assertThat(authcache).isEmpty();
  }

  @Test public void baseCase_reportsToZipkinWhenB3Sampled() {
    serviceRoot = TracedNode.createServiceGraph(s -> null, zipkin);

    Stream.of("/recommend", "/play").forEach(path -> {
        FakeHttpRequest.Client request = new FakeHttpRequest.Client(path);
        request.header("b3", "1");

        serviceRoot.execute(request);
      }
    );

    // Only reports to Zipkin
    assertThat(traceCount(zipkin)).isEqualTo(2);
    assertThat(gatewayplay).isEmpty();
    assertThat(authcache).isEmpty();
  }

  @Test public void baseCase_routingToRecommendations() { // sanity check
    serviceRoot = TracedNode.createServiceGraph(s -> null, zipkin);

    FakeHttpRequest.Client request = new FakeHttpRequest.Client("/recommend");
    request.header("b3", "1");

    serviceRoot.execute(request);

    // Does not accidentally call playback services
    assertThat(zipkin).extracting(MutableSpan::localServiceName)
      .containsOnly("api", "auth", "authdb", "cache", "gateway", "recodb", "recommendations");
  }

  @Test public void baseCase_routingToPlayback() { // sanity check
    serviceRoot = TracedNode.createServiceGraph(s -> null, zipkin);

    FakeHttpRequest.Client request = new FakeHttpRequest.Client("/play");
    request.header("b3", "1");

    serviceRoot.execute(request);

    // Does not accidentally call recommendations services
    assertThat(zipkin).extracting(MutableSpan::localServiceName).containsOnly(
      "api",
      "auth",
      "authdb",
      "cache",
      "gateway",
      "license",
      "licensedb",
      "moviemetadata",
      "playback",
      "streams"
    );
  }

  @Test public void baseCase_b3_unsampled() { // sanity check
    serviceRoot = TracedNode.createServiceGraph(s -> null, zipkin);

    Stream.of("/recommend", "/play").forEach(path -> {
        FakeHttpRequest.Client request = new FakeHttpRequest.Client(path);
        request.header("b3", "0");

        serviceRoot.execute(request);
      }
    );

    assertThat(getDependencies(zipkin)).isEmpty();
    assertThat(getDependencies(gatewayplay)).isEmpty();
    assertThat(getDependencies(authcache)).isEmpty();
  }

  @Test public void gatewayplay_b3_unsampled() {
    serviceRoot = TracedNode.createServiceGraph(configureGatewayPlay, traceForwarder);

    gatewayplaySampler.putPrimaryHttpRule("gateway", pathStartsWith("/"), Sampler.NEVER_SAMPLE);

    Stream.of("/recommend", "/play").forEach(path -> {
        FakeHttpRequest.Client request = new FakeHttpRequest.Client(path);
        request.header("sampling", "gatewayplay");

        serviceRoot.execute(request);
      }
    );

    assertThat(getDependencies(zipkin)).isEmpty();
    assertThat(getDependencies(gatewayplay)).containsExactly(
      DependencyLink.newBuilder().parent("gateway").child("playback").callCount(1).build()
    );
    assertThat(getDependencies(authcache)).isEmpty();

    // Hit playback directly as opposed to via the gateway. This should not increase the trace count
    FakeHttpRequest.Client request = new FakeHttpRequest.Client("/play");
    request.header("sampling", "gatewayplay");

    serviceRoot.findDownStream("playback").execute(request);
    assertThat(traceCount(gatewayplay)).isEqualTo(1);
  }

  @Test public void gatewayplay_b3_sampled() {
    serviceRoot = TracedNode.createServiceGraph(configureGatewayPlay, traceForwarder);

    gatewayplaySampler.putPrimaryHttpRule("gateway", pathStartsWith("/"), Sampler.ALWAYS_SAMPLE);

    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("sampling", "gatewayplay");

    Stream.of("/recommend", "/play").forEach(path -> {
        FakeHttpRequest.Client request = new FakeHttpRequest.Client(path);
        request.header("sampling", "gatewayplay");

        serviceRoot.execute(request);
      }
    );

    assertThat(getDependencies(zipkin)).isNotEmpty();
    assertThat(getDependencies(gatewayplay)).containsExactly( // doesn't double-count!
      DependencyLink.newBuilder().parent("gateway").child("playback").callCount(1).build()
    );
    assertThat(getDependencies(authcache)).isEmpty();
  }

  @Test public void authcache_b3_unsampled() {
    serviceRoot = TracedNode.createServiceGraph(configureAuthCache, traceForwarder);

    authcacheSampler.putPrimaryHttpRule("gateway", pathStartsWith("/"), Sampler.NEVER_SAMPLE);

    Stream.of("/recommend", "/play").forEach(path -> {
        FakeHttpRequest.Client request = new FakeHttpRequest.Client(path);
        request.header("sampling", "authcache");

        serviceRoot.execute(request);
      }
    );

    assertThat(getDependencies(zipkin)).isEmpty();
    assertThat(getDependencies(gatewayplay)).isEmpty();
    assertThat(getDependencies(authcache)).containsExactly(
      DependencyLink.newBuilder().parent("auth").child("cache").callCount(2).build()
    );
  }

  @Test public void authcache_b3_sampled() {
    serviceRoot = TracedNode.createServiceGraph(configureAuthCache, traceForwarder);

    authcacheSampler.putPrimaryHttpRule("gateway", pathStartsWith("/"), Sampler.ALWAYS_SAMPLE);

    Stream.of("/recommend", "/play").forEach(path -> {
        FakeHttpRequest.Client request = new FakeHttpRequest.Client(path);
        request.header("sampling", "authcache");

        serviceRoot.execute(request);
      }
    );

    assertThat(getDependencies(zipkin)).isNotEmpty();
    assertThat(getDependencies(gatewayplay)).isEmpty();
    assertThat(getDependencies(authcache)).containsExactly( // doesn't double-count!
      DependencyLink.newBuilder().parent("auth").child("cache").callCount(2).build()
    );
  }

  @Test public void all_b3_unsampled() {
    serviceRoot = TracedNode.createServiceGraph(configureAllSampling, traceForwarder);

    allSampler.putPrimaryHttpRule("gateway", pathStartsWith("/"), Sampler.NEVER_SAMPLE);

    Stream.of("/recommend", "/play").forEach(path -> {
        FakeHttpRequest.Client request = new FakeHttpRequest.Client(path);
        request.header("sampling", "gatewayplay,authcache");

        serviceRoot.execute(request);
      }
    );

    assertThat(getDependencies(zipkin)).isEmpty();
    assertThat(getDependencies(gatewayplay)).containsExactly(
      DependencyLink.newBuilder().parent("gateway").child("playback").callCount(1).build()
    );
    assertThat(getDependencies(authcache)).containsExactly(
      DependencyLink.newBuilder().parent("auth").child("cache").callCount(2).build()
    );
  }

  @Test public void all_b3_sampled() {
    serviceRoot = TracedNode.createServiceGraph(configureAllSampling, traceForwarder);

    allSampler.putPrimaryHttpRule("gateway", pathStartsWith("/"), Sampler.ALWAYS_SAMPLE);

    Stream.of("/recommend", "/play").forEach(path -> {
        FakeHttpRequest.Client request = new FakeHttpRequest.Client(path);
        request.header("sampling", "gatewayplay,authcache");

        serviceRoot.execute(request);
      }
    );

    assertThat(getDependencies(zipkin)).isNotEmpty();
    assertThat(getDependencies(gatewayplay)).containsExactly( // doesn't double-count!
      DependencyLink.newBuilder().parent("gateway").child("playback").callCount(1).build()
    );
    assertThat(getDependencies(authcache)).containsExactly( // doesn't double-count!
      DependencyLink.newBuilder().parent("auth").child("cache").callCount(2).build()
    );
  }

  @Test public void license_provision_and_sample() {
    serviceRoot = TracedNode.createServiceGraph(configureLicense, traceForwarder);

    FakeHttpRequest.Client request = new FakeHttpRequest.Client("/play");
    request.header("b3", "0");

    serviceRoot.execute(request);

    assertThat(getDependencies(zipkin)).isEmpty();
    assertThat(getDependencies(gatewayplay)).isEmpty();
    assertThat(getDependencies(authcache)).isEmpty();
    assertThat(getDependencies(license)).containsExactly(
      DependencyLink.newBuilder().parent("license").child("cache").callCount(1).build(),
      DependencyLink.newBuilder().parent("cache").child("licensedb").callCount(1).build()
    );
  }

  @After public void close() {
    Tracing.current().close();
  }

  static int traceCount(TestSpanHandler spans) {
    return (int) spans.spans().stream().map(MutableSpan::traceId).distinct().count();
  }

  static List<DependencyLink> getDependencies(TestSpanHandler spans) {
    DependencyLinker linker = new DependencyLinker();
    List<Span> zipkinSpans = new ArrayList<>();
    for (MutableSpan span : spans) {
      Span.Builder builder = Span.newBuilder()
          .traceId(span.traceId())
          .parentId(span.parentId())
          .id(span.id())
          .kind(Span.Kind.valueOf(span.kind().name()))
          .shared(span.shared())
          .localEndpoint(Endpoint.newBuilder().serviceName(span.localServiceName()).build());
      if (span.remoteServiceName() != null) {
        builder.remoteEndpoint(Endpoint.newBuilder().serviceName(span.remoteServiceName()).build());
      }
      if (span.tag("error") != null) builder.putTag("error", "");
      zipkinSpans.add(builder.build());
    }
    for (List<Span> trace : GroupByTraceId.create(false).map(zipkinSpans)) {
      linker.putTrace(trace);
    }
    return linker.link();
  }
}
