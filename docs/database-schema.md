# Database schema

**Database**: `speed_data_tracker_db` (Room, **version 3**, `fallbackToDestructiveMigration`).

No schema change was needed for the measurement-engine redesign: speed-test results and
display preferences live in DataStore (see the bottom of this file), and the daily table
keeps exactly the same four real counters per transport.

One table only — the app stores **real counters**, never simulated data.

## `daily_data_usage`

One row per calendar day (primary key = the date string).

| Column | Type | Notes |
|---|---|---|
| `date` | TEXT (PK) | `YYYY-MM-DD`, unique per day |
| `mobileRx` | INTEGER | bytes downloaded on cellular |
| `mobileTx` | INTEGER | bytes uploaded on cellular |
| `wifiRx` | INTEGER | bytes downloaded on Wi-Fi/Ethernet |
| `wifiTx` | INTEGER | bytes uploaded on Wi-Fi/Ethernet |

(Columns are named after the entity properties — no `@ColumnInfo` renames.)

Entity: `DataUsageEntity` with derived `mobileTotal`, `wifiTotal`, `total`.

## DAO (`DataUsageDao`)

| Method | Query |
|---|---|
| `upsertData(entity)` | `INSERT` with `OnConflictStrategy.REPLACE` |
| `getDataByDate(date): Flow<Entity?>` | live Flow for the dashboard cards |
| `getByDate(date): Entity?` | one-shot read (service path, external-reset detection) |
| `getAllHistory(): Flow<List<Entity>>` | all recorded days, newest first |
| `getThisMonthTotal(monthPrefix): Flow<Long>` | `COALESCE(SUM(mobileRx + mobileTx + wifiRx + wifiTx), 0) WHERE date LIKE 'YYYY-MM-%'` |
| `deleteByDate(date)` | "reset today" (integrated path re-seeds from the missing row) |
| `clearAll()` | Settings → wipe history |

## How rows are written

Only the foreground service writes this table (single writer, no contention):

1. **Usage access granted** — every 15 s the service reads
   `NetworkStatsManager.querySummaryForDevice` for cellular and
   Wi-Fi/Ethernet since midnight, subtracts the "reset today" baseline
   (stored in DataStore, not the DB), clamps at zero, and upserts the day.
2. **Usage access not granted** — the read loop (500 ms) hands every real byte delta to
   `SpeedEngine`, which attributes it to the transport that was active while it moved;
   the 15 s loop takes the per-transport delta since its previous pass and adds it to the
   in-memory accumulators, then upserts. A negative delta (service restart / source
   switch re-seeds the engine) is treated as "nothing countable" instead of a spike.
   Accumulators are re-seeded from the DB row after a service/process restart, and zeroed
   on midnight rollover or when the row is deleted externally (the "reset today" gesture).

## DataStore (not SQL, but persisted app state)

File `speed_meter_prefs.preferences_pb` (see `PreferencesManager`):

| Key | Type | Purpose |
|---|---|---|
| `is_dark_mode` | Boolean? | night-mode override; `null` = follow system |
| `mobile_limit` | Double | daily mobile limit value (`0` = no limit) |
| `mobile_limit_unit` | String | `GB` / `MB` / `KB` for the limit |
| `onboarding_completed` | Boolean | skip intro after first run |
| `reset_date` + `reset_mobile_rx/tx`, `reset_wifi_rx/tx` | String/Long | "reset today" baseline, captured when the user resets; only subtracted while `reset_date == today` |
| `last_dismissed_mobile_alert` + `last_dismissed_alert_date` | Long/String | rate-limit for the in-app limit alert |
| `speed_unit` | String | `BYTES` (KB/s · MB/s, default) or `BITS` (Kbps · Mbps) |
| `accent_color` | String | `INK` (default) / `GREEN` / `BLUE` / `VIOLET` — glass theme accent |
| `last_speed_down` + `last_speed_up` | Long/Long | last published (smoothed) speeds in B/s; written every 15 s so the status-bar number is meaningful immediately after a service start |
| `speed_test_results` | String | last 20 active speed tests, one per line: `epochMillis\|downloadBps\|uploadBps\|latencyMs\|jitterMs\|server` (defensive decode: malformed lines are dropped) |

Reads go through a `catch`-guarded flow, so a corrupt/IO-failing preferences file degrades
to defaults instead of crashing a collector.
