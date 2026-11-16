"""Bounded, exclusive runtime driver for the explicitly assigned Lawnchair LDPlayer target; never configures or deletes a guest."""

import argparse
from contextlib import contextmanager
from datetime import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
REGISTRY = Path(os.environ["LOCALAPPDATA"]) / "agent-emulators/ldplayer"
NAME = "agent-launcher-shortcuts-v2-6ff0dd05"
INDEX = 4
OWNER = "lawnchair-shortcut-reliability-worker"
RECORD = REGISTRY / (NAME + ".json")
LD = Path("C:/LDPlayer/LDPlayer9/ldconsole.exe")
ADB = Path("C:/Users/33632/AppData/Local/Android/Sdk/platform-tools/adb.exe")
BUDGET = 4194304


def run(command, timeout=20, check=True):
    result = subprocess.run(list(map(str, command)), capture_output=True, text=True, errors="replace", timeout=timeout)
    if check and result.returncode:
        raise RuntimeError(f"Command failed ({result.returncode}): {command}: {result.stdout} {result.stderr}")
    return result.stdout.strip()


def read(path):
    return json.loads(path.read_text(encoding="utf-8-sig"))


def write(path, value):
    temporary = path.with_name(path.name + "." + str(uuid.uuid4()) + ".tmp")
    temporary.write_text(json.dumps(value, indent=2) + "\n", encoding="utf-8")
    os.replace(temporary, path)


@contextmanager
def allocation():
    lock = REGISTRY / "allocation.lock"
    token = str(uuid.uuid4())
    deadline = time.monotonic() + 60
    while True:
        try:
            lock.mkdir()
            break
        except FileExistsError:
            if time.monotonic() >= deadline:
                raise RuntimeError("Existing allocation.lock remained held; no lock takeover attempted")
            time.sleep(1)
    owner_path = REGISTRY / "allocation-owner.json"
    write(owner_path, {"owner": OWNER, "generation": token, "state": "held", "acquired": time.time()})
    try:
        yield
    finally:
        owner = read(owner_path)
        if owner.get("generation") != token or owner.get("owner") != OWNER:
            raise RuntimeError("Allocation lock ownership changed; refusing to release it")
        write(owner_path, {**owner, "state": "released"})
        lock.rmdir()  # Release only the empty atomic lock acquired by this invocation; never remove guest storage.


def inventory():
    output = run([LD, "list2"])
    rows = [line.split(",") for line in output.splitlines() if line.strip()]
    matching = [row for row in rows if row[1] == NAME]
    if len(matching) != 1 or int(matching[0][0]) != INDEX:
        raise RuntimeError(f"Assigned native name/index does not match: {output}")
    return matching[0], output


def ps_json(script):
    output = run(["pwsh", "-NoProfile", "-Command", "$ErrorActionPreference='Stop'; " + script], timeout=30)
    return json.loads(output)


def processes(pids):
    positive = sorted({int(pid) for pid in pids if int(pid) > 0})
    if not positive:
        return []
    query = " OR ".join(f"ProcessId={pid}" for pid in positive)
    return ps_json("$p=@(Get-CimInstance Win32_Process -Filter '" + query + "' | ForEach-Object { "
                   "[pscustomobject]@{ProcessId=$_.ProcessId; CreationDate=$_.CreationDate.ToUniversalTime().ToString('o'); Name=$_.Name} }); "
                   "ConvertTo-Json -InputObject $p -Compress")


def same_process(left, right):
    return (left["ProcessId"] == right["ProcessId"]
            and datetime.fromisoformat(left["CreationDate"]) == datetime.fromisoformat(right["CreationDate"]))


def alive(identities):
    current = processes([p["ProcessId"] for p in identities])
    return [old for old in identities if any(same_process(old, now) for now in current)]


def claimed(generation):
    record = read(RECORD)
    if (not generation or record.get("owner") != OWNER or record.get("claim_generation") != generation
            or record.get("index") != INDEX or record.get("name") != NAME
            or Path(record["installation"]).resolve() != LD.parent.resolve()):
        raise RuntimeError("Current exclusive claim does not match this worker/generation/installation")
    inventory()
    return record


def ready(record):
    serial = run([LD, "adb", "--index", INDEX, "--command", "get-serialno"])
    if not re.fullmatch(r"emulator-\d+", serial) or (record.get("serial") and record["serial"] != serial):
        raise RuntimeError(f"Native serial missing, ambiguous, or changed: {serial!r}")
    if run([ADB, "-s", serial, "get-state"]) != "device":
        raise RuntimeError("Assigned ADB target is not ready")
    if run([ADB, "-s", serial, "shell", "getprop", "sys.boot_completed"]) != "1":
        raise RuntimeError("Assigned guest has not completed boot")
    if run([ADB, "-s", serial, "shell", "getprop", "ro.build.version.sdk"]) != "28":
        raise RuntimeError("Assigned guest is not API 28")
    if not run([ADB, "-s", serial, "shell", "pm", "path", "android"]).startswith("package:"):
        raise RuntimeError("Assigned package manager is not ready")
    return serial


def evidence(record, name, value):
    path = Path(record["evidence_directory"]) / name
    path.write_text(value, encoding="utf-8")
    return str(path)


def stop_locked(record):
    row, before = inventory()
    identities = list(record.get("runtime_processes", []))
    for current in processes(row[5:7]):
        if not any(same_process(current, old) for old in identities):
            identities.append(current)
    record.update(state="stopping", shutdown_ready=True, evidence_saved=True, runtime_processes=identities)
    write(RECORD, record)  # Save original identities before quit; retain them even after native PID fields disappear.
    run([LD, "quit", "--index", INDEX], timeout=30)
    deadline = time.monotonic() + 120
    while True:
        row, after = inventory()
        remaining = alive(identities)
        native = run([LD, "isrunning", "--index", INDEX])
        if row[4] == "0" and int(row[5]) <= 0 and int(row[6]) <= 0 and native == "stop" and not remaining:
            record.update(state="stopped-released", shutdown_verified=True, owner=None, reservedKiB=0)
            write(RECORD, record)
            evidence(record, "shutdown.json", json.dumps({"generation": record["claim_generation"], "before": before,
                     "after": after, "isrunning": native, "original_processes": identities, "remaining": remaining,
                     "verified": True}, indent=2))
            return record
        if time.monotonic() >= deadline:
            record.update(state="shutdown_failed", shutdown_verified=False, reservedKiB=BUDGET)
            write(RECORD, record)
            evidence(record, "shutdown-failed.json", json.dumps({"native": native, "inventory": after, "remaining": remaining}, indent=2))
            raise RuntimeError("Shutdown unconfirmed; ownership/reservation retained and restart blocked")
        time.sleep(2)


def start(directory):
    directory = Path(directory).resolve()
    approved = Path(os.environ["LOCALAPPDATA"]) / "Temp/opencode/launcher-reliability-runtime"
    if not directory.is_relative_to(approved.resolve()):
        raise RuntimeError("Evidence must stay under the approved launcher runtime directory")
    with allocation():
        record = read(RECORD)
        row, native = inventory()
        if (record.get("owner") is not None or not record.get("shutdown_verified")
                or record.get("handoff_to") is not None or record.get("keep_running")
                or row[4] != "0" or int(row[5]) > 0 or int(row[6]) > 0 or alive(record.get("runtime_processes", []))):
            raise RuntimeError("Target is not verifiably stopped, released and free of handoff")
        records = [read(path) for path in REGISTRY.glob("*.json") if path.name != "allocation-owner.json"]
        if any(other.get("state") in ("shutdown_failed", "launch_failed") for other in records):
            raise RuntimeError("Registry contains an unresolved runtime obligation; launch blocked")
        reservations = sum(int(other.get("reservedKiB", 0)) for other in records if other.get("name") != NAME)
        available = int(ps_json("(Get-CimInstance Win32_OperatingSystem).FreePhysicalMemory | ConvertTo-Json"))
        if available <= 0 or available - reservations < BUDGET:
            raise RuntimeError(f"RAM admission blocked: available={available} KiB, reservations={reservations}, need={BUDGET}")
        directory.mkdir(parents=True, exist_ok=True)
        generation = str(uuid.uuid4())
        record.update(owner=OWNER, generation=generation, claim_generation=generation, worktree=str(ROOT),
                      state="starting", shutdown_ready=False, evidence_saved=False, shutdown_verified=False,
                      keep_running=False, handoff_to=None, availableKiB=available, reservedKiB=BUDGET,
                      evidence_directory=str(directory), runtime_processes=[], serial=None,
                      results=[],
                      source_commit=run(["git", "rev-parse", "HEAD"]))
        write(RECORD, record)
        evidence(record, "admission.json", json.dumps({"availableKiB": available, "reservationsKiB": reservations,
                 "requiredKiB": BUDGET, "generation": generation, "inventory": native}, indent=2))
        try:
            run([LD, "launch", "--index", INDEX], timeout=30)
            deadline = time.monotonic() + 180
            last_error = ""
            while time.monotonic() < deadline:
                try:
                    serial = ready(record)
                    row, _ = inventory()
                    identities = processes(row[5:7])
                    if len(identities) != 2:
                        raise RuntimeError("Player and VM process identities are not both observable")
                    record.update(state="running", serial=serial, api="28", runtime_processes=identities, reservedKiB=0)
                    write(RECORD, record)
                    evidence(record, "claim.json", json.dumps(record, indent=2))
                    return record
                except (RuntimeError, subprocess.TimeoutExpired) as error:
                    last_error = str(error)
                    time.sleep(2)
            raise RuntimeError("Bounded boot failed: " + last_error)
        except Exception:
            stop_locked(record)
            raise


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=["start", "verify", "install", "test", "stop"])
    parser.add_argument("--generation")
    parser.add_argument("--evidence-dir")
    parser.add_argument("--app")
    parser.add_argument("--test-apk")
    parser.add_argument("--class-filter")
    parser.add_argument("--label", default="instrumentation")
    args = parser.parse_args()
    if args.action == "start":
        if not args.evidence_dir:
            parser.error("start requires --evidence-dir")
        record = start(args.evidence_dir)
    elif args.action == "stop":
        with allocation():
            record = claimed(args.generation)
            try:
                record = stop_locked(record)
            except Exception:
                # A timeout/inspection error is not shutdown proof. Retain original process identities and block any restart.
                record = read(RECORD)
                record.update(state="shutdown_failed", shutdown_verified=False, reservedKiB=BUDGET)
                write(RECORD, record)
                raise
    else:
        record = claimed(args.generation)
        serial = ready(record)
        if args.action == "install":
            artifacts = []
            for supplied in (args.app, args.test_apk):
                if not supplied:
                    parser.error("install requires --app and --test-apk")
                apk = Path(supplied).resolve()
                if not apk.is_relative_to((ROOT / "build/outputs/apk").resolve()):
                    raise RuntimeError("APK must be from this worktree's build outputs")
                artifacts.append({"path": str(apk), "sha256": hashlib.sha256(apk.read_bytes()).hexdigest()})
            for artifact in artifacts:
                # Revalidate the complete claim/native serial/API/readiness immediately before each mutating install.
                ready(claimed(args.generation))
                artifact["install"] = run([ADB, "-s", serial, "install", "-r", "-t", artifact["path"]], timeout=120)
            evidence(record, "artifacts.json", json.dumps(artifacts, indent=2))
            for package in ("app.lawnchair.debug", "app.lawnchair.debug.test"):
                evidence(record, package + ".txt", run([ADB, "-s", serial, "shell", "dumpsys", "package", package]))
        elif args.action == "test":
            command = [ADB, "-s", serial, "shell", "am", "instrument", "-w", "-r"]
            if args.class_filter:
                command += ["-e", "class", args.class_filter]
            command += ["app.lawnchair.debug.test/androidx.test.runner.AndroidJUnitRunner"]
            path = Path(record["evidence_directory"]) / (args.label + ".log")
            with path.with_suffix(".logcat.log").open("w", encoding="utf-8") as capture:
                logger = subprocess.Popen([str(ADB), "-s", serial, "logcat", "-v", "threadtime"], stdout=capture, stderr=subprocess.STDOUT)
                try:
                    ready(claimed(args.generation))
                    with path.open("w", encoding="utf-8") as output:
                        result = subprocess.run(list(map(str, command)), stdout=output, stderr=subprocess.STDOUT, timeout=240)
                finally:
                    logger.terminate()
                    logger.wait(timeout=10)
            text = path.read_text(encoding="utf-8")
            passed = result.returncode == 0 and re.search(r"OK \((\d+) tests?\)", text)
            skipped = len(re.findall(r"INSTRUMENTATION_STATUS_CODE: -(?:3|4)", text))
            success = bool(passed and (args.class_filter or passed.group(1) == "18") and not skipped)
            record = claimed(args.generation)
            record["results"].append({"passed": success, "tests": int(passed.group(1)) if passed else None,
                                     "skipped": skipped, "log": str(path), "class_filter": args.class_filter})
            write(RECORD, record)
            if not success:
                print(text[-14000:])
                raise SystemExit(f"Runtime RED or incomplete; full evidence: {path}")
            print(f"{passed.group(0)}; log={path}")
    print(json.dumps({key: record.get(key) for key in ("owner", "claim_generation", "state", "serial", "shutdown_verified", "evidence_directory")}))


if __name__ == "__main__":
    main()
