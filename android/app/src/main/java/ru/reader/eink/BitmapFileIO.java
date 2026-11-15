package ru.reader.eink;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;

final class BitmapFileIO {
    private BitmapFileIO() {
    }

    static void writeBmp(Bitmap bitmap, File destination, int dpi) throws IOException {
        int width = bitmap.getWidth();
        int height = bitmap.getHeight();
        int rowBytes = width * 3;
        int rowStride = (rowBytes + 3) & ~3;
        int imageSize = rowStride * height;
        int[] pixels = new int[width];
        byte[] row = new byte[rowStride];

        try (OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
            output.write('B');
            output.write('M');
            writeInt(output, 54 + imageSize);
            writeInt(output, 0);
            writeInt(output, 54);
            writeInt(output, 40);
            writeInt(output, width);
            writeInt(output, height);
            writeShort(output, 1);
            writeShort(output, 24);
            writeInt(output, 0);
            writeInt(output, imageSize);
            int pixelsPerMeter = Math.max(1, Math.round(dpi / 0.0254f));
            writeInt(output, pixelsPerMeter);
            writeInt(output, pixelsPerMeter);
            writeInt(output, 0);
            writeInt(output, 0);

            for (int y = height - 1; y >= 0; y--) {
                bitmap.getPixels(pixels, 0, width, 0, y, width, 1);
                for (int x = 0; x < width; x++) {
                    int color = pixels[x];
                    int gray = (((color >> 16) & 0xff) * 299
                            + ((color >> 8) & 0xff) * 587
                            + (color & 0xff) * 114 + 500) / 1000;
                    int offset = x * 3;
                    row[offset] = (byte) gray;
                    row[offset + 1] = (byte) gray;
                    row[offset + 2] = (byte) gray;
                }
                for (int offset = rowBytes; offset < rowStride; offset++) {
                    row[offset] = 0;
                }
                output.write(row);
            }
        }
    }

    static Bitmap readBitmap(File file) throws IOException {
        Bitmap bitmap = BitmapFactory.decodeFile(file.getAbsolutePath());
        if (bitmap == null) {
            throw new IOException("Unable to decode converted page");
        }
        return bitmap;
    }

    static boolean writePng(Bitmap bitmap, File destination) throws IOException {
        try (OutputStream output = new BufferedOutputStream(new FileOutputStream(destination))) {
            return bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        }
    }

    private static void writeShort(OutputStream output, int value) throws IOException {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
    }

    private static void writeInt(OutputStream output, int value) throws IOException {
        output.write(value & 0xff);
        output.write((value >>> 8) & 0xff);
        output.write((value >>> 16) & 0xff);
        output.write((value >>> 24) & 0xff);
    }
}
