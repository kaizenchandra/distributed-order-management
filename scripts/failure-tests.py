#!/usr/bin/env python3
"""Destructive only to disposable OMS containers. Uses the secure Docker-only stack."""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
import uuid

from compose_support import command
from recovery import inspect
from smoke import token, request, eventually, root, env


def compose(*args, input=None, timeout=180):
    result = subprocess.run(command() + list(args), cwd=root, input=input, capture_output=True, text=True,
                            timeout=timeout)
    assert result.returncode == 0, (args, result.stderr[-1500:]);
    return result.stdout


def sql(database, statement): return compose('exec', '-T', 'postgres', 'psql', '-U', 'postgres', '-d', database, '-At',
                                             '-c', statement).strip()


def provider(path, body=None):
    data = None if body is None else json.dumps(body).encode()
    req = urllib.request.Request('http://localhost:8100/internal' + path, data=data,
                                 headers={'Content-Type': 'application/json', 'X-Provider-Key': env['PROVIDER_KEY']})
    with urllib.request.urlopen(req, timeout=5) as response: return json.load(response)


def create(customer, scenario=None):
    payload = {'items': [{'sku': 'SKU-1', 'quantity': 1}]}
    if scenario: payload['paymentScenario'] = scenario
    code, order = request(8099, '/api/v1/orders', customer, payload, str(uuid.uuid4()));
    assert code == 202, (code, order);
    return order['orderId']


def operations(oid, admin):
    code, value = request(8093, '/api/v1/admin/payments/' + oid + '/operations', admin)
    return value if code == 200 and isinstance(value, list) else []


def find(oid, kind, admin): return next((op for op in operations(oid, admin) if op['kind'] == kind), {})


def terminal(oid, admin, state='COMPLETED'):
    eventually(lambda: inspect(oid, admin).get('state') == state, 180)


def admin_kafka(script):
    return compose('run', '--rm', '--no-deps', '--entrypoint', '/bin/bash', 'kafka-init', '-c', script, timeout=60)


def offsets(topic):
    raw = admin_kafka(
        '/opt/kafka/bin/kafka-get-offsets.sh --bootstrap-server kafka:9092 --command-config /security/cli/admin.properties --topic ' + topic + ' --time -1')
    return sum(int(line.rsplit(':', 1)[1]) for line in raw.splitlines() if line.startswith(topic + ':'))


if __name__ == '__main__':
    subprocess.run([sys.executable, str(root / 'scripts/check-no-host.py')], check=True)
    if os.environ.get('OMS_COMPOSE_FILE', 'compose.yml') != 'compose.yml' and '--payment-only' not in sys.argv:
        raise SystemExit('Use --payment-only with HA profiles; scripts/ha-test.py covers broker/quorum failures.')
    customer = token('customer');
    admin = token()
    try:
        urllib.request.urlopen('http://localhost:8100/internal/payments/' + str(uuid.uuid4()), timeout=5)
        raise AssertionError('Provider accepted unsigned request')
    except urllib.error.HTTPError as failure:
        assert failure.code == 403, failure.code
    print('PASS provider rejects missing service authentication', flush=True)
    try:
        provider('/operations/' + str(uuid.uuid4()))
        raise AssertionError('Unknown provider operation was reported present')
    except urllib.error.HTTPError as failure:
        assert failure.code == 404, failure.code
    print('PASS authenticated provider lookup preserves definitive 404 absence', flush=True)
    compose('stop', 'payment-provider')
    try:
        oid = create(customer)
        eventually(lambda: find(oid, 'Payment.Authorize', admin).get('status') == 'UNKNOWN', 60)
    finally:
        compose('up', '-d', '--no-deps', '--wait', '--wait-timeout', '90', 'payment-provider')
    terminal(oid, admin)
    account = provider('/payments/' + oid);
    assert account['captured'] == 7500 and account['operation_count'] == 2, account
    print('PASS provider outage before submit: absent lookup safely retried the same operation', flush=True)
    for scenario, kind in [('authorize-timeout', 'Payment.Authorize'), ('capture-timeout', 'Payment.Capture')]:
        oid = create(customer, scenario);
        terminal(oid, admin)
        op = find(oid, kind, admin);
        assert op['status'] == 'SUCCEEDED' and op['attempts'] >= 2, op
        account = provider('/payments/' + oid);
        assert account['captured'] == 7500 and account['operation_count'] == 2, account
        print('PASS ' + scenario + ': lookup resolved lost response; one provider operation', flush=True)
    oid = create(customer, 'refund-timeout');
    terminal(oid, admin)
    assert request(8099, '/api/v1/orders/' + oid + '/cancel', customer, {})[0] == 200
    terminal(oid, admin, 'COMPENSATED');
    op = find(oid, 'Payment.Reverse', admin);
    assert op['attempts'] >= 2, op
    account = provider('/payments/' + oid);
    assert account['refunded'] == 7500 and account['operation_count'] == 3, account
    print('PASS refund timeout reconciliation: refund applied once', flush=True)

    oid = create(customer, 'capture-unknown')
    eventually(lambda: find(oid, 'Payment.Capture', admin).get('status') in ('UNKNOWN', 'MANUAL_REVIEW'), 120)
    op = find(oid, 'Payment.Capture', admin)
    compose('kill', '-s', 'SIGKILL', 'payment-service')
    provider('/operations/' + op['operation_id'] + '/reveal', {})
    compose('up', '-d', '--no-deps', 'payment-service')
    terminal(oid, admin)
    assert provider('/payments/' + oid)['operation_count'] == 2
    print('PASS payment process killed after provider commit: restart reconciled without recapture', flush=True)

    oid = create(customer, 'capture-unknown')
    eventually(lambda: find(oid, 'Payment.Capture', admin).get('status') == 'MANUAL_REVIEW', 180)
    op = find(oid, 'Payment.Capture', admin)
    assert request(8099, '/api/v1/orders/' + oid + '/cancel', customer, {})[0] == 200
    time.sleep(2)
    account = provider('/payments/' + oid);
    assert account['captured'] == 7500 and account['refunded'] == 0, account
    assert request(8093, '/api/v1/payments/' + oid, admin)[1]['captured'] == 0
    provider('/operations/' + op['operation_id'] + '/reveal', {})
    assert request(8093, '/api/v1/admin/payments/operations/' + op['operation_id'] + '/retry', admin, {})[0] == 200
    terminal(oid, admin, 'COMPENSATED')
    account = provider('/payments/' + oid);
    assert account['refunded'] == 7500 and account['operation_count'] == 3, account
    print('PASS ambiguous capture escalated, blocked cancellation effects, then reconciled and refunded once',
          flush=True)

    if '--payment-only' in sys.argv: sys.exit(0)
    compose('stop', 'kafka')
    try:
        oid = create(customer)
        assert request(8091, '/api/v1/orders/' + oid, customer)[1]['status'] == 'PENDING'
        assert int(sql('order_service',
                       "select count(*) from outbox where aggregate_id='" + oid + "' and published_at is null")) == 2
    finally:
        compose('start', 'kafka')
    terminal(oid, admin)
    print('PASS Kafka outage: accepted order persisted two outbox intents and completed after recovery', flush=True)

    # Re-publish all forward commands, preserving message identities.
    sql('saga_orchestrator', "update outbox set published_at=null,next_attempt=now() where aggregate_id='" + oid + "'")
    time.sleep(3)
    assert provider('/payments/' + oid)['operation_count'] == 2
    assert sql('payment_service', "select count(*) from payment_operations where payment_id='" + oid + "'") == '2'
    assert sql('order_service', "select count(*) from order_events where aggregate_id='" + oid + "'") == '2'
    assert sql('inventory_service',
               "select count(*) from inventory_movements where reservation_id='" + oid + "'") == '2'
    print('PASS duplicate Saga commands did not repeat provider financial operations', flush=True)

    before = offsets('oms.order-events')
    assert before > 0
    compose('up', '-d', '--no-deps', '--force-recreate', 'kafka')
    eventually(lambda: offsets('oms.order-events') >= before, 90)
    fresh = create(customer);
    terminal(fresh, admin)
    print('PASS broker container recreation preserved log offsets and ACLs; new checkout completed', flush=True)

    before = offsets('oms.order-events.DLT')
    bad = str(uuid.uuid4());
    envelope = {'eventId': bad, 'eventType': 'Poison', 'aggregateId': bad, 'aggregateVersion': 1, 'schemaVersion': 999,
                'correlationId': bad, 'causationId': None, 'timestamp': '2026-11-15T00:00:00Z', 'payload': {}}
    # Message itself is test data; transport credentials remain mounted files.
    admin_kafka("printf '%s\\n' '" + json.dumps(
        envelope) + "' | /opt/kafka/bin/kafka-console-producer.sh --bootstrap-server kafka:9092 --producer.config /security/cli/admin.properties --topic oms.order-events")
    eventually(lambda: offsets('oms.order-events.DLT') >= before + 2, 90)
    fresh = create(customer);
    terminal(fresh, admin)
    eventually(lambda: request(8096, '/api/v1/query/orders/' + fresh, customer)[0] == 200)
    print('PASS poison envelope reached DLT; healthy projection processing continued', flush=True)
