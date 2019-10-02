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
package brave.unmerged;

import brave.sampler.Matcher;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

import static brave.sampler.Matchers.alwaysMatch;
import static brave.sampler.Matchers.neverMatch;
import static org.assertj.core.api.Assertions.assertThat;

public class MoreMatchersTest {
  @Test public void alwaysMatch_matched() {
    assertThat(alwaysMatch().matches(null)).isTrue();
  }

  @Test public void neverMatch_unmatched() {
    assertThat(neverMatch().matches(null)).isFalse();
  }

  @Test public void cast() {
    Matcher<Object> matchesObject = o -> true;
    Matcher<String> matchesString = MoreMatchers.cast(matchesObject);
    assertThat(matchesString).isSameAs(matchesObject);
  }

  @Test public void ifInstanceOf_matched() {
    Matcher<String> matchesString = v -> true;
    Matcher<Object> matchesObject = MoreMatchers.ifInstanceOf(String.class, matchesString);

    assertThat(matchesObject.matches("hello")).isTrue();
  }

  @Test public void ifInstanceOf_unmatched() {
    Matcher<String> matchesString = v -> false;
    Matcher<Object> matchesObject = MoreMatchers.ifInstanceOf(String.class, matchesString);

    assertThat(matchesObject.matches("hello")).isFalse();
  }

  @Test public void ifInstanceOf_unmatched_wrongType() {
    Matcher<String> matchesString = v -> true;
    Matcher<Object> matchesObject = MoreMatchers.ifInstanceOf(String.class, matchesString);

    assertThat(matchesObject.matches(1)).isFalse();
  }

  @Test public void ifInstanceOf_stillUsableAsKey() {
    Matcher<String> matchesString = v -> true;
    Matcher<Object> matchesObject = MoreMatchers.ifInstanceOf(String.class, matchesString);

    assertThat(matchesObject)
      .isNotSameAs(matchesString)
      .isEqualTo(matchesString)
      .hasSameHashCodeAs(matchesString)
      .hasToString(matchesString.toString());

    // Can be used as a key because instances are consistent
    Map<Matcher<Object>, String> map = new LinkedHashMap<>();
    map.put(MoreMatchers.ifInstanceOf(String.class, matchesString), "hello");
    map.put(MoreMatchers.ifInstanceOf(String.class, matchesString), "goodbye");
    assertThat(map)
      .hasSize(1)
      .containsValue("goodbye");
  }
}
