package com.pku.or.courseassistant.home;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import android.util.Log;

import com.pku.or.courseassistant.R;

import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.Locale;
import java.util.Arrays;
import java.util.Iterator;

public class HomeFragment extends Fragment {
    private static final String TAG = "HomeFragment";
    private HomeViewModel vm;
    private TextView tvStatus;
    private RecyclerView rv;
    private CourseAdapter adapter;

    private ActivityResultLauncher<Intent> openFileLauncher;
    private final ExecutorService parserExecutor = Executors.newSingleThreadExecutor();
    // saved mapping to allow 'use previous mapping' behavior
    private ColumnMapDialogFragment.MappingResult previousMapping = null;
    private boolean applyMappingToAll = false;
    // NOTE: debug flag removed in production

    private static class PendingSheet {
        String name;
        List<String[]> rows;
        String[] header;
        int[] autoMap; // possible precomputed autoMap for prefilling
        PendingSheet(String name, List<String[]> rows) { this(name, rows, new int[]{-1, -1, -1, -1}); }
        PendingSheet(String name, List<String[]> rows, int[] autoMap) { this.name = name; this.rows = rows; this.header = rows.size() > 0 ? rows.get(0) : new String[0]; this.autoMap = autoMap; }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        vm = new ViewModelProvider(this).get(HomeViewModel.class);

        openFileLauncher = registerForActivityResult(new ActivityResultContracts.StartActivityForResult(), result -> {
            // Activity result received
            if (result.getResultCode() == Activity.RESULT_OK) {
                Intent data = result.getData();
                if (data != null) {
                    Uri uri = data.getData();
                    handleUri(uri);
                } else {
                }
            } else {
            }
        });
    }

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.fragment_home, container, false);
        // do not log touch events in production
        v.setOnTouchListener((view, motionEvent) -> false);
        Button btn = v.findViewById(R.id.btn_import);
        btn.setClickable(true);
        btn.setEnabled(true);
        tvStatus = v.findViewById(R.id.tv_status);
        rv = v.findViewById(R.id.rv_courses);
        rv.setLayoutManager(new LinearLayoutManager(getContext()));
        adapter = new CourseAdapter(new ArrayList<>());
        rv.setAdapter(adapter);
        // use DefaultItemAnimator but disable change animations to avoid 'jump from top' effect
        androidx.recyclerview.widget.DefaultItemAnimator animator = new androidx.recyclerview.widget.DefaultItemAnimator();
        animator.setSupportsChangeAnimations(false);
        rv.setItemAnimator(animator);

        // wire adapter long-press listener (2s long press triggers)
        adapter.setOnItemLongPressListener((type, id, position) -> {
            if (getActivity() == null) return;
            // compute a friendly title for the dialog depending on item type
            String title = "(项)";
            try {
                if (type == 0) {
                    // file: show base name
                    String full = id == null ? "(file)" : id;
                    int idx = full.lastIndexOf('/');
                    title = (idx >= 0 && idx < full.length()-1) ? full.substring(idx+1) : full;
                } else if (type == 1) {
                    // sheet: id format file|sheetName
                    if (id != null && id.contains("|")) {
                        String[] parts = id.split("\\|", 2);
                        title = parts.length == 2 ? parts[1] : id;
                        if (title.startsWith("xl/worksheets/")) {
                            title = title.replaceFirst("^xl/worksheets/", "").replaceAll("\\.xml$", "");
                        }
                    } else {
                        title = id == null ? "(sheet)" : id;
                    }
                } else if (type == 2) {
                    // course: try to find the course title from vm
                    List<Course> cur = vm.courses.getValue();
                    if (cur != null) {
                        for (Course c : cur) {
                            if (c != null && c.id != null && c.id.equals(id)) {
                                title = c.title == null || c.title.isEmpty() ? "(未命名)" : c.title;
                                break;
                            }
                        }
                    }
                }
            } catch (Throwable _t) { title = id == null ? "(项)" : id; }

            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(getContext());
            b.setTitle("操作: " + title);
            b.setItems(new String[]{"删除", "重新映射", "取消"}, (dialog, which) -> {
                if (which == 0) {
                    // ask for confirmation
                    android.app.AlertDialog.Builder c = new android.app.AlertDialog.Builder(getContext());
                    c.setTitle("确认删除");
                    c.setMessage("确定要删除该项及其子项吗？此操作不可撤销。");
                    c.setPositiveButton("删除", (dd, ww) -> performDeleteTitle(type, id));
                    c.setNegativeButton("取消", null);
                    c.show();
                } else if (which == 1) {
                    performRemapTitle(type, id);
                } else {
                    // cancel — do nothing
                }
            });
            b.show();
        });

        btn.setOnClickListener(x -> {
            // quick feedback
            android.widget.Toast.makeText(getContext(), "打开文件选择器...", android.widget.Toast.LENGTH_SHORT).show();
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            String[] mimeTypes = {"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/csv", "application/vnd.ms-excel"};
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
                try {
                    if (openFileLauncher != null) {
                        openFileLauncher.launch(intent);
                    } else {
                        // fallback to legacy API
                        startActivityForResult(intent, 1234);
                    }
                } catch (Exception ex) {
                    Log.e(TAG, "Exception while launching file picker", ex);
                    try {
                        startActivityForResult(intent, 1234);
                    } catch (Exception ex2) {
                        Log.e(TAG, "Fallback startActivityForResult also failed", ex2);
                    }
                }
        });

        vm.courses.observe(getViewLifecycleOwner(), list -> {
            adapter.setCourses(list);
            tvStatus.setText("已导入课程: " + (list != null ? list.size() : 0));
        });

        return v;
    }

    // Merge parsed courses into VM with overwrite semantics: remove any existing courses that came from the same fileId and same sheetName before adding new ones.
    private void mergeParsedCourses(List<Course> parsed) {
    if (parsed == null || parsed.isEmpty()) return;
        List<Course> cur = vm.courses.getValue();
        if (cur == null) cur = new ArrayList<>();
        // collect keys to replace (fileId|sheetName) present in parsed
        Set<String> keysToReplace = new HashSet<>();
        // also collect (fileId|title|rawTime) triples from parsed to catch duplicates when sheetName format changed
        Set<String> triplesToReplace = new HashSet<>();
        for (Course p : parsed) {
            String k = (p.fileId == null ? "<nofile>" : p.fileId) + "|" + (p.sheetName == null ? "<nosheet>" : p.sheetName);
            keysToReplace.add(k);
            String t = (p.fileId == null ? "<nofile>" : p.fileId) + "|" + (p.title == null ? "" : p.title) + "|" + (p.rawTime == null ? "" : p.rawTime);
            triplesToReplace.add(t);
        }
        List<Course> remaining = new ArrayList<>();
        for (Course ex : cur) {
            String ek = (ex.fileId == null ? "<nofile>" : ex.fileId) + "|" + (ex.sheetName == null ? "<nosheet>" : ex.sheetName);
            if (keysToReplace.contains(ek)) {
                // exact sheet replacement requested, skip (remove old)
                continue;
            }
            // also remove if fileId+title+rawTime matches any newly parsed course (handles cases where sheetName format changed)
            String et = (ex.fileId == null ? "<nofile>" : ex.fileId) + "|" + (ex.title == null ? "" : ex.title) + "|" + (ex.rawTime == null ? "" : ex.rawTime);
            if (triplesToReplace.contains(et)) {
                // duplicate record found (likely previous import used different sheetName); skip
                continue;
            }
            remaining.add(ex);
        }
        remaining.addAll(parsed);
        vm.save(remaining);
    }

    @Override
    public void onStart() {
        super.onStart();
    }

    @Override
    public void onResume() {
        super.onResume();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        parserExecutor.shutdownNow();
        // parserExecutor shutdown
    }

    private void handleUri(Uri uri) {
        // Run parsing off the UI thread to avoid UI jank for large files
        parserExecutor.submit(() -> {
            // reset per-import 'apply to all' flag so each import starts fresh
            applyMappingToAll = false;
            try (InputStream is = getContext().getContentResolver().openInputStream(uri)) {
                if (is == null) return;
                String path = uri.getLastPathSegment();
                List<Course> parsed = new ArrayList<>();
                if (path != null && path.toLowerCase().endsWith(".csv")) {
                    CsvParser cp = new CsvParser();
                    List<String[]> rows = cp.parse(is);
                    if (rows.size() > 0) {
                        String[] header = rows.get(0);
                        int[] map = autoMapHeader(header);
                        // autoMap CSV header info suppressed in production
                            try { } catch (Exception ex) { /* suppressed in production */ }
                        // Always show mapping dialog for CSV imports (prefill with autoMap if available)
                        List<String> headers = new ArrayList<>();
                        for (String h : header) headers.add(h == null ? "" : h);
                        List<String[]> dataRows = rows.size() > 1 ? rows.subList(1, rows.size()) : new ArrayList<>();
                        getActivity().runOnUiThread(() -> {
                            ColumnMapDialogFragment dlg = new ColumnMapDialogFragment(headers, new ColumnMapDialogFragment.Listener() {
                                @Override
                                public void onMapping(ColumnMapDialogFragment.MappingResult result) {
                                    previousMapping = result;
                                    parserExecutor.submit(() -> {
                                        List<Course> p = Course.fromRows(path, "sheet1", dataRows, result.titleIdx, result.timeIdx, result.teacherIdx, result.unitIdx);
                                        getActivity().runOnUiThread(() -> mergeParsedCourses(p));
                                        if (result.applyToAll) applyMappingToAll = true;
                                    });
                                }

                                @Override
                                public void onUsePrevious() {
                                    if (previousMapping != null) {
                                        parserExecutor.submit(() -> {
                                            List<Course> p = Course.fromRows(path, "sheet1", dataRows, previousMapping.titleIdx, previousMapping.timeIdx, previousMapping.teacherIdx, previousMapping.unitIdx);
                                            getActivity().runOnUiThread(() -> mergeParsedCourses(p));
                                        });
                                    }
                                }
                            });
                            // prefill with autoMap if helpful
                            try {
                                if (map != null && !(map[0] == -1 && map[1] == -1 && map[2] == -1 && map[3] == -1)) {
                                    ColumnMapDialogFragment.MappingResult mr = new ColumnMapDialogFragment.MappingResult(map[0], map[1], map[2], map[3], false);
                                    dlg.setPrefillMapping(mr);
                                } else if (previousMapping != null) dlg.setPrefillMapping(previousMapping);
                            } catch (Exception ex) { /* suppressed in production */ }
                            dlg.show(getParentFragmentManager(), "colmap");
                        });
                    }
                } else {
                    XlsxLightParser xp = new XlsxLightParser();
                    XlsxLightParser.ParseResult pr = xp.parse(is);
                    // for MVP, concatenate all sheets rows
                    // we'll iterate sheets and allow reuse/apply-to-all behavior
                    // collect pending sheets that need manual mapping
                    List<PendingSheet> pending = new ArrayList<>();
                    for (XlsxLightParser.Sheet s : pr.sheets) {
                        if (s.rows.size() > 0) {
                            String[] header = s.rows.get(0);
                            int[] map = autoMapHeader(header);
                            try { } catch (Exception ex) { /* suppressed in production */ }
                            // Always add to pending so the mapping dialog will be shown for each import.
                            // If user chooses apply-to-all during this import, we'll apply previousMapping to remaining sheets in this run.
                            pending.add(new PendingSheet(s.name, s.rows, map));
                        }
                    }

                    try { } catch (Exception ex) { /* suppressed in production */ }

                    if (!pending.isEmpty()) {
                        // process pending sheets sequentially on the UI thread
                        getActivity().runOnUiThread(() -> processPendingSheets(pending, parsed, path));
                    }
                }
                // merge with existing (if parsing happened without needing mapping dialog)
                if (!parsed.isEmpty()) {
                    getActivity().runOnUiThread(() -> mergeParsedCourses(parsed));
                }
            } catch (Exception e) {
                Log.e(TAG, "handleUri error", e);
            }
        });
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 1234 && resultCode == Activity.RESULT_OK && data != null) {
            Uri uri = data.getData();
            handleUri(uri);
        }
    }

    private int[] autoMapHeader(String[] header) {
        int titleIdx = -1, timeIdx = -1, teacherIdx = -1, unitIdx = -1;
        if (header == null) return new int[]{-1, -1, -1, -1};
        for (int i = 0; i < header.length; i++) {
            String h = header[i] == null ? "" : header[i].toLowerCase(Locale.ROOT).trim();
            // detect title: prefer explicit "课程名/课程名称/课名/名称/title/name"
            boolean looksLikeTitle = h.contains("课程名") || h.contains("课程名称") || h.contains("课名") || h.contains("名称") || h.contains("title") || h.contains("name");
            // avoid columns that are clearly numeric id/code, e.g. "课程号", "编号", "序号", "id", "代码"
            boolean looksLikeId = h.contains("号") || h.contains("编号") || h.contains("序号") || h.contains("id") || h.contains("代码") || h.contains("code") || h.matches(".*\\bno\\b.*");
            if (titleIdx == -1 && looksLikeTitle) titleIdx = i;
            // if header contains only generic "课程" but also contains id-like tokens, skip as title
            if (titleIdx == -1 && h.contains("课程") && !looksLikeId) {
                titleIdx = i;
            }
            if (timeIdx == -1 && (h.contains("时间") || h.contains("上课") || h.contains("time") || h.contains("schedule"))) timeIdx = i;
            if (teacherIdx == -1 && (h.contains("教师") || h.contains("讲师") || h.contains("老师") || h.contains("teacher") || h.contains("lecturer"))) teacherIdx = i;
            if (unitIdx == -1 && (h.contains("学院") || h.contains("单位") || h.contains("department") || h.contains("单位"))) unitIdx = i;
        }
        return new int[]{titleIdx, timeIdx, teacherIdx, unitIdx};
    }

    private void processPendingSheets(List<PendingSheet> pending, List<Course> parsed, String path) {
        Iterator<PendingSheet> it = pending.iterator();
        // iterate sequentially
        processNextPending(it, parsed, path);
    }

    private void processNextPending(Iterator<PendingSheet> it, List<Course> parsed, String path) {
        if (!it.hasNext()) return;
        PendingSheet ps = it.next();
        List<String> headers = new ArrayList<>();
        for (String h : ps.header) headers.add(h == null ? "" : h);
                ColumnMapDialogFragment dlg = new ColumnMapDialogFragment(headers, new ColumnMapDialogFragment.Listener() {
            @Override
            public void onMapping(ColumnMapDialogFragment.MappingResult result) {
                previousMapping = result;
                if (result.applyToAll) applyMappingToAll = true;
                        List<String[]> dataRows = ps.rows.size() > 1 ? ps.rows.subList(1, ps.rows.size()) : new ArrayList<>();
                        // log sample values from the sheet header and first data row to help debug mapping
                        try { } catch (Exception ex) { /* suppressed in production */ }

                                parserExecutor.submit(() -> {
                                    List<Course> p = Course.fromRows(path, ps.name, dataRows, result.titleIdx, result.timeIdx, result.teacherIdx, result.unitIdx);
                                    // merge into parsed in background list
                                    synchronized (parsed) { parsed.addAll(p); }
                                    // save immediately so UI will show grouped title for this sheet
                                    getActivity().runOnUiThread(() -> mergeParsedCourses(p));
                                    if (result.applyToAll && previousMapping != null) {
                                        // apply previousMapping to remaining pending sheets in this import run
                                        List<PendingSheet> remaining = new ArrayList<>();
                                        while (it.hasNext()) remaining.add(it.next());
                                        for (PendingSheet ps2 : remaining) {
                                            List<String[]> dataRows2 = ps2.rows.size() > 1 ? ps2.rows.subList(1, ps2.rows.size()) : new ArrayList<>();
                                            List<Course> p2 = Course.fromRows(path, ps2.name, dataRows2, previousMapping.titleIdx, previousMapping.timeIdx, previousMapping.teacherIdx, previousMapping.unitIdx);
                                            synchronized (parsed) { parsed.addAll(p2); }
                                            // save each sheet's results as they are processed
                                            getActivity().runOnUiThread(() -> mergeParsedCourses(p2));
                                        }
                                        // all remaining processed; nothing more to do
                                    } else {
                                        // continue with next pending sheet on UI thread
                                        getActivity().runOnUiThread(() -> processNextPending(it, parsed, path));
                                    }
                                });
            }

            @Override
            public void onUsePrevious() {
                        if (previousMapping != null) {
                            List<String[]> dataRows = ps.rows.size() > 1 ? ps.rows.subList(1, ps.rows.size()) : new ArrayList<>();
                            parserExecutor.submit(() -> {
                                List<Course> p = Course.fromRows(path, ps.name, dataRows, previousMapping.titleIdx, previousMapping.timeIdx, previousMapping.teacherIdx, previousMapping.unitIdx);
                                synchronized (parsed) { parsed.addAll(p); }
                                getActivity().runOnUiThread(() -> processNextPending(it, parsed, path));
                            });
                        } else {
                            processNextPending(it, parsed, path);
                        }
            }
        });
        // determine prefill mapping: prefer sheet autoMap then previousMapping
        try {
            if (ps.autoMap != null && !(ps.autoMap[0] == -1 && ps.autoMap[1] == -1 && ps.autoMap[2] == -1 && ps.autoMap[3] == -1)) {
                ColumnMapDialogFragment.MappingResult mr = new ColumnMapDialogFragment.MappingResult(ps.autoMap[0], ps.autoMap[1], ps.autoMap[2], ps.autoMap[3], false);
                dlg.setPrefillMapping(mr);
            } else if (previousMapping != null) {
                dlg.setPrefillMapping(previousMapping);
            }
    } catch (Exception ex) { /* suppressed in production */ }
        dlg.show(getParentFragmentManager(), "colmap");
    }

    // Helper: delete the item represented by type + id
    private void performDeleteTitle(int type, String id) {
        if (id == null) return;
        parserExecutor.submit(() -> {
            List<Course> cur = vm.courses.getValue();
            if (cur == null) cur = new ArrayList<>();
            List<Course> remaining = new ArrayList<>();
            String targetFile = null;
            String targetSheet = null;
            if (type == 0) {
                // file id
                targetFile = id;
            } else if (type == 1) {
                // sheet id format: file|sheet
                String[] parts = id.split("\\|", 2);
                if (parts.length >= 1) targetFile = parts[0];
                if (parts.length == 2) targetSheet = parts[1];
            } else if (type == 2) {
                // course id: find the course to determine its file and sheet
                for (Course c : cur) {
                    if (c != null && c.id != null && c.id.equals(id)) {
                        targetFile = c.fileId == null ? "<nofile>" : c.fileId;
                        targetSheet = c.sheetName == null ? "<nosheet>" : c.sheetName;
                        break;
                    }
                }
            }
            for (Course c : cur) {
                String f = c.fileId == null ? "<nofile>" : c.fileId;
                String s = c.sheetName == null ? "<nosheet>" : c.sheetName;
                boolean drop = false;
                if (type == 0) {
                    if (f.equals(targetFile)) drop = true;
                } else if (type == 1) {
                    if (f.equals(targetFile) && targetSheet != null && s.equals(targetSheet)) drop = true;
                } else if (type == 2) {
                    // delete only this single course
                    if (c.id != null && c.id.equals(id)) drop = true;
                }
                if (!drop) remaining.add(c);
            }
            vm.save(remaining);
        });
    }

    // Helper: remap title — since original parsed rows are not cached across imports, instruct user to re-import
    private void performRemapTitle(int type, String id) {
        // try to present a helpful message including the file name if possible
        String fileHint = id;
        if (type == 1 && id != null && id.contains("|")) {
            fileHint = id.split("\\|", 2)[0];
        } else if (type == 2 && id != null) {
            // try to find course to show file
            List<Course> cur = vm.courses.getValue();
            if (cur != null) {
                for (Course c : cur) {
                    if (c != null && c.id != null && c.id.equals(id)) {
                        fileHint = c.fileId == null ? fileHint : c.fileId;
                        break;
                    }
                }
            }
        }
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(getContext());
        b.setTitle("重新映射");
        b.setMessage("未缓存原始表格数据（文件: " + fileHint + "）。要重新映射，请重新导入该文件并在映射对话框中选择新的列映射。\n\n是否现在打开文件选择器重新导入？");
        b.setPositiveButton("重新导入", (d, w) -> {
            // trigger file picker
            Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
            intent.setType("*/*");
            intent.addCategory(Intent.CATEGORY_OPENABLE);
            String[] mimeTypes = {"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "text/csv", "application/vnd.ms-excel"};
            intent.putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes);
            if (openFileLauncher != null) openFileLauncher.launch(intent);
        });
        b.setNegativeButton("取消", null);
        b.show();
    }
}
