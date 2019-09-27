/*
 * Copyright 2019 The OpenZipkin Authors
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
import brave.TracingCustomizer;
import brave.propagation.B3SinglePropagation;
import brave.propagation.Propagation;
import brave.secondary_sampling.SecondarySampling;
import brave.secondary_sampling.TestSecondarySampler;
import brave.secondary_sampling.TestSecondarySampler.Trigger;
import brave.secondary_sampling.TraceForwarder;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;
import org.junit.After;
import org.junit.Test;
import zipkin2.DependencyLink;
import zipkin2.reporter.Reporter;
import zipkin2.storage.InMemoryStorage;

import static brave.secondary_sampling.TestSecondarySampler.Trigger.Mode.PASSIVE;
import static brave.secondary_sampling.TraceForwarder.NOOP_CALLBACK;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * This is a <a href="https://github.com/openzipkin-contrib/zipkin-secondary-sampling/tree/master/docs/design.md">Secondary
 * Sampling</a> proof of concept.
 */
// intentionally not in package brave.secondary_sampling to ensure we exposed what we need
public class SecondarySamplingIntegratedTest {
  InMemoryStorage zipkin = InMemoryStorage.newBuilder().build();
  InMemoryStorage gatewayplay = InMemoryStorage.newBuilder().build();
  InMemoryStorage authcache = InMemoryStorage.newBuilder().build();

  Reporter<zipkin2.Span> traceForwarder = new TraceForwarder()
    .configureSamplingKey("b3", zipkin.spanConsumer())
    .configureSamplingKey("gatewayplay", gatewayplay.spanConsumer())
    .configureSamplingKey("authcache", authcache.spanConsumer());

  Propagation.Factory b3 = B3SinglePropagation.FACTORY;

  TestSecondarySampler gatewayplaySampler = new TestSecondarySampler()
    .addTrigger("gatewayplay", "gateway", new Trigger().rps("/play", 50))
    .addTrigger("gatewayplay", "playback", new Trigger().mode(PASSIVE));

  TestSecondarySampler authcacheSampler = new TestSecondarySampler()
    .addTrigger("authcache", "auth", new Trigger().rps(100).ttl(1));

  TestSecondarySampler allSampler = new TestSecondarySampler()
    .merge(gatewayplaySampler)
    .merge(authcacheSampler);

  Function<String, TracingCustomizer> configureGatewayPlay = localServiceName -> builder -> {
    SecondarySampling.newBuilder()
      .propagationFactory(b3)
      .sampler(gatewayplaySampler.forService(localServiceName)).build()
      .customize(builder);
    builder.spanReporter(traceForwarder);
  };
  Function<String, TracingCustomizer> configureAuthCache = localServiceName -> builder -> {
    SecondarySampling.newBuilder()
      .propagationFactory(b3)
      .sampler(authcacheSampler.forService(localServiceName)).build()
      .customize(builder);
    builder.spanReporter(traceForwarder);
  };
  Function<String, TracingCustomizer> configureAllSampling = localServiceName -> builder -> {
    SecondarySampling.newBuilder()
      .propagationFactory(b3)
      .sampler(allSampler.forService(localServiceName)).build()
      .customize(builder);
    builder.spanReporter(traceForwarder);
  };

  // gatewayplay -> api -> auth -> cache  -> authdb
  //                -> recommendations -> cache -> recodb
  //                -> playback -> license -> cache -> licensedb
  //                            -> moviemetadata
  //                            -> streams
  TracedNode serviceRoot;

  @Test public void baseCase_nothingToZipkinWhenB3Unsampled() {
    serviceRoot = TracedNode.createServiceGraph(tracingFunction(s -> TracingCustomizer.NOOP));

    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("b3", "0");

    serviceRoot.execute("/recommend", headers);
    serviceRoot.execute("/play", headers);

    assertThat(zipkin.getTraces()).isEmpty();
    assertThat(gatewayplay.getTraces()).isEmpty();
    assertThat(authcache.getTraces()).isEmpty();
  }

  @Test public void baseCase_reportsToZipkinWhenB3Sampled() {
    serviceRoot = TracedNode.createServiceGraph(tracingFunction(s -> TracingCustomizer.NOOP));

    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("b3", "1");

    serviceRoot.execute("/recommend", headers);
    serviceRoot.execute("/play", headers);

    // Only reports to Zipkin
    assertThat(zipkin.getTraces()).hasSize(2);
    assertThat(gatewayplay.getTraces()).isEmpty();
    assertThat(authcache.getTraces()).isEmpty();
  }

  @Test public void baseCase_routingToRecommendations() throws Exception { // sanity check
    serviceRoot = TracedNode.createServiceGraph(tracingFunction(s -> TracingCustomizer.NOOP));

    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("b3", "1");

    serviceRoot.execute("/recommend", headers);

    // Does not accidentally call playback services
    assertThat(zipkin.getServiceNames().execute())
      .containsExactly("api", "auth", "authdb", "cache", "gateway", "recodb", "recommendations");
  }

  @Test public void baseCase_routingToPlayback() throws Exception { // sanity check
    serviceRoot = TracedNode.createServiceGraph(tracingFunction(s -> TracingCustomizer.NOOP));

    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("b3", "1");

    serviceRoot.execute("/play", headers);

    // Does not accidentally call recommendations services
    assertThat(zipkin.getServiceNames().execute()).containsExactly(
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
    serviceRoot = TracedNode.createServiceGraph(tracingFunction(s -> TracingCustomizer.NOOP));

    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("b3", "0");

    serviceRoot.execute("/recommend", headers);
    serviceRoot.execute("/play", headers);

    assertThat(zipkin.getDependencies()).isEmpty();
    assertThat(gatewayplay.getDependencies()).isEmpty();
    assertThat(authcache.getDependencies()).isEmpty();
  }

  @Test public void gatewayplay_b3_unsampled() {
    serviceRoot = TracedNode.createServiceGraph(tracingFunction(configureGatewayPlay));

    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("b3", "0");
    headers.put("sampling", "gatewayplay");

    serviceRoot.execute("/recommend", headers);
    serviceRoot.execute("/play", headers);

    assertThat(zipkin.getDependencies()).isEmpty();
    assertThat(gatewayplay.getDependencies()).containsExactly(
      DependencyLink.newBuilder().parent("gateway").child("playback").callCount(1).build()
    );
    assertThat(authcache.getDependencies()).isEmpty();

    // Hit playback directly as opposed to via the gateway. This should not increase the trace count
    serviceRoot.findDownStream("playback").execute("/play", headers);
    assertThat(gatewayplay.getTraces()).hasSize(1);
  }

  @Test public void gatewayplay_b3_sampled() {
    serviceRoot = TracedNode.createServiceGraph(tracingFunction(configureGatewayPlay));

    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("b3", "1");
    headers.put("sampling", "gatewayplay");

    serviceRoot.execute("/recommend", headers);
    serviceRoot.execute("/play", headers);

    assertThat(zipkin.getDependencies()).isNotEmpty();
    assertThat(gatewayplay.getDependencies()).containsExactly( // doesn't double-count!
      DependencyLink.newBuilder().parent("gateway").child("playback").callCount(1).build()
    );
    assertThat(authcache.getDependencies()).isEmpty();
  }

  @Test public void authcache_b3_unsampled() {
    serviceRoot = TracedNode.createServiceGraph(tracingFunction(configureAuthCache));

    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("b3", "0");
    headers.put("sampling", "authcache");

    serviceRoot.execute("/recommend", headers);
    serviceRoot.execute("/play", headers);

    assertThat(zipkin.getDependencies()).isEmpty();
    assertThat(gatewayplay.getDependencies()).isEmpty();
    assertThat(authcache.getDependencies()).containsExactly(
      DependencyLink.newBuilder().parent("auth").child("cache").callCount(2).build()
    );
  }

  @Test public void authcache_b3_sampled() {
    serviceRoot = TracedNode.createServiceGraph(tracingFunction(configureAuthCache));

    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("b3", "1");
    headers.put("sampling", "authcache");

    serviceRoot.execute("/recommend", headers);
    serviceRoot.execute("/play", headers);

    assertThat(zipkin.getDependencies()).isNotEmpty();
    assertThat(gatewayplay.getDependencies()).isEmpty();
    assertThat(authcache.getDependencies()).containsExactly( // doesn't double-count!
      DependencyLink.newBuilder().parent("auth").child("cache").callCount(2).build()
    );
  }

  @Test public void all_b3_unsampled() {
    serviceRoot = TracedNode.createServiceGraph(tracingFunction(configureAllSampling));

    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("b3", "0");
    headers.put("sampling", "gatewayplay,authcache");

    serviceRoot.execute("/recommend", headers);
    serviceRoot.execute("/play", headers);

    assertThat(zipkin.getDependencies()).isEmpty();
    assertThat(gatewayplay.getDependencies()).containsExactly(
      DependencyLink.newBuilder().parent("gateway").child("playback").callCount(1).build()
    );
    assertThat(authcache.getDependencies()).containsExactly(
      DependencyLink.newBuilder().parent("auth").child("cache").callCount(2).build()
    );
  }

  @Test public void all_b3_sampled() {
    serviceRoot = TracedNode.createServiceGraph(tracingFunction(configureAllSampling));

    Map<String, String> headers = new LinkedHashMap<>();
    headers.put("b3", "1");
    headers.put("sampling", "gatewayplay,authcache");

    serviceRoot.execute("/recommend", headers);
    serviceRoot.execute("/play", headers);

    assertThat(zipkin.getDependencies()).isNotEmpty();
    assertThat(gatewayplay.getDependencies()).containsExactly( // doesn't double-count!
      DependencyLink.newBuilder().parent("gateway").child("playback").callCount(1).build()
    );
    assertThat(authcache.getDependencies()).containsExactly( // doesn't double-count!
      DependencyLink.newBuilder().parent("auth").child("cache").callCount(2).build()
    );
  }

  Function<String, Tracing> tracingFunction(Function<String, TracingCustomizer> customizer) {
    return (localServiceName) -> {
      Tracing.Builder result = Tracing.newBuilder()
        .localServiceName(localServiceName)
        .propagationFactory(B3SinglePropagation.FACTORY)
        .spanReporter(s -> zipkin.accept(asList(s)).enqueue(NOOP_CALLBACK));
      customizer.apply(localServiceName).customize(result);
      return result.build();
    };
  }

  @After public void close() {
    Tracing.current().close();
  }
}
