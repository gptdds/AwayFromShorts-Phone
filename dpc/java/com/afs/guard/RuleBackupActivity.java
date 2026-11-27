package com.afs.guard;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.afs.core.rules.RuleBackupDocument;

public final class RuleBackupActivity extends ThemedActivity {
    private static final int EXPORT = 8701, IMPORT = 8702;
    private boolean busy;
    private int generation;

    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this); body.setOrientation(1); body.setPadding(32,32,32,32);
        scroll.addView(body);
        TextView title = new TextView(this); title.setText("规则备份与恢复"); title.setTextSize(23); body.addView(title);
        TextView detail = new TextView(this);
        detail.setText("导出应用规则、星期、时段和专注设置。不会导出设备管理身份、系统权限、授权或密钥。完整学习数据请使用原有整合备份。");
        detail.setPadding(0,24,0,24); body.addView(detail);
        Button export = new Button(this); export.setText("导出 JSON 规则备份"); body.addView(export);
        export.setOnClickListener(view -> pick(EXPORT));
        Button restore = new Button(this); restore.setText("导入 JSON 规则备份"); body.addView(restore);
        restore.setOnClickListener(view -> pick(IMPORT));
        setContentView(scroll);
    }

    private void pick(int request) {
        if (busy) { toast("正在处理，请稍候"); return; }
        Intent intent = new Intent(request == EXPORT ? Intent.ACTION_CREATE_DOCUMENT : Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType(request == EXPORT ? "application/json" : "*/*");
        if (request == EXPORT) intent.putExtra(Intent.EXTRA_TITLE,"AwayFromShorts-rules-"
                + new java.text.SimpleDateFormat("yyyyMMdd-HHmmss",java.util.Locale.ROOT).format(new java.util.Date()) + ".json");
        try { startActivityForResult(intent,request); }
        catch (android.content.ActivityNotFoundException error) { toast("没有可用的系统文件选择器"); }
    }

    protected void onActivityResult(int request,int result,Intent data) {
        super.onActivityResult(request,result,data);
        if ((request != EXPORT && request != IMPORT) || result != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData();
        int token = ++generation; busy = true;
        new Thread(() -> {
            try {
                if (request == EXPORT) {
                    String json = RuleBackup.snapshot(this).encode();
                    try (java.io.OutputStream output = getContentResolver().openOutputStream(uri,"wt")) {
                        if (output == null) throw new java.io.IOException("无法写入目标文件");
                        output.write(json.getBytes("UTF-8"));
                    }
                    post(token,() -> { busy = false; toast("规则备份已导出"); });
                } else {
                    RuleBackupDocument document;
                    try (java.io.InputStream input = getContentResolver().openInputStream(uri)) {
                        document = RuleBackupDocument.parse(RuleBackupDocument.read(input),"device-owner");
                    }
                    String preview = document.preview(RuleBackup.snapshot(this).rules);
                    post(token,() -> {
                        busy = false;
                        new AlertDialog.Builder(this).setTitle("导入预览").setMessage(preview)
                                .setNegativeButton("取消",null)
                                .setNeutralButton("合并规则",(dialog,which) -> restore(document,false))
                                .setPositiveButton("替换规则",(dialog,which) -> restore(document,true)).show();
                    });
                }
            } catch (Exception error) { post(token,() -> { busy = false; toast("操作未完成：" + error.getMessage()); }); }
        },"afs-rule-backup").start();
    }

    private void restore(RuleBackupDocument document,boolean replace) {
        if (busy) return;
        busy = true; int token = ++generation;
        new Thread(() -> {
            try {
                RuleBackup.restore(this,document,replace);
                post(token,() -> { busy = false; toast("规则已恢复，当前配置的备份已保存在本机"); });
            } catch (Exception error) { post(token,() -> { busy = false; toast("恢复未完成：" + error.getMessage()); }); }
        },"afs-rule-restore").start();
    }

    private void post(int token,Runnable action) {
        runOnUiThread(() -> { if (token == generation && !isFinishing() && !isDestroyed()) action.run(); });
    }
    private void toast(String value) { Toast.makeText(this,value,Toast.LENGTH_LONG).show(); }
    protected void onDestroy() { generation++; super.onDestroy(); }
}
