# SMS Cleaner

Android utility app for bulk-deleting old SMS and MMS messages by date range. Supports manual one-shot cleanups and recurring scheduled jobs that run in the background.

## Requirements

- Android 14.0+ (API 34+)
- Android Studio (Ladybug or newer)
- Must be set as **default SMS app** to delete messages (Android requirement)

## Build & Run

1. Open this folder in Android Studio.
2. Let Gradle sync.
3. Connect a device or start an emulator (API 34+).
4. Click **Run**.

## Navigation

The app has a left-side navigation drawer with three tabs:

- **Manual Clean** — one-shot cleanup with dry run + delete.
- **Scheduled Clean** — list of recurring cleanup jobs.
- **Test Messages** — generate fake SMS/MMS for testing.

## Manual Clean

### Storage Status Card
Top of the screen shows current SMS count, MMS count, and `com.android.providers.telephony` storage size. Tap **Refresh** to update after a cleanup.

### Date Range
Set a start date, end date, or both:
- Start only → deletes messages *after* that date
- End only → deletes messages *before* that date
- Both → deletes messages *between* the dates
- Neither → not allowed

### Message Types
- **SMS** — standard text messages
- **MMS (media)** — messages with images, video, audio attachments
- **MMS (group chat)** — group conversations without media
- **RCS** — carrier-dependent; usually stored in SMS/MMS tables

### Exclusions
Tap **Manage Exclusions** to add phone numbers that should never be deleted. Pick from contacts or enter manually. Stored persistently in DataStore. Matches by normalized digits (trailing 10-digit compare handles `+1` prefix mismatches).

### Batch Settings
- **Batch Size** (default 1000) — messages processed per batch
- **Per-type batch sizes** (optional) — separate sizes for SMS, MMS media, MMS group
- **Delete Chunk Size** (default 50) — IDs per `applyBatch` call
- **Delay** (default 100ms) — pause between batches
- **Delete Order** — oldest first (default) or newest first
- **Debug Logging** — per-query and per-chunk timing in log
- **Auto-tune** — adjusts chunk size dynamically, targets 2 s/chunk (bounds 10-500)

### Workflow
1. Click **Dry Run** — scans and counts. Caches scan results in memory.
2. Review the log. Confirm counts and contact warnings.
3. Click **Delete Messages** — confirmation dialog shows total count and any messages involving saved contacts.
4. Progress bar shows live percent. Stop anytime.

Dry run cache is invalidated when date range, message types, or delete order change. Batch/chunk settings can be tuned without re-scanning.

## Scheduled Clean

Multiple independent schedules, each with its own name, recurrence, threshold, and batch settings.

### Create/Edit
From the list view, tap **New** (FAB) or an existing row to edit.

Fields:
- **Name** — free-form label
- **Enabled** — toggle to start/stop the schedule
- **Frequency** — Weekly, Every 2 Weeks, or Monthly
- **Day of Week / Day of Month** — depending on frequency
- **Notification Time** — hour and minute of day
- **Delete Threshold** — e.g., "2 Years" = delete messages older than 2 years ago at midnight
- **Message Types** — same as manual
- **Batch Settings** — same as manual
- **Only run when plugged in** — WorkManager `setRequiresCharging` constraint

### Execution
A `PeriodicWorkRequest` runs every 24 hours (with initial delay to match the configured time). On trigger, the worker:
1. Loads this schedule's config by id (from input data)
2. Checks if today matches the recurrence pattern
3. Checks that app is still default SMS — if not, shows a reminder notification and exits
4. Promotes to foreground with a persistent notification showing live progress
5. Runs `MessageCleaner` in delete mode with the configured end date
6. Persists `lastRunMs` on success

Each schedule has a unique work name (`scheduled_clean_$id`), so enabling/disabling one doesn't affect others.

### Caveats
- Must be default SMS app at run time. Reminder fires if not.
- `PeriodicWorkRequest` minimum interval is 15 minutes; actual trigger time may drift by up to an hour.
- Biweekly uses `lastRunMs` to skip weeks.

## Test Messages

From the drawer, pick **Test Messages**. Generates fake SMS and MMS in the phone's message database for testing:

- Counts for SMS, MMS (media), MMS (group)
- Date range for randomized timestamps
- Number of conversations (1:1) and group conversations
- Cancel mid-run

Requires default SMS app role (same as deletion).

## Permissions

| Permission | Purpose |
|---|---|
| READ_SMS | Scan message database |
| RECEIVE_SMS / SEND_SMS | Required for default SMS app role |
| RECEIVE_MMS / RECEIVE_WAP_PUSH | Required for default SMS app role |
| READ_CONTACTS | Resolve phone numbers to contact names |
| POST_NOTIFICATIONS | Scheduled cleanup notifications |
| FOREGROUND_SERVICE / FOREGROUND_SERVICE_DATA_SYNC | Keep scheduled work running |

## Architecture

- **Kotlin** + Coroutines for all async work
- **AndroidX Fragments** hosted by a `DrawerLayout` in `MainActivity`
- **AndroidX WorkManager** for scheduled jobs with foreground notification
- **DataStore Preferences** for persistent config (schedules as JSON array, exclusions as string set)
- **Material Design 3** components
- `MessageCleaner` is a reusable coroutine-based engine (scan → two-phase delete: whole-thread first, then per-message batched via `applyBatch`)
- Optimization highlights: bulk MMS classification (single query to parts table + conversations table), per-thread delete via `content://sms/conversations/$id`, cached scan results so delete run skips rescan, auto-tune chunk size

## Project Structure

```
app/src/main/java/com/smscleaner/app/
├── MainActivity.kt                     # Drawer nav host
├── SMSCleanerApplication.kt            # Notification channels
├── MessageCleaner.kt                   # Core scan/delete engine
├── CleanerViewModel.kt                 # Manual clean state
├── ContactResolver.kt                  # Phone → name
├── TestMessageGenerator.kt             # Fake message creation
├── fragment/
│   ├── ManualCleanFragment.kt          # Manual clean UI
│   ├── ScheduleListFragment.kt         # Schedule list with toggles
│   ├── ScheduledCleanFragment.kt       # Schedule editor
│   ├── TestMessagesFragment.kt         # Test generator UI
│   └── ExclusionsDialog.kt             # Manage excluded numbers
├── model/
│   └── ScheduleConfig.kt               # Schedule data class + JSON
├── schedule/
│   ├── SchedulePreferences.kt          # DataStore wrapper
│   ├── ExclusionPreferences.kt         # DataStore wrapper
│   ├── ScheduleManager.kt              # WorkManager orchestration
│   └── ScheduledCleanWorker.kt         # The background job
├── receiver/
│   ├── SmsReceiver.kt                  # Default SMS app requirement
│   └── MmsReceiver.kt                  # Default SMS app requirement
├── service/
│   └── HeadlessSmsSendService.kt       # Default SMS app requirement
└── activity/
    └── ComposeSmsActivity.kt           # Default SMS app requirement
```
