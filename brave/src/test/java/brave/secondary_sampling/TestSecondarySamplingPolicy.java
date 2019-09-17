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
import brave.sampler.RateLimitingSampler;
import brave.sampler.Sampler;
import java.util.LinkedHashMap;
import java.util.Map;

public final class TestSecondarySamplingPolicy implements SecondarySamplingPolicy {
  public static final class TestTrigger implements SecondarySamplingPolicy.Trigger {
    Sampler sampler = Sampler.ALWAYS_SAMPLE;
    int ttl = 0; // zero means don't add ttl

    public TestTrigger rps(int rps) {
      this.sampler = RateLimitingSampler.create(rps);
      return this;
    }

    public TestTrigger ttl(int ttl) {
      this.ttl = ttl;
      return this;
    }

    @Override public boolean isSampled() {
      return sampler.isSampled(0L);
    }

    @Override public int ttl() {
      return ttl;
    }
  }

  /** Sampling rules are pushed by config management to all nodes, looked up by sampling key */
  final Map<String, Trigger> allServices = new LinkedHashMap<>();
  final Map<String, Map<String, Trigger>> byService = new LinkedHashMap<>();

  TestSecondarySamplingPolicy addTrigger(String samplingKey, Trigger trigger) {
    allServices.put(samplingKey, trigger);
    return this;
  }

  public TestSecondarySamplingPolicy addTrigger(String samplingKey, String serviceName,
    Trigger trigger) {
    getByService(samplingKey).put(serviceName, trigger);
    return this;
  }

  TestSecondarySamplingPolicy removeTriggers(String samplingKey) {
    allServices.remove(samplingKey);
    byService.remove(samplingKey);
    return this;
  }

  public TestSecondarySamplingPolicy merge(TestSecondarySamplingPolicy input) {
    allServices.putAll(input.allServices);
    byService.putAll(input.byService);
    return this;
  }

  @Override @Nullable public Trigger getTriggerForService(String samplingKey, String serviceName) {
    Trigger result = getByService(samplingKey).get(serviceName);
    return result != null ? result : allServices.get(samplingKey);
  }

  Map<String, Trigger> getByService(String samplingKey) {
    return byService.computeIfAbsent(samplingKey, k -> new LinkedHashMap<>());
  }
}
