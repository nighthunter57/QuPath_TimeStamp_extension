#!/usr/bin/env python3
"""Check package checksums and exact agreement with the release source/build."""
import argparse
import hashlib
import io
from pathlib import Path
from zipfile import ZipFile

PACKAGE_SOURCES = {
    'live_whisper_demo.py': 'scripts/live_whisper_demo.py',
    'prepare_doctor_models.py': 'scripts/prepare_doctor_models.py',
    'requirements-doctor.txt': 'requirements-doctor.txt',
    'DOCTOR-INSTALL.txt': 'packaging/DOCTOR-INSTALL.txt',
    'Install TimeStamp.command': 'packaging/macos/Install TimeStamp.command',
    'Install TimeStamp on Linux.sh': 'packaging/linux/Install TimeStamp.sh',
    'Install TimeStamp on Windows.bat': 'packaging/windows/Install TimeStamp on Windows.bat',
    'Install-TimeStamp.ps1': 'packaging/windows/Install-TimeStamp.ps1',
}


def verify(package: Path, root: Path) -> None:
    with ZipFile(package) as archive:
        jars = [name for name in archive.namelist() if name.endswith('.jar')]
        if len(jars) != 1:
            raise ValueError('Package must contain exactly one extension JAR')
        prefix = jars[0].rsplit('/', 1)[0] + '/'
        for filename, source in PACKAGE_SOURCES.items():
            if archive.read(prefix + filename) != (root / source).read_bytes():
                raise ValueError('Package differs from source: ' + filename)
        checked = set()
        for line in archive.read(prefix + 'CHECKSUMS-SHA256.txt').decode('utf-8').splitlines():
            expected, filename = line.split(None, 1)
            filename = filename.lstrip('*')
            if filename in checked:
                raise ValueError('Duplicate checksum: ' + filename)
            checked.add(filename)
            if hashlib.sha256(archive.read(prefix + filename)).hexdigest() != expected:
                raise ValueError('Checksum mismatch: ' + filename)
        if checked != set(PACKAGE_SOURCES) | {Path(jars[0]).name}:
            raise ValueError('Checksums must cover every packaged installer and runtime file')
        if archive.read(jars[0]) != (root / 'build/libs' / Path(jars[0]).name).read_bytes():
            raise ValueError('Packaged JAR differs from this build')
        with ZipFile(io.BytesIO(archive.read(jars[0]))) as jar:
            if jar.read('qupath/ext/timestamp/scripts/live_whisper_demo.py') != (root / 'scripts/live_whisper_demo.py').read_bytes():
                raise ValueError('Embedded helper differs from source')
    print('Package, installers, guide, helper, and built JAR agree.')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('package', type=Path)
    args = parser.parse_args()
    verify(args.package, Path(__file__).resolve().parent.parent)
