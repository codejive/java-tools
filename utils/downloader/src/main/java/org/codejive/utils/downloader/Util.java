package org.codejive.utils.downloader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;

public class Util {

    /**
     * Java 8 approximate version of Java 11 Files.readString()
     **/
    public static String readString(Path toPath) throws IOException {
        return new String(Files.readAllBytes(toPath));
    }

    /**
     * Java 8 approximate version of Java 11 Files.writeString()
     **/
    static public void writeString(Path toPath, String scriptText) throws IOException {
        Files.write(toPath, scriptText.getBytes());
    }

    public static boolean isBlankString(String str) {
        return str.trim().isEmpty();
    }

    public static boolean deletePath(Path path) {
        Exception[] err = new Exception[] { null };
        try {
            if (Files.isDirectory(path)) {
                Files	.walk(path)
                        .sorted(Comparator.reverseOrder())
                        .forEach(f -> {
                            try {
                                Files.delete(f);
                            } catch (IOException e) {
                                err[0] = e;
                            }
                        });
            } else if (Files.exists(path)) {
                Files.delete(path);
            } else if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
                Files.delete(path);
            }
        } catch (IOException e) {
            err[0] = e;
        }
        return err[0] == null;
    }

}
