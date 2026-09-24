package org.example.diagnosis;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

public final class EvidenceBundle {

    private final List<Evidence> items;

    private EvidenceBundle(List<Evidence> items) {
        this.items = List.copyOf(items);
    }

    public static EvidenceBundle empty() {
        return new EvidenceBundle(List.of());
    }

    public static EvidenceBundle of(Evidence... evidence) {
        return new EvidenceBundle(List.of(evidence));
    }

    public static EvidenceBundle of(List<Evidence> evidence) {
        return new EvidenceBundle(evidence);
    }

    public EvidenceBundle plus(List<Evidence> extra) {
        List<Evidence> merged = new ArrayList<>(items);
        merged.addAll(extra);
        return new EvidenceBundle(merged);
    }

    public List<Evidence> items() {
        return items;
    }

    public boolean isEmpty() {
        return items.isEmpty();
    }

    public Optional<Evidence> find(String id) {
        return items.stream().filter(e -> e.id().equals(id)).findFirst();
    }
}
