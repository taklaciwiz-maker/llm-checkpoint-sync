package com.metakamera.app;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;

import com.metakamera.core.EditReport;
import com.metakamera.core.MediaFormat;
import com.metakamera.core.MediaInfo;

/** View holder for one selected item (layout/item_media.xml). Main thread only. */
final class ItemRow {

    interface Callbacks {
        void onRemove(MediaItem item);

        void onToggleDetails(MediaItem item);

        void onSave(MediaItem item);

        void onOpen(MediaItem item);

        void onShare(MediaItem item);
    }

    final View view;
    private final View divider;
    private final ImageView thumb;
    private final ImageView videoBadge;
    private final TextView name;
    private final TextView meta;
    private final TextView current;
    private final TextView gps;
    private final ImageButton remove;
    private final ProgressBar progress;
    private final TextView status;
    private final TextView status2;
    private final Button detailsToggle;
    private final TextView details;
    private final TextView saved;
    private final View actions;
    private final Button save;
    private final Button share;

    private MediaItem item;
    private boolean thumbRequested;
    private boolean thumbVideo;

    ItemRow(LayoutInflater inflater, ViewGroup parent, final Callbacks cb) {
        view = inflater.inflate(R.layout.item_media, parent, false);
        divider = view.findViewById(R.id.item_divider);
        thumb = (ImageView) view.findViewById(R.id.item_thumb);
        videoBadge = (ImageView) view.findViewById(R.id.item_video_badge);
        name = (TextView) view.findViewById(R.id.item_name);
        meta = (TextView) view.findViewById(R.id.item_meta);
        current = (TextView) view.findViewById(R.id.item_current);
        gps = (TextView) view.findViewById(R.id.item_gps);
        remove = (ImageButton) view.findViewById(R.id.item_remove);
        progress = (ProgressBar) view.findViewById(R.id.item_progress);
        status = (TextView) view.findViewById(R.id.item_status);
        status2 = (TextView) view.findViewById(R.id.item_status2);
        detailsToggle = (Button) view.findViewById(R.id.item_details_toggle);
        details = (TextView) view.findViewById(R.id.item_details);
        saved = (TextView) view.findViewById(R.id.item_saved);
        actions = view.findViewById(R.id.item_actions);
        save = (Button) view.findViewById(R.id.item_save);
        share = (Button) view.findViewById(R.id.item_share);

        remove.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (item != null) cb.onRemove(item);
            }
        });
        detailsToggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (item != null) cb.onToggleDetails(item);
            }
        });
        save.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (item == null) return;
                if (item.savedUri != null) cb.onOpen(item);
                else cb.onSave(item);
            }
        });
        share.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (item != null) cb.onShare(item);
            }
        });
    }

    void bind(Context c, MediaItem it, boolean first, boolean busy, ThumbnailLoader thumbs, int thumbPx) {
        boolean newItem = item != it;
        item = it;
        boolean video = it.isVideo();
        // (re)request when the row is new or the media type became known (placeholder icon differs)
        if (newItem || !thumbRequested || video != thumbVideo) {
            thumbRequested = true;
            thumbVideo = video;
            thumbs.load(it, thumb, thumbPx);
        }
        UiUtil.setVisible(divider, !first);
        UiUtil.setVisible(videoBadge, video);
        UiUtil.setText(name, it.name);
        UiUtil.setText(meta, metaLine(c, it));
        UiUtil.setEnabled(remove, !busy);

        // current device
        if (!it.inspected) {
            UiUtil.setText(current, c.getString(R.string.item_reading));
            current.setTextColor(c.getColor(R.color.mk_text2));
        } else if (it.inspectError != null) {
            UiUtil.setText(current, c.getString(R.string.item_read_error, it.inspectError));
            current.setTextColor(c.getColor(R.color.mk_error));
        } else {
            String label = it.info == null ? null : it.info.deviceLabel();
            UiUtil.setText(current, label == null ? c.getString(R.string.item_no_device)
                    : c.getString(R.string.item_current, label));
            current.setTextColor(c.getColor(R.color.mk_text));
        }

        // GPS
        String g = it.info == null ? null : it.info.gpsStatus;
        if (g == null) {
            UiUtil.setVisible(gps, false);
        } else {
            UiUtil.setVisible(gps, true);
            boolean masked = g.startsWith("maske");
            UiUtil.setText(gps, c.getString(masked ? R.string.gps_masked : "var".equals(g) ? R.string.gps_yes : R.string.gps_no));
            gps.setTextColor(c.getColor(masked ? R.color.mk_warn : R.color.mk_text2));
        }

        // progress + status
        boolean processing = it.state == MediaItem.ST_PROCESSING;
        UiUtil.setVisible(progress, processing);
        if (processing) progress.setProgress(Math.round(it.progress * 1000));
        bindStatus(c, it);

        // details
        boolean canDetail = (it.state == MediaItem.ST_DONE && it.report != null)
                || (it.inspected && it.info != null && it.info.format != MediaFormat.UNKNOWN);
        UiUtil.setVisible(detailsToggle, canDetail);
        UiUtil.setText(detailsToggle, c.getString(it.expanded ? R.string.item_details_hide : R.string.item_details_show));
        boolean showDetails = canDetail && it.expanded;
        UiUtil.setVisible(details, showDetails);
        if (showDetails) UiUtil.setText(details, detailsText(c, it));

        // gallery state
        if (it.savedPath != null) {
            UiUtil.setVisible(saved, true);
            UiUtil.setText(saved, c.getString(R.string.item_saved, it.savedPath));
            saved.setTextColor(c.getColor(R.color.mk_success));
        } else if (it.saveError != null) {
            UiUtil.setVisible(saved, true);
            UiUtil.setText(saved, c.getString(R.string.item_save_error, it.saveError));
            saved.setTextColor(c.getColor(R.color.mk_error));
        } else {
            UiUtil.setVisible(saved, false);
        }

        boolean done = it.isDone();
        UiUtil.setVisible(actions, done);
        if (done) {
            if (it.savedUri != null) {
                UiUtil.setText(save, c.getString(R.string.item_open));
                UiUtil.setEnabled(save, true);
            } else if (it.saving) {
                UiUtil.setText(save, c.getString(R.string.item_saving));
                UiUtil.setEnabled(save, false);
            } else {
                UiUtil.setText(save, c.getString(R.string.item_save));
                UiUtil.setEnabled(save, !busy && it.savedPath == null);
            }
            UiUtil.setEnabled(share, true);
        }
    }

    private void bindStatus(Context c, MediaItem it) {
        String s = null;
        int color = R.color.mk_text;
        String s2 = null;
        switch (it.state) {
            case MediaItem.ST_QUEUED:
                s = c.getString(R.string.item_queued);
                color = R.color.mk_text2;
                break;
            case MediaItem.ST_PROCESSING:
                s = c.getString(R.string.item_processing, Math.round(it.progress * 100));
                color = R.color.mk_accent;
                break;
            case MediaItem.ST_DONE: {
                String label = it.outputInfo == null ? null : it.outputInfo.deviceLabel();
                s = c.getString(R.string.item_new, label != null ? label : "—");
                color = R.color.mk_success;
                StringBuilder sb = new StringBuilder();
                if (it.outputName != null) sb.append(it.outputName);
                if (it.report != null && it.report.outputLength > 0) {
                    sb.append(" · ").append(UiUtil.formatSize(it.report.outputLength));
                }
                if (it.outputInfo != null && it.outputInfo.hasIcc) {
                    sb.append(" · ").append(c.getString(R.string.item_icc, iccLabel(c, it.outputInfo)));
                }
                s2 = sb.toString();
                break;
            }
            case MediaItem.ST_ERROR:
                s = c.getString(R.string.item_error, it.error);
                color = R.color.mk_error;
                break;
            default:
                break;
        }
        UiUtil.setVisible(status, s != null);
        if (s != null) {
            UiUtil.setText(status, s);
            status.setTextColor(c.getColor(color));
        }
        UiUtil.setVisible(status2, s2 != null);
        if (s2 != null) UiUtil.setText(status2, s2);
    }

    private static String metaLine(Context c, MediaItem it) {
        String type = UiUtil.formatName(it.format());
        if (type == null) {
            type = c.getString(it.isVideo() ? R.string.type_video
                    : it.mime != null && it.mime.startsWith("image/") ? R.string.type_photo : R.string.type_file);
        }
        String size = UiUtil.formatSize(it.size);
        return size.length() == 0 ? type : type + " · " + size;
    }

    private static String iccLabel(Context c, MediaInfo info) {
        String m = info.iccManufacturer == null ? "" : info.iccManufacturer.trim();
        String md = info.iccModel == null ? "" : info.iccModel.trim();
        if (m.length() == 0 && md.length() == 0) return c.getString(R.string.item_icc_none);
        if (md.length() == 0) return m;
        return m + " / " + md;
    }

    private static CharSequence detailsText(Context c, MediaItem it) {
        StringBuilder sb = new StringBuilder();
        if (it.state == MediaItem.ST_DONE && it.report != null) {
            EditReport r = it.report;
            if (r.changes.isEmpty() && r.warnings.isEmpty()) {
                sb.append(c.getString(R.string.item_no_changes));
            }
            if (!r.changes.isEmpty()) {
                sb.append(c.getString(R.string.item_changes));
                for (String s : r.changes) sb.append("\n• ").append(s);
            }
            if (!r.warnings.isEmpty()) {
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(c.getString(R.string.item_warnings));
                for (String s : r.warnings) sb.append("\n• ").append(s);
            }
            if (it.outputInspectError != null) sb.append("\n\n").append(it.outputInspectError);
            if (it.info != null) {
                sb.append("\n\n— Önce —\n");
                appendInfo(c, sb, it.info);
            }
            if (it.outputInfo != null) {
                sb.append("\n\n— Sonra —\n");
                appendInfo(c, sb, it.outputInfo);
            }
            return sb.toString();
        }
        if (it.info != null) appendInfo(c, sb, it.info);
        return sb.toString();
    }

    private static void appendInfo(Context c, StringBuilder sb, MediaInfo i) {
        int start = sb.length();
        line(sb, "Make", i.make);
        line(sb, "Model", i.model);
        line(sb, "Software", i.software);
        line(sb, "HostComputer", i.hostComputer);
        line(sb, "LensMake", i.lensMake);
        line(sb, "LensModel", i.lensModel);
        line(sb, "Çekim tarihi", i.dateTimeOriginal == null ? null
                : i.dateTimeOriginal + (i.offsetTimeOriginal != null ? " " + i.offsetTimeOriginal : ""));
        if (i.hasIcc) {
            line(sb, "ICC cihazı", iccLabel(c, i));
            line(sb, "ICC açıklaması", i.iccDescription);
            if (i.iccManufacturerText != null || i.iccModelText != null) {
                line(sb, "ICC dmnd/dmdd", (i.iccManufacturerText == null ? "" : i.iccManufacturerText) + " / "
                        + (i.iccModelText == null ? "" : i.iccModelText));
            }
        }
        line(sb, "MakerNote", i.hasMakerNote ? "var" : null);
        line(sb, "XMP", i.hasXmp ? "var" : null);
        if (i.width > 0 && i.height > 0) line(sb, "Boyut", i.width + " × " + i.height);
        if (sb.length() == start) sb.append(c.getString(R.string.item_no_device));
        else if (sb.charAt(sb.length() - 1) == '\n') sb.setLength(sb.length() - 1);
    }

    private static void line(StringBuilder sb, String key, String value) {
        if (value == null || value.trim().length() == 0) return;
        sb.append(key).append(": ").append(value.trim()).append('\n');
    }
}
