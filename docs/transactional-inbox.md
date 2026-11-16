# Inbox

Unique (consumer_name,event_id) is inserted inside the same transaction as business mutation and response outbox. A
handler failure rolls back all three. Listener offset acknowledgment follows the method return and local commit.
Rebalance or commit-to-offset crash repeats delivery, which is harmless to committed local effects.

Validation failures get bounded listener retries then a .DLT topic. Payment consumer inbox processing now queues a
durable provider operation instead of making network calls. The operation worker calls the independent provider outside
local transactions, using stable provider identity, lookup and fencing; definitive results commit with response outbox.
The provider owns its own SQL financial effects. Inbox alone does not wrap external calls.
