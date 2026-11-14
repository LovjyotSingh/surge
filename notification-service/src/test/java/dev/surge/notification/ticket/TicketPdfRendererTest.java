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
                "PLATINUM A1", "PLATINUM A2", "1234", "INR 4,499", "19:30");
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
