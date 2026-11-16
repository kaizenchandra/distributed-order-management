#!/usr/bin/env python3
"""Time a real authenticated, locally buffered projection rebuild and check contiguous versions."""
import argparse
import json
import os
import sys
import time
import urllib.request
from pathlib import Path

root = Path(__file__).resolve().parents[1];
sys.path.insert(0, str(root / 'scripts'))
from smoke import token
from compose_support import run

p = argparse.ArgumentParser();
p.add_argument('--label', required=True);
a = p.parse_args()
assert a.label.replace('-', '').replace('_', '').isalnum()
out = root / 'benchmarks/results' / a.label;
out.mkdir(parents=True, exist_ok=False)


def sql(query): return run('exec', '-T', 'postgres', 'psql', '-U', 'postgres', '-d', 'order_query_service', '-At', '-c',
                           query).stdout.strip()


before = int(sql('select count(*) from projection_events'))
req = urllib.request.Request('http://localhost:8096/api/v1/admin/projections/rebuild', data=b'{}',
                             headers={'Authorization': 'Bearer ' + (os.environ.get('ADMIN_TOKEN') or token(timeout=60)),
                                      'Content-Type': 'application/json'})
started = time.monotonic()
try:
    with urllib.request.urlopen(req, timeout=180) as response:
        result = json.load(response)
    elapsed = time.monotonic() - started
    # A single database snapshot checks the live versioned materialization, including gaps.
    check = json.loads(sql("""with ranked as (select order_id,
                                                     version,
                                                     row_number() over (partition by order_id order by version) position
                                              from projection_events),
                                   expected as (select order_id, max(version) filter (where version = position) version
                                                from ranked
                                                group by order_id)
                              select json_build_object('events', (select count(*) from projection_events),
                                                       'orders', (select count(*) from order_read_models),
                                                       'version_mismatches', (select count(*)
                                                                              from expected e
                                                                                       full join order_read_models r using (order_id)
                                                                              where r.version is distinct from coalesce(e.version, 0)),
                                                       'timeline_rows', (select count(*) from order_timeline),
                                                       'expected_timeline_rows',
                                                       (select coalesce(sum(version), 0) from expected))"""))
    report = {'label': a.label, 'workload': 'local buffered projection rebuild', 'events_before': before,
              'seconds': elapsed, 'response': result, 'validation': check}
    (out / 'report.json').write_text(json.dumps(report, indent=2));
    print(json.dumps(report, indent=2))
    assert check['version_mismatches'] == 0 and check['timeline_rows'] == check['expected_timeline_rows']
except Exception as error:
    (out / 'failure.json').write_text(
        json.dumps({'seconds': time.monotonic() - started, 'error': type(error).__name__, 'events_before': before},
                   indent=2));
    raise
