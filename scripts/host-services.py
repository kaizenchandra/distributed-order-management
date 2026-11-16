#!/usr/bin/env python3
import hashlib
import json
import os
import shutil
import signal
import subprocess
import sys
import time
import urllib.request
import uuid
from pathlib import Path

root = Path(__file__).resolve().parents[1]
state = root / '.local';
state.mkdir(exist_ok=True)
services = ['order-service', 'inventory-service', 'payment-service', 'fulfillment-service', 'saga-orchestrator',
            'order-query-service', 'catalog-service', 'notification-service', 'api-gateway', 'payment-provider']
if len(sys.argv) > 2:
    requested = set(sys.argv[2:]);
    assert requested.issubset(services);
    services = [s for s in services if s in requested]
if len(sys.argv) > 1 and sys.argv[1] == 'stop':
    for name in services:
        file = state / (name + '.pid')
        if file.exists():
            pid = int(file.read_text());
            info = subprocess.run(['ps', '-p', str(pid), '-o', 'command='], capture_output=True, text=True).stdout
            if str(root) in info and name in info:
                os.kill(pid, signal.SIGTERM)
                for _ in range(100):
                    try:
                        os.kill(pid, 0)
                    except ProcessLookupError:
                        break
                    time.sleep(0.1)
            file.unlink()
    sys.exit()
config = dict(line.split('=', 1) for line in (root / '.env').read_text().splitlines() if '=' in line)
ports = {name: 8091 + i for i, name in enumerate(
    ['order-service', 'inventory-service', 'payment-service', 'fulfillment-service', 'saga-orchestrator',
     'order-query-service', 'catalog-service', 'notification-service', 'api-gateway'])}
ports['payment-provider'] = 8100
for name in services:
    pidfile = state / (name + '.pid')
    if pidfile.exists():
        info = subprocess.run(['ps', '-p', pidfile.read_text().strip(), '-o', 'command='], capture_output=True,
                              text=True).stdout
        if str(root) in info and name in info: continue
        pidfile.unlink()
    env = os.environ.copy();
    env.update(DB_URL='jdbc:postgresql://localhost:15433/' + name.replace('-', '_'), DB_USER=name.replace('-', '_'),
               DB_PASSWORD=config['OMS_DB_PASSWORD'],
               KAFKA_BOOTSTRAP=os.environ.get('OMS_HOST_KAFKA_BOOTSTRAP', 'localhost:19092'),
               JWT_ISSUER='http://localhost:8180/realms/oms',
               JWT_JWKS='http://localhost:8180/realms/oms/protocol/openid-connect/certs', PORT=str(ports[name]),
               CATALOG_URL='http://localhost:8097', ORDER_SERVICE_URL='http://localhost:8091',
               ORDER_QUERY_SERVICE_URL='http://localhost:8096', CATALOG_SERVICE_URL='http://localhost:8097')
    env['PROVIDER_KEY'] = config['PROVIDER_KEY'];
    env['PROVIDER_URL'] = 'http://localhost:8100'
    if name != 'payment-provider': env['SPRING_CONFIG_IMPORT'] = 'file:' + str(
        root / 'docker/kafka.local' / (name.replace('-', '_') + '.host.properties'))
    if name == 'notification-service': env['PORT'] = '18098'
    profile = os.environ.get('OMS_HOST_PROFILE', 'baseline')
    if profile == 'tuned':
        env['SPRING_PROFILES_ACTIVE'] = 'tuned'
    else:
        env.pop('SPRING_PROFILES_ACTIVE', None)
    cfg = {'spring': {'datasource': {'hikari': {'minimum-idle': 0, 'maximum-pool-size': 4, 'idle-timeout': 10000}},
                      'task': {'scheduling': {'pool': {'size': 2 if profile == 'tuned' else 1}}}, 'kafka': {
            'producer': {'properties': {'request.timeout.ms': 10000, 'delivery.timeout.ms': 30000}}}},
           'oms': {'relay-delay': (100 if profile == 'tuned' else 250),
                   'provider': {'poll-ms': 25 if profile == 'tuned' else 250, 'timeout-ms': 1500}}}
    env['SPRING_APPLICATION_JSON'] = json.dumps(cfg)
    jar = Path(os.environ['OMS_JAR_DIR']) / (name + '.jar') if os.environ.get(
        'OMS_JAR_DIR') else root / 'services' / name / 'target' / (name + '-1.0.0-SNAPSHOT.jar')
    snapshot = state / (name + '-' + str(uuid.uuid4()) + '.jar');
    shutil.copyfile(jar, snapshot)
    with (state / (name + '.log')).open('a') as log:
        child = subprocess.Popen(
            [str(Path(env['JAVA_HOME']) / 'bin/java'), '-Xms24m', '-Xmx128m', '-Xss256k', '-XX:ActiveProcessorCount=1',
             '-jar', str(snapshot)], env=env, stdout=log, stderr=log, start_new_session=True)
    (state / (name + '.pid')).write_text(str(child.pid))
    (state / (name + '.launch.json')).write_text(json.dumps(
        {'service': name, 'profile': profile, 'sha256': hashlib.sha256(snapshot.read_bytes()).hexdigest(),
         'snapshot': str(snapshot), 'pid': child.pid}))
    print(name, child.pid)
    if os.environ.get('OMS_HOST_WAIT', '1') == '1':
        deadline = time.monotonic() + 180
        while time.monotonic() < deadline:
            if child.poll() is not None: raise RuntimeError(
                name + ' exited during startup; inspect .local/' + name + '.log')
            try:
                with urllib.request.urlopen('http://localhost:' + env['PORT'] + '/actuator/health/readiness',
                                            timeout=3) as response:
                    if response.status == 200: break
            except (OSError, TimeoutError):
                pass
            time.sleep(1)
        else:
            raise RuntimeError(name + ' did not become ready within 180 seconds')
