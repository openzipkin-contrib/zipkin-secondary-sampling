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

import brave.propagation.B3SinglePropagation;
import brave.propagation.Propagation;
import brave.propagation.TraceContext;
import brave.propagation.TraceContext.Extractor;
import brave.propagation.TraceContext.Injector;
import brave.propagation.TraceContextOrSamplingFlags;
import brave.secondary_sampling.SecondarySampling.Extra;
import brave.secondary_sampling.TestSecondarySampler.Trigger;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

import static brave.propagation.Propagation.KeyFactory.STRING;
import static java.util.Collections.singletonList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

/**
 * This is a <a href="https://github.com/openzipkin-contrib/zipkin-secondary-sampling/tree/master/docs/design.md">Secondary
 * Sampling</a> proof of concept.
 */
public class SecondarySamplingStateTest {
  String serviceName = "auth", notServiceName = "gateway", notSpanId = "19f84f102048e047";
  TestSecondarySampler sampler = new TestSecondarySampler();
  SecondarySampling secondarySampling = SecondarySampling.newBuilder()
    .propagationFactory(B3SinglePropagation.FACTORY)
    .sampler(sampler.forService(serviceName))
    .build();

  Propagation<String> propagation = secondarySampling.create(STRING);

  Extractor<Map<String, String>> extractor = propagation.extractor(Map::get);
  Injector<Map<String, String>> injector = propagation.injector(Map::put);

  Map<String, String> headers = new LinkedHashMap<>();

  @Test public void extract_samplesLocalWhenConfigured() {
    // base case: links is configured, authcache is not. authcache is in the headers, though!
    sampler.addTrigger("links", new Trigger());

    headers.put("b3", "0");
    headers.put("sampling", "authcache"); // sampling hint should not trigger

    assertThat(extractor.extract(headers).sampledLocal()).isFalse();

    headers.put("b3", "0");
    headers.put("sampling", "links,authcache;ttl=1"); // links should trigger

    assertThat(extractor.extract(headers).sampledLocal()).isTrue();
  }

  /** This shows that TTL is applied regardless of sampler */
  @Test public void extract_ttlOverridesSampler() {
    headers.put("b3", "0");
    headers.put("sampling", "links,authcache;ttl=1");

    TraceContextOrSamplingFlags extracted = extractor.extract(headers);

    Map<SecondarySamplingState, Boolean> keyToState = ((Extra) extracted.extra().get(0)).toMap();
    assertThat(keyToState)
      // no TTL left for the next hop
      .containsEntry(SecondarySamplingState.newBuilder("authcache").build(), true)
      // not sampled because there's no trigger for links
      .containsEntry(SecondarySamplingState.newBuilder("links").build(), false);
  }

  /** This shows an example of dynamic configuration */
  @Test public void dynamicConfiguration() {
    // base case: links is configured, authcache is not. authcache is in the headers, though!
    sampler.addTrigger("links", new Trigger());

    headers.put("b3", "0");
    headers.put("sampling", "links,authcache");

    assertThat(extractor.extract(headers).sampledLocal()).isTrue();

    // dynamic configuration removes link processing
    sampler.removeTriggers("links");
    assertThat(extractor.extract(headers).sampledLocal()).isFalse();

    // dynamic configuration adds authcache processing
    sampler.addTrigger("authcache", serviceName, new Trigger());
    assertThat(extractor.extract(headers).sampledLocal()).isTrue();
  }

  @Test public void extract_convertsConfiguredRpsToDecision() {
    sampler.addTrigger("gatewayplay", notServiceName, new Trigger().rps(50));
    sampler.addTrigger("links", new Trigger());
    sampler.addTrigger("authcache", new Trigger().rps(100).ttl(1));

    headers.put("b3", "0");
    headers.put("sampling", "gatewayplay,links,authcache");

    TraceContextOrSamplingFlags extracted = extractor.extract(headers);

    Map<SecondarySamplingState, Boolean> keyToState = ((Extra) extracted.extra().get(0)).toMap();
    assertThat(keyToState).containsOnly(
      entry(SecondarySamplingState.newBuilder("links").build(), true),
      // authcache triggers a ttl
      entry(SecondarySamplingState.newBuilder("authcache")
        .parameter("ttl", "1").build(), true),
      // not sampled as we aren't in that service
      entry(SecondarySamplingState.newBuilder("gatewayplay").build(), false)
    );
  }

  @Test public void extract_decrementsTtlEvenWhenNotConfigured() {
    headers.put("b3", "0");
    headers.put("sampling", "gatewayplay,authcache;ttl=2");

    TraceContextOrSamplingFlags extracted = extractor.extract(headers);
    Extra extra = (Extra) extracted.extra().get(0);

    Map<SecondarySamplingState, Boolean> keyToState = ((Extra) extracted.extra().get(0)).toMap();
    assertThat(keyToState)
      // not sampled due to config, rather from TTL: note it is decremented
      .containsEntry(SecondarySamplingState.newBuilder("authcache")
        .parameter("ttl", "1").build(), true)
      // not sampled as we aren't in that service
      .containsEntry(SecondarySamplingState.newBuilder("gatewayplay").build(), false);
  }

  @Test public void injectWritesNewLastParentWhenSampled() {
    Extra extra = new Extra();
    extra.put(SecondarySamplingState.newBuilder("gatewayplay")
      .parameter("spanId", notSpanId).build(), false);
    extra.put(SecondarySamplingState.newBuilder("links").build(), true);
    extra.put(SecondarySamplingState.newBuilder("authcache")
      .parameter("ttl", "1")
      .parameter("spanId", notSpanId).build(), false);

    TraceContext context = TraceContext.newBuilder()
      .traceId(1L).spanId(2L).sampled(false).extra(singletonList(extra)).build();
    injector.inject(context, headers);

    // doesn't interfere with keys not sampled.
    assertThat(headers).containsEntry("sampling",
      "gatewayplay;spanId=" + notSpanId + ","
        + "links;spanId=" + context.spanIdString() + ","
        + "authcache;ttl=1;spanId=" + notSpanId);
  }
}
