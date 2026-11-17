package pk.paysys.tap2pay.bankapp;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.content.res.XmlResourceParser;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.nfc.NfcAdapter;
import android.nfc.cardemulation.CardEmulation;
import android.nfc.cardemulation.HostApduService;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import pk.paysys.tap2pay.core.Apdu;
import pk.paysys.tap2pay.core.BankQr;
import pk.paysys.tap2pay.core.Hex;
import pk.paysys.tap2pay.core.NfcReport;
import pk.paysys.tap2pay.core.QrReport;
import pk.paysys.tap2pay.core.TapHistory;
import pk.paysys.tap2pay.core.TapLogText;

/**
 * A stand-in for a bank app's Tap2Pay flow, to check that a phone with a bank app opens on a tap and gets the
 * right data. It has two screens in one activity: the home screen (NFC status, recent taps) and the payment details
 * screen with everything a bank app would use to send the payment, taken from the QR. A tap brings the app to the front
 * through {@link BankHceService} and {@link TapLaunchActivity}, with the app closed, in the background or on the lock
 * screen; the screen then shows the tap that is pending in {@link BankStore}.
 *
 * It never sets itself as the preferred service for the Tap2Pay AID, so what the status card says about routing is what
 * a closed bank app gets. Look: blue and Inter font, white cards on a
 * light grey page under a blue header.
 */
public class MainActivity extends Activity implements BankStore.Listener {
    private static final String KEY_VIEWING = "viewing";
    private static final String KEY_TAGS = "tags";
    private static final String KEY_OPEN = "open";
    private static final String AID = Hex.encode(Apdu.AID).replace(" ", "");

    // palette
    private static final int GREEN = 0xFF009951;
    private static final int GREEN_SOFT = 0xFFE5F7EE;
    // brand blue: Tapsys blue, the colour of the Tap2Pay Test Kit's wordmark and launcher icon; green stays only for
    // OK / accepted status
    private static final int ACCENT = 0xFF0080BC;
    private static final int ACCENT_SOFT = 0xFFE5F2F8;
    private static final int PAGE = 0xFFF3F4F4;
    private static final int CARD_LINE = 0xFFE0E0E0;
    private static final int INNER = 0xFFF4F4F5;
    private static final int INK = 0xFF000000;
    private static final int LABEL = 0xFF71717A;
    private static final int RED = 0xFFFF4D72;
    private static final int RED_SOFT = 0xFFFFE9EE;
    private static final int ORANGE = 0xFFEA9731;
    private static final int ORANGE_SOFT = 0xFFFFEDD5;
    private static final int WHITE = 0xFFFFFFFF;

    private BankStore store;
    private Typeface regular = Typeface.DEFAULT;
    private Typeface bold = Typeface.DEFAULT_BOLD;
    private Typeface extraBold = Typeface.DEFAULT_BOLD;
    private final Typeface mono = Typeface.MONOSPACE;

    private LinearLayout headerBar;
    private TextView backButton;
    private TextView headerTitle;
    private TextView headerSub;
    private ScrollView scroll;
    private LinearLayout content;
    private int headerTop;

    /** Id of the tap whose details are on screen, or 0 for the home screen. */
    private long viewing;
    private boolean showTags;
    /** Which of the three sections of the details screen are open: NFC, QR, data. */
    private final boolean[] open = new boolean[3];

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = BankStore.get(this);
        loadFonts();
        if (savedInstanceState != null) {
            viewing = savedInstanceState.getLong(KEY_VIEWING, 0);
            showTags = savedInstanceState.getBoolean(KEY_TAGS, false);
            boolean[] saved = savedInstanceState.getBooleanArray(KEY_OPEN);
            if (saved != null && saved.length == open.length) System.arraycopy(saved, 0, open, 0, open.length);
        }
        buildFrame();
    }

    @Override
    protected void onResume() {
        super.onResume();
        store.setListener(this);
        TapHistory.Entry pending = store.pending();
        if (pending != null) {
            show(pending);
            store.markShown(pending.id);
        }
        render();
    }

    @Override
    protected void onPause() {
        store.setListener(null);
        super.onPause();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        // onResume follows and shows the pending tap
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putLong(KEY_VIEWING, viewing);
        out.putBoolean(KEY_TAGS, showTags);
        out.putBooleanArray(KEY_OPEN, open);
    }

    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (viewing != 0) {
            viewing = 0;
            showTags = false;
            render();
            return;
        }
        super.onBackPressed();
    }

    @Override
    public void onChanged() {
        if (!isFinishing() && !isDestroyed()) render();
    }

    // ---------------------------------------------------------------- frame

    private void loadFonts() {
        try {
            regular = Typeface.createFromAsset(getAssets(), "fonts/Inter-Regular.ttf");
            bold = Typeface.createFromAsset(getAssets(), "fonts/Inter-Bold.ttf");
            extraBold = Typeface.createFromAsset(getAssets(), "fonts/Inter-ExtraBold.ttf");
        } catch (RuntimeException e) {
            Log.w(BankStore.TAG, "Inter font not loaded, using the system font", e);
        }
    }

    private void buildFrame() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(PAGE);

        headerBar = new LinearLayout(this);
        headerBar.setOrientation(LinearLayout.HORIZONTAL);
        headerBar.setGravity(Gravity.CENTER_VERTICAL);
        headerBar.setBackgroundColor(ACCENT);
        headerTop = dp(14);
        headerBar.setPadding(dp(8), headerTop, dp(16), dp(14));

        backButton = text("←", 24, WHITE, bold);
        backButton.setPadding(dp(10), 0, dp(14), 0);
        backButton.setContentDescription("Back");
        backButton.setOnClickListener(v -> onBackPressed());
        headerBar.addView(backButton);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        headerTitle = text("", 20, WHITE, extraBold);
        headerSub = text("", 12, 0xCCFFFFFF, regular);
        titles.addView(headerTitle);
        titles.addView(headerSub);
        headerBar.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        root.addView(headerBar, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(16), dp(16), dp(16), dp(24));
        scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
        fitSystemBars(root);
    }

    /** The window is laid out behind the bars from Android 11 (and always on Android 15): pad for them. */
    private void fitSystemBars(View root) {
        getWindow().setStatusBarColor(ACCENT);
        getWindow().setNavigationBarColor(PAGE);
        if (Build.VERSION.SDK_INT < 30) return;
        getWindow().setDecorFitsSystemWindows(false);
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets i = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(i.left, 0, i.right, i.bottom);
            headerBar.setPadding(headerBar.getPaddingLeft(), headerTop + i.top, headerBar.getPaddingRight(),
                    headerBar.getPaddingBottom());
            return insets;
        });
        WindowInsetsController c = getWindow().getInsetsController();
        if (c != null) {
            c.setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS);
            c.setSystemBarsAppearance(WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS,
                    WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS);
        }
        root.requestApplyInsets();
    }

    // ---------------------------------------------------------------- screens

    private void render() {
        render(false);
    }

    /** {@code keepScroll}: a section was opened or closed on the screen already shown. */
    private void render(boolean keepScroll) {
        int y = scroll.getScrollY();
        TapHistory.Entry entry = viewing == 0 ? null : store.get(viewing);
        if (viewing != 0 && entry == null) viewing = 0; // cleared, or dropped from the list
        content.removeAllViews();
        if (entry == null) {
            renderHome();
        } else {
            renderDetails(entry);
        }
        if (keepScroll && entry != null) scroll.post(() -> scroll.scrollTo(0, y));
        else scroll.scrollTo(0, 0);
    }

    /**
     * Opens the details of a tap with all three sections minimized: the status chips and the section headers
     * (each with its one-line result) are what is seen first, and a tap on a chip or a header opens that section.
     */
    private void show(TapHistory.Entry e) {
        viewing = e.id;
        showTags = false;
        for (int i = 0; i < open.length; i++) open[i] = false;
    }

    private void renderHome() {
        backButton.setVisibility(View.GONE);
        headerBar.setPadding(dp(16), headerBar.getPaddingTop(), headerBar.getPaddingRight(), headerBar.getPaddingBottom());
        headerTitle.setText("NFC QR Test App");
        headerSub.setVisibility(View.GONE);

        LinearLayout status = card();
        status.addView(text("Ready for a tap", 18, INK, bold));
        status.addView(space(4));
        status.addView(text("Hold the phone on the POS reader. The app opens by itself when the POS sends a QR, "
                + "even when it is closed or the phone is locked (you are asked to unlock first).", 13, LABEL, regular));
        status.addView(space(12));
        addNfcStatus(status);
        content.addView(status);

        List<TapHistory.Entry> entries = store.entries();
        content.addView(sectionTitle("Recent taps"));
        if (entries.isEmpty()) {
            LinearLayout empty = card();
            empty.addView(text("No taps yet.", 14, LABEL, regular));
            content.addView(empty);
            return;
        }
        LinearLayout list = card();
        list.setPadding(dp(4), dp(4), dp(4), dp(4));
        for (int i = 0; i < entries.size(); i++) {
            if (i > 0) list.addView(divider());
            list.addView(tapRow(entries.get(i)));
        }
        content.addView(list);

        LinearLayout links = new LinearLayout(this);
        links.setOrientation(LinearLayout.HORIZONTAL);
        TextView copyAll = text("Copy all logs", 14, ACCENT, bold);
        copyAll.setGravity(Gravity.CENTER);
        copyAll.setPadding(dp(8), dp(14), dp(8), dp(14));
        copyAll.setOnClickListener(v -> copy("Tap logs", TapLogText.all(store.entries(), TimeZone.getDefault()),
                "Logs copied"));
        TextView clear = text("Clear history", 14, ACCENT, bold);
        clear.setGravity(Gravity.CENTER);
        clear.setPadding(dp(8), dp(14), dp(8), dp(14));
        clear.setOnClickListener(v -> store.clear());
        links.addView(copyAll, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        links.addView(clear, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        content.addView(links);
    }

    private View tapRow(TapHistory.Entry e) {
        BankQr qr = e.qr();
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(12), dp(12), dp(12), dp(12));

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        String title = e.kind == TapHistory.Kind.INCOMPLETE ? "No QR received"
                : !e.accepted() ? "Rejected QR"
                : qr.merchantName != null ? qr.merchantName : "Unnamed merchant";
        left.addView(text(title, 15, INK, bold));
        StringBuilder sub = new StringBuilder(time(e.atMs));
        if (e.accepted()) {
            sub.append("  \u00b7  ").append(qr.isDynamic() ? "Dynamic" : "Static");
            BankQr.Row amount = amountOf(qr);
            if (amount != null) sub.append("  \u00b7  ").append(amount.value);
        } else {
            sub.append("  \u00b7  ").append(e.reason);
        }
        left.addView(text(sub.toString(), 12, LABEL, regular));
        row.addView(left, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        String label = e.kind == TapHistory.Kind.ACCEPTED ? "RECEIVED"
                : e.kind == TapHistory.Kind.REJECTED ? "REJECTED" : "INCOMPLETE";
        int ink = e.kind == TapHistory.Kind.ACCEPTED ? GREEN : e.kind == TapHistory.Kind.REJECTED ? RED : ORANGE;
        int fill = e.kind == TapHistory.Kind.ACCEPTED ? GREEN_SOFT : e.kind == TapHistory.Kind.REJECTED ? RED_SOFT : ORANGE_SOFT;
        row.addView(chip(label, ink, fill));
        row.setOnClickListener(v -> {
            show(e);
            render();
        });
        return row;
    }

    private void renderDetails(TapHistory.Entry e) {
        BankQr qr = e.qr();
        NfcReport nfc = NfcReport.of(e.session);
        QrReport qrReport = e.payload.isEmpty() ? null : QrReport.of(e.payload);
        BankQr.Status dataStatus = e.accepted() ? dataStatus(qr) : null;

        backButton.setVisibility(View.VISIBLE);
        headerBar.setPadding(dp(8), headerBar.getPaddingTop(), headerBar.getPaddingRight(), headerBar.getPaddingBottom());
        headerTitle.setText(e.kind == TapHistory.Kind.ACCEPTED ? "Payment details"
                : e.kind == TapHistory.Kind.REJECTED ? "Rejected QR" : "Incomplete tap");
        headerSub.setVisibility(View.VISIBLE);
        headerSub.setText("Received " + time(e.atMs));

        if (!e.accepted()) {
            LinearLayout why = card();
            why.setBackground(rounded(RED_SOFT, RED, 1, 8));
            why.addView(text(e.kind == TapHistory.Kind.REJECTED ? "Not accepted" : "No QR reached the phone", 16, RED, bold));
            why.addView(space(4));
            why.addView(text(e.kind == TapHistory.Kind.REJECTED ? e.reason
                    : "The tap ended before a complete QR arrived. Section 1 shows what the POS sent and where it stopped.",
                    14, INK, regular));
            if (e.kind == TapHistory.Kind.REJECTED) {
                why.addView(space(6));
                why.addView(text("The POS got 6A80 for this payload, as the phone answers.", 12, LABEL, regular));
            }
            content.addView(why);
        }

        content.addView(summaryStrip(nfc == null ? null : nfc.status, qrReport == null ? null : qrReport.status, dataStatus));

        TextView copyLog = outlineButton("Copy log");
        copyLog.setOnClickListener(v -> copy("Tap log", TapLogText.of(e, TimeZone.getDefault()), "Log copied"));
        LinearLayout.LayoutParams copyLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        copyLp.bottomMargin = dp(12);
        content.addView(copyLog, copyLp);

        // 1. NFC communication
        if (addSection(0, "NFC communication", nfc == null ? null : nfc.status,
                nfc == null ? "no log was saved for this tap" : nfc.summary)) {
            if (nfc == null) {
                LinearLayout none = card();
                none.addView(text("This tap was saved without its APDU log.", 14, LABEL, regular));
                content.addView(none);
            } else {
                for (NfcReport.Step step : nfc.steps) content.addView(stepCard(step));
                content.addView(sessionCard(nfc));
            }
        }

        // 2. QR validation
        if (addSection(1, "QR validation", qrReport == null ? null : qrReport.status,
                qrReport == null ? "no QR was received" : qrReport.summary)) {
            if (qrReport == null) {
                LinearLayout none = card();
                none.addView(text("No QR was received, so there is nothing to validate.", 14, LABEL, regular));
                content.addView(none);
            } else {
                for (QrReport.Group g : qrReport.groups) {
                    if (g.rows.isEmpty() && g.absentOptional == 0) continue;
                    content.addView(groupCard(g));
                }
                content.addView(qrRawCard(e, qr));
            }
        }

        // 3. Data extracted
        if (addSection(2, "Data extracted", dataStatus,
                !e.accepted() ? (e.kind == TapHistory.Kind.REJECTED ? "not used: the QR was refused" : "nothing to extract")
                        : dataVerdict(qr))) {
            if (e.kind == TapHistory.Kind.INCOMPLETE) {
                LinearLayout none = card();
                none.addView(text("Nothing to extract.", 14, LABEL, regular));
                content.addView(none);
            } else {
                if (e.kind == TapHistory.Kind.REJECTED) {
                    LinearLayout note = card();
                    note.addView(text("The phone stops at the refusal. These fields are read from the refused QR "
                            + "anyway, as far as it parses.", 13, LABEL, regular));
                    content.addView(note);
                }
                content.addView(merchantCard(qr));
                content.addView(amountCard(qr));
                content.addView(readinessCard(qr));
                for (BankQr.Section sec : qr.sections()) content.addView(sectionCard(sec));
            }
        }

        if (e.accepted()) {
            TextView done = button("Done");
            done.setOnClickListener(v -> onBackPressed());
            content.addView(done);
        }
    }

    private static BankQr.Status dataStatus(BankQr qr) {
        BankQr.Status w = BankQr.Status.OK;
        for (BankQr.Check c : qr.checks()) {
            if (c.status == BankQr.Status.FAIL) return BankQr.Status.FAIL;
            if (c.status == BankQr.Status.WARN) w = BankQr.Status.WARN;
        }
        return w;
    }

    private static String dataVerdict(BankQr qr) {
        int problems = 0;
        int warnings = 0;
        for (BankQr.Check c : qr.checks()) {
            if (c.status == BankQr.Status.FAIL) problems++;
            else if (c.status == BankQr.Status.WARN) warnings++;
        }
        if (problems > 0) return "a bank app cannot send it: " + problems + (problems == 1 ? " problem" : " problems");
        if (warnings > 0) return "can be sent, " + warnings + (warnings == 1 ? " thing" : " things") + " for the payer to fill in";
        return "everything needed is in the QR";
    }

    private static int inkOf(BankQr.Status s) {
        return s == null ? LABEL : s == BankQr.Status.OK ? GREEN : s == BankQr.Status.WARN ? ORANGE : RED;
    }

    private static int fillOf(BankQr.Status s) {
        return s == null ? INNER : s == BankQr.Status.OK ? GREEN_SOFT : s == BankQr.Status.WARN ? ORANGE_SOFT : RED_SOFT;
    }

    private static String markOf(BankQr.Status s) {
        return s == null ? "\u2013" : s == BankQr.Status.OK ? "\u2713" : s == BankQr.Status.WARN ? "!" : "\u2715";
    }

    /** Three chips, one per section, with its verdict; a tap opens or closes that section. */
    private View summaryStrip(BankQr.Status nfc, BankQr.Status qr, BankQr.Status data) {
        LinearLayout strip = new LinearLayout(this);
        strip.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        strip.setLayoutParams(lp);
        BankQr.Status[] st = {nfc, qr, data};
        String[] names = {"1  NFC", "2  QR", "3  DATA"};
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView t = text(names[i] + "  " + markOf(st[i]), 13, inkOf(st[i]), bold);
            t.setGravity(Gravity.CENTER);
            t.setBackground(rounded(fillOf(st[i]), fillOf(st[i]), 0, 100));
            t.setPadding(dp(8), dp(10), dp(8), dp(10));
            t.setOnClickListener(v -> {
                open[idx] = !open[idx];
                render(true);
            });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
            if (i < 2) cp.rightMargin = dp(8);
            strip.addView(t, cp);
        }
        return strip;
    }

    /** The header of one numbered section; returns whether its body is open. */
    private boolean addSection(int index, String title, BankQr.Status status, String summary) {
        LinearLayout head = card();
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.setPadding(dp(14), dp(12), dp(14), dp(12));
        TextView num = text(String.valueOf(index + 1), 14, WHITE, extraBold);
        num.setGravity(Gravity.CENTER);
        num.setBackground(rounded(inkOf(status), inkOf(status), 0, 100));
        head.addView(num, new LinearLayout.LayoutParams(dp(28), dp(28)));
        LinearLayout words = new LinearLayout(this);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(12), 0, dp(8), 0);
        words.addView(text(title, 16, INK, bold));
        words.addView(text(summary, 12, status == null ? LABEL : inkOf(status), regular));
        head.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        head.addView(text(open[index] ? "\u25B4" : "\u25BE", 16, LABEL, bold));
        head.setOnClickListener(v -> {
            open[index] = !open[index];
            render(true);
        });
        content.addView(head);
        return open[index];
    }

    /** One APDU: each header field expected versus seen, then the bytes. */
    private View stepCard(NfcReport.Step step) {
        LinearLayout card = card();
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.addView(text(step.number + " \u00b7 " + step.title, 15, INK, bold),
                new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        head.addView(text("+" + step.atMs + " ms", 12, LABEL, mono));
        card.addView(head);
        for (NfcReport.Field f : step.fields) {
            card.addView(divider());
            card.addView(compareRow(f.name, f.expected, f.got, f.status, f.note));
        }
        card.addView(space(8));
        card.addView(hexBlock("Sent by the POS", step.command));
        if (step.data != null && !step.data.isEmpty()) {
            card.addView(space(6));
            card.addView(hexBlock("Data as text", step.data));
        }
        card.addView(space(6));
        card.addView(hexBlock("Phone answered", step.response));
        return card;
    }

    private View sessionCard(NfcReport nfc) {
        LinearLayout card = card();
        card.addView(text("Whole tap", 15, INK, bold));
        for (NfcReport.Field f : nfc.session) {
            card.addView(divider());
            card.addView(compareRow(f.name, f.expected, f.got, f.status, f.note));
        }
        return card;
    }

    private View groupCard(QrReport.Group g) {
        LinearLayout card = card();
        card.addView(text(g.title, 15, INK, bold));
        for (QrReport.Row r : g.rows) {
            card.addView(divider());
            String name = "-".equals(r.tag) ? r.element : r.tag + "  " + r.element;
            card.addView(compareRow(name, r.expected, r.got, r.status, r.note));
        }
        if (g.absentOptional > 0) {
            card.addView(divider());
            TextView more = text("+ " + g.absentOptional + " optional " + (g.absentOptional == 1 ? "tag" : "tags")
                    + " not in this QR", 12, LABEL, regular);
            more.setPadding(0, dp(10), 0, 0);
            card.addView(more);
        }
        return card;
    }

    /** One line of a check: the mark and name, what was expected, what came, and why it is not fine. */
    private View compareRow(String name, String expected, String got, BankQr.Status status, String note) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        TextView mark = text(markOf(status), 14, inkOf(status), extraBold);
        head.addView(mark, new LinearLayout.LayoutParams(dp(22), ViewGroup.LayoutParams.WRAP_CONTENT));
        head.addView(text(name, 14, INK, bold), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(head);
        if (expected != null && !"-".equals(expected)) row.addView(valueLine("Expected", expected, INK, regular));
        if (got != null && !"-".equals(got)) {
            row.addView(valueLine("Got", got, status == BankQr.Status.OK ? INK : inkOf(status), bold));
        }
        if (note != null) {
            TextView n = text(note, 12, inkOf(status), regular);
            n.setPadding(dp(22), dp(2), 0, 0);
            row.addView(n);
        }
        return row;
    }

    private View valueLine(String label, String value, int ink, Typeface face) {
        LinearLayout line = new LinearLayout(this);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setPadding(dp(22), dp(2), 0, 0);
        line.addView(text(label, 12, LABEL, regular), new LinearLayout.LayoutParams(dp(62), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView v = text(value, 13, ink, face);
        v.setTextIsSelectable(true);
        line.addView(v, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        return line;
    }

    private View hexBlock(String label, String hex) {
        LinearLayout block = new LinearLayout(this);
        block.setOrientation(LinearLayout.VERTICAL);
        block.addView(text(label, 11, LABEL, bold));
        TextView v = text(hex.isEmpty() ? "(nothing)" : hex, 11, INK, mono);
        v.setTextIsSelectable(true);
        v.setBackground(rounded(INNER, INNER, 0, 6));
        v.setPadding(dp(8), dp(8), dp(8), dp(8));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(3);
        block.addView(v, lp);
        return block;
    }

    private View merchantCard(BankQr qr) {
        LinearLayout card = card();
        LinearLayout inner = new LinearLayout(this);
        inner.setOrientation(LinearLayout.VERTICAL);
        inner.setBackground(rounded(INNER, INNER, 0, 6));
        inner.setPadding(dp(16), dp(14), dp(16), dp(14));
        inner.addView(text("Merchant Name", 13, LABEL, regular));
        inner.addView(space(2));
        inner.addView(text(qr.merchantName != null ? qr.merchantName : "(no tag 59)", 18, INK, bold));
        if (qr.city != null) {
            inner.addView(space(8));
            TextView city = chip("●  " + qr.city, WHITE, ACCENT);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            inner.addView(city, lp);
        }
        card.addView(inner);
        return card;
    }

    private View amountCard(BankQr qr) {
        LinearLayout card = card();
        card.addView(text("Amount to pay", 13, LABEL, regular));
        card.addView(space(4));
        java.math.BigDecimal total = qr.totalAmount();
        if (total != null) {
            card.addView(text(qr.currency() + " " + BankQr.money(total), 30, INK, extraBold));
        } else if (qr.amountState() == pk.paysys.tap2pay.core.RaastQr.AmountState.NONE) {
            card.addView(text("Payer enters the amount", 18, INK, bold));
        } else {
            card.addView(text("No usable amount", 18, RED, bold));
        }
        String note = qr.totalNote();
        if (note != null) card.addView(text(note, 12, LABEL, regular));
        card.addView(space(10));
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        String kind = qr.isDynamic() ? "DYNAMIC QR" : "static".equals(qr.type) ? "STATIC QR"
                : "QR TYPE " + qr.type.toUpperCase(Locale.US);
        chips.addView(chip(kind, ACCENT, ACCENT_SOFT));
        card.addView(chips);
        return card;
    }

    private View readinessCard(BankQr qr) {
        LinearLayout card = card();
        int problems = 0;
        int warnings = 0;
        for (BankQr.Check c : qr.checks()) {
            if (c.status == BankQr.Status.FAIL) problems++;
            else if (c.status == BankQr.Status.WARN) warnings++;
        }
        card.addView(text("Can a bank app send this payment?", 15, INK, bold));
        card.addView(space(2));
        String verdict = problems > 0 ? problems + (problems == 1 ? " problem" : " problems") + " in the QR"
                : warnings > 0 ? "Yes, with " + warnings + (warnings == 1 ? " thing" : " things") + " for the payer to fill in"
                : "Yes, everything needed is in the QR";
        card.addView(text(verdict, 13, problems > 0 ? RED : warnings > 0 ? ORANGE : GREEN, bold));
        card.addView(space(8));
        for (BankQr.Check c : qr.checks()) {
            card.addView(divider());
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setPadding(0, dp(10), 0, dp(10));
            int ink = c.status == BankQr.Status.OK ? GREEN : c.status == BankQr.Status.WARN ? ORANGE : RED;
            String mark = c.status == BankQr.Status.OK ? "✓" : c.status == BankQr.Status.WARN ? "!" : "✕";
            TextView m = text(mark, 15, ink, extraBold);
            m.setGravity(Gravity.CENTER);
            row.addView(m, new LinearLayout.LayoutParams(dp(24), ViewGroup.LayoutParams.WRAP_CONTENT));
            LinearLayout words = new LinearLayout(this);
            words.setOrientation(LinearLayout.VERTICAL);
            words.addView(text(c.label, 14, INK, bold));
            if (c.detail != null) words.addView(text(c.detail, 12, LABEL, regular));
            row.addView(words, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            card.addView(row);
        }
        return card;
    }

    private View sectionCard(BankQr.Section s) {
        LinearLayout card = card();
        card.addView(text(s.title, 15, INK, bold));
        card.addView(space(4));
        boolean any = false;
        for (BankQr.Row r : s.rows) {
            // optional rows the QR does not carry add nothing; a required one missing is the point of the screen
            if (!r.present() && !r.required) continue;
            card.addView(divider());
            card.addView(detailRow(r));
            any = true;
        }
        if (!any) card.addView(text("Nothing in the QR", 13, LABEL, regular));
        return card;
    }

    private View detailRow(BankQr.Row r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.VERTICAL);
        row.setPadding(0, dp(10), 0, dp(10));
        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.addView(text(r.label, 12, LABEL, regular), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        if (r.ref != null && !r.ref.isEmpty()) head.addView(text("tag " + r.ref, 11, LABEL, mono));
        row.addView(head);
        if (r.present()) {
            TextView v = text(r.value, 15, INK, bold);
            v.setTextIsSelectable(true);
            row.addView(v);
        } else {
            row.addView(text("missing from the QR", 14, RED, bold));
        }
        return row;
    }

    private View qrRawCard(TapHistory.Entry e, BankQr qr) {
        LinearLayout card = card();
        card.addView(text("QR text", 15, INK, bold));
        card.addView(space(6));
        TextView raw = text(e.payload.isEmpty() ? "(empty)" : e.payload, 12, INK, mono);
        raw.setTextIsSelectable(true);
        raw.setBackground(rounded(INNER, INNER, 0, 6));
        raw.setPadding(dp(10), dp(10), dp(10), dp(10));
        card.addView(raw);
        card.addView(space(10));

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        TextView copy = outlineButton("Copy QR");
        copy.setOnClickListener(v -> copy("Raast QR", e.payload, "QR copied"));
        TextView tags = outlineButton(showTags ? "Hide tags" : "Show all tags");
        tags.setOnClickListener(v -> {
            showTags = !showTags;
            render(true);
        });
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.rightMargin = dp(8);
        buttons.addView(copy, lp);
        buttons.addView(tags, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        card.addView(buttons);

        if (showTags) {
            card.addView(space(10));
            for (BankQr.Node n : qr.tree()) {
                StringBuilder line = new StringBuilder();
                for (int i = 0; i < n.depth; i++) line.append("    ");
                line.append(n.tag).append(' ').append(String.format(Locale.US, "%02d", n.length));
                if (!n.template) line.append("  ").append(n.value);
                TextView t = text(line.toString(), 12, n.tag.equals("--") ? RED : INK, mono);
                t.setPadding(0, dp(2), 0, dp(2));
                card.addView(t);
            }
        }
        return card;
    }

    private void addNfcStatus(LinearLayout parent) {
        NfcAdapter adapter = NfcAdapter.getDefaultAdapter(this);
        String line;
        int ink;
        if (adapter == null) {
            line = "No NFC hardware";
            ink = RED;
        } else if (!adapter.isEnabled()) {
            line = "NFC is off: turn it on in Settings";
            ink = RED;
        } else if (routedToThisApp(adapter)) {
            line = "NFC on, Tap2Pay taps reach this app";
            ink = GREEN;
        } else {
            line = "NFC on, but Tap2Pay taps are not routed to this app";
            ink = ORANGE;
        }
        parent.addView(statusLine(line, ink));

        List<String> others = otherAppsWithTheAid();
        if (!others.isEmpty()) {
            StringBuilder names = new StringBuilder();
            for (String n : others) names.append(names.length() > 0 ? ", " : "").append(n);
            parent.addView(space(6));
            TextView warn = text("Same AID also registered by " + names + ". A tap may open that app instead of "
                    + "this one; uninstall or disable it for this test.", 12, ORANGE, regular);
            warn.setBackground(rounded(ORANGE_SOFT, ORANGE_SOFT, 0, 6));
            warn.setPadding(dp(10), dp(8), dp(10), dp(8));
            parent.addView(warn);
        }
    }

    private boolean routedToThisApp(NfcAdapter adapter) {
        try {
            return CardEmulation.getInstance(adapter)
                    .isDefaultServiceForAid(new ComponentName(this, BankHceService.class), AID);
        } catch (RuntimeException e) {
            Log.w(BankStore.TAG, "could not ask about AID routing", e);
            return false;
        }
    }

    /** Labels of the other installed apps whose HCE service lists the Tap2Pay AID, read from their service XML. */
    private List<String> otherAppsWithTheAid() {
        List<String> out = new ArrayList<>();
        PackageManager pm = getPackageManager();
        List<ResolveInfo> services;
        try {
            services = pm.queryIntentServices(new Intent(HostApduService.SERVICE_INTERFACE), PackageManager.GET_META_DATA);
        } catch (RuntimeException e) {
            return out;
        }
        for (ResolveInfo ri : services) {
            ServiceInfo si = ri.serviceInfo;
            if (si == null || getPackageName().equals(si.packageName)) continue;
            if (listsAid(si, pm)) out.add(String.valueOf(si.applicationInfo.loadLabel(pm)));
        }
        return out;
    }

    private static boolean listsAid(ServiceInfo si, PackageManager pm) {
        try (XmlResourceParser p = si.loadXmlMetaData(pm, HostApduService.SERVICE_META_DATA)) {
            if (p == null) return false;
            for (int ev = p.getEventType(); ev != XmlPullParser.END_DOCUMENT; ev = p.next()) {
                if (ev == XmlPullParser.START_TAG && "aid-filter".equals(p.getName())) {
                    String name = p.getAttributeValue("http://schemas.android.com/apk/res/android", "name");
                    if (AID.equalsIgnoreCase(name)) return true;
                }
            }
        } catch (XmlPullParserException | IOException | RuntimeException e) {
            Log.w(BankStore.TAG, "could not read the AID list of " + si.packageName, e);
        }
        return false;
    }

    // ---------------------------------------------------------------- helpers

    /** The "Total to debit" value, or the QR's own amount, or null when it has none. */
    private static BankQr.Row amountOf(BankQr qr) {
        for (BankQr.Section s : qr.sections()) {
            for (BankQr.Row r : s.rows) if (r.label.equals("Total to debit") && r.present()) return r;
        }
        return null;
    }

    private void copy(String label, String text, String done) {
        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        if (cm == null) {
            Toast.makeText(this, "Copy is not available on this phone", Toast.LENGTH_SHORT).show();
            return;
        }
        cm.setPrimaryClip(ClipData.newPlainText(label, text));
        Toast.makeText(this, done, Toast.LENGTH_SHORT).show();
    }

    private static String time(long ms) {
        return new SimpleDateFormat("dd MMM, hh:mm a", Locale.US).format(new Date(ms));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, int sp, int color, Typeface face) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(face);
        t.setIncludeFontPadding(false);
        t.setLineSpacing(dp(2), 1f);
        return t;
    }

    private GradientDrawable rounded(int fill, int stroke, int strokeDp, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) d.setStroke(dp(strokeDp), stroke);
        return d;
    }

    private LinearLayout card() {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        c.setBackground(rounded(WHITE, CARD_LINE, 1, 8));
        c.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = dp(12);
        c.setLayoutParams(lp);
        return c;
    }

    private TextView sectionTitle(String s) {
        TextView t = text(s, 13, LABEL, bold);
        t.setPadding(dp(4), dp(8), 0, dp(8));
        return t;
    }

    private View statusLine(String s, int ink) {
        TextView t = text("●  " + s, 14, ink, bold);
        return t;
    }

    private View space(int heightDp) {
        View v = new View(this);
        v.setLayoutParams(new LinearLayout.LayoutParams(1, dp(heightDp)));
        return v;
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(CARD_LINE);
        v.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
        return v;
    }

    private TextView chip(String s, int ink, int fill) {
        TextView t = text(s, 11, ink, bold);
        t.setBackground(rounded(fill, fill, 0, 100));
        t.setPadding(dp(10), dp(4), dp(10), dp(4));
        return t;
    }

    private TextView button(String s) {
        TextView t = text(s, 16, WHITE, bold);
        t.setGravity(Gravity.CENTER);
        t.setBackground(rounded(ACCENT, ACCENT, 0, 8));
        t.setPadding(dp(16), dp(16), dp(16), dp(16));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        t.setLayoutParams(lp);
        return t;
    }

    private TextView outlineButton(String s) {
        TextView t = text(s, 14, ACCENT, bold);
        t.setGravity(Gravity.CENTER);
        t.setBackground(rounded(WHITE, ACCENT, 1, 8));
        t.setPadding(dp(12), dp(12), dp(12), dp(12));
        return t;
    }
}
