package com.afs.guard;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.TimePickerDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Typeface;
import android.media.RingtoneManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.List;

/**
 * 待办卡：本机待办列表与定时设置。
 *
 * <p>没有定时的新待办直接进打卡页；设了时间的到点才进，
 * 同时弹一条通知。完成/取消都在打卡页用上下滑决定。</p>
 */
public final class TodoActivity extends ThemedActivity {

    private static final int RC_NOTIFICATIONS = 47;
    private static final int RC_RINGTONE = 48;

    private Ui ui;
    private LinearLayout listBody;
    private TextView countLabel;
    private TextView notificationStatus;
    private String pendingPermissionTodoId;
    private TextView pendingSoundLabel;
    private String[] pendingSoundUri;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        ui = new Ui(this);
        page();
    }

    @Override
    protected void onResume() {
        super.onResume();
        TodoReminder.reconcile(this);
        refreshList();
    }

    @Override
    public void onRequestPermissionsResult(int request, String[] permissions, int[] grants) {
        super.onRequestPermissionsResult(request, permissions, grants);
        if (request != RC_NOTIFICATIONS) {
            return;
        }
        if (pendingPermissionTodoId != null) {
            TodoReminder.schedule(this, TodoStore.find(this, pendingPermissionTodoId));
            pendingPermissionTodoId = null;
        }
        if (TodoReminder.allowed(this)) {
            toast(T.s("通知已开启，到点会发桌面提醒"));
        } else {
            toast(T.s("通知没开；到点打卡页仍会出现待办，但不会弹提醒。"));
        }
        updateNotificationStatus();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request != RC_RINGTONE || result != RESULT_OK || data == null
                || pendingSoundUri == null || pendingSoundLabel == null) {
            return;
        }
        Uri picked = data.getParcelableExtra(RingtoneManager.EXTRA_RINGTONE_PICKED_URI);
        String soundUri;
        if (picked == null) {
            soundUri = "silent";
        } else {
            int flags = data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION;
            if (flags != 0) {
                try {
                    getContentResolver().takePersistableUriPermission(picked, flags);
                } catch (RuntimeException ignored) {
                    // RingtonePicker normally grants ongoing access; non-persistable
                    // providers can still return a usable URI for this session.
                }
            }
            soundUri = Settings.System.DEFAULT_NOTIFICATION_URI.equals(picked)
                    || Settings.System.DEFAULT_RINGTONE_URI.equals(picked)
                    ? "" : TodoStore.normalizeSoundUri(picked.toString());
        }
        pendingSoundUri[0] = soundUri;
        updateSoundLabel(pendingSoundLabel, soundUri);
    }

    private void page() {
        LinearLayout root = column();
        root.setPadding(0, ui.dp(24), 0, 0);
        ui.page(root);
        setContentView(root);

        LinearLayout bar = row();
        bar.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(10));
        root.addView(bar);
        bar.addView(glyphButton(T.s("返回"), T.s("返回"), this::finish),
                new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        TextView heading = SwissTypography.role(text(T.s("待办卡"), 28, ui.text()), SwissTypography.Role.TITLE);
        heading.setTypeface(Typeface.DEFAULT_BOLD);
        LinearLayout.LayoutParams headingLp = new LinearLayout.LayoutParams(0, -2, 1f);
        headingLp.leftMargin = ui.dp(12);
        bar.addView(heading, headingLp);
        bar.addView(plusButton(), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));

        TextView hint = text(T.s("上滑完成 · 下滑自定义延时 · 不占学习进度，也不延长冻结"), 12, ui.text2());
        LinearLayout.LayoutParams hintLp = new LinearLayout.LayoutParams(-1, -2);
        hintLp.setMargins(ui.dp(21), 0, ui.dp(21), ui.dp(14));
        root.addView(hint, hintLp);

        notificationStatus = text("", 12, ui.text2());
        LinearLayout.LayoutParams noticeLp = new LinearLayout.LayoutParams(-1, -2);
        noticeLp.setMargins(ui.dp(21), 0, ui.dp(21), ui.dp(4));
        root.addView(notificationStatus, noticeLp);

        LinearLayout noticeActions = row();
        LinearLayout.LayoutParams actionsLp = new LinearLayout.LayoutParams(-1, -2);
        actionsLp.setMargins(ui.dp(21), 0, ui.dp(21), ui.dp(12));
        root.addView(noticeActions, actionsLp);
        TextView test = text(T.s("发送测试通知"), 12, ui.accent());
        test.setPadding(0, ui.dp(5), ui.dp(18), ui.dp(5));
        test.setOnClickListener(v -> sendTestNotification());
        noticeActions.addView(test);
        TextView settings = text(T.s("系统通知设置"), 12, ui.text2());
        settings.setPadding(0, ui.dp(5), 0, ui.dp(5));
        settings.setOnClickListener(v -> openNotificationSettings());
        noticeActions.addView(settings);

        countLabel = text(T.s("共 0 条"), 12, ui.text2());
        LinearLayout.LayoutParams countLp = new LinearLayout.LayoutParams(-1, -2);
        countLp.setMargins(ui.dp(21), 0, ui.dp(21), ui.dp(10));
        root.addView(countLabel, countLp);

        ScrollView scroll = new ScrollView(this);
        scroll.setClipToPadding(false);
        listBody = vertical();
        listBody.setPadding(ui.dp(20), 0, ui.dp(20), ui.dp(28));
        scroll.addView(listBody, new LinearLayout.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1f));
    }

    private void refreshList() {
        if (listBody == null) {
            return;
        }
        updateNotificationStatus();
        List<TodoStore.Todo> todos = TodoStore.all(this);
        List<TodoStore.Todo> pending = TodoStore.pending(todos);
        List<TodoStore.Todo> resolved = TodoStore.resolved(todos);
        listBody.removeAllViews();
        countLabel.setText(T.s("待处理 ") + pending.size() + T.s(" 条 · 已处理 ") + resolved.size() + T.s(" 条"));

        if (todos.isEmpty()) {
            listBody.addView(emptyCard(T.s("还没有待办"),
                    T.s("新建一条；没定时会立刻出现在打卡页，定了时到点再出现。")));
            return;
        }

        for (TodoStore.Todo todo : pending) {
            listBody.addView(todoRow(todo), rowLp());
        }

        if (!resolved.isEmpty()) {
            LinearLayout head = row();
            LinearLayout.LayoutParams headLp = new LinearLayout.LayoutParams(-1, -2);
            headLp.topMargin = ui.dp(18);
            headLp.bottomMargin = ui.dp(10);
            listBody.addView(head, headLp);
            TextView label = text(T.s("已处理 · ") + resolved.size(), 13, ui.text2());
            head.addView(label, new LinearLayout.LayoutParams(0, -2, 1f));
            TextView clear = text(T.s("清空"), 13, ui.accent());
            clear.setPadding(ui.dp(10), ui.dp(4), ui.dp(4), ui.dp(4));
            clear.setOnClickListener(v -> confirmClearResolved());
            head.addView(clear);

            for (TodoStore.Todo todo : resolved) {
                listBody.addView(todoRow(todo), rowLp());
            }
        }
    }

    private LinearLayout.LayoutParams rowLp() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.bottomMargin = ui.dp(10);
        return lp;
    }

    private View emptyCard(String title, String note) {
        LinearLayout box = ui.card(20f, 18f);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(text(title, 17, ui.text()));
        TextView sub = text(note, 12, ui.text2());
        sub.setLineSpacing(ui.dp(3), 1f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(-1, -2);
        subLp.topMargin = ui.dp(6);
        box.addView(sub, subLp);
        return box;
    }

    private View todoRow(final TodoStore.Todo todo) {
        LinearLayout card = ui.card(18f, 16f);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setOnClickListener(v -> {
            if (todo.pending()) {
                editDialog(todo);
            } else {
                detailDialog(todo);
            }
        });
        card.setOnLongClickListener(v -> {
            confirmDelete(todo);
            return true;
        });

        LinearLayout top = row();
        TextView chip = ui.chip(statusLabel(todo), todo.pending() ? ui.accent() : ui.text2(),
                todo.pending() ? ui.soft(ui.accent(), 0.88f) : ui.surface2());
        chip.setTextSize(11);
        top.addView(chip, new LinearLayout.LayoutParams(-2, -2));
        top.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView schedule = text(scheduleText(todo), 11, ui.text3());
        top.addView(schedule);
        card.addView(top);

        TextView title = text(TodoStore.displayTitle(todo), 18, todo.pending() ? ui.text() : ui.text2());
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(-1, -2);
        titleLp.topMargin = ui.dp(10);
        card.addView(title, titleLp);

        String detail = TodoStore.oneLine(todo.detail);
        if (!detail.isEmpty() && !detail.equals(TodoStore.oneLine(todo.title))) {
            TextView body = text(detail, 12, ui.text2());
            body.setMaxLines(2);
            body.setEllipsize(TextUtils.TruncateAt.END);
            body.setLineSpacing(ui.dp(2), 1f);
            LinearLayout.LayoutParams bodyLp = new LinearLayout.LayoutParams(-1, -2);
            bodyLp.topMargin = ui.dp(5);
            card.addView(body, bodyLp);
        }
        return card;
    }

    private String statusLabel(TodoStore.Todo todo) {
        if (todo.status == TodoStore.STATUS_DONE) {
            return T.s("已完成");
        }
        if (todo.status == TodoStore.STATUS_CANCELLED) {
            return T.s("已取消");
        }
        return todo.remindAt > 0L ? T.s("定时待办") : T.s("待处理");
    }

    private String scheduleText(TodoStore.Todo todo) {
        if (!todo.pending()) {
            return formatTime(todo.resolvedAt, true);
        }
        if (todo.remindAt <= 0L) {
            return T.s("立即进入打卡");
        }
        if (todo.remindAt <= System.currentTimeMillis()) {
            return T.s("已到时间");
        }
        return formatTime(todo.remindAt, false);
    }

    private String formatTime(long millis, boolean resolved) {
        if (millis <= 0L) {
            return "";
        }
        Calendar now = Calendar.getInstance();
        Calendar when = Calendar.getInstance();
        when.setTimeInMillis(millis);
        String pattern = now.get(Calendar.YEAR) == when.get(Calendar.YEAR)
                ? T.s("M月d日 HH:mm") : T.s("yyyy年M月d日 HH:mm");
        String clock = new SimpleDateFormat(pattern, T.locale()).format(new Date(millis));
        return resolved ? clock : T.s("定时 ") + clock;
    }

    private View plusButton() {
        TextView view = new TextView(this);
        ThemeStore.excludeFont(view);
        view.setText(T.s("＋"));
        view.setTextSize(22);
        view.setTextColor(ui.onAccent());
        view.setGravity(Gravity.CENTER);
        view.setBackground(ui.round(ui.accent(), 99));
        view.setContentDescription(T.s("新建待办"));
        view.setOnClickListener(v -> editDialog(null));
        return view;
    }

    private View glyphButton(String glyph, String description, final Runnable action) {
        View view = new ThemeGlyphView(this, glyph, ui.text());
        view.setPadding(ui.dp(10), ui.dp(10), ui.dp(10), ui.dp(10));
        view.setBackground(ui.round(ui.surface(), 99));
        view.setContentDescription(description);
        view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    /** 新建/编辑对话框：标题、说明、显式的「添加提醒」弹窗入口。 */
    private void editDialog(final TodoStore.Todo existing) {
        LinearLayout form = vertical();
        form.setPadding(ui.dp(20), ui.dp(6), ui.dp(20), 0);

        final EditText titleInput = new EditText(this);
        titleInput.setHint(T.s("待办标题"));
        titleInput.setSingleLine(true);
        titleInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        titleInput.setTextColor(ui.text());
        titleInput.setHintTextColor(ui.text3());
        titleInput.setTextSize(17);
        titleInput.setBackground(ui.round(ui.surface2(), 14));
        titleInput.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10));
        if (existing != null) {
            titleInput.setText(existing.title);
            titleInput.setSelection(titleInput.length());
        }
        form.addView(titleInput, new LinearLayout.LayoutParams(-1, -2));

        final EditText detailInput = new EditText(this);
        detailInput.setHint(T.s("补充说明（可选）"));
        detailInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        detailInput.setTextColor(ui.text());
        detailInput.setHintTextColor(ui.text3());
        detailInput.setTextSize(13);
        detailInput.setGravity(Gravity.TOP | Gravity.START);
        detailInput.setMinLines(3);
        detailInput.setMaxLines(6);
        detailInput.setBackground(ui.round(ui.surface2(), 14));
        detailInput.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10));
        if (existing != null) {
            detailInput.setText(existing.detail);
        }
        LinearLayout.LayoutParams detailLp = new LinearLayout.LayoutParams(-1, -2);
        detailLp.topMargin = ui.dp(10);
        form.addView(detailInput, detailLp);

        final long[] remindAt = {existing == null ? 0L : Math.max(0L, existing.remindAt)};
        final TextView schedule = text("", 13, ui.text2());
        schedule.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        schedule.setBackground(ui.round(ui.surface2(), 14));
        schedule.setGravity(Gravity.CENTER_VERTICAL);
        final TextView immediate = text(T.s("取消提醒"), 12, ui.accent());
        immediate.setPadding(ui.dp(10), ui.dp(10), ui.dp(4), ui.dp(10));
        immediate.setOnClickListener(v -> {
            remindAt[0] = 0L;
            updateScheduleLabel(schedule, immediate, remindAt[0]);
        });
        schedule.setOnClickListener(v -> showReminderDialog(remindAt, schedule, immediate));

        LinearLayout scheduleRow = row();
        LinearLayout.LayoutParams scheduleLp = new LinearLayout.LayoutParams(0, -2, 1f);
        scheduleRow.addView(schedule, scheduleLp);
        LinearLayout.LayoutParams immediateLp = new LinearLayout.LayoutParams(-2, -2);
        immediateLp.leftMargin = ui.dp(8);
        scheduleRow.addView(immediate, immediateLp);
        LinearLayout.LayoutParams scheduleRowLp = new LinearLayout.LayoutParams(-1, -2);
        scheduleRowLp.topMargin = ui.dp(10);
        form.addView(scheduleRow, scheduleRowLp);
        updateScheduleLabel(schedule, immediate, remindAt[0]);

        final String[] soundUri = {existing == null ? "" : TodoStore.normalizeSoundUri(existing.soundUri)};
        final TextView sound = text("", 13, ui.text2());
        sound.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12));
        sound.setBackground(ui.round(ui.surface2(), 14));
        sound.setGravity(Gravity.CENTER_VERTICAL);
        sound.setOnClickListener(v -> openRingtonePicker(sound, soundUri, soundUri[0]));
        updateSoundLabel(sound, soundUri[0]);
        final TextView preview = text(T.s("试听"), 12, ui.accent());
        preview.setGravity(Gravity.CENTER);
        preview.setPadding(ui.dp(10), ui.dp(10), ui.dp(4), ui.dp(10));
        preview.setOnClickListener(v -> previewSound(soundUri[0]));

        LinearLayout soundRow = row();
        soundRow.addView(sound, new LinearLayout.LayoutParams(0, -2, 1f));
        soundRow.addView(preview, new LinearLayout.LayoutParams(-2, -2));
        LinearLayout.LayoutParams soundRowLp = new LinearLayout.LayoutParams(-1, -2);
        soundRowLp.topMargin = ui.dp(10);
        form.addView(soundRow, soundRowLp);

        TextView note = text(T.s("定时只用来提醒和把卡推进打卡页；不完成也不会延长学习冻结。"), 11, ui.text3());
        note.setLineSpacing(ui.dp(2), 1f);
        LinearLayout.LayoutParams noteLp = new LinearLayout.LayoutParams(-1, -2);
        noteLp.topMargin = ui.dp(10);
        form.addView(note, noteLp);

        final AlertDialog dialog = ThemeStore.dialog(this)
                .setTitle(existing == null ? T.s("新建待办") : T.s("编辑待办"))
                .setView(form)
                .setPositiveButton(T.s("保存"), null)
                .setNegativeButton(T.s("取消"), null)
                .create();
        dialog.setOnShowListener(d -> dialog.getButton(DialogInterface.BUTTON_POSITIVE)
                .setOnClickListener(v -> saveTodo(existing, titleInput, detailInput,
                        remindAt[0], soundUri[0], dialog)));
        dialog.setOnDismissListener(d -> {
            TodoReminder.stopPreview();
            if (pendingSoundUri == soundUri) {
                pendingSoundUri = null;
                pendingSoundLabel = null;
            }
        });
        dialog.show();
    }

    private void openRingtonePicker(TextView label, String[] soundUri, String current) {
        pendingSoundLabel = label;
        pendingSoundUri = soundUri;
        String normalized = TodoStore.normalizeSoundUri(current);
        Uri existing = "silent".equals(normalized) ? null
                : normalized.isEmpty() ? Settings.System.DEFAULT_NOTIFICATION_URI : Uri.parse(normalized);
        Intent picker = new Intent(RingtoneManager.ACTION_RINGTONE_PICKER)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TYPE, RingtoneManager.TYPE_NOTIFICATION)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_TITLE, T.s("选择提醒铃声"))
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_DEFAULT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_DEFAULT_URI, Settings.System.DEFAULT_NOTIFICATION_URI)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_SHOW_SILENT, true)
                .putExtra(RingtoneManager.EXTRA_RINGTONE_EXISTING_URI, existing);
        try {
            startActivityForResult(picker, RC_RINGTONE);
        } catch (ActivityNotFoundException error) {
            pendingSoundLabel = null;
            pendingSoundUri = null;
            toast(T.s("系统没有可用的铃声选择器"));
        }
    }

    private void updateSoundLabel(TextView label, String soundUri) {
        label.setText(T.s("提醒铃声") + " · " + TodoReminder.soundName(this, soundUri));
        label.setContentDescription(T.s("提醒铃声") + " · " + TodoReminder.soundName(this, soundUri));
    }

    private void previewSound(String soundUri) {
        String normalized = TodoStore.normalizeSoundUri(soundUri);
        if ("silent".equals(normalized)) {
            toast(T.s("当前提醒铃声为静音"));
            return;
        }
        if (!TodoReminder.playPreview(this, normalized)) {
            toast(T.s("无法试听这个铃声"));
        }
    }

    private void updateScheduleLabel(TextView label, TextView immediate, long remindAt) {
        if (remindAt <= 0L) {
            label.setText(T.s("＋ 添加提醒 · 到点弹桌面通知"));
            label.setTextColor(ui.accent());
            if (immediate != null) {
                immediate.setVisibility(View.INVISIBLE);
            }
        } else {
            label.setText(T.s("修改提醒") + " · " + formatTime(remindAt, false));
            label.setTextColor(ui.text());
            if (immediate != null) {
                immediate.setVisibility(View.VISIBLE);
            }
        }
    }

    /** 点「添加提醒」先弹一个说明弹窗，确认后再进日期/时间选择器。 */
    private void showReminderDialog(final long[] remindAt, final TextView label, final TextView immediate) {
        ThemeStore.dialog(this)
                .setTitle(remindAt[0] <= 0L ? T.s("添加提醒") : T.s("修改提醒"))
                .setMessage(T.s("到点会发桌面通知，并把待办推入打卡页。不完成也不会影响学习或冻结。"))
                .setPositiveButton(T.s("选择时间"), (d, w) -> pickSchedule(remindAt, label, immediate))
                .setNegativeButton(T.s("取消"), null)
                .show();
    }

    private void pickSchedule(final long[] remindAt, final TextView label, final TextView immediate) {
        final Calendar date = Calendar.getInstance();
        if (remindAt[0] > System.currentTimeMillis()) {
            date.setTimeInMillis(remindAt[0]);
        } else {
            date.add(Calendar.HOUR_OF_DAY, 1);
            date.set(Calendar.SECOND, 0);
            date.set(Calendar.MILLISECOND, 0);
        }
        DatePickerDialog picker = new DatePickerDialog(this, (view, year, month, day) -> {
            date.set(year, month, day);
            TimePickerDialog time = new TimePickerDialog(this, (clock, hour, minute) -> {
                date.set(Calendar.HOUR_OF_DAY, hour);
                date.set(Calendar.MINUTE, minute);
                date.set(Calendar.SECOND, 0);
                date.set(Calendar.MILLISECOND, 0);
                if (date.getTimeInMillis() <= System.currentTimeMillis()) {
                    toast(T.s("请选择未来的时间"));
                    return;
                }
                remindAt[0] = date.getTimeInMillis();
                updateScheduleLabel(label, immediate, remindAt[0]);
                if (android.os.Build.VERSION.SDK_INT >= 33
                        && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)
                        != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, RC_NOTIFICATIONS);
                }
            }, date.get(Calendar.HOUR_OF_DAY), date.get(Calendar.MINUTE), true);
            time.setButton(DialogInterface.BUTTON_NEGATIVE, T.s("取消"), time);
            time.setButton(DialogInterface.BUTTON_POSITIVE, T.s("确定"), time);
            time.show();
        }, date.get(Calendar.YEAR), date.get(Calendar.MONTH), date.get(Calendar.DAY_OF_MONTH));
        picker.getDatePicker().setMinDate(System.currentTimeMillis() - 1000L);
        picker.setButton(DialogInterface.BUTTON_NEGATIVE, T.s("取消"), picker);
        picker.setButton(DialogInterface.BUTTON_POSITIVE, T.s("确定"), picker);
        picker.show();
    }

    private void saveTodo(TodoStore.Todo existing, EditText titleInput,
                          EditText detailInput, long remindAt, String soundUri,
                          AlertDialog dialog) {
        String title = titleInput.getText().toString().trim();
        String detail = detailInput.getText().toString().trim();
        if (title.isEmpty() && detail.isEmpty()) {
            toast(T.s("写一个标题或说明再保存"));
            return;
        }
        try {
            String id;
            if (existing == null) {
                id = TodoStore.add(this, title, detail, remindAt, soundUri).id;
            } else {
                if (!TodoStore.update(this, existing.id, title, detail, remindAt, soundUri)) {
                    toast(T.s("这条待办已经处理过，不能编辑"));
                    dialog.dismiss();
                    refreshList();
                    return;
                }
                id = existing.id;
            }
            TodoStore.Todo saved = TodoStore.find(this, id);
            TodoReminder.schedule(this, saved);
            dialog.dismiss();
            refreshList();
            boolean timed = remindAt > System.currentTimeMillis();
            if (timed && !TodoReminder.allowed(this)) {
                pendingPermissionTodoId = id;
                toast(T.s("待办已保存；请允许通知，到点才会弹桌面提醒。"));
                if (Build.VERSION.SDK_INT >= 33) {
                    requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, RC_NOTIFICATIONS);
                } else {
                    openNotificationSettings();
                }
            } else {
                toast(timed ? T.s("待办已定时，到点会发桌面提醒") : T.s("待办已加入打卡页"));
            }
        } catch (IllegalArgumentException | IllegalStateException error) {
            toast(error.getMessage() == null ? T.s("待办没能保存") : error.getMessage());
        }
    }

    private void detailDialog(TodoStore.Todo todo) {
        StringBuilder message = new StringBuilder();
        if (todo.detail != null && !todo.detail.trim().isEmpty()) {
            message.append(todo.detail.trim()).append("\n\n");
        }
        message.append(todo.status == TodoStore.STATUS_DONE ? T.s("上滑完成于 ") : T.s("已取消于 "))
                .append(formatTime(todo.resolvedAt, true));
        ThemeStore.dialog(this)
                .setTitle(TodoStore.displayTitle(todo))
                .setMessage(message.toString())
                .setPositiveButton(T.s("关闭"), null)
                .setNeutralButton(T.s("删除"), (d, w) -> doDelete(todo))
                .show();
    }

    private void confirmDelete(final TodoStore.Todo todo) {
        ThemeStore.dialog(this)
                .setTitle(T.s("删除待办"))
                .setMessage(T.s("「") + TodoStore.displayTitle(todo) + T.s("」删掉后不能恢复。"))
                .setPositiveButton(T.s("删除"), (d, w) -> doDelete(todo))
                .setNegativeButton(T.s("取消"), null)
                .show();
    }

    private void doDelete(TodoStore.Todo todo) {
        TodoReminder.cancel(this, todo.id);
        TodoStore.remove(this, todo.id);
        refreshList();
    }

    private void confirmClearResolved() {
        ThemeStore.dialog(this)
                .setTitle(T.s("清空已处理"))
                .setMessage(T.s("所有已完成和已取消的记录会被删掉，没处理的待办不受影响。"))
                .setPositiveButton(T.s("清空"), (d, w) -> {
                    int removed = TodoStore.clearResolved(this);
                    toast(T.s("已清掉 ") + removed + T.s(" 条"));
                    refreshList();
                })
                .setNegativeButton(T.s("取消"), null)
                .show();
    }

    private void updateNotificationStatus() {
        if (notificationStatus == null) {
            return;
        }
        boolean allowed = TodoReminder.allowed(this);
        notificationStatus.setText(allowed
                ? T.s("到点会发桌面通知；横幅和锁屏显示取决于系统通知设置。")
                : T.s("通知未开启：到点不会弹桌面提醒。点这里打开系统通知设置。"));
        notificationStatus.setTextColor(allowed ? ui.green() : ui.red());
        notificationStatus.setOnClickListener(v -> {
            if (!allowed) {
                openNotificationSettings();
            }
        });
    }

    private void sendTestNotification() {
        if (!TodoReminder.allowed(this)) {
            toast(T.s("通知未开启，先打开系统通知设置"));
            openNotificationSettings();
            return;
        }
        toast(TodoReminder.postTest(this)
                ? T.s("测试通知已发送；没看到请检查系统通知的横幅/锁屏开关")
                : T.s("测试通知没能发送"));
    }

    private void openNotificationSettings() {
        try {
            startActivity(new Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, getPackageName()));
            return;
        } catch (RuntimeException ignored) {
        }
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.parse("package:" + getPackageName())));
        } catch (RuntimeException error) {
            toast(T.s("打不开系统设置"));
        }
    }

    private void toast(String value) {
        Toast.makeText(this, value, Toast.LENGTH_SHORT).show();
    }

    private LinearLayout column() {
        LinearLayout box = vertical();
        ui.page(box);
        return box;
    }

    private LinearLayout vertical() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        return box;
    }

    private LinearLayout row() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.HORIZONTAL);
        box.setGravity(Gravity.CENTER_VERTICAL);
        return box;
    }

    private TextView text(String value, float size, int color) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER_VERTICAL);
        return view;
    }
}
