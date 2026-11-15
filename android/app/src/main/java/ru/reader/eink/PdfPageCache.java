package ru.reader.eink;

import android.graphics.Bitmap;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

final class PdfPageCache {
    private static final long MAX_CACHE_BYTES = 1024L * 1024L * 1024L;
    private static final String COMPLETE_MARKER = ".complete";

    private final File root;
    private final String documentId;

    PdfPageCache(File cacheDirectory, String documentId) {
        this.root = new File(cacheDirectory, "k2pdfopt");
        this.documentId = documentId.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    List<File> load(int sourcePage) {
        File directory = pageDirectory(sourcePage);
        if (!new File(directory, COMPLETE_MARKER).isFile()) {
            return Collections.emptyList();
        }
        File[] pages = directory.listFiles();
        if (pages == null) {
            return Collections.emptyList();
        }
        ArrayList<File> images = new ArrayList<File>();
        for (File file : pages) {
            if (file.isFile() && file.getName().endsWith(".png")) {
                images.add(file);
            }
        }
        Collections.sort(images, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return left.getName().compareTo(right.getName());
            }
        });
        if (!images.isEmpty()) {
            directory.setLastModified(System.currentTimeMillis());
        }
        return images;
    }

    boolean store(int sourcePage, List<File> bitmapPages) throws IOException {
        if (bitmapPages.isEmpty()) {
            return false;
        }
        if (!root.exists() && !root.mkdirs()) {
            throw new IOException("Unable to create page cache");
        }
        File staging = new File(root, ".stage-" + documentId + "-" + sourcePage
                + "-" + System.nanoTime());
        if (!staging.mkdir()) {
            throw new IOException("Unable to create page cache staging directory");
        }
        long newBytes = 0;
        try {
            for (int index = 0; index < bitmapPages.size(); index++) {
                Bitmap bitmap = BitmapFileIO.readBitmap(bitmapPages.get(index));
                File image = new File(staging, String.format(java.util.Locale.US,
                        "page_%03d.png", index + 1));
                boolean written;
                try {
                    written = BitmapFileIO.writePng(bitmap, image);
                } finally {
                    bitmap.recycle();
                }
                if (!written) {
                    throw new IOException("Unable to encode converted page");
                }
                newBytes += image.length();
                if (image.length() == 0) {
                    throw new IOException("Converted page cache entry is empty");
                }
                if (newBytes > MAX_CACHE_BYTES) {
                    deleteTree(staging);
                    return false;
                }
            }
            File marker = new File(staging, COMPLETE_MARKER);
            if (!marker.createNewFile()) {
                throw new IOException("Unable to finish page cache entry");
            }
            if (!makeRoom(newBytes, pageDirectory(sourcePage), staging)) {
                deleteTree(staging);
                return false;
            }
            File destination = pageDirectory(sourcePage);
            File parent = destination.getParentFile();
            if (!parent.exists() && !parent.mkdirs()) {
                throw new IOException("Unable to create document page cache");
            }
            deleteTree(destination);
            if (!staging.renameTo(destination)) {
                throw new IOException("Unable to publish converted pages");
            }
            destination.setLastModified(System.currentTimeMillis());
            return true;
        } finally {
            if (staging.exists()) {
                deleteTree(staging);
            }
        }
    }

    void discard(int sourcePage) {
        deleteTree(pageDirectory(sourcePage));
    }

    private File pageDirectory(int sourcePage) {
        return new File(new File(root, documentId), "p" + sourcePage);
    }

    private boolean makeRoom(long newBytes, File protectedDirectory, File staging) {
        long used = directorySize(root) - directorySize(staging)
                - directorySize(protectedDirectory);
        if (used + newBytes <= MAX_CACHE_BYTES) {
            return true;
        }
        ArrayList<File> entries = pageDirectories(root);
        Collections.sort(entries, new Comparator<File>() {
            @Override
            public int compare(File left, File right) {
                return Long.compare(left.lastModified(), right.lastModified());
            }
        });
        for (File entry : entries) {
            if (entry.equals(protectedDirectory)) {
                continue;
            }
            deleteTree(entry);
            used = directorySize(root) - directorySize(staging)
                    - directorySize(protectedDirectory);
            if (used + newBytes <= MAX_CACHE_BYTES) {
                return true;
            }
        }
        return used + newBytes <= MAX_CACHE_BYTES;
    }

    private static ArrayList<File> pageDirectories(File directory) {
        ArrayList<File> result = new ArrayList<File>();
        File[] documents = directory.listFiles();
        if (documents == null) {
            return result;
        }
        for (File document : documents) {
            if (!document.isDirectory() || document.getName().startsWith(".stage-")) {
                continue;
            }
            File[] pages = document.listFiles();
            if (pages == null) {
                continue;
            }
            for (File page : pages) {
                if (page.isDirectory() && page.getName().startsWith("p")) {
                    result.add(page);
                }
            }
        }
        return result;
    }

    private static long directorySize(File directory) {
        if (!directory.exists()) {
            return 0;
        }
        if (directory.isFile()) {
            return directory.length();
        }
        long total = 0;
        File[] children = directory.listFiles();
        if (children != null) {
            for (File child : children) {
                total += directorySize(child);
            }
        }
        return total;
    }

    private static void deleteTree(File target) {
        if (target.isDirectory()) {
            File[] children = target.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteTree(child);
                }
            }
        }
        if (target.exists()) {
            target.delete();
        }
    }
}
