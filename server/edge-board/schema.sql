CREATE TABLE IF NOT EXISTS edges (
  net     TEXT NOT NULL,
  proto   TEXT NOT NULL,
  fam     TEXT NOT NULL,
  addr    TEXT NOT NULL,
  port    INTEGER NOT NULL,
  rtt     INTEGER NOT NULL,
  kbps    INTEGER NOT NULL,
  up      INTEGER NOT NULL DEFAULT 0,
  n       INTEGER NOT NULL DEFAULT 1,
  updated INTEGER NOT NULL,
  PRIMARY KEY (net, proto, fam, addr, port)
);
CREATE INDEX IF NOT EXISTS edges_rank ON edges (net, proto, fam, kbps DESC);
CREATE INDEX IF NOT EXISTS edges_pool ON edges (proto, fam, updated);

-- Public V2Ray configs as Freeport users found them: one row per config, shared by everyone.
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
