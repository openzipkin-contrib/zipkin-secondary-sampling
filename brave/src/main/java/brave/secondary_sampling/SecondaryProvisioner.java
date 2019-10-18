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

import brave.propagation.TraceContext;

/**
 * An implementation of this will apply policy to provision new sampling keys based on an incoming
 * request. All sampling keys provisioned will not be invoked with {@link SecondarySampler}.
 *
 * <p>Each invocation of the {@link SecondaryProvisioner.Callback} will provision a new key. When
 * that key is sampled, is means the node provisioning is also participating. When it is not
 * sampled, the node is just passing a key downstream for another to sample.
 */
public interface SecondaryProvisioner {
  interface Callback {
    /**
     * This adds a new {@link SecondarySamplingState#samplingKey() sampling key}.
     *
     * <p>If {@code sampled}, the resulting trace context will be {@link
     * TraceContext#sampledLocal()} for this node. Otherwise, the state is propagated downstream for
     * a later node to sample.
     *
     * <p>Only the first call for the same {@link SecondarySamplingState#samplingKey() sampling
     * key} is honored. Any redundant calls will be logged and ignored.
     */
    void addSamplingState(SecondarySamplingState state, boolean sampled);
  }

  /**
   * This parses a request and provisions any relevant sampling keys using the passed callback.
   *
   * <p>Here's an example where the current node provisions a key named "play" and also
   * participates in it. This results in data collected here and also downstream nodes that {@link
   * SecondarySampler sample} the key.
   * <pre>{@code
   * if (request instanceof HttpServerRequest) {
   *   HttpServerRequest serverRequest = (HttpServerRequest) request;
   *    if (serverRequest.path().startsWith("/play")) {
   *      callback.addSamplingState(SecondarySamplingState.create("play"), true);
   *    }
   * }
   * }</pre>
   */
  void provision(Object request, Callback callback);
}
