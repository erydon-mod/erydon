"""Prepare an isolated development capture; --launch explicitly opens Minecraft.

The supplied v2 repair directory is authoritative for offline inventories/checkpoints.
This adapter certifies development outputs separately, never inventing an Erydon JAR.
"""
import argparse
import hashlib
import importlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys

FLAGS = {
    'erydon.perf.pause_self_test': 'false',
    'erydon.perf.frame_capture': 'true', 'erydon.perf.alias_index': 'false',
    'erydon.perf.shared_stair_shapes': 'false', 'erydon.perf.sprite_handles': 'false',
    'erydon.perf.clip_plans': 'false', 'erydon.shared_geometry.mode': 'baseline',
    'erydon.shared_geometry.metrics': 'false', 'erydon.synapheia.metrics': 'false',
}


def save(path, value):
    with path.open('x', encoding='utf-8') as stream:
        json.dump(value, stream, indent=2)


def running_games():
    if os.name != 'nt':
        raise ValueError('Concurrent-game detection requires a reviewed platform adapter')
    # Only PIDs leave this query; authentication-bearing command lines are never logged.
    script = "Get-CimInstance Win32_Process | Where-Object { $_.Name -in 'java.exe','javaw.exe' -and $_.CommandLine -match 'net.minecraft.client.main.Main|fabricmc.loader.impl.launch.knot.KnotClient|fabricmc.devlaunchinjector.Main' } | Select-Object -ExpandProperty ProcessId"
    result = subprocess.run(['powershell', '-NoProfile', '-Command', script], capture_output=True, text=True, check=True)
    return result.stdout.split()


def prepare(args):
    repo = Path(__file__).resolve().parents[1]
    authority = args.repair_tools.resolve(strict=True)
    sys.path.insert(0, str(authority))
    checkpoint = importlib.import_module('source_checkpoint')
    collector = importlib.import_module('collect_manifest')
    instance = args.instance.resolve()
    allowed = (repo / 'build/perf-instance').resolve()
    if instance != allowed:
        raise ValueError('This runner only uses the dedicated build/perf-instance directory')
    for path in [instance, *instance.parents]:
        if path.exists():
            collector.no_link(path)
    if instance.exists() and not (instance / 'ERYDON_PERF_DISPOSABLE').is_file():
        raise ValueError('Existing instance is not marked disposable; refusing to adopt it')
    if args.launch and running_games():
        raise ValueError('Another Minecraft client is running; keep it untouched and run this trial later')
    output = args.out.resolve()
    if output.is_relative_to(repo):
        raise ValueError('Capture reports must be outside the source repository')
    output.mkdir(parents=True, exist_ok=False)
    instance.mkdir(parents=True, exist_ok=True)
    marker = instance / 'ERYDON_PERF_DISPOSABLE'
    if not marker.exists():
        marker.write_text('Dedicated measurement fixture. Never copy a real save here.\n', encoding='utf-8')
    before = checkpoint.snapshot(repo)
    save(output / 'source-before.json', before)
    layout = output / 'layout.json'
    properties = ['-Perydon.perf.foundation=true', '-Perydon.perf.layout=' + str(layout), '-Perydon.perf.maxHeap=' + args.max_heap]
    flags = dict(FLAGS, **{'erydon.perf.pause_self_test': str(args.pause_self_test).lower(),
                          'erydon.perf.alias_index': str(args.alias_index).lower()})
    properties += ['-P' + k + '=' + v for k, v in flags.items()]
    command = [str(repo / 'gradlew.bat'), '--no-daemon', '--max-workers=2', *properties]
    with (output / 'prepare.log').open('w', encoding='utf-8') as log:
        subprocess.run([*command, 'writePerfLayout', 'verifyPerfConfiguration'], cwd=repo, stdout=log, stderr=subprocess.STDOUT, check=True)
    after = checkpoint.snapshot(repo)
    source_gate = checkpoint.compare(before, after)
    save(output / 'source-after.json', after)
    save(output / 'source-gate.json', source_gate)
    if not source_gate['source_gate_passed']:
        raise ValueError('Source changed during preparation; see source-gate.json')
    paths = json.loads(layout.read_text())
    files = {}
    # Instance mods are loaded by Fabric outside the Java launch classpath.
    if (instance / 'mods').exists():
        paths['classpath'].append(str(instance / 'mods'))
    for root in paths['classpath']:
        path = Path(root)
        collector.no_link(path)
        candidates = [path] if path.is_file() else sorted(path.rglob('*'))
        for file in candidates:
            collector.no_link(file)
            if file.is_file():
                files[str(file.resolve())] = collector.digest(file)
    power = subprocess.run(['powercfg', '/getactivescheme'], capture_output=True, text=True, check=True).stdout.strip()
    expected = {**paths, 'source_sha256': after['content_sha256'], 'files': files, 'flags': flags, 'power_mode': power}
    save(output / 'expected.json', expected)
    save(output / 'static-instance.json', collector.collect(instance))
    launch = [*command, '-Perydon.perf.gameDir=' + str(instance),
              '-Perydon.perf.output=' + str(output / 'capture'),
              '-Perydon.perf.expected=' + str(output / 'expected.json'), 'runPerfClient']
    save(output / 'launch.json', {'command': launch, 'cwd': str(repo), 'heap': '2G initial / ' + args.max_heap + ' maximum',
         'world': 'Erydon Performance Disposable', 'requires_focused_uncapped_window': not args.pause_self_test,
         'performance_measurement': not args.pause_self_test})
    if not args.launch:
        print('Prepared only. No Minecraft launch; runtime checks remain unverified.')
        return
    if running_games():
        raise ValueError('Another Minecraft client started during preparation; launch deferred')
    with (output / 'client.log').open('w', encoding='utf-8') as log:
        subprocess.run(launch, cwd=repo, stdout=log, stderr=subprocess.STDOUT, check=True)
    status = output / 'capture/capture-status.json'
    if not status.is_file() or not successful_completion(json.loads(status.read_text()), args.pause_self_test):
        raise ValueError('Capture did not complete validly; inspect capture status or client.log')
    print('Hidden pause-menu self-test passed; no FPS data produced.' if args.pause_self_test
          else 'One development capture completed; compare a matching A/A pair before interpreting measurements.')


def successful_completion(status, pause_self_test=False):
    if pause_self_test:
        return (status.get('state') == 'INVALID' and status.get('pause_menu_test_passed') is True
                and status.get('performance_measurement') is False and status.get('intervals', 0) >= 10
                and status.get('dropped') == 0)
    return (status.get('state') == 'COMPLETE' and status.get('performance_measurement') is not False
            and status.get('dropped') == 0)


def prove_launch_timestamp(raw, hashes):
    """Prove both original byte hashes differ only in a generated timestamp.

    Recover each original header by exhaustive same-day seconds, never by
    trusting current settings or dropping arbitrary comments from evidence.
    """
    lines = raw.splitlines(keepends=True)
    stamp = rb'#(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun) [A-Z][a-z]{2} [0-9]{2} ([0-9]{2}:[0-9]{2}:[0-9]{2}) [A-Za-z0-9+:_-]+ [0-9]{4}\r?\n'
    match = re.fullmatch(stamp, lines[1]) if len(lines) > 2 else None
    if len(raw) > 65536 or not lines or not lines[0].startswith(b'#') or match is None:
        raise ValueError('Unrecognised generated properties timestamp')
    prefix = lines[0] + lines[1][:match.start(1)]
    suffix = lines[1][match.end(1):] + b''.join(lines[2:])
    found = {}
    wanted = set(hashes)
    for second in range(86400):
        clock = f'{second // 3600:02}:{second // 60 % 60:02}:{second % 60:02}'.encode('ascii')
        digest = hashlib.sha256(prefix + clock + suffix).hexdigest()
        if digest in wanted:
            found[digest] = clock.decode('ascii')
            if found.keys() == wanted:
                return {'matched_sha256_to_time': found,
                        'content_without_launch_time_sha256': hashlib.sha256(prefix + b'<launch time>' + suffix).hexdigest()}
    raise ValueError('Settings changed beyond the generated same-day timestamp')


def verify_launch_timestamp_differences(environments, instance):
    allowed = {'config/iris.properties', 'config/indium-renderer.properties',
               'config/fabric/indigo-renderer.properties'}
    settings = [dict(e.get('settings', {})) for e in environments]
    if settings[0].keys() != settings[1].keys():
        raise ValueError('Settings file inventory changed')
    proofs = {}
    for key in settings[0]:
        if settings[0][key] == settings[1][key]:
            continue
        relative = key.replace('\\', '/')
        if relative not in allowed:
            raise ValueError('Settings changed: ' + key)
        proof = prove_launch_timestamp((instance / relative).read_bytes(), [s[key] for s in settings])
        proofs[key] = proof
        for setting in settings:
            setting[key] = proof['content_without_launch_time_sha256']
    return [dict(e, settings=s) for e, s in zip(environments, settings)], proofs


def compare(args):
    sys.path.insert(0, str(args.repair_tools.resolve(strict=True)))
    frames = importlib.import_module('frame_summary')
    samples = []
    environments = []
    for root in [args.first, args.second]:
        capture = root / 'capture'
        status = json.loads((capture / 'capture-status.json').read_text())
        if not successful_completion(status):
            raise ValueError('Invalid or interrupted capture')
        before = json.loads((capture / 'runtime-before.json').read_text())
        after = json.loads((capture / 'runtime-after.json').read_text())
        if before != after:
            raise ValueError('Runtime changed within capture')
        for key in ['cpu', 'gpu', 'driver', 'power_mode', 'source_sha256']:
            if before.get(key) in (None, '', 'UNKNOWN'):
                raise ValueError('Incomplete runtime field: ' + key)
        environments.append(before)
        samples.append(frames.summarise(frames.load_frames(capture / 'frames.csv')))
    proofs = {}
    if getattr(args, 'instance', None) is not None:
        instance = args.instance.resolve(strict=True)
        if instance != (Path(__file__).resolve().parents[1] / 'build/perf-instance').resolve():
            raise ValueError('Only the dedicated disposable instance is supported')
        if not (instance / 'ERYDON_PERF_DISPOSABLE').is_file():
            raise ValueError('Disposable instance marker missing')
        environments, proofs = verify_launch_timestamp_differences(environments, instance)
    if environments[0] != environments[1]:
        raise ValueError('A/A runtime configurations or source/build identity differ')
    result = frames.compare_runs([samples[0]], [samples[1]])
    result.update({'comparison': 'identical-baseline plumbing smoke test', 'optimisation_gain': None, 'visual_approval': False})
    result['verified_launch_timestamp_differences'] = proofs
    save(args.out, result)
    print('A/A configuration and raw-frame plumbing passed; this establishes no optimisation gain.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--repair-tools', type=Path, required=True)
    sub = parser.add_subparsers(dest='action', required=True)
    trial = sub.add_parser('prepare')
    trial.add_argument('--instance', type=Path, required=True)
    trial.add_argument('--out', type=Path, required=True)
    trial.add_argument('--launch', action='store_true')
    trial.add_argument('--pause-self-test', action='store_true', help='Hidden automatic pause-menu lifecycle check; never produces FPS measurements')
    trial.add_argument('--alias-index', action='store_true', help='Opt into the experimental texture-alias prefix index in the child JVM and provenance record')
    trial.add_argument('--max-heap', choices=['8G', '12G', '16G', '24G'], default='8G')
    pair = sub.add_parser('compare')
    pair.add_argument('first', type=Path); pair.add_argument('second', type=Path)
    pair.add_argument('--out', type=Path, required=True)
    pair.add_argument('--instance', type=Path, help='Optionally prove known generated same-day timestamp comments against original capture hashes')
    args = parser.parse_args()
    (prepare if args.action == 'prepare' else compare)(args)


if __name__ == '__main__':
    main()
