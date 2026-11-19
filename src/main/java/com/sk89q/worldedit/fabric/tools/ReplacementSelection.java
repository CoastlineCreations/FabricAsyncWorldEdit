package com.sk89q.worldedit.fabric.tools;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;

/** Draft selection becomes an edit request only after two distinct valid choices. */
final class ReplacementSelection {
    record Request(String from, String to) { }

    private final Set<String> choices;
    private String source;
    private boolean finished;

    ReplacementSelection(Collection<String> choices) {
        this.choices = Set.copyOf(choices);
    }

    Optional<Request> pick(String key) {
        if (finished || !choices.contains(key)) return Optional.empty();
        if (source == null) {
            source = key;
            return Optional.empty();
        }
        if (source.equals(key)) {
            source = null;
            return Optional.empty();
        }
        finished = true;
        return Optional.of(new Request(source, key));
    }

    void clear() {
        if (!finished) source = null;
    }

    boolean cancel() {
        if (finished) return false;
        source = null;
        finished = true;
        return true;
    }

    String source() { return source; }
    boolean finished() { return finished; }
}
