#!/usr/bin/env python3
import argparse
import json
import os
import subprocess
import sys
import time
from pathlib import Path

root = Path(__file__).resolve().parents[1];
sys.path.insert(0, str(root / 'scripts'))
from smoke import token, request

p = argparse.ArgumentParser();
p.add_argument('--label', required=True);
p.add_argument('--rate', type=int, default=100);
p.add_argument('--duration', default='10s');
a = p.parse_args();
assert a.label.replace('-', '').replace('_', '').isalnum()
out = root / 'benchmarks/results' / a.label;
out.mkdir(parents=True, exist_ok=False);
customer = os.environ.get('CUSTOMER_TOKEN') or token('customer', timeout=60);
code, rows = request(8096, '/api/v1/query/customers/me/orders', customer);
assert code == 200 and rows
ids = [row['order_id'] for row in rows];
env = os.environ.copy();
env.update(CUSTOMER_TOKEN=customer, ORDER_IDS=json.dumps(ids), RATE=str(a.rate), DURATION=a.duration)
start = time.monotonic()
with (out / 'k6.log').open('w') as log: r = subprocess.run(
    ['k6', 'run', '--quiet', '--summary-export', str(out / 'summary.json'), str(root / 'benchmarks/query.js')], env=env,
    stdout=log, stderr=log)
metrics = json.loads((out / 'summary.json').read_text())['metrics'];
duration = time.monotonic() - start
report = {'workload': 'authenticated CQRS point reads over owned-order working set', 'working_set': len(ids),
          'offered_rate': a.rate, 'duration': a.duration, 'wall_seconds': duration, 'metrics': metrics,
          'exit': r.returncode};
(out / 'report.json').write_text(json.dumps(report, indent=2));
print(json.dumps(report, indent=2));
sys.exit(r.returncode)
