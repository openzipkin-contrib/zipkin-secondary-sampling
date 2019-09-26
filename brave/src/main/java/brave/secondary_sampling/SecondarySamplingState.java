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

import brave.internal.Nullable;
import java.util.Map;

/**
 * This type holds extracted state from a {@link SecondarySampling.Builder#fieldName(String)
 * sampling field} entry.
 *
 * <p><pre>{@code
 * // programmatic construction
 * mutable = MutableSecondarySamplingState.create("authcache").ttl(1);
 * authcacheState = SecondarySamplingState.create(mutable);
 * }</pre>
 * <p>This type is immutable and intended to be used as a key in a mapping of sampling keys to
 * local sampling decisions.
 */
public final class SecondarySamplingState {
  public static SecondarySamplingState create(String samplingKey) {
    return new SecondarySamplingState(MutableSecondarySamplingState.create(samplingKey));
  }

  public static SecondarySamplingState create(MutableSecondarySamplingState state) {
    return new SecondarySamplingState(state);
  }

  public interface ParameterConsumer<K, V> {
    // BiConsumer is Java 1.8+
    void accept(K key, V value);
  }

  private final String samplingKey;
  private final Map<String, String> parameters;
  // intentionally hidden so we can change the state model

  SecondarySamplingState(MutableSecondarySamplingState builder) {
    samplingKey = builder.samplingKey;
    parameters = builder.parameters;
  }

  public String samplingKey() {
    return samplingKey;
  }

  /** Retrieves the current TTL of this {@link #samplingKey()} or zero if there is none. */
  @Nullable public int ttl() {
    String ttl = parameter("ttl");
    return ttl == null ? 0 : Integer.parseInt(ttl);
  }

  @Nullable public String parameter(String name) {
    if (name == null) throw new NullPointerException("name == null");
    return parameters.get(name);
  }

  public void forEachParameter(ParameterConsumer<String, String> paramConsumer) {
    for (Map.Entry<String, String> entry : parameters.entrySet()) {
      String value = entry.getValue();
      if (value == null) continue;
      paramConsumer.accept(entry.getKey(), value);
    }
  }

  @Override public boolean equals(Object o) {
    if (o == this) return true;
    if (!(o instanceof SecondarySamplingState)) return false;
    SecondarySamplingState that = (SecondarySamplingState) o;
    // intentionally ignores parameters to allow put-based override
    return samplingKey.equals(that.samplingKey);
  }

  @Override public int hashCode() {
    int h = 1;
    h *= 1000003;
    h ^= samplingKey.hashCode();
    // intentionally ignores parameters to allow put-based override
    return h;
  }

  @Override public String toString() {
    return "SecondarySamplingState(samplingKey="
      + samplingKey
      + ", parameters="
      + parameters
      + ")";
  }
}
