package org.codejive.utils.downloader;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URLConnection;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

interface ConnectionConfigurator {

    void configure(URLConnection conn) throws IOException;

    static ConnectionConfigurator all(ConnectionConfigurator... configurators) {
        return conn -> {
            for (ConnectionConfigurator c : configurators) {
                c.configure(conn);
            }
        };
    }

    static ConnectionConfigurator userAgent(String agentString) {
        return conn -> {
            conn.setRequestProperty("User-Agent", agentString);
        };
    }

    static ConnectionConfigurator authentication() {
        return Util::addAuthHeaderIfNeeded;
    }

    interface HttpConnectionConfigurator {
        void configure(HttpURLConnection conn) throws IOException;
    }

    static ConnectionConfigurator forHttp(HttpConnectionConfigurator configurator) {
        return conn -> {
            if (conn instanceof HttpURLConnection) {
                configurator.configure((HttpURLConnection) conn);
            }
        };
    }

    static ConnectionConfigurator timeout(int timeOut) {
        return forHttp(conn -> {
            if (timeOut >= 0) {
                conn.setConnectTimeout(timeOut);
                conn.setReadTimeout(timeOut);
            }
        });
    }

    static ConnectionConfigurator cacheControl(Path cachedFile, Path metaSaveDir) throws IOException {
        if (cachedFile != null && !isFresh()) {
            String cachedETag = Downloader.safeReadEtagFile(cachedFile, metaSaveDir);
            Instant lmt = Files.getLastModifiedTime(cachedFile).toInstant();
            ZonedDateTime zlmt = ZonedDateTime.ofInstant(lmt, ZoneId.of("GMT"));
            String cachedLastModified = DateTimeFormatter.RFC_1123_DATE_TIME.format(zlmt);
            return conn -> {
                if (cachedETag != null) {
                    conn.setRequestProperty("If-None-Match", cachedETag);
                }
                conn.setRequestProperty("If-Modified-Since", cachedLastModified);
            };
        } else {
            return conn -> {
            };
        }
    }
}
