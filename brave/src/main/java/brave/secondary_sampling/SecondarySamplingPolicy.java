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

public interface SecondarySamplingPolicy {
  interface Trigger {
    boolean isSampled();

    // zero means don't add ttl
    int ttl();
  }

  /**
   * Returns a trigger if this tracer should participate with the given {@code samplingKey}.
   *
   * @param samplingKey sampling key extracted from a remote request.
   * @return a trigger if participating with this sampling key, or null to pass through data
   * associated with it.
   */
  @Nullable Trigger getTrigger(String samplingKey);
}
