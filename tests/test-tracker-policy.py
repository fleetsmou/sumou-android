#!/usr/bin/env python3
"""Run native session/fix/retry regressions with a JDK, without an emulator."""
import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JDK = Path(os.environ["JAVA_HOME"]) / "bin" if os.environ.get("JAVA_HOME") else None
JAVAC = str(JDK / "javac") if JDK else "javac"
JAVA = str(JDK / "java") if JDK else "java"
source = (ROOT / "native-src/SumouTrackerService.java").read_text()
policy = source[source.index("final class TrackerPolicy {"):]
harness = r'''
final class TrackerPolicyTest {
    static int checks;
    static void check(boolean value, String message) {
        checks++;
        if (!value) throw new AssertionError(message);
    }
    public static void main(String[] args) {
        check(TrackerPolicy.mayStopSession("token-a", "shift-a", "token-a", "shift-a"), "Clock out stops its own shift");
        check(!TrackerPolicy.mayStopSession("token-a", "shift-a", "token-a", "shift-b"), "Old shift stop cannot stop new shift");
        check(!TrackerPolicy.mayStopSession("token-a", "shift-a", "token-b", "shift-a"), "Old login stop cannot stop new login");
        check(TrackerPolicy.mayStopSession(null, null, "token-a", "shift-a"), "Explicit global logout can stop inherited tracking");
        check(TrackerPolicy.mayStopSession("token-a", "shift-a", null, null), "Clock out clears credentials even if service was killed");
        check(!TrackerPolicy.sameSession(null, "shift-a", null, "shift-a"), "Missing token is never a valid session");
        check(TrackerPolicy.sameSession("token-a", "shift-a", "token-a", "shift-a"), "Exact terminal session matches");
        check(!TrackerPolicy.sameSession("token-a", "shift-a", "token-b", "shift-a"), "Late terminal status cannot kill new login");
        check("NO_ACTIVE_SHIFT".equals(TrackerPolicy.terminalReason("NO_ACTIVE_SHIFT")), "Closed shift stops native tracker");
        check("SESSION_EXPIRED".equals(TrackerPolicy.terminalReason("SESSION_EXPIRED")), "Expired login stops native tracker");
        check(TrackerPolicy.terminalReason("gateway unavailable") == null, "Transient errors must retry");
        check(TrackerPolicy.validFix(29.3759, 47.9774, 8f, 100000L), "Accept Kuwait fix");
        check(!TrackerPolicy.validFix(0, 0, 8f, 100000L), "Reject invalid origin fallback");
        check(!TrackerPolicy.validFix(Double.NaN, 47, 8f, 100000L), "Reject NaN latitude");
        check(!TrackerPolicy.validFix(29, Double.POSITIVE_INFINITY, 8f, 100000L), "Reject infinite longitude");
        check(!TrackerPolicy.validFix(29, 47, Float.NaN, 100000L), "Reject invalid accuracy");
        check(!TrackerPolicy.plausibleCapture(161001L, 100000L), "Reject implausible future capture");
        check(TrackerPolicy.coalesceStationary(10000L, 1f, 0f, 0f, 8f, 8f), "Stationary duplicate is coalesced");
        check(!TrackerPolicy.coalesceStationary(30000L, 1f, 0f, 0f, 8f, 8f), "Stationary driver still sends a 30s heartbeat");
        check(!TrackerPolicy.coalesceStationary(10000L, 10f, 20f, 20f, 8f, 8f), "Moving driver is not suppressed");
        check(TrackerPolicy.backoffMillis(1) == 5000L, "First network retry is five seconds");
        check(TrackerPolicy.backoffMillis(50) == 60000L, "Long outages retain bounded retry interval");
        System.out.println("PASS: " + checks + " native tracking policy checks");
    }
}
'''
with tempfile.TemporaryDirectory(prefix="sumou-tracker-test-") as tmp:
    directory = Path(tmp)
    (directory / "TrackerPolicy.java").write_text(policy)
    (directory / "TrackerPolicyTest.java").write_text(harness)
    subprocess.run([JAVAC, "-d", tmp, str(directory / "TrackerPolicy.java"), str(directory / "TrackerPolicyTest.java")], check=True)
    subprocess.run([JAVA, "-cp", tmp, "TrackerPolicyTest"], check=True)
