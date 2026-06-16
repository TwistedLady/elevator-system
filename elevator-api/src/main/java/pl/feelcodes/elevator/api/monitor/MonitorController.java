package pl.feelcodes.elevator.api.monitor;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Monitor endpoint: returns the latest known state for an elevator as JSON,
 * or 404 if nothing has been seen yet.
 *
 *   GET /api/elevator/{name}  ->  {"tag":...,"elevatorName":...,"direction":...,"motion":...,"floor":N}
 */
@RestController
@RequestMapping("/api/elevator")
class MonitorController {

    private final StateStore store;

    MonitorController(StateStore store) {
        this.store = store;
    }

    /** List the latest state of every known elevator as a JSON array. */
    @GetMapping
    public ResponseEntity<String> all() {
        String body = "[" + String.join(",", store.all()) + "]";
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(body);
    }

    @GetMapping("/{name}")
    public ResponseEntity<String> latest(@PathVariable("name") String name) {
        return store.get(name)
                .map(json -> ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(json))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
