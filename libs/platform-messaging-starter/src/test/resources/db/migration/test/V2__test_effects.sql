-- Business side effect of the test event handler: one row per execution of the handler's logic.
CREATE TABLE demo_effect (
    id       bigserial PRIMARY KEY,
    event_id uuid NOT NULL
);
