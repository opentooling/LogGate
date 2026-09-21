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

  /** Regex metacharacters, the only characters a literal needs escaping from. */
  private static final String REGEX_META = "\\.+*?()|[]{}^$";

  /** A Prometheus label name, which is all a configured cluster label can be. */
  private static final Pattern LABEL_NAME = Pattern.compile("[a-zA-Z_][a-zA-Z0-9_]*");

  /** The stream selector, plus any line filter, for {@code request}. */
  public static String build(ExportRequest request) {
    return build(request, "");
  }

  /** The selector without any line filter, which is what the volume API sizes. */
  public static String buildStreamSelector(ExportRequest request) {
    return buildStreamSelector(request, "");
  }

  /**
   * The stream selector, plus any line filter, for {@code request}, with its
   * clusters matched on {@code clusterLabel} when one is configured.
   */
  public static String build(ExportRequest request, String clusterLabel) {
    StringBuilder selector = new StringBuilder("{");
    if (clusterLabel != null && !clusterLabel.isBlank() && !request.clusters().isEmpty()) {
      selector.append(clusterMatcher(clusterLabel, request.clusters())).append(", ");
    }
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
  public static String buildStreamSelector(ExportRequest request, String clusterLabel) {
    String full = build(request, clusterLabel);
    // Cluster values are escaped, so the first unescaped closing brace ends the
    // stream selector; a brace inside a quoted value is preceded by a backslash.
    int end = streamSelectorEnd(full);
    return full.substring(0, end + 1);
  }

  static int streamSelectorEnd(String selector) {
    boolean quoted = false;
    for (int i = 0; i < selector.length(); i++) {
      char c = selector.charAt(i);
      if (c == '\\') {
        i++;
      } else if (c == '"') {
        quoted = !quoted;
      } else if (c == '}' && !quoted) {
        return i;
      }
    }
    throw new IllegalStateException("selector has no end: " + selector);
  }

  /** A stream selector for every stream from {@code clusters}. */
  public static String clusterSelector(String clusterLabel, List<String> clusters) {
    return "{" + clusterMatcher(clusterLabel, clusters) + "}";
  }

  private static String clusterMatcher(String clusterLabel, List<String> clusters) {
    if (!LABEL_NAME.matcher(clusterLabel).matches()) {
      throw new IllegalStateException("not a valid label name: " + clusterLabel);
    }
    if (clusters.size() == 1) {
      return clusterLabel + "=\"" + escapeStringLiteral(clusters.getFirst()) + "\"";
    }
    List<String> alternatives = clusters.stream().map(SelectorBuilder::regexLiteral).toList();
    return clusterLabel + "=~\"" + String.join("|", alternatives) + "\"";
  }

  /**
   * A value matched literally inside a regex, then escaped for the LogQL
   * string it sits in. Only metacharacters are escaped, so a cluster name
   * reads the same in the generated query as it does in the label.
   */
  static String regexLiteral(String value) {
    if (value.length() > MAX_GLOB_LENGTH) {
      throw new IllegalArgumentException("value is longer than " + MAX_GLOB_LENGTH + " characters");
    }
    StringBuilder literal = new StringBuilder();
    for (char c : value.toCharArray()) {
      if (REGEX_META.indexOf(c) >= 0) {
        literal.append("\\\\").append(c);
      } else if (c == '"') {
        literal.append("\\\"");
      } else {
        literal.append(c);
      }
    }
    return literal.toString();
  }

  private static String namespaceMatcher(List<String> namespaces) {
    if (namespaces.isEmpty()) {
      // Every namespace. A stream selector must match something non-empty, and
      // this also keeps every export to streams that carry a namespace, which
      // is what the size breakdown and the files are organised by.
      return "namespace=~\".+\"";
    }
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
