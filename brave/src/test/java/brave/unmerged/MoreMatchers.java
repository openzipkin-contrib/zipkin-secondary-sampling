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

public final class MoreMatchers {
  /**
   * This allows you to safely pass a type-specific matcher, guarded in the same fashion as if you
   * called {@code parameters instance of P}. This is the opposite of {@link #cast(Matcher)}.
   *
   * <p>Ex to ensure an HTTP matcher isn't invoked with the wrong type:
   * <pre>{@code
   * Matcher<Object> acceptsObject =
   *   ifInstanceOf(HttpRequest.class, HttpRequestMatchers.pathStartsWith(path));
   * }</pre>
   *
   * @see #cast(Matcher)
   * @since 5.8
   */
  public static <P> Matcher<Object> ifInstanceOf(Class<P> paramType, Matcher<P> matcher) {
    return new IfInstanceOf(paramType, matcher);
  }

  /**
   * This casts a matcher so that it matches a narrowed type. This is the opposite of {@link
   * #ifInstanceOf(Class, Matcher)}.
   *
   * <p>For example, given a matcher of type Object, it is always safe to pass a subtype:
   * <pre>{@code
   * Matcher<Object> acceptsObject = ...
   * Matcher<HttpRequest> acceptsHttpRequest = cast(acceptsObject);
   * }</pre>
   *
   * @see #ifInstanceOf(Class, Matcher)
   * @since 5.8
   */
  @SuppressWarnings("unchecked") // safe contravariant cast
  public static <P> Matcher<P> cast(Matcher<Object> matcher) {
    return (Matcher<P>) matcher;
  }

  static final class IfInstanceOf implements Matcher<Object> {
    final Class<?> paramType;
    final Matcher delegate;

    IfInstanceOf(Class<?> paramType, Matcher delegate) {
      this.paramType = paramType;
      this.delegate = delegate;
    }

    @Override public boolean matches(Object parameters) {
      if (paramType.isAssignableFrom(parameters.getClass())) {
        return delegate.matches(parameters);
      }
      return false;
    }

    // Override equals and hashCode to ensure matchers can be used as keys
    @Override public boolean equals(Object o) {
      if (this == o) return true;
      if (o instanceof IfInstanceOf) {
        return delegate.equals(((IfInstanceOf) o).delegate);
      } else if (o instanceof Matcher) {
        return delegate.equals(o);
      }
      return false;
    }

    @Override public int hashCode() {
      return delegate.hashCode();
    }

    @Override public String toString() {
      return delegate.toString();
    }
  }
}
