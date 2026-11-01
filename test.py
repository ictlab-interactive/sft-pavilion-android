#!/usr/bin/env python3
"""Run update-policy regression checks on the JVM, without Android stub execution."""
from pathlib import Path
import hashlib
import shutil
import subprocess
import urllib.request

root = Path(__file__).resolve().parent
cache = root / '.cache'
cache.mkdir(exist_ok=True)
jar = cache / 'json.jar'
# org.json's reference implementation; only used for JVM tests, never packaged.
DIGEST = '3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'
if not jar.exists() or hashlib.sha256(jar.read_bytes()).hexdigest() != DIGEST:
    request = urllib.request.Request('https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar')
    with urllib.request.urlopen(request, timeout=30) as r:
        data = r.read()
    if hashlib.sha256(data).hexdigest() != DIGEST:
        raise RuntimeError('org.json test dependency checksum mismatch')
    jar.write_bytes(data)
classes = root / 'build/policy-tests'
classes.mkdir(parents=True, exist_ok=True)
subprocess.run(['java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-cp', str(jar), '-d', str(classes),
                str(root / 'src/com/sftpavilion/updates/ReleaseInfo.java'), str(root / 'tests/ReleaseInfoTest.java')], check=True)
subprocess.run(['java', '-cp', str(classes) + ':' + str(jar), 'ReleaseInfoTest'], check=True)
