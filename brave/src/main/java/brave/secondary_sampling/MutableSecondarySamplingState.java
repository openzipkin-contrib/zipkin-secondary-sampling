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
  public static final MutableSecondarySamplingState create(String samplingKey) {
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
    String[] nameParameters = entry.split(";", 100);
    MutableSecondarySamplingState result = create(nameParameters[0]);
    for (int i = 1; i < nameParameters.length; i++) {
      String[] nameValue = nameParameters[i].split("=", 2);
      result.parameter(nameValue[0], nameValue[1]);
    }
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
    String ttl = parameter("ttl");
    return ttl == null ? 0 : Integer.parseInt(ttl);
  }

  @Nullable public MutableSecondarySamplingState ttl(int ttl) {
    if (ttl == 0) return removeParameter("ttl");
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
