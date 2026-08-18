package vn.sgodata.revenueintelligence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class CsvTableParserTest {
  private final CsvTableParser parser = new CsvTableParser();

  @Test void parsesQuotedCommaAndEscapedQuote() {
    var table = parser.parse("external-id,name\r\n1,\"ACME, \"\"Vietnam\"\"\"\r\n".getBytes(StandardCharsets.UTF_8));
    assertThat(table.headers()).containsExactly("external_id", "name");
    assertThat(table.rows()).hasSize(1);
    assertThat(table.rows().getFirst().number()).isEqualTo(2);
    assertThat(table.rows().getFirst().values().get("name")).isEqualTo("ACME, \"Vietnam\"");
  }

  @Test void rejectsDuplicateHeaders() {
    assertThatThrownBy(() -> parser.parse("id,id\n1,2".getBytes(StandardCharsets.UTF_8)))
        .isInstanceOf(IllegalArgumentException.class).hasMessage("CSV_HEADER_INVALID");
  }
}
