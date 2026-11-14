package dev.surge.catalog.importer;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvReaderTest {

    private static List<CsvReader.Row> read(String text) throws IOException {
        CsvReader reader = new CsvReader(new StringReader(text));
        List<CsvReader.Row> rows = new ArrayList<>();
        for (CsvReader.Row row = reader.next(); row != null; row = reader.next()) {
            rows.add(row);
        }
        return rows;
    }

    @Test
    void splitsPlainFieldsAndTracksLines() throws IOException {
        List<CsvReader.Row> rows = read("a,b,c\r\n1,2,3\n4,,6");
        assertThat(rows).hasSize(3);
        assertThat(rows.get(0).fields()).containsExactly("a", "b", "c");
        assertThat(rows.get(1).line()).isEqualTo(2);
        assertThat(rows.get(2).fields()).containsExactly("4", "", "6");
        assertThat(rows.get(2).line()).isEqualTo(3);
    }

    @Test
    void quotedFieldsKeepCommasEscapedQuotesAndNewlines() throws IOException {
        List<CsvReader.Row> rows = read("\"a,b\",\"say \"\"hi\"\"\",\"x\ny\"\nnext,row\n");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).error()).isNull();
        assertThat(rows.get(0).fields()).containsExactly("a,b", "say \"hi\"", "x\ny");
        assertThat(rows.get(1).line()).isEqualTo(3);
    }

    @Test
    void unterminatedQuoteIsOneErrorRowAtTheStartLine() throws IOException {
        List<CsvReader.Row> rows = read("ok,1\n\"never closed,2\nmore,3\n");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(1).line()).isEqualTo(2);
        assertThat(rows.get(1).error()).contains("unterminated");
    }

    @Test
    void textAfterClosingQuoteIsAnErrorButTheNextRowStillParses() throws IOException {
        List<CsvReader.Row> rows = read("\"a\"b,1\nc,2\n");
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).error()).contains("closing quote");
        assertThat(rows.get(1).error()).isNull();
        assertThat(rows.get(1).fields()).containsExactly("c", "2");
    }

    @Test
    void blankLinesComeBackAsBlankRows() throws IOException {
        List<CsvReader.Row> rows = read("a,b\n\nc,d\n");
        assertThat(rows).hasSize(3);
        assertThat(rows.get(1).isBlank()).isTrue();
        assertThat(rows.get(2).line()).isEqualTo(3);
    }
}
