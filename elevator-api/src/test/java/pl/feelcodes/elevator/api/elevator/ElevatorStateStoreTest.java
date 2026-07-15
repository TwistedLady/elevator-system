package pl.feelcodes.elevator.api.elevator;

import org.junit.jupiter.api.Test;
import pl.feelcodes.elevator.common.dto.ElevatorStateDto;
import reactor.core.Disposable;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ElevatorStateStoreTest {

    @Test
    void streamsEachStateChangeAndKeepsTheLatest() {
        ElevatorStateStore store = new ElevatorStateStore();
        ElevatorStateDto moving = new ElevatorStateDto("e1", "Up", "Moving", 3, false);
        ElevatorStateDto stopped = new ElevatorStateDto("e1", "Down", "Stopped", 7, false);

        List<ElevatorStateDto> seen = new ArrayList<>();
        Disposable sub = store.changes().subscribe(seen::add);

        store.put("e1", moving);
        store.put("e1", stopped);
        sub.dispose();

        assertThat(seen).containsExactly(moving, stopped);
        assertThat(store.get("e1")).contains(stopped);
        assertThat(store.all()).containsExactly(stopped);
    }

    @Test
    void ignoresNullNameOrState() {
        ElevatorStateStore store = new ElevatorStateStore();
        store.put(null, new ElevatorStateDto("e1", "Up", "Moving", 1, false));
        store.put("e1", null);
        assertThat(store.all()).isEmpty();
    }
}
