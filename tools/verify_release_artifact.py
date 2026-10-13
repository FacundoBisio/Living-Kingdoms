"""Verify the actual distributable against pinned metadata, current resources and Java 21."""
import argparse
import hashlib
import json
from pathlib import Path
import tomllib
import zipfile


def verify(version):
    properties = dict(line.split('=', 1) for line in Path('gradle.properties').read_text().splitlines()
                      if '=' in line and not line.startswith('#'))
    assert properties['mod_version'] == version
    assert properties['minecraft_version'] == '1.21.1'
    assert properties['neo_version'] == '21.1.252'
    artifact = Path(f'build/libs/livingkingdoms-{version}.jar')
    with zipfile.ZipFile(artifact) as package:
        assert package.testzip() is None
        names = package.namelist()
        assert len(names) == len(set(names)), 'Duplicate JAR entries'
        metadata = tomllib.loads(package.read('META-INF/neoforge.mods.toml').decode('utf-8'))
        assert metadata['mods'][0]['modId'] == 'livingkingdoms'
        assert metadata['mods'][0]['version'] == version
        dependencies = {entry['modId']: entry for entry in metadata['dependencies']['livingkingdoms']}
        assert dependencies['minecraft']['versionRange'] == '[1.21.1]'
        assert dependencies['neoforge']['versionRange'] == '[21.1.252,21.2)'
        assert not any('gametest/' in name or 'livingkingdoms_tests' in name for name in names)
        for source in Path('src/main/resources').rglob('*'):
            if source.is_file():
                relative = source.relative_to('src/main/resources').as_posix()
                assert package.read(relative) == source.read_bytes(), f'Stale resource: {relative}'
        for implementation in ('profession/EconomicWork', 'profession/CitizenRoutine', 'profession/RepairWork',
                               'economy/KingdomEconomy', 'client/QuestNotifications', 'ui/QuestNotificationService'):
            contents = package.read(f'dev/livingkingdoms/{implementation}.class')
            assert contents[:4] == b'\xca\xfe\xba\xbe' and int.from_bytes(contents[6:8], 'big') == 65
        locales = [json.loads(package.read(f'assets/livingkingdoms/lang/{locale}.json')) for locale in ('en_us', 'es_ar')]
        assert locales[0].keys() == locales[1].keys(), 'Translation key mismatch'
        for locale in locales:
            for key in ('profession.livingkingdoms.lumberjack', 'profession.livingkingdoms.miner',
                        'notification.livingkingdoms.reward_claimed', 'ui.livingkingdoms.citizens.materials'):
                assert locale[key].strip()
    print(f'Verified {artifact.name}: pinned metadata, exact source resources, Java 21 classes, both locales, no test mod')
    print(f'{artifact.stat().st_size} bytes; SHA-256 {hashlib.sha256(artifact.read_bytes()).hexdigest()}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--version', required=True)
    verify(parser.parse_args().version)
