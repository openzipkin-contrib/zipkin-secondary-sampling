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

import brave.internal.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;
import zipkin2.Callback;
import zipkin2.Span;
import zipkin2.reporter.Reporter;
import zipkin2.storage.SpanConsumer;

import static java.util.Collections.singletonList;

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
public final class TraceForwarder implements Reporter<Span> {
  public static final Callback<Void> NOOP_CALLBACK = new Callback<Void>() {
    @Override public void onSuccess(Void aVoid) {
    }

    @Override public void onError(Throwable throwable) {
    }
  };
  Map<String, SpanConsumer> samplingKeyToSpanConsumer = new LinkedHashMap<>();

  public TraceForwarder configureSamplingKey(String samplingKey, SpanConsumer consumer) {
    samplingKeyToSpanConsumer.put(samplingKey, consumer);
    return this;
  }

  @Override public void report(Span span) {
    LinkedHashMap<String, String> tags = new LinkedHashMap<>(span.tags());
    String sampledKeys = tags.remove("sampled_keys");
    if (sampledKeys == null) return; // drop data not tagged properly

    // Find any parent ID matching
    for (String entry : sampledKeys.split(",", 100)) {
      String[] nameMetadata = entry.split(";", 100);
      String sampledKey = nameMetadata[0];
      String parentId = findParentId(nameMetadata);

      if (!samplingKeyToSpanConsumer.containsKey(sampledKey)) continue; // skip when unconfigured

      // retain tags unrelated to secondary sampling
      Span.Builder builder = span.toBuilder().clearTags();
      tags.forEach(builder::putTag);

      // Relink the trace to last upstream, saving the real parent ID off as a tag.
      if (parentId != null) {
        builder.parentId(parentId);
        builder.putTag("linkedParentId", span.parentId());
        builder.shared(false);
      }

      Span scoped = builder.build();
      samplingKeyToSpanConsumer.get(sampledKey)
        .accept(singletonList(scoped))
        .enqueue(NOOP_CALLBACK);
    }
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
