package com.metakamera.app;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/**
 * "Telefon Seç" dialog: search box, brand filter, list grouped by brand (newest first) and an optional
 * "Model kodu" choice for phones with regional model codes.
 */
final class DevicePickerDialog extends Dialog {

    interface Callback {
        void onDeviceChosen(Device device, String variant);
    }

    private static final String S_QUERY = "query";
    private static final String S_BRAND = "brand";
    private static final String S_SELECTED = "selected";
    private static final String S_VARIANT = "variant";

    private final DeviceDatabase db;
    private final Callback callback;
    private final EditText search;
    private final Spinner brandSpinner;
    private final ListView list;
    private final View emptyView;
    private final View variantRow;
    private final Spinner variantSpinner;
    private final Button ok;
    private final RowAdapter adapter = new RowAdapter();
    private final List<String> brands;

    private String brandFilter;
    private Device selected;
    private String selectedVariant;
    private String[] variantChoices = new String[0];
    private boolean bindingVariants;

    DevicePickerDialog(Activity activity, DeviceDatabase db, Device current, String variant, Callback callback) {
        super(activity, R.style.Theme_MetaKamera_Dialog);
        this.db = db;
        this.callback = callback;
        this.selected = current;
        this.selectedVariant = variant;
        this.brands = db.brandNames();
        setContentView(R.layout.dialog_device_picker);
        setCanceledOnTouchOutside(true);

        search = (EditText) findViewById(R.id.picker_search);
        brandSpinner = (Spinner) findViewById(R.id.picker_brand);
        list = (ListView) findViewById(R.id.picker_list);
        emptyView = findViewById(R.id.picker_empty);
        variantRow = findViewById(R.id.picker_variant_row);
        variantSpinner = (Spinner) findViewById(R.id.picker_variant);
        ok = (Button) findViewById(R.id.picker_ok);
        Button cancel = (Button) findViewById(R.id.picker_cancel);
        search.setSaveEnabled(false);

        Context c = getContext();
        List<String> brandItems = new ArrayList<String>();
        brandItems.add(c.getString(R.string.picker_all_brands));
        brandItems.addAll(brands);
        ArrayAdapter<String> ba = new ArrayAdapter<String>(c, android.R.layout.simple_spinner_item, brandItems);
        ba.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        brandSpinner.setAdapter(ba);
        brandSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String b = position <= 0 || position > brands.size() ? null : brands.get(position - 1);
                if (b == null ? brandFilter != null : !b.equals(brandFilter)) {
                    brandFilter = b;
                    refilter(false);
                }
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {
            }

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                refilter(false);
            }
        });
        search.setOnEditorActionListener(new TextView.OnEditorActionListener() {
            @Override
            public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
                if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE) {
                    hideKeyboard();
                    return true;
                }
                return false;
            }
        });

        list.setAdapter(adapter);
        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                Object row = adapter.getItem(position);
                if (row instanceof Device) {
                    Device d = (Device) row;
                    if (d != selected) {
                        selected = d;
                        selectedVariant = null;
                    }
                    adapter.notifyDataSetChanged();
                    bindVariants();
                    hideKeyboard();
                }
            }
        });

        variantSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (bindingVariants) return;
                selectedVariant = position <= 0 || position >= variantChoices.length ? null : variantChoices[position];
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        cancel.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                dismiss();
            }
        });
        ok.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Device d = selected;
                String variant = selectedVariant;
                dismiss();
                if (d != null) callback.onDeviceChosen(d, variant);
            }
        });

        refilter(true);
        bindVariants();
    }

    @Override
    protected void onStart() {
        super.onStart();
        Window w = getWindow();
        if (w == null) return;
        DisplayMetrics dm = getContext().getResources().getDisplayMetrics();
        int width = Math.min(dm.widthPixels - UiUtil.dp(getContext(), 24), UiUtil.dp(getContext(), 560));
        int height = Math.min(Math.round(dm.heightPixels * 0.9f), UiUtil.dp(getContext(), 760));
        w.setLayout(Math.max(width, UiUtil.dp(getContext(), 280)), Math.max(height, UiUtil.dp(getContext(), 320)));
        w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN);
    }

    Bundle saveState() {
        Bundle b = new Bundle();
        b.putString(S_QUERY, search.getText().toString());
        b.putString(S_BRAND, brandFilter);
        b.putString(S_SELECTED, selected == null ? null : selected.key);
        b.putString(S_VARIANT, selectedVariant);
        return b;
    }

    void restoreState(Bundle b) {
        if (b == null) return;
        Device d = db.find(b.getString(S_SELECTED));
        if (d != null) {
            selected = d;
            selectedVariant = b.getString(S_VARIANT);
        }
        String brand = b.getString(S_BRAND);
        int idx = brand == null ? -1 : brands.indexOf(brand);
        brandFilter = idx >= 0 ? brand : null;
        brandSpinner.setSelection(idx >= 0 ? idx + 1 : 0);
        String q = b.getString(S_QUERY);
        search.setText(q == null ? "" : q);
        refilter(true);
        bindVariants();
    }

    private void refilter(boolean scrollToSelection) {
        List<Device> devices = db.filter(search.getText().toString(), brandFilter);
        List<Object> rows = new ArrayList<Object>();
        String lastBrand = null;
        int selectedRow = -1;
        for (Device d : devices) {
            if (lastBrand == null || !lastBrand.equalsIgnoreCase(d.brand)) {
                rows.add(d.brand);
                lastBrand = d.brand;
            }
            if (d == selected) selectedRow = rows.size();
            rows.add(d);
        }
        adapter.setRows(rows);
        UiUtil.setVisible(emptyView, rows.isEmpty());
        if (scrollToSelection && selectedRow >= 0) list.setSelection(Math.max(0, selectedRow - 1));
        UiUtil.setEnabled(ok, selected != null);
    }

    private void bindVariants() {
        UiUtil.setEnabled(ok, selected != null);
        if (selected == null || selected.variants.length == 0) {
            variantChoices = new String[0];
            UiUtil.setVisible(variantRow, false);
            return;
        }
        variantChoices = selected.modelChoices();
        List<String> labels = new ArrayList<String>();
        for (int i = 0; i < variantChoices.length; i++) {
            labels.add(i == 0 ? getContext().getString(R.string.picker_variant_main, variantChoices[0]) : variantChoices[i]);
        }
        int sel = 0;
        for (int i = 1; i < variantChoices.length; i++) if (variantChoices[i].equals(selectedVariant)) sel = i;
        if (sel == 0) selectedVariant = null;
        bindingVariants = true;
        try {
            ArrayAdapter<String> va = new ArrayAdapter<String>(getContext(), android.R.layout.simple_spinner_item, labels);
            va.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
            variantSpinner.setAdapter(va);
            variantSpinner.setSelection(sel, false);
        } finally {
            bindingVariants = false;
        }
        UiUtil.setVisible(variantRow, true);
    }

    private void hideKeyboard() {
        try {
            InputMethodManager imm = (InputMethodManager) getContext().getSystemService(Context.INPUT_METHOD_SERVICE);
            if (imm != null) imm.hideSoftInputFromWindow(search.getWindowToken(), 0);
        } catch (RuntimeException ignored) {
            // cosmetic
        }
    }

    /** Brand header rows (String) and device rows (Device). */
    private final class RowAdapter extends BaseAdapter {
        private List<Object> rows = new ArrayList<Object>();

        void setRows(List<Object> r) {
            rows = r;
            notifyDataSetChanged();
        }

        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return position >= 0 && position < rows.size() ? rows.get(position) : null;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return getItem(position) instanceof Device ? 1 : 0;
        }

        @Override
        public boolean areAllItemsEnabled() {
            return false;
        }

        @Override
        public boolean isEnabled(int position) {
            return getItem(position) instanceof Device;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            Object row = getItem(position);
            LayoutInflater inf = LayoutInflater.from(getContext());
            if (!(row instanceof Device)) {
                TextView h = convertView instanceof TextView ? (TextView) convertView
                        : (TextView) inf.inflate(R.layout.row_brand_header, parent, false);
                h.setText(row == null ? "" : row.toString());
                return h;
            }
            Device d = (Device) row;
            View v = convertView != null && convertView.findViewById(R.id.row_name) != null ? convertView
                    : inf.inflate(R.layout.row_device, parent, false);
            ((TextView) v.findViewById(R.id.row_name)).setText(d.name);
            String detail = d.make + " · " + d.model;
            if (d.variants.length > 0) {
                detail += " · " + getContext().getString(R.string.picker_variants_count, d.variants.length + 1);
            }
            ((TextView) v.findViewById(R.id.row_detail)).setText(detail);
            TextView year = (TextView) v.findViewById(R.id.row_year);
            year.setText(d.year > 0 ? String.valueOf(d.year) : "");
            UiUtil.setVisible(year, d.year > 0);
            v.setBackgroundResource(d == selected ? R.drawable.bg_row_selected : 0);
            v.setActivated(d == selected);
            return v;
        }
    }
}
