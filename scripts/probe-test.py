#!/usr/bin/env python3
"""Check the configured lightweight HTTP probe against the live provider, including non-200 rejection."""
import json
import subprocess
import time
from pathlib import Path

root = Path(__file__).resolve().parents[1]
probe = (root / 'docker/healthcheck.sh').read_text()
results = []
for name, script, expected in [('ready', probe, 0), ('missing-endpoint', probe.replace('/actuator/health/readiness',
                                                                                       '/actuator/health/not-a-readiness-group'),
                                                     1)]:
    start = time.monotonic();
    r = subprocess.run(['docker', 'exec', 'oms-payment-provider-1', 'bash', '-c', script], capture_output=True,
                       text=True, timeout=15)
    assert r.returncode == expected, (name, r.returncode, r.stderr)
    results.append({'check': name, 'exit': r.returncode, 'seconds': round(time.monotonic() - start, 3)})
print(json.dumps({'checks': results, 'status': 'PASS'}, indent=2))
