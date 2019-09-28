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

import brave.sampler.RateLimitingSampler;
import brave.sampler.Sampler;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TestSecondarySampler {
  public static final class Trigger {
    public enum Mode {
      /** Triggers regardless of if upstream sampled. */
      ACTIVE,
      /** Triggers always when upstream sampled. Similar to b3 sampling*/
      PASSIVE
    }

    Mode mode = Mode.ACTIVE;
    Sampler sampler = Sampler.ALWAYS_SAMPLE;
    int ttl = 0; // zero means don't add ttl

    public Trigger rps(int rps) {
      this.sampler = RateLimitingSampler.create(rps);
      return this;
    }

    public Trigger ttl(int ttl) {
      this.ttl = ttl;
      return this;
    }

    public Trigger mode(Mode mode) {
      this.mode = mode;
      return this;
    }

    public boolean isSampled() {
      return sampler.isSampled(0L);
    }

    public int ttl() {
      return ttl;
    }
  }

  /** Sampling rules are pushed by config management to all nodes, looked up by sampling key */
  final Map<String, Trigger> allServices = new LinkedHashMap<>();
  final Map<String, Map<String, Trigger>> byService = new LinkedHashMap<>();

  TestSecondarySampler addTrigger(String samplingKey, Trigger trigger) {
    allServices.put(samplingKey, trigger);
    return this;
  }

  public SecondarySampler forService(String serviceName) {
    return (state) -> {
      Trigger trigger = getByService(state.samplingKey()).get(serviceName);
      if (trigger == null) trigger = allServices.get(state.samplingKey());
      if (trigger == null) return false;

      // When in passive mode, we always trigger when upstream did
      // Similar to b3 sampling
      if (trigger.mode == Trigger.Mode.PASSIVE) {
        return state.parameter("spanId") != null;
      }

      boolean sampled = trigger.isSampled();
      if (sampled) state.ttl(trigger.ttl()); // Set any TTL
      return sampled;
    };
  }

  public TestSecondarySampler addTrigger(String samplingKey, String serviceName,
    Trigger trigger) {
    getByService(samplingKey).put(serviceName, trigger);
    return this;
  }

  TestSecondarySampler removeTriggers(String samplingKey) {
    allServices.remove(samplingKey);
    byService.remove(samplingKey);
    return this;
  }

  public TestSecondarySampler merge(TestSecondarySampler input) {
    allServices.putAll(input.allServices);
    byService.putAll(input.byService);
    return this;
  }

  Map<String, Trigger> getByService(String samplingKey) {
    return byService.computeIfAbsent(samplingKey, k -> new LinkedHashMap<>());
  }
}
