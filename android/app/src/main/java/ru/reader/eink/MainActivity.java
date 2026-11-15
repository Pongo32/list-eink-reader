package ru.reader.eink;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public final class MainActivity extends Activity {
    private static final int REQUEST_OPEN_BOOK = 1;
    private static final int INK = Color.rgb(38, 37, 33);
    private static final int MUTED = Color.rgb(104, 101, 94);
    private static final int PAPER = Color.rgb(248, 247, 242);
    private static final String FILTER_ALL = "all";
    private static final String FILTER_READING = "reading";
    private static final String FILTER_FINISHED = "finished";

    private final ExecutorService scanner = Executors.newSingleThreadExecutor();
    private final ArrayList<Book> books = new ArrayList<Book>();
    private final ArrayList<Book> visibleBooks = new ArrayList<Book>();
    private final ArrayList<Button> filterButtons = new ArrayList<Button>();
    private final ArrayList<String> filterValues = new ArrayList<String>();
    private String selectedFilter = FILTER_ALL;
    private String searchQuery = "";
    private boolean destroyed;
    private GridView bookGrid;
    private TextView scanStatus;
    private TextView emptyState;
    private TextView bookCount;
    private LinearLayout continuePanel;
    private EditText searchField;
    private BookAdapter adapter;
    private Future<?> scanTask;
    private int scanGeneration;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        buildLibrary();
        Uri documentUri = getIntent().getData();
        if (Intent.ACTION_VIEW.equals(getIntent().getAction()) && documentUri != null) {
            openBook(documentUri, getIntent().getFlags(), null, null);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshLibrary();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        scanner.shutdownNow();
        super.onDestroy();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_OPEN_BOOK || resultCode != RESULT_OK || data == null) {
            return;
        }
        Uri documentUri = data.getData();
        if (documentUri == null) {
            Toast.makeText(this, R.string.book_uri_error, Toast.LENGTH_LONG).show();
            return;
        }
        openBook(documentUri, data.getFlags(), null, null);
    }

    private void buildLibrary() {
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setPadding(dp(18), dp(12), dp(18), dp(8));
        screen.setBackgroundColor(PAPER);

        LinearLayout brandRow = new LinearLayout(this);
        brandRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView brand = text("Лист", 28, INK, true);
        brand.setTypeface(Typeface.SERIF, Typeface.NORMAL);
        brandRow.addView(brand, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button addBook = smallButton(R.string.add_book);
        addBook.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                chooseBook();
            }
        });
        brandRow.addView(addBook);
        screen.addView(brandRow, margins(0, 0, 0, 2));
        screen.addView(text(R.string.library_subtitle, 13, MUTED, false),
                margins(0, 0, 0, 12));

        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        bookCount = text("", 12, MUTED, false);
        actionRow.addView(bookCount, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button refresh = smallButton(R.string.refresh_library);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                refreshLibrary();
            }
        });
        actionRow.addView(refresh);
        screen.addView(actionRow, margins(0, 0, 0, 7));

        searchField = new EditText(this);
        searchField.setSingleLine(true);
        searchField.setTextSize(15);
        searchField.setHint(R.string.search_books);
        searchField.setPadding(dp(12), 0, dp(12), 0);
        searchField.setBackgroundColor(Color.WHITE);
        searchField.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence value, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence value, int start, int before, int count) {
                searchQuery = value.toString().trim().toLowerCase(java.util.Locale.getDefault());
                updateVisibleBooks();
            }

            @Override
            public void afterTextChanged(Editable value) {
            }
        });
        screen.addView(searchField, margins(0, 0, 0, 8));

        LinearLayout filters = new LinearLayout(this);
        filters.setOrientation(LinearLayout.HORIZONTAL);
        addFilter(filters, R.string.filter_all, FILTER_ALL);
        addFilter(filters, R.string.filter_reading, FILTER_READING);
        addFilter(filters, R.string.filter_finished, FILTER_FINISHED);
        screen.addView(filters, margins(0, 0, 0, 8));

        scanStatus = text(R.string.library_scanning, 12, MUTED, false);
        screen.addView(scanStatus, margins(0, 0, 0, 5));

        continuePanel = new LinearLayout(this);
        continuePanel.setOrientation(LinearLayout.VERTICAL);
        continuePanel.setPadding(dp(12), dp(8), dp(12), dp(8));
        continuePanel.setBackgroundColor(Color.rgb(232, 230, 222));
        screen.addView(continuePanel, margins(0, 0, 0, 8));

        bookGrid = new GridView(this);
        bookGrid.setNumColumns(2);
        bookGrid.setVerticalSpacing(dp(8));
        bookGrid.setHorizontalSpacing(dp(6));
        bookGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
        bookGrid.setSelector(android.R.color.transparent);
        adapter = new BookAdapter();
        bookGrid.setAdapter(adapter);
        bookGrid.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Book book = visibleBooks.get(position);
                openBook(Uri.fromFile(book.file), 0, book.id, book.title);
            }
        });
        screen.addView(bookGrid, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        emptyState = text(R.string.library_empty, 14, MUTED, false);
        emptyState.setGravity(Gravity.CENTER);
        screen.addView(emptyState, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(screen);
        updateVisibleBooks();
    }

    private void addFilter(LinearLayout filters, int label, final String filter) {
        final Button button = smallButton(label);
        filterButtons.add(button);
        filterValues.add(filter);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                selectedFilter = filter;
                updateVisibleBooks();
            }
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(38), 1);
        if (filters.getChildCount() > 0) {
            params.leftMargin = dp(5);
        }
        filters.addView(button, params);
    }

    private void refreshLibrary() {
        if (destroyed || scanStatus == null) {
            return;
        }
        if (scanTask != null) {
            scanTask.cancel(true);
        }
        final int generation = ++scanGeneration;
        scanStatus.setText(R.string.library_scanning);
        scanTask = scanner.submit(new Runnable() {
            @Override
            public void run() {
                final BookScanner.Result result = BookScanner.scan(getApplicationContext(), null);
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (destroyed || generation != scanGeneration) {
                            return;
                        }
                        books.clear();
                        android.content.SharedPreferences preferences =
                                getSharedPreferences(ReaderActivity.PREFERENCES, MODE_PRIVATE);
                        for (Book scanned : result.books) {
                            String key = ReaderActivity.progressKey(scanned.id);
                            boolean opened = preferences.getBoolean(key + ".opened", false);
                            boolean finished = preferences.getBoolean(key + ".finished", false);
                            int lastPage = preferences.getInt(key + ".page", 0);
                            int pageCount = preferences.getInt(key + ".count", 0);
                            books.add(new Book(scanned.file, scanned.id, lastPage, pageCount,
                                    opened, finished));
                        }
                        scanStatus.setText(result.inaccessibleDirectories
                                ? R.string.library_scan_partial
                                : R.string.library_scan_complete);
                        updateVisibleBooks();
                    }
                });
            }
        });
    }

    private void updateVisibleBooks() {
        if (adapter == null) {
            return;
        }
        visibleBooks.clear();
        for (Book book : books) {
            if (FILTER_READING.equals(selectedFilter) && (!book.opened || book.finished)) {
                continue;
            }
            if (FILTER_FINISHED.equals(selectedFilter) && !book.finished) {
                continue;
            }
            if (!searchQuery.isEmpty()
                    && !book.title.toLowerCase(java.util.Locale.getDefault()).contains(searchQuery)) {
                continue;
            }
            visibleBooks.add(book);
        }
        adapter.notifyDataSetChanged();
        for (int i = 0; i < filterButtons.size(); i++) {
            boolean selected = selectedFilter.equals(filterValues.get(i));
            Button button = filterButtons.get(i);
            button.setTextColor(selected ? Color.WHITE : INK);
            button.setBackgroundColor(selected ? INK : Color.rgb(232, 230, 222));
        }
        bookCount.setText(getString(R.string.library_book_count, visibleBooks.size()));
        boolean hasBooks = !visibleBooks.isEmpty();
        bookGrid.setVisibility(hasBooks ? View.VISIBLE : View.GONE);
        emptyState.setVisibility(hasBooks ? View.GONE : View.VISIBLE);
        emptyState.setText(books.isEmpty() ? R.string.library_empty : R.string.library_no_matches);
        updateContinuePanel();
    }

    private void updateContinuePanel() {
        continuePanel.removeAllViews();
        Book latest = null;
        long latestOpened = Long.MIN_VALUE;
        android.content.SharedPreferences preferences =
                getSharedPreferences(ReaderActivity.PREFERENCES, MODE_PRIVATE);
        for (Book book : books) {
            if (!book.opened || book.finished) {
                continue;
            }
            long openedAt = preferences.getLong(
                    ReaderActivity.progressKey(book.id) + ".opened_at", 0);
            if (latest == null || openedAt > latestOpened) {
                latest = book;
                latestOpened = openedAt;
            }
        }
        if (latest == null) {
            continuePanel.setVisibility(View.GONE);
            return;
        }
        continuePanel.setVisibility(View.VISIBLE);
        continuePanel.addView(text(R.string.continue_reading, 11, MUTED, true));
        Button resume = smallButton(latest.title);
        resume.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        resume.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Book book = (Book) view.getTag();
                openBook(Uri.fromFile(book.file), 0, book.id, book.title);
            }
        });
        resume.setTag(latest);
        continuePanel.addView(resume);
    }

    private void chooseBook() {
        Intent openDocument = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        openDocument.addCategory(Intent.CATEGORY_OPENABLE);
        openDocument.setType("*/*");
        openDocument.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[] {"application/pdf", "application/epub+zip"});
        try {
            startActivityForResult(openDocument, REQUEST_OPEN_BOOK);
        } catch (ActivityNotFoundException exception) {
            Intent getContent = new Intent(Intent.ACTION_GET_CONTENT);
            getContent.addCategory(Intent.CATEGORY_OPENABLE);
            getContent.setType("*/*");
            try {
                startActivityForResult(getContent, REQUEST_OPEN_BOOK);
            } catch (ActivityNotFoundException fallbackException) {
                Toast.makeText(this, R.string.book_no_picker_error, Toast.LENGTH_LONG).show();
            }
        }
    }

    private void openBook(Uri uri, int sourceFlags, String bookId, String title) {
        Intent reader = new Intent(this, ReaderActivity.class);
        reader.setData(uri);
        reader.addFlags(sourceFlags & Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (bookId != null) {
            reader.putExtra(ReaderActivity.EXTRA_BOOK_ID, bookId);
        }
        if (title != null) {
            reader.putExtra(ReaderActivity.EXTRA_BOOK_TITLE, title);
        }
        startActivity(reader);
    }

    private Button smallButton(int resourceId) {
        return smallButton(getString(resourceId));
    }

    private Button smallButton(String label) {
        Button button = new Button(this);
        button.setText(label);
        button.setTextSize(12);
        button.setTextColor(INK);
        button.setAllCaps(false);
        button.setMinHeight(dp(38));
        button.setPadding(dp(8), 0, dp(8), 0);
        button.setBackgroundColor(Color.rgb(232, 230, 222));
        return button;
    }

    private TextView text(int resourceId, int sizeSp, int color, boolean bold) {
        TextView label = text(getString(resourceId), sizeSp, color, bold);
        return label;
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(sizeSp);
        label.setTextColor(color);
        if (bold) {
            label.setTypeface(null, Typeface.BOLD);
        }
        return label;
    }

    private LinearLayout.LayoutParams margins(int left, int top, int right, int bottom) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(dp(left), dp(top), dp(right), dp(bottom));
        return params;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private final class BookAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return visibleBooks.size();
        }

        @Override
        public Object getItem(int position) {
            return visibleBooks.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, android.view.ViewGroup parent) {
            final Book book = visibleBooks.get(position);
            LinearLayout card = convertView instanceof LinearLayout
                    ? (LinearLayout) convertView : new LinearLayout(MainActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setGravity(Gravity.CENTER_HORIZONTAL);
            card.setPadding(dp(6), dp(4), dp(6), dp(6));
            card.setBackgroundColor(PAPER);
            card.removeAllViews();

            LinearLayout cover = new LinearLayout(MainActivity.this);
            cover.setOrientation(LinearLayout.VERTICAL);
            cover.setGravity(Gravity.CENTER);
            cover.setPadding(dp(10), dp(12), dp(10), dp(12));
            cover.setBackgroundColor(Color.rgb(222, 220, 212));
            TextView mark = text(book.format, 11, MUTED, true);
            mark.setGravity(Gravity.CENTER);
            cover.addView(mark, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
            TextView coverTitle = text(book.title, 15, INK, true);
            coverTitle.setTypeface(Typeface.SERIF, Typeface.BOLD);
            coverTitle.setGravity(Gravity.CENTER);
            coverTitle.setMaxLines(3);
            coverTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
            cover.addView(coverTitle, new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, 0, 2));
            LinearLayout.LayoutParams coverParams = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(148));
            card.addView(cover, coverParams);

            TextView title = text(book.title, 14, INK, true);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setGravity(Gravity.CENTER);
            card.addView(title, margins(0, 6, 0, 1));
            String state = book.finished ? getString(R.string.book_finished)
                    : book.opened ? getString(R.string.book_in_progress)
                    : book.format;
            TextView detail = text(state, 11, MUTED, false);
            detail.setGravity(Gravity.CENTER);
            card.addView(detail);
            if (book.pageCount > 1 && book.opened) {
                int percent = Math.max(0, Math.min(100,
                        book.lastPage * 100 / Math.max(1, book.pageCount - 1)));
                FrameLayout track = new FrameLayout(MainActivity.this);
                track.setBackgroundColor(Color.rgb(205, 203, 195));
                int trackWidth = Math.max(dp(40), parent.getWidth() / 2 - dp(22));
                FrameLayout.LayoutParams progressParams = new FrameLayout.LayoutParams(
                        Math.max(1, trackWidth * percent / 100), dp(3), Gravity.LEFT);
                View progress = new View(MainActivity.this);
                progress.setBackgroundColor(INK);
                track.addView(progress, progressParams);
                LinearLayout.LayoutParams trackParams = new LinearLayout.LayoutParams(
                        trackWidth, dp(3));
                trackParams.topMargin = dp(5);
                card.addView(track, trackParams);
            }
            card.setTag(book);
            return card;
        }
    }
}
