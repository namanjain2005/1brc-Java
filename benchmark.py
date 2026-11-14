"""Benchmark every implementation found in a source tree.

Works in the main tree or inside a variant clone made by compare.sh: it
auto-detects which implementation directories exist, compiles them together
with the shared util classes, runs each one one warm-up plus --runs measured
times on the same dataset, and reports the median plus a checksum of the
result rows so correctness regressions are visible.

Usage: python3 benchmark.py [--runs N] [--tree PATH] [dataset]
"""

import argparse
import hashlib
import os
import re
import statistics
import subprocess
import sys
import tempfile
from pathlib import Path

JAVA_IMPLS = {
    'naive': 'naive.Main',
    'naive2': 'naive2.Main',
    'parallel': 'parallel.Main',
    'divided_workers': 'divided_workers.Main',
}
JAVA_OPTIONS = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
                '-Dstderr.encoding=UTF-8']
# Needed to compile and run the SIMD scanning in divided_workers; harmless
# for the baselines.
VECTOR_FLAGS = ['--add-modules', 'jdk.incubator.vector']
TIMING_RE = re.compile(r'Time taken:\s*([0-9.]+)\s*seconds')


def detect(tree: Path):
    """Return {implementation key: command builder info} for a source tree."""
    impls = {}
    util_sources = sorted((tree / 'util').glob('*.java'))
    for name, main_class in JAVA_IMPLS.items():
        if (tree / name / 'Main.java').is_file():
            impls['java-' + name] = {
                'kind': 'java',
                'sources': util_sources + sorted((tree / name).glob('*.java')),
                'main_class': main_class,
            }
    if (tree / 'test.py').is_file():
        impls['python'] = {'kind': 'python'}
    if not impls:
        raise RuntimeError(f'No implementation found in {tree}')
    return impls


def timed_run(command, cwd, dataset: Path, env=None):
    """Run once; return (seconds, sha256 of stdout)."""
    result = subprocess.run(command, cwd=str(cwd), stdout=subprocess.PIPE,
                            stderr=subprocess.PIPE, check=True, env=env)
    match = TIMING_RE.search(result.stderr.decode('utf-8', 'replace'))
    if not match:
        raise RuntimeError(f'No timing output from {command}')
    seconds = float(match.group(1))
    checksum = hashlib.sha256(result.stdout).hexdigest()
    return seconds, checksum


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runs', type=int, default=11)
    parser.add_argument('--tree', type=Path, default=Path(__file__).resolve().parent)
    parser.add_argument('dataset', nargs='?', type=Path, default=None,
                        help='input file, default: <tree>/weather_stations.csv')
    args = parser.parse_args()
    tree = args.tree.resolve()
    dataset = (args.dataset or tree / 'weather_stations.csv').resolve()

    impls = detect(tree)
    commands = {}

    with tempfile.TemporaryDirectory(prefix='1brc-benchmark-') as temp:
        temp_path = Path(temp)
        for key, info in impls.items():
            if info['kind'] == 'java':
                classes = temp_path / key
                classes.mkdir()
                subprocess.run(
                    ['javac', *VECTOR_FLAGS, '-encoding', 'UTF-8', '-d', str(classes),
                     *(str(source) for source in info['sources'])],
                    cwd=str(tree), check=True)
                commands[key] = (['java'] + VECTOR_FLAGS + JAVA_OPTIONS +
                                                 ['-cp', str(classes),
                                                 info['main_class'],
                                                 str(dataset)], tree, None)
            else:
                # test.py reads weather_stations.csv relative to its working
                # directory, so run it from a directory holding a symlink and
                # import temperature.py from the source tree.
                py_cwd = temp_path / 'python'
                py_cwd.mkdir()
                (py_cwd / 'weather_stations.csv').symlink_to(dataset)
                source = (tree / 'test.py').read_text(encoding='utf-8')
                timing_line = 'print("Time taken:", end - start, "seconds")'
                replacement = ('print("Time taken:", end - start, "seconds", '
                               'file=sys.stderr)')
                if timing_line not in source:
                    raise RuntimeError('Could not find test.py timing output')
                source = source.replace(timing_line, replacement, 1)
                env = dict(os.environ, PYTHONPATH=str(tree))
                commands[key] = ([sys.executable, '-c', source], py_cwd, env)

        for key in commands:  # warm-up, also checks checksums
            command, cwd, env = commands[key]
            timed_run(command, cwd, dataset, env)

        samples = {key: [] for key in commands}
        checksums = {}
        names = list(commands)
        for iteration in range(args.runs):
            for offset in range(len(names)):
                key = names[(iteration + offset) % len(names)]
                command, cwd, env = commands[key]
                seconds, checksum = timed_run(command, cwd, dataset, env)
                samples[key].append(seconds)
                checksums.setdefault(key, checksum)
                if checksums[key] != checksum:
                    raise RuntimeError(f'{key} produced different output')

        for key, values in samples.items():
            checksum = checksums[key]
            print(f'{key}: median={statistics.median(values):.6f}s '
                  f'checksum={checksum} runs=' +
                  ', '.join(f'{value:.6f}' for value in values))
            print(f'RESULT,{key},{statistics.median(values):.6f},{checksum}')


if __name__ == '__main__':
    main()
