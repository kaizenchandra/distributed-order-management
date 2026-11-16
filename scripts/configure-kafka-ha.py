#!/usr/bin/env python3
import json
import os
import secrets
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[1];
secure = root / 'docker/kafka.local';
ca = root / '.local/kafka-ca';
out = secure / 'ha';
out.mkdir(exist_ok=True);
out.chmod(0o755)
creds = json.loads((secure / 'credentials.json').read_text());
env = os.environ.copy();
env['OMS_STORE_PASS'] = creds['store']


def run(*args): subprocess.run(args, env=env, check=True, stdout=subprocess.DEVNULL, stderr=subprocess.PIPE)


for name in ['kafka-a', 'kafka-b', 'kafka-c']:
    if (out / (name + '.p12')).exists(): continue
    run('openssl', 'req', '-newkey', 'rsa:2048', '-nodes', '-keyout', str(out / (name + '.key')), '-out',
        str(out / (name + '.csr')), '-subj', '/CN=' + name)
    ext = out / (name + '.ext');
    ext.write_text(
        'subjectAltName=DNS:' + name + ',DNS:localhost,IP:127.0.0.1\nextendedKeyUsage=serverAuth,clientAuth\n')
    run('openssl', 'x509', '-req', '-in', str(out / (name + '.csr')), '-CA', str(ca / 'ca.crt'), '-CAkey',
        str(ca / 'ca.key'), '-CAcreateserial', '-out', str(out / (name + '.crt')), '-days', '365', '-extfile', str(ext))
    run('openssl', 'pkcs12', '-export', '-in', str(out / (name + '.crt')), '-inkey', str(out / (name + '.key')),
        '-certfile', str(ca / 'ca.crt'), '-name', name, '-out', str(out / (name + '.p12')), '-passout',
        'env:OMS_STORE_PASS')
    (out / (name + '.p12')).chmod(0o644);
    (out / (name + '.key')).unlink();
    (out / (name + '.csr')).unlink()
if not (out / 'cluster-id').exists(): (out / 'cluster-id').write_text(secrets.token_urlsafe(16))
print('Configured separate HA broker certificates and cluster identity.')
