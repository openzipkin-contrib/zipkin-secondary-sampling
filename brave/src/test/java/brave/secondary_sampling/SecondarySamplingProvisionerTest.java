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
package brave.secondary_sampling;

import brave.http.HttpServerRequest;
import brave.propagation.B3SinglePropagation;
import brave.propagation.TraceContext.Extractor;
import brave.propagation.TraceContextOrSamplingFlags;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

public class SecondarySamplingProvisionerTest {
  FakeHttpRequest.Server request = new FakeHttpRequest.Server(
    new FakeHttpRequest.Client("/validateLicense")
  );

  @Test public void extract_samplesLocalWhenProvisioned() {
    Extractor<HttpServerRequest> extractor = extractor((request, callback) ->
      callback.addSamplingState(
        SecondarySamplingState.create(
          MutableSecondarySamplingState.create("license100pct")),
        true
      ));

    TraceContextOrSamplingFlags extracted = extractor.extract(request);
    assertThat(extracted.sampledLocal()).isTrue();

    SecondarySamplingDecisions extra = (SecondarySamplingDecisions) extracted.extra().get(0);
    assertThat(extra.get(SecondarySamplingState.create("license100pct"))).isTrue();
  }

  /** This shows if the provisioner mistakenly adds the same key twice, the first wins. */
  @Test public void extract_firstAddWins() {
    Extractor<HttpServerRequest> extractor = extractor((request, callback) -> {
      callback.addSamplingState(
        SecondarySamplingState.create(
          MutableSecondarySamplingState.create("license100pct")),
        false
      );
      // This will be ignored, instead of somehow merged
      callback.addSamplingState(
        SecondarySamplingState.create(
          MutableSecondarySamplingState.create("license100pct").ttl(3)),
        true
      );
    });

    TraceContextOrSamplingFlags extracted = extractor.extract(request);
    assertThat(extracted.sampledLocal()).isFalse();

    SecondarySamplingDecisions extra = (SecondarySamplingDecisions) extracted.extra().get(0);
    assertThat(extra.asReadOnlyMap().keySet())
      .usingFieldByFieldElementComparator() // SecondarySamplingState.equals ignores params
      .containsExactly(SecondarySamplingState.create("license100pct"));
  }

  /** This shows the provisioner wins on the same key vs incoming state. */
  @Test public void extract_overridesIncomingSamplingKey() {
    Extractor<HttpServerRequest> extractor = extractor((request, callback) ->
      callback.addSamplingState(
        SecondarySamplingState.create(
          MutableSecondarySamplingState.create("license100pct")),
        true
      ));

    // Incoming state has the same key, but it has a TTL. We expect this to be overwritten.
    request.header("sampling", "license100pct;ttl=5");
    TraceContextOrSamplingFlags extracted = extractor.extract(request);
    assertThat(extracted.sampledLocal()).isTrue();

    SecondarySamplingDecisions extra = (SecondarySamplingDecisions) extracted.extra().get(0);
    assertThat(extra.asReadOnlyMap().keySet())
      .usingFieldByFieldElementComparator() // SecondarySamplingState.equals ignores params
      .containsExactly(SecondarySamplingState.create("license100pct"));
  }

  @Test public void extract_provisionedIsntSampled() {
    Extractor<HttpServerRequest> extractor = extractor((request, callback) ->
      callback.addSamplingState(
        SecondarySamplingState.create(
          MutableSecondarySamplingState.create("license100pct")),
        false // set provision, but sampled false. This is for downstream sampling.
      ));

    TraceContextOrSamplingFlags extracted = extractor.extract(request);
    assertThat(extracted.sampledLocal()).isFalse();

    SecondarySamplingDecisions extra = (SecondarySamplingDecisions) extracted.extra().get(0);
    assertThat(extra.asReadOnlyMap().get(SecondarySamplingState.create("license100pct"))).isFalse();
  }

  static Extractor<HttpServerRequest> extractor(SecondaryProvisioner provisioner) {
    SecondarySampling secondarySampling = SecondarySampling.newBuilder()
      .propagationFactory(B3SinglePropagation.FACTORY)
      .provisioner(provisioner)
      .secondarySampler(SecondarySamplers.passive())
      .build();

    return secondarySampling.extractor(HttpServerRequest::header);
  }
}
