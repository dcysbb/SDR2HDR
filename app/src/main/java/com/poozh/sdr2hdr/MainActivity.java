package com.poozh.sdr2hdr;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Gainmap;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.transition.AutoTransition;
import android.transition.TransitionManager;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.exifinterface.media.ExifInterface;

import com.google.android.material.card.MaterialCardView;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.slider.Slider;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQUEST_PICK_IMAGES = 1001;
    private static final long PREVIEW_DELAY_MS = 150L;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Views
    private ImageView sourcePreview;
    private ImageView resultPreview;
    private TextView statusText;
    private Slider strengthSlider;
    private TextView strengthValue;
    private MaterialSwitch protectSkinSwitch;
    private LinearProgressIndicator progressBar;
    private Button convertButton;
    private Button saveButton;
    private Button shareButton;
    private LinearLayout batchContainer;
    private HorizontalScrollView batchScroll;
    private TextView batchLabel;
    private TextView imageCountText;

    // State
    private final List<ImageItem> imageItems = new ArrayList<>();
    private int currentPreviewIndex = -1;
    private float hdrRatioMax = 3.0f;
    private boolean protectSkin = false;  // 默认关闭
    private HdrPreset currentHdrPreset = HdrPreset.BALANCED;
    private GainmapStats lastGainmapStats = GainmapStats.empty();
    private boolean isPreviewRunning = false;
    private final List<Button> presetButtons = new ArrayList<>();  // 存储预设按钮引用

    // Colors from theme
    private int colorPrimary;
    private int colorSurface;
    private int colorOnSurface;
    private int colorOnSurfaceVariant;
    private int colorOutline;
    private int colorSurfaceVariant;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        resolveThemeColors();
        configureHdrWindow();
        setContentView(createContentView());
        updateStatus(getString(R.string.status_start));
        updateButtonState();
    }

    private void resolveThemeColors() {
        TypedValue tv = new TypedValue();
        getTheme().resolveAttribute(com.google.android.material.R.attr.colorPrimary, tv, true);
        colorPrimary = tv.data;
        getTheme().resolveAttribute(com.google.android.material.R.attr.colorSurface, tv, true);
        colorSurface = tv.data;
        getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnSurface, tv, true);
        colorOnSurface = tv.data;
        getTheme().resolveAttribute(com.google.android.material.R.attr.colorOnSurfaceVariant, tv, true);
        colorOnSurfaceVariant = tv.data;
        getTheme().resolveAttribute(com.google.android.material.R.attr.colorOutline, tv, true);
        colorOutline = tv.data;
        getTheme().resolveAttribute(com.google.android.material.R.attr.colorSurfaceVariant, tv, true);
        colorSurfaceVariant = tv.data;
    }

    @Override
    protected void onDestroy() {
        mainHandler.removeCallbacksAndMessages(null);
        executor.shutdownNow();
        super.onDestroy();
    }

    @Override
    @Deprecated
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_PICK_IMAGES || resultCode != RESULT_OK || data == null) return;

        if (Build.VERSION.SDK_INT >= 33 && data.getClipData() != null) {
            int count = data.getClipData().getItemCount();
            for (int i = 0; i < count; i++) {
                Uri uri = data.getClipData().getItemAt(i).getUri();
                addImageItem(uri);
            }
        } else {
            Uri uri = data.getData();
            if (uri != null) {
                addImageItem(uri);
            }
        }

        updateBatchUI();
        updateButtonState();
    }

    private void addImageItem(Uri uri) {
        ImageItem item = new ImageItem(uri);
        imageItems.add(item);
        loadImageIntoItem(item);
    }

    private View createContentView() {
        ScrollView scrollView = new ScrollView(this);
        scrollView.setFillViewport(true);
        scrollView.setBackgroundColor(colorSurface);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(20), dp(20), dp(32));
        scrollView.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // ── Title Section ──
        TextView title = new TextView(this);
        title.setText(R.string.app_name);
        title.setTextColor(colorOnSurface);
        title.setTextSize(32);
        title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        root.addView(title, matchWrap());

        TextView subtitle = new TextView(this);
        subtitle.setText(R.string.subtitle);
        subtitle.setTextColor(colorOnSurfaceVariant);
        subtitle.setTextSize(14);
        subtitle.setPadding(0, dp(4), 0, dp(20));
        root.addView(subtitle, matchWrap());

        // ── Pick Button (prominent) ──
        MaterialCardView pickCard = new MaterialCardView(this);
        pickCard.setCardElevation(dp(0));
        pickCard.setRadius(dp(16));
        pickCard.setCardBackgroundColor(colorPrimary);
        pickCard.setUseCompatPadding(false);
        pickCard.setClickable(true);
        pickCard.setFocusable(true);
        pickCard.setOnClickListener(v -> pickImages());
        LinearLayout pickInner = new LinearLayout(this);
        pickInner.setOrientation(LinearLayout.HORIZONTAL);
        pickInner.setGravity(Gravity.CENTER);
        pickInner.setPadding(dp(24), dp(18), dp(24), dp(18));

        TextView pickIcon = new TextView(this);
        pickIcon.setText("⊕");
        pickIcon.setTextSize(22);
        pickIcon.setTextColor(Color.WHITE);
        pickInner.addView(pickIcon);

        TextView pickText = new TextView(this);
        pickText.setText(R.string.pick_images);
        pickText.setTextSize(16);
        pickText.setTextColor(Color.WHITE);
        pickText.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        pickText.setPadding(dp(12), 0, 0, 0);
        pickInner.addView(pickText);

        pickCard.addView(pickInner, new MaterialCardView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(pickCard, matchWrapWithMargins(0, 0, 0, 8));

        // ── Image Count + Clear ──
        LinearLayout countRow = new LinearLayout(this);
        countRow.setOrientation(LinearLayout.HORIZONTAL);
        countRow.setGravity(Gravity.CENTER_VERTICAL);
        countRow.setPadding(0, dp(8), 0, dp(4));

        imageCountText = new TextView(this);
        imageCountText.setTextColor(colorOnSurfaceVariant);
        imageCountText.setTextSize(13);
        countRow.addView(imageCountText, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView clearBtn = new TextView(this);
        clearBtn.setText(R.string.clear_all);
        clearBtn.setTextColor(colorPrimary);
        clearBtn.setTextSize(13);
        clearBtn.setPadding(dp(12), dp(4), dp(4), dp(4));
        clearBtn.setOnClickListener(v -> {
            TransitionManager.beginDelayedTransition(root, new AutoTransition());
            imageItems.clear();
            currentPreviewIndex = -1;
            sourcePreview.setImageDrawable(null);
            resultPreview.setImageDrawable(null);
            updateBatchUI();
            updateButtonState();
            updateStatus(getString(R.string.status_start));
        });
        countRow.addView(clearBtn, wrapWrap());
        root.addView(countRow, matchWrap());

        // ── Batch Thumbnail Strip ──
        batchLabel = new TextView(this);
        batchLabel.setText(R.string.batch_results);
        batchLabel.setTextColor(colorOnSurface);
        batchLabel.setTextSize(16);
        batchLabel.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        batchLabel.setPadding(0, dp(8), 0, dp(4));
        batchLabel.setVisibility(View.GONE);
        root.addView(batchLabel, matchWrap());

        batchScroll = new HorizontalScrollView(this);
        batchScroll.setHorizontalScrollBarEnabled(false);
        batchScroll.setVisibility(View.GONE);

        batchContainer = new LinearLayout(this);
        batchContainer.setOrientation(LinearLayout.HORIZONTAL);
        batchContainer.setPadding(0, dp(4), 0, dp(8));
        batchScroll.addView(batchContainer);
        root.addView(batchScroll, matchWrap());

        // ── Source Preview ──
        TextView sourceLabel = sectionLabel(getString(R.string.source_image));
        root.addView(sourceLabel, matchWrap());

        MaterialCardView sourceCard = new MaterialCardView(this);
        sourceCard.setCardElevation(dp(1));
        sourceCard.setRadius(dp(16));
        sourceCard.setCardBackgroundColor(Color.rgb(232, 236, 234));
        sourceCard.setUseCompatPadding(false);
        sourcePreview = new ImageView(this);
        sourcePreview.setAdjustViewBounds(true);
        sourcePreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        sourcePreview.setPadding(dp(2), dp(2), dp(2), dp(2));
        sourceCard.addView(sourcePreview, new MaterialCardView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(280)));
        root.addView(sourceCard, matchWrapWithMargins(0, 0, 0, 16));

        // ── Settings Card ──
        MaterialCardView settingsCard = new MaterialCardView(this);
        settingsCard.setCardElevation(dp(1));
        settingsCard.setRadius(dp(16));
        settingsCard.setCardBackgroundColor(colorSurface);
        settingsCard.setUseCompatPadding(false);

        LinearLayout settingsInner = new LinearLayout(this);
        settingsInner.setOrientation(LinearLayout.VERTICAL);
        settingsInner.setPadding(dp(20), dp(16), dp(20), dp(20));

        // HDR Strength
        LinearLayout strengthRow = new LinearLayout(this);
        strengthRow.setOrientation(LinearLayout.HORIZONTAL);
        strengthRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView strengthLabel = new TextView(this);
        strengthLabel.setText(R.string.hdr_strength);
        strengthLabel.setTextColor(colorOnSurface);
        strengthLabel.setTextSize(15);
        strengthLabel.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        strengthRow.addView(strengthLabel, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        strengthValue = new TextView(this);
        strengthValue.setTextColor(colorPrimary);
        strengthValue.setTextSize(15);
        strengthValue.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        strengthRow.addView(strengthValue, wrapWrap());
        settingsInner.addView(strengthRow, matchWrap());

        strengthSlider = new Slider(this);
        strengthSlider.setValueFrom(0f);
        strengthSlider.setValueTo(100f);
        strengthSlider.setValue(55f);
        strengthSlider.setStepSize(1f);
        strengthSlider.setPadding(0, dp(8), 0, dp(4));
        strengthSlider.addOnChangeListener((slider, value, fromUser) -> {
            hdrRatioMax = 1.4f + (value / 100f) * 3.6f;
            updateStrengthText();
            if (fromUser) {
                scheduleLivePreview();
            }
        });
        settingsInner.addView(strengthSlider, matchWrap());

        // Protect Skin Switch
        protectSkinSwitch = new MaterialSwitch(this);
        protectSkinSwitch.setText(R.string.protect_skin);
        protectSkinSwitch.setTextColor(colorOnSurface);
        protectSkinSwitch.setTextSize(14);
        protectSkinSwitch.setChecked(false);  // 默认关闭
        protectSkinSwitch.setOnCheckedChangeListener((btn, checked) -> {
            protectSkin = checked;
            scheduleLivePreview();
        });
        protectSkinSwitch.setPadding(0, dp(8), 0, 0);
        settingsInner.addView(protectSkinSwitch, matchWrap());

        // HDR Preset Selector
        TextView presetLabel = new TextView(this);
        presetLabel.setText("HDR 算法预设");
        presetLabel.setTextColor(colorOnSurface);
        presetLabel.setTextSize(15);
        presetLabel.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        presetLabel.setPadding(0, dp(8), 0, dp(4));
        settingsInner.addView(presetLabel, matchWrap());

        LinearLayout presetButtonsContainer = new LinearLayout(this);
        presetButtonsContainer.setOrientation(LinearLayout.HORIZONTAL);
        presetButtonsContainer.setGravity(Gravity.CENTER_VERTICAL);

        presetButtons.clear();  // 清空之前的按钮引用

        for (HdrPreset preset : HdrPreset.values()) {
            Button presetBtn = new Button(this, null, android.R.attr.borderlessButtonStyle);
            presetBtn.setText(preset.getDisplayName());
            presetBtn.setTextSize(13);
            presetBtn.setTextColor(currentHdrPreset == preset ? Color.WHITE : colorOnSurface);
            presetBtn.setBackground(createRippleBg(
                currentHdrPreset == preset ? colorPrimary : colorSurfaceVariant, dp(8)));
            presetBtn.setPadding(dp(12), dp(8), dp(12), dp(8));
            presetBtn.setMinHeight(0);
            presetBtn.setMinimumHeight(0);
            presetBtn.setTag(preset);
            presetBtn.setOnClickListener(v -> {
                currentHdrPreset = (HdrPreset) v.getTag();
                updatePresetButtons();
                scheduleLivePreview();
            });
            LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            btnLp.setMargins(0, 0, dp(8), 0);
            presetButtonsContainer.addView(presetBtn, btnLp);
            presetButtons.add(presetBtn);  // 存储按钮引用
        }

        // 使用 HorizontalScrollView 包裹按钮，确保在小屏幕上也能滚动查看
        HorizontalScrollView presetScroll = new HorizontalScrollView(this);
        presetScroll.setHorizontalScrollBarEnabled(false);
        presetScroll.addView(presetButtonsContainer);
        settingsInner.addView(presetScroll, matchWrap());

        settingsCard.addView(settingsInner, new MaterialCardView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(settingsCard, matchWrapWithMargins(0, 0, 0, 16));

        // ── Action Buttons ──
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);

        convertButton = createMaterialButton(getString(R.string.convert_hdr), colorPrimary, Color.WHITE, v -> convertAll());
        actionRow.addView(convertButton, weightWrap(1f));

        saveButton = createMaterialButton(getString(R.string.save_to_gallery), colorSurfaceVariant, colorOnSurface, v -> saveAll());
        actionRow.addView(saveButton, weightWrap(1f));

        shareButton = createMaterialButton(getString(R.string.share), colorSurfaceVariant, colorOnSurface, v -> shareResult());
        actionRow.addView(shareButton, weightWrap(1f));

        root.addView(actionRow, matchWrapWithMargins(0, 4, 0, 16));

        // ── Progress ──
        progressBar = new LinearProgressIndicator(this);
        progressBar.setVisibility(View.GONE);
        progressBar.setIndicatorColor(colorPrimary);
        root.addView(progressBar, matchWrap());

        // ── Status ──
        statusText = new TextView(this);
        statusText.setTextColor(colorOnSurfaceVariant);
        statusText.setTextSize(14);
        statusText.setLineSpacing(dp(2), 1f);
        statusText.setPadding(0, dp(8), 0, dp(12));
        root.addView(statusText, matchWrap());

        // ── Result Preview ──
        TextView resultLabel = sectionLabel(getString(R.string.hdr_result));
        root.addView(resultLabel, matchWrap());

        MaterialCardView resultCard = new MaterialCardView(this);
        resultCard.setCardElevation(dp(1));
        resultCard.setRadius(dp(16));
        resultCard.setCardBackgroundColor(Color.rgb(232, 236, 234));
        resultCard.setUseCompatPadding(false);
        resultPreview = new ImageView(this);
        resultPreview.setAdjustViewBounds(true);
        resultPreview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        resultPreview.setPadding(dp(2), dp(2), dp(2), dp(2));
        resultCard.addView(resultPreview, new MaterialCardView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(280)));
        root.addView(resultCard, matchWrap());

        updateStrengthText();
        return scrollView;
    }

    private Button createMaterialButton(String text, int bgColor, int textColor, View.OnClickListener listener) {
        Button btn = new Button(this, null, android.R.attr.borderlessButtonStyle);
        btn.setText(text);
        btn.setTextColor(textColor);
        btn.setTextSize(14);
        btn.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        btn.setAllCaps(false);
        btn.setBackground(createRippleBg(bgColor, dp(12)));
        btn.setPadding(dp(16), dp(12), dp(16), dp(12));
        btn.setMinimumHeight(dp(44));
        btn.setOnClickListener(listener);
        return btn;
    }

    private android.graphics.drawable.Drawable createRippleBg(int color, int radius) {
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(color);
        bg.setCornerRadius(radius);
        return new android.graphics.drawable.RippleDrawable(
                android.content.res.ColorStateList.valueOf(colorPrimary),
                bg, null);
    }

    // ── Image Loading ──

    private void loadImageIntoItem(ImageItem item) {
        executor.execute(() -> {
            try {
                // 加载两个版本：缩略图用于显示，原始分辨率用于 HDR 处理
                Bitmap thumbnail = decodeBitmap(item.uri, true);
                Bitmap fullRes = decodeBitmap(item.uri, false);

                mainHandler.post(() -> {
                    item.sourceBitmap = thumbnail;   // 缩略图
                    item.fullResSource = fullRes;    // 原始分辨率
                    item.state = ImageState.LOADED;
                    if (currentPreviewIndex < 0) {
                        currentPreviewIndex = imageItems.indexOf(item);
                        showPreviewForIndex(currentPreviewIndex);
                    }
                    updateBatchUI();
                    updateStatus(getString(R.string.status_loaded,
                            fullRes.getWidth(), fullRes.getHeight()));
                    updateButtonState();
                    // Auto-preview after loading
                    scheduleLivePreview();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    item.state = ImageState.FAILED;
                    item.error = readableError(e);
                    updateBatchUI();
                    updateButtonState();
                });
            }
        });
    }

    private void showPreviewForIndex(int index) {
        if (index < 0 || index >= imageItems.size()) return;
        ImageItem item = imageItems.get(index);
        sourcePreview.setImageBitmap(item.sourceBitmap);
        resultPreview.setImageBitmap(item.resultBitmap);
    }

    // ── Live Preview ──

    /**
     * Debounced live preview: converts the currently selected image after a short delay.
     * Each call cancels the previous scheduled preview.
     */
    private void scheduleLivePreview() {
        mainHandler.removeCallbacks(livePreviewRunnable);
        mainHandler.postDelayed(livePreviewRunnable, PREVIEW_DELAY_MS);
    }

    private final Runnable livePreviewRunnable = () -> {
        ImageItem item = getSelectedItem();
        if (item == null || item.fullResSource == null) return;
        if (isPreviewRunning) return;

        isPreviewRunning = true;
        updateStatus(getString(R.string.status_previewing));

        float ratio = hdrRatioMax;
        boolean protect = protectSkin;
        Bitmap fullResSource = item.fullResSource;
        Bitmap thumbnailSource = item.sourceBitmap;

        executor.execute(() -> {
            try {
                // 使用原始分辨率进行 HDR 处理
                Bitmap fullResResult = createUltraHdrBitmap(fullResSource, ratio, protect);

                // 缩放到缩略图大小用于显示
                Bitmap thumbnailResult = Bitmap.createScaledBitmap(
                    fullResResult,
                    thumbnailSource.getWidth(),
                    thumbnailSource.getHeight(),
                    true
                );

                mainHandler.post(() -> {
                    item.resultBitmap = thumbnailResult;      // 缩略图用于显示
                    item.fullResResult = fullResResult;      // 原始分辨率用于保存
                    item.state = ImageState.CONVERTED;
                    item.stats = lastGainmapStats;
                    // Only update preview if still looking at this item
                    if (currentPreviewIndex >= 0 && currentPreviewIndex < imageItems.size()
                            && imageItems.get(currentPreviewIndex) == item) {
                        resultPreview.setImageBitmap(thumbnailResult);
                    }
                    isPreviewRunning = false;
                    configureHdrWindow();
                    updateBatchUI();
                    updateButtonState();
                });
            } catch (Exception e) {
                mainHandler.post(() -> {
                    isPreviewRunning = false;
                    updateStatus(getString(R.string.status_convert_failed, readableError(e)));
                });
            }
        });
    };

    // ── Pick Images ──

    @SuppressLint("NewApi")
    private void pickImages() {
        Intent intent;
        if (Build.VERSION.SDK_INT >= 33) {
            intent = new Intent(MediaStore.ACTION_PICK_IMAGES);
            intent.setType("image/*");
            intent.putExtra("android.provider.extra.PICK_IMAGES_MAX", 100);
        } else {
            intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            intent.setType("image/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
            intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        }
        startActivityForResult(intent, REQUEST_PICK_IMAGES);
    }

    // ── Delete Single Photo ──

    private void deleteImageItem(int index) {
        if (index < 0 || index >= imageItems.size()) return;

        imageItems.remove(index);

        if (imageItems.isEmpty()) {
            currentPreviewIndex = -1;
            sourcePreview.setImageDrawable(null);
            resultPreview.setImageDrawable(null);
            updateStatus(getString(R.string.status_start));
        } else if (currentPreviewIndex >= imageItems.size()) {
            currentPreviewIndex = imageItems.size() - 1;
            showPreviewForIndex(currentPreviewIndex);
        } else if (currentPreviewIndex == index) {
            // Deleted the current preview item, show the one now at this index
            if (currentPreviewIndex >= imageItems.size()) {
                currentPreviewIndex = imageItems.size() - 1;
            }
            showPreviewForIndex(currentPreviewIndex);
            scheduleLivePreview();
        }

        updateBatchUI();
        updateButtonState();
    }

    // ── Batch Conversion ──

    private void convertAll() {
        List<ImageItem> toConvert = new ArrayList<>();
        for (ImageItem item : imageItems) {
            if (item.state == ImageState.LOADED) {
                toConvert.add(item);
            }
        }
        if (toConvert.isEmpty()) {
            updateStatus(getString(R.string.status_pick_first));
            return;
        }

        setBusy(true, getString(R.string.status_generating));
        float ratio = hdrRatioMax;
        boolean protect = protectSkin;

        executor.execute(() -> {
            int success = 0;
            int fail = 0;
            for (int i = 0; i < toConvert.size(); i++) {
                ImageItem item = toConvert.get(i);
                int idx = i + 1;
                int total = toConvert.size();
                mainHandler.post(() ->
                        updateStatus(getString(R.string.status_batch_progress, idx, total)));

                try {
                    // 使用原始分辨率进行 HDR 处理
                    Bitmap fullResResult = createUltraHdrBitmap(item.fullResSource, ratio, protect);

                    // 缩放到缩略图大小用于显示
                    Bitmap thumbnailResult = Bitmap.createScaledBitmap(
                        fullResResult,
                        item.sourceBitmap.getWidth(),
                        item.sourceBitmap.getHeight(),
                        true
                    );

                    item.resultBitmap = thumbnailResult;
                    item.fullResResult = fullResResult;
                    item.state = ImageState.CONVERTED;
                    item.stats = lastGainmapStats;
                    success++;
                } catch (Exception e) {
                    item.state = ImageState.FAILED;
                    item.error = readableError(e);
                    fail++;
                }

                mainHandler.post(() -> {
                    updateBatchUI();
                    if (currentPreviewIndex >= 0 && currentPreviewIndex < imageItems.size()) {
                        showPreviewForIndex(currentPreviewIndex);
                    }
                });
            }

            int s = success, f = fail;
            mainHandler.post(() -> {
                setBusy(false, null);
                updateStatus(getString(R.string.status_batch_complete, s, f));
                updateButtonState();
                configureHdrWindow();
            });
        });
    }

    // ── Batch Save ──

    private void saveAll() {
        List<ImageItem> toSave = new ArrayList<>();
        for (ImageItem item : imageItems) {
            if (item.state == ImageState.CONVERTED && item.fullResResult != null) {
                toSave.add(item);
            }
        }
        if (toSave.isEmpty()) {
            updateStatus(getString(R.string.status_generate_first));
            return;
        }

        setBusy(true, getString(R.string.status_saving));
        executor.execute(() -> {
            for (int i = 0; i < toSave.size(); i++) {
                ImageItem item = toSave.get(i);
                int idx = i + 1;
                int total = toSave.size();
                mainHandler.post(() ->
                        updateStatus(getString(R.string.status_batch_saving, idx, total)));
                try {
                    // 保存原始分辨率的 HDR 结果
                    Uri uri = saveBitmapToGallery(item.fullResResult);
                    item.savedUri = uri;
                    item.state = ImageState.SAVED;
                } catch (Exception e) {
                    item.state = ImageState.FAILED;
                    item.error = readableError(e);
                }
            }
            mainHandler.post(() -> {
                setBusy(false, null);
                updateStatus(getString(R.string.status_saved_hdr));
                updateBatchUI();
                updateButtonState();
            });
        });
    }

    private void shareResult() {
        ImageItem item = getSelectedItem();
        if (item == null || item.fullResResult == null) {
            updateStatus(getString(R.string.status_generate_first));
            return;
        }

        if (item.savedUri != null) {
            launchShare(item.savedUri);
            return;
        }

        executor.execute(() -> {
            try {
                // 保存并分享原始分辨率的 HDR 结果
                Uri uri = saveBitmapToGallery(item.fullResResult);
                item.savedUri = uri;
                item.state = ImageState.SAVED;
                mainHandler.post(() -> {
                    updateButtonState();
                    launchShare(uri);
                });
            } catch (Exception e) {
                mainHandler.post(() ->
                        updateStatus(getString(R.string.status_save_failed, readableError(e))));
            }
        });
    }

    @Nullable
    private ImageItem getSelectedItem() {
        if (currentPreviewIndex >= 0 && currentPreviewIndex < imageItems.size()) {
            return imageItems.get(currentPreviewIndex);
        }
        return null;
    }

    // ── Batch UI ──

    private void updateBatchUI() {
        TransitionManager.beginDelayedTransition((ViewGroup) batchScroll.getParent(), new AutoTransition());

        boolean hasMultiple = imageItems.size() > 0;
        batchLabel.setVisibility(hasMultiple ? View.VISIBLE : View.GONE);
        batchScroll.setVisibility(hasMultiple ? View.VISIBLE : View.GONE);
        imageCountText.setText(imageItems.isEmpty() ? "" :
                getString(R.string.image_count, imageItems.size()));

        batchContainer.removeAllViews();
        for (int i = 0; i < imageItems.size(); i++) {
            final int index = i;
            ImageItem item = imageItems.get(i);

            MaterialCardView card = new MaterialCardView(this);
            card.setRadius(dp(10));
            card.setCardElevation(dp(i == currentPreviewIndex ? 3 : 1));
            card.setCardBackgroundColor(i == currentPreviewIndex ? colorPrimary : colorSurfaceVariant);
            card.setUseCompatPadding(false);
            card.setOnClickListener(v -> {
                currentPreviewIndex = index;
                showPreviewForIndex(index);
                updateBatchUI();
                scheduleLivePreview();
            });
            // Long press to delete
            card.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle(R.string.delete_photo)
                        .setMessage(item.uri != null ? item.uri.getLastPathSegment() : "测试图")
                        .setPositiveButton(R.string.delete_photo, (dialog, which) -> {
                            TransitionManager.beginDelayedTransition(
                                    (ViewGroup) batchScroll.getParent(), new AutoTransition());
                            deleteImageItem(index);
                            updateStatus(getString(R.string.status_deleted));
                        })
                        .setNegativeButton(android.R.string.cancel, null)
                        .show();
                return true;
            });

            FrameLayout thumbWrap = new FrameLayout(this);
            ImageView thumb = new ImageView(this);
            thumb.setScaleType(ImageView.ScaleType.CENTER_CROP);
            if (item.sourceBitmap != null) {
                thumb.setImageBitmap(item.sourceBitmap);
            }

            // State indicator
            TextView indicator = new TextView(this);
            indicator.setTextSize(10);
            indicator.setTextColor(Color.WHITE);
            indicator.setGravity(Gravity.CENTER);
            switch (item.state) {
                case CONVERTED:
                case SAVED:
                    indicator.setBackgroundColor(Color.argb(180, 0, 107, 94));
                    indicator.setText("✓");
                    break;
                case FAILED:
                    indicator.setBackgroundColor(Color.argb(180, 186, 26, 26));
                    indicator.setText("✗");
                    break;
                case LOADING:
                    indicator.setBackgroundColor(Color.argb(128, 128, 128, 128));
                    indicator.setText("…");
                    break;
                default:
                    indicator.setBackgroundColor(Color.TRANSPARENT);
                    break;
            }

            FrameLayout.LayoutParams thumbLp = new FrameLayout.LayoutParams(dp(100), dp(100));
            thumbWrap.addView(thumb, thumbLp);
            FrameLayout.LayoutParams indLp = new FrameLayout.LayoutParams(dp(100), dp(22), Gravity.BOTTOM);
            thumbWrap.addView(indicator, indLp);

            card.addView(thumbWrap, new MaterialCardView.LayoutParams(dp(104), dp(104)));
            LinearLayout.LayoutParams cardLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            cardLp.setMargins(0, 0, dp(8), 0);
            batchContainer.addView(card, cardLp);
        }

        // Update button text based on count
        long loadedCount = 0;
        for (ImageItem item : imageItems) {
            if (item.state == ImageState.LOADED) loadedCount++;
        }
        if (loadedCount > 1) {
            convertButton.setText(getString(R.string.convert_all, (int) loadedCount));
        } else {
            convertButton.setText(getString(R.string.convert_hdr));
        }

        long savedCount = 0;
        for (ImageItem item : imageItems) {
            if (item.state == ImageState.CONVERTED || item.state == ImageState.SAVED) savedCount++;
        }
        if (savedCount > 1) {
            saveButton.setText(getString(R.string.save_all));
        } else {
            saveButton.setText(getString(R.string.save_to_gallery));
        }
    }

    // ── Bitmap Decoding (full resolution) ──

    private Bitmap decodeBitmap(Uri uri) throws IOException {
        return decodeBitmap(uri, true);
    }

    private Bitmap decodeBitmap(Uri uri, boolean forDisplay) throws IOException {
        // First pass: check image dimensions
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            BitmapFactory.decodeStream(input, null, bounds);
        }

        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            throw new IOException(getString(R.string.error_bad_size));
        }

        // Calculate sample size based on image dimensions and use case
        int maxDimension = Math.max(bounds.outWidth, bounds.outHeight);
        long totalPixels = (long) bounds.outWidth * bounds.outHeight;
        int sampleSize = 1;

        if (forDisplay) {
            // For display: limit to safe GPU texture size (~15M pixels max)
            if (totalPixels > 15_000_000L || maxDimension > 6000) {
                sampleSize = 2;
            }
            if (totalPixels > 30_000_000L || maxDimension > 8000) {
                sampleSize = 4;
            }
            if (totalPixels > 60_000_000L || maxDimension > 12000) {
                sampleSize = 8;
            }
        } else {
            // For HDR processing: use original resolution
            sampleSize = 1;
        }

        // Second pass: decode with appropriate settings
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inPreferredConfig = forDisplay ? Bitmap.Config.RGB_565 : Bitmap.Config.ARGB_8888;
        options.inSampleSize = sampleSize;
        options.inScaled = false;

        Bitmap bitmap;
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            bitmap = BitmapFactory.decodeStream(input, null, options);
        }

        if (bitmap == null) {
            throw new IOException(getString(R.string.error_decode_failed));
        }

        // Handle rotation
        int rotation = readExifRotation(uri);
        if (rotation != 0) {
            Matrix matrix = new Matrix();
            matrix.postRotate(rotation);
            try {
                Bitmap rotated = Bitmap.createBitmap(bitmap, 0, 0,
                        bitmap.getWidth(), bitmap.getHeight(), matrix, true);
                if (rotated != bitmap) {
                    bitmap.recycle();
                }
                bitmap = rotated;
            } catch (OutOfMemoryError e) {
                throw new IOException("Not enough memory to rotate image");
            }
        }

        // Convert to ARGB_8888 for Ultra HDR processing
        if (forDisplay) {
            try {
                Bitmap argbBitmap = bitmap.copy(Bitmap.Config.ARGB_8888, false);
                if (argbBitmap != bitmap) {
                    bitmap.recycle();
                }
                bitmap = argbBitmap;
            } catch (OutOfMemoryError e) {
                throw new IOException("Not enough memory to convert image format");
            }
        }

        // Make opaque (add white background for transparency)
        try {
            return makeOpaque(bitmap);
        } catch (OutOfMemoryError e) {
            throw new IOException("Not enough memory to process image");
        }
    }

    private int readExifRotation(Uri uri) {
        try (InputStream input = getContentResolver().openInputStream(uri)) {
            if (input == null) return 0;
            ExifInterface exif = new ExifInterface(input);
            int orientation = exif.getAttributeInt(
                    ExifInterface.TAG_ORIENTATION,
                    ExifInterface.ORIENTATION_NORMAL);
            if (orientation == ExifInterface.ORIENTATION_ROTATE_90) return 90;
            if (orientation == ExifInterface.ORIENTATION_ROTATE_180) return 180;
            if (orientation == ExifInterface.ORIENTATION_ROTATE_270) return 270;
        } catch (IOException ignored) {
        }
        return 0;
    }

    private Bitmap makeOpaque(Bitmap bitmap) {
        Bitmap opaque = Bitmap.createBitmap(bitmap.getWidth(), bitmap.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(opaque);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(bitmap, 0f, 0f, null);
        if (opaque != bitmap) {
            bitmap.recycle();
        }
        return opaque;
    }

    // ── Ultra HDR Conversion ──

    @SuppressLint("NewApi")
    private Bitmap createUltraHdrBitmap(Bitmap source, float ratioMax, boolean protect) {
        Bitmap base = Bitmap.createBitmap(source.getWidth(), source.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(base);
        canvas.drawColor(Color.WHITE);
        canvas.drawBitmap(source, 0f, 0f, null);

        if (Build.VERSION.SDK_INT < 34) {
            return base;
        }

        Bitmap gainmapContents = createGainmapContents(base, ratioMax, protect);
        Gainmap gainmap = new Gainmap(gainmapContents);
        gainmap.setRatioMin(1.0f, 1.0f, 1.0f);
        gainmap.setRatioMax(ratioMax, ratioMax, ratioMax);
        gainmap.setGamma(1.0f, 1.0f, 1.0f);
        gainmap.setEpsilonSdr(1.0f / 64.0f, 1.0f / 64.0f, 1.0f / 64.0f);
        gainmap.setEpsilonHdr(1.0f / 64.0f, 1.0f / 64.0f, 1.0f / 64.0f);
        gainmap.setMinDisplayRatioForHdrTransition(1.0f);
        gainmap.setDisplayRatioForFullHdr(ratioMax);
        if (Build.VERSION.SDK_INT >= 36) {
            gainmap.setGainmapDirection(Gainmap.GAINMAP_DIRECTION_SDR_TO_HDR);
        }
        base.setGainmap(gainmap);
        return base;
    }

    private Bitmap createGainmapContents(Bitmap base, float ratioMax, boolean protect) {
        int gainWidth;
        int gainHeight;
        if (base.getWidth() >= base.getHeight()) {
            gainWidth = Math.min(1024, Math.max(1, base.getWidth() / 4));
            gainHeight = Math.max(1, Math.round(gainWidth * (base.getHeight() / (float) base.getWidth())));
        } else {
            gainHeight = Math.min(1024, Math.max(1, base.getHeight() / 4));
            gainWidth = Math.max(1, Math.round(gainHeight * (base.getWidth() / (float) base.getHeight())));
        }

        Bitmap small = Bitmap.createScaledBitmap(base, gainWidth, gainHeight, true);
        int count = gainWidth * gainHeight;
        int[] pixels = new int[count];
        small.getPixels(pixels, 0, gainWidth, 0, 0, gainWidth, gainHeight);
        small.recycle();

        float[] luma = new float[count];
        float[] chroma = new float[count];
        float[] signal = new float[count];

        for (int i = 0; i < count; i++) {
            int color = pixels[i];
            float r = Color.red(color) / 255f;
            float g = Color.green(color) / 255f;
            float b = Color.blue(color) / 255f;
            float max = Math.max(r, Math.max(g, b));
            float min = Math.min(r, Math.min(g, b));
            luma[i] = 0.2126f * r + 0.7152f * g + 0.0722f * b;
            chroma[i] = max - min;
        }

        for (int y = 0; y < gainHeight; y++) {
            for (int x = 0; x < gainWidth; x++) {
                int index = y * gainWidth + x;
                float localContrast = localContrast(luma, gainWidth, gainHeight, x, y);
                int color = pixels[index];
                float r = Color.red(color) / 255f;
                float g = Color.green(color) / 255f;
                float b = Color.blue(color) / 255f;

                // 根据预设选择不同的算法参数
                float highlight, contrastWeight, colorWeight, value;
                float shadowFactor = 0.15f;
                float skinFactor = 0.10f;
                float highlightMin = 0.70f;
                float colorWeightMin = 0f;

                switch (currentHdrPreset) {
                    case BALANCED:
                        // 均衡：原始算法
                        highlight = smoothstep(0.54f, 0.94f, luma[index]);
                        contrastWeight = smoothstep(0.012f, 0.11f, localContrast);
                        colorWeight = smoothstep(0.045f, 0.22f, chroma[index]);
                        value = highlight * (0.30f + 0.45f * contrastWeight + 0.25f * colorWeight);
                        break;

                    case VIVID:
                        // 鲜艳：增强色彩和对比度
                        highlight = smoothstep(0.48f, 0.90f, luma[index]);
                        contrastWeight = smoothstep(0.008f, 0.12f, localContrast);
                        colorWeight = 0.2f + smoothstep(0.035f, 0.20f, chroma[index]) * 0.8f;
                        value = highlight * (0.35f + 0.50f * contrastWeight + 0.30f * colorWeight);
                        skinFactor = 0.15f;  // 肤色保护稍弱
                        break;

                    case NATURAL:
                        // 自然：保守处理
                        highlight = smoothstep(0.58f, 0.96f, luma[index]);
                        contrastWeight = smoothstep(0.018f, 0.10f, localContrast);
                        colorWeight = smoothstep(0.055f, 0.24f, chroma[index]);
                        value = highlight * (0.25f + 0.40f * contrastWeight + 0.20f * colorWeight);
                        shadowFactor = 0.25f;  // 暗部保护更强
                        skinFactor = 0.08f;
                        break;

                    case DRAMATIC:
                        // 戏剧：高对比度
                        highlight = smoothstep(0.40f, 0.88f, luma[index]);
                        contrastWeight = smoothstep(0.005f, 0.15f, localContrast);
                        colorWeight = smoothstep(0.030f, 0.20f, chroma[index]);
                        value = highlight * (0.40f + 0.55f * contrastWeight + 0.35f * colorWeight);
                        shadowFactor = 0.20f;
                        skinFactor = 0.18f;
                        highlightMin = 0.65f;
                        break;

                    case SOFT:
                        // 柔和：温和处理
                        highlight = smoothstep(0.60f, 0.98f, luma[index]);
                        contrastWeight = smoothstep(0.020f, 0.09f, localContrast);
                        colorWeight = 0.1f + smoothstep(0.050f, 0.25f, chroma[index]) * 0.6f;
                        value = highlight * (0.28f + 0.35f * contrastWeight + 0.22f * colorWeight);
                        shadowFactor = 0.30f;
                        skinFactor = 0.06f;
                        highlightMin = 0.75f;
                        break;

                    default:
                        highlight = smoothstep(0.54f, 0.94f, luma[index]);
                        contrastWeight = smoothstep(0.012f, 0.11f, localContrast);
                        colorWeight = smoothstep(0.045f, 0.22f, chroma[index]);
                        value = highlight * (0.30f + 0.45f * contrastWeight + 0.25f * colorWeight);
                        break;
                }

                // 应用保护机制
                if (luma[index] < 0.42f) {
                    value *= shadowFactor;
                }
                if (protect && isSkinLike(r, g, b, luma[index], chroma[index])) {
                    value *= skinFactor;
                }
                if (luma[index] > 0.97f && chroma[index] > 0.06f) {
                    value = Math.max(value, highlightMin);
                }

                float strengthBias = smoothstep(1.4f, 5.0f, ratioMax);
                signal[index] = clamp(value * (0.78f + 0.36f * strengthBias), 0f, 1f);
            }
        }

        blur(signal, gainWidth, gainHeight, 2);
        blur(signal, gainWidth, gainHeight, 2);

        Bitmap gainmap = Bitmap.createBitmap(gainWidth, gainHeight, Bitmap.Config.ARGB_8888);
        int[] out = new int[count];
        int maxEncoded = 0;
        long sumEncoded = 0L;
        int activePixels = 0;
        for (int i = 0; i < count; i++) {
            int encoded = Math.round((float) Math.pow(signal[i], 0.82f) * 255f);
            encoded = clampInt(encoded, 0, 255);
            maxEncoded = Math.max(maxEncoded, encoded);
            sumEncoded += encoded;
            if (encoded >= 24) activePixels++;
            out[i] = Color.rgb(encoded, encoded, encoded);
        }
        lastGainmapStats = new GainmapStats(
                maxEncoded,
                sumEncoded / (float) Math.max(1, count),
                activePixels * 100f / Math.max(1, count));
        gainmap.setPixels(out, 0, gainWidth, 0, 0, gainWidth, gainHeight);
        return gainmap;
    }

    // ── Test Image ──

    private void createBuiltInTestImage() {
        executor.execute(() -> {
            try {
                Bitmap testBase = createTestBaseBitmap(2400, 1600);
                ImageItem item = new ImageItem(null);
                item.sourceBitmap = testBase;
                item.state = ImageState.LOADED;
                imageItems.add(item);
                currentPreviewIndex = imageItems.size() - 1;

                Bitmap converted = createUltraHdrBitmap(testBase, 5.0f, false);
                item.resultBitmap = converted;
                item.state = ImageState.CONVERTED;
                item.stats = lastGainmapStats;

                mainHandler.post(() -> {
                    showPreviewForIndex(currentPreviewIndex);
                    updateBatchUI();
                    updateStatus(getString(R.string.status_test_ready)
                            + "\n" + formatGainmapStats(lastGainmapStats));
                    appendDisplayDiagnostics();
                    updateButtonState();
                });
            } catch (Exception e) {
                mainHandler.post(() ->
                        updateStatus(getString(R.string.status_convert_failed, readableError(e))));
            }
        });
    }

    private Bitmap createTestBaseBitmap(int width, int height) {
        Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

        for (int y = 0; y < height; y++) {
            float t = y / (float) Math.max(1, height - 1);
            int r = Math.round(38 + 70 * t);
            int g = Math.round(68 + 100 * t);
            int b = Math.round(86 + 105 * t);
            paint.setColor(Color.rgb(r, g, b));
            canvas.drawLine(0, y, width, y, paint);
        }

        paint.setShader(new RadialGradient(
                width * 0.72f, height * 0.28f, width * 0.18f,
                Color.rgb(255, 252, 226), Color.rgb(255, 174, 73),
                Shader.TileMode.CLAMP));
        canvas.drawCircle(width * 0.72f, height * 0.28f, width * 0.18f, paint);
        paint.setShader(null);

        paint.setColor(Color.rgb(236, 242, 241));
        canvas.drawRect(width * 0.08f, height * 0.66f, width * 0.92f, height * 0.72f, paint);
        paint.setColor(Color.rgb(21, 52, 52));
        paint.setTextSize(width * 0.045f);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        canvas.drawText("Ultra HDR gain map test", width * 0.08f, height * 0.82f, paint);

        return bitmap;
    }

    // ── Save ──

    private Uri saveBitmapToGallery(Bitmap bitmap) throws IOException {
        ContentResolver resolver = getContentResolver();
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date());
        String displayName = "SDR2HDR_" + timestamp + ".jpg";

        ContentValues values = new ContentValues();
        values.put(MediaStore.Images.Media.DISPLAY_NAME, displayName);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
        values.put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/SDR2HDR");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);

        Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
        Uri uri = resolver.insert(collection, values);
        if (uri == null) {
            throw new IOException(getString(R.string.error_empty_media_uri));
        }

        try (OutputStream output = resolver.openOutputStream(uri)) {
            if (output == null) {
                throw new IOException(getString(R.string.error_output_stream));
            }
            boolean ok = bitmap.compress(Bitmap.CompressFormat.JPEG, 96, output);
            if (!ok) {
                throw new IOException(getString(R.string.error_jpeg_encode));
            }
        } catch (IOException e) {
            resolver.delete(uri, null, null);
            throw e;
        }

        values.clear();
        values.put(MediaStore.Images.Media.IS_PENDING, 0);
        resolver.update(uri, values, null, null);
        return uri;
    }

    private void launchShare(Uri uri) {
        Intent sendIntent = new Intent(Intent.ACTION_SEND);
        sendIntent.setType("image/jpeg");
        sendIntent.putExtra(Intent.EXTRA_STREAM, uri);
        sendIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivity(Intent.createChooser(sendIntent, getString(R.string.share_chooser_title)));
    }

    // ── Gainmap Utilities ──

    private float localContrast(float[] luma, int width, int height, int x, int y) {
        float center = luma[y * width + x];
        float sum = 0f;
        int samples = 0;
        for (int yy = Math.max(0, y - 2); yy <= Math.min(height - 1, y + 2); yy++) {
            for (int xx = Math.max(0, x - 2); xx <= Math.min(width - 1, x + 2); xx++) {
                sum += luma[yy * width + xx];
                samples++;
            }
        }
        return Math.abs(center - (sum / Math.max(1, samples)));
    }

    private void blur(float[] data, int width, int height, int radius) {
        float[] temp = new float[data.length];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float sum = 0f;
                int s = 0;
                for (int xx = Math.max(0, x - radius); xx <= Math.min(width - 1, x + radius); xx++) {
                    sum += data[y * width + xx];
                    s++;
                }
                temp[y * width + x] = sum / s;
            }
        }
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                float sum = 0f;
                int s = 0;
                for (int yy = Math.max(0, y - radius); yy <= Math.min(height - 1, y + radius); yy++) {
                    sum += temp[yy * width + x];
                    s++;
                }
                data[y * width + x] = sum / s;
            }
        }
    }

    private boolean isSkinLike(float r, float g, float b, float luma, float chroma) {
        return luma > 0.24f && luma < 0.88f
                && chroma > 0.045f
                && r > g && g > b * 0.82f
                && r > b * 1.18f
                && (r - g) < 0.24f;
    }

    private float smoothstep(float edge0, float edge1, float value) {
        float t = clamp((value - edge0) / (edge1 - edge0), 0f, 1f);
        return t * t * (3f - 2f * t);
    }

    private float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    @SuppressLint("NewApi")
    private boolean hasGainmap(Bitmap bitmap) {
        return Build.VERSION.SDK_INT >= 34 && bitmap != null && bitmap.hasGainmap();
    }

    // ── UI Helpers ──

    private void configureHdrWindow() {
        Window window = getWindow();
        window.setColorMode(ActivityInfo.COLOR_MODE_HDR);
        if (Build.VERSION.SDK_INT >= 35) {
            window.setDesiredHdrHeadroom(Math.max(3.0f, hdrRatioMax));
        }
    }

    private void setBusy(boolean busy, String message) {
        progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
        if (message != null) updateStatus(message);
        convertButton.setEnabled(!busy && hasItemsToConvert());
        saveButton.setEnabled(!busy && hasItemsToSave());
        shareButton.setEnabled(!busy && hasItemsToShare());
    }

    private void updateButtonState() {
        convertButton.setEnabled(hasItemsToConvert());
        saveButton.setEnabled(hasItemsToSave());
        shareButton.setEnabled(hasItemsToShare());
    }

    private boolean hasItemsToConvert() {
        for (ImageItem item : imageItems) {
            if (item.state == ImageState.LOADED) return true;
        }
        return false;
    }

    private boolean hasItemsToSave() {
        for (ImageItem item : imageItems) {
            if (item.state == ImageState.CONVERTED) return true;
        }
        return false;
    }

    private boolean hasItemsToShare() {
        ImageItem sel = getSelectedItem();
        return sel != null && sel.resultBitmap != null;
    }

    private void updateStrengthText() {
        strengthValue.setText(getString(R.string.hdr_strength_value, hdrRatioMax));
    }

    private void updatePresetButtons() {
        for (Button btn : presetButtons) {
            HdrPreset preset = (HdrPreset) btn.getTag();
            boolean isSelected = preset == currentHdrPreset;
            btn.setTextColor(isSelected ? Color.WHITE : colorOnSurface);
            btn.setBackground(createRippleBg(
                isSelected ? colorPrimary : colorSurfaceVariant, dp(8)));
        }
    }

    private String formatGainmapStats(GainmapStats stats) {
        return getString(R.string.status_gainmap_stats,
                stats.maxEncoded, stats.meanEncoded, stats.activePercent);
    }

    private void updateStatus(String message) {
        statusText.setText(message);
    }

    private void appendDisplayDiagnostics() {
        if (Build.VERSION.SDK_INT >= 34 && getDisplay() != null) {
            statusText.append("\n" + getString(R.string.status_display_headroom,
                    getDisplay().getHdrSdrRatio()));
        }
    }

    private String readableError(Exception e) {
        String message = e.getMessage();
        return message == null || message.trim().isEmpty()
                ? e.getClass().getSimpleName() : message;
    }

    private TextView sectionLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextColor(colorOnSurface);
        label.setTextSize(16);
        label.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL));
        label.setPadding(0, dp(12), 0, dp(8));
        return label;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private LinearLayout.LayoutParams matchWrapWithMargins(int l, int t, int r, int b) {
        LinearLayout.LayoutParams lp = matchWrap();
        lp.setMargins(dp(l), dp(t), dp(r), dp(b));
        return lp;
    }

    private LinearLayout.LayoutParams weightWrap(float weight) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, weight);
        params.setMargins(dp(4), 0, dp(4), 0);
        return params;
    }

    private LinearLayout.LayoutParams wrapWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // ── Inner Classes ──

    private enum ImageState {
        LOADING, LOADED, CONVERTING, CONVERTED, SAVED, FAILED
    }

    private static final class ImageItem {
        final Uri uri;
        Bitmap sourceBitmap;      // 缩略图，用于预览显示
        Bitmap resultBitmap;      // 缩略图，用于预览显示
        Bitmap fullResSource;     // 原始分辨率，用于 HDR 处理
        Bitmap fullResResult;     // 原始分辨率，用于保存
        ImageState state = ImageState.LOADING;
        GainmapStats stats = GainmapStats.empty();
        Uri savedUri;
        String error;

        ImageItem(Uri uri) {
            this.uri = uri;
        }
    }

    private static final class GainmapStats {
        final int maxEncoded;
        final float meanEncoded;
        final float activePercent;

        GainmapStats(int maxEncoded, float meanEncoded, float activePercent) {
            this.maxEncoded = maxEncoded;
            this.meanEncoded = meanEncoded;
            this.activePercent = activePercent;
        }

        static GainmapStats empty() {
            return new GainmapStats(0, 0f, 0f);
        }
    }

    /**
     * HDR 算法预设
     */
    public enum HdrPreset {
        BALANCED("均衡", "均衡的 HDR 效果，适合大多数场景"),
        VIVID("鲜艳", "增强色彩和高光，效果更强烈"),
        NATURAL("自然", "保守的处理，保持原始观感"),
        DRAMATIC("戏剧", "高对比度，适合风景和建筑"),
        SOFT("柔和", "温和的处理，适合人像");

        private final String displayName;
        private final String description;

        HdrPreset(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String getDisplayName() {
            return displayName;
        }

        public String getDescription() {
            return description;
        }
    }
}
