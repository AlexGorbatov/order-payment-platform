-- Stand-in for a service's own table, so tests can show that a business change and its event commit or roll back together.
CREATE TABLE demo_order (
    id uuid PRIMARY KEY
);
