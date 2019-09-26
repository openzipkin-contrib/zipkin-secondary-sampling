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

import brave.http.HttpSampler;
import brave.http.HttpServerRequest;
import brave.propagation.TraceContext;

/**
 * Decides whether the {@link TraceContext#isLocalRoot() local root} of this request will be sampled
 * for the given sampling key.
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
 *
 * <p><h3>Implementation notes</h3>
 * This is invoked during {@link TraceContext.Extractor#extract(Object)}, for each sampling key
 * parsed. Unlike {@link HttpSampler}, it is possible that the request is not HTTP, hence the type
 * is {@code Object}. For example, it could be a different RPC request, or a messaging type.
 */
// Unlike brave.http.HttpSampler, there are generic types, so no problems implementing with lambdas where Java 1.8+ is available
public interface SecondarySampler {
  /**
   * Returning true will sample data for the {@link TraceContext#isLocalRoot() local root} of this
   * request, under the the given {@link SecondarySamplingState.Builder#samplingKey()}. Returning
   * false ignores the sampling key.
   *
   * <p>For example, to only handle http requests, implement like so:
   * <pre>{@code
   *     if (!(carrier instanceof HttpServerRequest)) return false;
   *     HttpServerRequest request = (HttpServerRequest) carrier;
   *     return isSampled(request, builder.samplingKey());
   * }</pre>
   *
   * <p><h3>The builder argument</h3>
   * Simple use cases will only read {@link SecondarySamplingState.Builder#samplingKey()}. This
   * argument is present to allow reading other parameters or refreshing a {@link
   * SecondarySamplingState.Builder#ttl(int)}.
   *
   * @param request the incoming request type, possibly {@link HttpServerRequest}
   * @param builder state extracted for this sampling key.
   * @return true if the {@link SecondarySamplingState.Builder#samplingKey()} is sampled for this
   * request
   */
  boolean isSampled(Object request, SecondarySamplingState.Builder builder);
}
