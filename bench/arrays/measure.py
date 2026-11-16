#!/usr/bin/env python3
"""Interleave three baseline/new pairs, using a saved baseline target/classes tree.

Before editing Java, copy target/classes to target/array-baseline/classes after package.
Rebuild the new code, then run this script from the repository root. All processes are
sequential; each PHP script warms for 15 rounds, and the report uses round 15 medians.
"""
import os
from pathlib import Path
import statistics
import subprocess


root = Path.cwd()
baseline = root / os.environ.get('BASELINE_CLASSES', 'target/array-baseline/classes')
if not baseline.is_dir():
    raise SystemExit(f'Missing baseline classes: {baseline}')
logs = root / 'target/array-measurements'
logs.mkdir(exist_ok=True)
java_home = Path(os.environ.get('PHP_TRUFFLE_JAVA_HOME', root / '.graalvm/current'))
java = str(java_home / 'bin/java')
flags = ['--enable-native-access=ALL-UNNAMED', '-XX:-DontCompileHugeMethods',
         '--sun-misc-unsafe-memory-access=allow', '-Xms256m', '-Xmx1g',
         '-Dpolyglot.engine.WarnInterpreterOnly=false']
results = {}
loads = []
for pair in range(1, 4):
    for label, classes in [('before', baseline), ('after', root / 'target/classes')]:
        for name in ['wp-array-get', 'micro']:
            load = subprocess.check_output(['uptime'], text=True).strip()
            loads.append(f'{pair} {label} {name}: {load}')
            print(load, flush=True)
            command = [java, *flags, '-cp', f'{classes}:{root}/target/lib/*',
                       'dev.phpruntime.host.Main', str(root / f'bench/arrays/{name}.php')]
            result = subprocess.run(command, capture_output=True, text=True, check=True)
            (logs / f'{label}-{name}-{pair}.txt').write_text(result.stdout)
            (logs / f'{label}-{name}-{pair}.stderr').write_text(result.stderr)
            for line in result.stdout.splitlines():
                fields = line.split()
                if fields and fields[0] == '15':
                    _, benchmark, ms, checksum = fields
                    results.setdefault((label, benchmark), []).append(float(ms))
                    print(f'{pair} {label} {benchmark}: {ms} ms ({checksum})', flush=True)

summary = ['| Benchmark | Before ms | After ms | Speedup |',
           '|---|---:|---:|---:|']
for benchmark in ['wp_array_get', 'list_sum', 'hash_sum', 'append_million', 'copy_write']:
    before = statistics.median(results['before', benchmark])
    after = statistics.median(results['after', benchmark])
    summary.append(f'| {benchmark} | {before:.3f} | {after:.3f} | {before / after:.2f}x |')
summary.extend(['', 'Raw last-round samples:', repr(results), '', 'Loads:', *loads])
(logs / 'summary.md').write_text('\n'.join(summary) + '\n')
print('\n'.join(summary), flush=True)
