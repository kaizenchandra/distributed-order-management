#!/usr/bin/env python3
"""Refuse container startup that would collide with this project's own native JVMs."""
import subprocess
import sys
from pathlib import Path

root = Path(__file__).resolve().parents[1]
active = []
for file in (root / '.local').glob('*.pid'):
    command = subprocess.run(['ps', '-p', file.read_text().strip(), '-o', 'command='], capture_output=True,
                             text=True).stdout
    if str(root) in command and file.stem in command: active.append(file.stem)
if active:
    sys.exit('Stop native OMS JVMs first: python3 scripts/host-services.py stop ' + ' '.join(active))
