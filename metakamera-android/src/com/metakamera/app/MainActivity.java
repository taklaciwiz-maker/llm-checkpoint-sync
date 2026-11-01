package com.metakamera.app;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.os.ext.SdkExtensions;
import android.provider.MediaStore;
import android.text.Editable;
import android.text.InputFilter;
import android.text.Spanned;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The single screen: select media, choose the target phone, set options, edit, copy to gallery/share. */
public class MainActivity extends Activity implements AppState.Listener, ItemRow.Callbacks {

    private static final int REQ_PICK_GALLERY = 1;
    private static final int REQ_PICK_FILES = 2;
    private static final int REQ_PERM_LOCATION = 10;
    private static final int REQ_PERM_STORAGE = 11;
    private static final int PICK_GALLERY = 1;
    private static final int PICK_FILES = 2;
    /** Ignore a second tap that would start the same external screen twice (picker, share sheet, viewer). */
    private static final long LAUNCH_DEBOUNCE_MS = 800;

    private static final String S_PENDING_PICK = "pending_pick";
    private static final String S_PENDING_SAVE = "pending_save";
    private static final String S_PICKER = "picker";

    private static final int[] ADV_FIELD_IDS = {R.id.adv_make, R.id.adv_model, R.id.adv_software, R.id.adv_lens_make,
            R.id.adv_lens_model, R.id.adv_host, R.id.adv_icc_mfr, R.id.adv_icc_model};
    private static final int[] ADV_LABEL_IDS = {R.id.adv_make_label, R.id.adv_model_label, R.id.adv_software_label,
            R.id.adv_lens_make_label, R.id.adv_lens_model_label, R.id.adv_host_label, R.id.adv_icc_mfr_label,
            R.id.adv_icc_model_label};
    private static final int[] ADV_LABEL_TEXT = {R.string.adv_make, R.string.adv_model, R.string.adv_software,
            R.string.adv_lens_make, R.string.adv_lens_model, R.string.adv_host, R.string.adv_icc_mfr,
            R.string.adv_icc_model};

    private AppState state;

    private View root;
    private Button btnPickGallery;
    private Button btnPickFiles;
    private Button btnClear;
    private View selectionHeader;
    private TextView selectionCount;
    private TextView emptyText;
    private LinearLayout itemsContainer;
    private TextView deviceBrand;
    private TextView deviceName;
    private TextView deviceDetail;
    private Button btnPickDevice;
    private CheckBox optIcc;
    private CheckBox optLens;
    private CheckBox optSoftware;
    private CheckBox optTraces;
    private CheckBox optXmp;
    private CheckBox optDescTags;
    private CheckBox optKeepName;
    private Button advancedToggle;
    private View advancedContainer;
    private final EditText[] advFields = new EditText[EditOptions.FIELD_COUNT];
    private final TextView[] advLabels = new TextView[EditOptions.FIELD_COUNT];
    private Spinner albumSpinner;
    private TextView albumNote;
    private Button btnProcess;
    private ProgressBar overallProgress;
    private TextView jobStatus;
    private Button btnSaveAll;
    private View messageBar;
    private TextView messageText;
    private Button messageAction;

    private final Map<Long, ItemRow> rows = new HashMap<Long, ItemRow>();
    private int thumbPx;
    private boolean bindingOptions;
    private boolean fillingAdvanced;
    private int pendingPick;
    private long[] pendingSaveIds;
    private Bundle pendingPickerState;
    private DevicePickerDialog picker;
    private AlertDialog helpDialog;
    private Uri messageUri;
    private String messageMime;
    private boolean keepScreenOn;
    private long lastLaunchAt;

    private final Runnable hideMessage = new Runnable() {
        @Override
        public void run() {
            hideMessageBar();
        }
    };

    // ------------------------------------------------------------------ lifecycle

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UiUtil.enableEdgeToEdge(this);
        setContentView(R.layout.activity_main);
        state = AppState.get(this);
        thumbPx = getResources().getDimensionPixelSize(R.dimen.mk_thumb);
        findViews();
        setupListeners();
        setupAdvancedFields();
        setupAlbumSpinner();
        UiUtil.applyInsetsPadding(root);
        View column = findViewById(R.id.content_column); // single-column layout only
        if (column != null) {
            UiUtil.limitColumnWidth(column, getResources().getDimensionPixelSize(R.dimen.mk_content_max_width),
                    getResources().getDimensionPixelSize(R.dimen.mk_gutter));
        }
        if (savedInstanceState != null) {
            pendingPick = savedInstanceState.getInt(S_PENDING_PICK, 0);
            pendingSaveIds = savedInstanceState.getLongArray(S_PENDING_SAVE);
            pendingPickerState = savedInstanceState.getBundle(S_PICKER);
        }
        state.ensureDatabase();
    }

    @Override
    protected void onStart() {
        super.onStart();
        state.setListener(this);
        bindAll();
        if (pendingPickerState != null && state.db != null) {
            showPicker(pendingPickerState);
            pendingPickerState = null;
        }
    }

    @Override
    protected void onStop() {
        state.removeListener(this);
        super.onStop();
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putInt(S_PENDING_PICK, pendingPick);
        if (pendingSaveIds != null) out.putLongArray(S_PENDING_SAVE, pendingSaveIds);
        if (picker != null && picker.isShowing()) out.putBundle(S_PICKER, picker.saveState());
        else if (pendingPickerState != null) out.putBundle(S_PICKER, pendingPickerState);
    }

    /**
     * Android 8-11 finish the root activity on Back, which also revokes the read grants of every picked
     * uri (they belong to this activity record): the selection would survive in AppState but could no
     * longer be read. Behave like Android 12+ instead and only move the task to the background. On 12+
     * the system already does this (and with predictive back on 16+ this method is not called at all).
     */
    @Override
    @SuppressWarnings("deprecation")
    public void onBackPressed() {
        if (Build.VERSION.SDK_INT < 31 && isTaskRoot() && (state.isBusy() || !state.items.isEmpty())) {
            try {
                if (moveTaskToBack(true)) return;
            } catch (RuntimeException ignored) {
                // fall back to the default behaviour
            }
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        if (messageBar != null) messageBar.removeCallbacks(hideMessage);
        if (picker != null) {
            picker.dismiss();
            picker = null;
        }
        if (helpDialog != null) {
            helpDialog.dismiss();
            helpDialog = null;
        }
        super.onDestroy();
    }

    private void findViews() {
        root = findViewById(R.id.root);
        btnPickGallery = (Button) findViewById(R.id.btn_pick_gallery);
        btnPickFiles = (Button) findViewById(R.id.btn_pick_files);
        btnClear = (Button) findViewById(R.id.btn_clear);
        selectionHeader = findViewById(R.id.selection_header);
        selectionCount = (TextView) findViewById(R.id.selection_count);
        emptyText = (TextView) findViewById(R.id.empty_text);
        itemsContainer = (LinearLayout) findViewById(R.id.items_container);
        deviceBrand = (TextView) findViewById(R.id.device_brand);
        deviceName = (TextView) findViewById(R.id.device_name);
        deviceDetail = (TextView) findViewById(R.id.device_detail);
        btnPickDevice = (Button) findViewById(R.id.btn_pick_device);
        optIcc = (CheckBox) findViewById(R.id.opt_icc);
        optLens = (CheckBox) findViewById(R.id.opt_lens);
        optSoftware = (CheckBox) findViewById(R.id.opt_software);
        optTraces = (CheckBox) findViewById(R.id.opt_traces);
        optXmp = (CheckBox) findViewById(R.id.opt_xmp);
        optDescTags = (CheckBox) findViewById(R.id.opt_desc_tags);
        optKeepName = (CheckBox) findViewById(R.id.opt_keep_name);
        advancedToggle = (Button) findViewById(R.id.advanced_toggle);
        advancedContainer = findViewById(R.id.advanced_container);
        albumSpinner = (Spinner) findViewById(R.id.album_spinner);
        albumNote = (TextView) findViewById(R.id.album_note);
        btnProcess = (Button) findViewById(R.id.btn_process);
        overallProgress = (ProgressBar) findViewById(R.id.overall_progress);
        jobStatus = (TextView) findViewById(R.id.job_status);
        btnSaveAll = (Button) findViewById(R.id.btn_save_all);
        messageBar = findViewById(R.id.message_bar);
        messageText = (TextView) findViewById(R.id.message_text);
        messageAction = (Button) findViewById(R.id.message_action);
    }

    private void setupListeners() {
        findViewById(R.id.btn_help).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showHelp();
            }
        });
        btnPickGallery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestPick(PICK_GALLERY);
            }
        });
        btnPickFiles.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestPick(PICK_FILES);
            }
        });
        btnClear.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                state.clearItems();
            }
        });
        btnPickDevice.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showPicker(null);
            }
        });
        btnProcess.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                state.startJob();
            }
        });
        btnSaveAll.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestSave(state.unsavedDone());
            }
        });
        advancedToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                state.advancedExpanded = !state.advancedExpanded;
                bindAdvancedVisibility();
            }
        });
        findViewById(R.id.btn_reset_advanced).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                state.options.clearOverrides();
                fillAdvanced();
            }
        });
        messageAction.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Uri u = messageUri;
                String m = messageMime;
                hideMessageBar();
                openUri(u, m);
            }
        });
        messageText.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                hideMessageBar();
            }
        });

        CompoundButton.OnCheckedChangeListener optListener = new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (bindingOptions) return;
                EditOptions o = state.options;
                int id = b.getId();
                if (id == R.id.opt_icc) o.icc = checked;
                else if (id == R.id.opt_lens) o.lens = checked;
                else if (id == R.id.opt_software) o.software = checked;
                else if (id == R.id.opt_traces) o.traces = checked;
                else if (id == R.id.opt_xmp) o.xmp = checked;
                else if (id == R.id.opt_desc_tags) o.descTags = checked;
                state.saveOptions();
                bindIccDependents();
            }
        };
        CheckBox[] boxes = {optIcc, optLens, optSoftware, optTraces, optXmp, optDescTags};
        for (CheckBox cb : boxes) {
            cb.setSaveEnabled(false);
            cb.setOnCheckedChangeListener(optListener);
        }
        optKeepName.setSaveEnabled(false);
        optKeepName.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton b, boolean checked) {
                if (!bindingOptions) state.prefs.setKeepName(checked);
            }
        });
    }

    private void setupAdvancedFields() {
        InputFilter ascii = new AsciiFilter();
        for (int i = 0; i < EditOptions.FIELD_COUNT; i++) {
            final int field = i;
            EditText e = (EditText) findViewById(ADV_FIELD_IDS[i]);
            e.setSaveEnabled(false);
            if (field == EditOptions.F_ICC_MFR || field == EditOptions.F_ICC_MODEL) {
                e.setFilters(new InputFilter[]{ascii, new InputFilter.LengthFilter(4)});
            }
            e.addTextChangedListener(new TextWatcher() {
                @Override
                public void beforeTextChanged(CharSequence s, int start, int count, int after) {
                }

                @Override
                public void onTextChanged(CharSequence s, int start, int before, int count) {
                }

                @Override
                public void afterTextChanged(Editable s) {
                    if (!fillingAdvanced) onAdvancedEdited(field, s.toString());
                }
            });
            advFields[i] = e;
            advLabels[i] = (TextView) findViewById(ADV_LABEL_IDS[i]);
        }
    }

    private void setupAlbumSpinner() {
        List<String> albums = new ArrayList<String>();
        albums.add(getString(R.string.album_camera));
        albums.add(getString(R.string.album_app));
        ArrayAdapter<String> a = new ArrayAdapter<String>(this, android.R.layout.simple_spinner_item, albums);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        albumSpinner.setAdapter(a);
        albumSpinner.setSaveEnabled(false);
        albumSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                int album = position == 1 ? Prefs.ALBUM_APP : Prefs.ALBUM_CAMERA;
                if (!bindingOptions) state.prefs.setAlbum(album);
                bindAlbumNote(album);
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });
    }

    // ------------------------------------------------------------------ AppState.Listener

    @Override
    public void onItemsChanged() {
        bindItems();
        bindActions();
    }

    @Override
    public void onItemChanged(MediaItem item) {
        ItemRow row = rows.get(item.id);
        if (row != null) {
            int idx = state.items.indexOf(item);
            row.bind(this, item, idx <= 0, state.isBusy(), state.thumbs, thumbPx);
        }
        bindActions();
    }

    @Override
    public void onDeviceChanged() {
        bindDevice();
        fillAdvanced();
        bindActions();
        // picker open before the process was killed: reopen once the database has loaded again
        if (pendingPickerState != null && state.db != null) {
            Bundle b = pendingPickerState;
            pendingPickerState = null;
            showPicker(b);
        }
    }

    @Override
    public void onBusyChanged() {
        bindItems();
        bindActions();
    }

    @Override
    public void onJobProgress() {
        bindJobProgress();
    }

    @Override
    public void onMessage(AppState.Message m) {
        showMessage(m.text, m.openUri, m.openMime);
    }

    // ------------------------------------------------------------------ binding

    private void bindAll() {
        bindItems();
        bindDevice();
        bindOptions();
        fillAdvanced();
        bindActions();
    }

    /** Syncs item rows with the selection, reusing existing row views. */
    private void bindItems() {
        List<MediaItem> items = state.items;
        boolean busy = state.isBusy();
        boolean structureSame = items.size() == itemsContainer.getChildCount() && items.size() == rows.size();
        if (structureSame) {
            for (int i = 0; i < items.size(); i++) {
                ItemRow r = rows.get(items.get(i).id);
                if (r == null || itemsContainer.getChildAt(i) != r.view) {
                    structureSame = false;
                    break;
                }
            }
        }
        if (!structureSame) {
            Map<Long, ItemRow> keep = new HashMap<Long, ItemRow>();
            LayoutInflater inf = getLayoutInflater();
            itemsContainer.removeAllViews();
            for (MediaItem it : items) {
                ItemRow r = rows.get(it.id);
                if (r == null) r = new ItemRow(inf, itemsContainer, this);
                keep.put(it.id, r);
                itemsContainer.addView(r.view);
            }
            rows.clear();
            rows.putAll(keep);
        }
        for (int i = 0; i < items.size(); i++) {
            MediaItem it = items.get(i);
            rows.get(it.id).bind(this, it, i == 0, busy, state.thumbs, thumbPx);
        }
        boolean has = !items.isEmpty();
        UiUtil.setVisible(selectionHeader, has);
        UiUtil.setVisible(emptyText, !has);
        if (has) UiUtil.setText(selectionCount, getString(R.string.selection_count, items.size()));
    }

    private void bindDevice() {
        Device d = state.device;
        if (d == null) {
            UiUtil.setText(deviceBrand, "");
            UiUtil.setVisible(deviceBrand, false);
            int msg = state.dbLoading ? R.string.device_loading
                    : state.dbError != null ? R.string.device_load_error : R.string.device_none;
            UiUtil.setText(deviceName, getString(msg));
            UiUtil.setText(deviceDetail, "");
            UiUtil.setVisible(deviceDetail, false);
        } else {
            UiUtil.setVisible(deviceBrand, true);
            UiUtil.setText(deviceBrand, d.brand);
            UiUtil.setText(deviceName, d.name);
            String model = SpecBuilder.modelFor(d, state.variant);
            String detail = d.year > 0 ? getString(R.string.device_detail, d.year, d.make, model) : d.make + " · " + model;
            UiUtil.setText(deviceDetail, detail);
            UiUtil.setVisible(deviceDetail, true);
        }
    }

    private void bindOptions() {
        bindingOptions = true;
        try {
            EditOptions o = state.options;
            optIcc.setChecked(o.icc);
            optLens.setChecked(o.lens);
            optSoftware.setChecked(o.software);
            optTraces.setChecked(o.traces);
            optXmp.setChecked(o.xmp);
            optDescTags.setChecked(o.descTags);
            optKeepName.setChecked(state.prefs.keepName());
            int album = state.prefs.album();
            if (albumSpinner.getSelectedItemPosition() != album) albumSpinner.setSelection(album, false);
            bindAlbumNote(album);
        } finally {
            bindingOptions = false;
        }
        bindAdvancedVisibility();
        bindIccDependents();
    }

    /** Where photos and videos end up (videos cannot go under Pictures/, see GallerySaver). */
    private void bindAlbumNote(int album) {
        if (albumNote == null) return;
        UiUtil.setText(albumNote, getString(album == Prefs.ALBUM_APP ? R.string.album_note_app : R.string.album_note_camera));
    }

    private void bindAdvancedVisibility() {
        UiUtil.setVisible(advancedContainer, state.advancedExpanded);
        UiUtil.setText(advancedToggle, getString(state.advancedExpanded ? R.string.advanced_hide : R.string.advanced_show));
    }

    private void bindIccDependents() {
        boolean icc = state.options.icc;
        UiUtil.setEnabled(advFields[EditOptions.F_ICC_MFR], icc);
        UiUtil.setEnabled(advFields[EditOptions.F_ICC_MODEL], icc);
        UiUtil.setEnabled(optDescTags, icc);
    }

    /** Fills the advanced fields with the user's overrides or the chosen device's values. */
    private void fillAdvanced() {
        Device d = state.device;
        BrandInfo b = state.brandInfo();
        fillingAdvanced = true;
        try {
            for (int f = 0; f < EditOptions.FIELD_COUNT; f++) {
                String ov = state.options.overrides[f];
                String v = ov != null ? ov : SpecBuilder.defaultValue(f, d, b, state.variant);
                EditText e = advFields[f];
                if (!v.contentEquals(e.getText())) e.setText(v);
                if (f == EditOptions.F_ICC_MFR || f == EditOptions.F_ICC_MODEL) {
                    e.setHint(SpecBuilder.iccFieldKeeps(f, b) ? R.string.adv_icc_keep_hint : R.string.adv_icc_hint);
                }
            }
        } finally {
            fillingAdvanced = false;
        }
        bindAdvancedLabels();
    }

    private void onAdvancedEdited(int field, String text) {
        String def = SpecBuilder.defaultValue(field, state.device, state.brandInfo(), state.variant);
        state.options.overrides[field] = text.equals(def) ? null : text;
        bindAdvancedLabels();
    }

    private void bindAdvancedLabels() {
        for (int f = 0; f < EditOptions.FIELD_COUNT; f++) {
            String label = getString(ADV_LABEL_TEXT[f]);
            if (state.options.overrides[f] != null) label += getString(R.string.adv_edited_suffix);
            UiUtil.setText(advLabels[f], label);
        }
    }

    private void bindActions() {
        boolean busy = state.isBusy();
        boolean has = !state.items.isEmpty();
        UiUtil.setEnabled(btnPickGallery, !busy);
        UiUtil.setEnabled(btnPickFiles, !busy);
        UiUtil.setEnabled(btnClear, !busy && has);
        UiUtil.setEnabled(btnPickDevice, !state.jobRunning && state.db != null);
        UiUtil.setEnabled(btnProcess, !busy && has && state.device != null);
        UiUtil.setText(btnProcess, getString(state.jobRunning ? R.string.processing : R.string.process));
        UiUtil.setEnabled(btnSaveAll, !busy && state.countUnsavedDone() > 0);
        bindJobProgress();
        if (busy != keepScreenOn) {
            keepScreenOn = busy;
            if (busy) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        }
    }

    private void bindJobProgress() {
        if (state.jobRunning) {
            UiUtil.setVisible(overallProgress, true);
            float frac = state.jobTotal <= 0 ? 0f
                    : (state.jobIndex + state.jobItemFraction) / (float) state.jobTotal;
            overallProgress.setProgress(Math.round(Math.max(0f, Math.min(1f, frac)) * 1000));
            UiUtil.setVisible(jobStatus, true);
            String name = state.jobCurrentName == null ? "" : state.jobCurrentName;
            UiUtil.setText(jobStatus, getString(R.string.job_running,
                    Math.min(state.jobIndex + 1, state.jobTotal), state.jobTotal, name));
        } else if (state.savingCount > 0) {
            UiUtil.setVisible(overallProgress, false);
            UiUtil.setVisible(jobStatus, true);
            UiUtil.setText(jobStatus, getString(R.string.job_saving));
        } else if (state.jobFinished && (state.jobOk + state.jobFail) > 0) {
            UiUtil.setVisible(overallProgress, false);
            UiUtil.setVisible(jobStatus, true);
            UiUtil.setText(jobStatus, state.jobFail == 0 ? getString(R.string.job_done_all, state.jobOk)
                    : getString(R.string.job_done_mixed, state.jobOk, state.jobFail));
        } else {
            UiUtil.setVisible(overallProgress, false);
            UiUtil.setVisible(jobStatus, false);
        }
    }

    // ------------------------------------------------------------------ ItemRow.Callbacks

    @Override
    public void onRemove(MediaItem item) {
        state.remove(item);
    }

    @Override
    public void onToggleDetails(MediaItem item) {
        item.expanded = !item.expanded;
        onItemChanged(item);
    }

    @Override
    public void onSave(MediaItem item) {
        List<MediaItem> l = new ArrayList<MediaItem>();
        l.add(item);
        requestSave(l);
    }

    @Override
    public void onOpen(MediaItem item) {
        openUri(item.savedUri, item.savedMime);
    }

    @Override
    public void onShare(MediaItem item) {
        if (!item.isDone() || item.outputName == null || item.outputFormat == null) return;
        if (!item.output.isFile()) {
            showMessage(getString(R.string.msg_file_missing), null, null);
            return;
        }
        Uri u = EditedFileProvider.uriFor(this, item.output);
        if (u == null) {
            showMessage(getString(R.string.msg_file_missing), null, null);
            return;
        }
        if (!launchAllowed()) return;
        String mime = GallerySaver.mimeFor(item.outputName, item.outputFormat);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(mime);
        send.putExtra(Intent.EXTRA_STREAM, u);
        send.setClipData(ClipData.newRawUri(item.outputName, u));
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(Intent.createChooser(send, getString(R.string.share_title)));
        } catch (RuntimeException e) {
            showMessage(getString(R.string.msg_no_share), null, null);
        }
    }

    /** False when another external screen was started a moment ago (double tap); otherwise records now. */
    private boolean launchAllowed() {
        long now = SystemClock.elapsedRealtime();
        if (lastLaunchAt != 0 && now - lastLaunchAt >= 0 && now - lastLaunchAt < LAUNCH_DEBOUNCE_MS) return false;
        lastLaunchAt = now;
        return true;
    }

    // ------------------------------------------------------------------ picking media

    private void requestPick(int which) {
        // pendingPick != 0: the media-location request is still on screen (a second tap would open the
        // picker now and once more when the permission answer arrives)
        if (state.isBusy() || pendingPick != 0) return;
        if (Build.VERSION.SDK_INT >= 29 && !state.prefs.mediaLocationAsked()
                && checkSelfPermission(Manifest.permission.ACCESS_MEDIA_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            state.prefs.setMediaLocationAsked();
            pendingPick = which;
            try {
                requestPermissions(new String[]{Manifest.permission.ACCESS_MEDIA_LOCATION}, REQ_PERM_LOCATION);
                return;
            } catch (RuntimeException e) {
                pendingPick = 0;
            }
        }
        launchPick(which);
    }

    private void launchPick(int which) {
        if (isFinishing()) return;
        int remaining = AppState.MAX_ITEMS - state.items.size();
        if (remaining <= 0) {
            showMessage(getString(R.string.msg_max_items, AppState.MAX_ITEMS), null, null);
            return;
        }
        if (!launchAllowed()) return;
        if (which == PICK_GALLERY) {
            // photo picker -> any gallery app (GET_CONTENT) -> document picker
            if (isPhotoPickerAvailable() && tryStart(photoPickerIntent(remaining), REQ_PICK_GALLERY)) return;
            if (tryStart(getContentIntent(), REQ_PICK_GALLERY)) return;
        }
        if (!tryStart(filesIntent(), REQ_PICK_FILES)) showMessage(getString(R.string.msg_no_picker), null, null);
    }

    private boolean tryStart(Intent intent, int requestCode) {
        try {
            startActivityForResult(intent, requestCode);
            return true;
        } catch (RuntimeException e) {
            // ActivityNotFoundException, or a SecurityException from a broken handler
            return false;
        }
    }

    /** Photo picker availability: API 33+, or API 30+ with SDK extension R >= 2. */
    private static boolean isPhotoPickerAvailable() {
        if (Build.VERSION.SDK_INT >= 33) return true;
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                return PhotoPicker.extensionVersionR() >= 2;
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    private static Intent photoPickerIntent(int remaining) {
        // images and videos (no type filter)
        Intent i = new Intent(MediaStore.ACTION_PICK_IMAGES);
        int limit = AppState.MAX_ITEMS;
        try {
            limit = PhotoPicker.maxLimit();
        } catch (Throwable ignored) {
            // keep our own limit
        }
        // EXTRA_PICK_IMAGES_MAX must be > 1 and <= getPickImagesMaxLimit(), otherwise the picker fails
        int max = Math.min(remaining, limit);
        if (max > 1) i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX, max);
        return i;
    }

    private static Intent getContentIntent() {
        Intent i = new Intent(Intent.ACTION_GET_CONTENT);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        return i;
    }

    private static Intent filesIntent() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        return i;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode != REQ_PICK_GALLERY && requestCode != REQ_PICK_FILES) {
            super.onActivityResult(requestCode, resultCode, data);
            return;
        }
        if (resultCode != RESULT_OK || data == null) return;
        List<Uri> uris = new ArrayList<Uri>();
        try {
            ClipData cd = data.getClipData();
            if (cd != null) {
                for (int i = 0; i < cd.getItemCount(); i++) {
                    ClipData.Item ci = cd.getItemAt(i);
                    Uri u = ci == null ? null : ci.getUri();
                    if (u != null && !uris.contains(u)) uris.add(u);
                }
            }
            Uri single = data.getData();
            if (single != null && !uris.contains(single)) uris.add(single);
        } catch (RuntimeException e) {
            // malformed result: use what we have
        }
        state.addUris(uris);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        boolean granted = grantResults != null && grantResults.length > 0
                && grantResults[0] == PackageManager.PERMISSION_GRANTED;
        if (requestCode == REQ_PERM_LOCATION) {
            int which = pendingPick;
            pendingPick = 0;
            if (which != 0) launchPick(which); // denied: still pick, GPS may just be redacted
        } else if (requestCode == REQ_PERM_STORAGE) {
            long[] ids = pendingSaveIds;
            pendingSaveIds = null;
            if (!granted) {
                showMessage(getString(R.string.msg_need_storage), null, null);
            } else if (ids != null) {
                List<MediaItem> l = new ArrayList<MediaItem>();
                for (long id : ids) {
                    MediaItem it = state.findItem(id);
                    if (it != null) l.add(it);
                }
                state.saveToGallery(l);
            }
        } else {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        }
    }

    // ------------------------------------------------------------------ gallery / open

    @SuppressWarnings("deprecation")
    private void requestSave(List<MediaItem> list) {
        // pendingSaveIds != null: the storage request is still on screen; a second request would be
        // answered "denied" at once by Activity.requestPermissions and drop the first one's items
        if (list == null || list.isEmpty() || state.isBusy() || pendingSaveIds != null) return;
        if (Build.VERSION.SDK_INT < 29
                && checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            long[] ids = new long[list.size()];
            for (int i = 0; i < ids.length; i++) ids[i] = list.get(i).id;
            pendingSaveIds = ids;
            try {
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_PERM_STORAGE);
            } catch (RuntimeException e) {
                pendingSaveIds = null;
                showMessage(getString(R.string.msg_need_storage), null, null);
            }
            return;
        }
        state.saveToGallery(list);
    }

    private void openUri(Uri uri, String mime) {
        if (uri == null || !launchAllowed()) return;
        Intent v = new Intent(Intent.ACTION_VIEW);
        if (mime != null) v.setDataAndType(uri, mime);
        else v.setData(uri);
        v.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(v);
        } catch (RuntimeException e) {
            showMessage(getString(R.string.msg_no_viewer), null, null);
        }
    }

    // ------------------------------------------------------------------ dialogs & messages

    private void showPicker(Bundle restore) {
        if (state.db == null || isFinishing() || isDestroyed()) return;
        if (picker != null && picker.isShowing()) return;
        picker = new DevicePickerDialog(this, state.db, state.device, state.variant, new DevicePickerDialog.Callback() {
            @Override
            public void onDeviceChosen(Device device, String variant) {
                state.setDevice(device, variant);
            }
        });
        if (restore != null) picker.restoreState(restore);
        picker.show();
    }

    private void showHelp() {
        if (isFinishing() || isDestroyed()) return;
        if (helpDialog != null && helpDialog.isShowing()) return;
        helpDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.help_title)
                .setMessage(R.string.help_body)
                .setPositiveButton(R.string.help_ok, null)
                .create();
        helpDialog.show();
    }

    private void showMessage(String text, Uri open, String mime) {
        if (text == null) return;
        messageText.setText(text);
        messageUri = open;
        messageMime = mime;
        UiUtil.setVisible(messageAction, open != null);
        messageBar.animate().cancel();
        if (messageBar.getVisibility() != View.VISIBLE) {
            messageBar.setAlpha(0f);
            messageBar.setVisibility(View.VISIBLE);
        }
        messageBar.animate().alpha(1f).setDuration(150).start();
        messageBar.removeCallbacks(hideMessage);
        messageBar.postDelayed(hideMessage, open != null ? 7000 : 4500);
        messageBar.announceForAccessibility(text);
    }

    private void hideMessageBar() {
        messageBar.removeCallbacks(hideMessage);
        if (messageBar.getVisibility() != View.VISIBLE) return;
        messageBar.animate().cancel();
        messageBar.animate().alpha(0f).setDuration(150).withEndAction(new Runnable() {
            @Override
            public void run() {
                messageBar.setVisibility(View.GONE);
            }
        }).start();
    }

    /** API 30+ photo-picker helpers, isolated so older ART versions verify MainActivity cleanly. */
    private static final class PhotoPicker {
        static int extensionVersionR() {
            return SdkExtensions.getExtensionVersion(Build.VERSION_CODES.R);
        }

        static int maxLimit() {
            return MediaStore.getPickImagesMaxLimit();
        }
    }

    /**
     * Keeps ICC signature input to printable ASCII; Turkish letters are folded (İ->I, ş->s, ...),
     * which matters with the all-caps Turkish keyboard.
     */
    private static final class AsciiFilter implements InputFilter {
        @Override
        public CharSequence filter(CharSequence source, int start, int end, Spanned dest, int dstart, int dend) {
            boolean changed = false;
            StringBuilder sb = new StringBuilder(end - start);
            for (int i = start; i < end; i++) {
                char c = source.charAt(i);
                if (c >= 0x20 && c <= 0x7E) {
                    sb.append(c);
                    continue;
                }
                changed = true;
                char f = fold(c);
                if (f != 0) sb.append(f);
            }
            return changed ? sb.toString() : null;
        }

        private static char fold(char c) {
            if (c == 'İ') return 'I';
            if (c == 'ı') return 'i';
            String d = Normalizer.normalize(String.valueOf(c), Normalizer.Form.NFD);
            char b = d.length() > 0 ? d.charAt(0) : 0;
            return b >= 0x20 && b <= 0x7E ? b : 0;
        }
    }
}
