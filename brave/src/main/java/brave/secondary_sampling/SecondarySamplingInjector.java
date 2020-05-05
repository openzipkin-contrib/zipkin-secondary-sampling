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

import brave.propagation.Propagation.Setter;
import brave.propagation.TraceContext;
import brave.propagation.TraceContext.Injector;
import java.util.StringJoiner;

/**
 * This writes the {@link SecondarySampling#fieldName sampling header}, with an updated {@code
 * spanId} parameters for each sampled key. The Zipkin endpoint can use that span ID to correct the
 * parent hierarchy.
 */
final class SecondarySamplingInjector<R> implements Injector<R> {
  final Injector<R> delegate;
  final Setter<R, String> setter;
  final String fieldName;

  SecondarySamplingInjector(SecondarySampling secondarySampling, Setter<R, String> setter) {
    this.delegate = secondarySampling.delegate.injector(setter);
    this.setter = setter;
    this.fieldName = secondarySampling.fieldName;
  }

  @Override public void inject(TraceContext traceContext, R request) {
    delegate.inject(traceContext, request);
    SecondarySamplingDecisions decisions = traceContext.findExtra(SecondarySamplingDecisions.class);
    if (decisions == null || decisions.isEmpty()) return;
    setter.put(request, fieldName, serializeWithSpanId(decisions, traceContext.spanIdString()));
  }

  static String serializeWithSpanId(SecondarySamplingDecisions decisions, String spanId) {
    StringJoiner joiner = new StringJoiner(",");
    decisions.map()
        .forEach((state, sampled) -> joiner.merge(serializeWithSpanId(state, sampled, spanId)));
    return joiner.toString();
  }

  static StringJoiner serializeWithSpanId(SecondarySamplingState state, boolean sampled,
      String spanId) {
    StringJoiner joiner = new StringJoiner(";");
    joiner.add(state.samplingKey());

    String upstreamSpanId = state.parameter("spanId");
    state.forEachParameter((key, value) -> {
      if (!"spanId".equals(key)) joiner.add(key + "=" + value);
    });

    if (sampled) {
      joiner.add("spanId=" + spanId);
    } else if (spanId != null) { // pass through the upstream span ID
      joiner.add("spanId=" + upstreamSpanId);
    }

    return joiner;
  }
}
