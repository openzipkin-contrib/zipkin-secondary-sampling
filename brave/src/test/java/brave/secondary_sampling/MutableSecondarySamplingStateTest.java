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

import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.entry;

public class MutableSecondarySamplingStateTest {
  @Test public void parse() {
    MutableSecondarySamplingState state = MutableSecondarySamplingState.parse("authcache");
    assertThat(state.samplingKey).isEqualTo("authcache");
    assertThat(state.parameters).isEmpty();
  }

  @Test public void parse_parameters() {
    MutableSecondarySamplingState state =
        MutableSecondarySamplingState.parse("authcache;ttl=1;spanId=19f84f102048e047");
    assertThat(state.samplingKey).isEqualTo("authcache");
    assertThat(state.parameters).containsExactly(
        entry("ttl", "1"),
        entry("spanId", "19f84f102048e047")
    );
  }

  @Test public void parse_ttl() {
    assertThat(MutableSecondarySamplingState.parse("authcache;ttl=-1").ttl()).isZero();
    assertThat(MutableSecondarySamplingState.parse("authcache;ttl=0").ttl()).isZero();
    assertThat(MutableSecondarySamplingState.parse("authcache;ttl=2").ttl()).isEqualTo(2);
  }
}
