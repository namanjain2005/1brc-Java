import contextlib
import cProfile
import hashlib
import html
import io
import os
import pstats
import re
import subprocess
import sys
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parent
OUTPUT = ROOT / 'profile-output'
JAVA_OPTIONS = ['-Dfile.encoding=UTF-8', '-Dstdout.encoding=UTF-8',
                '-Dstderr.encoding=UTF-8']
PROFILE_RUNS = 15
SVG_WIDTH = 1400
BAR_HEIGHT = 24


def run_java_profile(label, source_package, work_dir):
    classes = work_dir / label / 'classes'
    classes.mkdir(parents=True)
    main_source = ROOT / source_package / 'Main.java'
    sources = [main_source, ROOT / 'util/Temperature.java']
    if source_package != 'parallel':
        sources.append(ROOT / 'util/CountryStat.java')
    subprocess.run(
        ['javac', '-encoding', 'UTF-8', '-d', str(classes),
         *(str(source) for source in sources)],
        cwd=ROOT, check=True,
    )
    runner = work_dir / label / 'profiling' / 'ProfileRunner.java'
    runner.parent.mkdir(parents=True)
    class_checks = f'    Class.forName("{source_package}.Main");\n'
    if source_package != 'parallel':
        class_checks += '    Class.forName("util.CountryStat");\n'
    runner_source = (
        'package profiling;\n'
        f'import {source_package}.Main;\n'
        'import jdk.jfr.Configuration;\n'
        'import jdk.jfr.Recording;\n'
        'import java.nio.file.Path;\n'
        'public class ProfileRunner {\n'
        '  public static void main(String[] args) throws Exception {\n'
        f'{class_checks}'
        '    Main.main(new String[0]); // warm-up outside the recording\n'
        '    try (Recording recording = new Recording(Configuration.getConfiguration("profile"))) {\n'
        '      recording.start();\n'
        f'      for (int i = 0; i < {PROFILE_RUNS}; i++) Main.main(new String[0]);\n'
        '      recording.stop();\n'
        '      recording.dump(Path.of(args[0]));\n'
        '    }\n'
        '  }\n'
        '}\n',
    )
    runner.write_text(
        runner_source,
        encoding='ascii',
    )
    subprocess.run(
        ['javac', '-encoding', 'UTF-8', '-cp', str(classes), '-d', str(classes),
         str(runner)],
        cwd=ROOT, check=True,
    )
    recording = work_dir / f'{label}.jfr'
    subprocess.run(
        ['java', *JAVA_OPTIONS, '-cp', str(classes), 'profiling.ProfileRunner', str(recording)],
        cwd=ROOT, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True,
    )
    return recording


def jfr_hot_methods(recording):
    result = subprocess.run(
        ['jfr', 'view', '--width', '150', 'hot-methods', str(recording)],
        text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True,
    )
    return result.stdout


def jfr_stacks(recording):
    result = subprocess.run(
        ['jfr', 'print', '--events', 'jdk.ExecutionSample', '--stack-depth', '16',
         str(recording)],
        text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, check=True,
    )
    stacks = []
    for block in result.stdout.split('jdk.ExecutionSample {')[1:]:
        lines = block.splitlines()
        start = next((i for i, line in enumerate(lines)
                      if line.strip().startswith('stackTrace = [')), None)
        if start is None:
            continue
        frames = []
        for line in lines[start + 1:]:
            frame = line.strip()
            if frame == ']':
                break
            if frame and '(' in frame and ')' in frame:
                frames.append(frame.split(' line: ')[0])
        main_index = next((i for i, frame in enumerate(frames)
                           if frame.startswith('Main.main(') or '.Main.main(' in frame), None)
        if main_index is None:
            continue
        # JFR lists the active leaf first. Reverse through Main.main to make
        # root-to-leaf paths, which are the input format for a flame graph.
        stack = list(reversed(frames[:main_index + 1]))
        normalized = []
        for frame in stack:
            if frame.startswith('Main.main(') or '.Main.main(' in frame:
                frame = frame.split('(', 1)[0]
            if 'jdk.jfr.' not in frame and 'jdk.internal.classfile.' not in frame:
                normalized.append(frame)
        if normalized:
            stacks.append(normalized)
    return stacks


def node(name):
    return {'name': name, 'count': 0, 'self': 0, 'children': {}}


def add_stack(root, stack):
    current = root
    current['count'] += 1
    for frame in stack:
        child = current['children'].setdefault(frame, node(frame))
        child['count'] += 1
        current = child
    current['self'] += 1


def max_depth(current, depth=0):
    if not current['children']:
        return depth
    return max(max_depth(child, depth + 1) for child in current['children'].values())


def flamegraph_svg(stacks, title):
    root = node('samples')
    for stack in stacks:
        add_stack(root, stack)
    depth = max_depth(root) - 1
    top = 58
    height = top + (depth + 1) * BAR_HEIGHT + 24
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {SVG_WIDTH} {height}" '
        f'role="img" aria-label="{html.escape(title)}">',
        '<style>text{font-family:Segoe UI,Arial,sans-serif;fill:#17212b}'
        ' .frame{stroke:#ffffff;stroke-width:1;opacity:.94}'
        ' .frame:hover{opacity:1;stroke:#111827;stroke-width:2}</style>',
        f'<text x="16" y="25" font-size="18" font-weight="600">{html.escape(title)}</text>',
        f'<text x="16" y="44" font-size="12">{len(stacks)} JFR execution samples; width represents samples</text>',
    ]

    def draw(current, x, level, width):
        y = top + (depth - level) * BAR_HEIGHT
        hue = int.from_bytes(hashlib.md5(current['name'].encode()).digest()[:2], 'big') % 360
        color = f'hsl({hue}, 72%, 76%)'
        percent = 100 * current['count'] / max(1, len(stacks))
        label = current['name']
        parts.append(
            f'<rect class="frame" x="{x:.2f}" y="{y}" width="{max(0, width):.2f}" '
            f'height="{BAR_HEIGHT - 1}" fill="{color}">'
            f'<title>{html.escape(label)} — {current["count"]} samples ({percent:.1f}%)</title></rect>'
        )
        if width >= 42:
            chars = max(3, int((width - 8) / 6.8))
            text = label if len(label) <= chars else label[:chars - 1] + '…'
            parts.append(
                f'<text x="{x + 5:.2f}" y="{y + 16}" font-size="11" '
                f'pointer-events="none">{html.escape(text)}</text>'
            )
        child_x = x
        for child in sorted(current['children'].values(),
                            key=lambda value: value['count'], reverse=True):
            child_width = width * child['count'] / max(1, current['count'])
            draw(child, child_x, level + 1, child_width)
            child_x += child_width

    x = 0.0
    for child in sorted(root['children'].values(), key=lambda value: value['count'], reverse=True):
        width = SVG_WIDTH * child['count'] / max(1, root['count'])
        draw(child, x, 0, width)
        x += width
    parts.append('</svg>')
    return '\n'.join(parts)


def profile_python():
    source = (ROOT / 'test.py').read_text(encoding='utf-8')
    source = source.replace('start = time.time()', 'profiler.enable()\nstart = time.time()', 1)
    source = source.replace('end = time.time()', 'profiler.disable()\nend = time.time()', 1)
    profiler = cProfile.Profile()
    namespace = {'__name__': '__main__', '__file__': str(ROOT / 'test.py'),
                 'profiler': profiler}
    code = compile(source, str(ROOT / 'test.py'), 'exec')
    with open(os.devnull, 'w', encoding='utf-8') as sink:
        with contextlib.redirect_stdout(sink):
            for _ in range(PROFILE_RUNS):
                exec(code, namespace)
    stats = pstats.Stats(profiler).strip_dirs()
    report = io.StringIO()
    stats.stream = report
    stats.sort_stats('cumtime').print_stats(25)
    rows = []
    for (filename, line, function), (_, calls, self_time, cumulative, _) in stats.stats.items():
        label = f'{function} ({filename}:{line})'
        rows.append((label, calls, self_time, cumulative))
    rows.sort(key=lambda row: row[2], reverse=True)
    print('\n=== Python test.py: cProfile, timed body only ===')
    print(report.getvalue())
    return rows[:15]


def python_hotspots_svg(rows):
    width, left, bar_width, row_h, top = 1400, 440, 820, 27, 58
    height = top + row_h * len(rows) + 30
    max_time = max((row[2] for row in rows), default=1)
    parts = [
        f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {width} {height}" role="img">',
        '<style>text{font-family:Segoe UI,Arial,sans-serif;fill:#17212b}</style>',
        '<text x="16" y="25" font-size="18" font-weight="600">Python test.py — cProfile self time</text>',
        f'<text x="16" y="44" font-size="12">Top functions across {PROFILE_RUNS} profiled runs; bars show exclusive time</text>',
    ]
    for i, (label, calls, self_time, cumulative) in enumerate(rows):
        y = top + i * row_h
        bar = bar_width * self_time / max_time
        parts.append(f'<text x="16" y="{y + 16}" font-size="12">{html.escape(label[:62])}</text>')
        parts.append(f'<rect x="{left}" y="{y}" width="{bar:.2f}" height="19" fill="#72a7d8">'
                     f'<title>{html.escape(label)} — {calls} calls; self {self_time:.3f}s; cumulative {cumulative:.3f}s</title></rect>')
        parts.append(f'<text x="{left + bar + 7:.2f}" y="{y + 15}" font-size="11">{self_time:.3f}s</text>')
    parts.append('</svg>')
    return '\n'.join(parts)


def write_index():
    body = '''<!doctype html><html><head><meta charset="utf-8"><title>Profile flamegraphs</title>
<style>body{font:16px Segoe UI,Arial,sans-serif;max-width:1500px;margin:24px auto;padding:0 18px;color:#17212b}
section{margin:30px 0}img{width:100%;height:auto;border:1px solid #ccd4dc;background:#fff}
p{color:#485563}</style></head><body><h1>Performance profiles</h1>
<p>JFR flamegraphs show sampled Java call stacks. The Python chart shows cProfile exclusive time. Hover over bars for sample counts or timing details.</p>
<section><h2>TreeMap — naive</h2><img src="flamegraph-naive.svg" alt="TreeMap Java flamegraph"></section>
<section><h2>HashMap plus sort — naive2</h2><img src="flamegraph-naive2.svg" alt="HashMap Java flamegraph"></section>
<section><h2>Python baseline</h2><img src="python-hotspots.svg" alt="Python cProfile hotspots"></section>
</body></html>'''
    (OUTPUT / 'index.html').write_text(body, encoding='utf-8')


OUTPUT.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(prefix='1brc-profile-') as temp:
    work_dir = Path(temp)
    profiles = [
        ('naive', 'TreeMap (naive)', 'naive'),
        ('naive2', 'HashMap + sort (naive2)', 'naive2'),
    ]
    for output_name, label, source_package in profiles:
        recording = run_java_profile(output_name, source_package, work_dir)
        print(f'\n=== {label}: JFR hot methods ===')
        print(jfr_hot_methods(recording))
        stacks = jfr_stacks(recording)
        print(f'=== {label}: sampled call paths ({len(stacks)} samples) ===')
        seen = set()
        shown = 0
        for stack in stacks:
            path = tuple(stack)
            if path in seen:
                continue
            seen.add(path)
            print('  ' + ' -> '.join(stack))
            shown += 1
            if shown == 8:
                break
        filename = f'flamegraph-{output_name}.svg'
        (OUTPUT / filename).write_text(flamegraph_svg(stacks, label), encoding='utf-8')
    python_rows = profile_python()
    (OUTPUT / 'python-hotspots.svg').write_text(python_hotspots_svg(python_rows), encoding='utf-8')
write_index()
print(f'\nVisual profile report: {OUTPUT / "index.html"}')
