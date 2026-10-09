import struct

with open(r"C:\Users\Growth\.gemini\antigravity\scratch\kiwixbb10\scratch\sample.zim", "rb") as f:
    header = f.read(80)

magic, major, minor = struct.unpack("<IHH", header[:8])
uuid = header[8:24]
article_count, cluster_count = struct.unpack("<II", header[24:32])
url_ptr_pos, title_ptr_pos, cluster_ptr_pos, mime_list_pos = struct.unpack("<QQQQ", header[32:64])
main_page, layout_page, checksum_pos = struct.unpack("<IIQ", header[64:80])

print(f"Magic: 0x{magic:x} (Expected: 0x44d495a)")
print(f"Version: {major}.{minor}")
print(f"Articles: {article_count}, Clusters: {cluster_count}")
print(f"urlPtrPos: {url_ptr_pos}, titlePtrPos: {title_ptr_pos}")
print(f"clusterPtrPos: {cluster_ptr_pos}, mimeListPos: {mime_list_pos}")
print(f"mainPage: {main_page}, layoutPage: {layout_page}, checksumPos: {checksum_pos}")

# Read MIME types
f = open(r"C:\Users\Growth\.gemini\antigravity\scratch\kiwixbb10\scratch\sample.zim", "rb")
f.seek(mime_list_pos)
mimes = []
while True:
    b = bytearray()
    while True:
        c = f.read(1)
        if not c or c == b'\x00':
            break
        b.extend(c)
    if not b:
        break
    mimes.append(b.decode('utf-8'))
print("MIME types:", mimes)

# Read first few URL pointers and entries
print("\nFirst 5 entries:")
for i in range(min(5, article_count)):
    f.seek(url_ptr_pos + i * 8)
    offset = struct.unpack("<Q", f.read(8))[0]
    f.seek(offset)
    mime_type_idx, param_len, ns, rev = struct.unpack("<HBB I", f.read(8))
    ch_ns = chr(ns)
    if mime_type_idx == 0xffff:
        # redirect
        redirect_idx = struct.unpack("<I", f.read(4))[0]
        extra = f"REDIRECT -> {redirect_idx}"
    else:
        cluster_no, blob_no = struct.unpack("<II", f.read(8))
        extra = f"Cluster: {cluster_no}, Blob: {blob_no}"
    
    # URL (null-terminated)
    u = bytearray()
    while True:
        c = f.read(1)
        if not c or c == b'\x00': break
        u.extend(c)
    # Title (null-terminated)
    t = bytearray()
    while True:
        c = f.read(1)
        if not c or c == b'\x00': break
        t.extend(c)
    print(f"[{i}] offset={offset} mime_idx={mime_type_idx} ns='{ch_ns}' url='{u.decode('utf-8', errors='ignore')}' title='{t.decode('utf-8', errors='ignore')}' | {extra}")

# Check first cluster compression
f.seek(cluster_ptr_pos)
c0_offset = struct.unpack("<Q", f.read(8))[0]
f.seek(c0_offset)
comp_byte = f.read(1)[0]
print(f"\nCluster 0 offset={c0_offset}, compression byte={comp_byte}")
f.close()
