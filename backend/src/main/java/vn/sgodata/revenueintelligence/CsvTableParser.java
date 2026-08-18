package vn.sgodata.revenueintelligence;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Small RFC-4180 compatible parser kept inside the module to avoid a runtime dependency. */
final class CsvTableParser {
  static final int MAX_BYTES = 20 * 1024 * 1024;
  static final int MAX_ROWS = 50_000;

  record Row(int number, Map<String, String> values) {}
  record Table(List<String> headers, List<Row> rows) {}

  Table parse(byte[] bytes) {
    if (bytes == null || bytes.length == 0) throw new IllegalArgumentException("CSV_EMPTY");
    if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("CSV_TOO_LARGE");
    var text = new String(bytes, StandardCharsets.UTF_8);
    if (text.startsWith("\uFEFF")) text = text.substring(1);
    var records = records(text);
    if (records.isEmpty()) throw new IllegalArgumentException("CSV_HEADER_REQUIRED");
    var headers = records.removeFirst().stream().map(CsvTableParser::header).toList();
    if (headers.stream().anyMatch(String::isBlank) || new LinkedHashSet<>(headers).size() != headers.size())
      throw new IllegalArgumentException("CSV_HEADER_INVALID");
    var rows = new ArrayList<Row>();
    for (int index = 0; index < records.size(); index++) {
      var fields = records.get(index);
      if (fields.stream().allMatch(String::isBlank)) continue;
      if (fields.size() != headers.size()) throw new IllegalArgumentException("CSV_COLUMN_COUNT_AT_ROW_" + (index + 2));
      var values = new LinkedHashMap<String, String>();
      for (int column = 0; column < headers.size(); column++) values.put(headers.get(column), fields.get(column).trim());
      rows.add(new Row(index + 2, Map.copyOf(values)));
      if (rows.size() > MAX_ROWS) throw new IllegalArgumentException("CSV_TOO_MANY_ROWS");
    }
    return new Table(List.copyOf(headers), List.copyOf(rows));
  }

  private static ArrayList<List<String>> records(String text) {
    var output = new ArrayList<List<String>>();
    var row = new ArrayList<String>();
    var field = new StringBuilder();
    boolean quoted = false;
    for (int i = 0; i < text.length(); i++) {
      char current = text.charAt(i);
      if (quoted) {
        if (current == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') { field.append('"'); i++; }
        else if (current == '"') quoted = false;
        else field.append(current);
      } else if (current == '"' && field.isEmpty()) quoted = true;
      else if (current == ',') { row.add(field.toString()); field.setLength(0); }
      else if (current == '\n' || current == '\r') {
        if (current == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
        row.add(field.toString()); field.setLength(0); output.add(List.copyOf(row)); row.clear();
      } else field.append(current);
    }
    if (quoted) throw new IllegalArgumentException("CSV_UNCLOSED_QUOTE");
    if (!field.isEmpty() || !row.isEmpty()) { row.add(field.toString()); output.add(List.copyOf(row)); }
    return output;
  }

  private static String header(String value) {
    return value.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
  }
}
