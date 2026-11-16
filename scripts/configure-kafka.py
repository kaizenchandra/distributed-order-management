#!/usr/bin/env python3
"""Generate local TLS material and distinct Kafka identities. Never print credentials."""
import json
import os
import secrets
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[1];
os.umask(0o077)
secure = root / 'docker/kafka.local';
secure.mkdir(exist_ok=True);
secure.chmod(0o700)
ca = root / '.local/kafka-ca';
ca.mkdir(parents=True, exist_ok=True);
ca.chmod(0o700)
identities = ['admin', 'order_service', 'inventory_service', 'payment_service', 'fulfillment_service',
              'saga_orchestrator', 'order_query_service', 'catalog_service', 'notification_service', 'api_gateway']
file = secure / 'credentials.json'
credentials = json.loads(file.read_text()) if file.exists() else {}
for name in identities + ['store']: credentials.setdefault(name, secrets.token_hex(32))
file.write_text(json.dumps(credentials));
file.chmod(0o600)
env = os.environ.copy();
env['OMS_STORE_PASS'] = credentials['store']


def run(*args): subprocess.run(args, env=env, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)


if not (secure / 'keystore.p12').exists():
    run('openssl', 'req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days', '365', '-keyout', str(ca / 'ca.key'),
        '-out', str(ca / 'ca.crt'), '-subj', '/CN=OMS-local-CA')
    run('openssl', 'req', '-newkey', 'rsa:2048', '-nodes', '-keyout', str(secure / 'broker.key'), '-out',
        str(secure / 'broker.csr'), '-subj', '/CN=kafka')
    (secure / 'extensions').write_text(
        'subjectAltName=DNS:kafka,DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth,clientAuth\n')
    run('openssl', 'x509', '-req', '-in', str(secure / 'broker.csr'), '-CA', str(ca / 'ca.crt'), '-CAkey',
        str(ca / 'ca.key'), '-CAcreateserial', '-out', str(secure / 'broker.crt'), '-days', '365', '-extfile',
        str(secure / 'extensions'))
    run('openssl', 'pkcs12', '-export', '-in', str(secure / 'broker.crt'), '-inkey', str(secure / 'broker.key'),
        '-certfile', str(ca / 'ca.crt'), '-name', 'kafka', '-out', str(secure / 'keystore.p12'), '-passout',
        'env:OMS_STORE_PASS')
    keytool = str(Path(env['JAVA_HOME']) / 'bin/keytool') if 'JAVA_HOME' in env else 'keytool'
    run(keytool, '-importcert', '-noprompt', '-alias', 'oms-ca', '-file', str(ca / 'ca.crt'), '-keystore',
        str(secure / 'truststore.p12'), '-storetype', 'PKCS12', '-storepass:env', 'OMS_STORE_PASS')
    (secure / 'broker.key').unlink();
    (secure / 'broker.csr').unlink()
# Bind only individual files. The enclosing directory is host-private; container files must be readable by non-root runtime IDs.
for name in ['keystore.p12', 'truststore.p12']: (secure / name).chmod(0o644)
jaas = 'org.apache.kafka.common.security.plain.PlainLoginModule required'
server = f'KafkaServer {{ {jaas} username="admin" password="{credentials["admin"]}" ' + ''.join(
    f'user_{name}="{credentials[name]}" ' for name in identities) + '; };\n'
(secure / 'broker-jaas.conf').write_text(server);
(secure / 'broker-jaas.conf').chmod(0o644)
(secure / 'broker.env').write_text('\n'.join(f'{name}={credentials["store"]}' for name in
                                             ['KAFKA_SSL_KEYSTORE_PASSWORD', 'KAFKA_SSL_KEY_PASSWORD',
                                              'KAFKA_SSL_TRUSTSTORE_PASSWORD']) + '\n')
cli = secure / 'cli';
cli.mkdir(exist_ok=True);
cli.chmod(0o755)
for name in identities:
    common = {'security.protocol': 'SASL_SSL', 'sasl.mechanism': 'PLAIN',
              'sasl.jaas.config': f'{jaas} username="{name}" password="{credentials[name]}";',
              'ssl.truststore.type': 'PKCS12', 'ssl.truststore.password': credentials['store'],
              'ssl.endpoint.identification.algorithm': 'https'}
    spring = {f'spring.kafka.properties.{k}': v for k, v in common.items()};
    spring['spring.kafka.properties.ssl.truststore.location'] = '/run/oms/kafka-truststore.p12'
    host = dict(spring);
    host['spring.kafka.properties.ssl.truststore.location'] = str(secure / 'truststore.p12')
    (secure / (name + '.host.properties')).write_text('\n'.join(f'{k}={v}' for k, v in host.items()) + '\n');
    (secure / (name + '.host.properties')).chmod(0o600)
    (secure / (name + '.properties')).write_text('\n'.join(f'{k}={v}' for k, v in spring.items()) + '\n');
    (secure / (name + '.properties')).chmod(0o644)
    common.update({'ssl.truststore.location': '/security/truststore.p12', 'request.timeout.ms': '3000',
                   'default.api.timeout.ms': '5000'})
    (cli / (name + '.properties')).write_text('\n'.join(f'{k}={v}' for k, v in common.items()) + '\n');
    (cli / (name + '.properties')).chmod(0o644)
print('Configured Kafka TLS and per-service identities (credentials retained in ignored files).')
