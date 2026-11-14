package dev.surge.notification.ticket;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.format.DateTimeFormatter;
import java.nio.charset.Charset;
import java.nio.charset.CharsetEncoder;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
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
            Paragraph name = new Paragraph(clip(pdfSafe(t.eventTitle()), MAX_TITLE), title);
            name.setSpacingBefore(8);
            doc.add(name);
            doc.add(new Paragraph(clip(pdfSafe(t.venueName()), MAX_VENUE), body));
            doc.add(new Paragraph(t.startsAt().format(WHEN), body));

            Paragraph seats = new Paragraph("SEATS", label);
            seats.setSpacingBefore(8);
            doc.add(seats);
            doc.add(new Paragraph(seatSummary(t.seats()), body));

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

    private static final int MAX_TITLE = 60;
    private static final int MAX_VENUE = 60;
    /** About two lines of 9 pt text on A6, leaving room for the " (N seats)" suffix. */
    private static final int MAX_SEAT_LIST = 80;
    private static final CharsetEncoder WIN_ANSI = Charset.forName("windows-1252").newEncoder();
    private static final Pattern SEAT = Pattern.compile("^(.*?)(\\d+)$");

    /**
     * Base-14 Helvetica only: text it cannot encode (non-Latin scripts, emoji) prints as '?', never silently
     * dropped. Embed a Unicode font to support such text.
     */
    static String pdfSafe(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        s.codePoints().forEach(cp -> {
            if (Character.isISOControl(cp)) {
                out.append(' ');
            } else if (cp <= 0xFFFF && WIN_ANSI.canEncode((char) cp)) {
                out.appendCodePoint(cp);
            } else {
                out.append('?');
            }
        });
        return out.toString();
    }

    static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** "A1-A8, B3 (9 seats)": consecutive numbers in the same row/prefix become ranges. */
    static String seatSummary(List<String> seats) {
        // group by label prefix (everything before the trailing number), in first-seen order
        Map<String, TreeSet<Integer>> groups = new LinkedHashMap<>();
        List<String> loose = new ArrayList<>();
        for (String raw : seats) {
            String seat = pdfSafe(raw).trim();
            Matcher m = SEAT.matcher(seat);
            if (m.matches() && m.group(2).length() < 9) {
                groups.computeIfAbsent(m.group(1), k -> new TreeSet<>()).add(Integer.parseInt(m.group(2)));
            } else {
                loose.add(seat);
            }
        }
        List<String> parts = new ArrayList<>();
        groups.forEach((prefix, numbers) -> {
            Integer start = null;
            Integer prev = null;
            for (int n : numbers) {
                if (start != null && n != prev + 1) {
                    parts.add(range(prefix, start, prev));
                    start = null;
                }
                if (start == null) {
                    start = n;
                }
                prev = n;
            }
            parts.add(range(prefix, start, prev));
        });
        parts.addAll(loose);
        String list = clip(String.join(", ", parts), MAX_SEAT_LIST);
        return list + " (" + seats.size() + (seats.size() == 1 ? " seat)" : " seats)");
    }

    private static String range(String prefix, int from, int to) {
        // the end of a range drops any leading words: "PLATINUM A1-A8"
        String row = prefix.substring(prefix.lastIndexOf(' ') + 1);
        return from == to ? prefix + from : prefix + from + "–" + row + to;
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
