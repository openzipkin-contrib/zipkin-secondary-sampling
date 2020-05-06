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

import brave.Tracing;
import brave.TracingCustomizer;
import brave.http.HttpRequest;
import brave.http.HttpTracing;
import brave.http.HttpTracingCustomizer;
import brave.internal.Nullable;
import brave.internal.propagation.StringPropagationAdapter;
import brave.propagation.Propagation;
import brave.propagation.TraceContext;
import brave.propagation.TraceContext.Extractor;
import brave.propagation.TraceContext.Injector;
import brave.rpc.RpcRequest;
import brave.rpc.RpcTracing;
import brave.rpc.RpcTracingCustomizer;
import brave.sampler.SamplerFunction;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * This is a <a href="https://github.com/openzipkin-contrib/zipkin-secondary-sampling/tree/master/docs/design.md">Secondary
 * Sampling</a> proof of concept.
 */
public final class SecondarySampling extends Propagation.Factory
    implements TracingCustomizer, HttpTracingCustomizer, RpcTracingCustomizer, Propagation<String> {
  static final SecondarySamplingDecisions.Factory EXTRA_FACTORY =
      new SecondarySamplingDecisions.Factory();

  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder {
    String fieldName = "sampling", tagName = "sampled_keys";
    Propagation.Factory propagationFactory;
    SecondaryProvisioner provisioner = new SecondaryProvisioner() {
      @Override public void provision(Object request, Callback callback) {
      }

      @Override public String toString() {
        return "NoopSecondarySamplingStateProvisioner()";
      }
    };
    @Nullable SamplerFunction<HttpRequest> httpServerSampler;
    @Nullable SamplerFunction<RpcRequest> rpcServerSampler;
    SecondarySampler secondarySampler;

    /** Optional: The ascii lowercase propagation field name to use. Defaults to {@code sampling}. */
    public Builder fieldName(String fieldName) {
      this.fieldName = validateAndLowercase(fieldName, "field");
      return this;
    }

    /** Optional: The ascii lowercase tag name to use. Defaults to {@code sampled_keys}. */
    public Builder tagName(String tagName) {
      this.tagName = validateAndLowercase(tagName, "tag");
      return this;
    }

    /**
     * Required: This will override any value passed to {@link Tracing.Builder#propagationFactory(brave.propagation.Propagation.Factory)}
     *
     * <p>This controls the primary propagation mechanism.
     */
    public Builder propagationFactory(Propagation.Factory propagationFactory) {
      if (propagationFactory == null) throw new NullPointerException("propagationFactory == null");
      this.propagationFactory = propagationFactory;
      return this;
    }

    /**
     * Optional: This will override what is passed to {@link HttpTracing.Builder#serverSampler(SamplerFunction)}
     *
     * <p>This controls the primary sampling mechanism for HTTP server requests. This control is
     * present here for convenience only, as it can be done externally with {@link
     * HttpTracingCustomizer}.
     */
    public Builder httpServerSampler(SamplerFunction<HttpRequest> httpServerSampler) {
      if (httpServerSampler == null) throw new NullPointerException("httpServerSampler == null");
      this.httpServerSampler = httpServerSampler;
      return this;
    }

    /**
     * Optional: This will override what is passed to {@link RpcTracing.Builder#serverSampler(SamplerFunction)}
     *
     * <p>This controls the primary sampling mechanism for RPC server requests. This control is
     * present here for convenience only, as it can be done externally with {@link
     * RpcTracingCustomizer}.
     */
    public Builder rpcServerSampler(SamplerFunction<RpcRequest> rpcServerSampler) {
      if (rpcServerSampler == null) throw new NullPointerException("rpcServerSampler == null");
      this.rpcServerSampler = rpcServerSampler;
      return this;
    }

    /**
     * Optional: Parses a request to provision new secondary sampling keys prior to {@link
     * #secondarySampler(SecondarySampler) sampling existing ones}.
     *
     * <p>By default, this node will only participate in existing keys, it will not create new
     * sampling keys.
     */
    public Builder provisioner(SecondaryProvisioner provisioner) {
      if (provisioner == null) throw new NullPointerException("provisioner == null");
      this.provisioner = provisioner;
      return this;
    }

    /**
     * Required: Performs secondary sampling before primary {@link #httpServerSampler(SamplerFunction)
     * HTTP} and {@link #rpcServerSampler(SamplerFunction) RPC} sampling.
     */
    public Builder secondarySampler(SecondarySampler secondarySampler) {
      if (secondarySampler == null) throw new NullPointerException("secondarySampler == null");
      this.secondarySampler = secondarySampler;
      return this;
    }

    public SecondarySampling build() {
      if (propagationFactory == null) throw new NullPointerException("propagationFactory == null");
      if (secondarySampler == null) throw new NullPointerException("secondarySampler == null");
      return new SecondarySampling(this);
    }

    Builder() {
    }
  }

  final Propagation.Factory delegateFactory;
  final Propagation<String> delegate;
  final List<String> keyNames;
  final String fieldName, tagName;

  final SecondaryProvisioner provisioner;
  // TODO: it may be possible to make a parameterized primary sampler that just checks the input
  // type instead of having a separate type for http, rpc etc.
  @Nullable final SamplerFunction<HttpRequest> httpServerSampler;
  @Nullable final SamplerFunction<RpcRequest> rpcServerSampler;
  final SecondarySampler secondarySampler;

  SecondarySampling(Builder builder) {
    this.delegateFactory = builder.propagationFactory;
    this.delegate = builder.propagationFactory.get();
    this.fieldName = builder.fieldName;
    this.tagName = builder.tagName;
    this.provisioner = builder.provisioner;
    this.httpServerSampler = builder.httpServerSampler;
    this.rpcServerSampler = builder.rpcServerSampler;
    this.secondarySampler = builder.secondarySampler;
    ArrayList<String> keys = new ArrayList<>(delegate.keys());
    keys.add(fieldName);
    this.keyNames = Collections.unmodifiableList(keys);
  }

  @Override public List<String> keys() {
    return keyNames;
  }

  @Override public boolean supportsJoin() {
    return delegateFactory.supportsJoin();
  }

  @Override public boolean requires128BitTraceId() {
    return delegateFactory.requires128BitTraceId();
  }

  @Override public TraceContext decorate(TraceContext context) {
    TraceContext result = delegateFactory.decorate(context);
    return EXTRA_FACTORY.decorate(result);
  }

  @Override public <R> Injector<R> injector(Setter<R, String> setter) {
    if (setter == null) throw new NullPointerException("setter == null");
    return new SecondarySamplingInjector<>(this, setter);
  }

  @Override public <R> Extractor<R> extractor(Getter<R, String> getter) {
    if (getter == null) throw new NullPointerException("getter == null");
    return new SecondarySamplingExtractor<>(this, getter);
  }

  @Override public Propagation<String> get() {
    return this;
  }

  @Override public <K> Propagation<K> create(KeyFactory<K> keyFactory) {
    return StringPropagationAdapter.create(this, keyFactory);
  }

  @Override public void customize(Tracing.Builder builder) {
    builder.addFinishedSpanHandler(new SecondarySamplingSpanHandler(tagName))
        .propagationFactory(this)
        .alwaysReportSpans();
  }

  @Override public void customize(HttpTracing.Builder builder) {
    if (httpServerSampler != null) builder.serverSampler(httpServerSampler);
  }

  @Override public void customize(RpcTracing.Builder builder) {
    if (rpcServerSampler != null) builder.serverSampler(rpcServerSampler);
  }

  static String validateAndLowercase(String name, String title) {
    if (name == null || name.isEmpty()) {
      throw new IllegalArgumentException(name + " is not a valid " + title + " name");
    }
    return name.toLowerCase(Locale.ROOT);
  }
}
