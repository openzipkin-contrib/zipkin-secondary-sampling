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

import brave.http.HttpRequest;
import brave.http.HttpRuleSampler;
import brave.sampler.Matcher;
import brave.sampler.Sampler;
import brave.sampler.SamplerFunction;
import java.util.LinkedHashMap;
import java.util.Map;

import static brave.secondary_sampling.SecondarySamplers.active;
import static brave.unmerged.MoreMatchers.ifInstanceOf;

/**
 * This customizes a {@link SecondarySampling} instance with primary (B3) and secondary HTTP
 * sampling rules.
 */
public interface SamplerController {
  SamplerFunction<HttpRequest> primaryHttpSampler(String serviceName);

  SecondarySampler secondarySampler(String serviceName);

  SamplerController putPrimaryHttpRule(
    String serviceName, /* only arg different is lack of sampling key */
    Matcher<HttpRequest> matcher, Sampler sampler
  );

  // This shows you can do primary and secondary policy in the same type with the same matchers.
  default SamplerController putSecondaryHttpRule(
    String serviceName, String samplingKey,
    Matcher<HttpRequest> matcher, Sampler sampler
  ) {
    return putSecondaryRule(
      serviceName, samplingKey,
      // active means don't inherit a decision.
      // Secondary samplers can pass any request, so we guard narrower types with isInstanceOf
      active(ifInstanceOf(HttpRequest.class, matcher), sampler)
    );
  }

  SamplerController putSecondaryRule(String samplingKey, SecondarySampler sampler);

  SamplerController putSecondaryRule(String serviceName, String samplingKey,
    SecondarySampler sampler);

  SamplerController removeSecondaryRules(String samplingKey);

  // implementation is nested only to keep the imporant methods at the top of the file
  final class Default implements SamplerController {
    final Map<String, HttpRuleSampler> primarySamplers = new LinkedHashMap<>();

    /** Secondary sampling rules are pushed by config management to all nodes, looked up by key */
    final Map<String, SecondarySampler> secondaryRules = new LinkedHashMap<>();
    final Map<String, Map<String, SecondarySampler>> secondaryRulesByService =
      new LinkedHashMap<>();

    @Override public SamplerFunction<HttpRequest> primaryHttpSampler(String serviceName) {
      return new SamplerFunction<HttpRequest>() { // defer evaluation so dynamic changes are visible
        @Override public Boolean trySample(HttpRequest httpRequest) {
          HttpRuleSampler primarySampler = primarySamplers.get(serviceName);
          return primarySampler != null ? primarySampler.trySample(httpRequest) : null;
        }
      };
    }

    @Override public SecondarySampler secondarySampler(String serviceName) {
      return (request, state) -> { // defer evaluation so dynamic changes are visible
        SecondarySampler sampler = secondaryRulesByService(serviceName).get(state.samplingKey());
        if (sampler == null) sampler = secondaryRules.get(state.samplingKey());
        if (sampler == null) return false;
        return sampler.isSampled(request, state);
      };
    }

    @Override
    public SamplerController putPrimaryHttpRule(String serviceName, Matcher<HttpRequest> matcher,
      Sampler sampler) {
      HttpRuleSampler primarySampler =
        primarySamplers.getOrDefault(serviceName, HttpRuleSampler.newBuilder().build());
      primarySamplers.put(serviceName, HttpRuleSampler.newBuilder()
        .putAllRules(primarySampler)
        .putRule(matcher, sampler).build());
      return this;
    }

    @Override
    public SamplerController putSecondaryRule(String samplingKey, SecondarySampler sampler) {
      secondaryRules.put(samplingKey, sampler);
      return this;
    }

    @Override
    public SamplerController putSecondaryRule(String serviceName, String samplingKey,
      SecondarySampler sampler) {
      secondaryRulesByService(serviceName).put(samplingKey, sampler);
      return this;
    }

    @Override public SamplerController removeSecondaryRules(String samplingKey) {
      secondaryRules.remove(samplingKey);
      for (Map<String, SecondarySampler> samplingKeyToSampler : secondaryRulesByService.values()) {
        samplingKeyToSampler.remove(samplingKey);
      }
      return this;
    }

    Map<String, SecondarySampler> secondaryRulesByService(String serviceName) {
      return secondaryRulesByService.computeIfAbsent(serviceName, s -> new LinkedHashMap<>());
    }
  }
}
