# Database Schema

The **Speed & Data** tracking application utilizes Android **Room Persistence Library** to manage daily data usage telemetry securely on-device.

## 1. Table: `daily_data_usage`
Stores compiled network usage totals for each calendar date.

| Field Name | Type | Key | Description |
| :--- | :--- | :--- | :--- |
| `id` | `Long` | Primary Key (Auto-Increment) | Identifies the telemetry row. |
| `date` | `String` | Unique / High-index | Standardized ISO-8601 representation: `YYYY-MM-DD`. |
| `mobileDataUsed` | `Long` | - | Compiled mobile cellular data used during the date (in Bytes). |
| `wifiDataUsed` | `Long` | - | Compiled WiFi network data used during the date (in Bytes). |
| `addOnDataUsed` | `Long` | - | Compiled active custom data add-ons used during the date (in Bytes). |

## 2. Room Infrastructure
- **DAO (`DataUsageDao`)**:
  - `insertData(data: DataUsageEntity)`: Inserts/Replaces telemetry rows on the worker thread.
  - `getDataByDate(date: String): Flow<DataUsageEntity?>`: Exposes live updates for a single target calendar day.
  - `getAllHistory(): Flow<List<DataUsageEntity>>`: Exposes a list of histories sorted from newest to oldest.
  - `getThisMonthTotal(monthPrefix: String): Flow<Long?>`: Calculates SQLite-level sum of all network types for date matches starting with the target prefix (e.g. `2026-05-%`).
- **Database Engine (`AppDatabase`)**:
  - Leverages version `1` database holding SQLite sessions safely through singleton design pattern configurations.
- **Repository Pattern (`DataUsageRepository`)**:
  - Acts as the isolated single point of contact between ViewModels and structural database drivers, completely hiding internal SQLite implementations.
