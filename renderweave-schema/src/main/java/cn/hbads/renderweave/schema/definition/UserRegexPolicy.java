package cn.hbads.renderweave.schema.definition;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/** Conservative Java/ECMAScript intersection and catastrophic-backtracking guard. */
final class UserRegexPolicy {

    private static final String JAVA_ONLY_ESCAPES = "AZzGRXQEhHvV";

    private UserRegexPolicy() {
    }

    static Result inspect(String source) {
        var safetyProblem = unsafeReason(source);
        if (safetyProblem.isPresent()) {
            return new Result(null, "REGEX_UNSAFE", safetyProblem.orElseThrow());
        }
        try {
            return new Result(Pattern.compile(source), null, null);
        } catch (PatternSyntaxException exception) {
            return new Result(null, "REGEX_INVALID", "pattern is not valid in the supported regex grammar");
        }
    }

    private static Optional<String> unsafeReason(String source) {
        var groups = new ArrayDeque<GroupFrame>();
        var inClass = false;
        var escaped = false;

        for (int index = 0; index < source.length(); index++) {
            var current = source.charAt(index);
            if (escaped) {
                if (Character.isDigit(current) || current == 'k') {
                    return Optional.of("backreferences are not supported");
                }
                if (JAVA_ONLY_ESCAPES.indexOf(current) >= 0) {
                    return Optional.of("Java-specific character escapes are not supported");
                }
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (current == '[') {
                inClass = true;
                continue;
            }
            if (current == ']' && inClass) {
                inClass = false;
                continue;
            }
            if (inClass) {
                if (current == '&' && index + 1 < source.length() && source.charAt(index + 1) == '&') {
                    return Optional.of("character-class intersection is not supported");
                }
                continue;
            }

            if (current == '(') {
                if (index + 1 < source.length() && source.charAt(index + 1) == '?'
                        && !(index + 2 < source.length() && source.charAt(index + 2) == ':')) {
                    return Optional.of("lookaround, inline flags, named groups and recursive groups are not supported");
                }
                groups.push(new GroupFrame(index));
                continue;
            }
            if (current == '|') {
                if (!groups.isEmpty()) {
                    groups.peek().hasAlternation = true;
                }
                continue;
            }
            if (current == ')' && !groups.isEmpty()) {
                var group = groups.pop();
                var quantifier = quantifierAfter(source, index + 1);
                if (quantifier.unbounded()
                        && (group.hasQuantifier || ambiguousAlternation(source, group.startIndex + 1, index))) {
                    return Optional.of("nested or ambiguous repetition is not supported");
                }
                if (group.hasQuantifier && !groups.isEmpty()) {
                    groups.peek().hasQuantifier = true;
                }
                continue;
            }

            var quantifier = quantifierAt(source, index);
            if (quantifier.present()) {
                if (quantifier.possessive()) {
                    return Optional.of("possessive quantifiers are not supported");
                }
                if (!groups.isEmpty()) {
                    groups.peek().hasQuantifier = true;
                }
                index = quantifier.endIndex();
            }
        }
        return Optional.empty();
    }

    private static boolean ambiguousAlternation(String source, int start, int end) {
        var content = source.substring(start, end);
        if (content.startsWith("?:")) {
            content = content.substring(2);
        }
        var alternatives = splitTopLevelAlternatives(content);
        if (alternatives.size() < 2) {
            return false;
        }
        for (int left = 0; left < alternatives.size(); left++) {
            for (int right = left + 1; right < alternatives.size(); right++) {
                var first = alternatives.get(left);
                var second = alternatives.get(right);
                if (first.isEmpty() || second.isEmpty()
                        || first.startsWith(second) || second.startsWith(first)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static List<String> splitTopLevelAlternatives(String value) {
        var alternatives = new ArrayList<String>();
        var escaped = false;
        var inClass = false;
        var depth = 0;
        var start = 0;
        for (int index = 0; index < value.length(); index++) {
            var current = value.charAt(index);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
            } else if (current == '[') {
                inClass = true;
            } else if (current == ']') {
                inClass = false;
            } else if (!inClass && current == '(') {
                depth++;
            } else if (!inClass && current == ')') {
                depth--;
            } else if (!inClass && depth == 0 && current == '|') {
                alternatives.add(value.substring(start, index));
                start = index + 1;
            }
        }
        alternatives.add(value.substring(start));
        return alternatives;
    }

    private static Quantifier quantifierAfter(String source, int index) {
        return index >= source.length() ? Quantifier.NONE : quantifierAt(source, index);
    }

    private static Quantifier quantifierAt(String source, int index) {
        var current = source.charAt(index);
        if (current == '*' || current == '+') {
            var possessive = index + 1 < source.length() && source.charAt(index + 1) == '+';
            return new Quantifier(true, true, possessive, possessive ? index + 1 : index);
        }
        if (current == '?') {
            var possessive = index + 1 < source.length() && source.charAt(index + 1) == '+';
            return new Quantifier(true, false, possessive, possessive ? index + 1 : index);
        }
        if (current != '{') {
            return Quantifier.NONE;
        }
        var close = source.indexOf('}', index + 1);
        if (close < 0) {
            return Quantifier.NONE;
        }
        var body = source.substring(index + 1, close);
        if (!body.matches("[0-9]+(?:,[0-9]*)?")) {
            return Quantifier.NONE;
        }
        var unbounded = body.endsWith(",");
        var possessive = close + 1 < source.length() && source.charAt(close + 1) == '+';
        return new Quantifier(true, unbounded, possessive, possessive ? close + 1 : close);
    }

    record Result(Pattern pattern, String code, String message) {
        boolean valid() {
            return pattern != null;
        }
    }

    private static final class GroupFrame {
        private final int startIndex;
        private boolean hasQuantifier;
        @SuppressWarnings("unused")
        private boolean hasAlternation;

        private GroupFrame(int startIndex) {
            this.startIndex = startIndex;
        }
    }

    private record Quantifier(boolean present, boolean unbounded, boolean possessive, int endIndex) {
        private static final Quantifier NONE = new Quantifier(false, false, false, -1);
    }
}
