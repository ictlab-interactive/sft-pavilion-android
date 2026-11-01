#!/usr/bin/env python3
"""Reproducible updater overlay for the recovered, hash-pinned v1.2.6 APK.

Needs Python 3 and JDK 17. Private key arguments are file paths, never literals.
The APK is not published by this script. Read README.md before signing.
"""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent
CACHE = ROOT / '.cache'
BUILD = ROOT / 'build'
DIST = ROOT / 'dist'
PACKAGE = 'com.sftpavilion.reminderfix'
TOOLS = {
    'base.apk': ('https://github.com/ictlab-interactive/sft-pavilion-android/releases/download/v1.2.6/SFT-Pavilion.apk',
                 '4d5cd06f1443041333e13070fbd50dac74a0dceb375433a55694d3854077c591'),
    'apktool.jar': ('https://github.com/iBotPeaches/Apktool/releases/download/v3.0.3/apktool_3.0.3.jar',
                    'dbf930b076c6b9be08d57c449cacefc3bdd6b71ebd59b3066fc0e1f5b14f9423'),
    'build-tools.zip': ('https://dl.google.com/android/repository/build-tools_r35_linux.zip',
                        'bd3a4966912eb8b30ed0d00b0cda6b6543b949d5ffe00bea54c04c81e1561d88'),
    'platform.zip': ('https://dl.google.com/android/repository/platform-35_r02.zip',
                     '0988cacad01b38a18a47bac14a0695f246bc76c1b06c0eeb8eb0dc825ab0c8e0'),
}

def sha(path):
    h = hashlib.sha256()
    with open(path, 'rb') as f:
        for chunk in iter(lambda: f.read(1048576), b''):
            h.update(chunk)
    return h.hexdigest()

def run(*args):
    subprocess.run([str(x) for x in args], check=True, cwd=ROOT)

def download_tools():
    CACHE.mkdir(exist_ok=True)
    for name, (url, digest) in TOOLS.items():
        dest = CACHE / name
        if not dest.exists() or sha(dest) != digest:
            baseline = ROOT / 'baseline/SFT-Pavilion-v1.2.6.apk'
            if name == 'base.apk' and baseline.exists():
                if sha(baseline) != digest:
                    raise RuntimeError('Bundled baseline APK checksum mismatch')
                shutil.copyfile(baseline, dest)
                continue
            print('Fetching', name, flush=True)
            request = urllib.request.Request(url, headers={'User-Agent': 'SFT-Pavilion-Build/1'})
            temp = dest.with_suffix('.part')
            with urllib.request.urlopen(request, timeout=60) as response, temp.open('wb') as out:
                shutil.copyfileobj(response, out)
            if sha(temp) != digest:
                temp.unlink()
                raise RuntimeError('SHA-256 mismatch for ' + name)
            temp.replace(dest)
    sdk = CACHE / 'sdk'
    sdk.mkdir(exist_ok=True)
    if not (sdk / 'android-15/lib/d8.jar').exists():
        with zipfile.ZipFile(CACHE / 'build-tools.zip') as z:
            z.extractall(sdk)
    android = CACHE / 'android35.jar'
    if not android.exists():
        with zipfile.ZipFile(CACHE / 'platform.zip') as z:
            name = next(n for n in z.namelist() if n.endswith('/android.jar'))
            android.write_bytes(z.read(name))

def patch_decoded(decoded, release):
    manifest = decoded / 'AndroidManifest.xml'
    text = manifest.read_text()
    anchor = '    <application '
    if text.count(anchor) != 1:
        raise RuntimeError('Unexpected baseline manifest')
    text = text.replace(anchor, '    <uses-permission android:name="android.permission.REQUEST_INSTALL_PACKAGES"/>\n' + anchor)
    provider = ('        <provider android:name="com.sftpavilion.updates.UpdateFileProvider" '
                'android:authorities="' + PACKAGE + '.updates" android:exported="false" '
                'android:grantUriPermissions="true"/>\n')
    text = text.replace('    </application>', provider + '    </application>')
    manifest.write_text(text)
    yml = decoded / 'apktool.yml'
    text = yml.read_text()
    text, count = re.subn(r'(?m)^  versionCode: 9$', '  versionCode: ' + str(release['versionCode']), text)
    if count != 1:
        raise RuntimeError('Unexpected baseline version')
    text = text.replace('  versionName: 1.2.6', '  versionName: ' + release['versionName'])
    text = re.sub(r'(?m)^  minSdkVersion: 23$', '  minSdkVersion: ' + str(release['minSdk']), text)
    yml.write_text(text)
    main = decoded / 'smali/com/sftpavilion/app/MainActivity.smali'
    text = main.read_text()
    # Preserve the original three-item navigation; hook only foreground lifecycle.
    for method in ['onResume', 'onPause']:
        start = text.index('.method protected ' + method + '()V')
        end = text.index('.end method', start)
        part = text[start:end]
        if part.count('    return-void') != 1:
            raise RuntimeError('Unexpected lifecycle baseline: ' + method)
        part = part.replace('    return-void',
            '    invoke-static {p0}, Lcom/sftpavilion/updates/UpdateManager;->' + method + '(Landroid/app/Activity;)V\n\n    return-void')
        text = text[:start] + part + text[end:]
    main.write_text(text)
    config = decoded / 'smali/com/sftpavilion/app/BuildConfig.smali'
    text = config.read_text().replace('APPLICATION_ID:Ljava/lang/String; = "com.sftpavilion.app"',
                                   'APPLICATION_ID:Ljava/lang/String; = "' + PACKAGE + '"')
    text = re.sub(r'VERSION_CODE:I = 0x2\b', 'VERSION_CODE:I = ' + hex(release['versionCode']), text)
    text = text.replace('VERSION_NAME:Ljava/lang/String; = "1.1.0"', 'VERSION_NAME:Ljava/lang/String; = "' + release['versionName'] + '"')
    config.write_text(text)

def build(args):
    release = json.loads((ROOT / 'release.json').read_text())
    if not isinstance(release['versionCode'], int) or release['versionCode'] <= 9:
        raise RuntimeError('Version code must be an integer greater than 9')
    if not re.fullmatch(r'[0-9]{1,5}\.[0-9]{1,5}\.[0-9]{1,5}', release['versionName']):
        raise RuntimeError('Version name must be a numeric major.minor.patch')
    if not 23 <= release['minSdk'] <= 100:
        raise RuntimeError('Invalid minimum SDK')
    if len(release.get('releaseNotes', '')) > 6000:
        raise RuntimeError('Release notes are too long')
    download_tools()
    if BUILD.exists():
        shutil.rmtree(BUILD)
    BUILD.mkdir()
    DIST.mkdir(exist_ok=True)
    # Fail closed: a failed rebuild must not leave a previous APK looking current.
    for name in ['SFT-Pavilion.apk', 'SFT-Pavilion.apk.idsig', 'update.json']:
        (DIST / name).unlink(missing_ok=True)
    decoded = BUILD / 'decoded'
    run('java', '-jar', CACHE / 'apktool.jar', 'd', '-f', CACHE / 'base.apk', '-o', decoded)
    patch_decoded(decoded, release)
    classes = BUILD / 'classes'
    classes.mkdir()
    android = CACHE / 'android35.jar'
    source = sorted((ROOT / 'src').rglob('*.java'))
    run('java', '-m', 'jdk.compiler/com.sun.tools.javac.Main', '-source', '8', '-target', '8',
        '-encoding', 'UTF-8', '-classpath', android, '-d', classes, *source)
    class_jar = BUILD / 'updater.jar'
    with zipfile.ZipFile(class_jar, 'w', zipfile.ZIP_DEFLATED) as z:
        for p in sorted(classes.rglob('*.class')):
            z.write(p, p.relative_to(classes))
    dex = BUILD / 'dex'
    dex.mkdir()
    tools = CACHE / 'sdk/android-15'
    run('java', '-cp', tools / 'lib/d8.jar', 'com.android.tools.r8.D8', '--release', '--min-api',
        str(release['minSdk']), '--lib', android, '--output', dex, class_jar)
    base = BUILD / 'patched-base.apk'
    run('java', '-jar', CACHE / 'apktool.jar', 'b', decoded, '-o', base)
    unsigned = BUILD / 'unsigned.apk'
    with zipfile.ZipFile(base) as src, zipfile.ZipFile(unsigned, 'w', zipfile.ZIP_DEFLATED) as out:
        for entry in src.infolist():
            if not entry.filename.startswith('META-INF/'):
                out.writestr(entry, src.read(entry.filename))
        out.write(dex / 'classes.dex', 'classes2.dex')
    aligned = BUILD / 'aligned.apk'
    align = tools / 'zipalign'
    align.chmod(0o755)
    run(align, '-f', '-P', '16', '4', unsigned, aligned)
    signed = DIST / 'SFT-Pavilion.apk'
    if not args.keystore or not args.old_key or not args.old_cert or not args.lineage:
        print('Unsigned build created:', aligned)
        print('Signing requires --keystore, --old-key, --old-cert and --lineage. No release APK was produced.')
        return
    if not os.environ.get('SFT_KEYSTORE_PASSWORD'):
        raise RuntimeError('Set SFT_KEYSTORE_PASSWORD in the build environment')
    signer = tools / 'lib/apksigner.jar'
    run('java', '-jar', signer, 'sign', '--min-sdk-version', str(release['minSdk']),
        '--rotation-min-sdk-version', '28', '--lineage', Path(args.lineage).resolve(),
        '--key', Path(args.old_key).resolve(), '--cert', Path(args.old_cert).resolve(),
        '--next-signer', '--ks', Path(args.keystore).resolve(), '--ks-key-alias', 'sft-release',
        '--ks-pass', 'env:SFT_KEYSTORE_PASSWORD', '--out', signed, aligned)
    run('java', '-jar', signer, 'verify', '--verbose', '--print-certs', signed)
    metadata = dict(release, packageName=PACKAGE, size=signed.stat().st_size, sha256=sha(signed))
    (DIST / 'update.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n')
    print('Release files:', signed, DIST / 'update.json')

if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--keystore', help='Path to the PRIVATE release JKS')
    p.add_argument('--old-key', help='Path to the legacy public AOSP test key PK8')
    p.add_argument('--old-cert', help='Path to the legacy AOSP certificate PEM')
    p.add_argument('--lineage', help='Path to the saved rotation lineage')
    build(p.parse_args())
