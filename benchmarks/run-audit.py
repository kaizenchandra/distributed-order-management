#!/usr/bin/env python3
"""Read-only, post-drain cross-service audit of a labelled local load cohort."""
import argparse
import json
import os
import subprocess
import sys
import zipfile
from pathlib import Path

root = Path(__file__).resolve().parents[1]
p = argparse.ArgumentParser();
p.add_argument('--label', required=True);
a = p.parse_args()
assert a.label.replace('-', '').replace('_', '').isalnum()
archive = root / 'services/payment-service/target/payment-service-1.0.0-SNAPSHOT.jar'
driver = root / '.local/postgresql-audit.jar';
driver.parent.mkdir(exist_ok=True)
with zipfile.ZipFile(archive) as jar:
    matches = [name for name in jar.namelist() if
               name.startswith('BOOT-INF/lib/postgresql-') and name.endswith('.jar')];
    assert len(matches) == 1
    driver.write_bytes(jar.read(matches[0]))
java = str(Path(os.environ['JAVA_HOME']) / 'bin/java') if os.environ.get('JAVA_HOME') else 'java'
result = subprocess.run([java, '--class-path', str(driver), str(root / 'benchmarks/FinancialAudit.java'), a.label],
                        cwd=root, capture_output=True, text=True, check=True)
report = json.loads(result.stdout);
out = root / 'benchmarks/results' / a.label;
out.mkdir(parents=True, exist_ok=True);
(out / 'financial-audit.json').write_text(json.dumps(report, indent=2));
print(json.dumps(report, indent=2))
