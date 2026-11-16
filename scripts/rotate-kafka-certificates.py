#!/usr/bin/env python3
"""Resumable HA CA rotation: dual trust -> rolling leaves -> new-only trust. No secret output."""
import datetime
import fcntl
import json
import os
import shutil
import subprocess
import sys
import uuid
from pathlib import Path

from compose_support import root, run
from recovery import inspect
from smoke import token, request, eventually

os.environ.setdefault('OMS_COMPOSE_FILE', 'compose.ha.yml')
assert os.environ['OMS_COMPOSE_FILE'] in ('compose.ha.yml', 'compose.tuned.yml'), 'Rotation requires the HA profile'
secure = root / 'docker/kafka.local';
ca = root / '.local/kafka-ca';
creds = json.loads((secure / 'credentials.json').read_text());
env = os.environ.copy();
env['OMS_STORE_PASS'] = creds['store']
java = Path(os.environ['JAVA_HOME']) / 'bin';
nodes = ['kafka-a', 'kafka-b', 'kafka-c'];
apps = ['catalog-service', 'inventory-service', 'order-service', 'payment-service', 'fulfillment-service',
        'saga-orchestrator', 'order-query-service', 'notification-service', 'api-gateway']
bootstrap = ','.join(n + ':9092' for n in nodes)
journal = secure / 'rotation.json'


def execute(*args): subprocess.run([str(a) for a in args], env=env, check=True, stdout=subprocess.DEVNULL,
                                   stderr=subprocess.PIPE)


def save(state):
    state['updatedAt'] = datetime.datetime.now(datetime.timezone.utc).isoformat();
    temp = journal.with_suffix('.tmp');
    temp.write_text(json.dumps(state, indent=2));
    temp.replace(journal)


def install(src, dst):
    # Individual bind-mounted files must retain their inode. JVMs reload only on the explicit rolling restart.
    with open(dst, 'wb') as f: f.write(Path(src).read_bytes());f.flush();os.fsync(f.fileno())
    Path(dst).chmod(0o644)


def props(name, store):
    p = secure / 'cli' / (name + '.properties');
    p.write_text(
        'ssl.truststore.location=/security/cli/' + store + '\nssl.truststore.password=' + creds['store'] + '\n');
    p.chmod(0o644);
    return p


def probe(node, kind, expected=True):
    script = 'java -cp /security/cli TlsProbe ' + node + ' 9092 /security/cli/rotation-' + kind + '.properties'
    result = run('run', '--rm', '--no-deps', '--entrypoint', '/bin/bash', 'kafka-init', '-c', script, check=False,
                 timeout=20)
    if expected:
        assert result.returncode == 0, result.stderr[-1500:]
    else:
        assert result.returncode != 0 and (
                'SSLHandshakeException' in result.stderr or 'PKIX' in result.stderr), result.stderr[-1500:]
    return result.stdout.strip()


def healthy_cluster():
    text = run('run', '--rm', '--no-deps', '--entrypoint', '/bin/bash', 'kafka-init', '-c',
               '/opt/kafka/bin/kafka-topics.sh --bootstrap-server ' + bootstrap + ' --command-config /security/cli/admin.properties --describe --topic oms.order-events').stdout
    import re
    rows = [line for line in text.splitlines() if 'Partition:' in line];
    return len(rows) == 3 and all(len(re.search(r'Isr:\s*([\d,]+)', line).group(1).split(',')) == 3 for line in rows)


def brokers():
    for node in nodes: run('restart', node);eventually(healthy_cluster, 120)


def clients():
    subprocess.run([sys.executable, str(root / 'scripts/check-no-host.py')], check=True)
    for app in apps: run('up', '-d', '--no-deps', '--force-recreate', '--wait', '--wait-timeout', '120', app)


def checkout():
    customer = token('customer');
    operator = token();
    key = str(uuid.uuid4())
    code, order = request(8099, '/api/v1/orders', customer, {'items': [{'sku': 'SKU-1', 'quantity': 1}]}, key);
    assert code == 202, (code, order)
    eventually(lambda: inspect(order['orderId'], operator).get('state') == 'COMPLETED', 180)
    payment = request(8093, '/api/v1/payments/' + order['orderId'], operator)[1];
    assert payment['captured'] == 7500 and payment['refunded'] == 0


def prepare():
    directory = secure / 'rotations' / str(uuid.uuid4());
    directory.mkdir(parents=True, mode=0o700)
    for f in ['ca.crt', 'ca.key', 'ca.srl']:
        if (ca / f).exists(): shutil.copyfile(ca / f, directory / ('old-' + f))
    shutil.copyfile(secure / 'truststore.p12', directory / 'old-truststore.p12')
    for node in ['kafka'] + nodes:
        source = secure / 'keystore.p12' if node == 'kafka' else secure / 'ha' / (node + '.p12');
        shutil.copyfile(source, directory / ('old-' + node + '.p12'))
    execute('openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '365', '-keyout', directory / 'ca.key',
            '-out', directory / 'ca.crt', '-subj', '/CN=OMS-rotated-CA-' + directory.name)
    for node in ['kafka'] + nodes:
        execute('openssl', 'req', '-newkey', 'rsa:2048', '-nodes', '-keyout', directory / (node + '.key'), '-out',
                directory / (node + '.csr'), '-subj', '/CN=' + node)
        ext = directory / (node + '.ext');
        ext.write_text(
            'subjectAltName=DNS:' + node + ',DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth,clientAuth\n')
        execute('openssl', 'x509', '-req', '-in', directory / (node + '.csr'), '-CA', directory / 'ca.crt', '-CAkey',
                directory / 'ca.key', '-CAcreateserial', '-out', directory / (node + '.crt'), '-days', '365',
                '-extfile', ext)
        execute('openssl', 'pkcs12', '-export', '-in', directory / (node + '.crt'), '-inkey',
                directory / (node + '.key'), '-certfile', directory / 'ca.crt', '-name', node, '-out',
                directory / (node + '.p12'), '-passout', 'env:OMS_STORE_PASS')
        (directory / (node + '.key')).unlink();
        (directory / (node + '.csr')).unlink()
    for kind, certs in [('dual', ['old-ca.crt', 'ca.crt']), ('new', ['ca.crt'])]:
        for index, cert in enumerate(certs): execute(java / 'keytool', '-importcert', '-noprompt', '-alias', str(index),
                                                     '-file', directory / cert, '-keystore',
                                                     directory / (kind + '-truststore.p12'), '-storetype', 'PKCS12',
                                                     '-storepass:env', 'OMS_STORE_PASS')
    for kind, source in [('old', 'old-truststore.p12'), ('new', 'new-truststore.p12')]: install(directory / source,
                                                                                                secure / 'cli' / (
                                                                                                        'rotation-' + kind + '.p12'));props(
        'rotation-' + kind, 'rotation-' + kind + '.p12')
    execute(java / 'javac', '--release', '17', '-d', secure / 'cli', root / 'docker/TlsProbe.java');
    (secure / 'cli/TlsProbe.class').chmod(0o644)
    state = {'stage': 'PREPARED', 'directory': str(directory), 'rolled': [], 'fingerprints': {}};
    save(state);
    return state


if __name__ == '__main__':
    os.umask(0o077)
    with (secure / 'rotation.lock').open('w') as lock:
        fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
        state = json.loads(journal.read_text()) if journal.exists() else prepare()
        if '--new' in sys.argv:
            assert state['stage'] in ('RETIRED', 'ROLLED_BACK'), 'Resume or roll back the existing rotation first'
            shutil.copyfile(journal, Path(state['directory']) / 'completed-journal.json')
            state = prepare()
        directory = Path(state['directory'])
        if '--rollback' in sys.argv:
            install(directory / 'dual-truststore.p12', secure / 'truststore.p12');
            clients();
            brokers()
            for node in nodes: install(directory / ('old-' + node + '.p12'), secure / 'ha' / (node + '.p12'));run(
                'restart', node);eventually(healthy_cluster, 120)
            install(directory / 'old-kafka.p12', secure / 'keystore.p12');
            install(directory / 'old-truststore.p12', secure / 'truststore.p12');
            clients();
            brokers()
            for f in ['ca.crt', 'ca.key', 'ca.srl']:
                if (directory / ('old-' + f)).exists(): shutil.copyfile(directory / ('old-' + f), ca / f)
            state['stage'] = 'ROLLED_BACK';
            save(state);
            checkout();
            print('PASS rotation rollback restored old trust and checkout');
            sys.exit()
        if state['stage'] == 'PREPARED':
            for node in nodes: probe(node, 'new', False)
            install(directory / 'dual-truststore.p12', secure / 'truststore.p12');
            clients();
            brokers();
            state['stage'] = 'DUAL_TRUST';
            save(state);
            checkout();
            print('PASS dual CA trust installed on clients and all quorum members', flush=True)
        if state['stage'] == 'DUAL_TRUST':
            for node in nodes:
                if node in state['rolled']: continue
                install(directory / (node + '.p12'), secure / 'ha' / (node + '.p12'));
                shutil.copyfile(directory / (node + '.crt'), secure / 'ha' / (node + '.crt'));
                run('restart', node);
                eventually(healthy_cluster, 120)
                state['fingerprints'][node] = probe(node, 'new');
                state['rolled'].append(node);
                save(state);
                checkout();
                print('PASS rolled new certificate and completed checkout: ' + node, flush=True)
            install(directory / 'kafka.p12', secure / 'keystore.p12');
            shutil.copyfile(directory / 'kafka.crt', secure / 'broker.crt');
            state['stage'] = 'NEW_LEAVES';
            save(state)
        if state['stage'] == 'NEW_LEAVES':
            for node in nodes: probe(node, 'new');probe(node, 'old', False)
            install(directory / 'new-truststore.p12', secure / 'truststore.p12');
            clients();
            brokers()
            for node in nodes: probe(node, 'new');probe(node, 'old', False)
            for f in ['ca.crt', 'ca.key', 'ca.srl']: shutil.copyfile(directory / f, ca / f)
            state['stage'] = 'RETIRED';
            save(state);
            checkout();
            print('PASS old CA retired; fresh new-only TLS and checkout verified', flush=True)
        if state['stage'] == 'RETIRED':
            for node in nodes: assert probe(node, 'new') == state['fingerprints'][
                node], 'Unexpected live broker certificate'
        print(json.dumps({'stage': state['stage'], 'fingerprints': state['fingerprints']}, indent=2))
