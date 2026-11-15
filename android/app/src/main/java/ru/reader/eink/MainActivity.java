package ru.reader.eink;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.graphics.drawable.StateListDrawable;
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
    private static final int INK = Ui.INK;
    private static final int MUTED = Ui.SECONDARY;
    private static final int PAPER = Ui.WHITE;
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
        TextView brand = text("Лист", Ui.TEXT_TITLE, INK, true);
        brand.setTypeface(Typeface.SERIF, Typeface.NORMAL);
        brandRow.addView(brand, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        final View searchButton = new SearchIconView();
        brandRow.addView(searchButton, new LinearLayout.LayoutParams(dp(Ui.TOUCH), dp(Ui.TOUCH)));
        Button addBook = smallButton(R.string.add_book);
        addBook.setContentDescription(getString(R.string.add_book_description));
        brandRow.addView(addBook, new LinearLayout.LayoutParams(dp(Ui.TOUCH), dp(Ui.TOUCH)));
        addBook.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                chooseBook();
            }
        });
        screen.addView(brandRow, margins(0, 0, 0, 2));
        screen.addView(Ui.divider(this), new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));

        bookCount = text("", Ui.TEXT_SMALL, MUTED, false);

        searchField = new EditText(this);
        searchField.setSingleLine(true);
        searchField.setTextSize(Ui.TEXT_BODY);
        searchField.setHint(R.string.search_books);
        searchField.setPadding(dp(12), 0, dp(12), 0);
        searchField.setBackground(Ui.box(this, Ui.WHITE, Ui.INK));
        searchField.setVisibility(View.GONE);
        searchButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                boolean show = searchField.getVisibility() != View.VISIBLE;
                searchField.setVisibility(show ? View.VISIBLE : View.GONE);
                if (show) {
                    searchField.requestFocus();
                } else {
                    searchField.setText("");
                    screen.requestFocus();
                }
            }
        });
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

        scanStatus = text(R.string.library_scanning, Ui.TEXT_SMALL, MUTED, false);

        continuePanel = new LinearLayout(this);
        continuePanel.setOrientation(LinearLayout.VERTICAL);
        continuePanel.setPadding(dp(12), dp(8), dp(12), dp(8));
        continuePanel.setBackground(Ui.box(this, Ui.WHITE, Ui.INK));
        screen.addView(continuePanel, margins(0, 0, 0, 8));

        bookGrid = new GridView(this);
        bookGrid.setNumColumns(3);
        bookGrid.setVerticalSpacing(dp(8));
        bookGrid.setHorizontalSpacing(dp(6));
        bookGrid.setStretchMode(GridView.STRETCH_COLUMN_WIDTH);
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
        LinearLayout statusRow = new LinearLayout(this);
        statusRow.setGravity(Gravity.CENTER_VERTICAL);
        statusRow.addView(bookCount, new LinearLayout.LayoutParams(0,
            dp(Ui.TOUCH), 1));
        statusRow.addView(scanStatus);
        screen.addView(statusRow);
        setContentView(screen);
        screen.setFocusableInTouchMode(true);
        screen.requestFocus();
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
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(Ui.TOUCH), 1);
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
                            ? getString(R.string.library_scan_partial) : "");
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
            Ui.styleTab(button, selected);
        }
        bookCount.setText(getString(R.string.library_book_count, books.size()));
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
        continuePanel.addView(text(R.string.continue_reading, Ui.TEXT_SMALL, MUTED, true));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout cover = new LinearLayout(this);
        cover.setOrientation(LinearLayout.VERTICAL);
        cover.setGravity(Gravity.CENTER);
        cover.setPadding(dp(6), dp(6), dp(6), dp(6));
        cover.setBackground(Ui.box(this, Ui.WHITE, Ui.INK));
        TextView coverTitle = text(latest.title, Ui.TEXT_SMALL, INK, true);
        coverTitle.setTypeface(Typeface.SERIF, Typeface.BOLD);
        coverTitle.setGravity(Gravity.CENTER);
        coverTitle.setMaxLines(3);
        coverTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
        cover.addView(coverTitle, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        TextView coverFormat = text(latest.format, Ui.TEXT_SMALL, MUTED, true);
        coverFormat.setGravity(Gravity.CENTER);
        cover.addView(coverFormat);
        row.addView(cover, new LinearLayout.LayoutParams(dp(80), dp(112)));

        LinearLayout details = new LinearLayout(this);
        details.setOrientation(LinearLayout.VERTICAL);
        details.setPadding(dp(10), 0, 0, 0);
        TextView title = text(latest.title, Ui.TEXT_BODY, INK, true);
        title.setTypeface(Typeface.SERIF, Typeface.BOLD);
        title.setMaxLines(2);
        title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        details.addView(title);
        int percent = latest.pageCount > 1 ? Math.max(0, Math.min(100,
            latest.lastPage * 100 / (latest.pageCount - 1))) : 0;
        TextView progress = text(getString(R.string.continue_progress,
                percent, Math.max(1, latest.lastPage + 1), latest.pageCount),
                Ui.TEXT_SMALL, MUTED, false);
        details.addView(progress, margins(0, 2, 0, 4));
        Button resume = smallButton(R.string.continue_button);
        resume.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                Book book = (Book) view.getTag();
                openBook(Uri.fromFile(book.file), 0, book.id, book.title);
            }
        });
        resume.setTag(latest);
        details.addView(resume);
        row.addView(details, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        continuePanel.addView(row);
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
        Ui.styleButton(button);
        return button;
    }

    private TextView text(int resourceId, int sizeSp, int color, boolean bold) {
        TextView label = text(getString(resourceId), sizeSp, color, bold);
        return label;
    }

    private TextView text(String value, int sizeSp, int color, boolean bold) {
        TextView label = new TextView(this);
        label.setText(value);
        label.setTextSize(Math.max(Ui.TEXT_SMALL, sizeSp));
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

    private final class SearchIconView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        SearchIconView() {
            super(MainActivity.this);
            StateListDrawable background = new StateListDrawable();
            background.addState(new int[] {android.R.attr.state_pressed},
                    Ui.box(getContext(), Ui.INK, Ui.INK));
                background.addState(new int[] {}, Ui.box(getContext(), Ui.WHITE, Ui.INK));
            setBackgroundDrawable(background);
            setContentDescription(getString(R.string.search_books));
            setClickable(true);
            setFocusable(true);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            float density = getResources().getDisplayMetrics().density;
            float centerX = getWidth() / 2f - 2 * density;
            float centerY = getHeight() / 2f - 2 * density;
            paint.setColor(isPressed() ? Ui.WHITE : Ui.INK);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(2 * density);
            canvas.drawCircle(centerX, centerY, 7 * density, paint);
            canvas.drawLine(centerX + 5 * density, centerY + 5 * density,
                    centerX + 11 * density, centerY + 11 * density, paint);
        }

        @Override
        protected void drawableStateChanged() {
            super.drawableStateChanged();
            invalidate();
        }
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
            cover.setBackground(Ui.box(MainActivity.this, Ui.WHITE, Ui.INK));
            TextView mark = text(book.format, Ui.TEXT_SMALL, MUTED, true);
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

                String state;
                if (book.finished) {
                state = getString(R.string.book_finished);
                } else if (book.opened && book.pageCount > 1) {
                    int percent = Math.max(0, Math.min(100,
                            book.lastPage * 100 / Math.max(1, book.pageCount - 1)));
                state = getString(R.string.book_progress_percent, percent);
                } else {
                state = getString(R.string.book_new);
                }
            TextView detail = text(state, Ui.TEXT_SMALL, MUTED, false);
            detail.setGravity(Gravity.CENTER);
            card.addView(detail);
            card.setTag(book);
            return card;
        }
    }
}
