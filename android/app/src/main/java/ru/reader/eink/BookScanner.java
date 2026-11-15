package ru.reader.eink;

import android.content.Context;
import android.os.Environment;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

final class BookScanner {
    private static final int ID_SAMPLE_BYTES = 8192;

    interface Progress {
        void onDirectoryVisited();
    }

    static final class Result {
        final List<Book> books;
        final boolean inaccessibleDirectories;

        Result(List<Book> books, boolean inaccessibleDirectories) {
            this.books = books;
            this.inaccessibleDirectories = inaccessibleDirectories;
        }
    }

    private BookScanner() {
    }

    static Result scan(Context context, Progress progress) {
        ArrayList<File> roots = storageRoots(context);
        ArrayDeque<File> pending = new ArrayDeque<File>(roots);
        Set<String> visitedDirectories = new HashSet<String>();
        ArrayList<File> documents = new ArrayList<File>();
        boolean inaccessible = false;

        while (!pending.isEmpty() && !Thread.currentThread().isInterrupted()) {
            File directory = pending.removeFirst();
            String canonicalPath;
            try {
                canonicalPath = directory.getCanonicalPath();
            } catch (IOException exception) {
                inaccessible = true;
                continue;
            }
            if (!visitedDirectories.add(canonicalPath) || isExcluded(directory)) {
                continue;
            }

            File[] entries = directory.listFiles();
            if (entries == null) {
                inaccessible = true;
                continue;
            }
            if (progress != null) {
                progress.onDirectoryVisited();
            }
            for (File entry : entries) {
                if (Thread.currentThread().isInterrupted()) {
                    break;
                }
                if (entry.isDirectory()) {
                    if (!isExcluded(entry)) {
                        pending.addLast(entry);
                    }
                } else if (entry.isFile() && isSupported(entry) && isValidDocument(entry)) {
                    documents.add(entry);
                }
            }
        }

        ArrayList<Book> books = new ArrayList<Book>();
        for (File file : documents) {
            if (Thread.currentThread().isInterrupted()) {
                break;
            }
            String id = identityFor(file);
            if (id != null) {
                books.add(new Book(file, id, 0, 0, false, false));
            } else {
                inaccessible = true;
            }
        }
        Collections.sort(books, new Comparator<Book>() {
            @Override
            public int compare(Book left, Book right) {
                int modifiedOrder = Long.compare(right.modified, left.modified);
                return modifiedOrder != 0
                        ? modifiedOrder
                        : left.title.compareToIgnoreCase(right.title);
            }
        });
        return new Result(books, inaccessible);
    }

    static String identityForUri(String uri) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(uri.getBytes(
                    java.nio.charset.Charset.forName("UTF-8")));
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                result.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
            }
            return result.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    static String identityFor(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        RandomAccessFile input = null;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long length = file.length();
            digest.update(ByteBuffer.allocate(Long.SIZE / 8).putLong(length).array());
            byte[] extension = Book.extensionOf(file.getName())
                    .getBytes(java.nio.charset.Charset.forName("UTF-8"));
            digest.update(extension);
            input = new RandomAccessFile(file, "r");
            updateDigestFromFile(digest, input, 0, Math.min(length, ID_SAMPLE_BYTES));
            if (length > ID_SAMPLE_BYTES) {
                long tailStart = Math.max(ID_SAMPLE_BYTES, length - ID_SAMPLE_BYTES);
                updateDigestFromFile(digest, input, tailStart, length - tailStart);
            }
            if (file.length() != length) {
                return null;
            }
            byte[] hash = digest.digest();
            StringBuilder result = new StringBuilder(hash.length * 2);
            for (byte value : hash) {
                result.append(String.format(java.util.Locale.US, "%02x", value & 0xff));
            }
            return result.toString();
        } catch (IOException exception) {
            return null;
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        } finally {
            if (input != null) {
                try {
                    input.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static void updateDigestFromFile(MessageDigest digest, RandomAccessFile input,
                                             long offset, long length) throws IOException {
        input.seek(offset);
        byte[] buffer = new byte[4096];
        long remaining = length;
        while (remaining > 0 && !Thread.currentThread().isInterrupted()) {
            int count = input.read(buffer, 0, (int) Math.min(buffer.length, remaining));
            if (count < 0) {
                throw new IOException("Book changed while calculating its identifier");
            }
            digest.update(buffer, 0, count);
            remaining -= count;
        }
        if (remaining > 0) {
            throw new IOException("Book identity calculation was interrupted");
        }
    }

    private static ArrayList<File> storageRoots(Context context) {
        ArrayList<File> roots = new ArrayList<File>();
        addRoot(roots, Environment.getExternalStorageDirectory());

        File[] appDirectories = context.getExternalFilesDirs(null);
        if (appDirectories != null) {
            for (File appDirectory : appDirectories) {
                File root = appDirectory;
                for (int level = 0; level < 4 && root != null; level++) {
                    root = root.getParentFile();
                }
                addRoot(roots, root);
            }
        }
        return roots;
    }

    private static void addRoot(List<File> roots, File root) {
        if (root == null || !root.isDirectory() || !root.canRead()) {
            return;
        }
        try {
            String canonicalPath = root.getCanonicalPath();
            for (File existing : roots) {
                if (canonicalPath.equals(existing.getCanonicalPath())) {
                    return;
                }
            }
            roots.add(root);
        } catch (IOException ignored) {
        }
    }

    private static boolean isSupported(File file) {
        String extension = Book.extensionOf(file.getName());
        return "pdf".equals(extension) || "epub".equals(extension);
    }

    private static boolean isValidDocument(File file) {
        String extension = Book.extensionOf(file.getName());
        if ("pdf".equals(extension)) {
            InputStream input = null;
            try {
                input = new java.io.FileInputStream(file);
                byte[] header = new byte[5];
                int count = input.read(header);
                return count == header.length
                        && header[0] == '%'
                        && header[1] == 'P'
                        && header[2] == 'D'
                        && header[3] == 'F'
                        && header[4] == '-';
            } catch (IOException exception) {
                return false;
            } finally {
                if (input != null) {
                    try {
                        input.close();
                    } catch (IOException ignored) {
                    }
                }
            }
        }
        ZipFile zip = null;
        try {
            zip = new ZipFile(file);
            ZipEntry entry = zip.getEntry("mimetype");
            if (entry == null || entry.getSize() > 128) {
                return false;
            }
            InputStream input = zip.getInputStream(entry);
            try {
                byte[] value = new byte[32];
                int count = input.read(value);
                return count == 20
                        && "application/epub+zip".equals(
                        new String(value, 0, count, java.nio.charset.Charset.forName("UTF-8")));
            } finally {
                input.close();
            }
        } catch (IOException exception) {
            return false;
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (IOException ignored) {
                }
            }
        }
    }

    private static boolean isExcluded(File file) {
        String name = file.getName();
        return name.startsWith(".")
                || "Android".equalsIgnoreCase(name)
                || "cache".equalsIgnoreCase(name)
                || "LOST.DIR".equalsIgnoreCase(name)
                || "$RECYCLE.BIN".equalsIgnoreCase(name);
    }
}
