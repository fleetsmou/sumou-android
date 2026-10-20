#!/usr/bin/env python3
"""Align and sign a release APK using a private, externally stored signing key."""
from pathlib import Path
import argparse
import hashlib
import os
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('output', type=Path, help='Destination for the signed release APK')
parser.add_argument('--input', type=Path,
                    default=ROOT / 'android/app/build/outputs/apk/release/app-release-unsigned.apk',
                    help='Unsigned APK, including one downloaded from GitHub Actions')
args = parser.parse_args()
output = args.output.resolve()
sdk = os.environ.get('ANDROID_HOME') or os.environ.get('ANDROID_SDK_ROOT')
key = os.environ.get('SUMOU_RELEASE_KEYSTORE')
password = os.environ.get('SUMOU_RELEASE_PASSWORD_FILE')
if not all((sdk, key, password)):
    raise SystemExit('Set ANDROID_HOME, SUMOU_RELEASE_KEYSTORE and SUMOU_RELEASE_PASSWORD_FILE.')
source = args.input.resolve()
if not source.exists():
    raise SystemExit('Unsigned APK not found. Build assembleRelease or supply --input /path/to/app-release-unsigned.apk.')
for value in (key, password):
    if not Path(value).is_file():
        raise SystemExit('Signing key/password file does not exist.')
build_tools = Path(sdk) / 'build-tools/34.0.0'
output.parent.mkdir(parents=True, exist_ok=True)
with tempfile.TemporaryDirectory(prefix='sumou-sign-') as temp:
    aligned = Path(temp) / 'aligned.apk'
    subprocess.run([str(build_tools / 'zipalign'), '-f', '-p', '4', str(source), str(aligned)], check=True)
    signing_env = dict(os.environ)
    signing_env['SUMOU_SIGNING_PASSWORD'] = Path(password).read_text().strip()
    subprocess.run([str(build_tools / 'apksigner'), 'sign', '--ks', key,
                    '--ks-key-alias', 'sumou-fleet', '--ks-pass', 'env:SUMOU_SIGNING_PASSWORD',
                    '--key-pass', 'env:SUMOU_SIGNING_PASSWORD', '--out', str(output), str(aligned)],
                   check=True, env=signing_env)
subprocess.run([str(build_tools / 'apksigner'), 'verify', '--verbose', '--print-certs', str(output)], check=True)
subprocess.run([str(build_tools / 'zipalign'), '-c', '4', str(output)], check=True)
print(f'Signed release: {output.name}')
print(f'SHA-256: {hashlib.sha256(output.read_bytes()).hexdigest()}')
