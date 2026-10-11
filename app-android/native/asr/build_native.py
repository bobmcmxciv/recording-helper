"""Build libasrnative.so (arm64) with the NDK: asr_native.cpp + minimp3 + opencore-amr + fdk-aac decoders (no autotools).

Run from a directory that contains asr_native.cpp plus the opencore-amr and fdk-aac source trees. Source lists come from opencore-amr's Makefile.am files, decoder and
common parts only (the AMR-NB encoder is compiled out with DISABLE_AMRNB_ENCODER), and from fdk-aac's Makefile.am
without its encoder groups.
"""
import concurrent.futures
import os
import re
import subprocess
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
OC = os.path.join(HERE, 'opencore-amr-0.1.6')
FDK = os.path.join(HERE, 'fdk-aac-2.0.3')
FDK_LIBS = ['libAACdec', 'libArithCoding', 'libDRCdec', 'libSACdec', 'libSBRdec', 'libMpegTPDec', 'libSYS', 'libFDK',
            'libPCMutils']
NDK_ROOT = os.environ.get('ANDROID_NDK_HOME')
if not NDK_ROOT:
    raise SystemExit('Set ANDROID_NDK_HOME to your Android NDK r28c directory')
NDK = os.path.join(NDK_ROOT, 'toolchains', 'llvm', 'prebuilt', 'windows-x86_64', 'bin')
CXX = os.path.join(NDK, 'clang++.exe')
TARGET = ['--target=aarch64-linux-android31']
OBJ = os.path.join(HERE, 'obj')
OUT = os.path.join(HERE, 'out', 'libasrnative.so')
# hidden by default: only the JNIEXPORT entry points are exported, not opencore's hundreds of internal symbols
COMMON_FLAGS = TARGET + ['-O3', '-fPIC', '-fvisibility=hidden', '-ffunction-sections', '-fdata-sections', '-Wno-everything']


def sources(makefile, wanted_vars):
    """Expand $(VAR)/x.cpp entries of the given automake variables (only lines inside decoder/common blocks)."""
    # drop comment lines: they name files the upstream build deliberately leaves out
    text = '\n'.join(l for l in open(makefile, encoding='utf-8').read().splitlines() if not l.lstrip().startswith('#'))
    vars_ = dict(re.findall(r'^(\w+)\s*=\s*([^\n\\]+)$', text, re.M))
    vars_['top_srcdir'] = OC

    def expand(s):
        for _ in range(5):
            s = re.sub(r'\$\((\w+)\)', lambda m: vars_.get(m.group(1), m.group(0)), s)
        return s

    out = []
    for block in re.split(r'\n(?=if |else|endif|\w+_SOURCES)', text):
        head = block.strip().splitlines()[0] if block.strip() else ''
        if head.startswith('if AMRNB_ENCODER'):
            continue
        for path in re.findall(r'(\$\(\w+\)/[\w./]+\.cpp|\bwrapper\.cpp)', block):
            if any(v in path for v in wanted_vars) or path == 'wrapper.cpp':
                out.append(expand(path) if path != 'wrapper.cpp' else os.path.join(os.path.dirname(makefile), 'wrapper.cpp'))
    seen, uniq = set(), []
    for p in out:
        p = os.path.normpath(p)
        if p not in seen:
            seen.add(p)
            uniq.append(p)
    return uniq


def fdk_sources():
    """Decoder-side source groups of fdk-aac's Makefile.am (every *_SRC whose name has no ENC)."""
    text = open(os.path.join(FDK, 'Makefile.am'), encoding='utf-8').read().replace('\\\n', ' ')
    out = []
    for name, files in re.findall(r'^(\w+)_SRC\s*=\s*(.+)$', text, re.M):
        if 'ENC' not in name:
            out += [os.path.normpath(os.path.join(FDK, f)) for f in files.split()]
    return out


def compile_one(args):
    src, obj, flags = args
    if os.path.exists(obj) and os.path.getmtime(obj) > os.path.getmtime(src):
        return None
    r = subprocess.run([CXX] + COMMON_FLAGS + flags + ['-c', src, '-o', obj], capture_output=True, text=True)
    return None if r.returncode == 0 else f'{src}\n{r.stderr[-1500:]}'


def main():
    if '--clean' in sys.argv and os.path.isdir(OBJ):
        import shutil
        shutil.rmtree(OBJ)
    os.makedirs(OBJ, exist_ok=True)
    os.makedirs(os.path.dirname(OUT), exist_ok=True)
    gsm = os.path.join(OC, 'opencore', 'codecs_v2', 'audio', 'gsm_amr')
    nb_inc = ['-I' + os.path.join(OC, 'oscl'), '-I' + os.path.join(gsm, 'amr_nb', 'dec', 'src'),
              '-I' + os.path.join(gsm, 'amr_nb', 'common', 'include'), '-I' + os.path.join(gsm, 'amr_nb', 'dec', 'include'),
              '-I' + os.path.join(gsm, 'common', 'dec', 'include'), '-I' + os.path.join(gsm, 'amr_nb', 'enc', 'src'),
              '-DDISABLE_AMRNB_ENCODER']
    wb_inc = ['-I' + os.path.join(OC, 'oscl'), '-I' + os.path.join(gsm, 'amr_wb', 'dec', 'src'),
              '-I' + os.path.join(gsm, 'amr_wb', 'dec', 'include'), '-I' + os.path.join(gsm, 'common', 'dec', 'include')]
    nb = sources(os.path.join(OC, 'amrnb', 'Makefile.am'), ['DEC_SRC_DIR', 'COMMON_SRC_DIR'])
    wb = sources(os.path.join(OC, 'amrwb', 'Makefile.am'), ['DEC_SRC_DIR'])
    print('amrnb sources', len(nb), 'amrwb sources', len(wb), flush=True)
    jobs = [(s, os.path.join(OBJ, 'nb_' + os.path.basename(s) + '.o'), nb_inc) for s in nb]
    jobs += [(s, os.path.join(OBJ, 'wb_' + os.path.basename(s) + '.o'), wb_inc) for s in wb]
    fdk_inc = ['-I' + os.path.join(FDK, lib, 'include') for lib in FDK_LIBS] + ['-I' + os.path.join(HERE, 'compat')]
    fdk = fdk_sources()
    print('fdk-aac sources', len(fdk), flush=True)
    jobs += [(s, os.path.join(OBJ, 'aac_' + os.path.basename(s) + '.o'), fdk_inc + ['-fno-exceptions', '-fno-rtti'])
             for s in fdk]
    main_obj = os.path.join(OBJ, 'asr_native.o')
    jobs.append((os.path.join(HERE, 'asr_native.cpp'), main_obj,
                 ['-std=c++17', '-I' + HERE, '-I' + os.path.join(OC, 'amrnb'), '-I' + os.path.join(OC, 'amrwb')] + fdk_inc))
    errors = []
    with concurrent.futures.ThreadPoolExecutor(max_workers=6) as ex:
        for err in ex.map(compile_one, jobs):
            if err:
                errors.append(err)
    if errors:
        print('COMPILE ERRORS', len(errors))
        for e in errors[:5]:
            print(e)
        sys.exit(1)
    objs = [j[1] for j in jobs]
    r = subprocess.run([CXX] + TARGET + ['-shared', '-o', OUT] + objs +
                       ['-static-libstdc++', '-Wl,--gc-sections', '-Wl,-z,max-page-size=16384', '-Wl,--exclude-libs,ALL',
                        '-Wl,-s', '-lm', '-llog'], capture_output=True, text=True)
    if r.returncode != 0:
        print('LINK FAILED', r.stderr[-3000:])
        sys.exit(1)
    print('built', OUT, os.path.getsize(OUT))


if __name__ == '__main__':
    main()
