package com.example.llamaapp;

import android.content.Intent;
import android.os.Bundle;
import android.widget.*;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import org.json.JSONObject;
import org.vosk.demo.VoskActivity;

import pingpong.advisor.llama.LlamaNative;
import pingpong.advisor.llama.LlmNluEngine;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class MainActivity extends AppCompatActivity {

    // ================= UI =================
    private TextView tvLog;
    private EditText etInput;
    private Button btnSend;
    private ScrollView scrollView;

    // ================= Qwen / llama =================
    private long modelContextPtr = 0;
    private final LlamaNative llamaNative = new LlamaNative();
    private LlmNluEngine nluEngine;
    private final Object llamaLock = new Object();

    // ================= 系统状态 =================
    private boolean qwenReady = false;

    private enum InputMode { NONE, VOICE, TEXT }
    private InputMode currentMode = InputMode.NONE;

    private ActivityResultLauncher<Intent> voskLauncher;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        initUI();

        // ================= Vosk 回调 =================
        voskLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        String recognizedText =
                                result.getData().getStringExtra(VoskActivity.VOSK_RESULT);
                        if (recognizedText != null && !recognizedText.isEmpty()) {
                            log("\n🎙 你: " + recognizedText);
                            runNlu(recognizedText);
                        }
                    } else {
                        log("⚠️ 语音识别取消或失败");
                    }
                }
        );

        btnSend.setOnClickListener(v -> {
            String text = etInput.getText().toString().trim();
            if (text.isEmpty()) return;
            etInput.setText("");

            if (currentMode == InputMode.NONE) {
                handleModeSelect(text);
                return;
            }

            if (currentMode == InputMode.TEXT) {
                log("\n🧑‍💻 你: " + text);
                runNlu(text);
            }
        });

        log("🚀 系统启动中...");
        new Thread(this::bootSystem).start();
    }

    // ================= 模式选择 =================

    private void handleModeSelect(String text) {
        if ("1".equals(text)) {
            currentMode = InputMode.VOICE;
            log("🎙 已选择：语音输入");
            launchVoskUI();
        } else if ("2".equals(text)) {
            currentMode = InputMode.TEXT;
            log("⌨ 已选择：手动输入");
            etInput.setHint("请输入指令...");
        } else {
            log("⚠️ 请输入 1 或 2");
        }
    }

    // ================= Vosk =================

    private void launchVoskUI() {
        try {
            Intent intent = new Intent(this, VoskActivity.class);
            voskLauncher.launch(intent);
        } catch (Exception e) {
            log("❌ 启动 Vosk 失败: " + e.getMessage());
        }
    }

    // ================= UI =================

    private void initUI() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(0, 150, 0, 80);

        scrollView = new ScrollView(this);
        tvLog = new TextView(this);
        tvLog.setPadding(40, 20, 40, 20);
        tvLog.setTextSize(17);
        scrollView.addView(tvLog);

        LinearLayout.LayoutParams scrollParams =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scrollView, scrollParams);

        LinearLayout inputLayout = new LinearLayout(this);
        inputLayout.setOrientation(LinearLayout.HORIZONTAL);
        inputLayout.setPadding(30, 20, 30, 20);

        etInput = new EditText(this);
        etInput.setHint("请输入 1 或 2 选择模式...");
        etInput.setBackgroundColor(0xFFEEEEEE);
        etInput.setPadding(30, 30, 30, 30);

        LinearLayout.LayoutParams etParams =
                new LinearLayout.LayoutParams(0,
                        LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        etParams.rightMargin = 20;
        inputLayout.addView(etInput, etParams);

        btnSend = new Button(this);
        btnSend.setText("发送");
        btnSend.setEnabled(false);
        inputLayout.addView(btnSend);

        root.addView(inputLayout);
        setContentView(root);
    }

    // ================= 系统启动 =================

    private void bootSystem() {
        try {
            loadQwen();

            if (qwenReady) {
                runOnUiThread(() -> {
                    log("\n🎉 Qwen 模型加载完成！");
                    log("请选择输入方式：\n1. 语音（Vosk）\n2. 手动");
                    btnSend.setEnabled(true);
                });
            }
        } catch (Throwable e) {
            log("💥 启动失败: " + e.getMessage());
        }
    }

    private void loadQwen() throws Exception {
        log("⚙ 加载 Qwen 模型...");

        File modelFile = new File(getFilesDir(), "qwen3_0.6b.gguf");
        if (!modelFile.exists() || modelFile.length() < 1024) {
            log("⬇ 复制模型文件...");
            copyAsset("qwen3_0.6b.gguf", modelFile);
        }

        modelContextPtr = llamaNative.initModel(modelFile.getAbsolutePath());
        if (modelContextPtr == 0) {
            throw new RuntimeException("initModel 返回 0");
        }

        /** ✅ 初始化一次即可 */
        nluEngine = new LlmNluEngine(llamaNative, modelContextPtr);

        qwenReady = true;
        log("✅ Qwen 模型就绪");
    }

    // ================= NLU 调用 =================

    private void runNlu(String text) {
        btnSend.setEnabled(false);
        btnSend.setText("解析中...");

        new Thread(() -> {
            try {
                JSONObject result;

                /** 🚨 关键：同一时间只允许一次 llama.generate */
                synchronized (llamaLock) {
                    result = nluEngine.parse(text);
                }

                log("🧠 NLU 结果:\n" + result.toString(2));

            } catch (Exception e) {
                log("❌ NLU 失败: " + e.getMessage());
            } finally {
                runOnUiThread(() -> {
                    btnSend.setEnabled(true);
                    btnSend.setText("发送");
                });
            }
        }).start();
    }

    // ================= 工具 =================

    private void log(String msg) {
        runOnUiThread(() -> {
            tvLog.append(msg + "\n");
            scrollView.post(() -> scrollView.fullScroll(ScrollView.FOCUS_DOWN));
        });
    }

    private void copyAsset(String assetName, File outFile) throws IOException {
        try (InputStream in = getAssets().open(assetName);
             OutputStream out = new FileOutputStream(outFile)) {
            byte[] buffer = new byte[1024 * 1024];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
        }
    }
}
