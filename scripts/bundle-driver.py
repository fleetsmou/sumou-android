#!/usr/bin/env python3
"""Refresh the bundled web UI from the sibling driver repository, retaining pinned vendors."""
from pathlib import Path
import argparse
import shutil

parser = argparse.ArgumentParser()
parser.add_argument('source', type=Path, help='Path to the Driver-s-App checkout')
args = parser.parse_args()
source = args.source.resolve()
if not (source / 'index.html').exists():
    raise SystemExit('Driver source must contain index.html')
destination = Path(__file__).resolve().parents[1] / 'www'
for item in source.iterdir():
    if item.name == 'icons' and item.is_dir():
        shutil.copytree(item, destination / item.name, dirs_exist_ok=True)
    elif item.is_file() and (item.name in {'index.html', 'fonts.css', 'sw.js', 'manifest.webmanifest', 'OFL.txt'}
                             or item.suffix == '.woff2'):
        shutil.copy2(item, destination / item.name)
shutil.rmtree(destination / 'tests', ignore_errors=True)
page = destination / 'index.html'
contents = page.read_text()
contents = contents.replace('https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.css',
                            'vendor/leaflet/leaflet.css')
contents = contents.replace('https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.js',
                            'vendor/leaflet/leaflet.js')
contents = contents.replace('https://cdn.jsdelivr.net/npm/@supabase/supabase-js@2',
                            'vendor/supabase/supabase.js')
page.write_text(contents)
print('Bundled driver UI, fonts, icons and pinned local JavaScript libraries.')
