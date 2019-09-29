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
import brave.propagation.TraceContext;

/**
 * This is invoked during {@link TraceContext.Extractor#extract(Object)}, for each sampling key
 * parsed from propagation fields. This decides if the {@link TraceContext#isLocalRoot() local root}
 * of this request will be sampled for the given sampling key.
 *
 * <p><h3>Details</h3>
 * To review, a {@link TraceContext#isLocalRoot() local root} is the partition of a trace that
 * starts and ends in one tracer. An HTTP example of a local root is a single incoming request and
 * the tree of spans up to egress requests. By definition, a local root does not include the next
 * hop.
 *
 * <p>{@link brave.sampler.Sampler trace-scoped sampling}, a decision is made for the entire
 * root of the trace, so that decision is constant for each {@link TraceContext#isLocalRoot() local
 * root}, including all spans of the request: they are either all sampled or all not sampled.
 *
 * <p>Secondary sampling is mainly different as it is evaluated at each node, each {@link
 * TraceContext#isLocalRoot() local root}. It is also different as multiple participants, identified
 * by sampling keys, may want the same data.
 *
 * <p>Unlike {@code X-B3-Sampled}, a "sampling key" does not tell you anything about upstream. It
 * does not mean anything participated yet. This function is not trace-scoped in other words, it is
 * only about the local root of this request. The decision made here is if this request is sampled
 * against the "sampling key". To participate in the key would be to return true from this function,
 * to abstain would be to return false.
 *
 * <p>Please read the <a href="https://github.com/openzipkin-contrib/zipkin-secondary-sampling/tree/master/docs/design.md">design
 * doc</a> for more concepts.
 */
public interface SecondarySampler {
  /**
   * Returning true will sample data for the {@link TraceContext#isLocalRoot() local root} of this
   * trace, under the the given {@link MutableSecondarySamplingState#samplingKey()}. Returning false
   * ignores the sampling key.
   *
   * <p>Here's an example of evaluating participation based on a configured service name.
   * <pre>{@code
   * return (request, state) -> isSampled(state.samplingKey(), localServiceName());
   * }</pre>
   *
   * <p>Here's an example that uses http request properties along with the key.
   * <pre>{@code
   * // Assume an initialized HTTP sampler exists
   * samplers.put("play", HttpRuleSampler.newBuilder()
   *   .addRuleWithRate("GET", "/play", 100) // requests per second
   *   .build());
   *
   * // The secondary sampler can leverage this
   * return (request, state) -> {
   *   if (!(request instanceof HttpServerRequest)) return false; // only sample server side
   *   HttpRequestSampler sampler = samplers.get(state.samplingKey());
   *   if (sampler == null) return false; // key isn't configured
   *   return Boolean.TRUE.equals(sampler.trySample((HttpServerRequest) request));
   * };
   * }</pre>
   *
   * <p><h3>The request parameter</h3></p>
   * The request parameter may be an {@link HttpRequest}, which would allow implementation with
   * tools like {@link HttpRuleSampler}. However, the request could also be non-HTTP. For example,
   * it could be a gRPC or messaging request.
   *
   * <p><h3>The state parameter</h3>
   * Simple use cases will only read {@link MutableSecondarySamplingState#samplingKey()}. This
   * argument is present to allow reading other parameters or refreshing a {@link
   * MutableSecondarySamplingState#ttl(int)}.
   *
   * @param request incoming request
   * @param state a sampling key associated with the request, and any parameters attached to it.
   * @return true if the {@link MutableSecondarySamplingState#samplingKey()} is sampled.
   */
  boolean isSampled(Object request, MutableSecondarySamplingState state);
}
