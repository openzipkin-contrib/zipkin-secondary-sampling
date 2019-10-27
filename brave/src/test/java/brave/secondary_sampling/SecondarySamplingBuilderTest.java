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

import brave.propagation.B3SinglePropagation;
import brave.propagation.Propagation;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

import static brave.propagation.Propagation.KeyFactory.STRING;
import static org.assertj.core.api.Assertions.assertThat;

// TODO: we eventually need to refactor SecondarySamplingTest and SecondarySamplingStateTest to not
// include redundant tests that show integration scenarios. The tests here are more unit test in
// nature.
public class SecondarySamplingBuilderTest {

  /**
   * The {@link Propagation#keys()} result must include all trace state carrying fields. Because the
   * {@link SecondarySampling.Builder#fieldName(String) secondary field} can be provisioned prior to
   * a primary decision, we must include this to avoid extraction logic accidentally ignoring it.
   */
  @Test public void keys_includesSamplingField() {
    SecondarySampling secondarySampling = SecondarySampling.newBuilder()
      .secondarySampler((request, state) -> false)
      .propagationFactory(B3SinglePropagation.FACTORY)
      .build();

    SecondarySampling.Propagation<String> propagation = secondarySampling.create(STRING);
    assertThat(propagation.keys())
      .containsExactly("b3", "sampling");

    // This is an example to reinforce the use case, eventhough the unit test covers this.

    // Pretend you have a message coming from a proxy which has assigned the sampling field, but it
    // has not actually started any trace yet. If we only looked for "b3", a scan like below would
    // miss.
    Map<String, String> messageHeaders = new LinkedHashMap<>();
    messageHeaders.put("sampling", "links");

    assertThat(propagation.keys())
      .containsAnyElementsOf(messageHeaders.keySet());
  }
}
