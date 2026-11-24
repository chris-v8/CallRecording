package io.github.vvb2060.callrecording.settings;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import io.github.vvb2060.callrecording.R;

/**
 * Multi-select list of the phone's numbers for Google Phone's "Selected
 * numbers" (always record) list.
 *
 * Started by Google Phone's Add button (redirected by SelectedNumbersHook) it
 * returns the picks like the system picker does, plus the full list in
 * EXTRA_URIS. Started from this app it queues the picks for the next time
 * Google Phone's Selected numbers screen is opened.
 */
public final class NumberPickerActivity extends Activity {
    public static final String EXTRA_URIS = "io.github.vvb2060.callrecording.extra.URIS";
    private static final String DIALER = "com.google.android.dialer";
    private static final int REQUEST_CONTACTS = 1;

    /** Must match SelectedNumbersHook.EXTRA_EXISTING. */
    public static final String EXTRA_EXISTING = "io.github.vvb2060.callrecording.extra.EXISTING_NUMBERS";
    /** Compare the last 9 digits, so "600 123 456" matches "+48600123456". */
    private static final int MATCH_DIGITS = 9;

    private static final class Entry {
        final long id;
        final String text;
        final String name;
        final String digits;
        /** Already on Google Phone's Selected numbers list: shown checked, can't be changed. */
        final boolean already;

        Entry(long id, String text, String name, String digits, boolean already) {
            this.id = id;
            this.text = text;
            this.name = name;
            this.digits = digits;
            this.already = already;
        }

        @Override
        public String toString() {
            return text;
        }
    }

    private final List<Entry> all = new ArrayList<>();
    private final List<Entry> shown = new ArrayList<>();
    private final Set<Long> selected = new LinkedHashSet<>();
    private ArrayAdapter<Entry> adapter;
    private ListView list;
    private EditText search;
    private TextView message;
    private Button add;
    private boolean forDialer;
    /** Digits of the numbers already on the list; null if unknown. */
    private Set<String> existing;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String caller = getCallingPackage();
        if (caller != null && !DIALER.equals(caller) && !getPackageName().equals(caller)) {
            // Only Google Phone and this app may receive picked contacts.
            setResult(RESULT_CANCELED);
            finish();
            return;
        }
        forDialer = caller != null;
        setTitle(R.string.picker_title);
        existing = existingNumbers();

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(8), dp(12), dp(12));

        search = new EditText(this);
        search.setHint(R.string.picker_search);
        search.setSingleLine(true);
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}

            @Override
            public void afterTextChanged(Editable s) {
                filter();
            }
        });
        content.addView(search, new LinearLayout.LayoutParams(-1, -2));

        message = new TextView(this);
        message.setPadding(dp(4), dp(16), dp(4), dp(16));
        message.setVisibility(View.GONE);
        content.addView(message, new LinearLayout.LayoutParams(-1, -2));

        list = new ListView(this);
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        adapter = new ArrayAdapter<Entry>(this,
                android.R.layout.simple_list_item_multiple_choice, shown) {
            @Override
            public boolean areAllItemsEnabled() {
                return false;
            }

            @Override
            public boolean isEnabled(int position) {
                return !shown.get(position).already;
            }

            @Override
            public View getView(int position, View convertView, android.view.ViewGroup parent) {
                View row = super.getView(position, convertView, parent);
                row.setAlpha(shown.get(position).already ? 0.5f : 1f);
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Entry entry = shown.get(position);
            if (entry.already) return;
            if (!selected.remove(entry.id)) selected.add(entry.id);
            syncChecks();
            updateButton();
        });
        content.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));

        add = new Button(this);
        add.setOnClickListener(view -> finishWithSelection());
        content.addView(add, new LinearLayout.LayoutParams(-1, -2));
        updateButton();

        setContentView(content);

        if (checkSelfPermission(Manifest.permission.READ_CONTACTS)
                == PackageManager.PERMISSION_GRANTED) {
            load();
        } else {
            requestPermissions(new String[]{Manifest.permission.READ_CONTACTS}, REQUEST_CONTACTS);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
                                           int[] grantResults) {
        if (requestCode != REQUEST_CONTACTS) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            load();
        } else {
            showMessage(R.string.picker_permission);
        }
    }

    private void load() {
        Context context = getApplicationContext();
        String alreadyLabel = getString(R.string.picker_already);
        Set<String> known = existing;
        new Thread(() -> {
            List<Entry> entries = new ArrayList<>();
            String[] projection = {Phone._ID, Phone.DISPLAY_NAME_PRIMARY, Phone.NUMBER,
                    Phone.TYPE, Phone.LABEL, Phone.NORMALIZED_NUMBER};
            try (Cursor cursor = context.getContentResolver().query(Phone.CONTENT_URI,
                    projection, null, null, Phone.DISPLAY_NAME_PRIMARY + " COLLATE LOCALIZED ASC")) {
                while (cursor != null && cursor.moveToNext()) {
                    String number = cursor.getString(2);
                    if (number == null || number.trim().isEmpty()) continue;
                    String name = cursor.getString(1);
                    if (name == null) name = number;
                    CharSequence type = Phone.getTypeLabel(context.getResources(),
                            cursor.getInt(3), cursor.getString(4));
                    boolean already = known != null
                            && (isKnown(known, number) || isKnown(known, cursor.getString(5)));
                    String text = name + "\n" + number + "  ·  " + type
                            + (already ? "\n" + alreadyLabel : "");
                    entries.add(new Entry(cursor.getLong(0), text,
                            name.toLowerCase(Locale.ROOT), digits(number), already));
                }
            } catch (RuntimeException error) {
                runOnUiThread(() -> showMessage(R.string.picker_permission));
                return;
            }
            runOnUiThread(() -> {
                all.clear();
                all.addAll(entries);
                filter();
            });
        }, "NumberPicker-load").start();
    }

    private void filter() {
        String query = search.getText().toString().trim().toLowerCase(Locale.ROOT);
        String queryDigits = digits(query);
        shown.clear();
        for (Entry entry : all) {
            if (query.isEmpty() || entry.name.contains(query)
                    || (!queryDigits.isEmpty() && entry.digits.contains(queryDigits))) {
                shown.add(entry);
            }
        }
        adapter.notifyDataSetChanged();
        syncChecks();
        if (all.isEmpty()) {
            showMessage(R.string.picker_empty);
        } else if (existing == null) {
            showMessage(R.string.picker_existing_unknown);
        } else {
            message.setVisibility(View.GONE);
        }
    }

    private void syncChecks() {
        for (int i = 0; i < shown.size(); i++) {
            Entry entry = shown.get(i);
            list.setItemChecked(i, entry.already || selected.contains(entry.id));
        }
    }

    /**
     * Numbers already on Google Phone's list: passed in by Google Phone's Add
     * button, or the copy saved the last time its Selected numbers screen opened.
     */
    private Set<String> existingNumbers() {
        List<String> numbers = null;
        if (forDialer) {
            numbers = getIntent().getStringArrayListExtra(EXTRA_EXISTING);
        } else {
            Set<String> saved = getSharedPreferences(SettingsProvider.PREFS, MODE_PRIVATE)
                    .getStringSet(SettingsProvider.SNAPSHOT, null);
            if (saved != null) numbers = new ArrayList<>(saved);
        }
        if (numbers == null) return null;
        Set<String> keys = new HashSet<>();
        for (String number : numbers) {
            String key = matchKey(number);
            if (key != null) keys.add(key);
        }
        return keys;
    }

    private static boolean isKnown(Set<String> known, String number) {
        String key = matchKey(number);
        return key != null && known.contains(key);
    }

    /** Last 9 digits (or all, if shorter), so local and +country forms match. */
    private static String matchKey(String number) {
        if (number == null) return null;
        String digits = digits(number);
        if (digits.length() < 6) return null;
        return digits.length() > MATCH_DIGITS
                ? digits.substring(digits.length() - MATCH_DIGITS) : digits;
    }

    private void updateButton() {
        add.setText(getString(R.string.picker_add, selected.size()));
        add.setEnabled(!selected.isEmpty());
    }

    private void finishWithSelection() {
        ArrayList<Uri> uris = new ArrayList<>();
        for (long id : selected) {
            // The same kind of URI the system phone-number picker returns.
            uris.add(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, id));
        }
        if (uris.isEmpty()) return;

        if (forDialer) {
            Intent data = new Intent();
            data.setData(uris.get(0)); // what Google Phone reads without the module hook
            data.putParcelableArrayListExtra(EXTRA_URIS, uris);
            setResult(RESULT_OK, data);
            finish();
            return;
        }

        Set<String> pending = new HashSet<>(getSharedPreferences(SettingsProvider.PREFS,
                MODE_PRIVATE).getStringSet(SettingsProvider.PENDING, new HashSet<>()));
        for (Uri uri : uris) pending.add(uri.toString());
        getSharedPreferences(SettingsProvider.PREFS, MODE_PRIVATE).edit()
                .putStringSet(SettingsProvider.PENDING, pending).apply();
        Toast.makeText(this, getString(R.string.picker_queued, pending.size()),
                Toast.LENGTH_LONG).show();
        // Google Phone's settings activity isn't exported (and the exported
        // DialerSettingsActivityCompat entry has no class in 88.x), so open its
        // main screen; the toast says where to go from there.
        Intent phone = getPackageManager().getLaunchIntentForPackage(DIALER);
        try {
            if (phone == null) throw new ActivityNotFoundException();
            startActivity(phone.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (ActivityNotFoundException | SecurityException error) {
            Toast.makeText(this, R.string.picker_no_dialer, Toast.LENGTH_LONG).show();
        }
        finish();
    }

    private void showMessage(int text) {
        message.setText(text);
        message.setVisibility(View.VISIBLE);
    }

    private static String digits(String value) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c >= '0' && c <= '9') out.append(c);
        }
        return out.toString();
    }

    private int dp(int value) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, value,
                getResources().getDisplayMetrics());
    }
}
