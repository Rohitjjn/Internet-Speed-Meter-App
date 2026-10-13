# Database schema

**Database**: `speed_data_tracker_db` (Room, **version 3**, `fallbackToDestructiveMigration`).

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
2. **Usage access not granted** — the 1 Hz ticker attributes each tick's
   real byte delta to the active transport into in-memory accumulators;
   the 15 s loop upserts those. Accumulators are re-seeded from the DB row
   after a service/process restart, and zeroed on midnight rollover or
   when the row is deleted externally (the "reset today" gesture).

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
