import re
import statistics
import subprocess
import sys
import tempfile
from pathlib import Path

root = Path(__file__).resolve().parent
variants = {
    'Java TreeMap (naive)': ('naive', root / 'naive/Main.java', root / 'CountryStat.java'),
    'Java HashMap + sort (naive2)': ('naive2', root / 'naive2/Main.java', root / 'naive2/CountryStat.java'),
}
java_options = ['java', '-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
                '-Dstderr.encoding=UTF-8']

# Compile into a temporary directory so benchmarking leaves no class files in the repo.
with tempfile.TemporaryDirectory(prefix='java-benchmark-') as temp:
    commands = {}
    for name, (directory, main_source, stat_source) in variants.items():
        classes = Path(temp) / directory
        subprocess.run(
            ['javac', '-encoding', 'UTF-8', '-d', str(classes),
             str(main_source), str(stat_source)],
            cwd=root, check=True,
        )
        commands[name] = java_options + ['-cp', str(classes), 'Main']

    # Keep test.py's timer and computation unchanged; route its final timing line
    # to stderr so its result rows can go to the null device during benchmarking.
    python_source = (root / 'test.py').read_text(encoding='utf-8')
    timing_line = 'print("Time taken:", end - start, "seconds")'
    replacement = 'print("Time taken:", end - start, "seconds", file=sys.stderr)'
    if timing_line not in python_source:
        raise RuntimeError('Could not find test.py timing output to redirect')
    python_source = python_source.replace(timing_line, replacement, 1)
    commands['Python test.py'] = [sys.executable, '-c', python_source]

    def timed_run(command):
        result = subprocess.run(command, cwd=root, stdout=subprocess.DEVNULL,
                                stderr=subprocess.PIPE, check=True)
        match = re.search(rb'Time taken:\s*([0-9.]+)\s*seconds', result.stderr)
        if not match:
            raise RuntimeError('Could not parse the implementation timing')
        return float(match.group(1))

    for command in commands.values():
        timed_run(command)  # warm-up

    samples = {name: [] for name in commands}
    names = list(commands)
    for iteration in range(11):
        for offset in range(len(names)):
            name = names[(iteration + offset) % len(names)]
            samples[name].append(timed_run(commands[name]))

    for name, values in samples.items():
        print(f'{name}: median={statistics.median(values):.6f}s; runs=' +
              ', '.join(f'{value:.6f}' for value in values))
