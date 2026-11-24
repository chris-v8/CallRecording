package io.github.vvb2060.callrecording.settings;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentUris;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
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
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.github.vvb2060.callrecording.R;

/**
 * Multi-select list of the phone's numbers for Google Phone's "Selected
 * numbers" (always record) list.
 *
 * Numbers already on that list start ticked; unticking one removes it, ticking
 * a new one adds it. Started by Google Phone's Add button (redirected by
 * SelectedNumbersHook) it returns the changes to Google Phone, which applies
 * them with its own add/remove code. Started from this app it queues them for
 * the next time Google Phone's Selected numbers screen is opened.
 */
public final class NumberPickerActivity extends Activity {
    /** These must match SelectedNumbersHook. */
    public static final String EXTRA_URIS = "io.github.vvb2060.callrecording.extra.URIS";
    public static final String EXTRA_REMOVE = "io.github.vvb2060.callrecording.extra.REMOVE_NUMBERS";
    public static final String EXTRA_EXISTING = "io.github.vvb2060.callrecording.extra.EXISTING_NUMBERS";
    private static final String DIALER = "com.google.android.dialer";
    private static final int REQUEST_CONTACTS = 1;
    /** Compare the last 9 digits, so "600 123 456" matches "+48600123456". */
    private static final int MATCH_DIGITS = 9;

    private static final class Entry {
        final long id;
        final String text;
        final String name;
        final String digits;
        /** Match key (last digits); null for very short numbers. */
        final String key;
        /** Already on Google Phone's Selected numbers list. */
        final boolean already;

        Entry(long id, String text, String name, String digits, String key, boolean already) {
            this.id = id;
            this.text = text;
            this.name = name;
            this.digits = digits;
            this.key = key;
            this.already = already;
        }

        @Override
        public String toString() {
            return text;
        }
    }

    private final List<Entry> all = new ArrayList<>();
    private final List<Entry> shown = new ArrayList<>();
    /** New numbers to add, by contact Data row id. */
    private final Set<Long> selected = new LinkedHashSet<>();
    /** Numbers already on the list that the user unticked, by match key. */
    private final Set<String> removedKeys = new LinkedHashSet<>();
    private ArrayAdapter<Entry> adapter;
    private ListView list;
    private EditText search;
    private TextView message;
    private Button save;
    private boolean forDialer;
    /** Numbers already on the list: match key → number exactly as stored; null if unknown. */
    private Map<String, String> existing;

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

        String onList = getString(R.string.picker_on_list);
        String willRemove = getString(R.string.picker_will_remove);
        list = new ListView(this);
        list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        adapter = new ArrayAdapter<Entry>(this,
                android.R.layout.simple_list_item_multiple_choice, shown) {
            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                TextView row = (TextView) super.getView(position, convertView, parent);
                Entry entry = shown.get(position);
                if (entry.already) {
                    boolean removing = removedKeys.contains(entry.key);
                    row.setText(entry.text + "\n" + (removing ? willRemove : onList));
                } else {
                    row.setText(entry.text);
                }
                return row;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            Entry entry = shown.get(position);
            if (entry.already) {
                // Every contact row with this number follows the same state.
                if (!removedKeys.remove(entry.key)) removedKeys.add(entry.key);
            } else if (!selected.remove(entry.id)) {
                selected.add(entry.id);
            }
            syncChecks();
            adapter.notifyDataSetChanged();
            updateButton();
        });
        content.addView(list, new LinearLayout.LayoutParams(-1, 0, 1f));

        save = new Button(this);
        save.setOnClickListener(view -> finishWithChanges());
        content.addView(save, new LinearLayout.LayoutParams(-1, -2));
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
        Map<String, String> known = existing;
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
                    String key = matchKey(number);
                    String normalizedKey = matchKey(cursor.getString(5));
                    boolean already = false;
                    if (known != null) {
                        if (key != null && known.containsKey(key)) {
                            already = true;
                        } else if (normalizedKey != null && known.containsKey(normalizedKey)) {
                            already = true;
                            key = normalizedKey;
                        }
                    }
                    String text = name + "\n" + number + "  ·  " + type;
                    entries.add(new Entry(cursor.getLong(0), text,
                            name.toLowerCase(Locale.ROOT), digits(number), key, already));
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
            boolean checked = entry.already
                    ? !removedKeys.contains(entry.key)
                    : selected.contains(entry.id);
            list.setItemChecked(i, checked);
        }
    }

    private void updateButton() {
        save.setText(getString(R.string.picker_save, selected.size(), removedKeys.size()));
        save.setEnabled(!selected.isEmpty() || !removedKeys.isEmpty());
    }

    private void finishWithChanges() {
        ArrayList<Uri> uris = new ArrayList<>();
        for (long id : selected) {
            // The same kind of URI the system phone-number picker returns.
            uris.add(ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, id));
        }
        ArrayList<String> remove = new ArrayList<>();
        if (existing != null) {
            for (String key : removedKeys) {
                String stored = existing.get(key);
                if (stored != null) remove.add(stored);
            }
        }
        if (uris.isEmpty() && remove.isEmpty()) return;

        if (forDialer) {
            Intent data = new Intent();
            // What Google Phone reads without the module hook: the first new number.
            if (!uris.isEmpty()) data.setData(uris.get(0));
            data.putParcelableArrayListExtra(EXTRA_URIS, uris);
            data.putStringArrayListExtra(EXTRA_REMOVE, remove);
            setResult(RESULT_OK, data);
            finish();
            return;
        }

        SharedPreferences prefs = getSharedPreferences(SettingsProvider.PREFS, MODE_PRIVATE);
        Set<String> add = new HashSet<>(prefs.getStringSet(SettingsProvider.PENDING, new HashSet<>()));
        Set<String> drop = new HashSet<>(prefs.getStringSet(SettingsProvider.PENDING_REMOVALS,
                new HashSet<>()));
        for (Uri uri : uris) add.add(uri.toString());
        drop.addAll(remove);
        prefs.edit()
                .putStringSet(SettingsProvider.PENDING, add)
                .putStringSet(SettingsProvider.PENDING_REMOVALS, drop)
                .apply();
        Toast.makeText(this, getString(R.string.picker_queued, add.size(), drop.size()),
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

    /**
     * Numbers already on Google Phone's list: passed in by Google Phone's Add
     * button, or the copy saved the last time its Selected numbers screen opened.
     */
    private Map<String, String> existingNumbers() {
        List<String> numbers = null;
        if (forDialer) {
            numbers = getIntent().getStringArrayListExtra(EXTRA_EXISTING);
        } else {
            Set<String> saved = getSharedPreferences(SettingsProvider.PREFS, MODE_PRIVATE)
                    .getStringSet(SettingsProvider.SNAPSHOT, null);
            if (saved != null) numbers = new ArrayList<>(saved);
        }
        if (numbers == null) return null;
        Map<String, String> keys = new HashMap<>();
        for (String number : numbers) {
            String key = matchKey(number);
            if (key != null) keys.put(key, number);
        }
        return keys;
    }

    /** Last 9 digits (or all, if shorter), so local and +country forms match. */
    private static String matchKey(String number) {
        if (number == null) return null;
        String digits = digits(number);
        if (digits.length() < 6) return null;
        return digits.length() > MATCH_DIGITS
                ? digits.substring(digits.length() - MATCH_DIGITS) : digits;
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
