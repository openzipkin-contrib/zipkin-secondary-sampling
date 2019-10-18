# brave-secondary-sampling rationale

## `SecondaryProvisioner` overview

`SecondaryProvisioner`'s job is to create new sampling key configuration. Without this,
implementation can only participate in existing sampling keys: they cannot create new ones.

### What happens if the sampling key being provisioned exists in incoming headers?
It is possible that a sampling key the host wants to provision is also in headers. To write an Api
that can see potentially pre-existing state is more complex. As it is expected that a local decision
would override anyway, this implementation ignores any incoming state when the same key is
provisioned locally.

### Why not always sample new keys?
Some sites will want to decentralize key provisioning, so every time they create a key, they will
also sample it. For example, if such a site desires just data around the auth subsytem, the auth
service itself would provision a key and also send data for it before propagating downstream.

The converse could also be true. For example, existing Zipkin sites centralize policy in API
gateways. In this case, the duty for provisioning secondary keys could also be centralized, even
if the desired data is downstream.
