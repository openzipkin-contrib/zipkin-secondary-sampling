/*
 * Copyright 2019-2020 The OpenZipkin Authors
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

import brave.internal.baggage.Extra;
import brave.internal.baggage.ExtraHandler;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

final class SecondarySamplingDecisions
    extends Extra<SecondarySamplingDecisions, SecondarySamplingDecisions.Handler>
    implements SecondaryProvisioner.Callback {

  static final class Handler extends ExtraHandler<SecondarySamplingDecisions, Handler> {
    Handler() {
      super(Collections.emptyMap());
    }

    @Override protected SecondarySamplingDecisions provisionExtra() {
      return new SecondarySamplingDecisions(this);
    }
  }

  boolean sampledLocal = false;

  SecondarySamplingDecisions(Handler handler) {
    super(handler);
  }

  Map<SecondarySamplingState, Boolean> map() {
    return (Map<SecondarySamplingState, Boolean>) state;
  }

  boolean isEmpty() {
    return map().isEmpty();
  }

  @Override public void addSamplingState(SecondarySamplingState state, boolean sampled) {
    Map<SecondarySamplingState, Boolean> map = map();
    if (map.containsKey(state)) {
      // redundant: log and continue
      return;
    }
    if (sampled) sampledLocal = true;
    if (map.isEmpty()) this.state = map = new LinkedHashMap<>();
    map.put(state, sampled);
  }

  @Override
  protected void mergeStateKeepingOursOnConflict(SecondarySamplingDecisions theirDecisions) {
    Map<SecondarySamplingState, Boolean> ourDecisions = map();
    boolean dirty = false;
    for (Entry<SecondarySamplingState, Boolean> theirDecision : theirDecisions.map().entrySet()) {
      Boolean ourDecision = ourDecisions.get(theirDecision.getKey());
      if (ourDecision != null) continue; // prefer our decision
      if (!dirty) {
        ourDecisions = new LinkedHashMap<>(ourDecisions);
        dirty = true;
      }
      ourDecisions.put(theirDecision.getKey(), theirDecision.getValue());
    }
    if (dirty) state = ourDecisions;
  }

  @Override protected boolean stateEquals(Object o) {
    return map().equals(o);
  }

  @Override protected int stateHashCode() {
    return map().hashCode();
  }

  @Override protected String stateString() {
    return map().toString();
  }
}