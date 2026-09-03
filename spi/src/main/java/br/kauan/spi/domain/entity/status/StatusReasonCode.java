package br.kauan.spi.domain.entity.status;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

public record StatusReasonCode(String value) {
    private static final Pattern VALID = Pattern.compile("[A-Z0-9]{1,4}");

    public StatusReasonCode {
        value = value == null ? null : value.trim().toUpperCase(Locale.ROOT);
        if (value == null || !VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid status reason code: " + value);
        }
    }

    public static StatusReasonCode of(String value) {
        return new StatusReasonCode(value);
    }

    public static List<StatusReasonCode> normalize(List<StatusReasonCode> codes) {
        if (codes == null || codes.isEmpty()) {
            return List.of();
        }

        return codes.stream()
                .map(code -> Objects.requireNonNull(code, "Status reason code cannot be null"))
                .distinct()
                .sorted(Comparator.comparing(StatusReasonCode::value))
                .toList();
    }
}
