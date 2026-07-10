package pl.feelcodes.elevator.api.call;

import java.time.OffsetDateTime;

// A rolled-up view of one simulation run, read from the call_status model:
//   simSize   — how many calls the run asked for (echoed from the request)
//   calls     — how many of them have reached the read model so far
//   orders    — distinct orders those calls grouped into
//   doneCalls — calls already served (status DONE)
//   firstCall — when the earliest call of the run was created
//   lastDone  — when the most recent call of the run was served
public record SimProgress(
        int simSize,
        long calls,
        long orders,
        long doneCalls,
        OffsetDateTime firstCall,
        OffsetDateTime lastDone) {
}
