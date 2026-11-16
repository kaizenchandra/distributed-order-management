#!/usr/bin/env python3
"""Sequential query/lifecycle measurements, sharing short-lived tokens only in process memory."""
import argparse
import json
import os
import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1];
sys.path.insert(0, str(root / 'scripts'))
from smoke import token, request, eventually

p = argparse.ArgumentParser();
p.add_argument('--label', required=True);
p.add_argument('--rate', type=int, default=10);
p.add_argument('--duration', default='20s');
a = p.parse_args();
assert a.label.replace('-', '').replace('_', '').isalnum()
folder = root / 'benchmarks/results' / (a.label + '-campaign');
folder.mkdir(parents=True, exist_ok=False)
print('Acquiring campaign tokens outside the timing windows', flush=True)
env = os.environ.copy();
phase = 'OIDC preflight'
try:
    for port in [8091, 8092, 8093, 8094, 8095, 8096, 8097, 8099, 8100]:
        phase = 'readiness:' + str(port);
        print('Checking ' + phase, flush=True)
        eventually(lambda: request(port, '/actuator/health/readiness')[0] == 200, 180)
    phase = 'OIDC preflight';
    env['ADMIN_TOKEN'] = token(timeout=60);
    env['CUSTOMER_TOKEN'] = token('customer', timeout=60)
except Exception as failure:
    (folder / 'failure.json').write_text(
        json.dumps({'phase': phase, 'error': type(failure).__name__, 'load_executed': False}));
    raise
results = []
for script, suffix, extra in [('run-query.py', 'query', ['--rate', '100', '--duration', '10s']),
                              ('run.py', 'checkout', ['--rate', str(a.rate), '--duration', a.duration])]:
    print('Running ' + suffix, flush=True)
    with (folder / (suffix + '.log')).open('w') as log:
        result = subprocess.run(
            [sys.executable, str(root / 'benchmarks' / script), '--label', a.label + '-' + suffix] + extra, env=env,
            cwd=root, stdout=log, stderr=log)
    results.append({'workload': suffix, 'exit': result.returncode});
    print(suffix + ' exit ' + str(result.returncode), flush=True)
(folder / 'campaign.json').write_text(json.dumps(results, indent=2));
sys.exit(1 if any(r['exit'] for r in results) else 0)
