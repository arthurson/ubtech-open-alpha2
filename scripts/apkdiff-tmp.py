import zipfile, os
OLD = r"C:\Users\user\Desktop\open-alpha2-beta6.apk"
NEW = r"C:\Users\user\usb_driver\Downloads\ubtech-open-alpha2-beta5\app\build\outputs\apk\debug\app-debug.apk"
zo = zipfile.ZipFile(OLD)
zn = zipfile.ZipFile(NEW)
so = {n: zo.getinfo(n) for n in zo.namelist()}
sn = {n: zn.getinfo(n) for n in zn.namelist()}

def cat(n):
    if n.startswith("lib/"): return "lib/"
    if n.startswith("assets/"): return "assets/"
    if n == "classes.dex": return "classes.dex"
    return "other"

print("=== compressed size by category (old -> new) ===")
cats = sorted(set([cat(n) for n in list(so) + list(sn)]))
for c in cats:
    a = sum(so[n].compress_size for n in so if cat(n) == c)
    b = sum(sn[n].compress_size for n in sn if cat(n) == c)
    print("%-12s %8.2f MB -> %8.2f MB  (delta %+7.2f MB)" % (c, a / 1048576.0, b / 1048576.0, (b - a) / 1048576.0))

print()
print("=== files only in NEW (added, >20KB compressed) ===")
for n in sorted(sn, key=lambda n: sn[n].compress_size, reverse=True):
    if n not in so and sn[n].compress_size > 20480:
        print("%8.2f MB(c) %8.2f MB(u)  %s" % (sn[n].compress_size / 1048576.0, sn[n].file_size / 1048576.0, n))

print()
print("=== files only in OLD (removed, >20KB compressed) ===")
for n in sorted(so, key=lambda n: so[n].compress_size, reverse=True):
    if n not in sn and so[n].compress_size > 20480:
        print("%8.2f MB(c) %8.2f MB(u)  %s" % (so[n].compress_size / 1048576.0, so[n].file_size / 1048576.0, n))

print()
print("=== biggest growth in common files (delta compressed >50KB) ===")
rows = []
for n in sn:
    if n in so:
        d = sn[n].compress_size - so[n].compress_size
        if d > 51200:
            rows.append((d, n, so[n].compress_size, sn[n].compress_size))
for d, n, a, b in sorted(rows, reverse=True)[:15]:
    print("%+8.2f MB  %8.2f -> %8.2f MB(c)  %s" % (d / 1048576.0, a / 1048576.0, b / 1048576.0, n))

print()
print("old file: %.2f MB   new file: %.2f MB" % (os.path.getsize(OLD) / 1048576.0, os.path.getsize(NEW) / 1048576.0))
