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
package brave.secondary_sampling;

import brave.http.HttpClientRequest;
import brave.http.HttpServerRequest;
import brave.propagation.B3SinglePropagation;
import brave.propagation.Propagation;
import brave.propagation.TraceContext;
import brave.propagation.TraceContext.Extractor;
import brave.propagation.TraceContext.Injector;
import brave.propagation.TraceContextOrSamplingFlags;
import brave.sampler.RateLimitingSampler;
import brave.secondary_sampling.SecondarySampling.Extra;
import org.junit.Test;

import static brave.propagation.Propagation.KeyFactory.STRING;
import static brave.secondary_sampling.SecondarySamplers.active;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * This is a <a href="https://github.com/openzipkin-contrib/zipkin-secondary-sampling/tree/master/docs/design.md">Secondary
 * Sampling</a> proof of concept.
 */
public class SecondarySamplingTest {
  String serviceName = "auth", unknown = "unknown", notSpanId = "19f84f102048e047";
  SamplerController sampler = new SamplerController.Default();
  SecondarySampling secondarySampling = SecondarySampling.newBuilder()
    .propagationFactory(B3SinglePropagation.FACTORY)
    .httpServerSampler(sampler.primaryHttpSampler(serviceName))
    .rpcServerSampler(sampler.primaryRpcSampler(serviceName))
    .secondarySampler(sampler.secondarySampler(serviceName))
    .build();

  Propagation<String> propagation = secondarySampling.create(STRING);

  Extractor<HttpServerRequest> httpExtractor = propagation.extractor(HttpServerRequest::header);
  Injector<HttpClientRequest> httpInjector = propagation.injector(HttpClientRequest::header);

  FakeHttpRequest.Client clientHttpRequest = new FakeHttpRequest.Client("/play");
  FakeHttpRequest.Server serverHttpRequest = new FakeHttpRequest.Server(clientHttpRequest);

  @Test public void extract_samplesLocalWhenConfigured() {
    // base case: links is configured, authcache is not. authcache is in the serverRequest, though!
    sampler.putSecondaryRule("links", active());

    serverHttpRequest.header("b3", "0");
    serverHttpRequest.header("sampling", "authcache"); // sampling hint should not trigger

    assertThat(httpExtractor.extract(serverHttpRequest).sampledLocal()).isFalse();

    serverHttpRequest.header("b3", "0");
    serverHttpRequest.header("sampling", "links,authcache;ttl=1"); // links should trigger

    assertThat(httpExtractor.extract(serverHttpRequest).sampledLocal()).isTrue();
  }

  /** This shows that TTL is applied regardless of sampler */
  @Test public void extract_ttlOverridesSampler() {
    serverHttpRequest.header("b3", "0");
    serverHttpRequest.header("sampling", "links,authcache;ttl=1");

    TraceContextOrSamplingFlags extracted = httpExtractor.extract(serverHttpRequest);
    Extra extra = (Extra) extracted.extra().get(0);

    assertThat(extra.toMap())
      // no TTL left for the next hop
      .containsEntry(SecondarySamplingState.create("authcache"), true)
      // not sampled because there's no trigger for links
      .containsEntry(SecondarySamplingState.create("links"), false);
  }

  /** This shows an example of dynamic configuration */
  @Test public void dynamicConfiguration() {
    // base case: links is configured, authcache is not. authcache is in the serverRequest, though!
    sampler.putSecondaryRule("links", active());

    serverHttpRequest.header("b3", "0");
    serverHttpRequest.header("sampling", "links,authcache");

    assertThat(httpExtractor.extract(serverHttpRequest).sampledLocal()).isTrue();

    // dynamic configuration removes link processing
    sampler.removeSecondaryRules("links");
    assertThat(httpExtractor.extract(serverHttpRequest).sampledLocal()).isFalse();

    // dynamic configuration adds authcache processing
    sampler.putSecondaryRule(serviceName, "authcache", active());
    assertThat(httpExtractor.extract(serverHttpRequest).sampledLocal()).isTrue();
  }

  @Test public void extract_convertsConfiguredRpsToDecision() {
    sampler.putSecondaryRule(unknown, "gatewayplay", active(RateLimitingSampler.create(50)));
    sampler.putSecondaryRule("links", active());
    sampler.putSecondaryRule("authcache", active(RateLimitingSampler.create(100), 1));

    serverHttpRequest.header("b3", "0");
    serverHttpRequest.header("sampling", "gatewayplay,links,authcache");

    TraceContextOrSamplingFlags extracted = httpExtractor.extract(serverHttpRequest);
    Extra extra = (Extra) extracted.extra().get(0);

    assertThat(extra.toMap()).containsOnly(
      entry(SecondarySamplingState.create("links"), true),
      // authcache triggers a ttl
      entry(SecondarySamplingState.create(MutableSecondarySamplingState.create("authcache")
        .parameter("ttl", "1")), true),
      // not sampled as we aren't in that service
      entry(SecondarySamplingState.create("gatewayplay"), false)
    );
  }

  @Test public void extract_decrementsTtlEvenWhenNotConfigured() {
    serverHttpRequest.header("b3", "0");
    serverHttpRequest.header("sampling", "gatewayplay,authcache;ttl=2");

    TraceContextOrSamplingFlags extracted = httpExtractor.extract(serverHttpRequest);
    Extra extra = (Extra) extracted.extra().get(0);

    assertThat(extra.toMap())
      // not sampled due to config, rather from TTL: note it is decremented
      .containsEntry(SecondarySamplingState.create(MutableSecondarySamplingState.create("authcache")
        .parameter("ttl", "1")), true)
      // not sampled as we aren't in that service
      .containsEntry(SecondarySamplingState.create("gatewayplay"), false);
  }

  @Test public void injectWritesNewLastParentWhenSampled() {
    Extra extra = new Extra();
    extra.put(SecondarySamplingState.create(MutableSecondarySamplingState.create("gatewayplay")
      .parameter("spanId", notSpanId)), false);
    extra.put(SecondarySamplingState.create("links"), true);
    extra.put(SecondarySamplingState.create(MutableSecondarySamplingState.create("authcache")
      .parameter("ttl", "1")
      .parameter("spanId", notSpanId)), false);

    TraceContext context = TraceContext.newBuilder()
      .traceId(1L).spanId(2L).sampled(false).extra(singletonList(extra)).build();
    httpInjector.inject(context, clientHttpRequest);

    // doesn't interfere with keys not sampled.
    assertThat(clientHttpRequest.header("sampling")).isEqualTo(
      "gatewayplay;spanId=" + notSpanId + ","
        + "links;spanId=" + context.spanIdString() + ","
        + "authcache;ttl=1;spanId=" + notSpanId);
  }
}
