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

import brave.Tracing;
import brave.TracingCustomizer;
import brave.internal.MapPropagationFields;
import brave.internal.PropagationFieldsFactory;
import brave.propagation.Propagation;
import brave.propagation.Propagation.KeyFactory;
import brave.propagation.TraceContext;
import java.util.List;
import java.util.Locale;

/**
 * This is a <a href="https://github.com/openzipkin-contrib/zipkin-secondary-sampling/tree/master/docs/design.md">Secondary
 * Sampling</a> proof of concept.
 */
public final class SecondarySampling extends Propagation.Factory implements TracingCustomizer {
  static final ExtraFactory EXTRA_FACTORY = new ExtraFactory();

  public static Builder newBuilder() {
    return new Builder();
  }

  public static final class Builder {
    String fieldName = "sampling", tagName = "sampled_keys";
    Propagation.Factory propagationFactory;
    SecondarySampler sampler;

    /** The ascii lowercase propagation field name to use. Defaults to {@code sampling}. */
    public Builder fieldName(String fieldName) {
      this.fieldName = validateAndLowercase(fieldName, "field");
      return this;
    }

    /** The ascii lowercase tag name to use. Defaults to {@code sampled_keys}. */
    public Builder tagName(String tagName) {
      this.tagName = validateAndLowercase(tagName, "tag");
      return this;
    }

    /**
     * Use the same value normally passed to {@link Tracing.Builder#propagationFactory(brave.propagation.Propagation.Factory)}
     *
     * <p>This controls the primary propagation mechanism.
     */
    public Builder propagationFactory(Propagation.Factory propagationFactory) {
      if (propagationFactory == null) throw new NullPointerException("propagationFactory == null");
      this.propagationFactory = propagationFactory;
      return this;
    }

    public Builder sampler(SecondarySampler sampler) {
      if (sampler == null) throw new NullPointerException("sampler == null");
      this.sampler = sampler;
      return this;
    }

    public SecondarySampling build() {
      if (propagationFactory == null) throw new NullPointerException("propagationFactory == null");
      if (sampler == null) throw new NullPointerException("policy == null");
      return new SecondarySampling(this);
    }

    Builder() {
    }
  }

  final String fieldName, tagName;
  final Propagation.Factory delegate;
  final SecondarySampler sampler;

  SecondarySampling(Builder builder) {
    this.fieldName = builder.fieldName;
    this.tagName = builder.tagName;
    this.delegate = builder.propagationFactory;
    this.sampler = builder.sampler;
  }

  @Override public boolean supportsJoin() {
    return delegate.supportsJoin();
  }

  @Override public <K> Propagation<K> create(KeyFactory<K> keyFactory) {
    if (keyFactory == null) throw new NullPointerException("keyFactory == null");
    return new Propagation<>(delegate.create(keyFactory), keyFactory.create(fieldName), this);
  }

  @Override public boolean requires128BitTraceId() {
    return delegate.requires128BitTraceId();
  }

  @Override public TraceContext decorate(TraceContext context) {
    TraceContext result = delegate.decorate(context);
    return EXTRA_FACTORY.decorate(result);
  }

  @Override public void customize(Tracing.Builder builder) {
    builder.addFinishedSpanHandler(new SecondarySamplingFinishedSpanHandler(tagName))
      .propagationFactory(this)
      .alwaysReportSpans();
  }

  static final class ExtraFactory
    extends PropagationFieldsFactory<SecondarySamplingState, Boolean, Extra> {
    @Override public Class<Extra> type() {
      return Extra.class;
    }

    @Override protected Extra create() {
      return new Extra();
    }

    @Override protected Extra create(Extra parent) {
      return new Extra(parent);
    }
  }

  static final class Extra extends MapPropagationFields<SecondarySamplingState, Boolean> {
    Extra() {
    }

    Extra(Extra parent) {
      super(parent);
    }
  }

  static class Propagation<K> implements brave.propagation.Propagation<K> {
    final brave.propagation.Propagation<K> delegate;
    final K samplingKey;
    final SecondarySampling secondarySampling;

    Propagation(brave.propagation.Propagation<K> delegate, K samplingKey,
      SecondarySampling secondarySampling) {
      this.delegate = delegate;
      this.samplingKey = samplingKey;
      this.secondarySampling = secondarySampling;
    }

    @Override public List<K> keys() {
      return delegate.keys();
    }

    @Override public <C> TraceContext.Injector<C> injector(Setter<C, K> setter) {
      if (setter == null) throw new NullPointerException("setter == null");
      return new SecondarySamplingInjector<>(this, setter);
    }

    @Override public <C> TraceContext.Extractor<C> extractor(Getter<C, K> getter) {
      if (getter == null) throw new NullPointerException("getter == null");
      return new SecondarySamplingExtractor<>(this, getter);
    }
  }

  static String validateAndLowercase(String name, String title) {
    if (name == null || name.isEmpty()) {
      throw new IllegalArgumentException(name + " is not a valid " + title + " name");
    }
    return name.toLowerCase(Locale.ROOT);
  }
}
