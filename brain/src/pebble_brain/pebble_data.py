"""Pebble's own training data: template sentences in English, Roman Hindi and Devanagari.

MASSIVE is a generic voice-assistant corpus: it has 28 English greetings, almost no notes-app phrasing and
no chat-style Hinglish. This module fills those gaps with templates written in MASSIVE's annotation format
("[time : 5 baje]"), labelled with the same 60 intents and slot types, so the model and app stay unchanged.

Rules:
  * Never copy eval sentences. `generate()` drops anything whose word overlap with eval v0/v1 is ≥ 0.6.
  * Roman Hindi gets chat spelling noise (`chatify`): hai → h, karna → krna, raha → rha …
"""

from __future__ import annotations

import json
import pathlib
import random
import re

from .massive import Example, parse_annotated

ROOT = pathlib.Path(__file__).resolve().parents[2]

# --------------------------------------------------------------------------- chat spelling

_CHAT = {
    "hai": ["h", "hai", "he", "hai"],
    "hain": ["h", "hain", "hai"],
    "karna": ["krna", "karna"],
    "karo": ["kro", "karo"],
    "kar": ["kr", "kar"],
    "karke": ["krke", "karke"],
    "raha": ["rha", "raha"],
    "rahi": ["rhi", "rahi"],
    "rahe": ["rhe", "rahe"],
    "nahi": ["nhi", "nahi", "nai", "nahin"],
    "nahin": ["nhi", "nahi"],
    "baje": ["bje", "baje"],
    "mein": ["me", "mai", "mein", "mei"],
    "main": ["mai", "main", "me"],
    "kya": ["kya", "kyaa", "kia"],
    "kyun": ["kyu", "kyun", "q"],
    "mujhe": ["mujhe", "mjhe", "muje"],
    "kuch": ["kuch", "kch", "kuchh"],
    "kaise": ["kaise", "kese", "kaisay"],
    "yaar": ["yaar", "yr", "yar"],
    "please": ["pls", "plz", "please"],
    "dena": ["dena", "dna"],
    "diya": ["diya", "dia"],
    "liya": ["liya", "lia"],
    "kaam": ["kaam", "kam"],
    "abhi": ["abhi", "abi"],
    "bhai": ["bhai", "bro"],
    "theek": ["theek", "thik", "tik"],
    "acha": ["acha", "accha", "achha"],
    "dilana": ["dilana", "dilaana"],
    "aaj": ["aaj", "aj"],
}


def chatify(tokens: list[str], rng: random.Random, p: float = 0.5) -> list[str]:
    """Chat spelling for Roman Hindi words; one choice per word so slot labels stay aligned."""
    return [rng.choice(_CHAT[t.lower()]) if t.lower() in _CHAT and rng.random() < p else t for t in tokens]


# --------------------------------------------------------------------------- fillers

EN = {
    "time": ["5 pm", "7pm", "6:30", "10 am", "9", "half past four", "8:15 am", "noon", "11 pm", "quarter to six", "3"],
    "in": ["in 10 mins", "in 20 minutes", "in an hour", "in half an hour", "in 2 hours", "in 45 min", "in 5 mins"],
    "date": [
        "tomorrow",
        "tmrw",
        "today",
        "monday",
        "on tuesday",
        "wednesday",
        "thursday",
        "on friday",
        "saturday",
        "sunday",
        "next monday",
        "next week",
        "day after tomorrow",
        "the 20th",
        "tonight",
    ],
    "tod": ["morning", "evening", "tonight", "afternoon", "night"],
    "task": [
        "call mom",
        "submit the assignment",
        "pay the electricity bill",
        "drink water",
        "take my meds",
        "study for the exam",
        "go to the gym",
        "book the train ticket",
        "reply to the professor",
        "recharge my phone",
        "buy groceries",
        "water the plants",
        "send the report",
        "practice coding",
        "clean my room",
        "renew the library book",
    ],
    "event": [
        "dbms viva",
        "team meeting",
        "project review",
        "dentist appointment",
        "mid sem exam",
        "lab practical",
        "interview",
        "standup",
        "maths class",
        "doctor visit",
        "birthday party",
        "football practice",
    ],
    "item": ["milk", "eggs", "bread", "maggi", "a notebook", "pens", "shampoo", "coffee", "bananas", "a charger"],
    "list": ["shopping", "grocery", "to do", "college", "packing"],
    "idea": [
        "ask sir about the marks",
        "start the project report",
        "check the hostel fee",
        "fix the login bug",
        "read chapter 5",
        "update my resume",
        "email the hod",
        "watch the lecture recording",
    ],
}

RO = {
    "time": [
        "5 baje",
        "7 baje",
        "saade 6 baje",
        "10 baje",
        "9 baje",
        "sava 4 baje",
        "paune 8 baje",
        "dedh baje",
        "dhai baje",
        "8:30 baje",
        "6 bje",
        "11 baje",
    ],
    "in": [
        "10 minute baad",
        "aadhe ghante baad",
        "ek ghante baad",
        "20 min baad",
        "2 ghante mein",
        "15 minute mein",
        "thodi der mein",
    ],
    "date": [
        "kal",
        "aaj",
        "parso",
        "somvar ko",
        "mangalvar",
        "budhvar ko",
        "guruvar",
        "shukravar ko",
        "shanivar",
        "ravivar ko",
        "monday ko",
        "friday ko",
        "agle hafte",
        "agle somvar",
        "20 tareekh ko",
    ],
    "tod": ["subah", "shaam", "raat", "dopahar", "shaam ko", "raat ko", "subah subah"],
    "task": [
        "mummy ko call karna",
        "assignment submit karna",
        "bijli ka bill bharna",
        "paani peena",
        "dawai leni",
        "padhai karni",
        "gym jana",
        "ticket book karni",
        "sir ko mail karna",
        "phone recharge karna",
        "sabzi lana",
        "paudhon ko paani dena",
        "report bhejna",
        "coding practice karni",
        "kamra saaf karna",
        "kitaab lautani",
    ],
    "event": [
        "dbms viva",
        "meeting",
        "project review",
        "dentist",
        "exam",
        "lab practical",
        "interview",
        "class",
        "doctor",
        "birthday party",
        "practice",
    ],
    "item": ["doodh", "ande", "bread", "maggi", "copy", "pen", "shampoo", "chai patti", "kele", "atta", "chawal"],
    "list": ["shopping", "saaman", "kaam", "college", "packing"],
    "idea": [
        "sir se marks puchna",
        "project report shuru karni",
        "hostel fee check karni",
        "login bug theek karna",
        "chapter 5 padhna",
        "resume update karna",
        "lecture recording dekhni",
    ],
}

DE = {
    "time": ["5 बजे", "सात बजे", "साढ़े छह बजे", "दस बजे", "नौ बजे", "सवा चार बजे", "पौने आठ बजे", "डेढ़ बजे", "ढाई बजे", "11 बजे"],
    "in": ["दस मिनट बाद", "आधे घंटे बाद", "एक घंटे बाद", "बीस मिनट बाद", "दो घंटे में", "पंद्रह मिनट में", "थोड़ी देर में"],
    "date": [
        "कल",
        "आज",
        "परसों",
        "सोमवार को",
        "मंगलवार",
        "बुधवार को",
        "गुरुवार",
        "शुक्रवार को",
        "शनिवार",
        "रविवार को",
        "अगले हफ्ते",
        "अगले सोमवार",
        "बीस तारीख को",
    ],
    "tod": ["सुबह", "शाम", "रात", "दोपहर", "शाम को", "रात को"],
    "task": [
        "मम्मी को फोन करना",
        "असाइनमेंट जमा करना",
        "बिजली का बिल भरना",
        "पानी पीना",
        "दवाई लेनी",
        "पढ़ाई करनी",
        "जिम जाना",
        "टिकट बुक करनी",
        "सर को मेल करना",
        "फोन रिचार्ज करना",
        "सब्ज़ी लानी",
        "पौधों को पानी देना",
        "रिपोर्ट भेजनी",
    ],
    "event": ["DBMS वाइवा", "मीटिंग", "प्रोजेक्ट रिव्यू", "डेंटिस्ट", "परीक्षा", "लैब प्रैक्टिकल", "इंटरव्यू", "क्लास", "डॉक्टर", "बर्थडे पार्टी"],
    "item": ["दूध", "अंडे", "ब्रेड", "मैगी", "कॉपी", "पेन", "शैम्पू", "चायपत्ती", "केले", "आटा", "चावल"],
    "list": ["शॉपिंग", "सामान", "काम", "कॉलेज"],
    "idea": ["सर से मार्क्स पूछना", "प्रोजेक्ट रिपोर्ट शुरू करनी", "हॉस्टल फीस देखनी", "चैप्टर पांच पढ़ना", "रिज्यूमे अपडेट करना"],
}

# Slot each filler carries ("" = plain words, no slot).
SLOT = {
    "time": "time",
    "in": "time",
    "date": "date",
    "tod": "timeofday",
    "event": "event_name",
    "list": "list_name",
    "task": "",
    "item": "",
    "idea": "",
}

# --------------------------------------------------------------------------- templates  (intent, template)

T_EN = [
    ("calendar_set", "remind me to {task} {date} at {time}"),
    ("calendar_set", "remind me to {task} {in}"),
    ("calendar_set", "{task} {date} {time} remind me"),
    ("calendar_set", "dont let me forget to {task} {date}"),
    ("calendar_set", "don't let me forget the {event} {date}"),
    ("calendar_set", "make sure i dont forget to {task}"),
    ("calendar_set", "pls remind me abt the {event} {date} {time}"),
    ("calendar_set", "ping me {in} to {task}"),
    ("calendar_set", "nudge me {date} {tod} to {task}"),
    ("calendar_set", "i have {event} {date} at {time} remind me"),
    ("calendar_set", "{event} {date} {tod} dont let me miss it"),
    ("calendar_set", "set a reminder {date} for the {event}"),
    ("calendar_set", "need to {task} by {date} {tod} remind me"),
    ("alarm_set", "wake me up at {time}"),
    ("alarm_set", "wake me {date} {tod} at {time}"),
    ("alarm_set", "set an alarm for {time} {date}"),
    ("lists_createoradd", "add {item} to my {list} list"),
    ("lists_createoradd", "put {item} and {item} on the {list} list"),
    ("lists_createoradd", "note down {idea}"),
    ("lists_createoradd", "jot down {idea}"),
    ("lists_createoradd", "make a note {idea}"),
    ("lists_createoradd", "save a note that i need to {idea}"),
    ("lists_createoradd", "write this down {idea}"),
    ("lists_createoradd", "take a note pls {idea}"),
    ("lists_query", "show my notes"),
    ("lists_query", "whats on my {list} list"),
    ("lists_query", "read out my notes"),
    ("lists_query", "what did i note down {date}"),
    ("lists_query", "open my {list} list"),
    ("lists_remove", "clear my {list} list"),
    ("lists_remove", "delete the note about {item}"),
    ("lists_remove", "remove {item} from the {list} list"),
    ("lists_remove", "wipe all my notes"),
    ("calendar_query", "what reminders do i have {date}"),
    ("calendar_query", "whats coming up {date}"),
    ("calendar_query", "do i have anything {date} {tod}"),
    ("calendar_query", "when is my {event}"),
    ("calendar_query", "whats my schedule {date}"),
    ("calendar_query", "any reminders left"),
    ("calendar_remove", "cancel the reminder for the {event}"),
    ("calendar_remove", "delete my {time} reminder"),
    ("calendar_remove", "remove the {event} reminder {date}"),
    ("calendar_remove", "i dont need the {event} reminder anymore"),
    ("alarm_remove", "turn off the {time} alarm"),
    ("alarm_remove", "cancel my {tod} alarm"),
    ("datetime_query", "whats the time"),
    ("datetime_query", "time pls"),
    ("datetime_query", "what time is it now"),
    ("datetime_query", "whats the date {date}"),
    ("datetime_query", "which day is it"),
    ("general_greet", "hey pebble"),
    ("general_greet", "hi there"),
    ("general_greet", "yo pebble whats up"),
    ("general_greet", "sup"),
    ("general_greet", "hello buddy"),
    ("general_greet", "good morning pebble"),
    ("general_greet", "how are you doing pebble"),
    ("general_greet", "hey hows it going"),
    ("general_greet", "heyy"),
    ("general_greet", "good night pebble"),
    ("general_greet", "wassup buddy"),
    ("general_greet", "hii"),
    ("general_joke", "tell me a joke"),
    ("general_joke", "say something funny"),
    ("general_joke", "make me laugh"),
    ("general_joke", "know any good jokes"),
    ("general_joke", "crack a joke pebble"),
    ("general_quirky", "you are so cute"),
    ("general_quirky", "i love you pebble"),
    ("general_quirky", "youre my favourite"),
    ("general_quirky", "thank you buddy"),
    ("general_quirky", "thanks pebble"),
    ("general_quirky", "good job"),
    ("general_quirky", "are you sleeping"),
    ("general_quirky", "do you get bored"),
    ("general_quirky", "youre awesome"),
    ("general_quirky", "what are you doing pebble"),
    ("general_quirky", "lol"),
    ("general_quirky", "nice one"),
]

T_RO = [
    ("calendar_set", "{date} {time} {task} hai yaad dila dena"),
    ("calendar_set", "{in} {task} yaad dilana"),
    ("calendar_set", "{date} {tod} {time} mujhe {task} hai remind kar dena"),
    ("calendar_set", "mujhe {date} {task} hai bhoolna mat yaad dilana"),
    ("calendar_set", "{event} {date} hai yaad dila dena"),
    ("calendar_set", "{date} wala {event} yaad dila dena {time}"),
    ("calendar_set", "{date} wali {event} ke liye reminder laga do"),
    ("calendar_set", "{tod} {time} {task} hai please yaad dilana"),
    ("calendar_set", "{in} bata dena {task} hai"),
    ("calendar_set", "{event} se pehle yaad dila dena {date}"),
    ("calendar_set", "reminder set kar do {date} {time} {task}"),
    ("alarm_set", "{date} {tod} {time} utha dena"),
    ("alarm_set", "mujhe {time} jaga dena"),
    ("alarm_set", "{time} ka alarm laga do"),
    ("lists_createoradd", "{list} list mein {item} daal do"),
    ("lists_createoradd", "{item} aur {item} likh lo list mein"),
    ("lists_createoradd", "note bana lo {idea} hai"),
    ("lists_createoradd", "ek note likh lo {idea}"),
    ("lists_createoradd", "ye likh ke rakh lo {idea}"),
    ("lists_createoradd", "note kar lo {idea}"),
    ("lists_createoradd", "{item} lana hai list mein add kar do"),
    ("lists_query", "mere notes dikhao"),
    ("lists_query", "{list} list mein kya kya hai"),
    ("lists_query", "maine kya likha tha notes mein"),
    ("lists_query", "meri {list} list dikhao"),
    ("lists_remove", "{list} list saaf kar do"),
    ("lists_remove", "{item} wala note hata do"),
    ("lists_remove", "saare notes delete kar do"),
    ("lists_remove", "{list} list se {item} hata do"),
    ("calendar_query", "{date} ke reminders kya hai"),
    ("calendar_query", "{date} kya kya hai mera"),
    ("calendar_query", "{date} {tod} kuch hai kya"),
    ("calendar_query", "mera {event} kab hai"),
    ("calendar_query", "kitne reminders bache hai"),
    ("calendar_query", "{date} ka schedule batao"),
    ("calendar_remove", "{event} wala reminder hata do"),
    ("calendar_remove", "{time} wala reminder cancel kar do"),
    ("calendar_remove", "{date} ka {event} reminder delete karo"),
    ("alarm_remove", "{tod} wala alarm band kar do"),
    ("alarm_remove", "{time} ka alarm hata do"),
    ("datetime_query", "kitne baje hai"),
    ("datetime_query", "abhi kya time hua hai"),
    ("datetime_query", "time batao"),
    ("datetime_query", "aaj kaunsa din hai"),
    ("datetime_query", "aaj date kya hai"),
    ("general_greet", "kya haal hai"),
    ("general_greet", "kaise ho pebble"),
    ("general_greet", "kya chal raha hai"),
    ("general_greet", "namaste pebble"),
    ("general_greet", "aur bhai kya scene hai"),
    ("general_greet", "hello pebble kaisa hai"),
    ("general_greet", "sab badhiya"),
    ("general_greet", "good morning yaar"),
    ("general_greet", "kaisa hai tu"),
    ("general_greet", "aur sunao"),
    ("general_greet", "kya haal chaal"),
    ("general_joke", "koi joke sunao"),
    ("general_joke", "kuch funny bolo"),
    ("general_joke", "hasao mujhe"),
    ("general_joke", "ek chutkula sunao yaar"),
    ("general_quirky", "tum bahut cute ho"),
    ("general_quirky", "tu mera best friend hai"),
    ("general_quirky", "thank you yaar"),
    ("general_quirky", "shukriya pebble"),
    ("general_quirky", "bahut badhiya"),
    ("general_quirky", "tum so rahe ho kya"),
    ("general_quirky", "tumhe bore nahi hota"),
    ("general_quirky", "kya kar rahe ho"),
    ("general_quirky", "tum pyare ho"),
    ("general_quirky", "love you pebble"),
    ("general_quirky", "mast hai tu"),
]

T_DE = [
    ("calendar_set", "{date} {time} {task} है याद दिला देना"),
    ("calendar_set", "{in} {task} की याद दिलाना"),
    ("calendar_set", "{date} {tod} {time} मुझे {task} है"),
    ("calendar_set", "मुझे {date} {task} है भूलना मत"),
    ("calendar_set", "{date} {event} है याद दिला देना"),
    ("calendar_set", "{date} वाली {event} याद दिलाना {time}"),
    ("calendar_set", "{event} के लिए रिमाइंडर लगा दो {date}"),
    ("calendar_set", "{in} बता देना {task} है"),
    ("alarm_set", "{date} {tod} {time} उठा देना"),
    ("alarm_set", "मुझे {time} जगा देना"),
    ("alarm_set", "{time} का अलार्म लगा दो"),
    ("lists_createoradd", "{list} लिस्ट में {item} डाल दो"),
    ("lists_createoradd", "{item} और {item} लिस्ट में लिख लो"),
    ("lists_createoradd", "नोट बना लो {idea} है"),
    ("lists_createoradd", "एक नोट लिख लो {idea}"),
    ("lists_createoradd", "ये लिख कर रख लो {idea}"),
    ("lists_query", "मेरे नोट्स दिखाओ"),
    ("lists_query", "{list} लिस्ट में क्या क्या है"),
    ("lists_query", "मैंने नोट्स में क्या लिखा था"),
    ("lists_remove", "{list} लिस्ट साफ कर दो"),
    ("lists_remove", "{item} वाला नोट हटा दो"),
    ("lists_remove", "सारे नोट्स मिटा दो"),
    ("calendar_query", "{date} के रिमाइंडर क्या हैं"),
    ("calendar_query", "{date} मेरा क्या क्या है"),
    ("calendar_query", "मेरी {event} कब है"),
    ("calendar_query", "कितने रिमाइंडर बचे हैं"),
    ("calendar_remove", "{event} वाला रिमाइंडर हटा दो"),
    ("calendar_remove", "{time} वाला रिमाइंडर रद्द कर दो"),
    ("alarm_remove", "{tod} वाला अलार्म बंद कर दो"),
    ("datetime_query", "कितने बजे हैं"),
    ("datetime_query", "अभी क्या समय हुआ है"),
    ("datetime_query", "टाइम बताओ"),
    ("datetime_query", "आज कौन सा दिन है"),
    ("general_greet", "क्या हाल है"),
    ("general_greet", "कैसे हो पेबल"),
    ("general_greet", "क्या चल रहा है"),
    ("general_greet", "नमस्ते पेबल"),
    ("general_greet", "और सुनाओ"),
    ("general_greet", "सुप्रभात"),
    ("general_greet", "हेलो पेबल"),
    ("general_joke", "कोई जोक सुनाओ"),
    ("general_joke", "कुछ मज़ेदार सुनाओ"),
    ("general_joke", "मुझे हंसाओ"),
    ("general_quirky", "तुम बहुत क्यूट हो"),
    ("general_quirky", "तुम मेरे सबसे अच्छे दोस्त हो"),
    ("general_quirky", "धन्यवाद पेबल"),
    ("general_quirky", "शुक्रिया दोस्त"),
    ("general_quirky", "तुम सो रहे हो क्या"),
    ("general_quirky", "क्या कर रहे हो"),
    ("general_quirky", "बहुत बढ़िया"),
]

# --------------------------------------------------------------------------- mood (head 3)
# Written after eval/mood_v1.jsonl was frozen and without copying it; generate() drops look-alikes.
# Mood sentences are small talk to the command model (general_quirky), with no slots.

MOOD_FILL = {
    "en": {
        "low_adj": ["sad", "down", "lonely", "stressed", "anxious", "drained", "upset", "low", "hopeless", "burnt out"],
        "when": ["today", "tonight", "this week", "lately", "right now", "since morning"],
        "bad": ["rough", "exhausting", "horrible", "the worst", "really hard", "a mess"],
        "reason": ["the assignment", "placements", "the project", "the deadline", "college", "my grades", "the viva", "my code"],
        "good_adj": ["happy", "excited", "proud", "relaxed", "pumped", "grateful", "on top of the world", "really good"],
        "great": ["awesome", "fantastic", "so good", "wonderful", "brilliant", "really fun"],
        "achieved": [
            "aced the test",
            "finished the project",
            "got the internship",
            "submitted everything on time",
            "won the hackathon",
            "fixed the bug",
            "got selected",
            "finished my workout",
        ],
    },
    "hi_roman": {
        "low_adj": ["udaas", "pareshan", "akela", "dukhi", "tension mein", "bechain", "low", "thaka hua"],
        "when": ["aaj", "aaj raat", "is hafte", "kuch dino se", "abhi", "subah se"],
        "reason": ["assignment", "placement", "project", "deadline", "college", "marks", "viva", "exam"],
        "good_adj": ["khush", "excited", "proud", "relaxed", "mast", "badhiya"],
        "achieved": [
            "test acche se gaya",
            "project khatam ho gaya",
            "internship mil gayi",
            "sab time pe submit ho gaya",
            "hackathon jeet gaye",
            "bug fix ho gaya",
            "selection ho gaya",
            "workout poora ho gaya",
        ],
    },
    "hi_deva": {
        "low_adj": ["उदास", "परेशान", "अकेला", "दुखी", "टेंशन में", "बेचैन", "थका हुआ"],
        "when": ["आज", "आज रात", "इस हफ्ते", "कुछ दिनों से", "अभी", "सुबह से"],
        "reason": ["असाइनमेंट", "प्लेसमेंट", "प्रोजेक्ट", "डेडलाइन", "कॉलेज", "मार्क्स", "वाइवा", "परीक्षा"],
        "good_adj": ["खुश", "उत्साहित", "गर्व महसूस", "रिलैक्स", "मस्त"],
        "achieved": [
            "टेस्ट अच्छा गया",
            "प्रोजेक्ट पूरा हो गया",
            "इंटर्नशिप मिल गई",
            "सब समय पर जमा हो गया",
            "हैकाथॉन जीत गए",
            "बग ठीक हो गया",
            "सिलेक्शन हो गया",
        ],
    },
}

# Compliments and plain "bahut" facts: without these the head learned "bahut … ho/hai" = sad.
for script, fill in {
    "en": {
        "nice": ["cute", "sweet", "smart", "funny", "helpful", "adorable", "kind", "clever"],
        "thing": ["the weather", "this movie", "the tea", "this room", "my code", "the traffic"],
        "fact": ["really hot", "very long", "too sweet", "super crowded", "very quiet", "so slow"],
    },
    "hi_roman": {
        "nice": ["cute", "sweet", "smart", "funny", "helpful", "pyare", "achhe", "mast", "samajhdar"],
        "thing": ["aaj", "bahar", "ye movie", "chai", "ye kamra", "traffic"],
        "fact": [
            "bahut garmi hai",
            "bahut lambi hai",
            "bahut meethi hai",
            "bahut thanda hai",
            "bahut bheed hai",
            "bahut slow hai",
        ],
    },
    "hi_deva": {
        "nice": ["प्यारे", "अच्छे", "क्यूट", "स्मार्ट", "मज़ेदार", "समझदार", "मीठे"],
        "thing": ["आज", "बाहर", "ये फिल्म", "चाय", "ये कमरा", "ट्रैफिक"],
        "fact": ["बहुत गर्मी है", "बहुत लंबी है", "बहुत मीठी है", "बहुत ठंडा है", "बहुत भीड़ है"],
    },
}.items():
    MOOD_FILL[script].update(fill)

T_MOOD_EXTRA = {
    "en": [
        ("good", "you are so {nice} pebble"),
        ("good", "pebble you're really {nice}"),
        ("good", "aww you're {nice}"),
        ("neutral", "{thing} is {fact} today"),
        ("neutral", "{thing} is {fact}"),
    ],
    "hi_roman": [
        ("good", "pebble tum bahut {nice} ho"),
        ("good", "tu kitna {nice} hai yaar"),
        ("good", "tum sach mein {nice} ho"),
        ("good", "pebble tu bada {nice} hai"),
        ("neutral", "{thing} {fact}"),
        ("neutral", "yaar {thing} {fact}"),
    ],
    "hi_deva": [
        ("good", "पेबल तुम बहुत {nice} हो"),
        ("good", "तू कितना {nice} है"),
        ("good", "तुम सच में {nice} हो"),
        ("neutral", "{thing} {fact}"),
        ("neutral", "यार {thing} {fact}"),
    ],
}

T_MOOD = {
    "en": [
        ("low", "i feel {low_adj} {when}"),
        ("low", "{when} has been {bad}"),
        ("low", "i'm so {low_adj} about {reason}"),
        ("low", "{reason} is stressing me out"),
        ("low", "i don't feel like doing anything {when}"),
        ("low", "ugh {reason} again, i hate this"),
        ("low", "i messed up {reason} and feel {low_adj}"),
        ("low", "not in a good place {when}"),
        ("low", "everything about {reason} feels too much"),
        ("good", "i'm so {good_adj} {when}"),
        ("good", "{when} was {great}"),
        ("good", "i {achieved}!"),
        ("good", "{achieved} and i feel {good_adj}"),
        ("good", "yay i {achieved}"),
        ("good", "feeling {good_adj} {when}"),
        ("good", "life is {great} {when}"),
    ],
    "hi_roman": [
        ("low", "{when} bahut {low_adj} feel ho raha hai"),
        ("low", "{reason} ki wajah se bahut tension hai"),
        ("low", "{reason} se {low_adj} hu yaar"),
        ("low", "kisi cheez mein mann nahi lag raha {when}"),
        ("low", "{when} se dil bhaari hai"),
        ("low", "{reason} kharab ho gaya, ab kya karu"),
        ("low", "main {when} thoda {low_adj} hu"),
        ("low", "sab kuch galat ho raha hai {when}"),
        ("good", "{when} bahut {good_adj} hu"),
        ("good", "{achieved}, maza aa gaya"),
        ("good", "yaar {achieved}!"),
        ("good", "{when} full {good_adj} mood hai"),
        ("good", "{achieved} toh {good_adj} feel ho raha hai"),
        ("good", "{when} ka din zabardast raha"),
    ],
    "hi_deva": [
        ("low", "{when} बहुत {low_adj} महसूस हो रहा है"),
        ("low", "{reason} की वजह से बहुत टेंशन है"),
        ("low", "{reason} से {low_adj} हूं"),
        ("low", "किसी चीज़ में मन नहीं लग रहा {when}"),
        ("low", "{when} से दिल भारी है"),
        ("low", "मैं {when} थोड़ा {low_adj} हूं"),
        ("low", "सब कुछ गलत हो रहा है {when}"),
        ("good", "{when} बहुत {good_adj} हूं"),
        ("good", "{achieved}, मज़ा आ गया"),
        ("good", "अरे {achieved}!"),
        ("good", "{when} मूड एकदम {good_adj} है"),
        ("good", "{achieved} तो बहुत अच्छा लग रहा है"),
        ("good", "{when} का दिन ज़बरदस्त रहा"),
    ],
}


#: Warm words in small talk: compliments and thanks are a good mood, not a low one.
_WARM = {
    "cute",
    "love",
    "best",
    "awesome",
    "thank",
    "thanks",
    "favourite",
    "nice",
    "job",
    "shukriya",
    "badhiya",
    "mast",
    "pyare",
    "क्यूट",
    "प्यारे",
    "धन्यवाद",
    "शुक्रिया",
    "बढ़िया",
    "अच्छे",
}


def small_talk_mood(intent: str, tokens: list[str]) -> str | None:
    """Mood label for template small talk (commands get "neutral" in train_intent.mood_of)."""
    if intent not in ("general_greet", "general_joke", "general_quirky"):
        return None
    return "good" if any(t.lower().strip("!?.,") in _WARM for t in tokens) else "neutral"


_FIELD = re.compile(r"\{(\w+)\}")


def _fill(template: str, fillers: dict, rng: random.Random) -> str:
    def sub(m):
        kind = m.group(1)
        value = rng.choice(fillers[kind])
        slot = SLOT[kind]
        if not slot:
            return value
        # "on friday" / "somvar ko": the slot is the day itself, the particle stays outside.
        head, _, tail = value.rpartition(" ")
        if kind == "date" and value.split()[0] == "on":
            return "on [date : " + value[3:] + "]"
        if kind == "date" and tail in ("ko", "को"):
            return f"[date : {head}] {tail}"
        return f"[{slot} : {value}]"

    return _FIELD.sub(sub, template)


def _words(t: str) -> set[str]:
    return set(re.sub(r"[^\w\s]", " ", t.lower()).split())


EVAL_FILES = ("pebble_commands_v0.jsonl", "pebble_commands_v1.jsonl", "mood_v1.jsonl")


def eval_texts() -> list[set[str]]:
    out = []
    for name in EVAL_FILES:
        p = ROOT / "eval" / name
        if p.exists():
            out += [_words(json.loads(l)["text"]) for l in p.read_text(encoding="utf-8").splitlines() if l.strip()]
    return out


def generate(per_template: int = 30, seed: int = 0, leak_threshold: float = 0.6) -> list[Example]:
    """Template sentences, de-duplicated, with anything close to an eval sentence removed."""
    rng = random.Random(seed)
    held_out = eval_texts()
    seen: set[str] = set()
    out: list[Example] = []
    dropped = 0
    for script, templates, fillers in (("en", T_EN, EN), ("hi_roman", T_RO, RO), ("hi_deva", T_DE, DE)):
        for intent, template in templates:
            n = per_template if "{" in template else 3  # fixed phrases: a few chat-spelling variants only
            for _ in range(n):
                tokens, tags = parse_annotated(_fill(template, fillers, rng))
                if script == "hi_roman":
                    tokens = chatify(tokens, rng)
                key = " ".join(tokens).lower()
                if key in seen:
                    continue
                seen.add(key)
                w = _words(key)
                if any(len(w & e) / len(w | e) >= leak_threshold for e in held_out):
                    dropped += 1
                    continue
                part = "dev" if rng.random() < 0.1 else "train"
                out.append(Example(tokens, tags, intent, script, part, small_talk_mood(intent, tokens)))
    # Mood sentences: small talk to the command model, labelled for the mood head.
    for script, templates in ((k, T_MOOD[k] + T_MOOD_EXTRA[k]) for k in T_MOOD):
        fill = MOOD_FILL[script]
        for mood, template in templates:
            for _ in range(per_template):
                text = _FIELD.sub(lambda m: rng.choice(fill[m.group(1)]), template)  # noqa: B023 (used within this iteration)
                tokens = text.split()
                if script == "hi_roman":
                    tokens = chatify(tokens, rng)
                key = " ".join(tokens).lower()
                if key in seen:
                    continue
                seen.add(key)
                w = _words(key)
                if any(len(w & e) / len(w | e) >= leak_threshold for e in held_out):
                    dropped += 1
                    continue
                part = "dev" if rng.random() < 0.1 else "train"
                out.append(Example(tokens, ["O"] * len(tokens), "general_quirky", script, part, mood))
    print(f"pebble data: {len(out)} sentences ({dropped} dropped as too close to the eval sets)")
    return out


if __name__ == "__main__":
    import sys

    sys.stdout.reconfigure(encoding="utf-8")
    ex = generate()
    rng = random.Random(1)
    for e in rng.sample(ex, 25):
        print(f"{e.script:<9} {e.intent:<18} {' '.join(f'{w}/{t}' if t != 'O' else w for w, t in zip(e.tokens, e.tags))}")
