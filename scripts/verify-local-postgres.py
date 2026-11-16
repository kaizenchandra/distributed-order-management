#!/usr/bin/env python3
"""Run the normal Java suite with newly-created disposable DBs on the local OMS PostgreSQL."""
import os
import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
config = dict(line.split('=', 1) for line in (root / '.env').read_text().splitlines() if '=' in line)
env = os.environ.copy();
env.update(OMS_TEST_POSTGRES_ADMIN_URL='jdbc:postgresql://localhost:15433/postgres',
           OMS_TEST_POSTGRES_ADMIN_USER='postgres', OMS_TEST_POSTGRES_ADMIN_PASSWORD=config['OMS_DB_PASSWORD'],
           OMS_TEST_ALLOW_DATABASE_CREATION='1')
env.setdefault('MAVEN_OPTS', '-Xmx384m -XX:ActiveProcessorCount=2')
sys.exit(
    subprocess.run(['mvn', '-B', 'verify', '-DargLine=-Xmx384m -XX:ActiveProcessorCount=2'] + sys.argv[1:], cwd=root,
                   env=env).returncode)
