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

import brave.http.HttpServerRequest;
import brave.propagation.TraceContext;

public interface SecondarySampler {
  /**
   * Returns a trigger function that decides if this request is sampled for the given {@code
   * samplingKey}.
   *
   * <p>This is invoked during {@link TraceContext.Extractor#extract(Object)}. It is usually the
   * case that the carrier parameter is a request. For example, in http middleware, it will be
   * {@link HttpServerRequest}. However, in other RPC middleware it may be a different type.
   *
   * <p>For example, to only handle http requests, implement like so:
   * <pre>{@code
   *     if (!(carrier instanceof HttpServerRequest)) return false;
   *     HttpServerRequest request = (HttpServerRequest) carrier;
   *     return isSampled(request, builder.samplingKey());
   * }</pre>
   *
   * <p><h3>Mutating the builder</h3>
   * A side-effect of a sampling decision could be adding parameters or refreshing a {@link
   * SecondarySamplingState.Builder#ttl(int)}.
   *
   * @param request the incoming request type, such as {@link HttpServerRequest}
   * @param builder state extracted for this sampling key.
   * @return true if the {@link SecondarySamplingState.Builder#samplingKey()} is sampled for this
   * request
   */
  boolean isSampled(Object request, SecondarySamplingState.Builder builder);
}
