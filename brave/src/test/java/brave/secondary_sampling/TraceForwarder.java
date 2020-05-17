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
import brave.handler.SpanHandler;
import brave.internal.Nullable;
import brave.propagation.TraceContext;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * This is a simulation of <a href="https://github.com/openzipkin-contrib/zipkin-secondary-sampling/tree/master/docs/design.md#the-trace-forwarder">Trace
 * Forwarder</a>.
 *
 * <p>Specifically, this processes the {@link SecondarySamplingSpanHandler#tagName
 * sampled_keys tag} created by the {@link SecondarySamplingSpanHandler} like so.
 * <pre>
 *   <ol>
 *     <li>Drops the {@link SecondarySamplingSpanHandler#tagName sampled_keys tag}</li>
 *     <li>Corrects hierarchy upon a {@code parentId} sampling key parameter as needed</li>
 *     <li>Forwards data to the sampling key participant</li>
 *   </ol>
 * </pre>
 *
 * <h3>The {@code parentId} parameter of a {@code sampled_keys} entry</h3>
 * When the a {@code parentId} sampled key parameter exists, the span's parent ID is rewritten as if
 * that were its direct upstream. This allows trace view and dependency linking to work. The actual
 * parent ID is saved off as a tag {@code linkedParentId}, allowing the user, UI or other processors
 * to know the hierarchy was rewritten at that point.
 */
public final class TraceForwarder extends SpanHandler {
  Map<String, SpanHandler> samplingKeyToSpanHandler = new LinkedHashMap<>();

  public TraceForwarder configureSamplingKey(String samplingKey, SpanHandler consumer) {
    samplingKeyToSpanHandler.put(samplingKey, consumer);
    return this;
  }

  @Override public boolean end(TraceContext context, MutableSpan span, Cause cause) {
    String sampledKeys = span.removeTag("sampled_keys");
    if (sampledKeys == null) return false; // drop data not tagged properly

    // Find any parent ID matching
    MutableSpan copy = null;
    for (String entry : sampledKeys.split(",", 100)) {
      String[] nameMetadata = entry.split(";", 100);
      String sampledKey = nameMetadata[0];
      String parentId = findParentId(nameMetadata);

      if (!samplingKeyToSpanHandler.containsKey(sampledKey)) continue; // skip when unconfigured

      // Relink the trace to last upstream, saving the real parent ID off as a tag.
      MutableSpan next = span;
      if (parentId != null) {
        if (copy == null) {
          copy = new MutableSpan(span);
          copy.tag("linkedParentId", span.parentId());
          copy.unsetShared();
        }
        copy.parentId(parentId);
      }

      samplingKeyToSpanHandler.get(sampledKey).end(context, next, cause);
    }
    return true;
  }

  @Nullable static String findParentId(String[] nameMetadata) {
    for (int i = 1; i < nameMetadata.length; i++) {
      String[] nameValue = nameMetadata[i].split("=", 2);
      if (nameValue[0].equals("parentId")) {
        return nameValue[1];
      }
    }
    return null;
  }
}
