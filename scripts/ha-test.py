#!/usr/bin/env python3
import datetime
import json
import re
import sys
import time
import urllib.request
import uuid

from compose_support import run, root
from recovery import inspect
from smoke import token, request, eventually, env

nodes = {11: 'kafka-a', 12: 'kafka-b', 13: 'kafka-c'}
bootstrap = ','.join(n + ':9092' for n in nodes.values())
phase = 'startup'


def admin(script): return run('run', '--rm', '--no-deps', '--entrypoint', '/bin/bash', 'kafka-init', '-c',
                              script).stdout


def kafka(tool, args=''): return admin(
    '/opt/kafka/bin/' + tool + ' --bootstrap-server ' + bootstrap + ' --command-config /security/cli/admin.properties ' + args)


def isr3():
    output = kafka('kafka-topics.sh', '--describe --topic oms.order-events')
    rows = [line for line in output.splitlines() if 'Partition:' in line]
    return len(rows) == 3 and all(len(re.search(r'Isr:\s*([\d,]+)', line).group(1).split(',')) == 3 for line in rows)


def checkout(customer, admin_token):
    code, value = request(8099, '/api/v1/orders', customer, {'items': [{'sku': 'SKU-1', 'quantity': 1}]},
                          str(uuid.uuid4()));
    assert code == 202, (code, value)
    (root / '.local').mkdir(exist_ok=True)
    with (root / '.local/ha-checkouts.jsonl').open('a') as journal: journal.write(json.dumps(
        {'orderId': value['orderId'], 'phase': phase,
         'at': datetime.datetime.now(datetime.timezone.utc).isoformat()}) + '\n')
    return value['orderId']


def complete(oid, admin_token):
    admin_token = token(timeout=60)  # A slow HA campaign can exceed the initial token lifetime.
    eventually(lambda: inspect(oid, admin_token).get('state') == 'COMPLETED', 180)
    p = request(8093, '/api/v1/payments/' + oid, admin_token)[1];
    assert p['captured'] == 7500 and p['refunded'] == 0, p
    req = urllib.request.Request('http://localhost:8100/internal/payments/' + oid,
                                 headers={'X-Provider-Key': env['PROVIDER_KEY']})
    with urllib.request.urlopen(req, timeout=10) as response: provider = json.load(response)
    assert provider['captured'] == 7500 and provider['refunded'] == 0 and provider['operation_count'] == 2, provider


if __name__ == '__main__':
    customer = token('customer', timeout=60);
    operator = token(timeout=60);
    eventually(isr3, 120)
    if '--quorum-only' not in sys.argv:
        topic = kafka('kafka-topics.sh', '--describe --topic oms.order-events')
        leader = int(re.search(r'Partition:\s*0\s+Leader:\s*(\d+)', topic).group(1));
        node = nodes[leader]
        customer = token('customer', timeout=60);
        phase = 'partition-leader-loss'
        run('kill', '-s', 'SIGKILL', node)
        try:
            ids = [checkout(customer, operator) for _ in range(5)]
            for oid in ids: complete(oid, operator)
            print('PASS partition leader loss: five accepted orders completed with ISR=2', flush=True)
        finally:
            run('up', '-d', '--no-deps', node)
        eventually(isr3, 120)
        quorum = kafka('kafka-metadata-quorum.sh', 'describe --status');
        controller = nodes[int(re.search(r'LeaderId:\s*(\d+)', quorum).group(1))]
        customer = token('customer', timeout=60);
        phase = 'controller-loss'
        run('kill', '-s', 'SIGKILL', controller)
        try:
            ids = [checkout(customer, operator) for _ in range(5)]
            for oid in ids: complete(oid, operator)
            print('PASS active controller loss: quorum re-elected and checkout continued', flush=True)
        finally:
            run('up', '-d', '--no-deps', controller)
        eventually(isr3, 120)
    customer = token('customer', timeout=60);
    phase = 'quorum-loss'
    down = ['kafka-b', 'kafka-c'];
    run('stop', *down)
    try:
        oid = checkout(customer, operator);
        time.sleep(6)
        assert request(8091, '/api/v1/orders/' + oid, customer)[1]['status'] == 'PENDING'
        value = run('exec', '-T', 'postgres', 'psql', '-U', 'postgres', '-d', 'order_service', '-At', '-c',
                    "select count(*) from outbox where aggregate_id='" + oid + "' and published_at is null").stdout.strip();
        assert value == '2', value
        print('PASS quorum/ISR loss: local order accepted, broker publication failed closed', flush=True)
    finally:
        run('up', '-d', '--no-deps', *down)
    eventually(isr3, 120);
    complete(oid, operator)
    print('PASS restored quorum/ISR drained outbox and completed the pending order', flush=True)
