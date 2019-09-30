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

import brave.http.HttpClientRequest;
import brave.http.HttpServerRequest;
import java.util.LinkedHashMap;
import java.util.Map;

final class FakeRequest {
  static final class Client extends HttpClientRequest {
    Map<String, String> headers = new LinkedHashMap<>();

    @Override public Object unwrap() {
      return this;
    }

    @Override public String method() {
      return null;
    }

    @Override public String path() {
      return "/";
    }

    @Override public String url() {
      return null;
    }

    @Override public void header(String name, String value) {
      headers.put(name, value);
    }

    @Override public String header(String name) {
      return headers.get(name);
    }
  }

  static final class Server extends HttpServerRequest {
    Map<String, String> headers = new LinkedHashMap<>();

    @Override public Object unwrap() {
      return this;
    }

    @Override public String method() {
      return null;
    }

    @Override public String path() {
      return "/";
    }

    @Override public String url() {
      return null;
    }

    public void header(String name, String value) {
      headers.put(name, value);
    }

    @Override public String header(String name) {
      return headers.get(name);
    }
  }

  private FakeRequest() {
  }
}
