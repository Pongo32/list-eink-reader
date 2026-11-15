package ru.reader.eink;

import java.io.File;

final class Book {
    final File file;
    final String id;
    final String title;
    final String format;
    final long modified;
    final int lastPage;
    final int pageCount;
    final boolean opened;
    final boolean finished;

    Book(File file, String id, int lastPage, int pageCount, boolean opened,
         boolean finished) {
        this.file = file;
        this.id = id;
        this.title = titleFromFile(file);
        this.format = extensionOf(file.getName()).toUpperCase(java.util.Locale.US);
        this.modified = file.lastModified();
        this.lastPage = lastPage;
        this.pageCount = pageCount;
        this.opened = opened;
        this.finished = finished;
    }

    private static String titleFromFile(File file) {
        String name = file.getName();
        int extension = name.lastIndexOf('.');
        return extension > 0 ? name.substring(0, extension) : name;
    }

    static String extensionOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase(java.util.Locale.US) : "";
    }
}
