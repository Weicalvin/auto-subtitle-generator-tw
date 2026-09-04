package com.serhat.autosub.ui.media;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.CompoundButton;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.C;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.MediaSource;
import androidx.media3.exoplayer.source.MediaSourceFactory;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.common.MediaItem.SubtitleConfiguration;
import androidx.media3.ui.PlayerView;

import com.google.android.material.button.MaterialButton;
import com.serhat.autosub.R;
import com.serhat.autosub.queue.QueueItem;
import com.serhat.autosub.subtitles.SubtitleGenerator;
import com.serhat.autosub.ui.main.MainViewModel;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

@UnstableApi
public class MediaPlayerFragment extends Fragment {
    private ExoPlayer player;
    private PlayerView playerView;
    private TextView fileNameView;
    private TextView captionView;
    private TextView statusView;
    private TextView subtitleListView;
    private MaterialButton liveButton;
    private CompoundButton translateToggle;
    private MainViewModel viewModel;
    private Uri mediaUri;
    private Uri subtitleUri;
    private QueueItem liveQueueItem;
    private boolean translationRequested;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable captionTicker = new Runnable() {
        @Override public void run() {
            updateCaption();
            handler.postDelayed(this, 120);
        }
    };

    private final ActivityResultLauncher<String[]> mediaPicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), result -> {
                if (result != null) {
                    mediaUri = result;
                    try { requireContext().getContentResolver().takePersistableUriPermission(result, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
                    fileNameView.setText(displayName(result));
                    preparePlayer();
                    statusView.setText("媒體已載入，可播放或開始離線即時聽譯。") ;
                }
            });
    private final ActivityResultLauncher<String[]> subtitlePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), result -> {
                if (result != null) {
                    subtitleUri = result;
                    try { requireContext().getContentResolver().takePersistableUriPermission(result, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) {}
                    if (mediaUri != null) preparePlayer();
                    statusView.setText("外部字幕已載入。") ;
                }
            });

    @Nullable @Override public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle state) {
        return inflater.inflate(R.layout.fragment_media_player, container, false);
    }

    @Override public void onViewCreated(@NonNull View view, @Nullable Bundle state) {
        super.onViewCreated(view, state);
        viewModel = new ViewModelProvider(requireActivity()).get(MainViewModel.class);
        playerView = view.findViewById(R.id.mediaPlayerView);
        fileNameView = view.findViewById(R.id.mediaFileNameTV);
        captionView = view.findViewById(R.id.liveCaptionTV);
        statusView = view.findViewById(R.id.liveStatusTV);
        subtitleListView = view.findViewById(R.id.liveSubtitleListTV);
        liveButton = view.findViewById(R.id.liveTranslateBT);
        translateToggle = view.findViewById(R.id.translateToggle);
        view.findViewById(R.id.openMediaBT).setOnClickListener(v -> mediaPicker.launch(new String[]{"video/*", "audio/*"}));
        view.findViewById(R.id.openSubtitleBT).setOnClickListener(v -> subtitlePicker.launch(new String[]{"text/*", "application/x-subrip", "text/vtt"}));
        liveButton.setOnClickListener(v -> startOfflineListening());
        translateToggle.setOnCheckedChangeListener((button, checked) -> {
            if (liveQueueItem != null) {
                updateLiveStatus();
                updateCaption();
            }
        });
        viewModel.getQueueItems().observe(getViewLifecycleOwner(), items -> {
            if (mediaUri == null) return;
            liveQueueItem = null;
            for (QueueItem item : items) {
                if (item.getVideoUri() != null && mediaUri.toString().equals(item.getVideoUri().toString())) {
                    liveQueueItem = item;
                    break;
                }
            }
            updateLiveStatus();
        });
    }

    private void preparePlayer() {
        if (player == null) {
            player = new ExoPlayer.Builder(requireContext()).build();
            playerView.setPlayer(player);
            player.addListener(new Player.Listener() {
                @Override public void onIsPlayingChanged(boolean playing) {
                    if (playing) handler.post(captionTicker); else handler.removeCallbacks(captionTicker);
                }
                @Override public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_READY && player.isPlaying()) handler.post(captionTicker);
                }
            });
        }
        MediaItem.Builder item = new MediaItem.Builder().setUri(mediaUri).setMediaMetadata(
                new MediaMetadata.Builder().setTitle(displayName(mediaUri)).build());
        if (subtitleUri != null) {
            String name = displayName(subtitleUri).toLowerCase(Locale.US);
            String mime = name.endsWith(".vtt") ? MimeTypes.TEXT_VTT : MimeTypes.APPLICATION_SUBRIP;
            item.setSubtitleConfigurations(java.util.Collections.singletonList(
                    new SubtitleConfiguration.Builder(subtitleUri).setMimeType(mime).setLanguage("zh-TW").setLabel("外部字幕").setSelectionFlags(C.SELECTION_FLAG_DEFAULT).build()));
        }
        player.setMediaItem(item.build());
        player.prepare();
    }

    private void startOfflineListening() {
        if (mediaUri == null) { Toast.makeText(requireContext(), "請先選擇媒體檔案", Toast.LENGTH_SHORT).show(); return; }
        liveButton.setEnabled(false);
        liveButton.setText("正在準備離線聽譯…");
        translationRequested = translateToggle.isChecked();
        statusView.setText(translationRequested
                ? "正在辨識並翻譯成繁體中文；完成的句子會依播放位置同步顯示。"
                : "正在辨識原文；目前不會翻譯字幕。") ;
        viewModel.setTranslateSubtitles(translationRequested);
        viewModel.setTranslationTargetLanguage("zh");
        List<Uri> uris = new ArrayList<>(); uris.add(mediaUri);
        viewModel.addVideosToQueue(uris, this::displayName, uri -> false);
    }

    private void updateLiveStatus() {
        if (liveQueueItem == null) return;
        if (liveQueueItem.getStatus() == QueueItem.Status.COMPLETED) {
            if (translationRequested && !liveQueueItem.hasTranslations()) {
                liveButton.setEnabled(false);
                liveButton.setText("正在翻譯成繁體中文…");
                statusView.setText("辨識完成，正在使用裝置端翻譯模型轉換成繁體中文……");
                viewModel.setTranslationTargetLanguage("zh");
                viewModel.translateQueueItem(liveQueueItem, new SubtitleGenerator.TranslationCallback() {
                    @Override public void onTranslated(List<SubtitleGenerator.SubtitleEntry> entries, String source, String target) {
                        liveQueueItem.setSubtitles(entries);
                        updateLiveStatus();
                    }
                    @Override public void onError(String error) {
                        liveButton.setEnabled(true);
                        statusView.setText("翻譯失敗：" + error + "；仍可查看原文字幕。") ;
                    }
                    @Override public void onProgressUpdate(int progress) { statusView.setText("正在翻譯成繁體中文…… " + Math.max(0, progress) + "%"); }
                });
                translationRequested = false;
                return;
            }
            liveButton.setEnabled(true); liveButton.setText("重新開始離線即時聽譯");
            statusView.setText(translateToggle.isChecked()
                    ? "聽譯完成；播放影片時會同步顯示原文與繁體中文。"
                    : "辨識完成；目前只顯示原文字幕。") ;
            StringBuilder all = new StringBuilder();
            for (SubtitleGenerator.SubtitleEntry e : liveQueueItem.getSubtitles()) {
                all.append(e.getText());
                if (translateToggle.isChecked() && e.hasTranslation()) all.append("\n").append(e.getTranslationText());
                all.append("\n\n");
            }
            subtitleListView.setText(all.toString());
        } else if (liveQueueItem.getMessage() != null && !liveQueueItem.getMessage().isEmpty()) {
            statusView.setText(liveQueueItem.getMessage());
        }
    }

    private void updateCaption() {
        if (player == null || liveQueueItem == null || liveQueueItem.getSubtitles() == null) return;
        long position = player.getCurrentPosition();
        StringBuilder text = new StringBuilder();
        for (SubtitleGenerator.SubtitleEntry entry : liveQueueItem.getSubtitles()) {
            long start = parseTime(entry.getStartTime()), end = parseTime(entry.getEndTime());
            if (position >= start && position <= end) {
                text.append(entry.getText());
                if (translateToggle.isChecked() && entry.hasTranslation()) text.append("\n").append(entry.getTranslationText());
                break;
            }
        }
        captionView.setText(text.toString());
        captionView.setVisibility(text.length() == 0 ? View.GONE : View.VISIBLE);
    }

    private long parseTime(String value) {
        try {
            String v = value.replace(',', '.'); String[] p = v.split(":");
            double seconds = Double.parseDouble(p[p.length - 1]);
            if (p.length > 1) seconds += Integer.parseInt(p[p.length - 2]) * 60;
            if (p.length > 2) seconds += Integer.parseInt(p[p.length - 3]) * 3600;
            return (long) (seconds * 1000);
        } catch (Exception e) { return 0; }
    }

    private String displayName(Uri uri) {
        Cursor cursor = requireContext().getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null);
        if (cursor != null) try { if (cursor.moveToFirst()) return cursor.getString(0); } finally { cursor.close(); }
        return uri.getLastPathSegment() == null ? "媒體檔案" : uri.getLastPathSegment();
    }

    private void updateLiveStatusUnused() { updateLiveStatus(); }
    @Override public void onStart() { super.onStart(); if (player != null) handler.post(captionTicker); }
    @Override public void onStop() { super.onStop(); handler.removeCallbacks(captionTicker); }
    @Override public void onDestroyView() { super.onDestroyView(); handler.removeCallbacks(captionTicker); if (player != null) { player.release(); player = null; } }
}
