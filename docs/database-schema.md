# Database schema

**Database**: `speed_data_tracker_db` (Room, **version 3**,
`fallbackToDestructiveMigration(dropAllTables = true)`).

No Room schema change was needed for the speed-test, widget or export work: speed-test
results and display preferences live in DataStore (see the bottom of this file), the daily
table keeps exactly the same four real counters per transport, and the CSV export / widget
only *read* it.

`date` keys are always written with `Locale.US` digits (`DateFormatter.day()`), so a device
in a Hindi/Arabic-digit locale cannot split one day into two rows.

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
| `getByDate(date): Entity?` | one-shot read (service path, external-reset detection, widget refresh) |
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

## Who reads the table

| Reader | Path |
|---|---|
| Dashboard / History screens | `getDataByDate` / `getAllHistory` / `getThisMonthTotal` Flows |
| History → export | `UsageCsv.build(rows)` — header + one line per day, raw bytes, newest first; shared as text through `ACTION_SEND` (no file is written) |
| `TodayUsageWidget` | the service's in-memory `todayUsageFlow` while it runs, otherwise `getByDate(today)` from `WidgetRefreshWorker` (every 30 min) |
| `SpeedMeterTile` (Quick Settings) | `getByDate(today)` once per `onStartListening` (shade opened) → subtitle; never a live value |
| Status-bar notification | the service's own row (`todayUsageFlow`) |

## DataStore (not SQL, but persisted app state)

File `speed_meter_prefs.preferences_pb` (see `PreferencesManager`). The `DataStore`
instance is a constructor parameter (`PreferencesManager(context, store = context.dataStore)`),
so unit tests run against an isolated per-test file (`TestPreferences`) and never read or
write the real one.

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
| `test_server` | String | `AUTO` (default) / `CLOUDFLARE` / `LIBRESPEED` / `CUSTOM` — endpoint profile of the opt-in test; an unknown value behaves like `AUTO`, so a corrupt file can never make the test dead |
| `test_server_url` | String | base URL of the user's own endpoint (only used when `test_server == CUSTOM`); an unusable value falls back to the default host |
| `test_server_flavour` | String | `CLOUDFLARE` (`/__down?bytes=N`, `/__up`) or `LIBRESPEED` (`backend/garbage.php?ckSize=N`, `backend/empty.php`) — how the custom base URL is expanded |

Reads go through a `catch`-guarded flow, so a corrupt/IO-failing preferences file degrades
to defaults instead of crashing a collector.
