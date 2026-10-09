package com.kiwixbb10;

import android.util.Log;

import org.apache.commons.compress.compressors.xz.XZCompressorInputStream;
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorInputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * Pure Java decompressor for ZIM cluster data.
 * Supports LZMA/XZ (via Apache Commons Compress + tukaani/xz)
 * and Zstandard (via Apache Commons Compress's Zstd support).
 */
public class ZimDecompressor {
    private static final String TAG = "ZimDecompressor";
    private static final int BUFFER_SIZE = 65536;

    /**
     * Decompress LZMA/XZ compressed bytes.
     * @param data   raw byte array
     * @param offset start of compressed data within array
     * @param length number of compressed bytes
     */
    public static byte[] decompressLzma(byte[] data, int offset, int length) throws IOException {
        ByteArrayInputStream bis = new ByteArrayInputStream(data, offset, length);
        XZCompressorInputStream xzIn = new XZCompressorInputStream(bis, true);
        return readAll(xzIn);
    }

    /**
     * Decompress Zstandard compressed bytes.
     * @param data   raw byte array
     * @param offset start of compressed data within array
     * @param length number of compressed bytes
     */
    public static byte[] decompressZstd(byte[] data, int offset, int length) throws IOException {
        ByteArrayInputStream bis = new ByteArrayInputStream(data, offset, length);
        ZstdCompressorInputStream zstdIn = new ZstdCompressorInputStream(bis);
        return readAll(zstdIn);
    }

    private static byte[] readAll(java.io.InputStream in) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        byte[] buf = new byte[BUFFER_SIZE];
        int read;
        try {
            while ((read = in.read(buf)) != -1) {
                baos.write(buf, 0, read);
            }
        } finally {
            try { in.close(); } catch (Exception ignored) {}
        }
        return baos.toByteArray();
    }
}
