"""Pebble's own action vocabulary and how MASSIVE's 60 intents map onto it.

The model is trained on all 60 MASSIVE intents (broad language understanding is the point of the
common base); the app only acts on the Pebble actions below. Pebble-only actions that MASSIVE has no
data for are served by the rule parser today and by few-shot prototypes in M2.
"""

MASSIVE_TO_PEBBLE = {
    "calendar_set": "remind",
    "alarm_set": "remind",
    "calendar_query": "reminders_query",
    "alarm_query": "reminders_query",
    "calendar_remove": "reminder_remove",
    "alarm_remove": "reminder_remove",
    "lists_createoradd": "add_note",
    "lists_query": "notes_query",
    "lists_remove": "note_remove",
    "datetime_query": "time_query",
    "general_greet": "chitchat",
    "general_joke": "chitchat",
    "general_quirky": "chitchat",
}

#: Actions the M1 encoder can produce via the mapping above (anything unmapped becomes "other").
MODEL_ACTIONS = sorted(set(MASSIVE_TO_PEBBLE.values()) | {"other"})

#: Pebble-only actions: no MASSIVE data. Rules today, few-shot prototypes in M2.
PEBBLE_ONLY_ACTIONS = ["log_water", "remember_fact", "set_interval", "mood"]


def to_pebble(massive_intent: str) -> str:
    return MASSIVE_TO_PEBBLE.get(massive_intent, "other")
