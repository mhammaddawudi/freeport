-- Adds the upload rate to a board created before uploads were measured.
ALTER TABLE edges ADD COLUMN up INTEGER NOT NULL DEFAULT 0;
