package com.kiwixbb10;

import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure Java ZIM file reader.
 *
 * ZIM format spec: https://openzim.org/wiki/ZIM_file_format
 *
 * Layout:
 *   Header (80 bytes)
 *   MIME type list (null-terminated strings, double-null terminated)
 *   URL pointer list (uint64 * articleCount)
 *   Title pointer list (uint32 * articleCount)
 *   Directory entries (variable length)
 *   Cluster pointer list (uint64 * clusterCount)
 *   Clusters (variable length, compressed)
 *   Checksum (MD5, 16 bytes)
 */
public class ZimReader {
    private static final String TAG = "ZimReader";

    public static final int MAGIC = 0x44D495A;

    // Header offsets
    private static final int HEADER_MAGIC_OFFSET        = 0;
    private static final int HEADER_MAJOR_VER_OFFSET    = 4;
    private static final int HEADER_MINOR_VER_OFFSET    = 6;
    private static final int HEADER_UUID_OFFSET         = 8;
    private static final int HEADER_ARTICLE_COUNT_OFFSET= 24;
    private static final int HEADER_CLUSTER_COUNT_OFFSET= 28;
    private static final int HEADER_URL_PTR_POS_OFFSET  = 32;
    private static final int HEADER_TITLE_PTR_POS_OFFSET= 40;
    private static final int HEADER_CLUSTER_PTR_POS_OFFSET = 48;
    private static final int HEADER_MIME_LIST_POS_OFFSET= 56;
    private static final int HEADER_MAIN_PAGE_OFFSET    = 64;
    private static final int HEADER_LAYOUT_PAGE_OFFSET  = 68;
    private static final int HEADER_CHECKSUM_POS_OFFSET = 72;

    // Directory entry types
    public static final int REDIRECT_ENTRY = 0xffff;

    private final RandomAccessFile mFile;
    private final String mFilePath;

    // Parsed header fields
    public int  majorVersion;
    public int  minorVersion;
    public long articleCount;
    public long clusterCount;
    public long urlPtrPos;
    public long titlePtrPos;
    public long clusterPtrPos;
    public long mimeListPos;
    public long mainPage;
    public long checksumPos;

    // Cached MIME types
    private List<String> mMimeTypes;

    public static class DirectoryEntry {
        public int     mimeTypeIdx;  // 0xffff = redirect
        public int     paramLen;
        public char    namespace;
        public int     revision;
        // Article entry:
        public long    clusterNumber;
        public long    blobNumber;
        public String  url;
        public String  title;
        // Redirect entry:
        public long    redirectIndex;

        public boolean isRedirect() {
            return mimeTypeIdx == REDIRECT_ENTRY;
        }
    }

    public ZimReader(String filePath) throws IOException {
        mFilePath = filePath;
        mFile = new RandomAccessFile(new File(filePath), "r");
        parseHeader();
    }

    private void parseHeader() throws IOException {
        byte[] header = new byte[80];
        mFile.seek(0);
        mFile.readFully(header);
        ByteBuffer buf = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN);

        int magic = buf.getInt(HEADER_MAGIC_OFFSET);
        if (magic != MAGIC) {
            throw new IOException("Not a valid ZIM file (bad magic: 0x" + Integer.toHexString(magic) + ")");
        }

        majorVersion  = buf.getShort(HEADER_MAJOR_VER_OFFSET) & 0xFFFF;
        minorVersion  = buf.getShort(HEADER_MINOR_VER_OFFSET) & 0xFFFF;
        articleCount  = buf.getInt(HEADER_ARTICLE_COUNT_OFFSET) & 0xFFFFFFFFL;
        clusterCount  = buf.getInt(HEADER_CLUSTER_COUNT_OFFSET) & 0xFFFFFFFFL;
        urlPtrPos     = buf.getLong(HEADER_URL_PTR_POS_OFFSET);
        titlePtrPos   = buf.getLong(HEADER_TITLE_PTR_POS_OFFSET);
        clusterPtrPos = buf.getLong(HEADER_CLUSTER_PTR_POS_OFFSET);
        mimeListPos   = buf.getLong(HEADER_MIME_LIST_POS_OFFSET);
        mainPage      = buf.getInt(HEADER_MAIN_PAGE_OFFSET) & 0xFFFFFFFFL;
        checksumPos   = buf.getLong(HEADER_CHECKSUM_POS_OFFSET);

        Log.i(TAG, "ZIM v" + majorVersion + "." + minorVersion +
                " articles=" + articleCount + " clusters=" + clusterCount);
    }

    public List<String> getMimeTypes() throws IOException {
        if (mMimeTypes != null) return mMimeTypes;
        mMimeTypes = new ArrayList<String>();
        mFile.seek(mimeListPos);
        while (true) {
            String mime = readNullTerminatedString();
            if (mime.isEmpty()) break;
            mMimeTypes.add(mime);
        }
        return mMimeTypes;
    }

    /** Return the file offset of a URL-sorted directory entry by index. */
    public long getUrlPtrAt(long index) throws IOException {
        mFile.seek(urlPtrPos + index * 8L);
        return readUint64LE();
    }

    /** Return the file offset of a Title-sorted directory entry by index. */
    public long getTitlePtrAt(long index) throws IOException {
        mFile.seek(titlePtrPos + index * 4L);
        return readUint32LE();
    }

    /** Parse the directory entry at the given file offset. */
    public DirectoryEntry readDirectoryEntry(long offset) throws IOException {
        mFile.seek(offset);
        DirectoryEntry entry = new DirectoryEntry();
        entry.mimeTypeIdx = readUint16LE();
        entry.paramLen    = mFile.read() & 0xFF;
        entry.namespace   = (char)(mFile.read() & 0xFF);
        entry.revision    = (int) readUint32LE();

        if (entry.mimeTypeIdx == REDIRECT_ENTRY) {
            entry.redirectIndex = readUint32LE();
        } else {
            entry.clusterNumber = readUint32LE();
            entry.blobNumber    = readUint32LE();
        }

        entry.url   = readNullTerminatedString();
        entry.title = readNullTerminatedString();
        if (entry.title.isEmpty()) entry.title = entry.url;

        return entry;
    }

    /** Resolve a redirect chain to the final article entry. */
    public DirectoryEntry resolveRedirect(DirectoryEntry entry) throws IOException {
        int maxHops = 10;
        while (entry.isRedirect() && maxHops-- > 0) {
            long offset = getUrlPtrAt(entry.redirectIndex);
            entry = readDirectoryEntry(offset);
        }
        return entry;
    }

    /** Return the raw (possibly compressed) bytes of a cluster. */
    public byte[] readClusterRaw(long clusterIdx) throws IOException {
        mFile.seek(clusterPtrPos + clusterIdx * 8L);
        long clusterOffset = readUint64LE();

        // Next cluster offset gives us the length
        long nextOffset;
        if (clusterIdx + 1 < clusterCount) {
            mFile.seek(clusterPtrPos + (clusterIdx + 1) * 8L);
            nextOffset = readUint64LE();
        } else {
            nextOffset = checksumPos;
        }

        int len = (int)(nextOffset - clusterOffset);
        byte[] data = new byte[len];
        mFile.seek(clusterOffset);
        mFile.readFully(data);
        return data;
    }

    /**
     * Read the content of a specific blob inside a cluster.
     * Handles compression type byte: 0/1=none, 4=LZMA/XZ, 5=Zstd
     */
    public byte[] readBlob(long clusterIdx, long blobIdx) throws IOException {
        byte[] raw = readClusterRaw(clusterIdx);
        if (raw.length == 0) return new byte[0];

        int compressionType = raw[0] & 0xFF;
        byte[] uncompressed;

        switch (compressionType) {
            case 0:
            case 1:
                // Uncompressed — skip the 1-byte type marker
                uncompressed = new byte[raw.length - 1];
                System.arraycopy(raw, 1, uncompressed, 0, uncompressed.length);
                break;
            case 4:
                // LZMA / XZ — skip type byte
                uncompressed = ZimDecompressor.decompressLzma(raw, 1, raw.length - 1);
                break;
            case 5:
                // Zstd — skip type byte
                uncompressed = ZimDecompressor.decompressZstd(raw, 1, raw.length - 1);
                break;
            default:
                throw new IOException("Unknown cluster compression type: " + compressionType);
        }

        // Cluster blob layout:
        //   [offset_0 uint32][offset_1 uint32]...[offset_N uint32][data...]
        // Number of blobs = offset_0 / 4
        if (uncompressed.length < 4) return new byte[0];

        ByteBuffer bb = ByteBuffer.wrap(uncompressed).order(ByteOrder.LITTLE_ENDIAN);
        long firstOffset = bb.getInt(0) & 0xFFFFFFFFL;
        long blobCount   = firstOffset / 4L;

        if (blobIdx >= blobCount) return new byte[0];

        long start = bb.getInt((int)(blobIdx * 4)) & 0xFFFFFFFFL;
        long end;
        if (blobIdx + 1 < blobCount) {
            end = bb.getInt((int)((blobIdx + 1) * 4)) & 0xFFFFFFFFL;
        } else {
            end = uncompressed.length;
        }

        int len = (int)(end - start);
        if (len <= 0) return new byte[0];

        byte[] result = new byte[len];
        System.arraycopy(uncompressed, (int)start, result, 0, len);
        return result;
    }

    /** Search articles by URL or title. */
    public List<DirectoryEntry> searchByTitle(String query, int maxResults) throws IOException {
        List<DirectoryEntry> results = new ArrayList<DirectoryEntry>();
        String lowerQuery = query.toLowerCase();

        boolean hasTitleTable = (titlePtrPos != 0xFFFFFFFFFFFFFFFFL && titlePtrPos > 0);
        long count = Math.min(articleCount, 50000L);

        for (long i = 0; i < count && results.size() < maxResults; i++) {
            long offset;
            if (hasTitleTable) {
                long urlIdx = getTitlePtrAt(i);
                if (urlIdx >= articleCount) continue;
                offset = getUrlPtrAt(urlIdx);
            } else {
                offset = getUrlPtrAt(i);
            }
            try {
                DirectoryEntry e = readDirectoryEntry(offset);
                // In ZIM v6 content is 'C', in ZIM v5 content is 'A'
                if ((e.namespace == 'C' || e.namespace == 'A') && !e.isRedirect()) {
                    String matchTarget = e.title.isEmpty() ? e.url : e.title;
                    if (matchTarget.toLowerCase().contains(lowerQuery)) {
                        results.add(e);
                    }
                }
            } catch (Exception ignored) {}
        }
        return results;
    }

    /** Get main page entry. */
    public DirectoryEntry getMainPage() throws IOException {
        if (mainPage == 0xFFFFFFFFL) return null;
        long offset = getUrlPtrAt(mainPage);
        DirectoryEntry e = readDirectoryEntry(offset);
        return resolveRedirect(e);
    }

    /** Find an entry by namespace + URL using binary search. */
    public DirectoryEntry findByUrl(char namespace, String url) throws IOException {
        String target = namespace + "/" + url;
        long lo = 0, hi = articleCount - 1;
        while (lo <= hi) {
            long mid = (lo + hi) / 2;
            long offset = getUrlPtrAt(mid);
            DirectoryEntry e = readDirectoryEntry(offset);
            String entryKey = e.namespace + "/" + e.url;
            int cmp = entryKey.compareTo(target);
            if (cmp == 0) return resolveRedirect(e);
            if (cmp < 0) lo = mid + 1;
            else hi = mid - 1;
        }
        return null;
    }

    /** Flexible lookup trying common namespaces (C for v6, A/- /I for v5). */
    public DirectoryEntry findArticle(String url) throws IOException {
        // Try Content namespace (ZIM v6)
        DirectoryEntry e = findByUrl('C', url);
        if (e != null) return e;

        // Try Article namespace (ZIM v5)
        e = findByUrl('A', url);
        if (e != null) return e;

        // Try Assets / Media namespaces
        e = findByUrl('-', url);
        if (e != null) return e;

        e = findByUrl('I', url);
        if (e != null) return e;

        return null;
    }

    public void close() {
        try { mFile.close(); } catch (Exception ignored) {}
    }

    // ---- Binary read helpers ----

    private String readNullTerminatedString() throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = mFile.read()) > 0) {
            baos.write(b);
        }
        return baos.toString("UTF-8");
    }

    private long readUint64LE() throws IOException {
        byte[] b = new byte[8];
        mFile.readFully(b);
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getLong();
    }

    private long readUint32LE() throws IOException {
        byte[] b = new byte[4];
        mFile.readFully(b);
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getInt() & 0xFFFFFFFFL;
    }

    private int readUint16LE() throws IOException {
        byte[] b = new byte[2];
        mFile.readFully(b);
        return ByteBuffer.wrap(b).order(ByteOrder.LITTLE_ENDIAN).getShort() & 0xFFFF;
    }
}
