#!/usr/bin/env python3
"""Apply pinned build settings to a freshly generated Capacitor 5 Android project."""
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / 'android'
variables = ANDROID / 'variables.gradle'
contents = variables.read_text()
contents = re.sub(r'compileSdkVersion = \d+', 'compileSdkVersion = 34', contents)
variables.write_text(contents)

build = ANDROID / 'build.gradle'
build.write_text(re.sub(r'com.android.tools.build:gradle:[0-9.]+',
                        'com.android.tools.build:gradle:8.1.4', build.read_text()))

app = ANDROID / 'app/build.gradle'
contents = re.sub(r'versionCode \d+', 'versionCode 2', app.read_text())
contents = re.sub(r'versionName "[^"]+"', 'versionName "1.0.1"', contents)
marker = '// SUMOU_OPTIONAL_DEBUG_SIGNING'
if marker not in contents:
    contents += '''
// SUMOU_OPTIONAL_DEBUG_SIGNING
// Keep this file outside source control to reuse the same test signing identity.
def sumouDebugKeystore = System.getenv("SUMOU_DEBUG_KEYSTORE")
if (sumouDebugKeystore) {
    android.signingConfigs.debug.storeFile = file(sumouDebugKeystore)
}
'''
app.write_text(contents)
print('Configured Android build: compile SDK 34, AGP 8.1.4, version 1.0.1 (2).')
