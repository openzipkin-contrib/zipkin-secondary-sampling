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

import static brave.secondary_sampling.SecondarySampling.EXTRA_FACTORY;

/**
 * This extracts the {@link SecondarySampling#fieldName sampling header}, and parses it a list of
 * {@link SecondarySamplingState}. For each extracted sampling key, TTL and sampling takes place if
 * configured.
 */
final class SecondarySamplingExtractor<C, K> implements Extractor<C> {
  final Extractor<C> delegate;
  final Getter<C, K> getter;
  final SecondarySampler sampler;
  final K samplingKey;

  SecondarySamplingExtractor(SecondarySampling.Propagation<K> propagation, Getter<C, K> getter) {
    this.delegate = propagation.delegate.extractor(getter);
    this.getter = getter;
    this.sampler = propagation.secondarySampling.sampler;
    this.samplingKey = propagation.samplingKey;
  }

  @Override public TraceContextOrSamplingFlags extract(C carrier) {
    TraceContextOrSamplingFlags result = delegate.extract(carrier);
    String maybeValue = getter.get(carrier, samplingKey);
    if (maybeValue == null) return result;

    SecondarySampling.Extra extra = EXTRA_FACTORY.create();
    boolean sampledLocal = false;
    for (String entry : maybeValue.split(",", 100)) {
      MutableSecondarySamplingState state = MutableSecondarySamplingState.parse(entry);
      boolean sampled = updateStateAndSample(state);
      if (sampled) sampledLocal = true;
      extra.put(SecondarySamplingState.create(state), sampled);
    }

    if (extra.isEmpty()) return result;

    TraceContextOrSamplingFlags.Builder builder = result.toBuilder();
    if (sampledLocal) builder.sampledLocal(); // Data will be recorded even if B3 unsampled
    builder.addExtra(extra);
    return builder.build();
  }

  boolean updateStateAndSample(MutableSecondarySamplingState state) {
    boolean ttlSampled = false;

    // decrement ttl from upstream, if there is one
    int ttl = state.ttl();
    if (ttl != 0) {
      state.ttl(ttl - 1);
      ttlSampled = true;
    }

    return ttlSampled || sampler.isSampled(state);
  }
}
