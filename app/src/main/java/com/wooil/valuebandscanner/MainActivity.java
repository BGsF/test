package com.wooil.valuebandscanner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.database.Cursor;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_FILES = 1001;
    private static final int LOOKBACK = 480;
    private static final int BINS = 50;
    private static final int TOP_N = 7;
    private static final double MAX_SUPPORT_DISTANCE = 5.0;
    private static final double MIN_SUPPORT_SCORE = 50.0;
    private static final double MIN_DECLINE_20 = 8.0;
    private static final double MIN_DECLINE_60 = 12.0;
    private static final double MIN_AVG_TURNOVER = 300_000_000.0;

    private final List<Uri> selectedUris = new ArrayList<>();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    private TextView fileStatus;
    private TextView scanStatus;
    private LinearLayout resultBox;
    private Button scanButton;
    private Button toggleButton;
    private boolean showAll = false;
    private List<Result> lastResults = new ArrayList<>();

    private int dp(float v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, float sp, int color) {
        TextView v = new TextView(this);
        v.setText(s);
        v.setTextSize(sp);
        v.setTextColor(color);
        v.setLineSpacing(0, 1.15f);
        return v;
    }

    private GradientDrawable bg(int color, int strokeColor, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(radiusDp));
        d.setStroke(dp(1), strokeColor);
        return d;
    }

    private Button button(String label) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(Color.WHITE);
        b.setBackground(bg(Color.rgb(38, 44, 52), Color.rgb(65, 74, 84), 10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(48), 1f);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4));
        b.setLayoutParams(lp);
        return b;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(14), dp(16), dp(14), dp(30));
        root.setBackgroundColor(Color.rgb(12, 14, 17));

        TextView title = text("Value Band Scanner", 23, Color.WHITE);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView desc = text("강한 하단 매물대 접근 종목을 먼저 찾고, 그 뒤 기업가치 훼손 여부를 검증하는 1차 스캐너", 12, Color.rgb(166, 176, 188));
        desc.setPadding(0, dp(4), 0, dp(12));
        root.addView(desc);

        LinearLayout controls = new LinearLayout(this);
        controls.setOrientation(LinearLayout.HORIZONTAL);
        Button choose = button("OHLCV CSV 선택");
        scanButton = button("스캔 실행");
        toggleButton = button("전체 보기");
        toggleButton.setEnabled(false);
        controls.addView(choose);
        controls.addView(scanButton);
        controls.addView(toggleButton);
        root.addView(controls);

        fileStatus = text("선택된 파일 없음", 11, Color.rgb(160, 170, 181));
        fileStatus.setPadding(dp(4), dp(8), dp(4), dp(4));
        root.addView(fileStatus);

        scanStatus = text("기준: 480일 · 50 bins · Top7 · 하단 매물대 5% 이내 · Support 50 이상", 11, Color.rgb(143, 183, 232));
        scanStatus.setPadding(dp(4), dp(5), dp(4), dp(12));
        root.addView(scanStatus);

        resultBox = new LinearLayout(this);
        resultBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(resultBox);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);

        choose.setOnClickListener(v -> chooseFiles());
        scanButton.setOnClickListener(v -> scan());
        toggleButton.setOnClickListener(v -> {
            showAll = !showAll;
            toggleButton.setText(showAll ? "후보만 보기" : "전체 보기");
            renderResults();
        });
    }

    private void chooseFiles() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // MIME 필터를 걸지 않는다. Android/브라우저/ChatGPT 다운로드 파일은
        // CSV여도 application/octet-stream 등으로 등록되는 경우가 있어
        // MIME 제한을 두면 파일이 회색으로 비활성화될 수 있다.
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(Intent.createChooser(intent, "OHLCV CSV 선택"), REQ_FILES);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_FILES || resultCode != RESULT_OK || data == null) return;
        selectedUris.clear();
        ClipData clips = data.getClipData();
        if (clips != null) {
            for (int i = 0; i < clips.getItemCount(); i++) {
                selectedUris.add(clips.getItemAt(i).getUri());
            }
        } else if (data.getData() != null) {
            selectedUris.add(data.getData());
        }
        List<String> names = new ArrayList<>();
        for (Uri u : selectedUris) names.add(fileName(u));
        fileStatus.setText(selectedUris.size() + "개 선택 · " + String.join(" · ", names));
    }

    private String fileName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) return c.getString(idx);
            }
        } catch (Exception ignored) {}
        String p = uri.getLastPathSegment();
        return p == null ? "data.csv" : p;
    }

    private void scan() {
        if (selectedUris.isEmpty()) {
            Toast.makeText(this, "OHLCV CSV를 먼저 선택하세요.", Toast.LENGTH_SHORT).show();
            return;
        }
        scanButton.setEnabled(false);
        toggleButton.setEnabled(false);
        resultBox.removeAllViews();
        scanStatus.setText("CSV 읽는 중...");

        List<Uri> work = new ArrayList<>(selectedUris);
        executor.execute(() -> {
            try {
                ParseBundle bundle = new ParseBundle();
                for (int i = 0; i < work.size(); i++) {
                    Uri u = work.get(i);
                    final int n = i + 1;
                    runOnUiThread(() -> scanStatus.setText("CSV 읽는 중 " + n + "/" + work.size() + " · " + fileName(u)));
                    parseUri(u, bundle);
                }

                List<Result> results = new ArrayList<>();
                int total = bundle.groups.size();
                int pos = 0;
                for (Map.Entry<String, List<Row>> e : bundle.groups.entrySet()) {
                    pos++;
                    Result r = analyze(e.getKey(), bundle.names.get(e.getKey()), e.getValue());
                    if (r != null) results.add(r);
                    final int fp = pos;
                    if (fp % 20 == 0 || fp == total) {
                        runOnUiThread(() -> scanStatus.setText("매물대 분석 중 " + fp + "/" + total));
                    }
                }

                results.sort((a, b) -> {
                    if (a.candidate != b.candidate) return a.candidate ? -1 : 1;
                    int s = Double.compare(b.rankScore, a.rankScore);
                    if (s != 0) return s;
                    return Double.compare(a.distancePct, b.distancePct);
                });
                lastResults = results;

                int candidates = 0;
                for (Result r : results) if (r.candidate) candidates++;
                final int fc = candidates;
                runOnUiThread(() -> {
                    scanButton.setEnabled(true);
                    toggleButton.setEnabled(true);
                    showAll = false;
                    toggleButton.setText("전체 보기");
                    scanStatus.setText("완료 · 분석 " + results.size() + "종목 · 1차 후보 " + fc + "종목");
                    renderResults();
                });
            } catch (Exception ex) {
                runOnUiThread(() -> {
                    scanButton.setEnabled(true);
                    scanStatus.setText("오류: " + ex.getClass().getSimpleName() + " · " + ex.getMessage());
                });
            }
        });
    }

    private void parseUri(Uri uri, ParseBundle out) throws Exception {
        String fallback = fileName(uri).replaceFirst("(?i)\\.csv$", "");
        try (InputStream is = getContentResolver().openInputStream(uri);
             BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8), 64 * 1024)) {
            String headerLine = br.readLine();
            if (headerLine == null) return;
            if (!headerLine.isEmpty() && headerLine.charAt(0) == '\uFEFF') headerLine = headerLine.substring(1);
            List<String> header = splitCsv(headerLine);
            Map<String, Integer> idx = headerMap(header);

            int iDate = find(idx, "date", "일자", "날짜");
            int iOpen = find(idx, "open", "시가");
            int iHigh = find(idx, "high", "고가");
            int iLow = find(idx, "low", "저가");
            int iClose = find(idx, "close", "종가", "현재가");
            int iVol = find(idx, "volume", "거래량");
            int iCode = find(idx, "code", "symbol", "ticker", "종목코드");
            int iName = find(idx, "name", "종목명");

            if (iDate < 0 || iOpen < 0 || iHigh < 0 || iLow < 0 || iClose < 0 || iVol < 0) {
                throw new IllegalArgumentException(fileName(uri) + ": date/open/high/low/close/volume 컬럼을 찾지 못했습니다.");
            }

            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) continue;
                List<String> c = splitCsv(line);
                int need = Math.max(iVol, Math.max(iClose, Math.max(iHigh, Math.max(iLow, Math.max(iOpen, iDate)))));
                if (c.size() <= need) continue;
                String code = iCode >= 0 && iCode < c.size() ? normalizeCode(c.get(iCode)) : normalizeCode(fallback);
                if (code.isBlank()) code = fallback;
                String name = iName >= 0 && iName < c.size() ? c.get(iName).trim() : code;
                try {
                    Row r = new Row();
                    r.date = c.get(iDate).trim();
                    r.open = num(c.get(iOpen));
                    r.high = num(c.get(iHigh));
                    r.low = num(c.get(iLow));
                    r.close = num(c.get(iClose));
                    r.volume = num(c.get(iVol));
                    if (!(r.open > 0 && r.high > 0 && r.low > 0 && r.close > 0 && r.volume >= 0)) continue;
                    out.groups.computeIfAbsent(code, k -> new ArrayList<>()).add(r);
                    out.names.put(code, name);
                } catch (Exception ignored) {}
            }
        }
    }

    private static double num(String s) {
        String v = s.trim().replace(",", "").replace("+", "");
        if (v.startsWith("=")) v = v.substring(1);
        return Double.parseDouble(v);
    }

    private static String normalizeCode(String s) {
        String v = s == null ? "" : s.trim();
        if (v.matches("\\d{1,6}")) {
            try { return String.format(Locale.US, "%06d", Integer.parseInt(v)); } catch (Exception ignored) {}
        }
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{6})").matcher(v);
        if (m.find()) return m.group(1);
        return v;
    }

    private static Map<String, Integer> headerMap(List<String> h) {
        Map<String, Integer> m = new HashMap<>();
        for (int i = 0; i < h.size(); i++) {
            String k = h.get(i).trim().toLowerCase(Locale.ROOT).replace(" ", "").replace("_", "");
            m.put(k, i);
        }
        return m;
    }

    private static int find(Map<String, Integer> m, String... names) {
        for (String n : names) {
            Integer x = m.get(n.toLowerCase(Locale.ROOT).replace(" ", "").replace("_", ""));
            if (x != null) return x;
        }
        return -1;
    }

    private static List<String> splitCsv(String line) {
        List<String> out = new ArrayList<>();
        StringBuilder b = new StringBuilder();
        boolean q = false;
        for (int i = 0; i < line.length(); i++) {
            char ch = line.charAt(i);
            if (ch == '"') {
                if (q && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    b.append('"');
                    i++;
                } else q = !q;
            } else if (ch == ',' && !q) {
                out.add(b.toString());
                b.setLength(0);
            } else b.append(ch);
        }
        out.add(b.toString());
        return out;
    }

    private Result analyze(String code, String name, List<Row> input) {
        if (input.size() < 120) return null;
        input.sort(Comparator.comparing(r -> r.date));
        int from = Math.max(0, input.size() - LOOKBACK);
        List<Row> rows = new ArrayList<>(input.subList(from, input.size()));
        if (rows.size() < 120) return null;

        double current = rows.get(rows.size() - 1).close;
        double pMin = Double.POSITIVE_INFINITY, pMax = 0;
        for (Row r : rows) {
            pMin = Math.min(pMin, r.low);
            pMax = Math.max(pMax, r.high);
        }
        if (!(pMax > pMin)) return null;
        double width = (pMax - pMin) / BINS;
        double[] hist = new double[BINS];

        for (Row r : rows) {
            double typical = (r.high + r.low + r.close) / 3.0;
            int bi = (int) ((typical - pMin) / width);
            if (bi < 0) bi = 0;
            if (bi >= BINS) bi = BINS - 1;
            hist[bi] += r.volume;
        }

        List<Integer> ids = new ArrayList<>();
        for (int i = 0; i < BINS; i++) ids.add(i);
        ids.sort((a, b) -> Double.compare(hist[b], hist[a]));
        if (ids.size() > TOP_N) ids = new ArrayList<>(ids.subList(0, TOP_N));

        double maxHist = 0;
        for (double v : hist) maxHist = Math.max(maxHist, v);
        Support best = null;

        for (int bi : ids) {
            double lo = pMin + bi * width;
            double hi = lo + width;
            if (lo > current * 1.01) continue;
            double dist = current <= hi ? 0 : (current - hi) / current * 100.0;
            if (dist > 15) continue;

            int visits = 0, first = -1, last = -1;
            double launchMax = 0;
            for (int i = 0; i < rows.size(); i++) {
                Row r = rows.get(i);
                if (r.close >= lo && r.close <= hi) {
                    visits++;
                    if (first < 0) first = i;
                    last = i;
                    double futureMax = r.close;
                    int e = Math.min(rows.size(), i + 21);
                    for (int j = i + 1; j < e; j++) futureMax = Math.max(futureMax, rows.get(j).high);
                    launchMax = Math.max(launchMax, (futureMax - hi) / Math.max(hi, 1e-9) * 100.0);
                }
            }

            double volumeStrength = maxHist <= 0 ? 0 : hist[bi] / maxHist * 100.0;
            double visitScore = Math.min(100, visits / 12.0 * 100.0);
            double formationScore = (first >= 0 && last > first) ? (last - first) / (double) rows.size() * 100.0 : 0;
            double recencyScore = last < 0 ? 0 : Math.max(0, 100 - (rows.size() - 1 - last) / 120.0 * 100.0);
            double launchScore = Math.max(0, Math.min(100, launchMax / 30.0 * 100.0));
            double score = volumeStrength * .45 + visitScore * .20 + formationScore * .15 + recencyScore * .10 + launchScore * .10;

            Support s = new Support();
            s.low = lo;
            s.high = hi;
            s.distance = dist;
            s.score = score;
            s.visits = visits;
            s.volumeShare = total(hist) <= 0 ? 0 : hist[bi] / total(hist) * 100.0;
            s.launchPct = launchMax;
            if (best == null || score > best.score || (Math.abs(score - best.score) < 0.001 && dist < best.distance)) best = s;
        }
        if (best == null) return null;

        double d20 = decline(rows, 20, current);
        double d60 = decline(rows, 60, current);
        double avgTurn = 0;
        int nTurn = Math.min(20, rows.size());
        for (int i = rows.size() - nTurn; i < rows.size(); i++) avgTurn += rows.get(i).close * rows.get(i).volume;
        avgTurn /= nTurn;

        double target = current;
        int nTarget = Math.min(120, rows.size());
        for (int i = rows.size() - nTarget; i < rows.size(); i++) target = Math.max(target, rows.get(i).close);
        double rebound = (target - current) / current * 100.0;
        double stop = best.low * 0.985;
        double downside = Math.max(0.1, (current - stop) / current * 100.0);
        double rr = Math.max(0, rebound) / downside;

        boolean candidate = best.distance <= MAX_SUPPORT_DISTANCE
                && best.score >= MIN_SUPPORT_SCORE
                && (d20 >= MIN_DECLINE_20 || d60 >= MIN_DECLINE_60)
                && avgTurn >= MIN_AVG_TURNOVER;

        double distanceScore = Math.max(0, 100 - best.distance / MAX_SUPPORT_DISTANCE * 100.0);
        double declineScore = Math.min(100, Math.max(d20 / 20.0, d60 / 30.0) * 100.0);
        double liqScore = Math.min(100, Math.log10(Math.max(1, avgTurn / 100_000_000.0) + 1) / Math.log10(31) * 100.0);
        double rank = best.score * .60 + distanceScore * .15 + declineScore * .15 + liqScore * .10;

        Result r = new Result();
        r.code = code;
        r.name = name == null || name.isBlank() ? code : name;
        r.current = current;
        r.supportLow = best.low;
        r.supportHigh = best.high;
        r.distancePct = best.distance;
        r.supportScore = best.score;
        r.visits = best.visits;
        r.volumeSharePct = best.volumeShare;
        r.launchPct = best.launchPct;
        r.decline20 = d20;
        r.decline60 = d60;
        r.avgTurnover = avgTurn;
        r.target = target;
        r.stop = stop;
        r.riskReward = rr;
        r.rankScore = rank;
        r.candidate = candidate;
        return r;
    }

    private static double total(double[] a) {
        double s = 0;
        for (double v : a) s += v;
        return s;
    }

    private static double decline(List<Row> rows, int days, double current) {
        int n = Math.min(days, rows.size());
        double max = current;
        for (int i = rows.size() - n; i < rows.size(); i++) max = Math.max(max, rows.get(i).close);
        return max <= 0 ? 0 : (max - current) / max * 100.0;
    }

    private void renderResults() {
        resultBox.removeAllViews();
        int shown = 0;
        for (Result r : lastResults) {
            if (!showAll && !r.candidate) continue;
            if (shown >= 100) break;
            shown++;
            TextView card = text(cardText(r), 13, Color.rgb(231, 237, 244));
            card.setPadding(dp(13), dp(11), dp(13), dp(11));
            card.setBackground(bg(Color.rgb(22, 26, 32), r.candidate ? Color.rgb(71, 107, 82) : Color.rgb(46, 53, 62), 12));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.setMargins(0, dp(5), 0, dp(5));
            card.setLayoutParams(lp);
            card.setOnClickListener(v -> showDetail(r));
            resultBox.addView(card);
        }
        if (shown == 0) {
            TextView empty = text(showAll ? "분석 가능한 종목이 없습니다." : "현재 조건을 모두 충족한 1차 후보가 없습니다.\n'전체 보기'에서 근접 종목을 확인할 수 있습니다.", 13, Color.rgb(160, 170, 181));
            empty.setGravity(Gravity.CENTER);
            empty.setPadding(dp(10), dp(40), dp(10), dp(40));
            resultBox.addView(empty);
        }
    }

    private String cardText(Result r) {
        return (r.candidate ? "● 1차 후보  " : "○ 관찰  ") + r.name + "  [" + r.code + "]\n"
                + "현재 " + money(r.current)
                + "   매물대 " + money(r.supportLow) + "~" + money(r.supportHigh)
                + "   거리 " + pct(r.distancePct) + "\n"
                + "Support " + one(r.supportScore)
                + "   20D -" + pct(r.decline20)
                + "   60D -" + pct(r.decline60)
                + "   거래대금 " + eok(r.avgTurnover) + "\n"
                + "R/R " + one(r.riskReward)
                + "   1차점수 " + one(r.rankScore)
                + "   → 기업가치 훼손 여부 추가검증";
    }

    private void showDetail(Result r) {
        String msg = "현재가: " + money(r.current)
                + "\n강한 하단 매물대: " + money(r.supportLow) + " ~ " + money(r.supportHigh)
                + "\n현재가와 거리: " + pct(r.distancePct)
                + "\nSupport Score: " + one(r.supportScore)
                + "\n누적 거래비중: " + pct(r.volumeSharePct)
                + "\n과거 방문: " + r.visits + "회"
                + "\n매물대 이후 최대 상승: " + pct(r.launchPct)
                + "\n20일 고점 대비: -" + pct(r.decline20)
                + "\n60일 고점 대비: -" + pct(r.decline60)
                + "\n20일 평균 거래대금: " + eok(r.avgTurnover)
                + "\n정상화 참고가격: " + money(r.target)
                + "\n참고 손절선: " + money(r.stop)
                + "\nRisk / Reward: " + one(r.riskReward)
                + "\n\n다음 단계: 최근 하락이 실적·수주·재무·희석 등 기업가치 훼손인지, 시장·업종·수급 같은 외부요인인지 확인합니다.";
        new AlertDialog.Builder(this)
                .setTitle(r.name + " [" + r.code + "]")
                .setMessage(msg)
                .setPositiveButton("확인", null)
                .show();
    }

    private static String one(double v) {
        return String.format(Locale.KOREA, "%.1f", v);
    }

    private static String pct(double v) {
        return String.format(Locale.KOREA, "%.1f%%", v);
    }

    private static String money(double v) {
        DecimalFormat f = new DecimalFormat("#,###");
        return f.format(Math.round(v));
    }

    private static String eok(double won) {
        return String.format(Locale.KOREA, "%.1f억", won / 100_000_000.0);
    }

    @Override
    protected void onDestroy() {
        executor.shutdownNow();
        super.onDestroy();
    }

    static class Row {
        String date;
        double open, high, low, close, volume;
    }

    static class Support {
        double low, high, distance, score, volumeShare, launchPct;
        int visits;
    }

    static class Result {
        String code, name;
        double current, supportLow, supportHigh, distancePct, supportScore;
        double volumeSharePct, launchPct, decline20, decline60, avgTurnover;
        double target, stop, riskReward, rankScore;
        int visits;
        boolean candidate;
    }

    static class ParseBundle {
        final Map<String, List<Row>> groups = new LinkedHashMap<>();
        final Map<String, String> names = new HashMap<>();
    }
}
