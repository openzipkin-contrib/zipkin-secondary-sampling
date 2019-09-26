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
 * Secondary sampling is different than typical {@link brave.sampler.Sampler trace-scoped sampling},
 * as it has is re-evaluated at each node as opposed to assumed from constant values, like {@code
 * X-B3-Sampled: 1}.
 *
 * <p>Please read the <a href="https://github.com/openzipkin-contrib/zipkin-secondary-sampling/tree/master/docs/design.md">design
 * doc</a> for more concepts.
 *
 * <p><h3>Implementation notes</h3>
 * This is invoked during {@link TraceContext.Extractor#extract(Object)}, for each sampling key
 * parsed. Unlike {@link HttpSampler}, it is possible that the request is not HTTP, hence the type
 * is {@code Object}. For example, it could be a different RPC request, or a messaging type.
 */
public abstract class SecondarySampler { // class to permit late adding methods for Java 1.6
  /**
   * Returns a true if this request is sampled for a secondary sampling key {@code samplingKey}.
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
  public abstract boolean isSampled(Object request, SecondarySamplingState.Builder builder);
}
