import urllib.request
import re

url = "https://download.kiwix.org/zim/wikipedia/"
req = urllib.request.Request(url, headers={"User-Agent": "Mozilla/5.0"})
try:
    with urllib.request.urlopen(req) as resp:
        html = resp.read().decode("utf-8")
    # match rows with size
    rows = re.findall(r'<a href="([^"]+\.zim)">[^<]+</a>\s+([0-9A-Za-z- :]+)\s+([0-9.]+[KMG])', html)
    # Sort by size
    small = []
    for f, d, s in rows:
        if 'M' in s and float(s.replace('M','')) < 20:
            small.append((float(s.replace('M','')), f))
        elif 'K' in s:
            small.append((0.001 * float(s.replace('K','')), f))
    small.sort()
    for sz, f in small[:10]:
        print(f"{sz} MB: {f}")
except Exception as e:
    print(e)
