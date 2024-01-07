/*
 * Copyright 2019-2024 The OpenZipkin Authors
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
package brave.secondary_sampling.integration;

import brave.Span;
import brave.Tracing;
import brave.handler.SpanHandler;
import brave.http.HttpClientHandler;
import brave.http.HttpClientRequest;
import brave.http.HttpClientResponse;
import brave.http.HttpServerHandler;
import brave.http.HttpServerRequest;
import brave.http.HttpServerResponse;
import brave.http.HttpTracing;
import brave.internal.Nullable;
import brave.propagation.B3SinglePropagation;
import brave.propagation.CurrentTraceContext;
import brave.propagation.CurrentTraceContext.Scope;
import brave.propagation.TraceContextOrSamplingFlags;
import brave.rpc.RpcTracing;
import brave.secondary_sampling.FakeHttpRequest;
import brave.secondary_sampling.FakeHttpResponse;
import brave.secondary_sampling.FakeRpcRequest;
import brave.secondary_sampling.SecondarySampling;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Function;

class TracedNode {
  // gateway -> api -> auth -> cache  -> authdb
  //                -> recommendations -> cache -> recodb
  //                -> playback -> license -> cache -> licensedb
  //                            -> moviemetadata
  //                            -> streams
  static TracedNode createServiceGraph(
    Function<String, SecondarySampling> secondarySamplingFunction,
    SpanHandler spanHandler) {
    TracedNode.Factory nodeFactory = new TracedNode.Factory(secondarySamplingFunction, spanHandler);
    TracedNode gateway = nodeFactory.create("gateway");
    TracedNode api = nodeFactory.create("api", (path, serviceName) -> {
      if (serviceName.equals("playback")) return path.equals("/play");
      if (serviceName.equals("recommendations")) return path.equals("/recommend");
      return true;
    });
    gateway.addDownStream(api);
    TracedNode auth = nodeFactory.create("auth").asRpc("GetToken");
    api.addDownStream(auth);
    auth.addDownStream(nodeFactory.create("cache").addDownStream(nodeFactory.create("authdb")));
    api.addDownStream(nodeFactory.create("recommendations")
      .addDownStream(nodeFactory.create("cache")
        .addDownStream(nodeFactory.create("recodb"))));
    TracedNode playback = nodeFactory.create("playback");
    api.addDownStream(playback);
    playback.addDownStream(nodeFactory.create("license")
      .addDownStream(nodeFactory.create("cache")
        .addDownStream(nodeFactory.create("licensedb"))));
    playback.addDownStream(nodeFactory.create("moviemetadata"));
    playback.addDownStream(nodeFactory.create("streams"));
    return gateway;
  }

  static class Factory {
    final Function<String, SecondarySampling> secondarySamplingFunction;
    final SpanHandler spanHandler;

    Factory(Function<String, SecondarySampling> secondarySamplingFunction,
      SpanHandler spanHandler) {
      this.secondarySamplingFunction = secondarySamplingFunction;
      this.spanHandler = spanHandler;
    }

    TracedNode create(String serviceName) {
      return create(serviceName, (e, r) -> true);
    }

    TracedNode create(String serviceName, BiPredicate<String, String> routeFunction) {
      SecondarySampling secondarySampling = secondarySamplingFunction.apply(serviceName);
      Tracing tracing = tracing(serviceName, secondarySampling);
      HttpTracing httpTracing = httpTracing(tracing, secondarySampling);
      RpcTracing rpcTracing = rpcTracing(tracing, secondarySampling);
      return new TracedNode(serviceName, httpTracing, rpcTracing, routeFunction);
    }

    Tracing tracing(String serviceName, @Nullable SecondarySampling secondarySampling) {
      Tracing.Builder tracingBuilder = Tracing.newBuilder()
        .localServiceName(serviceName)
        .propagationFactory(B3SinglePropagation.FACTORY)
        .addSpanHandler(spanHandler);
      if (secondarySampling != null) secondarySampling.customize(tracingBuilder);
      return tracingBuilder.build();
    }

    HttpTracing httpTracing(Tracing tracing, @Nullable SecondarySampling secondarySampling) {
      HttpTracing.Builder httpTracingBuilder = HttpTracing.newBuilder(tracing);
      if (secondarySampling != null) secondarySampling.customize(httpTracingBuilder);
      return httpTracingBuilder.build();
    }

    RpcTracing rpcTracing(Tracing tracing, @Nullable SecondarySampling secondarySampling) {
      RpcTracing.Builder rpcTracingBuilder = RpcTracing.newBuilder(tracing);
      if (secondarySampling != null) secondarySampling.customize(rpcTracingBuilder);
      return rpcTracingBuilder.build();
    }
  }

  final String localServiceName;
  final BiPredicate<String, String> routeFunction;
  final List<TracedNode> downstream = new ArrayList<>();
  final CurrentTraceContext current;
  final HttpClientHandler<HttpClientRequest, HttpClientResponse> clientHandler;
  final HttpServerHandler<HttpServerRequest, HttpServerResponse> serverHandler;
  final RpcTracing rpcTracing;
  String rpcMethod;

  TracedNode asRpc(String method) {
    rpcMethod = method;
    return this;
  }

  TracedNode(String localServiceName, HttpTracing httpTracing,
    RpcTracing rpcTracing, BiPredicate<String, String> routeFunction) {
    this.localServiceName = localServiceName;
    this.routeFunction = routeFunction;
    this.current = httpTracing.tracing().currentTraceContext();
    this.serverHandler = HttpServerHandler.create(httpTracing);
    this.clientHandler = HttpClientHandler.create(httpTracing);
    this.rpcTracing = rpcTracing;
  }

  /** Returns the first node in the service graph with the given name, or null if none match. */
  @Nullable TracedNode findDownStream(String serviceName) {
    if (serviceName == null) throw new NullPointerException("serviceName == null");
    if (localServiceName.equals(serviceName)) return this;
    for (TracedNode down : downstream) {
      TracedNode result = down.findDownStream(serviceName);
      if (result != null) return result;
    }
    return null;
  }

  TracedNode addDownStream(TracedNode downstream) {
    this.downstream.add(downstream);
    return this;
  }

  void execute(FakeHttpRequest.Client clientRequest) {
    FakeHttpRequest.Server serverRequest = new FakeHttpRequest.Server(clientRequest);
    Span span = serverHandler.handleReceive(serverRequest);
    callDownstream(serverRequest.path(), span);
    serverHandler.handleSend(new FakeHttpResponse.Server(), span);
  }

  void execute(FakeRpcRequest.Client clientRequest) {
    FakeRpcRequest.Server serverRequest = new FakeRpcRequest.Server(clientRequest);
    Span span = handleRpcServerReceive(serverRequest);
    callDownstream(clientRequest.method(), span);
    span.finish();
  }

  void callDownstream(String endpoint, Span span) {
    try (Scope ws = current.newScope(span.context())) {
      for (TracedNode down : downstream) {
        if (routeFunction.test(endpoint, down.localServiceName)) {
          if (down.rpcMethod != null) {
            callDownstreamRpc(down.rpcMethod, down);
          } else {
            callDownstreamHttp(endpoint, down);
          }
        }
      }
    }
  }

  void callDownstreamHttp(String path, TracedNode down) {
    FakeHttpRequest.Client clientRequest = new FakeHttpRequest.Client(path);
    Span span = clientHandler.handleSend(clientRequest);
    down.execute(clientRequest);
    clientHandler.handleReceive(new FakeHttpResponse.Client(), span);
  }

  void callDownstreamRpc(String method, TracedNode down) {
    FakeRpcRequest.Client clientRequest = new FakeRpcRequest.Client(method);
    Span span = handleRpcClientSend(clientRequest);
    down.execute(clientRequest);
    span.finish();
  }

  // remove after https://github.com/openzipkin/brave/pull/999
  Span handleRpcClientSend(FakeRpcRequest.Client clientRequest) {
    Span span = rpcTracing.tracing().tracer().nextSpan(rpcTracing.clientSampler(), clientRequest);
    rpcTracing.tracing()
      .propagation()
      .<FakeRpcRequest.Client>injector(FakeRpcRequest.Client::header)
      .inject(span.context(), clientRequest);
    span.kind(Span.Kind.CLIENT).name(clientRequest.method());
    return span.start();
  }

  // remove after https://github.com/openzipkin/brave/pull/999
  Span handleRpcServerReceive(FakeRpcRequest.Server serverRequest) {
    TraceContextOrSamplingFlags extracted = rpcTracing.tracing().propagation()
      .<FakeRpcRequest.Server>extractor(FakeRpcRequest.Server::header)
      .extract(serverRequest);

    Boolean sampled = extracted.sampled();
    // only recreate the context if the rpc sampler made a decision
    if (sampled == null
      && (sampled = rpcTracing.serverSampler().trySample(serverRequest)) != null) {
      extracted = extracted.sampled(sampled.booleanValue());
    }

    Span span;
    if (extracted.context() != null) {
      span = rpcTracing.tracing().tracer().joinSpan(extracted.context());
    } else {
      span = rpcTracing.tracing().tracer().nextSpan(extracted);
    }

    span.kind(Span.Kind.SERVER).name(serverRequest.method());
    return span.start();
  }

  @Override
  public String toString() {
    return localServiceName;
  }
}
