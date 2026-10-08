package com.altronixsoft.opp.platform.idempotency.testapp;

import com.altronixsoft.opp.platform.idempotency.Idempotent;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** {@code @Idempotent} on the class applies to every method; a method-level annotation overrides it. */
@RestController
@RequestMapping("/class-level")
@Idempotent(ttl = "PT1H")
public class ClassLevelController {

    private final DemoState state;

    public ClassLevelController(DemoState state) {
        this.state = state;
    }

    @PostMapping("/inherits")
    public ResponseEntity<String> inherits() {
        return ResponseEntity.ok("run " + state.executed("class-inherits"));
    }

    @PostMapping("/overrides")
    @Idempotent(required = false)
    public ResponseEntity<String> overrides() {
        return ResponseEntity.ok("run " + state.executed("class-overrides"));
    }
}
