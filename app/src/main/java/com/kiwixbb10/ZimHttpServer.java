package com.kiwixbb10;

import android.util.Log;

import fi.iki.elonen.NanoHTTPD;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Tiny local HTTP server (NanoHTTPD) that serves ZIM content to the WebView.
 *
 * URL scheme:
 *   http://127.0.0.1:8080/A/Article_Title   -> article content
 *   http://127.0.0.1:8080/-/images/...      -> image blobs
 *   http://127.0.0.1:8080/search?q=query    -> search results page
 */
public class ZimHttpServer extends NanoHTTPD {
    private static final String TAG = "ZimHttpServer";
    public  static final int    PORT = 8080;
    public  static final String BASE_URL = "http://127.0.0.1:" + PORT;

    private ZimReader mReader;

    // MIME type mappings
    private static final Map<String, String> MIME_FALLBACKS = new HashMap<String, String>();
    static {
        MIME_FALLBACKS.put("html", "text/html; charset=utf-8");
        MIME_FALLBACKS.put("css",  "text/css");
        MIME_FALLBACKS.put("js",   "application/javascript");
        MIME_FALLBACKS.put("png",  "image/png");
        MIME_FALLBACKS.put("jpg",  "image/jpeg");
        MIME_FALLBACKS.put("jpeg", "image/jpeg");
        MIME_FALLBACKS.put("gif",  "image/gif");
        MIME_FALLBACKS.put("svg",  "image/svg+xml");
        MIME_FALLBACKS.put("webp", "image/webp");
        MIME_FALLBACKS.put("json", "application/json");
        MIME_FALLBACKS.put("woff", "font/woff");
        MIME_FALLBACKS.put("woff2","font/woff2");
    }

    public ZimHttpServer(ZimReader reader) throws IOException {
        super("127.0.0.1", PORT);
        mReader = reader;
    }

    public void setReader(ZimReader reader) {
        mReader = reader;
    }

    @Override
    public Response serve(IHTTPSession session) {
        String uri = session.getUri();
        Map<String, String> params = session.getParms();

        Log.d(TAG, "Request: " + uri);

        // Search endpoint
        if (uri.equals("/search")) {
            String q = params.get("q");
            if (q == null || q.trim().isEmpty()) {
                return newFixedLengthResponse(Response.Status.OK,
                        "text/html; charset=utf-8", buildEmptySearchPage());
            }
            return handleSearch(q.trim());
        }

        // Root → main page
        if (uri.equals("/") || uri.isEmpty()) {
            return handleMainPage();
        }

        // Clean and decode URI
        String cleanUri = decodeUrl(uri.startsWith("/") ? uri.substring(1) : uri);

        // Check if explicit namespace: /X/...
        if (cleanUri.length() >= 2 && cleanUri.charAt(1) == '/') {
            char ns = cleanUri.charAt(0);
            String subUrl = cleanUri.substring(2);
            Response resp = handleArticle(ns, subUrl);
            if (resp.getStatus() == Response.Status.OK) {
                return resp;
            }
        }

        // Direct article lookup across all namespaces (C for v6, A/- /I for v5)
        return handleFlexibleArticle(cleanUri);
    }

    private Response handleMainPage() {
        if (mReader == null) {
            return errorPage("No ZIM file loaded");
        }
        try {
            ZimReader.DirectoryEntry main = mReader.getMainPage();
            if (main == null) {
                return errorPage("No main page defined in this ZIM file");
            }
            return serveEntry(main);
        } catch (Exception e) {
            Log.e(TAG, "Main page error", e);
            return errorPage("Error loading main page: " + e.getMessage());
        }
    }

    private Response handleArticle(char namespace, String url) {
        if (mReader == null) {
            return errorPage("No ZIM file loaded");
        }
        try {
            ZimReader.DirectoryEntry entry = mReader.findByUrl(namespace, url);
            if (entry == null) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND,
                        "text/plain", "Not found: " + namespace + "/" + url);
            }
            return serveEntry(entry);
        } catch (Exception e) {
            Log.e(TAG, "Article error: " + url, e);
            return errorPage("Error loading: " + url + "<br>" + e.getMessage());
        }
    }

    private Response handleFlexibleArticle(String url) {
        if (mReader == null) {
            return errorPage("No ZIM file loaded");
        }
        try {
            ZimReader.DirectoryEntry entry = mReader.findArticle(url);
            if (entry == null) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND,
                        "text/plain", "Article not found: " + url);
            }
            return serveEntry(entry);
        } catch (Exception e) {
            Log.e(TAG, "Flexible article error: " + url, e);
            return errorPage("Error loading: " + url + "<br>" + e.getMessage());
        }
    }

    private Response serveEntry(ZimReader.DirectoryEntry entry) throws IOException {
        byte[] data = mReader.readBlob(entry.clusterNumber, entry.blobNumber);

        // Determine MIME type from ZIM's MIME list
        String mimeType = "application/octet-stream";
        try {
            java.util.List<String> mimes = mReader.getMimeTypes();
            if (entry.mimeTypeIdx < mimes.size()) {
                mimeType = mimes.get(entry.mimeTypeIdx);
            }
        } catch (Exception e) {
            // Fallback by extension
            String url = entry.url;
            int dot = url.lastIndexOf('.');
            if (dot >= 0) {
                String ext = url.substring(dot + 1).toLowerCase();
                String m = MIME_FALLBACKS.get(ext);
                if (m != null) mimeType = m;
            }
        }

        // Inject base tag into HTML so relative URLs work
        if (mimeType.startsWith("text/html")) {
            String html = new String(data, "UTF-8");
            String baseHref = BASE_URL + "/" + entry.namespace + "/";
            // Inject <base href="..."> after <head>
            String baseTag = "<base href=\"" + baseHref + "\">";
            int headIdx = html.indexOf("<head>");
            if (headIdx < 0) headIdx = html.indexOf("<HEAD>");
            if (headIdx >= 0) {
                html = html.substring(0, headIdx + 6) + baseTag + html.substring(headIdx + 6);
            } else {
                html = baseTag + html;
            }
            data = html.getBytes("UTF-8");
        }

        return newFixedLengthResponse(Response.Status.OK, mimeType,
                new ByteArrayInputStream(data), data.length);
    }

    private Response handleSearch(String query) {
        if (mReader == null) {
            return errorPage("No ZIM file loaded");
        }
        try {
            java.util.List<ZimReader.DirectoryEntry> results =
                    mReader.searchByTitle(query, 30);

            StringBuilder sb = new StringBuilder();
            sb.append("<!DOCTYPE html><html><head><meta charset=\"utf-8\">")
              .append("<title>Search: ").append(escapeHtml(query)).append("</title>")
              .append("<style>")
              .append("body{font-family:sans-serif;background:#1a1a2e;color:#eee;margin:0;padding:16px}")
              .append("h2{color:#e94560}a{color:#0f9b8e;text-decoration:none;display:block;")
              .append("padding:10px;border-bottom:1px solid #2a2a4a}a:hover{background:#16213e}")
              .append(".empty{color:#888;margin-top:32px}")
              .append("</style></head><body>")
              .append("<h2>Results for: ").append(escapeHtml(query)).append("</h2>");

            if (results.isEmpty()) {
                sb.append("<p class='empty'>No articles found.</p>");
            } else {
                for (ZimReader.DirectoryEntry e : results) {
                    String articleUrl = BASE_URL + "/" + e.namespace + "/" + e.url;
                    sb.append("<a href=\"").append(escapeHtml(articleUrl)).append("\">")
                      .append(escapeHtml(e.title)).append("</a>");
                }
            }
            sb.append("</body></html>");
            return newFixedLengthResponse(Response.Status.OK,
                    "text/html; charset=utf-8", sb.toString());
        } catch (Exception e) {
            Log.e(TAG, "Search error", e);
            return errorPage("Search error: " + e.getMessage());
        }
    }

    private Response errorPage(String msg) {
        String html = "<!DOCTYPE html><html><body style='background:#1a1a2e;color:#e94560;" +
                "font-family:sans-serif;padding:24px'><h3>Error</h3><p>" + escapeHtml(msg) + "</p></body></html>";
        return newFixedLengthResponse(Response.Status.INTERNAL_ERROR,
                "text/html; charset=utf-8", html);
    }

    private String buildEmptySearchPage() {
        return "<!DOCTYPE html><html><body style='background:#1a1a2e;color:#888;" +
               "font-family:sans-serif;padding:24px'><p>Enter a search term above.</p></body></html>";
    }

    private static String escapeHtml(String s) {
        return s.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");
    }

    private static String decodeUrl(String url) {
        try {
            return java.net.URLDecoder.decode(url, "UTF-8");
        } catch (Exception e) {
            return url;
        }
    }
}
