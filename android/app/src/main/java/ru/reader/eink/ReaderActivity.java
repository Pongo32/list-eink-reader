package ru.reader.eink;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import com.artifex.mupdf.fitz.Document;
import com.artifex.mupdf.fitz.Page;
import com.artifex.mupdf.fitz.Rect;
import com.artifex.mupdf.fitz.android.AndroidDrawDevice;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class ReaderActivity extends Activity {
    static final String PREFERENCES = "reader-progress";
    static final String EXTRA_BOOK_ID = "book_id";
    static final String EXTRA_BOOK_TITLE = "book_title";
    private static final String FALLBACK_ID = "external-document";
    private static final int MAX_CONVERSION_PIXELS = 3 * 1024 * 1024;
    private static final int MAX_CONVERSION_DIMENSION = 2048;
    private static final int MAX_CONVERSION_DPI = 120;
    private static final int MIN_CONVERSION_DPI = 24;
    private static final int MIN_EPUB_FONT_SIZE = 8;
    private static final int MAX_EPUB_FONT_SIZE = 32;
    private static final int EPUB_FONT_SIZE_STEP = 2;

    private final Handler mainHandler = new Handler();
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ExecutorService converter = Executors.newSingleThreadExecutor();
    private final ArrayList<PageItem> displayPages = new ArrayList<PageItem>();
    private volatile boolean destroyed;
    private volatile int renderRequestId;
    private volatile int saveRequestId;
    private volatile Document document;
    private File cachedDocument;
    private PdfPageCache pageCache;
    private Bitmap displayedBitmap;
    private Bitmap zoomBitmap;
    private FrameLayout rootContainer;
    private ImageView pageImage;
    private ImageView zoomImage;
    private TextView status;
    private TextView pageLabel;
    private TextView titleLabel;
    private Button previousButton;
    private Button nextButton;
    private LinearLayout epubFontControls;
    private Button decreaseEpubFontButton;
    private Button increaseEpubFontButton;
    private TextView epubFontSizeLabel;
    private BookmarkFlagView bookmarkButton;
    private Button addBookmarkButton;
    private LinearLayout conversionIndicator;
    private View conversionDot;
    private TextView conversionPercent;
    private View bookmarkDismissArea;
    private LinearLayout bookmarkPanel;
    private LinearLayout bookmarkList;
    private FrameLayout zoomOverlay;
    private List<RectF> displayedImageBounds = Collections.emptyList();
    private int pageCount;
    private volatile int sourcePageCount;
    private int currentPage;
    private int requestedPage;
    private int restoredPart;
    private int restoredSourcePage;
    private boolean restoringPosition;
    private int epubFontSize = 12;
    private boolean reflowable;
    private boolean epubFontRelayoutPending;
    private boolean pdfDocument;
    private boolean conversionStarted;
    private String documentId;
    private String documentTitle;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildScreen();
        documentId = getIntent().getStringExtra(EXTRA_BOOK_ID);
        documentTitle = getIntent().getStringExtra(EXTRA_BOOK_TITLE);
        Uri documentUri = getIntent().getData();
        if (documentUri == null) {
            showOpenError(getString(R.string.book_uri_error));
            return;
        }
        openDocument(documentUri);
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (event.getAction() == KeyEvent.ACTION_DOWN && zoomOverlay != null) {
            closeImageZoom();
            return true;
        }
        if (event.getAction() == KeyEvent.ACTION_DOWN
                && bookmarkPanel != null && bookmarkPanel.getVisibility() == View.VISIBLE) {
            closeBookmarkPanel();
            return true;
        }
        int keyCode = event.getKeyCode();
        boolean previousPage = keyCode == KeyEvent.KEYCODE_DPAD_LEFT
                || keyCode == KeyEvent.KEYCODE_PAGE_UP
                || keyCode == KeyEvent.KEYCODE_VOLUME_UP;
        boolean nextPage = keyCode == KeyEvent.KEYCODE_DPAD_RIGHT
                || keyCode == KeyEvent.KEYCODE_PAGE_DOWN
                || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN;
        if (previousPage || nextPage) {
            if (event.getAction() == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
                movePage(previousPage ? -1 : 1);
            }
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    @Override
    protected void onPause() {
        if (currentPage >= 0 && currentPage < displayPages.size()) {
            savePosition(displayPages.get(currentPage));
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        renderRequestId++;
        if (zoomOverlay != null) {
            rootContainer.removeView(zoomOverlay);
            zoomOverlay = null;
        }
        if (zoomImage != null) {
            zoomImage.setImageDrawable(null);
            zoomImage = null;
        }
        if (zoomBitmap != null && zoomBitmap != displayedBitmap) {
            zoomBitmap.recycle();
        }
        zoomBitmap = null;
        if (displayedBitmap != null) {
            pageImage.setImageDrawable(null);
            displayedBitmap.recycle();
            displayedBitmap = null;
        }
        worker.execute(new Runnable() {
            @Override
            public void run() {
                if (document != null) {
                    document.destroy();
                    document = null;
                }
            }
        });
        converter.execute(new Runnable() {
            @Override
            public void run() {
                if (cachedDocument != null) {
                    cachedDocument.delete();
                    cachedDocument = null;
                }
            }
        });
        worker.shutdown();
        converter.shutdown();
        super.onDestroy();
    }

    private void buildScreen() {
        rootContainer = new FrameLayout(this);
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(dp(8), dp(6), dp(8), dp(6));
        screen.setBackgroundColor(Color.WHITE);
        rootContainer.addView(screen, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        LinearLayout toolbar = new LinearLayout(this);
        toolbar.setGravity(Gravity.CENTER_VERTICAL);
        Button close = button(R.string.back_to_library);
        close.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                finish();
            }
        });
        toolbar.addView(close);
        titleLabel = new TextView(this);
        titleLabel.setTextColor(Color.BLACK);
        titleLabel.setTextSize(15);
        titleLabel.setSingleLine(true);
        titleLabel.setEllipsize(android.text.TextUtils.TruncateAt.END);
        titleLabel.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams titleParams = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        titleParams.leftMargin = dp(6);
        toolbar.addView(titleLabel, titleParams);
        epubFontControls = new LinearLayout(this);
        epubFontControls.setGravity(Gravity.CENTER_VERTICAL);
        decreaseEpubFontButton = button(R.string.epub_font_decrease);
        decreaseEpubFontButton.setMinWidth(dp(38));
        decreaseEpubFontButton.setPadding(dp(2), 0, dp(2), 0);
        decreaseEpubFontButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                changeEpubFontSize(-EPUB_FONT_SIZE_STEP);
            }
        });
        epubFontControls.addView(decreaseEpubFontButton);
        epubFontSizeLabel = new TextView(this);
        epubFontSizeLabel.setTextColor(Color.BLACK);
        epubFontSizeLabel.setTextSize(12);
        epubFontSizeLabel.setGravity(Gravity.CENTER);
        epubFontControls.addView(epubFontSizeLabel, new LinearLayout.LayoutParams(
                dp(28), LinearLayout.LayoutParams.WRAP_CONTENT));
        increaseEpubFontButton = button(R.string.epub_font_increase);
        increaseEpubFontButton.setMinWidth(dp(38));
        increaseEpubFontButton.setPadding(dp(2), 0, dp(2), 0);
        increaseEpubFontButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                changeEpubFontSize(EPUB_FONT_SIZE_STEP);
            }
        });
        epubFontControls.addView(increaseEpubFontButton);
        epubFontControls.setVisibility(View.GONE);
        toolbar.addView(epubFontControls);
        bookmarkButton = new BookmarkFlagView(this);
        bookmarkButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggleBookmarkPanel();
            }
        });
        toolbar.addView(bookmarkButton, new LinearLayout.LayoutParams(dp(44), dp(44)));
        screen.addView(toolbar, margins(0, 0, 0, 4));

        status = new TextView(this);
        status.setText(R.string.book_opening);
        status.setTextColor(Color.DKGRAY);
        status.setTextSize(13);
        status.setGravity(Gravity.CENTER);
        screen.addView(status, margins(0, 0, 0, 4));

        pageImage = new ImageView(this);
        pageImage.setBackgroundColor(Color.WHITE);
        pageImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        pageImage.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                if (event.getAction() != MotionEvent.ACTION_UP || pageCount == 0
                        || epubFontRelayoutPending) {
                    return true;
                }
                RectF imageBounds = findEpubImageAt(event.getX(), event.getY());
                if (imageBounds != null) {
                    showImageZoom(imageBounds);
                    return true;
                }
                float fraction = event.getX() / Math.max(1, view.getWidth());
                if (fraction < 0.35f) {
                    movePage(-1);
                } else if (fraction > 0.65f) {
                    movePage(1);
                }
                return true;
            }
        });
        LinearLayout.LayoutParams imageParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1);
        screen.addView(pageImage, imageParams);

        LinearLayout footer = new LinearLayout(this);
        footer.setGravity(Gravity.CENTER_VERTICAL);
        previousButton = button(R.string.previous_page);
        nextButton = button(R.string.next_page);
        previousButton.setEnabled(false);
        nextButton.setEnabled(false);
        previousButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                movePage(-1);
            }
        });
        nextButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                movePage(1);
            }
        });
        footer.addView(previousButton);
        pageLabel = new TextView(this);
        pageLabel.setTextColor(Color.DKGRAY);
        pageLabel.setTextSize(13);
        pageLabel.setGravity(Gravity.CENTER);
        footer.addView(pageLabel, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        conversionIndicator = new LinearLayout(this);
        conversionIndicator.setGravity(Gravity.CENTER_VERTICAL);
        conversionDot = new View(this);
        GradientDrawable dot = new GradientDrawable();
        dot.setShape(GradientDrawable.OVAL);
        dot.setColor(Color.DKGRAY);
        conversionDot.setBackgroundDrawable(dot);
        conversionIndicator.addView(conversionDot, new LinearLayout.LayoutParams(
                dp(8), dp(8)));
        conversionPercent = new TextView(this);
        conversionPercent.setTextColor(Color.DKGRAY);
        conversionPercent.setTextSize(12);
        conversionPercent.setPadding(dp(4), 0, dp(4), 0);
        conversionIndicator.addView(conversionPercent);
        conversionIndicator.setVisibility(View.GONE);
        footer.addView(conversionIndicator);
        footer.addView(nextButton);
        screen.addView(footer, margins(0, 4, 0, 0));

        bookmarkDismissArea = new View(this);
        bookmarkDismissArea.setBackgroundColor(Color.TRANSPARENT);
        bookmarkDismissArea.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                closeBookmarkPanel();
            }
        });
        bookmarkDismissArea.setVisibility(View.GONE);
        rootContainer.addView(bookmarkDismissArea, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        bookmarkPanel = new LinearLayout(this);
        bookmarkPanel.setOrientation(LinearLayout.VERTICAL);
        bookmarkPanel.setPadding(dp(10), dp(10), dp(10), dp(10));
        bookmarkPanel.setBackgroundDrawable(panelBackground());
        TextView bookmarksTitle = new TextView(this);
        bookmarksTitle.setText(R.string.bookmarks_title);
        bookmarksTitle.setTextColor(Color.BLACK);
        bookmarksTitle.setTextSize(17);
        bookmarksTitle.setPadding(dp(4), dp(2), dp(4), dp(8));
        bookmarkPanel.addView(bookmarksTitle);
        addBookmarkButton = button(R.string.bookmark_add_page);
        addBookmarkButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                toggleCurrentBookmark();
            }
        });
        bookmarkPanel.addView(addBookmarkButton, margins(0, 0, 0, 6));
        ScrollView bookmarkScroll = new ScrollView(this);
        bookmarkList = new LinearLayout(this);
        bookmarkList.setOrientation(LinearLayout.VERTICAL);
        bookmarkScroll.addView(bookmarkList);
        bookmarkPanel.addView(bookmarkScroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        bookmarkPanel.setVisibility(View.GONE);
        FrameLayout.LayoutParams bookmarkPanelParams = new FrameLayout.LayoutParams(
                dp(250), dp(460), Gravity.LEFT | Gravity.CENTER_VERTICAL);
        bookmarkPanelParams.leftMargin = dp(12);
        rootContainer.addView(bookmarkPanel, bookmarkPanelParams);
        setContentView(rootContainer);
    }

    private void openDocument(final Uri documentUri) {
        worker.execute(new Runnable() {
            @Override
            public void run() {
                Document openedDocument = null;
                File temporaryFile = null;
                try {
                    temporaryFile = copyToCache(documentUri);
                    openedDocument = Document.openDocument(temporaryFile.getAbsolutePath());
                    if (openedDocument.needsPassword()) {
                        throw new IOException(getString(R.string.book_password_error));
                    }
                    if (!openedDocument.isPDF() && !openedDocument.isReflowable()) {
                        throw new IOException(getString(R.string.book_invalid_error));
                    }
                    document = openedDocument;
                    cachedDocument = temporaryFile;
                    reflowable = openedDocument.isReflowable();
                    pdfDocument = openedDocument.isPDF();
                    if (documentId == null) {
                        documentId = BookScanner.identityFor(temporaryFile);
                    }
                    if (documentId == null) {
                        documentId = BookScanner.identityForUri(documentUri.toString());
                    }
                    if (reflowable) {
                        epubFontSize = readEpubFontSize();
                    }
                    pageCache = new PdfPageCache(getCacheDir(), documentId);
                    if (documentTitle == null || documentTitle.length() == 0) {
                        documentTitle = titleForUri(documentUri);
                    }
                    postDocumentTitle(documentTitle);
                    prepareDocument();
                    return;
                } catch (IOException exception) {
                    postOpenError(exception.getMessage());
                } catch (RuntimeException exception) {
                    postOpenError(getString(R.string.book_open_error));
                }
                if (openedDocument != null) {
                    openedDocument.destroy();
                }
                if (temporaryFile != null) {
                    temporaryFile.delete();
                }
            }
        });
    }

    private void prepareDocument() {
        pageImage.post(new Runnable() {
            @Override
            public void run() {
                final int width = Math.max(1, pageImage.getWidth());
                final int height = Math.max(1, pageImage.getHeight());
                final float densityDpi = getResources().getDisplayMetrics().densityDpi;
                final float pageWidth = width * 72f / densityDpi;
                final float pageHeight = height * 72f / densityDpi;
                worker.execute(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            if (reflowable) {
                                document.layout(pageWidth, pageHeight, epubFontSize);
                            }
                            int count = document.countPages();
                            if (count < 1) {
                                throw new IOException(getString(R.string.book_empty_error));
                            }
                            int startPage = restoredPage(count);
                            postDocumentReady(count, startPage, restoredPart);
                        } catch (IOException exception) {
                            postOpenError(exception.getMessage());
                        } catch (RuntimeException exception) {
                            postOpenError(getString(R.string.book_open_error));
                        }
                    }
                });
            }
        });
    }

    private int restoredPage(int count) {
        android.content.SharedPreferences preferences =
                getSharedPreferences(PREFERENCES, MODE_PRIVATE);
        String key = progressKey(documentId);
        int page = preferences.getInt(key + ".page", 0);
        restoredPart = preferences.getInt(key + ".part", 0);
        if (!pdfDocument) {
            restoredPart = 0;
        }
        if (reflowable) {
            long bookmark = preferences.getLong(key + ".bookmark", -1);
            if (bookmark >= 0) {
                int bookmarkedPage = document.findBookmark(bookmark);
                if (bookmarkedPage >= 0 && bookmarkedPage < count) {
                    page = bookmarkedPage;
                }
            }
        }
        return Math.max(0, Math.min(page, count - 1));
    }

    private File copyToCache(Uri documentUri) throws IOException {
        File temporaryFile = File.createTempFile("reader-", extensionFor(documentUri), getCacheDir());
        byte[] buffer = new byte[8192];
        long copied = 0;
        try {
            InputStream input = getContentResolver().openInputStream(documentUri);
            if (input == null) {
                throw new IOException(getString(R.string.book_uri_error));
            }
            try (InputStream source = input; OutputStream output = new FileOutputStream(temporaryFile)) {
                int count;
                while ((count = source.read(buffer)) != -1) {
                    if (getCacheDir().getUsableSpace() < count) {
                        throw new IOException(getString(R.string.book_no_space_error));
                    }
                    output.write(buffer, 0, count);
                    copied += count;
                }
                if (copied == 0) {
                    throw new IOException(getString(R.string.book_empty_error));
                }
            }
            return temporaryFile;
        } catch (IOException exception) {
            temporaryFile.delete();
            throw exception;
        } catch (RuntimeException exception) {
            temporaryFile.delete();
            throw exception;
        }
    }

    private String extensionFor(Uri uri) {
        String extension = Book.extensionOf(titleForUri(uri));
        if ("epub".equals(extension)) {
            return ".epub";
        }
        if ("pdf".equals(extension)) {
            return ".pdf";
        }
        String mimeType = getContentResolver().getType(uri);
        return "application/epub+zip".equalsIgnoreCase(mimeType) ? ".epub" : ".pdf";
    }

    private String titleForUri(Uri uri) {
        if ("content".equalsIgnoreCase(uri.getScheme())) {
            android.database.Cursor cursor = null;
            try {
                cursor = getContentResolver().query(uri,
                        new String[] {OpenableColumns.DISPLAY_NAME}, null, null, null);
                if (cursor != null && cursor.moveToFirst()) {
                    int column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (column >= 0) {
                        String name = cursor.getString(column);
                        if (name != null && name.length() > 0) {
                            return name;
                        }
                    }
                }
            } catch (RuntimeException ignored) {
            } finally {
                if (cursor != null) {
                    cursor.close();
                }
            }
        }
        String segment = uri.getLastPathSegment();
        return segment == null ? getString(R.string.book_default_title) : segment;
    }

    private void postDocumentTitle(final String title) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!destroyed) {
                    titleLabel.setText(title);
                    updateEpubFontControls();
                }
            }
        });
    }

    private void updateEpubFontControls() {
        epubFontControls.setVisibility(reflowable ? View.VISIBLE : View.GONE);
        epubFontSizeLabel.setText(String.valueOf(epubFontSize));
        epubFontSizeLabel.setContentDescription(getString(
                R.string.epub_font_size_description, epubFontSize));
        boolean enabled = reflowable && pageCount > 0 && !epubFontRelayoutPending;
        decreaseEpubFontButton.setEnabled(enabled && epubFontSize > MIN_EPUB_FONT_SIZE);
        increaseEpubFontButton.setEnabled(enabled && epubFontSize < MAX_EPUB_FONT_SIZE);
        bookmarkButton.setEnabled(!epubFontRelayoutPending);
    }

    private int readEpubFontSize() {
        int saved = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getInt(progressKey(documentId) + ".epub_font_size", 12);
        return Math.max(MIN_EPUB_FONT_SIZE, Math.min(MAX_EPUB_FONT_SIZE, saved));
    }

    private void changeEpubFontSize(final int change) {
        if (!reflowable || document == null || pageCount == 0 || epubFontRelayoutPending) {
            return;
        }
        final int previousSize = epubFontSize;
        final int newSize = Math.max(MIN_EPUB_FONT_SIZE,
                Math.min(MAX_EPUB_FONT_SIZE, previousSize + change));
        if (newSize == previousSize) {
            return;
        }
        final int pageIndex = Math.max(0, Math.min(requestedPage, displayPages.size() - 1));
        final PageItem currentItem = displayPages.get(pageIndex);
        final int width = pageImage.getWidth();
        final int height = pageImage.getHeight();
        if (width <= 0 || height <= 0) {
            return;
        }
        final float pageWidth = width * 72f / getResources().getDisplayMetrics().densityDpi;
        final float pageHeight = height * 72f / getResources().getDisplayMetrics().densityDpi;
        final int requestId = ++renderRequestId;
        closeBookmarkPanel();
        epubFontRelayoutPending = true;
        epubFontSize = newSize;
        status.setText(R.string.epub_font_reflowing);
        updateEpubFontControls();
        worker.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    long location = document.makeBookmark(currentItem.sourcePage);
                    document.layout(pageWidth, pageHeight, newSize);
                    final int count = document.countPages();
                    if (count < 1) {
                        throw new IllegalStateException("EPUB has no pages after relayout");
                    }
                    int bookmarkedPage = document.findBookmark(location);
                    final int targetPage = bookmarkedPage >= 0 && bookmarkedPage < count
                            ? bookmarkedPage : Math.min(currentItem.sourcePage, count - 1);
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed || requestId != renderRequestId) {
                                return;
                            }
                            sourcePageCount = count;
                            displayPages.clear();
                            for (int page = 0; page < count; page++) {
                                displayPages.add(new PageItem(page, 0, null));
                            }
                            pageCount = count;
                            requestedPage = targetPage;
                            currentPage = targetPage;
                            epubFontRelayoutPending = false;
                            getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                                    .putInt(progressKey(documentId) + ".epub_font_size", newSize)
                                    .apply();
                            updateNavigation();
                            updateEpubFontControls();
                            requestPage(targetPage);
                        }
                    });
                } catch (RuntimeException exception) {
                    try {
                        document.layout(pageWidth, pageHeight, previousSize);
                    } catch (RuntimeException restoreException) {
                        exception.addSuppressed(restoreException);
                    }
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (destroyed || requestId != renderRequestId) {
                                return;
                            }
                            epubFontSize = previousSize;
                            epubFontRelayoutPending = false;
                            status.setText(R.string.epub_font_error);
                            updateEpubFontControls();
                        }
                    });
                }
            }
        });
    }

    private void postDocumentReady(final int count, final int startPage, final int startPart) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed) {
                    return;
                }
                sourcePageCount = count;
                restoredSourcePage = startPage;
                restoredPart = startPart;
                restoringPosition = pdfDocument && startPart > 0;
                displayPages.clear();
                for (int page = 0; page < count; page++) {
                    displayPages.add(new PageItem(page, 0, null));
                }
                pageCount = displayPages.size();
                currentPage = findDisplayIndex(startPage, startPart);
                requestedPage = currentPage;
                updateNavigation();
                updateEpubFontControls();
                requestPage(currentPage);
            }
        });
    }

    private void postOpenError(final String message) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!destroyed) {
                    showOpenError(message);
                }
            }
        });
    }

    private void showOpenError(String message) {
        status.setText(message == null || message.length() == 0
                ? getString(R.string.book_open_error) : message);
        previousButton.setEnabled(false);
        nextButton.setEnabled(false);
    }

    private void movePage(int direction) {
        if (epubFontRelayoutPending) {
            return;
        }
        restoringPosition = false;
        pageCount = displayPages.size();
        int nextPage = requestedPage + direction;
        if (nextPage < 0 || nextPage >= pageCount) {
            return;
        }
        requestedPage = nextPage;
        if (nextPage < pageCount - 1) {
            markFinished(false);
        }
        requestPage(nextPage);
    }

    private void requestPage(final int pageNumber) {
        if (pageNumber < 0 || pageNumber >= displayPages.size()) {
            return;
        }
        final int requestId = ++renderRequestId;
        final PageItem pageItem = displayPages.get(pageNumber);
        final int width = pageImage.getWidth();
        final int height = pageImage.getHeight();
        if (width <= 0 || height <= 0) {
            pageImage.post(new Runnable() {
                @Override
                public void run() {
                    if (!destroyed) {
                        requestPage(pageNumber);
                    }
                }
            });
            return;
        }
        status.setText(R.string.book_loading_page);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                if (requestId != renderRequestId || document == null) {
                    return;
                }
                Bitmap bitmap;
                List<RectF> imageBounds = Collections.emptyList();
                try {
                    if (pageItem.convertedImage != null) {
                        bitmap = BitmapFileIO.readBitmap(pageItem.convertedImage);
                    } else {
                        if (pdfDocument) {
                            List<File> cached = pageCache.load(pageItem.sourcePage);
                            if (!cached.isEmpty()) {
                                postAdaptedPages(pageItem.sourcePage, cached);
                                return;
                            }
                        }
                        Page page = document.loadPage(pageItem.sourcePage);
                        try {
                            bitmap = AndroidDrawDevice.drawPageFit(page, width, height);
                            if (reflowable) {
                                imageBounds = EpubImageBounds.find(page, width, height);
                            }
                        } finally {
                            page.destroy();
                        }
                    }
                } catch (IOException exception) {
                    if (pageItem.convertedImage != null) {
                        pageCache.discard(pageItem.sourcePage);
                        postAdaptedPages(pageItem.sourcePage, Collections.<File>emptyList());
                    } else {
                        postPageError(requestId);
                    }
                    return;
                } catch (RuntimeException exception) {
                    postPageError(requestId);
                    return;
                }
                postPageBitmap(requestId, pageItem, width, height, bitmap, imageBounds);
            }
        });
    }

    private void postPageBitmap(final int requestId, final PageItem pageItem,
                                final int width, final int height,
                                final Bitmap bitmap, final List<RectF> imageBounds) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                int currentIndex = displayPages.indexOf(pageItem);
                if (destroyed || requestId != renderRequestId || currentIndex < 0) {
                    bitmap.recycle();
                    return;
                }
                Bitmap previousBitmap = displayedBitmap;
                displayedBitmap = bitmap;
                displayedImageBounds = imageBounds;
                pageImage.setImageBitmap(bitmap);
                if (previousBitmap != null) {
                    previousBitmap.recycle();
                }
                currentPage = currentIndex;
                requestedPage = currentIndex;
                pageCount = displayPages.size();
                updateNavigation();
                status.setText("");
                if (!restoringPosition || pageItem.sourcePage != restoredSourcePage
                        || pageItem.convertedImage != null) {
                    savePosition(pageItem);
                }
                if (currentPage == pageCount - 1) {
                    markFinished(true);
                }
                if (pdfDocument) {
                    startBookConversion(width, height);
                }
            }
        });
    }

    private void postPageError(final int requestId) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!destroyed && requestId == renderRequestId) {
                    requestedPage = currentPage;
                    status.setText(R.string.book_page_error);
                }
            }
        });
    }

    private void updateNavigation() {
        pageCount = displayPages.size();
        if (pageCount == 0) {
            previousButton.setEnabled(false);
            nextButton.setEnabled(false);
            pageLabel.setText("");
            updateBookmarkButton();
            return;
        }
        currentPage = Math.max(0, Math.min(currentPage, pageCount - 1));
        pageLabel.setText(getString(R.string.book_page_label, currentPage + 1, pageCount));
        previousButton.setEnabled(currentPage > 0);
        nextButton.setEnabled(currentPage + 1 < pageCount);
        updateBookmarkButton();
    }

    private void toggleBookmarkPanel() {
        if (bookmarkPanel.getVisibility() == View.VISIBLE) {
            closeBookmarkPanel();
            return;
        }
        refreshBookmarkPanel();
        bookmarkDismissArea.setVisibility(View.VISIBLE);
        bookmarkPanel.setVisibility(View.VISIBLE);
    }

    private void closeBookmarkPanel() {
        bookmarkPanel.setVisibility(View.GONE);
        bookmarkDismissArea.setVisibility(View.GONE);
    }

    private void refreshBookmarkPanel() {
        bookmarkList.removeAllViews();
        PageItem current = currentPage >= 0 && currentPage < displayPages.size()
                ? displayPages.get(currentPage) : null;
        boolean currentSaved = current != null && findBookmark(readBookmarks(),
                current.sourcePage, current.part) != null;
        addBookmarkButton.setText(currentSaved
                ? R.string.bookmark_remove_page : R.string.bookmark_add_page);
        addBookmarkButton.setEnabled(current != null && document != null);

        ArrayList<BookmarkEntry> bookmarks = readBookmarks();
        if (bookmarks.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.bookmarks_empty);
            empty.setTextColor(Color.DKGRAY);
            empty.setPadding(dp(4), dp(8), dp(4), dp(8));
            bookmarkList.addView(empty);
            return;
        }
        for (final BookmarkEntry entry : bookmarks) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            Button open = button(R.string.bookmark_open);
            if (entry.part == 0) {
                open.setText(getString(R.string.bookmark_page, entry.sourcePage + 1));
            } else {
                open.setText(getString(R.string.bookmark_page_part,
                        entry.sourcePage + 1, entry.part + 1));
            }
            LinearLayout.LayoutParams openParams = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1);
            openParams.rightMargin = dp(4);
            row.addView(open, openParams);
            open.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    openBookmark(entry);
                }
            });
            Button remove = button(R.string.bookmark_remove);
            remove.setMinWidth(dp(40));
            row.addView(remove);
            remove.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View view) {
                    removeBookmark(entry);
                }
            });
            bookmarkList.addView(row, margins(0, 0, 0, 4));
        }
    }

    private void toggleCurrentBookmark() {
        if (currentPage < 0 || currentPage >= displayPages.size()) {
            return;
        }
        final PageItem item = displayPages.get(currentPage);
        ArrayList<BookmarkEntry> bookmarks = readBookmarks();
        BookmarkEntry existing = findBookmark(bookmarks, item.sourcePage, item.part);
        if (existing != null) {
            bookmarks.remove(existing);
            saveBookmarks(bookmarks);
            refreshBookmarkPanel();
            updateBookmarkButton();
            return;
        }
        if (document == null) {
            status.setText(R.string.bookmark_save_error);
            return;
        }
        addBookmarkButton.setEnabled(false);
        addBookmarkButton.setText(R.string.bookmark_saving);
        worker.execute(new Runnable() {
            @Override
            public void run() {
                final long location;
                try {
                    location = reflowable ? document.makeBookmark(item.sourcePage) : -1;
                } catch (RuntimeException exception) {
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!destroyed) {
                                status.setText(R.string.bookmark_save_error);
                                refreshBookmarkPanel();
                            }
                        }
                    });
                    return;
                }
                mainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed) {
                            return;
                        }
                        ArrayList<BookmarkEntry> currentBookmarks = readBookmarks();
                        if (findBookmark(currentBookmarks, item.sourcePage, item.part) == null) {
                            currentBookmarks.add(new BookmarkEntry(
                                    item.sourcePage, item.part, location));
                            saveBookmarks(currentBookmarks);
                        }
                        refreshBookmarkPanel();
                        updateBookmarkButton();
                    }
                });
            }
        });
    }

    private void removeBookmark(BookmarkEntry entry) {
        ArrayList<BookmarkEntry> bookmarks = readBookmarks();
        BookmarkEntry existing = findBookmark(bookmarks, entry.sourcePage, entry.part);
        if (existing != null) {
            bookmarks.remove(existing);
            saveBookmarks(bookmarks);
        }
        refreshBookmarkPanel();
        updateBookmarkButton();
    }

    private void openBookmark(final BookmarkEntry entry) {
        closeBookmarkPanel();
        if (reflowable && entry.location >= 0 && document != null) {
            worker.execute(new Runnable() {
                @Override
                public void run() {
                    int page = entry.sourcePage;
                    try {
                        int locatedPage = document.findBookmark(entry.location);
                        if (locatedPage >= 0 && locatedPage < sourcePageCount) {
                            page = locatedPage;
                        }
                    } catch (RuntimeException exception) {
                        postBookmarkNavigationError();
                        return;
                    }
                    final int targetPage = page;
                    mainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (!destroyed) {
                                navigateToPage(targetPage, 0);
                            }
                        }
                    });
                }
            });
        } else {
            navigateToPage(entry.sourcePage, entry.part);
        }
    }

    private void postBookmarkNavigationError() {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (!destroyed) {
                    status.setText(R.string.bookmark_open_error);
                }
            }
        });
    }

    private void navigateToPage(int sourcePage, int part) {
        if (displayPages.isEmpty()) {
            return;
        }
        restoringPosition = false;
        int target = findDisplayIndex(sourcePage, part);
        requestedPage = target;
        requestPage(target);
    }

    private ArrayList<BookmarkEntry> readBookmarks() {
        String key = progressKey(documentId) + ".bookmarks";
        Set<String> saved = getSharedPreferences(PREFERENCES, MODE_PRIVATE)
                .getStringSet(key, Collections.<String>emptySet());
        ArrayList<BookmarkEntry> result = new ArrayList<BookmarkEntry>();
        if (saved != null) {
            for (String value : saved) {
                String[] fields = value.split(":");
                if (fields.length != 3) {
                    continue;
                }
                try {
                    int sourcePage = Integer.parseInt(fields[0]);
                    int part = Integer.parseInt(fields[1]);
                    long location = Long.parseLong(fields[2]);
                    if (sourcePage >= 0 && part >= 0) {
                        result.add(new BookmarkEntry(sourcePage, part, location));
                    }
                } catch (NumberFormatException ignored) {
                }
            }
        }
        Collections.sort(result, new Comparator<BookmarkEntry>() {
            @Override
            public int compare(BookmarkEntry left, BookmarkEntry right) {
                if (left.sourcePage != right.sourcePage) {
                    return left.sourcePage < right.sourcePage ? -1 : 1;
                }
                if (left.part != right.part) {
                    return left.part < right.part ? -1 : 1;
                }
                return 0;
            }
        });
        return result;
    }

    private void saveBookmarks(List<BookmarkEntry> bookmarks) {
        Set<String> saved = new HashSet<String>();
        for (BookmarkEntry entry : bookmarks) {
            saved.add(entry.encode());
        }
        String key = progressKey(documentId) + ".bookmarks";
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .putStringSet(key, saved)
                .apply();
    }

    private BookmarkEntry findBookmark(List<BookmarkEntry> bookmarks, int sourcePage, int part) {
        for (BookmarkEntry entry : bookmarks) {
            if (entry.sourcePage == sourcePage && entry.part == part) {
                return entry;
            }
        }
        return null;
    }

    private void updateBookmarkButton() {
        if (bookmarkButton == null) {
            return;
        }
        boolean saved = false;
        if (currentPage >= 0 && currentPage < displayPages.size()) {
            PageItem item = displayPages.get(currentPage);
            saved = findBookmark(readBookmarks(), item.sourcePage, item.part) != null;
        }
        bookmarkButton.setBookmarked(saved);
    }

    private RectF findEpubImageAt(float viewX, float viewY) {
        if (!reflowable || pdfDocument || displayedBitmap == null
                || displayedImageBounds.isEmpty()) {
            return null;
        }
        float scale = Math.min(pageImage.getWidth() / (float) displayedBitmap.getWidth(),
                pageImage.getHeight() / (float) displayedBitmap.getHeight());
        float offsetX = (pageImage.getWidth() - displayedBitmap.getWidth() * scale) / 2f;
        float offsetY = (pageImage.getHeight() - displayedBitmap.getHeight() * scale) / 2f;
        float bitmapX = (viewX - offsetX) / scale;
        float bitmapY = (viewY - offsetY) / scale;
        for (RectF bounds : displayedImageBounds) {
            if (bounds.contains(bitmapX, bitmapY)) {
                return bounds;
            }
        }
        return null;
    }

    private void showImageZoom(RectF imageBounds) {
        if (displayedBitmap == null || zoomOverlay != null) {
            return;
        }
        int left = Math.max(0, Math.min(displayedBitmap.getWidth() - 1,
                (int) Math.floor(imageBounds.left)));
        int top = Math.max(0, Math.min(displayedBitmap.getHeight() - 1,
                (int) Math.floor(imageBounds.top)));
        int right = Math.max(left + 1, Math.min(displayedBitmap.getWidth(),
                (int) Math.ceil(imageBounds.right)));
        int bottom = Math.max(top + 1, Math.min(displayedBitmap.getHeight(),
                (int) Math.ceil(imageBounds.bottom)));
        zoomBitmap = Bitmap.createBitmap(displayedBitmap, left, top,
                right - left, bottom - top);
        zoomOverlay = new FrameLayout(this);
        zoomOverlay.setBackgroundColor(Color.WHITE);
        zoomOverlay.setClickable(true);
        zoomOverlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                closeImageZoom();
            }
        });
        zoomImage = new ImageView(this);
        zoomImage.setScaleType(ImageView.ScaleType.FIT_CENTER);
        zoomImage.setImageBitmap(zoomBitmap);
        zoomOverlay.addView(zoomImage, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        TextView hint = new TextView(this);
        hint.setText(R.string.image_zoom_hint);
        hint.setTextColor(Color.DKGRAY);
        hint.setGravity(Gravity.CENTER);
        hint.setBackgroundColor(Color.LTGRAY);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(36), Gravity.BOTTOM);
        zoomOverlay.addView(hint, hintParams);
        rootContainer.addView(zoomOverlay, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
    }

    private void closeImageZoom() {
        if (zoomOverlay != null) {
            rootContainer.removeView(zoomOverlay);
            zoomOverlay = null;
        }
        if (zoomImage != null) {
            zoomImage.setImageDrawable(null);
            zoomImage = null;
        }
        if (zoomBitmap != null && zoomBitmap != displayedBitmap) {
            zoomBitmap.recycle();
        }
        zoomBitmap = null;
    }

    private GradientDrawable panelBackground() {
        GradientDrawable background = new GradientDrawable();
        background.setColor(Color.WHITE);
        background.setCornerRadius(dp(2));
        background.setStroke(dp(1), Color.DKGRAY);
        return background;
    }

    private int findDisplayIndex(int sourcePage, int part) {
        int firstMatch = -1;
        for (int index = 0; index < displayPages.size(); index++) {
            PageItem item = displayPages.get(index);
            if (item.sourcePage == sourcePage) {
                if (firstMatch < 0) {
                    firstMatch = index;
                }
                if (item.part == part) {
                    return index;
                }
            }
        }
        return firstMatch >= 0 ? firstMatch
                : Math.max(0, Math.min(sourcePage, displayPages.size() - 1));
    }

    private boolean hasSameAdaptation(int sourcePage, List<File> images) {
        int part = 0;
        for (PageItem item : displayPages) {
            if (item.sourcePage != sourcePage) {
                continue;
            }
            if (images.isEmpty()) {
                return part == 0 && item.part == 0 && item.convertedImage == null
                        && countPagesForSource(sourcePage) == 1;
            }
            if (part >= images.size() || item.part != part
                    || !images.get(part).equals(item.convertedImage)) {
                return false;
            }
            part++;
        }
        return !images.isEmpty() && part == images.size();
    }

    private int countPagesForSource(int sourcePage) {
        int count = 0;
        for (PageItem item : displayPages) {
            if (item.sourcePage == sourcePage) {
                count++;
            }
        }
        return count;
    }

    private void postAdaptedPages(final int sourcePage, final List<File> images) {
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed || sourcePage < 0 || sourcePage >= sourcePageCount) {
                    return;
                }
                if (hasSameAdaptation(sourcePage, images)) {
                    return;
                }
                PageItem current = displayPages.get(currentPage);
                PageItem requested = displayPages.get(requestedPage);
                int currentSourcePage = current.sourcePage;
                int currentPart = current.part;
                int requestedSourcePage = requested.sourcePage;
                int requestedPart = requested.part;
                boolean pageRequestPending = currentPage != requestedPage;
                boolean currentPageReplaced = currentSourcePage == sourcePage;
                boolean requestedPageReplaced = requestedSourcePage == sourcePage;
                int insertionIndex = displayPages.size();
                for (int index = displayPages.size() - 1; index >= 0; index--) {
                    if (displayPages.get(index).sourcePage == sourcePage) {
                        insertionIndex = index;
                        displayPages.remove(index);
                    }
                }
                if (insertionIndex > displayPages.size()) {
                    insertionIndex = displayPages.size();
                }
                if (images.isEmpty()) {
                    displayPages.add(insertionIndex, new PageItem(sourcePage, 0, null));
                    restoringPosition = false;
                } else {
                    for (int part = 0; part < images.size(); part++) {
                        displayPages.add(insertionIndex + part,
                                new PageItem(sourcePage, part, images.get(part)));
                    }
                }

                if (restoringPosition && sourcePage == restoredSourcePage && !images.isEmpty()) {
                    currentPage = findDisplayIndex(sourcePage, restoredPart);
                    requestedPage = currentPage;
                    restoringPosition = false;
                } else {
                    currentPage = findDisplayIndex(currentSourcePage, currentPart);
                    requestedPage = findDisplayIndex(requestedSourcePage, requestedPart);
                }
                updateNavigation();
                markFinished(currentPage == displayPages.size() - 1);
                if (requestedPageReplaced
                        || (!pageRequestPending && currentPageReplaced)) {
                    renderRequestId++;
                    requestPage(requestedPage);
                }
            }
        });
    }

    private void startBookConversion(final int width, final int height) {
        if (destroyed || !pdfDocument || pageCache == null || conversionStarted) {
            return;
        }
        conversionStarted = true;
        final int totalPages = sourcePageCount;
        final int dpi = Math.max(72, getResources().getDisplayMetrics().densityDpi);
        updateConversionProgress(0, totalPages);
        converter.execute(new Runnable() {
            @Override
            public void run() {
                if (destroyed) {
                    return;
                }
                Document conversionDocument = null;
                try {
                    if (cachedDocument == null || !cachedDocument.isFile()) {
                        updateConversionProgress(totalPages, totalPages);
                        return;
                    }
                    conversionDocument = Document.openDocument(cachedDocument.getAbsolutePath());
                    int completedPages = 0;
                    for (int sourcePage = 0; sourcePage < totalPages; sourcePage++) {
                        if (destroyed) {
                            return;
                        }
                        try {
                            List<File> images = pageCache.load(sourcePage);
                            if (images.isEmpty()) {
                                images = convertSourcePage(conversionDocument, sourcePage,
                                        width, height, dpi);
                            }
                            if (!images.isEmpty()) {
                                postAdaptedPages(sourcePage, images);
                            }
                        } catch (IOException exception) {
                            // Keep the original page when conversion or cache storage fails.
                        } catch (RuntimeException exception) {
                            // Keep the original page when conversion or cache storage fails.
                        } catch (LinkageError error) {
                            // Keep the original page when the native converter is unavailable.
                        }
                        completedPages++;
                        updateConversionProgress(completedPages, totalPages);
                    }
                } catch (RuntimeException exception) {
                    updateConversionProgress(totalPages, totalPages);
                } catch (LinkageError error) {
                    updateConversionProgress(totalPages, totalPages);
                } finally {
                    if (conversionDocument != null) {
                        conversionDocument.destroy();
                    }
                }
            }
        });
    }

    private List<File> convertSourcePage(Document sourceDocument, int sourcePage,
                                         int width, int height, int targetDpi)
            throws IOException {
        File workingDirectory = File.createTempFile("k2-page-", "", getCacheDir());
        if (!workingDirectory.delete() || !workingDirectory.mkdir()) {
            throw new IOException("Unable to create conversion workspace");
        }
        try {
            Page page = sourceDocument.loadPage(sourcePage);
            int sourceDpi;
            Bitmap sourceBitmap;
            try {
                Rect bounds = page.getBounds();
                float pageWidth = bounds.x1 - bounds.x0;
                float pageHeight = bounds.y1 - bounds.y0;
                sourceDpi = conversionSourceDpi(pageWidth, pageHeight);
                if (sourceDpi < MIN_CONVERSION_DPI) {
                    return Collections.emptyList();
                }
                int sourceWidth = Math.max(1,
                        Math.round(pageWidth * sourceDpi / 72f));
                int sourceHeight = Math.max(1,
                        Math.round(pageHeight * sourceDpi / 72f));
                sourceBitmap = AndroidDrawDevice.drawPageFit(
                        page, sourceWidth, sourceHeight);
            } finally {
                page.destroy();
            }
            File inputBitmap = new File(workingDirectory, "input.bmp");
            try {
                BitmapFileIO.writeBmp(sourceBitmap, inputBitmap, sourceDpi);
            } finally {
                sourceBitmap.recycle();
            }
            int count = K2PdfOpt.convertBitmap(inputBitmap.getAbsolutePath(),
                    workingDirectory.getAbsolutePath(), sourceDpi,
                    width, height, targetDpi);
            if (count < 1) {
                return Collections.emptyList();
            }
            ArrayList<File> outputBitmaps = new ArrayList<File>();
            for (int index = 1; index <= count; index++) {
                File output = new File(workingDirectory, String.format(
                        java.util.Locale.US, "page_%03d.bmp", index));
                if (!output.isFile() || output.length() == 0) {
                    return Collections.emptyList();
                }
                outputBitmaps.add(output);
            }
            if (!pageCache.store(sourcePage, outputBitmaps)) {
                return Collections.emptyList();
            }
            return pageCache.load(sourcePage);
        } finally {
            deleteTree(workingDirectory);
        }
    }

    private void updateConversionProgress(final int completedPages, final int totalPages) {
        final int percent = totalPages <= 0 ? 100
                : Math.max(0, Math.min(100, completedPages * 100 / totalPages));
        mainHandler.post(new Runnable() {
            @Override
            public void run() {
                if (destroyed) {
                    return;
                }
                if (percent >= 100) {
                    conversionDot.setVisibility(View.GONE);
                    conversionIndicator.setVisibility(View.GONE);
                    return;
                }
                conversionIndicator.setVisibility(View.VISIBLE);
                conversionPercent.setText(percent + "%");
                conversionDot.setVisibility(View.VISIBLE);
            }
        });
    }

    private static int conversionSourceDpi(float pageWidth, float pageHeight) {
        if (Float.isNaN(pageWidth) || Float.isInfinite(pageWidth) || pageWidth <= 0
                || Float.isNaN(pageHeight) || Float.isInfinite(pageHeight) || pageHeight <= 0) {
            return 0;
        }
        double widthInches = pageWidth / 72.0;
        double heightInches = pageHeight / 72.0;
        double areaLimitDpi = Math.sqrt(MAX_CONVERSION_PIXELS
                / (widthInches * heightInches));
        double dimensionLimitDpi = MAX_CONVERSION_DIMENSION
                / Math.max(widthInches, heightInches);
        return (int) Math.floor(Math.min(MAX_CONVERSION_DPI,
                Math.min(areaLimitDpi, dimensionLimitDpi)));
    }

    private void savePosition(final PageItem item) {
        if (documentId == null) {
            return;
        }
        final String key = progressKey(documentId);
        final int requestId = ++saveRequestId;
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .putBoolean(key + ".opened", true)
                .putInt(key + ".page", item.sourcePage)
                .putInt(key + ".part", item.part)
                .putInt(key + ".count", sourcePageCount)
                .putLong(key + ".opened_at", System.currentTimeMillis())
                .apply();
        if (!reflowable || document == null) {
            return;
        }
        worker.execute(new Runnable() {
            @Override
            public void run() {
                if (requestId != saveRequestId || document == null) {
                    return;
                }
                long bookmark = document.makeBookmark(item.sourcePage);
                if (requestId == saveRequestId) {
                    getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                            .putLong(key + ".bookmark", bookmark)
                            .apply();
                }
            }
        });
    }

    private void markFinished(boolean finished) {
        if (documentId == null) {
            return;
        }
        getSharedPreferences(PREFERENCES, MODE_PRIVATE).edit()
                .putBoolean(progressKey(documentId) + ".finished", finished)
                .apply();
    }

    static String progressKey(String id) {
        return "book." + (id == null ? FALLBACK_ID : id);
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

    private static final class PageItem {
        final int sourcePage;
        final int part;
        final File convertedImage;

        PageItem(int sourcePage, int part, File convertedImage) {
            this.sourcePage = sourcePage;
            this.part = part;
            this.convertedImage = convertedImage;
        }
    }

    private static final class BookmarkEntry {
        final int sourcePage;
        final int part;
        final long location;

        BookmarkEntry(int sourcePage, int part, long location) {
            this.sourcePage = sourcePage;
            this.part = part;
            this.location = location;
        }

        String encode() {
            return sourcePage + ":" + part + ":" + location;
        }
    }

    private final class BookmarkFlagView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean bookmarked;

        BookmarkFlagView(android.content.Context context) {
            super(context);
            setBackgroundColor(Color.LTGRAY);
            setContentDescription(getString(R.string.bookmarks_title));
            setClickable(true);
            setFocusable(true);
        }

        void setBookmarked(boolean saved) {
            if (bookmarked != saved) {
                bookmarked = saved;
                invalidate();
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float density = getResources().getDisplayMetrics().density;
            float poleX = getWidth() / 2f - 2 * density;
            float top = getHeight() / 2f - 12 * density;
            float bottom = getHeight() / 2f + 12 * density;
            paint.setColor(Color.BLACK);
            paint.setStrokeWidth(1.8f * density);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStyle(Paint.Style.STROKE);
            canvas.drawLine(poleX, top, poleX, bottom, paint);

            Path flag = new Path();
            flag.moveTo(poleX, top);
            flag.lineTo(poleX + 13 * density, top);
            flag.lineTo(poleX + 9 * density, top + 7 * density);
            flag.lineTo(poleX + 13 * density, top + 14 * density);
            flag.lineTo(poleX, top + 14 * density);
            flag.close();
            if (bookmarked) {
                paint.setStyle(Paint.Style.FILL);
                canvas.drawPath(flag, paint);
            } else {
                paint.setStyle(Paint.Style.STROKE);
                canvas.drawPath(flag, paint);
            }
        }
    }

    private Button button(int resourceId) {
        Button button = new Button(this);
        button.setText(resourceId);
        button.setTextColor(Color.BLACK);
        button.setTextSize(13);
        button.setAllCaps(false);
        button.setBackgroundColor(Color.LTGRAY);
        button.setMinHeight(dp(44));
        return button;
    }

    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) {
        float density = getResources().getDisplayMetrics().density;
        return Math.round(value * density);
    }
}
