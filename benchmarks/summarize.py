#!/usr/bin/env python3
import csv
import json
from pathlib import Path

root = Path(__file__).resolve().parents[1]


def percentile(value): return value.get('p(95)', value.get('values', {}).get('p(95)', 0)) if value else 0


rows = []
for file in (root / 'benchmarks/results').glob('*/report.json'):
    r = json.loads(file.read_text())
    if 'offered_rate' not in r or 'completed' not in r: continue
    rows.append({'run': r['label'], 'rate': r['offered_rate'], 'offered': r['offered'], 'accepted': r['accepted'],
                 'completed': r['completed'], 'failed': r['failed'], 'timeouts': r['timeouts'],
                 'dropped': r['dropped_iterations'], 'command_p95_ms': round(percentile(r['command_ms']), 2),
                 'saga_p95_s': round(percentile(r['saga_ms']) / 1000, 3),
                 'terminal_tps_including_drain': r['terminal_tps_including_drain'], 'poll_errors': r['poll_errors'],
                 'exit': r['k6_exit']})
rows.sort(key=lambda r: r['run'])
if rows:
    with (root / 'benchmarks/results/comparison.csv').open('w') as f:
        writer = csv.DictWriter(f, fieldnames=list(rows[0]));
        writer.writeheader();
        writer.writerows(rows)
print(json.dumps(rows, indent=2))
