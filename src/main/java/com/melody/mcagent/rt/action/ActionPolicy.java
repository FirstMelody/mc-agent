package com.melody.mcagent.rt.action;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * What a bot is permitted to do.
 *
 * <p>Per the project's agreed safety posture, bots have the abilities of an ordinary survival
 * player plus a small allowlist of slash commands. They are deliberately <b>never</b> operators:
 * a hallucinating model must not be able to reshape the world with a single command.
 *
 * <p>Two independent gates apply:
 * <ol>
 *   <li>this policy, which decides which slash commands exist for a bot at all;</li>
 *   <li>the server's own permission system, which will still refuse anything requiring op.</li>
 * </ol>
 *
 * <p>Destructive actions can be disabled wholesale, so an operator can run bots in a purely
 * conversational or observational mode without editing code.
 */
public final class ActionPolicy {

    /**
     * Commands a bot may run by default. These are ordinary player conveniences that a normal
     * survival player could use; nothing here can alter the world or the server configuration.
     */
    public static final Set<String> DEFAULT_ALLOWED_COMMANDS = Set.of(
            "home", "sethome", "spawn", "msg", "tell", "w", "r", "list", "tps", "help");

    private final Set<String> allowedCommands;
    private final boolean allowBreakBlocks;
    private final boolean allowPlaceBlocks;
    private final boolean allowAttack;
    private final boolean allowContainers;

    private ActionPolicy(Set<String> allowedCommands,
                         boolean allowBreakBlocks,
                         boolean allowPlaceBlocks,
                         boolean allowAttack,
                         boolean allowContainers) {
        this.allowedCommands = allowedCommands;
        this.allowBreakBlocks = allowBreakBlocks;
        this.allowPlaceBlocks = allowPlaceBlocks;
        this.allowAttack = allowAttack;
        this.allowContainers = allowContainers;
    }

    /** The default posture: a normal survival player, with the command allowlist above. */
    public static ActionPolicy standard() {
        return new ActionPolicy(new LinkedHashSet<>(DEFAULT_ALLOWED_COMMANDS), true, true, true, true);
    }

    /**
     * Build a policy from explicit settings, as assembled from the server config.
     *
     * <p>Command names are normalised to lower case and stripped of any leading slash, so an
     * operator writing "/home" in the config gets the same behaviour as "home".
     */
    public static ActionPolicy custom(Collection<String> commands,
                                      boolean allowBreakBlocks,
                                      boolean allowPlaceBlocks,
                                      boolean allowAttack,
                                      boolean allowContainers) {
        Set<String> normalised = new LinkedHashSet<>();
        for (String command : commands) {
            String cleaned = command.trim().toLowerCase(Locale.ROOT);
            if (cleaned.startsWith("/")) {
                cleaned = cleaned.substring(1);
            }
            if (!cleaned.isEmpty()) {
                normalised.add(cleaned);
            }
        }
        return new ActionPolicy(normalised, allowBreakBlocks, allowPlaceBlocks, allowAttack, allowContainers);
    }

    /** Read-only posture: the bot may look, walk, talk and query, but not change the world. */
    public static ActionPolicy observeOnly() {
        return new ActionPolicy(new LinkedHashSet<>(DEFAULT_ALLOWED_COMMANDS), false, false, false, false);
    }

    public ActionPolicy withAllowedCommands(Set<String> commands) {
        Set<String> normalised = new LinkedHashSet<>();
        for (String command : commands) {
            normalised.add(command.toLowerCase(Locale.ROOT));
        }
        return new ActionPolicy(normalised, this.allowBreakBlocks, this.allowPlaceBlocks,
                this.allowAttack, this.allowContainers);
    }

    public boolean isCommandAllowed(String rootCommand) {
        return this.allowedCommands.contains(rootCommand.toLowerCase(Locale.ROOT));
    }

    public Set<String> allowedCommands() {
        return Set.copyOf(this.allowedCommands);
    }

    public boolean canBreakBlocks() {
        return this.allowBreakBlocks;
    }

    public boolean canPlaceBlocks() {
        return this.allowPlaceBlocks;
    }

    public boolean canAttack() {
        return this.allowAttack;
    }

    public boolean canUseContainers() {
        return this.allowContainers;
    }

    /** A human/LLM-readable summary of what this bot is allowed to do. */
    public String describe() {
        StringBuilder sb = new StringBuilder("Allowed: move, look, chat, query items and recipes");
        if (this.allowBreakBlocks) {
            sb.append(", break blocks");
        }
        if (this.allowPlaceBlocks) {
            sb.append(", place blocks");
        }
        if (this.allowAttack) {
            sb.append(", attack");
        }
        if (this.allowContainers) {
            sb.append(", open containers and use machines");
        }
        sb.append(". Commands: /").append(String.join(", /", this.allowedCommands));
        sb.append(". NOT an operator.");
        return sb.toString();
    }
}
