# Pebble privacy policy

*Last updated: 4 October 2026 · applies to Pebble 0.1.x and later*

**Short version:** Pebble runs entirely on your computer. It has no account, no cloud service, no ads and no analytics. Nothing you type, say or do in Pebble is sent anywhere.

## What Pebble stores, and where

Everything lives in one folder on your PC: `%APPDATA%\Pebble` (for example `C:\Users\you\AppData\Roaming\Pebble`).

| What | Why | How to delete it |
|---|---|---|
| Notes and reminders you create | That's the app | Delete them in the app, or remove the folder |
| Your conversation with Pebble (what you typed or said, its replies) | So you can see it on the Chat page | Chat page → **Clear conversation** |
| Water log, reminder reactions (done / snoozed / skipped), when you're usually at your computer | To learn your habits: streaks, good times for reminders | Memory page → remove any memory; or remove the folder |
| "Did you mean…" picks and "Not what I meant" taps | To improve how Pebble understands you, if *you* choose to retrain the model | Remove the folder |
| What you watch (video app + title), **only if you turn it on** (Memory → "Notice what I watch", off by default) | So Pebble can mention it, and keep quiet during movies | Memory → **Clear watch history**, or turn it off |
| Voice clips, **only if you turn it on** (Memory → "Keep voice clips I correct", off by default), and only clips whose transcript you corrected | To tune speech recognition to your voice | Memory → **Delete voice clips** |
| Search index: for each note, each fact you told Pebble and each thing you said, a list of 384 numbers that the command model makes on your computer, and a copy of the text it was made from (to notice when you edit a note) | So Memory → **Search memory** (and "what did I note about …") can find them by meaning | Delete the note or fact, or clear the conversation. A deleted item never shows in search results, and its numbers and copy are removed at the next index pass. Or remove the folder |
| Settings (pet, sizes, toggles) | To remember your choices | Remove the folder |

## Microphone

- The microphone is **off** except while you hold Ctrl+Alt+Space or press the 🎤 button in Pebble. It is opened for that moment and closed right after.
- Speech is turned into text **on your computer** by local models (Dolphin and Whisper). The audio is then thrown away, unless you turned on "Keep voice clips I correct".
- You can switch voice off completely: Memory page → **Microphone** off. Pebble then never opens the microphone.

## What Pebble reads from your PC

- **The title of the window in front, every few seconds:** to stay quiet while you watch a video or use a fullscreen app. It's checked on the spot and not stored (unless "Notice what I watch" is on).
- **Keyboard/mouse idle time and battery state:** to let the pet sleep and save power. Not stored.
- **Nothing else.** No screenshots, no files, no browsing history, no contacts.

## Network

Pebble makes no network connections. The models ship inside the installer. If that ever changes (for example an optional "download a better model" button), it will be off by default, ask you first, and be described here.

## Children

Pebble collects nothing, so it has no special handling for children's data. There is nothing to collect.

## Open source

Every claim here can be checked in the code: <https://github.com/Wickedsoni/pebble>. If you find Pebble doing something this page doesn't say, please report it privately: <https://github.com/Wickedsoni/pebble/security/advisories/new>.

## Changes

Changes to this policy are made in the open, in this file's history on GitHub, and noted in the release notes.
