-- Legacy Member table as created by Hibernate in the kitchensink MySQL database
CREATE TABLE Member (
    id           BIGINT       NOT NULL,
    email        VARCHAR(255) NOT NULL,
    name         VARCHAR(25)  NOT NULL,
    phone_number VARCHAR(12)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_member_email (email)
);

-- legacy seed data, captured by the CDC initial snapshot
INSERT INTO Member (id, email, name, phone_number) VALUES (0, 'john.smith@mailinator.com', 'John Smith', '2125551212');
