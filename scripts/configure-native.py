#!/usr/bin/env python3
"""Install the on-duty native tracker into a generated Capacitor Android project."""
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android"
MANIFEST = ANDROID / "app/src/main/AndroidManifest.xml"
PACKAGE = ANDROID / "app/src/main/java/com/sumou/fleet"
NS = "http://schemas.android.com/apk/res/android"
ET.register_namespace("android", NS)


def android(name):
    return f"{{{NS}}}{name}"


def configure():
    if not MANIFEST.exists():
        raise SystemExit("Generate the Android platform with Capacitor before configuring the tracker.")
    PACKAGE.mkdir(parents=True, exist_ok=True)
    for source in (ROOT / "native-src").glob("*.java"):
        shutil.copy2(source, PACKAGE / source.name)

    tree = ET.parse(MANIFEST)
    root = tree.getroot()
    permissions = {
        "INTERNET", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION",
        "FOREGROUND_SERVICE", "FOREGROUND_SERVICE_LOCATION", "WAKE_LOCK", "POST_NOTIFICATIONS"
    }
    declared = {node.get(android("name")) for node in root.findall("uses-permission")}
    for permission in sorted(permissions):
        name = f"android.permission.{permission}"
        if name not in declared:
            root.insert(0, ET.Element("uses-permission", {android("name"): name}))
    application = root.find("application")
    if application is None:
        raise SystemExit("Android manifest has no application element.")
    service = next((node for node in application.findall("service")
                    if node.get(android("name")) == "com.sumou.fleet.SumouTrackerService"), None)
    if service is None:
        service = ET.SubElement(application, "service")
    service.set(android("name"), "com.sumou.fleet.SumouTrackerService")
    service.set(android("exported"), "false")
    service.set(android("foregroundServiceType"), "location")
    # Recents removes the Activity task; the on-duty service is deliberately independent.
    service.set(android("stopWithTask"), "false")
    ET.indent(tree, space="    ")
    tree.write(MANIFEST, encoding="utf-8", xml_declaration=True)

    gradle = ANDROID / "app/build.gradle"
    contents = gradle.read_text()
    dependency = "com.google.android.gms:play-services-location:21.0.1"
    if dependency not in contents:
        gradle.write_text(contents + f"\ndependencies {{ implementation '{dependency}' }}\n")
    print("Configured native live-location foreground service (latest location only).")


if __name__ == "__main__":
    configure()
