package dev.surge.notification.ticket;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.openpdf.text.pdf.PRStream;
import org.openpdf.text.pdf.PdfDictionary;
import org.openpdf.text.pdf.PdfName;
import org.openpdf.text.pdf.PdfReader;
import org.openpdf.text.pdf.parser.PdfTextExtractor;

class TicketPdfRendererTest {

    private final TicketData data = new TicketData(1234L, "u-7", "Coldplay: Music of the Spheres",
            "Jawaharlal Nehru Stadium", ZonedDateTime.of(2027, 1, 18, 19, 30, 0, 0, ZoneId.of("Asia/Kolkata")),
            List.of("PLATINUM A1", "PLATINUM A2"), new BigDecimal("4499"));

    private final byte[] pdf = new TicketPdfRenderer().render(data);

    @Test
    void startsWithPdfMagic() {
        assertThat(new String(pdf, 0, 4, StandardCharsets.ISO_8859_1)).isEqualTo("%PDF");
    }

    @Test
    void showsTheTicketDetails() throws Exception {
        PdfReader reader = new PdfReader(pdf);
        assertThat(reader.getNumberOfPages()).isEqualTo(1);
        String text = new PdfTextExtractor(reader).getTextFromPage(1);

        assertThat(text).contains("SURGE", "Coldplay: Music of the Spheres", "Jawaharlal Nehru Stadium",
                "PLATINUM A1", "A2 (2 seats)", "1234", "INR 4,499", "19:30");
    }

    private static TicketData with(String title, List<String> seats) {
        return new TicketData(77L, "u-1", title, "Venue", ZonedDateTime.of(2027, 1, 18, 19, 30, 0, 0,
                ZoneId.of("Asia/Kolkata")), seats, new BigDecimal("1499.50"));
    }

    private static String textOfOnePage(TicketData d) throws Exception {
        PdfReader reader = new PdfReader(new TicketPdfRenderer().render(d));
        assertThat(reader.getNumberOfPages()).isEqualTo(1);
        return new PdfTextExtractor(reader).getTextFromPage(1);
    }

    @Test
    void seatsCompactIntoRangesWithATotal() {
        assertThat(TicketPdfRenderer.seatSummary(List.of("A1", "A2", "A3", "A4", "A5", "A6", "A7", "A8", "B3")))
                .isEqualTo("A1–A8, B3 (9 seats)");
        assertThat(TicketPdfRenderer.seatSummary(List.of("A1"))).isEqualTo("A1 (1 seat)");
    }

    @Test
    void thirtyScatteredSeatsStayOnOnePage() throws Exception {
        List<String> seats = new java.util.ArrayList<>();
        for (int i = 0; i < 30; i++) {
            seats.add("ROW" + (char) ('A' + i % 6) + " " + (char) ('A' + i % 6) + (i * 3 + 1));
        }
        String text = textOfOnePage(with("Show", seats));
        assertThat(text).contains("(30 seats)", "77");
    }

    @Test
    void veryLongTitleIsTruncatedOnOnePage() throws Exception {
        String text = textOfOnePage(with("Long ".repeat(40), List.of("A1")));
        assertThat(text).doesNotContain("Long ".repeat(30));
        assertThat(TicketPdfRenderer.clip("x".repeat(200), 60)).hasSize(60).endsWith("…");
    }

    @Test
    void hindiTitlePrintsQuestionMarksNotNothing() throws Exception {
        String text = textOfOnePage(with("अरिजीत नाइट्स",
                List.of("A1")));
        assertThat(text).contains("?");
        assertThat(TicketPdfRenderer.pdfSafe("अर")).isEqualTo("??");
        assertThat(TicketPdfRenderer.pdfSafe("Café 😀")).isEqualTo("Café ?");
        assertThat(TicketPdfRenderer.qrPayload(with("x", List.of("A1")))).isEqualTo("surge:ticket:77:u-1");
    }

    /**
     * OpenPDF stores a 1-bit PNG as CCITT G4, which its own reader cannot decode, so the pixels are not read back
     * here. Decoding is proven by QrCodesTest; this test proves the PDF embeds a square image and that the encoded
     * payload is the documented one.
     */
    @Test
    void embedsTheQrCodeImage() throws Exception {
        PdfReader reader = new PdfReader(pdf);
        PdfDictionary xobjects = reader.getPageN(1).getAsDict(PdfName.RESOURCES).getAsDict(PdfName.XOBJECT);
        assertThat(xobjects).isNotNull();

        List<PRStream> images = xobjects.getKeys().stream()
                .map(k -> PdfReader.getPdfObject(xobjects.get(k)))
                .filter(o -> o instanceof PRStream s && PdfName.IMAGE.equals(s.getAsName(PdfName.SUBTYPE)))
                .map(o -> (PRStream) o)
                .toList();

        assertThat(images).hasSize(1);
        assertThat(images.get(0).getAsNumber(PdfName.WIDTH).intValue())
                .isEqualTo(images.get(0).getAsNumber(PdfName.HEIGHT).intValue())
                .isGreaterThanOrEqualTo(100);
        assertThat(TicketPdfRenderer.qrPayload(data)).isEqualTo("surge:ticket:1234:u-7");
    }
}
