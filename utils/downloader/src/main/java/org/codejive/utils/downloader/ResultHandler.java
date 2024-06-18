package org.codejive.utils.downloader;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URLConnection;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.ZonedDateTime;
import java.util.Map;
import java.util.Objects;
import java.util.function.BiFunction;
import java.util.stream.Collectors;

interface ResultHandler {

    Path handle(URLConnection urlConnection) throws IOException;

    static ResultHandler redirects(ConnectionConfigurator configurator, ResultHandler okHandler) {
        return conn -> {
            if (conn instanceof HttpURLConnection) {
                conn = Downloader.handleRedirects((HttpURLConnection) conn, configurator);
            }
            return okHandler.handle(conn);
        };
    }

    static ResultHandler throwOnError(ResultHandler okHandler) {
        return conn -> {
            if (conn instanceof HttpURLConnection) {
                HttpURLConnection httpConn = (HttpURLConnection) conn;
                int responseCode = httpConn.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_NOT_FOUND) {
                    String fileURL = conn.getURL().toExternalForm();
                    throw new FileNotFoundException(
                            "No file to download at " + fileURL + ". Server replied HTTP code: " + responseCode);
                } else if (responseCode >= 400) {
                    String message = null;
                    if (httpConn.getErrorStream() != null) {
                        String err = new BufferedReader(new InputStreamReader(httpConn.getErrorStream()))
                                .lines()
                                .collect(
                                        Collectors.joining(
                                                "\n"))
                                .trim();
                        verboseMsg("HTTP: " + responseCode + " - " + err);
                        if (err.startsWith("{") && err.endsWith("}")) {
                            // Could be JSON, let's try to parse it
                            try {
                                Gson parser = new Gson();
                                Map json = parser.fromJson(err, Map.class);
                                // GitHub returns useful information in `message`,
                                // if it's there we use it.
                                // TODO add support for other known sites
                                message = Objects.toString(json.get("message"));
                            } catch (JsonSyntaxException ex) {
                                // Not JSON it seems
                            }
                        }
                    }
                    if (message != null) {
                        throw new IOException(
                                String.format("Server returned HTTP response code: %d for URL: %s with message: %s",
                                        responseCode, conn.getURL().toString(), message));
                    } else {
                        throw new IOException(String.format("Server returned HTTP response code: %d for URL: %s",
                                responseCode, conn.getURL().toString()));
                    }
                }
            }
            return okHandler.handle(conn);
        };
    }

    static ResultHandler downloadTo(Path saveDir, Path metaSaveDir) {
        return (conn) -> {
            // copy content from connection to file
            String fileName = Downloader.extractFileName(conn);
            Path file = saveDir.resolve(fileName);
            Files.createDirectories(saveDir);
            Files.createDirectories(metaSaveDir);
            try (ReadableByteChannel readableByteChannel = Channels.newChannel(conn.getInputStream());
                 FileOutputStream fileOutputStream = new FileOutputStream(file.toFile())) {
                fileOutputStream.getChannel().transferFrom(readableByteChannel, 0, Long.MAX_VALUE);
            }
            // create an .etag file if the information is present in the response headers
            String etag = conn.getHeaderField("ETag");
            if (etag != null) {
                Util.writeString(Downloader.etagFile(file, metaSaveDir), etag);
            }
            verboseMsg(String.format("Downloaded file %s", conn.getURL().toExternalForm()));
            return file;
        };
    }

    static ResultHandler downloadToTempDir(Path saveDir, Path metaSaveDir,
                                           BiFunction<Path, Path, ResultHandler> downloader) {
        return (conn) -> {
            // create a temp directory for the downloaded content
            Path saveTmpDir = saveDir.getParent().resolve(saveDir.getFileName() + ".tmp");
            Path saveOldDir = saveDir.getParent().resolve(saveDir.getFileName() + ".old");
            Path metaTmpDir = metaSaveDir.getParent().resolve(metaSaveDir.getFileName() + ".tmp");
            Path metaOldDir = metaSaveDir.getParent().resolve(metaSaveDir.getFileName() + ".old");
            try {
                Util.deletePath(saveTmpDir);
                Util.deletePath(saveOldDir);
                Util.deletePath(metaTmpDir);
                Util.deletePath(metaOldDir);

                Path saveFilePath = downloader.apply(saveTmpDir, metaTmpDir).handle(conn);

                // temporarily save the old content
                if (Files.isDirectory(saveDir)) {
                    Files.move(saveDir, saveOldDir);
                }
                if (Files.isDirectory(metaSaveDir)) {
                    Files.move(metaSaveDir, metaOldDir);
                }
                // rename the folder to its final name
                Files.move(saveTmpDir, saveDir);
                Files.move(metaTmpDir, metaSaveDir);
                // remove any old content
                Util.deletePath(saveOldDir);
                Util.deletePath(metaOldDir);

                return saveDir.resolve(saveFilePath.getFileName());
            } catch (Throwable th) {
                // remove the temp folder if anything went wrong
                Util.deletePath(saveTmpDir);
                Util.deletePath(metaTmpDir);
                // and move the old content back if it exists
                if (!Files.isDirectory(saveDir) && Files.isDirectory(saveOldDir)) {
                    try {
                        Files.move(saveOldDir, saveDir);
                    } catch (IOException ex) {
                        // Ignore
                    }
                }
                if (!Files.isDirectory(metaSaveDir) && Files.isDirectory(metaOldDir)) {
                    try {
                        Files.move(metaOldDir, metaSaveDir);
                    } catch (IOException ex) {
                        // Ignore
                    }
                }
                throw th;
            }
        };
    }

    static ResultHandler handleUnmodified(Path cachedFile, ResultHandler okHandler) {
        if (cachedFile != null) {
            return (conn) -> {
                if (conn instanceof HttpURLConnection) {
                    HttpURLConnection httpConn = (HttpURLConnection) conn;
                    if (httpConn.getResponseCode() == HttpURLConnection.HTTP_NOT_MODIFIED) {
                        verboseMsg(String.format("Not modified, using cached file %s for remote %s", cachedFile,
                                conn.getURL().toExternalForm()));
                        // Update cached file's last modified time
                        try {
                            Files.setLastModifiedTime(cachedFile, FileTime.from(ZonedDateTime.now().toInstant()));
                        } catch (IOException e) {
                            // There is an issue with Java not being able to set the file's last modified
                            // time
                            // on certain systems, resulting in an exception. There's not much we can do
                            // about
                            // that, so we'll just ignore it. It does mean that files affected by this will
                            // be
                            // re-downloaded every time.
                            verboseMsg("Unable to set last-modified time for " + cachedFile, e);
                        }
                        return cachedFile;
                    }
                }
                return okHandler.handle(conn);
            };
        } else {
            return okHandler;
        }
    }
}
