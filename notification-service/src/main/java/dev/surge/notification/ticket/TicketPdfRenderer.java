package dev.surge.notification.ticket;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.Image;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Component;

/**
 * Renders an A6 ticket. The amount is written as "INR 4,499": the Rupee sign is not in the standard PDF fonts
 * (Helvetica, WinAnsi), and embedding a font for a single glyph is not worth the licence and file size.
 */
@Component
public class TicketPdfRenderer {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("EEE d MMM yyyy, HH:mm z", Locale.ENGLISH);
    private static final int QR_PIXELS = 360;
    private static final float QR_POINTS = 110f;

    public byte[] render(TicketData t) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document doc = new Document(PageSize.A6, 24, 24, 24, 24);
        try {
            PdfWriter.getInstance(doc, out);
            doc.open();

            Font wordmark = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 20);
            Font title = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 13);
            Font body = FontFactory.getFont(FontFactory.HELVETICA, 9);
            Font label = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8);

            doc.add(new Paragraph("SURGE", wordmark));
            Paragraph name = new Paragraph(t.eventTitle(), title);
            name.setSpacingBefore(8);
            doc.add(name);
            doc.add(new Paragraph(t.venueName(), body));
            doc.add(new Paragraph(t.startsAt().format(WHEN), body));

            Paragraph seats = new Paragraph("SEATS", label);
            seats.setSpacingBefore(8);
            doc.add(seats);
            doc.add(new Paragraph(String.join(", ", t.seats()), body));

            Paragraph order = new Paragraph("ORDER", label);
            order.setSpacingBefore(6);
            doc.add(order);
            doc.add(new Paragraph("#" + t.orderId() + "   Total " + money(t.totalInr()), body));

            Image qr = Image.getInstance(QrCodes.png(qrPayload(t), QR_PIXELS));
            qr.scaleAbsolute(QR_POINTS, QR_POINTS);
            qr.setAlignment(Element.ALIGN_CENTER);
            qr.setSpacingBefore(10);
            doc.add(qr);
            doc.close();
        } catch (DocumentException | IOException e) {
            throw new IllegalStateException("Could not render ticket PDF", e);
        }
        return out.toByteArray();
    }

    static String qrPayload(TicketData t) {
        return "surge:ticket:" + t.orderId() + ":" + t.userId();
    }

    static String money(BigDecimal inr) {
        boolean whole = inr.stripTrailingZeros().scale() <= 0;
        DecimalFormat format = new DecimalFormat(whole ? "#,##0" : "#,##0.00",
                DecimalFormatSymbols.getInstance(Locale.US));
        return "INR " + format.format(inr);
    }
}
