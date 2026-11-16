# Kafka HA and certificate rotation

`compose.ha.yml` defines three independently persisted broker/controller processes with separate TLS leaf certificates,
a three-voter KRaft quorum, RF=3, min ISR=2, acks=all/idempotent producers, deny-by-default service ACLs and disabled
unclean leader election. `compose.tuned.yml` preserves these consistency settings with bounded local application
connections and a restored 256-MiB broker heap. An earlier aggressive 192-MiB/free-ratio profile caused quorum churn
under shared-host pressure and was replaced after actual measurements. Current local quorum request/fetch/election
budgets and relative CPU shares are explicit in the file; these trade pause tolerance against failover-detection delay.
Combined broker/controller roles and one physical host are deliberate test-environment choices; this is not proof of
multi-AZ availability.

The original single-node Kafka volume is preserved separately. Switching profiles is not a production Kafka log
migration: drain pending workflows first, and continue using the selected profile for commands and tests. Databases/read
models remain owned and retained. The HA profile creates a separate transport cluster for fault verification.

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
OMS_COMPOSE_FILE=compose.tuned.yml sh scripts/start-ha.sh
OMS_COMPOSE_FILE=compose.tuned.yml python3 scripts/ha-test.py
OMS_COMPOSE_FILE=compose.tuned.yml python3 scripts/kafka-security-test.py
```

HA tests discover the actual partition/controller leaders, kill each independently and verify accepted checkouts. A
two-node quorum/ISR loss must leave orders locally durable/PENDING with unpublished outbox intents. Restoring quorum
drains them. Replica settings are never weakened to improve benchmark results.

## CA rotation

```sh
OMS_COMPOSE_FILE=compose.tuned.yml python3 scripts/rotate-kafka-certificates.py
```

The script prepares new CA/leaf material in ignored private directories, verifies new-only trust rejects the old leaves,
installs dual trust, rolls clients and quorum members, rolls one leaf at a time with ISR recovery, then retires old
trust. Fresh hostname-verified TLS connections prove each new leaf; old-only trust is rejected. Checkout runs after each
transition. A durable journal records stage, completed nodes and fingerprints so a failed run resumes without discarding
keys or restarting the protocol with new identity.

Individual bind-mounted store files are updated in place, followed by explicit JVM restart; atomic replacement would
leave a running file mount on its old inode. Private CA keys never enter application images. Rollback materials are
retained under the protected rotation directory. `--rollback` implements staged restoration; that full rollback
procedure has not been exercised in the recorded campaign. `--new` starts another rotation only after the previous one
completed or rolled back. Re-running a completed rotation verifies live fingerprints without issuing certificates or
financial operations.

This is a local simulator canary workflow using generated certificates. Production needs managed CA issuance, protected
signing keys, expiry monitoring, operator approval/audit policies and multi-host deployment. Public fingerprints/journal
summaries can be retained as evidence; do not publish credential files, private keys, keystores or the `.local`
directory.

Long campaigns refresh customer credentials between fault phases and obtain a fresh admin token for each completion
check; the local realm uses 300-second tokens. Checkout IDs/phases are journaled under ignored
`.local/ha-checkouts.jsonl`. `scripts/ha-test.py --quorum-only` reruns only the quorum outage/recovery pair. These are
operational test-harness controls, not changes to production token validation.
