package dev.dayssince;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.WindowManager;
import android.widget.EditText;
import android.widget.FrameLayout;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

/**
 * Invisible activity that only shows native dialogs. Three entry points:
 *  - APPWIDGET_CONFIGURE: adding (or reconfiguring) a widget -> label + start date
 *  - ACTION_MENU: tapping a widget -> [Reset to today] [Change date] [Rename]
 *  - MAIN (launcher icon): short how-to
 */
public class CounterActivity extends Activity {

    static final String ACTION_MENU = "dev.dayssince.action.MENU";

    private int widgetId = AppWidgetManager.INVALID_APPWIDGET_ID;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        Intent in = getIntent();
        String action = in.getAction();
        widgetId = in.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID);

        if (AppWidgetManager.ACTION_APPWIDGET_CONFIGURE.equals(action)) {
            // If the user backs out, the launcher must not keep a half-made widget.
            setResult(RESULT_CANCELED, resultIntent());
            if (widgetId == AppWidgetManager.INVALID_APPWIDGET_ID) { finish(); return; }
            showLabelDialog(true);
        } else if (ACTION_MENU.equals(action) && widgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            showMenu();
        } else {
            showHelp();
        }
    }

    // ---- Tap menu ---------------------------------------------------------------

    private void showMenu() {
        String label = Store.label(this, widgetId);
        long days = DaysWidget.daysSince(this, widgetId);
        String since = LocalDate.ofEpochDay(Store.startDay(this, widgetId))
                .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM));
        String title = (label.isEmpty() ? "Counter" : label) + " · " + days + (days == 1 ? " day" : " days");

        CharSequence[] items = {
                "Reset to today",
                "Change date (now " + since + ")",
                "Rename",
        };
        new AlertDialog.Builder(this, dialogTheme())
                .setTitle(title)
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        Store.saveDay(this, widgetId, LocalDate.now().toEpochDay());
                        done(false);
                    } else if (which == 1) {
                        showDatePicker(false, null);
                    } else {
                        showLabelDialog(false);
                    }
                })
                .setNegativeButton(android.R.string.cancel, (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    // ---- Label (used when adding, reconfiguring, or renaming) -------------------

    private void showLabelDialog(boolean configuring) {
        boolean isNew = !Store.has(this, widgetId);

        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        input.setHint("Label, e.g. gym");
        input.setText(Store.label(this, widgetId));
        input.setSelection(input.getText().length());
        FrameLayout box = new FrameLayout(this);
        int pad = dp(20);
        box.setPadding(pad, dp(8), pad, 0);
        box.addView(input);

        AlertDialog.Builder b = new AlertDialog.Builder(this, dialogTheme())
                .setTitle(isNew ? "New counter" : "Rename")
                .setView(box)
                .setNegativeButton(android.R.string.cancel, (d, w) -> finish())
                .setOnCancelListener(d -> finish());

        if (isNew) {
            b.setPositiveButton("Start today", (d, w) -> {
                Store.save(this, widgetId, LocalDate.now().toEpochDay(), text(input));
                done(configuring);
            });
            b.setNeutralButton("Pick date", (d, w) -> showDatePicker(configuring, text(input)));
        } else {
            b.setPositiveButton("Save", (d, w) -> {
                Store.saveLabel(this, widgetId, text(input));
                done(configuring);
            });
        }

        AlertDialog dialog = b.create();
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        dialog.show();
        input.requestFocus();
    }

    // ---- Date picker ------------------------------------------------------------

    /** @param newLabel non-null when creating a new counter (label not saved yet). */
    private void showDatePicker(boolean configuring, String newLabel) {
        LocalDate current = newLabel != null
                ? LocalDate.now()
                : LocalDate.ofEpochDay(Store.startDay(this, widgetId));

        DatePickerDialog dp = new DatePickerDialog(this, dialogTheme(), (view, y, m, d) -> {
            long day = LocalDate.of(y, m + 1, d).toEpochDay();
            if (newLabel != null) Store.save(this, widgetId, day, newLabel);
            else Store.saveDay(this, widgetId, day);
            done(configuring);
        }, current.getYear(), current.getMonthValue() - 1, current.getDayOfMonth());
        dp.getDatePicker().setMaxDate(System.currentTimeMillis());
        dp.setOnCancelListener(d -> finish());
        dp.show();
    }

    // ---- Launcher icon ----------------------------------------------------------

    private void showHelp() {
        new AlertDialog.Builder(this, dialogTheme())
                .setTitle("Days Since")
                .setMessage("This app is a home-screen widget.\n\n"
                        + "Long-press an empty spot on your home screen → Widgets → Days Since, "
                        + "then drag it onto the screen and give it a label.\n\n"
                        + "Tap the widget any time to reset it to today or change the date.")
                .setPositiveButton(android.R.string.ok, (d, w) -> finish())
                .setOnCancelListener(d -> finish())
                .show();
    }

    // ---- Helpers ----------------------------------------------------------------

    private void done(boolean configuring) {
        DaysWidget.render(this, AppWidgetManager.getInstance(this), widgetId);
        DaysWidget.scheduleMidnightRefresh(this);
        if (configuring) setResult(RESULT_OK, resultIntent());
        finish();
    }

    private Intent resultIntent() {
        return new Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, widgetId);
    }

    private static String text(EditText e) {
        return e.getText().toString().trim();
    }

    private int dialogTheme() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES
                ? android.R.style.Theme_DeviceDefault_Dialog_Alert
                : android.R.style.Theme_DeviceDefault_Light_Dialog_Alert;
    }

    private int dp(int v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
