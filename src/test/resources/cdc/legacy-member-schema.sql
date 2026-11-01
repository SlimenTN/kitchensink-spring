-- Legacy tables as created by Hibernate in the kitchensink MySQL database
CREATE TABLE Member (
    id           BIGINT       NOT NULL,
    email        VARCHAR(255) NOT NULL,
    name         VARCHAR(25)  NOT NULL,
    phone_number VARCHAR(12)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_member_email (email)
);

-- Hibernate's emulated sequence for Member ids (one row). The legacy app reserves blocks of 50 from it, so
-- next_val runs ahead of the highest stored id.
CREATE TABLE Member_SEQ (
    next_val BIGINT
);
INSERT INTO Member_SEQ (next_val) VALUES (151);

-- legacy seed data, captured by the CDC initial snapshot
INSERT INTO Member (id, email, name, phone_number) VALUES (0, 'john.smith@mailinator.com', 'John Smith', '2125551212');
