package vn.coreplatform.filemanagement;

import java.io.*;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import vn.coreplatform.shared.ApiExceptionHandler.ApiProblem;

/**
 * File lifecycle (E8): STAGING -> (ghi nội dung, checksum) -> SCANNING -> (scan CLEAN) -> ACTIVE,
 * scan bẩn -> QUARANTINED. Upload đứt giữa chừng chỉ để lại row STAGING — cleanup dọn theo tuổi.
 * Reconciliation so khớp metadata DB với object trên storage (missing/mismatch/orphan).
 */
@Service
public class FileStorageService {
  static final Logger log = LoggerFactory.getLogger(FileStorageService.class);
  private final JdbcTemplate jdbc;
  private final FileScanAdapter scanAdapter;
  private final Path root;

  public FileStorageService(JdbcTemplate jdbc, FileScanAdapter scanAdapter,
                            @Value("${core.file-storage-root:/data/files}") String root) throws IOException {
    this.jdbc = jdbc; this.scanAdapter = scanAdapter;
    this.root = Path.of(root).toAbsolutePath().normalize();
    Files.createDirectories(stagingRoot());
    Files.createDirectories(objectsRoot());
  }

  public interface FileScanAdapter { ScanResult scan(String name, String checksumSha256, Path object); }
  public enum ScanResult { CLEAN, INFECTED }

  /** Adapter mặc định: chấp nhận mọi file (triển khai thật sẽ cắm AV). */
  @org.springframework.stereotype.Component
  static class DefaultScanAdapter implements FileScanAdapter {
    @Override public ScanResult scan(String name, String checksumSha256, Path object) { return ScanResult.CLEAN; }
  }

  // ---- lifecycle ----

  @Transactional
  public UUID createSession(UUID tenantId, String tenantKey, UUID owner, String name, String mediaType, String classification, String resourceType, String resourceId) {
    var id = UUID.randomUUID();
    jdbc.update("""
        insert into files.file_object(id, tenant_id, name, media_type, size_bytes, classification, status, storage_key, owner_subject_id, created_by, upload_session_id, resource_type, resource_id)
        values (?,?,?,?,0,?,'STAGING',?,?,?,?,?,?)
        """, id, tenantId, name, mediaType, classification, "staging/" + tenantKey + "/" + id, owner, owner, id, resourceType, resourceId);
    return id;
  }

  public record Uploaded(long sizeBytes, String checksumSha256) {}

  /** Stream nội dung vào staging + tính checksum; row -> SCANNING. Throw thì toàn bộ dọn sạch (không có ACTIVE). */
  @Transactional
  public Uploaded writeContent(UUID fileId, InputStream content) {
    var key = storageKey(fileId);
    var target = resolve(key);
    try {
      Files.createDirectories(target.getParent());
      var digest = MessageDigest.getInstance("SHA-256");
      long size;
      try (var in = content; var out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW)) {
        var buffer = new byte[64 * 1024]; int read; size = 0;
        while ((read = in.read(buffer)) != -1) { digest.update(buffer, 0, read); out.write(buffer, 0, read); size += read; }
      }
      var checksum = HexFormat.of().formatHex(digest.digest());
      jdbc.update("update files.file_object set size_bytes=?, checksum_sha256=?, status='SCANNING', updated_at=now() where id=? and status='STAGING'", size, checksum, fileId);
      if (jdbc.queryForObject("select count(*) from files.file_object where id=? and status='SCANNING' and size_bytes=?", Integer.class, fileId, size) == 0)
        throw new ApiProblem(HttpStatus.CONFLICT, "SESSION_STATE", "Session không ở trạng thái STAGING");
      return new Uploaded(size, checksum);
    } catch (Exception e) {
      try { Files.deleteIfExists(target); } catch (IOException ignored) {}
      if (e instanceof ApiProblem p) throw p;
      throw new ApiProblem(HttpStatus.INTERNAL_SERVER_ERROR, "FILE_WRITE_FAILED", "Không thể ghi file");
    }
  }

  /** E8-S02: chỉ ACTIVE khi scan CLEAN; INFECTED -> QUARANTINED vĩnh viễn. */
  @Transactional
  public FileController.FileItem finalizeUpload(UUID fileId) {
    var rows = jdbc.queryForList("""
        select name, checksum_sha256, storage_key, tenant_id from files.file_object
        where id=? and status='SCANNING'
        """, fileId);
    if (rows.isEmpty()) throw new ApiProblem(HttpStatus.CONFLICT, "SESSION_NOT_SCANNING", "Session chưa có nội dung hoặc đã finalize");
    var row = rows.getFirst();
    var result = scanAdapter.scan((String) row.get("name"), (String) row.get("checksum_sha256"), resolve((String) row.get("storage_key")));
    if (result != ScanResult.CLEAN) {
      jdbc.update("update files.file_object set status='QUARANTINED', scan_result=?, scanned_at=now(), updated_at=now() where id=?", result.name(), fileId);
      throw new ApiProblem(HttpStatus.UNPROCESSABLE_ENTITY, "FILE_QUARANTINED", "Kết quả scan: " + result + " — file không được kích hoạt");
    }
    var stagingKey = (String) row.get("storage_key");
    var finalKey = "objects/" + stagingKey.substring("staging/".length());
    try {
      var source = resolve(stagingKey); var target = resolve(finalKey);
      Files.createDirectories(target.getParent());
      Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
    } catch (IOException e) {
      throw new ApiProblem(HttpStatus.INTERNAL_SERVER_ERROR, "FILE_FINALIZE_FAILED", "Không thể chuyển object sang storage chính");
    }
    jdbc.update("update files.file_object set status='ACTIVE', scan_result='CLEAN', scanned_at=now(), storage_key=?, updated_at=now() where id=?", finalKey, fileId);
    return item(fileId);
  }

  public FileController.FileItem item(UUID id) {
    return jdbc.queryForObject("""
        select id,name,media_type,size_bytes,classification,status,checksum_sha256,created_at,updated_at
        from files.file_object where id=?
        """, (r, n) -> new FileController.FileItem(r.getObject("id", UUID.class), r.getString("name"), r.getString("media_type"),
        r.getLong("size_bytes"), r.getString("classification"), r.getString("status"), r.getString("checksum_sha256"),
        r.getTimestamp("created_at").toInstant(), r.getTimestamp("updated_at").toInstant()), id);
  }

  // ---- E8-S04 reconciliation + staging cleanup ----

  public record Reconciliation(int checked, int missingObjects, int checksumMismatches, int orphansDeleted) {}

  @Transactional
  public Reconciliation reconcile() {
    int missing = 0, mismatch = 0;
    var rows = jdbc.queryForList("select id, storage_key, checksum_sha256 from files.file_object where status='ACTIVE' and storage_key like 'objects/%'");
    for (var row : rows) {
      var path = resolve((String) row.get("storage_key"));
      if (!Files.isRegularFile(path)) {
        jdbc.update("update files.file_object set status='QUARANTINED', scan_result='MISSING_OBJECT', updated_at=now() where id=?", row.get("id"));
        missing++; continue;
      }
      try {
        var actual = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
        if (!actual.equals(row.get("checksum_sha256"))) {
          jdbc.update("update files.file_object set status='QUARANTINED', scan_result='CHECKSUM_MISMATCH', updated_at=now() where id=?", row.get("id"));
          mismatch++;
        }
      } catch (Exception e) { mismatch++; }
    }
    var orphans = 0;
    try (var stream = Files.walk(objectsRoot())) {
      for (var path : (Iterable<Path>) () -> stream.filter(Files::isRegularFile).iterator()) {
        var key = root.relativize(path).toString().replace('\\', '/');
        var known = jdbc.queryForObject("select count(*) from files.file_object where storage_key=?", Integer.class, key);
        if (known == 0) { Files.deleteIfExists(path); orphans++; }
      }
    } catch (IOException e) { log.warn("Reconcile walk failed: {}", e.getMessage()); }
    log.info("Reconciliation: checked={} missing={} mismatch={} orphans={}", rows.size(), missing, mismatch, orphans);
    return new Reconciliation(rows.size(), missing, mismatch, orphans);
  }

  /** E8-S01: dọn staging/scanning quá hạn (upload đứt giữa chừng). */
  @Transactional
  public int cleanupStaging(int olderThanMinutes) {
    var stale = jdbc.queryForList("""
        select id, storage_key from files.file_object where status in ('STAGING','SCANNING') and created_at < now() - make_interval(mins => ?)
        """, olderThanMinutes);
    for (var row : stale) {
      try { Files.deleteIfExists(resolve((String) row.get("storage_key"))); } catch (IOException ignored) {}
      jdbc.update("delete from files.file_object where id=?", row.get("id"));
    }
    return stale.size();
  }

  public Path resolve(String key) {
    var path = root.resolve(key).normalize();
    if (!path.startsWith(root)) throw new ApiProblem(HttpStatus.BAD_REQUEST, "INVALID_STORAGE_KEY", "Storage key không hợp lệ");
    return path;
  }

  public String storageKey(UUID id) {
    var key = jdbc.queryForObject("select storage_key from files.file_object where id=?", String.class, id);
    if (key == null || !key.startsWith("staging/")) throw new ApiProblem(HttpStatus.CONFLICT, "SESSION_NOT_STAGING", "Session đã chuyển trạng thái");
    return key;
  }

  public Path stagingRoot() { return root.resolve("staging"); }
  public Path objectsRoot() { return root.resolve("objects"); }
}
