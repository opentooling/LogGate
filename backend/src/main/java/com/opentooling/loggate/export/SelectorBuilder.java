package com.opentooling.loggate.export;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Builds the LogQL selector for an export.
 *
 * <p>Callers never supply LogQL. Pod and container filters are globs, and every
 * character except {@code *} and {@code ?} is escaped into a literal, which
 * removes both LogQL injection and regex denial-of-service as categories rather
 * than defending against them case by case.
 */
public final class SelectorBuilder {

  /** DNS-1123 label, which is all a Kubernetes namespace name can be. */
  private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9]([-a-z0-9]*[a-z0-9])?");

  private static final int MAX_GLOB_LENGTH = 256;
  private static final int MAX_LINE_FILTER_LENGTH = 512;

  private SelectorBuilder() {}

  /** The stream selector, plus any line filter, for {@code request}. */
  public static String build(ExportRequest request) {
    StringBuilder selector = new StringBuilder("{");
    selector.append(namespaceMatcher(request.namespaces()));
    if (hasText(request.podPattern())) {
      selector.append(", pod=~\"").append(globToRegex(request.podPattern())).append('"');
    }
    if (hasText(request.containerPattern())) {
      selector
          .append(", container=~\"")
          .append(globToRegex(request.containerPattern()))
          .append('"');
    }
    selector.append('}');
    if (hasText(request.lineFilter())) {
      // A literal substring match, not a regex: |= rather than |~.
      selector.append(" |= \"").append(escapeStringLiteral(request.lineFilter())).append('"');
    }
    return selector.toString();
  }

  /** The selector without any line filter, which is what the volume API sizes. */
  public static String buildStreamSelector(ExportRequest request) {
    String full = build(request);
    int end = full.indexOf('}');
    return full.substring(0, end + 1);
  }

  private static String namespaceMatcher(List<String> namespaces) {
    namespaces.forEach(SelectorBuilder::requireNamespace);
    if (namespaces.size() == 1) {
      return "namespace=\"" + namespaces.getFirst() + "\"";
    }
    return "namespace=~\"" + String.join("|", namespaces) + "\"";
  }

  private static void requireNamespace(String namespace) {
    if (namespace == null || !NAMESPACE.matcher(namespace).matches()) {
      // Authorization already rejects namespaces it does not know, so reaching
      // here means something unexpected is being asked for.
      throw new IllegalArgumentException("not a valid namespace name: " + namespace);
    }
  }

  /**
   * Converts a glob to an anchored regex, escaping everything that is not a
   * wildcard. {@code api-*} becomes {@code api\-.*}.
   */
  static String globToRegex(String glob) {
    if (glob.length() > MAX_GLOB_LENGTH) {
      throw new IllegalArgumentException("pattern is longer than " + MAX_GLOB_LENGTH + " characters");
    }
    StringBuilder regex = new StringBuilder();
    for (int i = 0; i < glob.length(); i++) {
      char c = glob.charAt(i);
      switch (c) {
        case '*' -> regex.append(".*");
        case '?' -> regex.append('.');
        default -> {
          if (Character.isLetterOrDigit(c)) {
            regex.append(c);
          } else {
            // Escaped for the regex, then again for the LogQL string literal.
            regex.append("\\\\").append(c);
          }
        }
      }
    }
    return regex.toString();
  }

  /** Escapes a value for use inside a LogQL double-quoted string. */
  static String escapeStringLiteral(String value) {
    if (value.length() > MAX_LINE_FILTER_LENGTH) {
      throw new IllegalArgumentException(
          "line filter is longer than " + MAX_LINE_FILTER_LENGTH + " characters");
    }
    return value.replace("\\", "\\\\").replace("\"", "\\\"");
  }

  private static boolean hasText(String value) {
    return value != null && !value.isBlank();
  }
}
