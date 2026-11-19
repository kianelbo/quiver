package com.quiver.storage.wal;

import com.quiver.core.fsm.FsmDefinition;
import com.quiver.core.fsm.TransitionKey;
import com.quiver.core.instance.FsmInstance;
import com.quiver.core.instance.HistoryEntry;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Turns {@link FsmDefinition} and {@link FsmInstance} into the plain
 * {@code Map<String,Object>}/{@code List<Object>} shapes {@code Json} can write, and
 * back. Kept separate from the domain classes themselves so that neither {@code core}
 * nor the WAL format have to know about each other's internal representation choices.
 */
final class WalCodec {

    private WalCodec() {
    }

    static Map<String, Object> encodeDefinition(FsmDefinition def) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "DEFINE_FSM");
        m.put("name", def.name());
        m.put("states", List.copyOf(def.states()));
        m.put("initial", def.initialState());
        m.put("terminal", List.copyOf(def.terminalStates()));
        m.put("alphabet", List.copyOf(def.alphabet()));
        List<Object> transitions = new ArrayList<>();
        for (var entry : def.transitions().entrySet()) {
            TransitionKey key = entry.getKey();
            for (String to : entry.getValue()) {
                Map<String, Object> t = new LinkedHashMap<>();
                t.put("from", key.state());
                t.put("input", key.input());
                t.put("to", to);
                transitions.add(t);
            }
        }
        m.put("transitions", transitions);
        return m;
    }

    @SuppressWarnings("unchecked")
    static FsmDefinition decodeDefinition(Map<String, Object> m) {
        FsmDefinition.Builder b = FsmDefinition.builder((String) m.get("name"));
        for (Object s : (List<Object>) m.get("states")) b.states((String) s);
        b.initial((String) m.get("initial"));
        for (Object s : (List<Object>) m.get("terminal")) b.terminal((String) s);
        for (Object s : (List<Object>) m.get("alphabet")) b.alphabet((String) s);
        for (Object t : (List<Object>) m.get("transitions")) {
            Map<String, Object> tm = (Map<String, Object>) t;
            b.transition((String) tm.get("from"), (String) tm.get("input"), (String) tm.get("to"));
        }
        return b.build();
    }

    static Map<String, Object> encodeInstance(FsmInstance instance) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fsm", instance.fsmName());
        m.put("id", instance.instanceId());
        m.put("states", List.copyOf(instance.currentStates()));
        List<Object> history = new ArrayList<>();
        for (HistoryEntry h : instance.history()) {
            Map<String, Object> he = new LinkedHashMap<>();
            he.put("input", h.input());
            he.put("timestamp", h.timestamp().toString());
            he.put("states", List.copyOf(h.resultingStates()));
            history.add(he);
        }
        m.put("history", history);
        m.put("attributes", instance.attributes());
        m.put("version", instance.version());
        m.put("createdAt", instance.createdAt().toString());
        return m;
    }

    @SuppressWarnings("unchecked")
    static FsmInstance decodeInstance(Map<String, Object> m) {
        String fsmName = (String) m.get("fsm");
        String instanceId = (String) m.get("id");
        Map<String, Object> attributes = (Map<String, Object>) m.get("attributes");
        Instant createdAt = Instant.parse((String) m.get("createdAt"));

        // Reconstruct via the zero-history factory, then replay each history entry through
        // withTransition -- this keeps FsmInstance's own invariants (immutability, version
        // bumping) as the single source of truth for what a "valid" instance looks like,
        // rather than duplicating that logic here with a second constructor path.
        FsmInstance instance;
        List<Object> history = (List<Object>) m.get("history");
        if (history.isEmpty()) {
            String onlyState = ((List<Object>) m.get("states")).get(0).toString();
            instance = FsmInstance.created(fsmName, instanceId, onlyState, attributes, createdAt);
        } else {
            // The state the instance was created in isn't separately recorded in the log --
            // only history entries are. That's fine: withTransition() overwrites currentStates
            // wholesale, so any placeholder seed state works here; it never becomes visible.
            instance = FsmInstance.created(fsmName, instanceId, "__wal_seed__", attributes, createdAt);
            for (Object o : history) {
                Map<String, Object> he = (Map<String, Object>) o;
                String input = (String) he.get("input");
                Instant ts = Instant.parse((String) he.get("timestamp"));
                var states = ((List<Object>) he.get("states")).stream().map(Object::toString)
                        .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
                instance = instance.withTransition(input, states, ts);
            }
        }
        return instance;
    }
}
