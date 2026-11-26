package com.powerbill.common;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.lowagie.text.Document;
import com.lowagie.text.Element;
import com.lowagie.text.Font;
import com.lowagie.text.PageSize;
import com.lowagie.text.Paragraph;
import com.lowagie.text.Phrase;
import com.lowagie.text.Rectangle;
import com.lowagie.text.pdf.PdfContentByte;
import com.lowagie.text.pdf.PdfPCell;
import com.lowagie.text.pdf.PdfPTable;
import com.lowagie.text.pdf.PdfWriter;

/** Small helpers for the server-rendered invoice and receipt documents. */
public final class PdfKit {
    public static final Color INK = new Color(15, 23, 42);
    public static final Color MUTED = new Color(71, 85, 105);
    public static final Color RULE = new Color(219, 227, 241);
    public static final Color BRAND = new Color(29, 78, 216);
    public static final Font TITLE = new Font(Font.HELVETICA, 18, Font.BOLD, INK);
    public static final Font HEADING = new Font(Font.HELVETICA, 11, Font.BOLD, INK);
    public static final Font BODY = new Font(Font.HELVETICA, 9, Font.NORMAL, INK);
    public static final Font SMALL = new Font(Font.HELVETICA, 8, Font.NORMAL, MUTED);
    public static final Font STRONG = new Font(Font.HELVETICA, 9, Font.BOLD, INK);
    public static final Font BRAND_FONT = new Font(Font.HELVETICA, 10, Font.BOLD, BRAND);

    private PdfKit() {}

    public static byte[] render(String title, String subject, Consumer<Page> body) {
        var output = new ByteArrayOutputStream();
        var document = new Document(PageSize.A4, 42, 42, 42, 42);
        PdfWriter writer = PdfWriter.getInstance(document, output);
        document.addTitle(title);
        document.addSubject(subject);
        document.addCreator("PowerBill GridIQ");
        document.open();
        body.accept(new Page(document, writer));
        document.close();
        return output.toByteArray();
    }

    public static String inr(BigDecimal value) {
        NumberFormat format = NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN"));
        format.setMinimumFractionDigits(2);
        format.setMaximumFractionDigits(2);
        return "INR " + format.format(value.setScale(2, RoundingMode.HALF_UP));
    }

    public static String kwh(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).toPlainString() + " kWh";
    }

    public record Page(Document document, PdfWriter writer) {

        public void heading(String brandLine, String title) {
            add(new Paragraph(brandLine, BRAND_FONT));
            var paragraph = new Paragraph(title, TITLE);
            paragraph.setSpacingAfter(10);
            add(paragraph);
        }

        public void section(String text) {
            var paragraph = new Paragraph(text, HEADING);
            paragraph.setSpacingBefore(12);
            paragraph.setSpacingAfter(4);
            add(paragraph);
        }

        public void note(String text) {
            var paragraph = new Paragraph(text, SMALL);
            paragraph.setSpacingBefore(6);
            add(paragraph);
        }

        public void details(Map<String, String> values) {
            var table = new PdfPTable(new float[] {1.3f, 2.7f});
            table.setWidthPercentage(100);
            values.forEach((label, value) -> {
                table.addCell(cell(label, SMALL, Element.ALIGN_LEFT, false));
                table.addCell(cell(value, BODY, Element.ALIGN_LEFT, false));
            });
            add(table);
        }

        public PdfPTable table(float[] widths, String... headers) {
            var table = new PdfPTable(widths);
            table.setWidthPercentage(100);
            table.setHeaderRows(1);
            for (int index = 0; index < headers.length; index++) {
                var header = cell(headers[index], STRONG, index == 0 ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT, true);
                header.setBackgroundColor(new Color(241, 245, 249));
                table.addCell(header);
            }
            return table;
        }

        public void row(PdfPTable table, boolean bold, String... values) {
            for (int index = 0; index < values.length; index++) {
                table.addCell(cell(values[index], bold ? STRONG : BODY,
                        index == 0 ? Element.ALIGN_LEFT : Element.ALIGN_RIGHT, true));
            }
        }

        public void add(Element element) {
            document.add(element);
        }

        /** Draws a QR code at the top-right corner of the current page. */
        public void qr(String contents, float size) {
            try {
                BitMatrix matrix = new QRCodeWriter().encode(contents, BarcodeFormat.QR_CODE, 0, 0,
                        Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M, EncodeHintType.MARGIN, 0));
                PdfContentByte canvas = writer.getDirectContent();
                Rectangle page = document.getPageSize();
                float module = size / matrix.getWidth();
                float left = page.getRight() - document.rightMargin() - size;
                float top = page.getTop() - document.topMargin();
                canvas.saveState();
                canvas.setColorFill(INK);
                for (int y = 0; y < matrix.getHeight(); y++) {
                    for (int x = 0; x < matrix.getWidth(); x++) {
                        if (matrix.get(x, y)) {
                            canvas.rectangle(left + x * module, top - (y + 1) * module, module, module);
                        }
                    }
                }
                canvas.fill();
                canvas.restoreState();
            } catch (WriterException error) {
                throw new IllegalStateException("Could not encode the verification code.", error);
            }
        }

        private static PdfPCell cell(String text, Font font, int alignment, boolean ruled) {
            var cell = new PdfPCell(new Phrase(text == null ? "" : text, font));
            cell.setHorizontalAlignment(alignment);
            cell.setPadding(5);
            cell.setBorder(ruled ? Rectangle.BOTTOM : Rectangle.NO_BORDER);
            cell.setBorderColor(RULE);
            return cell;
        }
    }
}
