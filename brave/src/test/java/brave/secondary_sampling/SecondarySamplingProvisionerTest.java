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

import brave.http.HttpServerRequest;
import brave.propagation.B3SinglePropagation;
import brave.propagation.Propagation;
import brave.propagation.TraceContext.Extractor;
import brave.propagation.TraceContextOrSamplingFlags;
import brave.secondary_sampling.SecondarySampling.Extra;
import org.junit.Test;

import static brave.propagation.Propagation.KeyFactory.STRING;
import static org.assertj.core.api.Assertions.assertThat;

public class SecondarySamplingProvisionerTest {
  String serviceName = "license";
  SamplerController sampler = new SamplerController.Default();
  SecondarySampling secondarySampling = SecondarySampling.newBuilder()
    .propagationFactory(B3SinglePropagation.FACTORY)
    .secondarySampler(sampler.secondarySampler(serviceName))
    .provisioner(sampler.secondaryProvisioner(serviceName))
    .build();

  Propagation<String> propagation = secondarySampling.create(STRING);

  Extractor<HttpServerRequest> httpExtractor = propagation.extractor(HttpServerRequest::header);

  FakeHttpRequest.Client clientHttpRequest = new FakeHttpRequest.Client("/validateLicense");
  FakeHttpRequest.Server serverHttpRequest = new FakeHttpRequest.Server(clientHttpRequest);

  @Test public void extract_samplesLocalWhenProvisioned() {
    sampler.putProvisioner(serviceName, (request, callback) -> callback.addSamplingState(
      SecondarySamplingState.create(
        MutableSecondarySamplingState.create("license100pct")),
      true
    ));

    TraceContextOrSamplingFlags extracted = httpExtractor.extract(serverHttpRequest);
    assertThat(extracted.sampledLocal()).isTrue();

    Extra extra = (Extra) extracted.extra().get(0);
    assertThat(extra.toMap())
      .containsEntry(SecondarySamplingState.create("license100pct"), true);
  }

}
