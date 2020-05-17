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
import brave.internal.codec.EntrySplitter;
import brave.internal.codec.EntrySplitter.Handler;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mutable form of {@link SecondarySamplingState} for use in parsing and collaboration, prior to
 * marking immutable for local propagation.
 *
 * <p><pre>{@code
 * // programmatic construction
 * authcacheState = MutableSecondarySamplingState.create("authcache").ttl(1);
 *
 * // parsing of the serialized form
 * authcacheState = MutableSecondarySamplingState.parse("authcache;ttl=1");
 * }</pre>
 */
// naming convention is like MutableSpan. Unlike a builder, this allows readback.
public final class MutableSecondarySamplingState {
  static final EntrySplitter PARAMETER_SPLITTER = EntrySplitter.newBuilder()
      .entrySeparator(';')
      .keyValueSeparator('=')
      .build();
  static final Handler<MutableSecondarySamplingState> HANDLER =
      (target, input, beginKey, endKey, beginValue, endValue) -> {
        String key = input.substring(beginKey, endKey);
        String value = input.substring(beginValue, endValue);
        target.parameter(key, value);
        return true;
      };

  public static MutableSecondarySamplingState create(String samplingKey) {
    if (samplingKey == null) throw new NullPointerException("samplingKey == null");
    if (samplingKey.isEmpty()) throw new IllegalArgumentException("samplingKey is empty");
    return new MutableSecondarySamplingState(samplingKey);
  }

  /**
   * Parses one entry from a comma-separated @link SecondarySampling.Builder#fieldName(String)
   * sampling field}.
   */
  // Like Accept header, parameters are after semi-colon and themselves semi-colon delimited.
  public static MutableSecondarySamplingState parse(String entry) {
    int indexOfParameters = entry.indexOf(';');
    if (indexOfParameters == -1) return MutableSecondarySamplingState.create(entry);

    MutableSecondarySamplingState result =
        MutableSecondarySamplingState.create(entry.substring(0, indexOfParameters));
    PARAMETER_SPLITTER.parse(HANDLER, result, entry, indexOfParameters, entry.length());
    return result;
  }

  final String samplingKey;
  Map<String, String> parameters = new LinkedHashMap<>();

  MutableSecondarySamplingState(String samplingKey) {
    this.samplingKey = samplingKey;
  }

  public String samplingKey() {
    return samplingKey;
  }

  /** Retrieves the current TTL of this {@link #samplingKey()} or zero if there is none. */
  public int ttl() {
    // TODO: add a limit to TTL, like 255 and make this and below super more efficient
    String ttl = parameter("ttl");
    if (ttl == null || "0".equals(ttl) || ttl.startsWith("-")) return 0;
    return Integer.parseInt(ttl); // TODO: when we make a limit, eliminate the parse exception
  }

  @Nullable public MutableSecondarySamplingState ttl(int ttl) {
    if (ttl <= 0) return removeParameter("ttl");
    return parameter("ttl", String.valueOf(ttl));
  }

  @Nullable public String parameter(String name) {
    if (name == null) throw new NullPointerException("name == null");
    return parameters.get(name);
  }

  @Nullable public MutableSecondarySamplingState removeParameter(String name) {
    if (name == null) throw new NullPointerException("name == null");
    parameters.remove(name);
    return this;
  }

  public MutableSecondarySamplingState parameter(String name, String value) {
    if (name == null) throw new NullPointerException("name == null");
    if (value == null) throw new NullPointerException("value == null");
    parameters.put(name, value);
    return this;
  }
}
