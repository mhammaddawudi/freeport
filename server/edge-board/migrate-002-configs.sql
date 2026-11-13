CREATE TABLE IF NOT EXISTS configs (
  key     TEXT PRIMARY KEY,
  link    TEXT NOT NULL,
  delay   INTEGER NOT NULL,
  kbps    INTEGER NOT NULL,
  ok      INTEGER NOT NULL DEFAULT 0,
  fail    INTEGER NOT NULL DEFAULT 0,
  updated INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS configs_rank ON configs (updated, kbps DESC);
