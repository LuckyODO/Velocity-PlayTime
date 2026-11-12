package _1ms.playtime.Rewards;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/** Only the context-free, permanent LuckPerms command has these semantics. */
public record PermissionReward(String permission, boolean value) {
    private static final Pattern COMMAND = Pattern.compile(
            "(?i)^lpv\\s+user\\s+%(?:player|uuid)%\\s+permission\\s+set\\s+([a-z0-9_.:*\\-]+)(?:\\s+(true|false))?$");

    public static Optional<PermissionReward> parse(String command) {
        var match = COMMAND.matcher(command);
        if(!match.matches()) return Optional.empty();
        return Optional.of(new PermissionReward(match.group(1).toLowerCase(Locale.ROOT),
                !"false".equalsIgnoreCase(match.group(2))));
    }
}
