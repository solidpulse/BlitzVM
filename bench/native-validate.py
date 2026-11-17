#!/usr/bin/env python3
"""Run step-0 native bytecode correctness/compilation gates; never benchmark AST."""
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess
import sys

root = Path('/home/silva/Documents/java/php-runtime-native')
run = root / 'target/18b/compiled-correctness'
run.mkdir(parents=True, exist_ok=True)
binary = root / 'target/blitzvm'
identity = {'bytes': binary.stat().st_size, 'sha256': hashlib.sha256(binary.read_bytes()).hexdigest()}
(run / 'binary.json').write_text(json.dumps(identity, indent=2) + '\n')
common = [str(binary), '-Dblitz.bytecode=auto', '-Dpolyglot.engine.AllowExperimentalOptions=true',
          '-Dpolyglot.engine.TraceCompilation=true', '-Dpolyglot.engine.TraceCompilationDetails=true',
          '-Dpolyglot.engine.MultiTier=false', '-Dpolyglot.engine.SingleTierCompilationThreshold=10',
          '-Dpolyglot.engine.BackgroundCompilation=false', '-Dpolyglot.engine.CompilationFailureAction=Print']

def cli(label, args, expected, name):
    trace = run / (label + '.trace')
    p = subprocess.run([*common, '-Dpolyglot.log.file=' + str(trace), *args], cwd=root,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=180)
    (run / (label + '.out')).write_bytes(p.stdout)
    (run / (label + '.err')).write_bytes(p.stderr)
    text = trace.read_text(errors='replace') if trace.exists() else p.stderr.decode(errors='replace')
    done = [line for line in text.splitlines() if 'opt done' in line and name in line]
    print(label, 'exit=', p.returncode, 'output=', p.stdout, 'successful compilations=', len(done), flush=True)
    if p.returncode or p.stdout != expected or not done:
        raise RuntimeError('Compilation/correctness gate failed: ' + label)

cli('simple-f', ['-r', 'function f($x) { return $x+1; } for($i=0;$i<100;$i=$i+1) f($i); echo "done\\n";'],
    b'done\n', 'f()')
cli('hot-paths', ['/tmp/opencode/18b-jit-correctness.php'], b'610:xab:a-b\n', 'hot_paths')

spec = importlib.util.spec_from_file_location('native_benchmark', root / 'bench/native-image.py')
bench = importlib.util.module_from_spec(spec)
spec.loader.exec_module(bench)
docroot = Path('/home/silva/Documents/java/blitz-wp/wordpress')
pages = {}
for config in ('jvm-bc', 'native-bc'):
    with bench.server(config, docroot, run, 'pages-' + config, 18088):
        pages[config] = []
        for index, path in enumerate(bench.PAGES):
            result = bench.get(18088, path)
            pages[config].append(result)
            (run / f'page-{config}-{index}.body').write_bytes(result[1])
            equal = config == 'jvm-bc' or result == pages['jvm-bc'][index]
            print(config, path, 'HTTP', result[0], 'bytes', len(result[1]), 'equal', equal, flush=True)
summary = {config: [{'path': path, 'status': result[0], 'bytes': len(result[1]),
                    'sha256': hashlib.sha256(result[1]).hexdigest(), 'equal': result == pages['jvm-bc'][i]}
                   for i, (path, result) in enumerate(zip(bench.PAGES, values))]
           for config, values in pages.items()}
(run / 'pages.json').write_text(json.dumps(summary, indent=2) + '\n')
if pages['jvm-bc'] != pages['native-bc']:
    raise RuntimeError('Native/JVM bytecode page equality failed')

failures = []
scripts = sorted((root / 'src/test/resources/php').glob('*.php'))
for script in scripts:
    p = subprocess.run([str(binary), '-Dblitz.bytecode=auto', str(script.relative_to(root))], cwd=root,
                       stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=120)
    if p.returncode or p.stdout != script.with_suffix('.out').read_bytes():
        failures.append({'script': script.name, 'exit': p.returncode, 'stderr': p.stderr.decode(errors='replace')})
        (run / ('fixture-' + script.stem + '.actual')).write_bytes(p.stdout)
(run / 'fixtures.json').write_text(json.dumps({'total': len(scripts), 'failures': failures}, indent=2) + '\n')
print('Native bytecode fixtures:', len(scripts) - len(failures), '/', len(scripts), flush=True)
if failures:
    raise RuntimeError('Native bytecode fixtures failed')
