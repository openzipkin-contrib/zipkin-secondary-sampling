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

import brave.sampler.Matcher;
import brave.sampler.Matchers;
import brave.sampler.Sampler;

public final class SecondarySamplers {
  /** Triggers always when upstream sampled. Similar to b3 sampling */
  public static SecondarySampler passive() {
    return (request, state) -> {
      // When in passive mode, we always trigger when upstream did, similar to b3 sampling
      return state.parameter("spanId") != null;
    };
  }

  /** Triggers regardless of if upstream sampled. */
  public static SecondarySampler active() {
    return active(Sampler.ALWAYS_SAMPLE);
  }

  /** Triggers regardless of if upstream sampled. */
  public static SecondarySampler active(Sampler sampler) {
    return active(Matchers.alwaysMatch(), sampler, 0);
  }

  /** Triggers regardless of if upstream sampled. */
  public static SecondarySampler active(Matcher<Object> matcher, Sampler sampler) {
    return active(matcher, sampler, 0);
  }

  public static SecondarySampler active(int ttl) {
    return active(Sampler.ALWAYS_SAMPLE, ttl);
  }

  public static SecondarySampler active(Sampler sampler, int ttl) {
    return active(Matchers.alwaysMatch(), sampler, ttl);
  }

  public static SecondarySampler active(Matcher<Object> matcher, Sampler sampler, int ttl) {
    return new Active(matcher, sampler, ttl);
  }

  static final class Active implements SecondarySampler {
    final Matcher<Object> matcher;
    final Sampler sampler;
    final int ttl;

    Active(Matcher<Object> matcher, Sampler sampler, int ttl) {
      if (matcher == null) throw new NullPointerException("matcher == null");
      if (sampler == null) throw new NullPointerException("sampler == null");
      this.matcher = matcher;
      this.sampler = sampler;
      this.ttl = ttl;
    }

    @Override public boolean isSampled(Object request, MutableSecondarySamplingState state) {
      if (matcher.matches(request)) {
        if (Boolean.TRUE.equals(sampler.isSampled(0L))) {
          state.ttl(ttl); // Set any TTL
        }
        return true;
      }
      return false;
    }
  }
}
