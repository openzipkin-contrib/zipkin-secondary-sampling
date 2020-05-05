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

import brave.ScopedSpan;
import brave.Span;
import brave.Tracing;
import brave.handler.FinishedSpanHandler;
import brave.handler.MutableSpan;
import brave.http.HttpClientHandler;
import brave.http.HttpServerHandler;
import brave.http.HttpTracing;
import brave.propagation.B3Propagation;
import brave.propagation.TraceContext;
import brave.secondary_sampling.SecondarySampling.Extra;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

import static org.assertj.core.api.Assertions.assertThat;

// Do not bring any dependencies into this test without looking at src/it/pom.xml as this is used
// to verify we don't depend on internals.
public class BasicUsageTest {

  @Test public void basicUsage() {
    SecondarySampling secondarySampling = SecondarySampling.newBuilder()
        .propagationFactory(B3Propagation.newFactoryBuilder()
            .injectFormat(Span.Kind.CLIENT, B3Propagation.Format.SINGLE_NO_PARENT)
            .injectFormat(Span.Kind.SERVER, B3Propagation.Format.SINGLE_NO_PARENT)
            .build())
        .secondarySampler((request, state) -> true)
        .build();

    List<MutableSpan> spans = new ArrayList<>();
    Tracing.Builder tracingBuilder = Tracing.newBuilder()
        .addFinishedSpanHandler(new FinishedSpanHandler() {
          @Override public boolean handle(TraceContext context, MutableSpan span) {
            spans.add(span);
            return true;
          }
        });

    secondarySampling.customize(tracingBuilder);

    try (Tracing tracing = tracingBuilder.build()) {
      HttpTracing.Builder httpTracingBuilder = HttpTracing.newBuilder(tracing);
      secondarySampling.customize(httpTracingBuilder);

      ScopedSpan parent = tracing.tracer().startScopedSpan("parent");

      // hack until we have a local provisioning api
      setSampled(parent.context(), "test");
      assertThat(isSampled(parent.context(), "test")).isTrue(); // sanity check

      try (HttpTracing httpTracing = httpTracingBuilder.build()) {
        HttpClientHandler clientHandler = HttpClientHandler.create(httpTracing);
        HttpServerHandler serverHandler = HttpServerHandler.create(httpTracing);

        FakeHttpRequest.Client request = new FakeHttpRequest.Client("/");
        request.header("sampling", "test");

        Span client = clientHandler.handleSend(request);
        assertThat(isSampled(client.context(), "test"))
            .withFailMessage(SecondarySampling.class + " didn't decorate the client context")
            .isTrue(); // sanity check

        assertThat(request.headers)
            .withFailMessage(SecondarySamplingInjector.class + " didn't add the sampling header")
            .containsEntry("sampling", "test;spanId=" + client.context().spanIdString());

        Span server = serverHandler.handleReceive(new FakeHttpRequest.Server(request));
        assertThat(isSampled(server.context(), "test"))
            .withFailMessage(SecondarySamplingExtractor.class + " didn't parse the sampling header")
            .isTrue();

        server.finish();
        client.finish();
      } finally {
        parent.finish();
      }
    }

    for (MutableSpan span : spans) {
      assertThat(span.tag("sampled_keys"))
          .withFailMessage(SecondarySamplingSpanHandler.class + " didn't add the sampled_keys tag")
          .isEqualTo("b3,test");
    }
  }

  // hack until we have a local provisioning api
  static void setSampled(TraceContext context, String samplingKey) {
    context.findExtra(Extra.class).put(SecondarySamplingState.create(samplingKey), true);
  }

  // hack until https://github.com/openzipkin-contrib/zipkin-secondary-sampling/issues/12
  static boolean isSampled(TraceContext context, String samplingKey) {
    return Boolean.TRUE.equals(
        context.findExtra(Extra.class).get(SecondarySamplingState.create(samplingKey)));
  }
}
