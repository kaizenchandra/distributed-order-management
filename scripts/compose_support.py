import os
import subprocess
from pathlib import Path

root = Path(__file__).resolve().parents[1]


def command():
    config = os.environ.get('OMS_COMPOSE_FILE', 'compose.yml')
    if config in ('compose.ha.yml', 'compose.tuned.yml'): os.environ['OMS_KAFKA_HA_CLUSTER_ID'] = (
            root / 'docker/kafka.local/ha/cluster-id').read_text().strip()
    args = ['docker', 'compose', '-f', config]
    if os.environ.get('OMS_COMPOSE_EXTRA'): args += ['-f', os.environ['OMS_COMPOSE_EXTRA']]
    return args


def run(*args, check=True, timeout=180, input=None):
    result = subprocess.run(command() + list(args), cwd=root, input=input, capture_output=True, text=True,
                            timeout=timeout)
    if check and result.returncode: raise AssertionError((args, result.stderr[-2000:]))
    return result
