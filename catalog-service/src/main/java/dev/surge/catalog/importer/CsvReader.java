package dev.surge.catalog.importer;

import java.io.IOException;
import java.io.PushbackReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * A small streaming RFC 4180 reader: comma separated, fields may be double-quoted (a quoted field may contain
 * commas, line breaks and {@code ""} for a quote). A quote only opens a quoted field when it is the first
 * character of the field. A malformed record never throws: it comes back with {@link Row#error()} set, and reading
 * continues with the next record, so one bad line cannot sink an import.
 */
final class CsvReader {

    /** One record. {@code line} is the physical line it starts on (1-based). */
    record Row(int line, List<String> fields, String error) {
        boolean isBlank() {
            return error == null && fields.size() == 1 && fields.get(0).isBlank();
        }
    }

    private final PushbackReader in;
    private int line = 1;

    CsvReader(Reader reader) {
        this.in = new PushbackReader(reader, 2);
    }

    /** @return the next record, or null at the end of the input */
    Row next() throws IOException {
        int c = in.read();
        if (c < 0) {
            return null;
        }
        in.unread(c);
        int startLine = line;
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        String error = null;
        boolean inQuotes = false;
        boolean closedQuote = false;
        while (true) {
            c = in.read();
            if (inQuotes) {
                if (c < 0) {
                    error = "unterminated quoted field";
                    break;
                }
                if (c == '"') {
                    int following = in.read();
                    if (following == '"') {
                        field.append('"');
                    } else {
                        if (following >= 0) {
                            in.unread(following);
                        }
                        inQuotes = false;
                        closedQuote = true;
                    }
                } else {
                    if (c == '\n') {
                        line++;
                    }
                    field.append((char) c);
                }
                continue;
            }
            if (c < 0) {
                fields.add(field.toString());
                break;
            }
            if (c == ',') {
                fields.add(field.toString());
                field.setLength(0);
                closedQuote = false;
            } else if (c == '\n' || c == '\r') {
                if (c == '\r') {
                    int following = in.read();
                    if (following != '\n' && following >= 0) {
                        in.unread(following);
                    }
                }
                line++;
                fields.add(field.toString());
                break;
            } else if (c == '"' && field.length() == 0 && !closedQuote) {
                inQuotes = true;
            } else if (closedQuote) {
                if (error == null) {
                    error = "unexpected character after the closing quote";
                }
            } else {
                field.append((char) c);
            }
        }
        return new Row(startLine, fields, error);
    }
}
