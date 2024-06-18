package org.codejive.utils.downloader;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.logging.Level;
import java.util.logging.Logger;

public class Downloader {
    private boolean offline;
    private boolean refresh;
    private long cacheEvictDuration;
    private String userAgent = DEFAULT_USERAGENT;
    private Logger logger;

    public static final String DEFAULT_USERAGENT = Downloader.class.getName() + " v0.1 ("
            + System.getProperty("os.name") + " " + System.getProperty("os.version")
            + " " + System.getProperty("os.arch") + ")";

    /**
     * Either retrieves a previously downloaded file from the cache or downloads a
     * file from a URL and stores it in the cache.
     *
     * @param fileURL HTTP URL of the file to be downloaded
     * @return Path to the downloaded file
     * @throws IOException
     */
    public Path downloadAndCacheFile(String fileURL) throws IOException {
        Path saveDir = getUrlCacheDir(fileURL);
        Path metaSaveDir = getCacheMetaDir(saveDir);
        Path cachedFile = getCachedFile(saveDir);
        if (cachedFile == null || isEvicted(cachedFile)) {
            return downloadFileAndCache(fileURL, saveDir, metaSaveDir, cachedFile);
        } else {
            logger.log(Level.FINE, String.format("Using cached file %s for remote %s", cachedFile, fileURL));
            return saveDir.resolve(cachedFile);
        }
    }

    // Returns true if the cached file doesn't exist or if its last
    // modified time is longer ago than the configuration value
    // indicated by "cache-evict" (defaults to "0" which will
    // cause this method to always return "true").
    private boolean isEvicted(Path cachedFile) {
        if (offline) {
            return false;
        }
        if (refresh) {
            return true;
        }
        if (Files.isRegularFile(cachedFile)) {
            if (cacheEvictDuration == 0) {
                return true;
            } else if (cacheEvictDuration == -1) {
                return false;
            }
            try {
                Instant cachedLastModified = Files.getLastModifiedTime(cachedFile).toInstant();
                Duration d = Duration.between(cachedLastModified, Instant.now());
                return d.getSeconds() >= cacheEvictDuration;
            } catch (IOException e) {
                return true;
            }
        } else {
            return true;
        }
    }

    private Path downloadFileAndCache(String fileURL, Path saveDir, Path metaSaveDir, Path cachedFile)
            throws IOException {
        ConnectionConfigurator cfg = ConnectionConfigurator.all(
                ConnectionConfigurator.userAgent(userAgent),
                ConnectionConfigurator.authentication(),
                ConnectionConfigurator.timeout(null),
                ConnectionConfigurator.cacheControl(cachedFile, metaSaveDir));
        ResultHandler handler = ResultHandler.redirects(cfg,
                ResultHandler.handleUnmodified(cachedFile,
                        ResultHandler.throwOnError(
                                ResultHandler.downloadToTempDir(saveDir, metaSaveDir,
                                        ResultHandler::downloadTo))));
        return connect(fileURL, cfg, handler);
    }

    /**
     * Downloads a file from a URL
     *
     * @param fileURL HTTP URL of the file to be downloaded
     * @param saveDir path of the directory to save the file
     * @return Path to the downloaded file
     * @throws IOException
     */
    public Path downloadFile(String fileURL, Path saveDir) throws IOException {
        return downloadFile(fileURL, saveDir, -1);
    }

    /**
     * Downloads a file from a URL
     *
     * @param fileURL HTTP URL of the file to be downloaded
     * @param saveDir path of the directory to save the file
     * @param timeOut the timeout in milliseconds to use for opening the connection.
     *                0 is an infinite timeout while -1 uses the defaults
     * @return Path to the downloaded file
     * @throws IOException
     */
    public Path downloadFile(String fileURL, Path saveDir, Integer timeOut) throws IOException {
        ConnectionConfigurator cfg = ConnectionConfigurator.all(
                ConnectionConfigurator.userAgent(userAgent),
                ConnectionConfigurator.authentication(),
                ConnectionConfigurator.timeout(timeOut));
        ResultHandler handler = ResultHandler.redirects(cfg,
                ResultHandler.throwOnError(
                        ResultHandler.downloadTo(saveDir, saveDir)));
        return connect(fileURL, cfg, handler);
    }

    static Path etagFile(Path cachedFile, Path metaSaveDir) {
        return metaSaveDir.resolve(cachedFile.getFileName() + ".etag");
    }

    static String safeReadEtagFile(Path cachedFile, Path metaSaveDir) {
        Path etag = etagFile(cachedFile, metaSaveDir);
        if (Files.isRegularFile(etag)) {
            try {
                return Util.readString(etag);
            } catch (IOException e) {
                // Ignore
            }
        }
        return null;
    }

    private Path connect(String fileURL, ConnectionConfigurator configurator, ResultHandler resultHandler)
            throws IOException {
        if (offline) {
            throw new FileNotFoundException("jbang is in offline mode, no remote access permitted");
        }

        URL url = new URL(fileURL);
        URLConnection urlConnection = url.openConnection();
        configurator.configure(urlConnection);

        if (urlConnection instanceof HttpURLConnection) {
            HttpURLConnection httpConn = (HttpURLConnection) urlConnection;
            logger.log(Level.FINE, String.format("Requesting HTTP %s %s", httpConn.getRequestMethod(), httpConn.getURL()));
            logger.log(Level.FINE, String.format("Headers %s", httpConn.getRequestProperties()));
        } else {
            logger.log(Level.FINE, String.format("Requesting %s", urlConnection.getURL()));
        }

        try {
            return resultHandler.handle(urlConnection);
        } finally {
            if (urlConnection instanceof HttpURLConnection) {
                ((HttpURLConnection) urlConnection).disconnect();
            }
        }
    }

    private static String extractFileName(URLConnection urlConnection) throws IOException {
        String fileURL = urlConnection.getURL().toExternalForm();
        String fileName = "";
        if (urlConnection instanceof HttpURLConnection) {
            HttpURLConnection httpConn = (HttpURLConnection) urlConnection;
            int responseCode = httpConn.getResponseCode();
            // always check HTTP response code first
            if (responseCode == HttpURLConnection.HTTP_OK || responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                String disposition = httpConn.getHeaderField("Content-Disposition");
                if (disposition != null) {
                    // extracts file name from header field
                    fileName = getDispositionFilename(disposition);
                }
            }
            if (Util.isBlankString(fileName)) {
                // extracts file name from URL if nothing found
                int p = fileURL.indexOf("?");
                // Strip parameters from the URL (if any)
                String simpleUrl = (p > 0) ? fileURL.substring(0, p) : fileURL;
                while (simpleUrl.endsWith("/")) {
                    simpleUrl = simpleUrl.substring(0, simpleUrl.length() - 1);
                }
                fileName = simpleUrl.substring(simpleUrl.lastIndexOf("/") + 1);
            }
        } else {
            fileName = fileURL.substring(fileURL.lastIndexOf("/") + 1);
        }
        return fileName;
    }

    private HttpURLConnection handleRedirects(HttpURLConnection httpConn, ConnectionConfigurator configurator)
            throws IOException {
        int responseCode;
        int redirects = 0;
        while (true) {
            httpConn.setInstanceFollowRedirects(false);
            responseCode = httpConn.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_MULT_CHOICE ||
                    responseCode == HttpURLConnection.HTTP_MOVED_PERM ||
                    responseCode == HttpURLConnection.HTTP_MOVED_TEMP ||
                    responseCode == HttpURLConnection.HTTP_SEE_OTHER ||
                    responseCode == 307 /* TEMP REDIRECT */ ||
                    responseCode == 308 /* PERM REDIRECT */) {
                if (redirects++ > 8) {
                    throw new IOException("Too many redirects");
                }
                String location = httpConn.getHeaderField("Location");
                if (location == null) {
                    throw new IOException("No 'Location' header in redirect");
                }
                URL url = new URL(httpConn.getURL(), location);
                url = new URL(swizzleURL(url.toString()));
                logger.log(Level.FINE, "Redirected to: " + url); // Should be debug info
                httpConn = (HttpURLConnection) url.openConnection();
                if (responseCode == HttpURLConnection.HTTP_SEE_OTHER) {
                    // This response code forces the method to GET
                    httpConn.setRequestMethod("GET");
                }
                configurator.configure(httpConn);
                continue;
            }
            break;
        }
        return httpConn;
    }

    private void addAuthHeaderIfNeeded(URLConnection urlConnection) {
        String auth = null;
        if (urlConnection.getURL().getHost().endsWith("github.com") && System.getenv().containsKey("GITHUB_TOKEN")) {
            auth = "token " + System.getenv("GITHUB_TOKEN");
        } else {
            String username = System.getenv(JBANG_AUTH_BASIC_USERNAME);
            String password = System.getenv(JBANG_AUTH_BASIC_PASSWORD);
            if (username != null && password != null) {
                String id = username + ":" + password;
                String encodedId = Base64.getEncoder().encodeToString(id.getBytes(StandardCharsets.UTF_8));
                auth = "Basic " + encodedId;
            }
        }
        if (auth != null) {
            urlConnection.setRequestProperty("Authorization", auth);
        }
    }

    public static String getDispositionFilename(String disposition) throws UnsupportedEncodingException {
        String fileName = "";
        int index1 = disposition.toLowerCase().lastIndexOf("filename=");
        int index2 = disposition.toLowerCase().lastIndexOf("filename*=");
        if (index1 > 0 && index1 > index2) {
            fileName = unquote(disposition.substring(index1 + 9));
        }
        if (index2 > 0 && index2 > index1) {
            String encodedName = disposition.substring(index2 + 10);
            String[] parts = encodedName.split("'", 3);
            if (parts.length == 3) {
                String encoding = parts[0].isEmpty() ? "iso-8859-1" : parts[0];
                String name = parts[2];
                fileName = URLDecoder.decode(name, encoding);
            }
        }
        return fileName;
    }

    public static String unquote(String txt) {
        if (txt.startsWith("\"") && txt.endsWith("\"")) {
            txt = txt.substring(1, txt.length() - 1);
        }
        return txt;
    }
}

