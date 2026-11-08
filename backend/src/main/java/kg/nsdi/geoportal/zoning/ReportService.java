package kg.nsdi.geoportal.zoning;

import com.lowagie.text.Document;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.pdf.BaseFont;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;
import kg.nsdi.geoportal.auth.CurrentUser;
import kg.nsdi.geoportal.catalog.LayerRepository;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddress;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * FR-TER-05: отчёт по территории/району — число и типы расхождений, реестр. Форматы XLSX (Apache POI) и PDF (OpenPDF).
 * В целевой архитектуре PDF с картографическим изображением формирует Headless Chromium (подсистема отчётов FR-RPT).
 */
@Service
public class ReportService {
    private static final DateTimeFormatter DT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(ZoneId.of("Asia/Bishkek"));
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("dd.MM.yyyy").withZone(ZoneId.of("Asia/Bishkek"));

    private final ZoningService zoning;
    private final LayerRepository layers;

    public ReportService(ZoningService zoning, LayerRepository layers) { this.zoning = zoning; this.layers = layers; }

    record Report(String title, String territory, String period, String generated, List<String> statuses, List<String> types,
                  Map<String, Map<String, Integer>> matrix, List<Map<String, Object>> rows, Map<String, String> zl, Map<String, String> ul) {}

    private Report build(ZoningService.Filter f, String territoryName, CurrentUser u) {
        List<Map<String, Object>> rows = zoning.list(f);
        List<String> types = List.of("USE_NOT_ALLOWED", "NO_ZONE", "CROSSES_ZONES");
        List<String> statuses = new ArrayList<>(ZoningService.STATUS_LABELS.keySet());
        Map<String, Map<String, Integer>> matrix = new LinkedHashMap<>();
        for (String t : types) { Map<String, Integer> m = new LinkedHashMap<>(); statuses.forEach(s -> m.put(s, 0)); matrix.put(t, m); }
        for (var r : rows) matrix.get((String) r.get("type")).merge((String) r.get("status"), 1, Integer::sum);
        String period = (f.from() == null ? "начало ведения" : D.format(f.from().atStartOfDay(ZoneId.of("Asia/Bishkek")))) + " — " +
                        (f.to() == null ? "текущая дата" : D.format(f.to().atStartOfDay(ZoneId.of("Asia/Bishkek"))));
        return new Report("Отчёт по контролю соответствия территории утверждённому зонированию",
                territoryName == null ? "Все территории" : territoryName, period,
                DT.format(OffsetDateTime.now()) + ", " + (u.name() == null ? "" : u.name()) + (u.orgName() == null ? "" : " (" + u.orgName() + ")"),
                statuses, types, matrix, rows,
                zoning.labels(layers.byCode(ZoningService.ZONING_LAYER), "zone_type"),
                zoning.labels(layers.byCode(ZoningService.DEV_LAYER), "use_type"));
    }

    // ------------------------------------------------------------------ XLSX

    public byte[] xlsx(ZoningService.Filter f, String territoryName, CurrentUser u) throws IOException {
        Report r = build(f, territoryName, u);
        try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            CellStyle bold = wb.createCellStyle();
            org.apache.poi.ss.usermodel.Font bf = wb.createFont(); bf.setBold(true); bold.setFont(bf);
            CellStyle head = wb.createCellStyle();
            head.cloneStyleFrom(bold);
            head.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            head.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            head.setWrapText(true);

            Sheet s = wb.createSheet("Сводка");
            int i = 0;
            cell(s.createRow(i++), 0, r.title(), bold);
            row(s.createRow(i++), "Территория:", r.territory());
            row(s.createRow(i++), "Период выявления:", r.period());
            row(s.createRow(i++), "Сформирован:", r.generated());
            row(s.createRow(i++), "Всего расхождений:", String.valueOf(r.rows().size()));
            i++;
            Row h = s.createRow(i++);
            cell(h, 0, "Тип расхождения", head);
            for (int c = 0; c < r.statuses().size(); c++) cell(h, c + 1, ZoningService.STATUS_LABELS.get(r.statuses().get(c)), head);
            cell(h, r.statuses().size() + 1, "Итого", head);
            for (String t : r.types()) {
                Row row = s.createRow(i++);
                cell(row, 0, ZoningService.TYPE_LABELS.get(t), null);
                int sum = 0;
                for (int c = 0; c < r.statuses().size(); c++) {
                    int v = r.matrix().get(t).get(r.statuses().get(c));
                    row.createCell(c + 1).setCellValue(v); sum += v;
                }
                row.createCell(r.statuses().size() + 1).setCellValue(sum);
            }
            s.setColumnWidth(0, 45 * 256);
            for (int c = 1; c <= r.statuses().size() + 1; c++) s.setColumnWidth(c, 18 * 256);
            s.addMergedRegion(new CellRangeAddress(0, 0, 0, 6));

            Sheet reg = wb.createSheet("Реестр расхождений");
            String[] cols = {"№", "Объект", "Фактическое использование", "Зона", "Тип расхождения", "Описание", "Статус", "Территория",
                             "Ответственный орган", "Выявлено", "Изменено", "Обнаружено при последней проверке", "Долгота", "Широта"};
            Row hr = reg.createRow(0);
            for (int c = 0; c < cols.length; c++) cell(hr, c, cols[c], head);
            int n = 1;
            for (var d : r.rows()) {
                Row row = reg.createRow(n);
                row.createCell(0).setCellValue(n++);
                row.createCell(1).setCellValue(str(d.get("objectTitle")));
                row.createCell(2).setCellValue(r.ul().getOrDefault(str(d.get("useType")), str(d.get("useType"))));
                row.createCell(3).setCellValue(r.zl().getOrDefault(str(d.get("zoneType")), str(d.get("zoneType"))));
                row.createCell(4).setCellValue(str(d.get("typeLabel")));
                row.createCell(5).setCellValue(str(d.get("details")));
                row.createCell(6).setCellValue(str(d.get("statusLabel")));
                row.createCell(7).setCellValue(str(d.get("territoryName")));
                row.createCell(8).setCellValue(str(d.get("responsibleOrg")));
                row.createCell(9).setCellValue(fmt(d.get("createdAt")));
                row.createCell(10).setCellValue(fmt(d.get("updatedAt")));
                row.createCell(11).setCellValue(Boolean.TRUE.equals(d.get("stillPresent")) ? "да" : "нет");
                row.createCell(12).setCellValue(((Number) d.get("lon")).doubleValue());
                row.createCell(13).setCellValue(((Number) d.get("lat")).doubleValue());
            }
            int[] widths = {6, 14, 24, 24, 34, 60, 22, 22, 18, 17, 17, 14, 11, 11};
            for (int c = 0; c < widths.length; c++) reg.setColumnWidth(c, widths[c] * 256);
            reg.createFreezePane(0, 1);
            reg.setAutoFilter(new CellRangeAddress(0, Math.max(n - 1, 1), 0, cols.length - 1));
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static void row(Row r, String k, String v) { r.createCell(0).setCellValue(k); r.createCell(1).setCellValue(v); }
    private static void cell(Row r, int c, String v, CellStyle st) { Cell x = r.createCell(c); x.setCellValue(v); if (st != null) x.setCellStyle(st); }
    private static String str(Object o) { return o == null ? "" : o.toString(); }
    private static String fmt(Object o) {
        if (o instanceof java.sql.Timestamp ts) return DT.format(ts.toInstant());
        if (o instanceof OffsetDateTime odt) return DT.format(odt);
        return str(o);
    }

    // ------------------------------------------------------------------ PDF

    public byte[] pdf(ZoningService.Filter f, String territoryName, CurrentUser u) throws IOException {
        Report r = build(f, territoryName, u);
        BaseFont base = BaseFont.createFont("fonts/DejaVuSans.ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true,
                new ClassPathResource("fonts/DejaVuSans.ttf").getContentAsByteArray(), null);
        BaseFont baseB = BaseFont.createFont("fonts/DejaVuSans-Bold.ttf", BaseFont.IDENTITY_H, BaseFont.EMBEDDED, true,
                new ClassPathResource("fonts/DejaVuSans-Bold.ttf").getContentAsByteArray(), null);
        com.lowagie.text.Font tf = new com.lowagie.text.Font(baseB, 14);
        com.lowagie.text.Font hf = new com.lowagie.text.Font(baseB, 8);
        com.lowagie.text.Font nf = new com.lowagie.text.Font(base, 8);
        com.lowagie.text.Font mf = new com.lowagie.text.Font(base, 10);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A4.rotate(), 28, 28, 28, 28);
        PdfWriter.getInstance(doc, out);
        doc.open();
        doc.add(new Paragraph("Геопортал НИПД КР · Цифровое территориальное управление", nf));
        Paragraph title = new Paragraph(r.title(), tf); title.setSpacingAfter(8); doc.add(title);
        doc.add(new Paragraph("Территория: " + r.territory(), mf));
        doc.add(new Paragraph("Период выявления: " + r.period(), mf));
        doc.add(new Paragraph("Сформирован: " + r.generated(), mf));
        Paragraph tot = new Paragraph("Всего расхождений: " + r.rows().size(), new com.lowagie.text.Font(baseB, 10));
        tot.setSpacingAfter(10); doc.add(tot);

        PdfPTable sum = new PdfPTable(r.statuses().size() + 2);
        sum.setWidthPercentage(100);
        sum.addCell(hcell("Тип расхождения", hf));
        for (String s : r.statuses()) sum.addCell(hcell(ZoningService.STATUS_LABELS.get(s), hf));
        sum.addCell(hcell("Итого", hf));
        for (String t : r.types()) {
            sum.addCell(new PdfPCell(new Phrase(ZoningService.TYPE_LABELS.get(t), nf)));
            int total = 0;
            for (String s : r.statuses()) { int v = r.matrix().get(t).get(s); total += v; sum.addCell(new PdfPCell(new Phrase(String.valueOf(v), nf))); }
            sum.addCell(new PdfPCell(new Phrase(String.valueOf(total), hf)));
        }
        sum.setSpacingAfter(14);
        doc.add(sum);

        doc.add(new Paragraph("Реестр расхождений", new com.lowagie.text.Font(baseB, 11)));
        PdfPTable t = new PdfPTable(new float[]{3, 8, 10, 10, 12, 26, 11, 9, 9});
        t.setWidthPercentage(100);
        t.setSpacingBefore(6);
        t.setHeaderRows(1);
        for (String c : new String[]{"№", "Объект", "Использование", "Зона", "Тип", "Описание", "Статус", "Выявлено", "Изменено"}) t.addCell(hcell(c, hf));
        int n = 1;
        for (var d : r.rows()) {
            t.addCell(new Phrase(String.valueOf(n++), nf));
            t.addCell(new Phrase(str(d.get("objectTitle")), nf));
            t.addCell(new Phrase(r.ul().getOrDefault(str(d.get("useType")), str(d.get("useType"))), nf));
            t.addCell(new Phrase(r.zl().getOrDefault(str(d.get("zoneType")), str(d.get("zoneType"))), nf));
            t.addCell(new Phrase(str(d.get("typeLabel")), nf));
            t.addCell(new Phrase(str(d.get("details")), nf));
            t.addCell(new Phrase(str(d.get("statusLabel")), nf));
            t.addCell(new Phrase(fmt(d.get("createdAt")), nf));
            t.addCell(new Phrase(fmt(d.get("updatedAt")), nf));
        }
        doc.add(t);
        doc.close();
        return out.toByteArray();
    }

    private static PdfPCell hcell(String s, com.lowagie.text.Font f) {
        PdfPCell c = new PdfPCell(new Phrase(s, f));
        c.setBackgroundColor(new Color(230, 233, 237));
        return c;
    }
}
