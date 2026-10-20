# MergeSMS Sender

Android bulk SMS app with CSV merge. Based on
[Android-MassSmsSender](https://icllc.cc/devtool-android-masssmssender/) by Jacob "Isreal" Eiler
and Isreal Consulting, LLC (AGPL-3.0-or-later).

**WARNING:** messages are sent through your device's SMS service from your own number. Bulk
sending from non-registered numbers can get the number blocked or reported as spam, and your
carrier may bill every SMS segment. Use at your own risk and only message people who expect it.

## Features
- Import up to **1,000** numbers per batch from CSV (with or without a header row)
- Number validation and duplicate removal (`+1 555…` and `555…` count as the same number)
- Messages up to **500 characters**, with a segment/cost estimate
- **Personalization**: `{Name}`, `{First Name}`, `{Name|there}` (fallback), or any CSV column header, e.g. `{Company}`
- Preview (with merged samples) before sending; live progress; cancel; runs as a foreground service
- History of batches and CSV reports (exported via the system file picker)
- Pick single contacts (as in the original app)

## CSV format
```
name,phone,company
Alice Smith,+15551234567,Acme
Bob,555-987-6543,
```
Without a header, the first column that looks like a phone number is used, and the next column is the name.

## Build the APK with GitHub Actions
Push to GitHub. **Actions → Build APK → Run workflow** (also runs on every push to main/master).
Download the `MergeSMS-Sender-debug-apk` artifact, unzip, and install `app-debug.apk`
(allow "install unknown apps"). On first send, grant the SMS permission.

Local build: `./gradlew assembleDebug` (JDK 17, Android SDK 34).

## License
GNU Affero General Public License v3 or later. See LICENSE.
