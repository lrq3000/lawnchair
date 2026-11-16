"""Compile and run the actual Android-independent production availability store on JDK 21."""

import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path(os.environ.get("JAVA_HOME", "C:/Program Files/Java/jdk-21")) / "bin"
OUTPUT = Path(tempfile.gettempdir()) / "opencode" / "lawnchair-availability-tests"
OUTPUT.mkdir(parents=True, exist_ok=True)
subprocess.run([
    str(JAVA / "javac"), "-Xlint:all", "-d", str(OUTPUT),
    str(ROOT / "lawnchair/src/app/lawnchair/model/WorkspaceAvailabilityStore.java"),
    str(ROOT / "lawnchair/src/app/lawnchair/model/WorkspaceTargetState.java"),
    str(ROOT / "lawnchair/src/app/lawnchair/model/WorkspaceIconLoad.java"),
    str(ROOT / "tests/host/WorkspaceAvailabilityStoreTest.java"),
    str(ROOT / "tests/host/WorkspaceTargetStateTest.java"),
    str(ROOT / "tests/host/WorkspaceIconLoadTest.java"),
], check=True)
subprocess.run([
    str(JAVA / "java"), "-ea", "-cp", str(OUTPUT),
    "app.lawnchair.model.WorkspaceIconLoadTest",
], check=True)
subprocess.run([
    str(JAVA / "java"), "-ea", "-cp", str(OUTPUT),
    "app.lawnchair.model.WorkspaceTargetStateTest",
], check=True)
subprocess.run([
    str(JAVA / "java"), "-ea", "-cp", str(OUTPUT),
    "app.lawnchair.model.WorkspaceAvailabilityStoreTest",
], check=True)
