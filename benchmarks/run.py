#!/usr/bin/env python3
"""Open-loop k6 lifecycle workload; captures real latency, terminal throughput, and DB/resource snapshots."""
import argparse
import datetime
import json
import os
import subprocess
import sys
import threading
import time
import urllib.request
from pathlib import Path

root = Path(__file__).resolve().parents[1];
sys.path.insert(0, str(root / 'scripts'))
from smoke import token
from compose_support import run

parser = argparse.ArgumentParser();
parser.add_argument('--rate', type=int, default=10);
parser.add_argument('--duration', default='20s');
parser.add_argument('--label', required=True);
args = parser.parse_args()
assert args.label.replace('-', '').replace('_', '').isalnum()
output = root / 'benchmarks/results' / args.label;
output.mkdir(parents=True, exist_ok=False)
environment = run('images', '--format', 'json').stdout
(output / 'images.json').write_text(environment)
hardware = subprocess.run(
    ['docker', 'info', '--format', '{"cpus":{{.NCPU}},"memory_bytes":{{.MemTotal}},"docker":"{{.ServerVersion}}"}'],
    capture_output=True, text=True, check=True).stdout
(output / 'hardware.json').write_text(hardware)
if os.environ.get('OMS_DEPLOYMENT') == 'host':
    manifest = [json.loads(p.read_text()) for p in (root / '.local').glob('*.launch.json')]
    (output / 'host-launches.json').write_text(json.dumps(manifest, indent=2))
env = os.environ.copy();
env.update(CUSTOMER_TOKEN=env.get('CUSTOMER_TOKEN') or token('customer', timeout=60),
           ADMIN_TOKEN=env.get('ADMIN_TOKEN') or token(timeout=60), RATE=str(args.rate), DURATION=args.duration,
           RUN_ID=args.label)
stop = threading.Event();
samples = []


def prometheus(port):
    req = urllib.request.Request('http://localhost:' + str(port) + '/actuator/prometheus',
                                 headers={'Authorization': 'Bearer ' + env['ADMIN_TOKEN']})
    with urllib.request.urlopen(req, timeout=20) as response: return response.read().decode()


for port in [8091, 8092, 8093, 8095, 8096]: (output / ('before-' + str(port) + '.prom')).write_text(prometheus(port))


def resources():
    while not stop.is_set():
        try:
            result = run('stats', '--no-stream', '--format', '{{json .}}', timeout=15)
            queue = run('exec', '-T', 'postgres', 'psql', '-U', 'postgres', '-d', 'payment_service', '-At', '-c',
                        "select json_build_object('pending',count(*) filter(where status='PENDING'),'submitting',count(*) filter(where status='SUBMITTING'),'unknown',count(*) filter(where status='UNKNOWN'),'manual',count(*) filter(where status='MANUAL_REVIEW')) from provider_operations").stdout.strip()
            vm = run('exec', '-T', 'postgres', 'sh', '-c',
                     "grep -E 'MemAvailable:|SwapFree:' /proc/meminfo; grep -E 'pswpin |pswpout ' /proc/vmstat",
                     timeout=10).stdout
            hosts = []
            if os.environ.get('OMS_DEPLOYMENT') == 'host':
                for file in (root / '.local').glob('*.pid'):
                    value = subprocess.run(['ps', '-p', file.read_text().strip(), '-o', 'rss=,pcpu='],
                                           capture_output=True, text=True).stdout.strip()
                    hosts.append({'service': file.stem, 'rss_kib_and_cpu_percent': value})
            samples.append({'host_processes': hosts, 'vm': vm, 'at': time.time(),
                            'containers': [json.loads(line) for line in result.stdout.splitlines() if line.strip()],
                            'payment_queue': json.loads(queue)})
        except Exception as error:
            samples.append({'error': type(error).__name__})
        stop.wait(5)


thread = threading.Thread(target=resources, daemon=True);
thread.start();
begin = time.monotonic()
with (output / 'k6.log').open('w') as log:
    result = subprocess.run(['k6', 'run', '--quiet', '--summary-export', str(output / 'summary.json'),
                             str(root / 'benchmarks/lifecycle.js')], cwd=root, env=env, stdout=log, stderr=log)
wall = time.monotonic() - begin;
stop.set();
thread.join(20);
(output / 'resources.json').write_text(json.dumps(samples, indent=2))
summary = json.loads((output / 'summary.json').read_text());
metrics = summary['metrics']


def metric(name, key='count'): return metrics.get(name, {}).get(key,
                                                                metrics.get(name, {}).get('values', {}).get(key, 0))


report = {'label': args.label, 'offered_rate': args.rate, 'submission_duration': args.duration,
          'wall_seconds': round(wall, 3), 'offered': metric('oms_offered'), 'accepted': metric('oms_accepted'),
          'completed': metric('oms_completed'), 'failed': metric('oms_failed'), 'timeouts': metric('oms_timeouts'),
          'dropped_iterations': metric('dropped_iterations'), 'poll_errors': metric('oms_poll_errors'),
          'terminal_tps_including_drain': round(metric('oms_completed') / wall, 3),
          'command_ms': metrics.get('oms_command_ms'), 'saga_ms': metrics.get('oms_saga_ms'),
          'k6_exit': result.returncode, 'entrypoint': env.get('ORDER_URL', 'http://localhost:8091'),
          'timestamp': datetime.datetime.now(datetime.timezone.utc).isoformat()}
report['deployment'] = os.environ.get('OMS_DEPLOYMENT', 'docker')
report['preallocated_vus'] = int(env.get('VUS', '300'));
report['max_vus'] = int(env.get('MAX_VUS', '500'))
report['accounting_gap'] = report['offered'] - report['completed'] - report['failed'] - report['timeouts']
(output / 'report.json').write_text(json.dumps(report, indent=2))
for db, sql in [('payment_service', "select status,count(*) from provider_operations group by status"),
                ('saga_orchestrator', "select state,count(*) from saga_instances group by state"),
                ('inventory_service', "select sku,on_hand,reserved,allocated from inventory_items")]:
    snapshot = run('exec', '-T', 'postgres', 'psql', '-U', 'postgres', '-d', db, '-At', '-c', sql).stdout;
    (output / (db + '.txt')).write_text(snapshot)
ids = run('exec', '-T', 'postgres', 'psql', '-U', 'postgres', '-d', 'order_service', '-At', '-c',
          "select order_id from order_idempotency where key like '" + args.label + "-%'").stdout.splitlines()
if ids:
    import uuid

    selected = ','.join("'" + str(uuid.UUID(value)) + "'" for value in ids if value.strip())
    sql = "copy (with spans as (select command_id,min(action) action,extract(epoch from max(recorded_at)-min(recorded_at))*1000 ms from saga_steps where saga_id in (" + selected + ") group by command_id having count(*) filter(where outcome='SUCCEEDED')>0) select action,count(*),avg(ms)::numeric(14,2) mean_ms,percentile_cont(0.95) within group(order by ms) p95_ms from spans group by action order by action) to stdout with csv header"
    (output / 'stage-spans.csv').write_text(
        run('exec', '-T', 'postgres', 'psql', '-U', 'postgres', '-d', 'saga_orchestrator', '-c', sql).stdout)
    sql = "copy (select kind,count(*),avg(extract(epoch from completed_at-created_at)*1000)::numeric(14,2) mean_ms,percentile_cont(0.95) within group(order by extract(epoch from completed_at-created_at)*1000) p95_ms,max(attempts) attempts from provider_operations where payment_id in (" + selected + ") and status='SUCCEEDED' group by kind) to stdout with csv header"
    (output / 'provider-dwell.csv').write_text(
        run('exec', '-T', 'postgres', 'psql', '-U', 'postgres', '-d', 'payment_service', '-c', sql).stdout)
    for database in ['order_service', 'saga_orchestrator', 'inventory_service', 'payment_service',
                     'fulfillment_service']:
        sql = "copy (select count(*),avg(extract(epoch from published_at-created_at)*1000)::numeric(14,2) mean_ms,percentile_cont(0.95) within group(order by extract(epoch from published_at-created_at)*1000) p95_ms from outbox where aggregate_id in (" + selected + ")) to stdout with csv header"
        (output / (database + '-outbox.csv')).write_text(
            run('exec', '-T', 'postgres', 'psql', '-U', 'postgres', '-d', database, '-c', sql).stdout)
for port in [8091, 8092, 8093, 8095, 8096]: (output / ('after-' + str(port) + '.prom')).write_text(prometheus(port))
print(json.dumps(report, indent=2));
sys.exit(result.returncode or (2 if report['accounting_gap'] else 0))
