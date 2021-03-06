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

import brave.internal.codec.EntrySplitter;
import brave.internal.codec.EntrySplitter.Handler;
import brave.propagation.Propagation.Getter;
import brave.propagation.TraceContext.Extractor;
import brave.propagation.TraceContextOrSamplingFlags;

/**
 * This extracts the {@link SecondarySampling#fieldName sampling header}, and parses it a list of
 * {@link SecondarySamplingState}. For each extracted sampling key, TTL and sampling takes place if
 * configured.
 */
final class SecondarySamplingExtractor<R> implements Extractor<R> {
  static final EntrySplitter SPLITTER = EntrySplitter.newBuilder()
      .keyValueSeparator(';')
      .keyValueSeparatorRequired(false)
      .build();

  final Extractor<R> delegate;
  final Getter<R, String> getter;
  final SecondaryProvisioner provisioner;
  final SecondarySampler secondarySampler;
  final String fieldName;

  SecondarySamplingExtractor(SecondarySampling secondarySampling, Getter<R, String> getter) {
    this.delegate = secondarySampling.delegate.extractor(getter);
    this.getter = getter;
    this.provisioner = secondarySampling.provisioner;
    this.secondarySampler = secondarySampling.secondarySampler;
    this.fieldName = secondarySampling.fieldName;
  }

  @Override public TraceContextOrSamplingFlags extract(R request) {
    TraceContextOrSamplingFlags.Builder builder = delegate.extract(request).toBuilder();
    SecondarySamplingDecisions initial = SecondarySamplingDecisions.FACTORY.create();
    builder.addExtra(initial);

    provisioner.provision(request, initial);

    String maybeValue = getter.get(request, fieldName);
    if (maybeValue != null) {
      Handler<SecondarySamplingDecisions> handlerForRequest = handlerForRequest(request);
      SPLITTER.parse(handlerForRequest, initial, maybeValue);
    }

    if (initial.sampledLocal()) builder.sampledLocal();
    return builder.build();
  }

  boolean updateStateAndSample(Object request, MutableSecondarySamplingState state) {
    boolean ttlSampled = false;

    // decrement ttl from upstream, if there is one
    int ttl = state.ttl();
    if (ttl != 0) {
      state.ttl(ttl - 1);
      ttlSampled = true;
    }

    return ttlSampled || secondarySampler.isSampled(request, state);
  }

  /** Parses the input into {@link SecondarySamplingDecisions} */
  Handler<SecondarySamplingDecisions> handlerForRequest(Object request) {
    return (target, input, beginKey, endKey, beginValue, endValue) -> {
      String key = input.subSequence(beginKey, endKey).toString();
      MutableSecondarySamplingState state = MutableSecondarySamplingState.create(key);
      if (beginValue != endValue) {
        MutableSecondarySamplingState.PARAMETER_SPLITTER.parse(
            MutableSecondarySamplingState.HANDLER, state, input, beginValue, endValue);
      }
      boolean sampled = updateStateAndSample(request, state);
      target.addSamplingState(SecondarySamplingState.create(state), sampled);
      return true;
    };
  }
}
