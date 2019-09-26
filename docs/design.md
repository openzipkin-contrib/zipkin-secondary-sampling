## Overview
Secondary sampling allows participants to choose trace data from a request even when it is not sampled with B3. This is particularly important with customer support or triage in large deployments. Triggers occur based on propagated "sampling keys", human readable labels that identify an investigation or a topology of interest.

This design allows multiple participants to perform investigations that possibly overlap, while only incurring overhead at most once. For example, if B3 is sampled, all investigations reuse its data. If B3 is not, investigations only record data at trigger points in the request.

After recording, these keys are passed to a Trace Forwarder who can easily dispatch the same data to many places which may have different retention and cost profiles. This is a cheaper and more focused approach than sending 100% data to a triage system for late sampling. By partitioning data by investigation, any span processors start with the most relevant subset of the network. For example, topology graphs can operate with less data and without uninteresting data. Any late sampling that exists skips less data.

Here are some examples of sampling keys:

* `gatewayplay`: I want to see 50 gateway requests per second with a path expression `/play/*`. However, I only want data from the gateway and playback services.
* `authcache`: I want to see up to 100 `authUser()` gRPC requests per second, but only between the auth service and its cache.

The below design is made to drop into existing Zipkin sites and cause no breaks of the primary sampling logic. The fundamentals used are:
* A function of request creates zero or many "sampling keys". This function trigger anywhere in the service graph.
* A header `sampling` is co-propagated with B3 including these keys and any parameters.
* A delimited span tag `sampled_keys` is added to all recorded spans. `sampled_keys` is a subset of all propagated keys relevant for this hop. Notably, it may include a keyword 'b3' if the span was B3 sampled.
* A "trace forwarder" routes data to relevant participants by parsing the `sampled_keys` tag.

## Credit
This design comes with thanks to [Narayanan Arunachalam](https://github.com/narayaruna/) who spent the last year integrating several tracing systems into one pipeline at Netflix. The sampling keys concept originates from internal work which collaborates with a dynamic property system to trigger data collection for support scenarios.

## Background

Typically, a Zipkin trace is sampled up front and before any activity is recorded. [B3 propagation](https://github.com/openzipkin/b3-propagation/) conveys the sampling decision downwards consistently. In other words, a "no" the decision never changes from unsampled to sampled on the same request.

Many large sites use random sampling, to ensure a small percentage <5% result in a trace. While nuanced, it is important to note that even when random sampling, sites often have blacklists which prevent instrumentation from triggering at all. A prime example are health checks which are usually never recorded even if everything else is randomly sampled.

Many conflate Zipkin and B3 with pure random sampling, because initially that was the only choice. However times have changed. Sites often use conditions [such as an http request](https://github.com/openzipkin/zipkin-go/blob/master/middleware/http/request_sampler.go) to choose data. For example, record 100% of traffic at a specific endpoint (while randomly sampling other traffic). Choosing what to record based on context including request and node-specific state is called conditional sampling.

In either case of random or conditional sampling, there's other guards as well. For example, decisions are subject to a [rate-limit](https://github.com/openzipkin/brave/blob/master/brave/src/main/java/brave/sampler/RateLimitingSampler.java). For example, up to 1000 traces per second for this endpoint means effectively 100% until/unless that cap is reached. Further concepts are available in [William Louth's Scaling Distributed Tracing talk](https://medium.com/@autoletics/scaling-distributed-tracing-c848d911ae2e).

The important takeaway is that existing Zipkin sites select traces based on criteria visible at the beginning of the request. Once selected, this data is expected to be recorded into Zipkin consistently even if the request crosses 300 services.

For the rest of this document, we'll call this up front, consistent decision the "primary sampling decision". We'll understand that this primary decision is propagated in-process in a trace context and across nodes using [B3 propagation](https://github.com/openzipkin/b3-propagation/).

## Sampling Keys
Secondary sampling decisions can happen anywhere in the trace and can trigger recording anywhere also. For example, a gateway could add a sampling key that is triggered only upon reaching a specific service. Sampling keys are human readable labels corresponding to a trace participant. There's no established registry or mechanism for choosing these labels, as it is site-specific. An example might be `auth15pct`.

### The `sampling` field
The `sampling` header (or specifically propagated field) carries the sampling keys and any parameters, such as ttl. Keys are comma delimited. Parameters for a key begin at the semi-colon (;) character and are themselves semi-colon delimited. This means semi-colons and commas are all reserved characters.

For example, here's encoding of TTL 1 for the `authcache` key:
```
sampling: authcache;ttl=1
```

The naming convention `sampling` follows the same design concern as [b3 single](https://github.com/openzipkin/b3-propagation/blob/master/RATIONALE.md#relationship-to-jms-java-message-service). Basically, hyphens cause problems across messaging links. By avoiding them, we allow the same system to work with message traces as opposed to just RPC ones, and with no conversion concerns.  This encoding is the similar to the [Accept header](https://tools.ietf.org/html/rfc7231#section-5.3.2) in order to provide familiarity.

### `spanId` parameter
The `spanId` parameter should be added to a sampling key when only a subset of the service graph is triggered. This allows features like rate-limiting to be applied consistently. It also allows intentional gaps between triggers to be repaired later. Details about the `spanId` are discussed later in this document. 

## Non-interference
The application is unaware secondary sampling. It is critical that this design and tooling in no way change the api surface to instrumentation libraries, such as what's used by frameworks like Spring Boot. This reduces implementation risk and also allows the feature to be enabled or disabled without affecting production code.

Moreover, the fact that there are multiple participants choosing data differently should not be noticeable by instrumentation. All participants use the same trace and span IDs, which means log correlation is not affected. Sharing instrumentation and reporting means we are not burdening the application with redundant overhead. It also means we are not requiring engineering effort to re-instrument each time a participant triggers recording.

## The Trace Forwarder
Each participant in the trace could have different capacities, retention rates and billing implications. The responsibility for this is a zipkin-compatible endpoint, which routes the same data to participants associated with a sampling key. We'll call this the trace forwarder. Some examples are [PitchFork](https://github.com/jeqo/zipkin-forwarder) and [Zipkin Forwarder](https://github.com/jeqo/zipkin-forwarder).

If the trace forwarder sees two keys b3 and gateway, it knows to forward the same span to the standard Zipkin backend as well as the API Gateway team's Zipkin.

### The `sampled_keys` Tag
As trace data is completely out-of-band: it is decoupled from request headers. For example, if the forwarder needs to see sampled keys, they must be encoded into a tag `sampled_keys`.

The naming convention `sampled_keys` two important facets. One is that it is encoded lower_snake_case. This is to allow straight-forward json path expressions, like `tags.sampled_keys`. Secondly this is the word "sampled" to differentiate this from the `sampling` header. Keys sampled are a subset of all sampling keys, hence the word "sampled" not "sampling". The value is comma separated as it is easy to tokenize. It isn't a list because Zipkin's data format only allows string values.

### The `b3` sampling key
The special sampling key `b3` ensures secondarily sampled data are not confused with B3 sampled data. Remember, in normal Zipkin installs, presence of spans at all imply they were B3 sampled. Now that there are multiple destinations, we need to back-fill a tag to indicate the base case. This ensures the standard install doesn't accidentally receive more data than was B3 sampled. `b3` should never appear in the `sampling` header: it is a pointer to the sampling state of B3 headers.

## Rate limited sampling keys
In normal B3 propagation, a sampling rate is guaranteed by virtue of all requests being uniformly sampled or not. For example, a rate limiting implementation propagates `1` when sampled and '0' when not. By virtue continuous downstream propagation, all nodes know from these fields whether or not to record data.

The `spanId` parameter of a sampling field allows consistent rate limiting when secondary sampling. For example, the first triggered participant adds their outgoing span ID as the `spanId` parameter. Other participants look to see if this parameter is present or not before themselves triggering.

## Impact of skipping a service
It is possible that some sampling keys skip hops, or services, when recording. When this happens, parent IDs will be wrong, and also any dependency links will also be wrong. It may be heuristically possible to reconnect the spans, but this will push complexity into the forwarder, at least requiring it to buffer a trace based on a sampling key.

For this reason, we always update the `spanId` parameter of the corresponding `sampling` field entry. When a discrepancy is noticed, this parameter is copied to the corresponding `sampled_keys` tag entry as `parentId`.

This allows any trace forwarders to correct the hierarchy as needed. Please look at the gatewayplay example for details.

## Implementation requirements
Not all tracing libraries have the same features. The following are required for this design to work:

* ability to trigger a "local sampled" decision independent of the B3 decision, which propagates to child contexts
* propagation components must be extensible such that the `sampling` field can be extracted and injected
* trace context extractors must see request objects, to allow for secondary request sampling decisions.
  * often they can only see headers, but they now need to see the entire request object (ex the http path)
* ability to attach extra data to the trace context, in order to store sampling key state.
* ability to differentiate local roots from intermediate spans. This is needed to detect if a hierarchy problem exists.
* a span finished hook needs to be able write the `sampled_keys` tag based on this state.
* the span reporter needs to be able to see all spans, not just B3 sampled ones.

## Example participants

The following fictitious application is used for use case scenarios. It highlights that only sites with 10 or more applications will benefit from the added complexity of secondary sampling. Small sites may be fine just recording everything.

In most scenarios, the gateway provisions sampling keys even if they are triggered downstream.

```
gateway -> api -> auth -> cache  -> authdb
               -> recommendations -> cache -> recodb
               -> playback -> license -> cache -> licensedb
                           -> moviemetadata
                           -> streams
```

### authcache
I want to see up to 100 `authUser()` gRPC requests per second, but only between the auth service and its cache.

This scenario is interesting as the decision happens well past the gateway. As the auth service only interacts with the database via the cache, and cache is its only downstream, it is easiest to implement this with `ttl=1`

#### Example flow

The gateway adds the sampling key `authcache` to the `sampling` field. Configuration management is responsible to ship the TTL and sample rate to the auth node.
```
sampling: authcache
```

The complete key `authcache` is ignored by all nodes until the auth service. Even if they add other sampling keys, they do not drop this one.

When the auth service sees this key, it looks up the trigger and ttl parameters from out-of-band configuration. Then, it triggers a decision. Assuming the decision is yes, it adds the TTL value down to the cache service. 

Let's assume the decision was pass. The auth service records the request regardless of B3 headers and appends `authcache` to `span.tags.sampled_keys` when reporting the span. Outbound headers include the name of the sampling key, the ttl value and the span ID of the outbound request.
```
sampling: authcache;ttl=1;spanId=19f84f102048e047
```

The cache service does not need any configuration state as it knows to trigger based on a non-zero TTL. Upon reading the header, it records decrements the ttl to 0, and appends `authcache` to `span.tags.sampled_keys` when reporting the span. The propagation logic knows to redact any ttl=0 fields. In other words, no special logic is needed to redact authcache.

### gatewayplay
I want to see 50 gateway requests per second with a path expression `/play/*`. However, I only want data from the gateway and playback services.

This use case is interesting because the trigger occurs at the same node that provisions the sampling key. Also, it involves skipping the `api` service, which may cause some technical concerns at the forwarding layer.

#### Example flow
Similar `authcache`, the gateway adds the sampling key `gatewayplay` to the `sampling` field.
```
sampling: gatewayplay
```

This use case shows that provisioning and triggering a sampling decision are decoupled even in the same node. First, a `gatewayplay` sampling key is provisioned based on out-of-band configuration. It is immediately evaluated, against the configured rate. If sampled, the `spanId` parameter will be sent downstream.

Let's assume the decision was pass. The gateway service records the request regardless of B3 headers and appends `gatewayplay` to `span.tags.sampled_keys` when reporting the span. Outbound headers include the name of the sampling key and the span ID of the outbound request.
```
sampling: gatewayplay;spanId=26bd982d53f50d1f
```

The sampling field is unaltered as it passes the api service because out-of-band configuration does not trigger on the key `gatewayplay`. The api service would only report data if the request was B3 sampled.

The play service is configured participate and to honor the sample rate. It only triggers when both the `gatewayplay` sampling key exists and it has a `spanId` parameter. Triggering regardless of `spanId`, would be effectively 100% sampling. As `spanId` is only present when upstream sampled, this guarantees the intended rate.

Assuming that was present, play samples the incoming request. When reporting, the parent ID won't match the primary parent ID, because the primary parent is the api service which wasn't sampled for the key "gatewayplay." To ensure the trace isn't hierarchically broken, it saves the incoming spanId parameter for `gatewayplay` (in this exampled `spanId=26bd982d53f50d1f`) as a corresponding `parentId` parameter on the sampled tag. Literally, the tag value reported out-of-band would be `b3,gatewayplay;parentId=26bd982d53f50d1f`, if b3 was also sampled.

A trace forwarder could then use that data to fix the hierarchy. Possibly like this when sending to the `gatewayplay` participant:

```diff
3c3
<   "parentId": "0562809467078eab",
---
>   "parentId": "26bd982d53f50d1f",
11,13c11,12
<     "sampled_keys": "b3,gatewayplay;parentId=26bd982d53f50d1f"
<   },
<   "shared": true
---
>     "linkedParentId": "0562809467078eab"
>   }
```
