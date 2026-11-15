package ru.reader.eink;

final class K2PdfOpt {
    static {
        System.loadLibrary("k2pdfopt_jni");
    }

    private K2PdfOpt() {
    }

    static native int convertBitmap(String inputPath, String outputDirectory,
                                    int sourceDpi, int targetWidth, int targetHeight,
                                    int targetDpi);
}
