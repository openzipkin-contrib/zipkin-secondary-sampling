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

import brave.propagation.Propagation.Getter;
import brave.propagation.TraceContext.Extractor;
import brave.propagation.TraceContextOrSamplingFlags;
import java.util.LinkedHashMap;

import static brave.secondary_sampling.SecondarySampling.EXTRA_FACTORY;

/**
 * This extracts the {@link SecondarySampling#fieldName sampling header}, and parses it a list of
 * {@link SecondarySamplingState}. For each extracted sampling key, TTL and sampling takes place if
 * configured.
 */
final class SecondarySamplingExtractor<C, K> implements Extractor<C> {
  final Extractor<C> delegate;
  final Getter<C, K> getter;
  final SecondaryProvisioner provisioner;
  final SecondarySampler sampler;
  final K samplingKey;

  SecondarySamplingExtractor(SecondarySampling.Propagation<K> propagation, Getter<C, K> getter) {
    this.delegate = propagation.delegate.extractor(getter);
    this.getter = getter;
    this.provisioner = propagation.secondarySampling.provisioner;
    this.sampler = propagation.secondarySampling.secondarySampler;
    this.samplingKey = propagation.samplingKey;
  }

  @Override public TraceContextOrSamplingFlags extract(C request) {
    TraceContextOrSamplingFlags result = delegate.extract(request);
    String maybeValue = getter.get(request, samplingKey);
    if (maybeValue == null) return result;

    SampledLocalMap initial = new SampledLocalMap();
    provisioner.provision(request, initial);
    for (String entry : maybeValue.split(",", 100)) {
      MutableSecondarySamplingState state = MutableSecondarySamplingState.parse(entry);
      boolean sampled = updateStateAndSample(request, state);
      initial.addSamplingState(SecondarySamplingState.create(state), sampled);
    }

    SecondarySampling.Extra extra = EXTRA_FACTORY.create(initial);
    TraceContextOrSamplingFlags.Builder builder = result.toBuilder();
    if (initial.sampledLocal) builder.sampledLocal(); // Data will be recorded even if B3 unsampled
    builder.addExtra(extra);
    return builder.build();
  }

  final class SampledLocalMap extends LinkedHashMap<SecondarySamplingState, Boolean>
    implements SecondaryProvisioner.Callback {
    boolean sampledLocal = false;

    @Override public void addSamplingState(SecondarySamplingState state, boolean sampled) {
      if (containsKey(state)) {
        // redundant: log and continue
        return;
      }
      if (sampled) sampledLocal = true;
      put(state, sampled);
    }
  }

  boolean updateStateAndSample(Object request, MutableSecondarySamplingState state) {
    boolean ttlSampled = false;

    // decrement ttl from upstream, if there is one
    int ttl = state.ttl();
    if (ttl != 0) {
      state.ttl(ttl - 1);
      ttlSampled = true;
    }

    return ttlSampled || sampler.isSampled(request, state);
  }
}
