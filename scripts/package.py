"""Package only after all target builds and both-mode gameplay tests have passed."""
from pathlib import Path
import hashlib
import json
import shutil
import struct
import sys
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parents[1]
out = Path(sys.argv[1]).resolve() if len(sys.argv) > 1 else root.parents[1] / 'outputs' / 'skin-switching-0.2.0'
targets = json.loads((root / 'versions.json').read_text(encoding='utf-8'))
assert (root / 'shared/src/main/resources/skin-switching-mode.txt').read_text(encoding='utf-8').strip() == 'sync'
out.mkdir(parents=True, exist_ok=True)
summary = {}
for project, target in targets.items():
    reports = [ET.parse(p).getroot() for p in (root / project / 'build/test-results/test').glob('TEST-*.xml')]
    assert sum(int(r.attrib['tests']) for r in reports) == 17, project
    assert all(int(r.attrib['failures']) + int(r.attrib['errors']) == 0 for r in reports), project
    summary[target['minecraft']] = {'neoforge': target['neoforge'], 'java': target['java'], 'unitTestsPassed': 17, 'gameplay': {}}
    for mode in ('client', 'sync'):
        result = (root / project / 'run-smoke-play' / f'smoke-result-{mode}.txt').read_text(encoding='utf-8')
        assert result.startswith('PASS:'), (project, mode, result)
        summary[target['minecraft']]['gameplay'][mode] = result
        name = f"skin-switching-{target['minecraft']}-0.2.0-{mode}.jar"
        source = root / project / 'build/libs' / name
        with zipfile.ZipFile(source) as jar:
            assert jar.namelist().count('skin-switching-mode.txt') == 1
            assert jar.read('skin-switching-mode.txt').decode().strip() == mode
            metadata = jar.read('META-INF/neoforge.mods.toml').decode()
            assert f"[{target['minecraft']}]" in metadata
            assert target['neoRange'] in metadata
            assert '${' not in metadata
            assert not any('smoketest' in n or '/core/CoreTest' in n for n in jar.namelist())
            assert json.loads(jar.read('assets/skin_switching/lang/en_us.json')).keys() == json.loads(jar.read('assets/skin_switching/lang/zh_cn.json')).keys()
            for entry in jar.namelist():
                if entry.endswith('.class'):
                    major = struct.unpack('>H', jar.read(entry)[6:8])[0]
                    assert major == target['java'] + 44, (name, entry, major)
        destination = out / 'jars' / target['minecraft'] / name
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)

for name in ('README.md', 'TESTING.md', 'LICENSE'):
    shutil.copy2(root / name, out / name)
(out / 'test-results.json').write_text(json.dumps(summary, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')

with zipfile.ZipFile(out / 'skin-switching-0.2.0-all-jars.zip', 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for path in sorted((out / 'jars').rglob('*.jar')):
        archive.write(path, path.relative_to(out / 'jars'))
    for name in ('README.md', 'TESTING.md', 'LICENSE', 'test-results.json'):
        archive.write(out / name, name)

source_files = [root / p for p in ('settings.gradle', 'build.gradle', 'gradle.properties', 'versions.json', '.gitignore',
                                  'gradlew', 'gradlew.bat', 'README.md', 'TESTING.md', 'LICENSE', 'THIRD_PARTY_NOTICES.md')]
for directory in ('shared', 'legacy', 'modern', 'smokeAdapters', 'clientFlavor', 'gradle', 'licenses', 'scripts'):
    source_files.extend(p for p in (root / directory).rglob('*') if p.is_file() and '__pycache__' not in p.parts)
with zipfile.ZipFile(out / 'skin-switching-0.2.0-source.zip', 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for path in sorted(source_files):
        info = zipfile.ZipInfo.from_file(path, str(Path('skin-switching') / path.relative_to(root)))
        info.compress_type = zipfile.ZIP_DEFLATED
        if path.name == 'gradlew':
            info.create_system = 3
            info.external_attr = 0o100755 << 16
        archive.writestr(info, path.read_bytes())
    for project in targets:
        archive.writestr(f'skin-switching/{project}/', b'')

with (out / 'SHA256SUMS.txt').open('w', encoding='utf-8', newline='\n') as checksum:
    for path in sorted(out.rglob('*')):
        if path.is_file() and path.name != 'SHA256SUMS.txt':
            checksum.write(hashlib.sha256(path.read_bytes()).hexdigest() + '  ' + path.relative_to(out).as_posix() + '\n')
print(f'Packaged {len(targets) * 2} verified mod JARs and source in {out}')
