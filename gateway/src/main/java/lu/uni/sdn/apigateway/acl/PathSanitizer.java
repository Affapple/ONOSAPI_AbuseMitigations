package lu.uni.sdn.apigateway.acl;

import com.google.common.collect.ImmutableList;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Turns the raw (still percent-encoded) request path, relative to /onos/gw,
 * into decoded segments for the ACL. Anything ambiguous is rejected so that the
 * ACL and Jersey's matcher cannot disagree about which resource is addressed:
 * empty segments ("//", trailing "/"), "." and "..", matrix parameters (";"),
 * encoded "/" or "\" and invalid or control-character escapes.
 */
public final class PathSanitizer {

    private PathSanitizer() {
    }

    /**
     * Splits and decodes a raw relative path.
     *
     * @param rawPath raw path relative to the web context, with or without a leading "/"
     * @return decoded segments (empty list for the context root)
     * @throws IllegalArgumentException if the path is ambiguous
     */
    public static List<String> segments(String rawPath) {
        String p = rawPath == null ? "" : rawPath;
        if (p.startsWith("/")) {
            p = p.substring(1);
        }
        if (p.isEmpty()) {
            return ImmutableList.of();
        }
        if (p.indexOf(';') >= 0) {
            throw new IllegalArgumentException("matrix parameters are not allowed");
        }
        ImmutableList.Builder<String> out = ImmutableList.builder();
        for (String raw : p.split("/", -1)) {
            if (raw.isEmpty()) {
                throw new IllegalArgumentException("empty path segment");
            }
            String seg = decode(raw);
            if (seg.equals(".") || seg.equals("..")) {
                throw new IllegalArgumentException("dot segment");
            }
            for (char c : seg.toCharArray()) {
                if (c == '/' || c == '\\' || c < 0x20 || c == 0x7f) {
                    throw new IllegalArgumentException("encoded '/', '\\' or control character in path");
                }
            }
            out.add(seg);
        }
        return out.build();
    }

    private static String decode(String s) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '%') {
                if (i + 2 >= s.length()) {
                    throw new IllegalArgumentException("truncated percent escape");
                }
                int hi = Character.digit(s.charAt(i + 1), 16);
                int lo = Character.digit(s.charAt(i + 2), 16);
                if (hi < 0 || lo < 0) {
                    throw new IllegalArgumentException("invalid percent escape");
                }
                bytes.write(hi * 16 + lo);
                i += 2;
            } else if (c > 0x7f) {
                byte[] b = String.valueOf(c).getBytes(StandardCharsets.UTF_8);
                bytes.write(b, 0, b.length);
            } else {
                bytes.write(c);
            }
        }
        return new String(bytes.toByteArray(), StandardCharsets.UTF_8);
    }
}
