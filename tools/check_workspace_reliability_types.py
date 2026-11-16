"""Focused mixed Kotlin/Java type check against an existing coordinator Android build; never invokes Gradle."""

import os
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
JAVA = Path(os.environ.get("JAVA_HOME", "C:/Program Files/Java/jdk-21")) / "bin"
CACHE = Path.home() / ".gradle/caches"
OUTPUT = Path(tempfile.gettempdir()) / "opencode/lawnchair-focused-typecheck"
OUTPUT.mkdir(parents=True, exist_ok=True)
KOTLIN = OUTPUT / "kotlin"
CLASSES = OUTPUT / "java"
KOTLIN.mkdir(exist_ok=True)
CLASSES.mkdir(exist_ok=True)

changed = subprocess.check_output([
    "git", "diff", "511824997f84f212ffbb3de9ad6ee222481b24e7", "--name-only",
], cwd=ROOT, text=True).splitlines()
sources = [ROOT / name for name in changed if name.endswith((".java", ".kt")) and not name.startswith("tests/")]
# Kotlin requires a sealed superclass and its subclasses to be compiled in the same module, even for a focused check.
if ROOT / "lawnchair/src/app/lawnchair/preferences/PreferenceManager.kt" in sources:
    sources.append(ROOT / "lawnchair/src/app/lawnchair/preferences/BasePreferenceManager.kt")
    sources.append(ROOT / "lawnchair/src/app/lawnchair/preferences/PrefEntry.kt")
if not sources:
    raise SystemExit("No correction sources found")

cached_jars = sorted((CACHE / "modules-2/files-2.1").rglob("*.jar"))
compiler = next(p for p in cached_jars if "kotlin-compiler-embeddable/2.3.0/" in p.as_posix())
compose_plugin = next(p for p in cached_jars if "kotlin-compose-compiler-plugin-embeddable/2.3.0/" in p.as_posix())
compiler_cp = [compiler] + [p for p in cached_jars if p != compiler]
dependencies = list((CACHE / "9.3.0/transforms").rglob("classes.jar")) + cached_jars
existing_classes = [
    ROOT / "build/tmp/kotlin-classes/lawnWithQuickstepGithubDebug",
    ROOT / "build/intermediates/javac/lawnWithQuickstepGithubDebug/compileLawnWithQuickstepGithubDebugJavaWithJavac/classes",
]
existing_classes += [p for p in ROOT.glob("**/build/intermediates/javac/**/classes") if "AndroidTest" not in str(p)]
existing_classes += [p for p in ROOT.glob("**/build/tmp/kotlin-classes/*") if p.is_dir() and "AndroidTest" not in str(p)]
r_jars = [p for p in ROOT.glob("**/build/intermediates/**/R.jar") if "AndroidTest" not in str(p)]
classpath = [KOTLIN, CLASSES, ROOT / "prebuilts/libs/framework-15.jar"] + existing_classes + r_jars + dependencies


def quote(value):
    return '"' + str(value).replace("\\", "/").replace('"', '\\"') + '"'


def invoke(executable, name, arguments):
    args_file = OUTPUT / (name + ".args")
    args_file.write_text("\n".join(quote(argument) for argument in arguments), encoding="utf-8")
    result = subprocess.run([str(executable), "@" + str(args_file)], cwd=ROOT, capture_output=True, text=True, errors="replace")
    log = OUTPUT / (name + ".log")
    log.write_text(result.stdout + result.stderr, encoding="utf-8")
    if result.returncode:
        print("\n".join((result.stdout + result.stderr).splitlines()[-100:]))
        raise SystemExit(f"{name} failed ({result.returncode}); full output: {log}")
    print(f"PASS {name}; log: {log}")


invoke(JAVA / "java", "focused-kotlin", [
    "-Xmx1024m", "-cp", os.pathsep.join(map(str, compiler_cp)),
    "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "17",
    "-Xjvm-default=all", "-Xplugin=" + str(compose_plugin), "-classpath", os.pathsep.join(map(str, classpath)), "-d", KOTLIN,
] + sources)
java_sources = [p for p in sources if p.suffix == ".java"]
invoke(JAVA / "javac", "focused-java", [
    "-proc:none", "-source", "17", "-target", "17", "-encoding", "UTF-8", "-classpath",
    os.pathsep.join(map(str, classpath)), "-d", CLASSES,
] + java_sources)
print(f"Focused correction type check passed for {len(sources)} production files; not a final Android build or runtime test.")

test_sources = list((ROOT / "tests/shortcutReliability/src").rglob("*.java")) + list((ROOT / "tests/shortcutReliability/src").rglob("*.kt"))
test_kotlin = OUTPUT / "test-kotlin"
test_java = OUTPUT / "test-java"
test_kotlin.mkdir(exist_ok=True)
test_java.mkdir(exist_ok=True)
test_classpath = [test_kotlin, test_java] + classpath
invoke(JAVA / "java", "focused-test-kotlin", [
    "-Xmx1024m", "-cp", os.pathsep.join(map(str, compiler_cp)),
    "org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", "-no-stdlib", "-no-reflect", "-jvm-target", "17",
    "-classpath", os.pathsep.join(map(str, test_classpath)), "-d", test_kotlin,
] + test_sources)
invoke(JAVA / "javac", "focused-test-java", [
    "-proc:none", "-source", "17", "-target", "17", "-encoding", "UTF-8", "-classpath",
    os.pathsep.join(map(str, test_classpath)), "-d", test_java,
] + [p for p in test_sources if p.suffix == ".java"])
print("Focused canonical test-source type check passed; instrumentation was not executed.")
