package vn.coreplatform.identity;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.*;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/** RFC 6238 TOTP (HmacSHA1, 6 chữ số, bước 30s) + Base32, không cần dependency ngoài (E3-S04). */
public final class Totp {
  private static final char[] ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
  private static final SecureRandom RANDOM = new SecureRandom();
  private Totp() {}

  public static String generateSecret() {
    var bytes = new byte[20];
    RANDOM.nextBytes(bytes);
    return base32Encode(bytes);
  }

  public static boolean verify(String base32Secret, String code, int windowSteps) {
    if (base32Secret == null || code == null || !code.matches("\\d{6}")) return false;
    for (var offset = -windowSteps; offset <= windowSteps; offset++)
      if (code(base32Secret, offset).equals(code)) return true;
    return false;
  }

  public static String code(String base32Secret, long stepOffset) {
    try {
      var counter = System.currentTimeMillis() / 1000 / 30 + stepOffset;
      var message = new byte[8];
      for (var i = 7; i >= 0; i--) { message[i] = (byte) counter; counter >>>= 8; }
      var mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(base32Decode(base32Secret), "HmacSHA1"));
      var digest = mac.doFinal(message);
      var offset = digest[digest.length - 1] & 0x0f;
      var binary = ((digest[offset] & 0x7f) << 24) | ((digest[offset + 1] & 0xff) << 16) | ((digest[offset + 2] & 0xff) << 8) | (digest[offset + 3] & 0xff);
      return String.format("%06d", binary % 1_000_000);
    } catch (Exception e) { throw new IllegalStateException(e); }
  }

  public static String base32Encode(byte[] bytes) {
    var builder = new StringBuilder();
    int buffer = 0, bits = 0;
    for (var b : bytes) {
      buffer = (buffer << 8) | (b & 0xff);
      bits += 8;
      while (bits >= 5) { builder.append(ALPHABET[(buffer >> (bits - 5)) & 0x1f]); bits -= 5; }
    }
    if (bits > 0) builder.append(ALPHABET[(buffer << (5 - bits)) & 0x1f]);
    return builder.toString();
  }

  public static byte[] base32Decode(String text) {
    var clean = text.replaceAll("=+$", "").replaceAll("\\s", "").toUpperCase(Locale.ROOT);
    int buffer = 0, bits = 0;
    var output = new java.io.ByteArrayOutputStream();
    for (var c : clean.toCharArray()) {
      var index = new String(ALPHABET).indexOf(c);
      if (index < 0) throw new IllegalArgumentException("Ký tự base32 không hợp lệ: " + c);
      buffer = (buffer << 5) | index;
      bits += 5;
      if (bits >= 8) { output.write((buffer >> (bits - 8)) & 0xff); bits -= 8; }
    }
    return output.toByteArray();
  }
}
