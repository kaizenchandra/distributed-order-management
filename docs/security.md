# Security

Each service validates JWT issuer, audience oms and signature via OIDC JWKS. Order and query enforce JWT subject
ownership. Inventory, payment and fulfillment require oms.service; Saga admin and metrics require oms.admin. Gateway
forwards caller credentials; Catalog quote accepts authenticated customers. Password grant and HTTP exist solely in the
loopback local demonstration. Use authorization code + PKCE and production TLS.

Generated internal-client and database secrets reside only in ignored deployment files. Helm references externally
provisioned per-service DB secrets. The local internal client has both admin and service scopes to simplify tests;
production must split workload identities and operator identities, rotate secrets and restrict audiences per service.

Kafka now uses SASL/PLAIN over TLS on both broker and controller listeners, with hostname verification, a generated
local CA, unique service principals and StandardAuthorizer deny-by-default ACLs. There is no plaintext listener. Topic
auto-creation is disabled. The bootstrap admin creates only known command/event/DLT topics; each participant has its own
group and limited read/write topics. Negative tests verify wrong password, untrusted CA, anonymous API access,
unauthorized publishing and consumer-group isolation.

The broker log directory is explicitly the mounted persistent volume. Published events and ACL metadata survive broker
container recreation. Local TLS material/credentials are ignored, excluded from image build contexts, and protected by a
host-private directory. Applications bind only their own credential file and the truststore. Bootstrap is a privileged
one-shot component; do not mount its credentials into application pods.

Production still requires CA lifecycle/rotation, broker HA, network policies and workload-specific operator identities.
Helm consumes externally provisioned per-service Kafka secrets containing client.properties and truststore.p12; it does
not operate the Kafka cluster or issue certificates. SQL values are bound; no card data is accepted or logged. Database
roles are separate but own their schemas locally. Production migration and runtime roles must differ.

Compatibility
references: [Spring Boot 3.5 requirements](https://docs.spring.io/spring-boot/3.5/system-requirements.html)
and [Keycloak subject/basic scope behavior](https://www.keycloak.org/docs/26.4.7/server_admin/index.html). The local
realm explicitly configures its subject mapper.

The separate provider simulator requires a generated service key on every internal API. Payment alone receives that key
in Compose (test tooling can read local ignored configuration). Its ledger transaction commits before intentionally
delayed responses. Provider access is not routed through the customer gateway. Production external adapters must supply
verified HTTPS authentication and payment-provider semantics.
