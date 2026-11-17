#!/usr/bin/env python3
"""Collect native-image metadata from the full suite and both WordPress modes.

Uses only installed tools and an already-running database. Does not change WordPress.
The resulting files are merged into src/main/resources/META-INF/native-image.
"""
import argparse
import json
import os
from pathlib import Path
import socket
import subprocess
import time
import urllib.error
import urllib.request
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parent.parent
PAGES = ["/", "/?p=1", "/?cat=1", "/?s=hello", "/wp-login.php", "/?feed=rss2", "/?p=999"]


def prune_runner_metadata(output):
    # JAVA_TOOL_OPTIONS also traces the Maven parent. Retain only application,
    # runtime-dependency and JDK types/resources, not the test/build runner.
    classes = {str(p.relative_to(ROOT / "target/classes"))[:-6].replace("/", ".")
               for p in (ROOT / "target/classes").rglob("*.class")}
    resources = {str(p.relative_to(ROOT / "target/classes"))
                 for p in (ROOT / "target/classes").rglob("*") if p.is_file()}
    for jar in (ROOT / "target/lib").glob("*.jar"):
        with ZipFile(jar) as archive:
            resources.update(archive.namelist())
            classes.update(name[:-6].replace("/", ".") for name in archive.namelist()
                           if name.endswith(".class"))

    def keep_type(value):
        if isinstance(value, str):
            value = value.removesuffix("[]")
            return value in classes or value.startswith(("java.", "javax.", "jdk.", "sun.", "com.sun."))
        if "proxy" in value:
            return all(keep_type(name) for name in value["proxy"])
        if "lambda" in value:
            return keep_type(value["lambda"]["declaringClass"])
        return False

    path = output / "reachability-metadata.json"
    data = json.loads(path.read_text())
    data["reflection"] = [entry for entry in data.get("reflection", []) if keep_type(entry["type"])]
    data["resources"] = [entry for entry in data.get("resources", [])
                         if entry.get("module", "").startswith(("java.", "jdk."))
                         or entry.get("glob", "") in resources]
    path.write_text(json.dumps(data, indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("docroot", type=Path)
    parser.add_argument("--port", type=int, default=18088)
    parser.add_argument("--skip-tests", action="store_true", help="reuse completed test traces")
    args = parser.parse_args()
    graal = Path(os.environ.get("GRAALVM_HOME", ROOT / ".graalvm/current")).resolve()
    run = ROOT / "target/native-agent"
    run.mkdir(parents=True, exist_ok=True)
    # Do not include the Maven/JUnit runner's own reflection in the executable.
    caller_filter = run / "caller-filter.json"
    caller_filter.write_text(json.dumps({"rules": [
        {"includeClasses": "**"},
        {"excludeClasses": "org.apache.maven.**"},
        {"excludeClasses": "org.apache.maven.surefire.**"},
        {"excludeClasses": "org.junit.**"},
        {"excludeClasses": "org.codehaus.**"},
    ]}, indent=2) + "\n")
    env = os.environ.copy()
    env["JAVA_HOME"] = str(graal)
    env["PATH"] = str(graal / "bin") + os.pathsep + env["PATH"]
    # JDBC fixtures DROP and recreate tables, including wp_options. Never point
    # them at the WordPress database. They are skipped unless an isolated test
    # database is supplied explicitly; WordPress requests trace JDBC separately.
    if env.get("BLITZ_MYSQL_HOST"):
        if not env.get("BLITZ_MYSQL_DB") or env["BLITZ_MYSQL_DB"] in ("wp", "wpfresh"):
            parser.error("JDBC tests require an explicit isolated BLITZ_MYSQL_DB (not wp/wpfresh)")
    subprocess.run(["mvn", "-q", "-B", "-DskipTests", "package"], cwd=ROOT, check=True)
    for mode in (() if args.skip_tests else ("off", "auto")):
        agent = (f"-agentlib:native-image-agent=config-output-dir={run}/tests-{mode}-{{pid}},"
                 f"caller-filter-file={caller_filter}")
        test_env = env | {"JAVA_TOOL_OPTIONS": f"{agent} -Dblitz.bytecode={mode}"}
        subprocess.run(["mvn", "-q", "-B", "test"], cwd=ROOT, env=test_env, check=True)

    for mode in ("off", "auto"):
        # Check the port before launching; never reuse another process's listener.
        with socket.socket() as sock:
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(("127.0.0.1", args.port))
        agent = f"-agentlib:native-image-agent=config-output-dir={run}/wordpress-{mode}"
        cmd = [str(graal / "bin/java"), agent, "--enable-native-access=ALL-UNNAMED",
               "--sun-misc-unsafe-memory-access=allow", "-XX:-DontCompileHugeMethods",
               f"-Dblitz.bytecode={mode}", "-Djava.lang.invoke.MethodHandle.CUSTOMIZE_THRESHOLD=-1",
               "-cp", f"{ROOT}/target/classes:{ROOT}/target/lib/*", "dev.phpruntime.host.Main",
               "serve", "--port", str(args.port), "--cpus", "4", str(args.docroot.resolve())]
        with (run / f"wordpress-{mode}.log").open("wb") as log:
            server = subprocess.Popen(cmd, cwd=ROOT, stdout=log, stderr=log)
            try:
                for _ in range(300):
                    if server.poll() is not None:
                        raise RuntimeError(f"Server exited; see {log.name}")
                    try:
                        with socket.create_connection(("127.0.0.1", args.port), timeout=.2):
                            break
                    except OSError:
                        time.sleep(.2)
                else:
                    raise RuntimeError("Server did not start")
                for path in PAGES:
                    req = urllib.request.Request(f"http://127.0.0.1:{args.port}{path}",
                                                 headers={"Host": "localhost:18088"})
                    try:
                        response = urllib.request.urlopen(req, timeout=120)
                    except urllib.error.HTTPError as error:
                        response = error  # The missing post deliberately returns 404.
                    with response:
                        body = response.read()
                        print(f"{mode} {path}: HTTP {response.status}, {len(body)} bytes", flush=True)
                        if response.status >= 500:
                            raise RuntimeError(f"WordPress failed: {path}")
            finally:
                server.terminate()
                try:
                    server.wait(timeout=30)
                except subprocess.TimeoutExpired:
                    server.kill()
                    server.wait()
    output = ROOT / "src/main/resources/META-INF/native-image/dev.phpruntime/blitzvm"
    output.mkdir(parents=True, exist_ok=True)
    inputs = sorted(p.parent for p in run.glob("*/reachability-metadata.json"))
    inputs += sorted(p.parent for p in run.glob("*/reflect-config.json") if p.parent not in inputs)
    subprocess.run([str(graal / "bin/native-image-utils"), "generate",
                    *[f"--input-dir={path}" for path in inputs], f"--output-dir={output}"], check=True)
    prune_runner_metadata(output)
    print(f"Merged {len(inputs)} traces into {output}")


if __name__ == "__main__":
    main()
