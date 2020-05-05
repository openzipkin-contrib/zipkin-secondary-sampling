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

import brave.handler.MutableSpan;
import brave.propagation.TraceContext;
import java.util.StringJoiner;

/**
 * This writes the {@link #tagName sampled_keys tag} needed by the Zipkin endpoint to route data
 * correctly, and fix any hierarchy problems created by missing spans:
 *
 * <h3>The {@code parentId} parameter of a {@code sampled_keys} entry</h3>
 * If the {@code spanId} parameter of a sampling key doesn't match the current parent of a local
 * root, it is copied as the {@code parentId} parameter of the corresponding {@link #tagName
 * sampled_keys entry}. This allows the trace forwarder to fix the hierarchy for this participant.
 */
final class SecondarySamplingSpanHandler extends brave.handler.FinishedSpanHandler {
  final String tagName;

  SecondarySamplingSpanHandler(String tagName) {
    this.tagName = tagName;
  }

  @Override public boolean handle(TraceContext context, MutableSpan span) {
    StringJoiner joiner = new StringJoiner(",");
    if (Boolean.TRUE.equals(context.sampled())) joiner.add("b3");

    SecondarySamplingDecisions extra = context.findExtra(SecondarySamplingDecisions.class);
    if (extra != null) {
      String parentId;
      if (context.isLocalRoot()) {
        parentId = context.shared() ? context.spanIdString() : context.parentIdString();
      } else {
        parentId = null;
      }

      extra.map().forEach((state, sampled) -> {
        if (!sampled) return;
        String upstreamSpanId = state.parameter("spanId");
        if (parentId != null && !parentId.equals(upstreamSpanId)) {
          joiner.add(state.samplingKey() + ";parentId=" + upstreamSpanId);
        } else {
          joiner.add(state.samplingKey());
        }
      });
    }

    if (joiner.length() != 0) span.tag(tagName, joiner.toString());
    return true;
  }
}
