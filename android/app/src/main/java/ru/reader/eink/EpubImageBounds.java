package ru.reader.eink;

import android.graphics.RectF;

import com.artifex.mupdf.fitz.Font;
import com.artifex.mupdf.fitz.Image;
import com.artifex.mupdf.fitz.Matrix;
import com.artifex.mupdf.fitz.Page;
import com.artifex.mupdf.fitz.Point;
import com.artifex.mupdf.fitz.Quad;
import com.artifex.mupdf.fitz.Rect;
import com.artifex.mupdf.fitz.StructuredText;
import com.artifex.mupdf.fitz.StructuredTextWalker;
import com.artifex.mupdf.fitz.android.AndroidDrawDevice;

import java.util.ArrayList;
import java.util.List;

final class EpubImageBounds {
    private EpubImageBounds() {
    }

    static List<RectF> find(Page page, int width, int height) {
        final ArrayList<RectF> bounds = new ArrayList<RectF>();
        final Matrix fit = AndroidDrawDevice.fitPage(page, width, height);
        final Rect pageBounds = page.getBounds().transform(fit);
        StructuredText text = page.toStructuredText("preserve-images");
        try {
            text.walk(new StructuredTextWalker() {
                @Override
                public void onImageBlock(Rect rect, Matrix matrix, Image image) {
                    Rect transformed = rect.transform(fit);
                    float left = Math.max(0, transformed.x0 - pageBounds.x0);
                    float top = Math.max(0, transformed.y0 - pageBounds.y0);
                    float right = Math.min(width, transformed.x1 - pageBounds.x0);
                    float bottom = Math.min(height, transformed.y1 - pageBounds.y0);
                    if (right > left && bottom > top) {
                        bounds.add(new RectF(left, top, right, bottom));
                    }
                }

                @Override
                public void beginTextBlock(Rect rect) {
                }

                @Override
                public void endTextBlock() {
                }

                @Override
                public void beginLine(Rect rect, int wmode) {
                }

                @Override
                public void endLine() {
                }

                @Override
                public void onChar(int c, Point point, Font font, float size, Quad quad) {
                }
            });
        } finally {
            text.destroy();
        }
        return bounds;
    }
}
