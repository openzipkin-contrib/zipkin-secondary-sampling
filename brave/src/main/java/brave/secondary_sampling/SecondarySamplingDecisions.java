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

import brave.internal.extra.MapExtra;
import brave.internal.extra.MapExtraFactory;
import java.util.Map;

final class SecondarySamplingDecisions extends
    MapExtra<SecondarySamplingState, Boolean, SecondarySamplingDecisions, SecondarySamplingDecisions.Factory>
    implements SecondaryProvisioner.Callback {
  static final Factory FACTORY = new FactoryBuilder().maxDynamicEntries(32).build();

  static final class FactoryBuilder extends
      MapExtraFactory.Builder<SecondarySamplingState, Boolean, SecondarySamplingDecisions, Factory, FactoryBuilder> {
    @Override protected Factory build() {
      return new Factory(this);
    }
  }

  static final class Factory extends
      MapExtraFactory<SecondarySamplingState, Boolean, SecondarySamplingDecisions, Factory> {
    Factory(FactoryBuilder builder) {
      super(builder);
    }

    @Override protected SecondarySamplingDecisions create() {
      return new SecondarySamplingDecisions(this);
    }
  }

  SecondarySamplingDecisions(Factory factory) {
    super(factory);
  }

  @Override public void addSamplingState(SecondarySamplingState state, boolean sampled) {
    if (get(state) == null) put(state, sampled);
  }

  @Override protected boolean isEmpty() {
    return super.isEmpty();
  }

  @Override protected Boolean get(SecondarySamplingState key) { // exposed for tests
    return super.get(key);
  }

  @Override protected Map<SecondarySamplingState, Boolean> asReadOnlyMap() {
    return super.asReadOnlyMap();
  }

  boolean sampledLocal() {
    return asReadOnlyMap().containsValue(true);
  }
}
