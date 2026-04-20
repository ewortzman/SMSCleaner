# SMS Cleaner

Android utility app for bulk-deleting old SMS and MMS messages by date range.

## Requirements

- Android 14.0+ (API 34+)
- Android Studio (Ladybug or newer recommended)
- Must be set as **default SMS app** to delete messages (Android requirement)

## Build & Run

1. Open this folder in Android Studio
2. Let Gradle sync complete
3. Connect a device or start an emulator (API 34+)
4. Click **Run**

## How It Works

### Setting Up

When launched, the app will:
1. Request SMS, MMS, and Contacts permissions
2. Prompt you to set it as the default SMS app (required for write/delete access)

> **Important:** While set as default, this app handles incoming SMS. It stores them but does not provide a full messaging UI. Switch back to your regular SMS app when done.

### Cleaning Messages

1. **Date Range** — Set a start date, end date, or both:
   - Start only: deletes messages *after* that date
   - End only: deletes messages *before* that date
   - Both: deletes messages *between* the dates
   - Neither: not allowed (at least one required)

2. **Message Types** — Check which types to target:
   - **SMS** — Standard text messages
   - **MMS (media)** — Messages with pictures, videos, or audio
   - **MMS (group chat)** — Group text messages without media
   - **RCS** — Rich Communication Services (carrier-dependent)

3. **Batch Settings**:
   - **Batch Size** (default 1000) — Messages processed per batch
   - **Delay** (default 500ms) — Pause between batches to avoid system overload

4. **Dry Run** — Always run this first. Scans and counts messages without deleting. Shows per-conversation breakdown with contact names.

5. **Delete Messages** — Only enabled after a successful dry run. Disabled again if any settings change.

### Test Message Generator

Access via the **"Generate Test Messages"** button. Creates fake SMS and MMS messages in the phone's message database for testing:

- Configure counts for SMS, MMS (media), and MMS (group) messages
- Set a date range for randomized timestamps
- Choose how many conversations to spread messages across

## Permissions

| Permission | Purpose |
|---|---|
| READ_SMS | Read message database for scanning |
| RECEIVE_SMS | Required for default SMS app |
| SEND_SMS | Required for default SMS app |
| RECEIVE_MMS | Required for default SMS app |
| RECEIVE_WAP_PUSH | Required for default SMS app |
| READ_CONTACTS | Resolve phone numbers to contact names |

## Architecture

- **Kotlin** with coroutines for background processing
- **MVVM** with AndroidX ViewModel + LiveData
- **Material Design 3** components
- No external dependencies beyond AndroidX and Material

## Project Structure

```
app/src/main/java/com/smscleaner/app/
├── MainActivity.kt          # Main cleaner UI
├── TestActivity.kt          # Test message generator UI
├── CleanerViewModel.kt      # State management
├── MessageCleaner.kt        # Core scan/delete logic
├── ContactResolver.kt       # Phone number → contact name
├── TestMessageGenerator.kt  # Fake message creation
├── receiver/
│   ├── SmsReceiver.kt       # SMS receive handler (default SMS requirement)
│   └── MmsReceiver.kt       # MMS receive handler (default SMS requirement)
├── service/
│   └── HeadlessSmsSendService.kt  # Quick-reply service (default SMS requirement)
└── activity/
    └── ComposeSmsActivity.kt      # Compose stub (default SMS requirement)
```
