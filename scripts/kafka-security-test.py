#!/usr/bin/env python3
"""Positive and negative checks against the actual TLS/SASL/ACL broker. No secrets in argv."""
import os
import subprocess
from pathlib import Path

from compose_support import command

root = Path(__file__).resolve().parents[1]


def probe(script):
    result = subprocess.run(command() + ['run', '--rm', '--no-deps', '--entrypoint', '/bin/bash', 'kafka-init', '-c',
                                         script.replace('kafka:9092', os.environ.get('OMS_KAFKA_BOOTSTRAP', (
                                             'kafka-a:9092,kafka-b:9092,kafka-c:9092' if os.environ.get(
                                                 'OMS_COMPOSE_FILE') in ('compose.ha.yml',
                                                                         'compose.tuned.yml') else 'kafka:9092')))],
                            cwd=root, capture_output=True, text=True, timeout=45)
    return result.returncode, result.stdout + result.stderr


checks = [
    ('admin authenticates over verified TLS',
     "/opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --command-config /security/cli/admin.properties --describe --topic oms.order-events",
     None),
    ('wrong password rejected',
     "sed 's/password=\"[^\"]*\"/password=\"wrong\"/' /security/cli/order_service.properties > /tmp/bad.properties; /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --command-config /tmp/bad.properties --list",
     'SaslAuthenticationException'),
    ('untrusted certificate rejected',
     "grep -v '^ssl.truststore' /security/cli/admin.properties > /tmp/untrusted.properties; /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --command-config /tmp/untrusted.properties --list",
     'SSLHandshakeException'),
    ('order identity cannot publish payment commands',
     "printf 'forbidden-command\\n' | /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:9092 --producer.config /security/cli/order_service.properties --topic oms.payment-service",
     'TopicAuthorizationException'),
    ('query identity cannot consume using another service group',
     "/opt/kafka/bin/kafka-console-consumer.sh --bootstrap-server kafka:9092 --consumer.config /security/cli/order_query_service.properties --topic oms.order-events --group order-service --timeout-ms 5000",
     'GroupAuthorizationException'),
    ('anonymous TLS connection cannot use Kafka API',
     "grep '^ssl\\.' /security/cli/admin.properties > /tmp/anonymous.properties; printf 'security.protocol=SSL\\ndefault.api.timeout.ms=5000\\nrequest.timeout.ms=3000\\n' >> /tmp/anonymous.properties; /opt/kafka/bin/kafka-topics.sh --bootstrap-server kafka:9092 --command-config /tmp/anonymous.properties --list",
     'TimeoutException')]
for title, script, expected in checks:
    code, output = probe(script)
    if expected is None:
        assert code == 0 and 'oms.order-events' in output, (title, output[-2000:])
    else:
        assert expected in output, (title, 'Expected ' + expected, output[-2000:])
    print('PASS ' + title, flush=True)
