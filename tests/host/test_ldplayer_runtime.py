"""Host-only guard regressions; no test invokes LDPlayer, ADB, CIM, or the live registry."""

from contextlib import nullcontext
import importlib.util
from pathlib import Path
import sys
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
spec = importlib.util.spec_from_file_location("runtime", Path(__file__).resolve().parents[2] / "tools/lawnchair_ldplayer_runtime.py")
runtime = importlib.util.module_from_spec(spec)
spec.loader.exec_module(runtime)


class RuntimeGuardsTest(unittest.TestCase):
    def test_reused_pid_with_a_new_creation_time_is_not_the_original_vm(self):
        old = {"ProcessId": 42, "CreationDate": "2026-11-16T01:00:00+00:00"}
        new = {"ProcessId": 42, "CreationDate": "2026-11-16T02:00:00+00:00"}
        self.assertFalse(runtime.same_process(old, new))
        self.assertTrue(runtime.same_process(old, {**old, "CreationDate": "2026-11-16T02:00:00+01:00"}))

    def test_changed_owner_or_generation_rejects_device_use_before_inventory(self):
        with patch.object(runtime, "read", return_value={"owner": "another-worker"}), patch.object(runtime, "inventory") as inventory:
            with self.assertRaisesRegex(RuntimeError, "exclusive claim"):
                runtime.claimed("expected-generation")
            inventory.assert_not_called()

    def test_native_name_cannot_authorize_a_reused_index(self):
        with patch.object(runtime, "run", return_value="3," + runtime.NAME + ",0,0,0,-1,-1,1280,720,240"):
            with self.assertRaisesRegex(RuntimeError, "name/index"):
                runtime.inventory()

    def test_ambiguous_serial_never_reaches_sdk_adb(self):
        with patch.object(runtime, "run", return_value="emulator-5562\nemulator-5554") as run:
            with self.assertRaisesRegex(RuntimeError, "serial"):
                runtime.ready({})
            self.assertEqual(1, run.call_count)
            self.assertEqual(runtime.LD, run.call_args.args[0][0])

    def test_start_refuses_unreleased_or_handed_off_instance_without_writing(self):
        base = {"owner": None, "shutdown_verified": True, "handoff_to": None, "keep_running": False}
        for changed in ({"owner": "another-worker"}, {"handoff_to": "another-worker"}, {"shutdown_verified": False}):
            with self.subTest(changed=changed), patch.object(runtime, "allocation", return_value=nullcontext()), patch.object(runtime, "read", return_value={**base, **changed}), patch.object(runtime, "inventory", return_value=(["4", runtime.NAME, "0", "0", "0", "-1", "-1"], "inventory")), patch.object(runtime, "write") as write, patch.object(runtime, "run") as run:
                with self.assertRaisesRegex(RuntimeError, "stopped, released"):
                    runtime.start(runtime.Path(runtime.os.environ["LOCALAPPDATA"]) / "Temp/opencode/launcher-reliability-runtime/test-guards")
                write.assert_not_called()
                run.assert_not_called()


if __name__ == "__main__":
    unittest.main()
