# Project Blueprint: Internet Speed & Data Tracker

## 1. Vision & Core Value
A premium, hyper-minimalist Android utility for measuring internet performance and tracking long-term data consumption. The design is tailored to look like a high-end luxury instrument, utilizing sharp geometry, light/dark contrast, and frictionless transitions.

## 2. Architecture: Kotlin + Jetpack Compose
- **Language**: Kotlin 100%
- **UI Framework**: Jetpack Compose (Material 3)
- **Conformant with MVVM Pattern**:
  - **View (Jetpack Compose)**: Stateless UI screens reacting to State Flow.
  - **ViewModel**: Standard Android architecture components exposing reactive screen state with Kotlin Flows.
  - **Repository Pattern**: Abstracting the source of data (network tests, local tracking, etc.).
  - **Local Persistence**: Full Android Room Database containing localized telemetry history, compiled daily bytes tracking, and month-to-date summarization.

## 3. Design System & Visual Guidelines
- **Color Palette**:
  - **Core Background**: Pure White (`#FFFFFF`) for Light Theme, Dark Black (`#0A0A0A`) for Dark Theme.
  - **Visual Containers/Surfaces**: Creamy White (`#F9F9FB`) / Dark Slate (`#161618`).
  - **Accent Elements**: Pitch Black (`#000000`) / Vibrant White (`#FFFFFF`).
  - **Specials**: Translucent Glass Gray (`rgba(0, 0, 0, 0.05)` or `rgba(255, 255, 255, 0.05)`) with rounded corners, providing a premium, glassy aesthetic.
- **Typography & Font Strategy**:
  - High-end technical instrument style.
  - Clean display titles paired with monospaced typography for speed numbers and exact data units (simulating measuring instruments).
- **Interactive States**:
  - Minimalist tactile animations with native, elegant Material ripple effects.
  - Custom fluid animations using spring behaviors with transitions under 300ms.

## 4. Initial Directory/Folder Structure
All Kotlin source code lives in `com.example` (retained namespace):
- `com.example`
  - `MainActivity.kt`: Single Activity hosting the compose navigation controller.
  - `data`
    - `PreferencesManager.kt`: Key-value storage utilizing Jetpack DataStore Preferences for key state markers.
    - `DataUsageEntity.kt`: Room database entity model for daily bandwidth volumes.
    - `DataUsageDao.kt`: SQLite queries and operations.
    - `AppDatabase.kt`: Main database coordinator.
    - `DataUsageRepository.kt`: Clean abstraction of the data storage system.
  - `ui`
    - `theme`: Theme parameters, color structures, custom fonts, typography styles.
    - `navigation`: Navigation definitions, routes, type-safe structures, and Bottom App Bar custom styling.
    - `screens`: Screen layouts and Views.
      - `splash`: Splash Screen displaying custom splash animations.
      - `dashboard`: Real-time network speed metrics mapping VM states.
      - `history`: Network test and data logs.
      - `settings`: Configurations and user preferences.
    - `components`: Reusable customized premium components (glassy buttons, modular cards, indicators).
    - `viewmodel`
      - `DashboardViewModel.kt`: Central state manager coordinating active monitoring periods and simulation tick loops.
      - `HistoryViewModel.kt`: Gathers data flows from the room database and structures them for rendering.

## 5. State Management & Real-time Interactivity
- **State Broker**: State data resides within `DashboardViewModel`. It connects seamlessly with Jetpack Compose views using Kotlin `StateFlow`.
- **DataStore Storage**: The `PreferencesManager` persistently stores critical variables such as `isAddOnActive` and historical values across applications resets using **DataStore Preferences**.
- **The Core Freeze/Resume Loop**:
  - Activated by toggling Card 3's "Start" Button, introducing an M3 Modal Confirmation.
  - Consenting updates `isAddOnActive = true`, turning on simulated active calculations for Card 3 while simultaneously freezing Card 1 (Mobile Data) into a partial-alpha, visually distinct "Paused" lock.
  - Toggling "Stop" undoes this relationship, restoring standard tracking metrics and resetting Card 3 calculations to `0 MB`.

## 6. Real-Time Tracking, Permissions & Background Engine
- **Required Platform Permissions**:
  - `android.permission.INTERNET`: For standard connection checking.
  - `android.permission.READ_PHONE_STATE`: To authenticate telephony configurations.
  - `android.permission.PACKAGE_USAGE_STATS`: System usage telemetry access.
  - `android.permission.FOREGROUND_SERVICE` / `android.permission.FOREGROUND_SERVICE_DATA_SYNC`: To run non-blocking persistent tasks beyond the lifecycle of current views.
  - `android.permission.POST_NOTIFICATIONS`: Visual status notification dispatching.
- **Background Foreground Service (`SpeedMeterService`)**:
  - A persistent, battery-optimized background service that measures connection bandwidth every 1 second based on global device packet counts with `TrafficStats`.
  - Exposes live values to view controllers via global thread-safe Kotlin `StateFlow` streams.
  - Displays a persistent, non-dismissible notification indicating speed.
- **Bandwidth Usage Engine (`NetworkStatsManager`)**:
  - Automatically queries cell and WiFi summaries transferred since midnight.
  - Includes high-reliability permission detection. If usage stats access is blocked by system policies, the UI seamlessly delegates tracking updates to elegant simulated traffic vectors.
  - Synchronizes all calculated values dynamically into the local Room database to maintain history charts across platform resets.

## 7. Settings Architecture, Dark Theme & Notification Alerts
- **Integrated Settings ViewModel & DataStore**:
  - `SettingsViewModel` acts as the MVVM coordinator for system configurations, binding changes in the layout directly with Kotlin state streams in the `PreferencesManager`.
- **System Settings Preferences Keys**:
  - `KEY_IS_DARK_MODE`: Custom theme overrides (resolving between pure Light or deep Dark Theme styles globally). Fallback respects physical system themes.
  - `KEY_MOBILE_LIMIT` & `KEY_MOBILE_LIMIT_UNIT`: Floating thresholds determining standard daily cell warning ceilings.
  - `KEY_ADDON_LIMIT` & `KEY_ADDON_LIMIT_UNIT`: Custom threshold limiting active Add-On cellular usage limits.
- **Notification Warnings & Threshold Triggers**:
  - **Status Updates Channel**: Low importance channel dispatching real-time warnings whenever total usage crosses 50% or 90% parameters of either standard or limit configurations.
  - **High-Alert Channel**: Registered with high urgency (`IMPORTANCE_HIGH`) utilizing audio and custom vibration buzzers to instantly post alarm alerts whenever usage crosses 100% of limits with the exact text: "Data Limit Reached! You have used 100% of your allocated [Mobile/Add-On] data."


