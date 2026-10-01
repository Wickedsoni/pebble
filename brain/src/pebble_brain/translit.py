"""Devanagari → informal Roman Hindi ("Hinglish" spelling), word by word.

MASSIVE has Hindi only in Devanagari, but people type Hindi in Roman letters with loose spelling:
"kal", "paani"/"pani", "yaad"/"yad". We start from the MIT-licensed `indic_transliteration`
OPTITRANS scheme and then make it casual:

* drop the inherent final "a" (schwa): kala → kal, shAma → sham
* nasal M → n: pAMcha → paancha → paanch
* long vowels become doubled or plain at random (A → aa|a, I → ee|i, U → oo|u),
  so the model sees the spelling variety real users produce
* retroflex/aspirate capitals are lowercased (T → t, D → d, N → n)

Word-by-word conversion keeps slot (BIO) labels aligned 1:1 with the original tokens.
"""

from __future__ import annotations

import random
import re

from indic_transliteration import sanscript
from indic_transliteration.sanscript import transliterate

_DEVANAGARI = re.compile(r"[ऀ-ॿ]")
_VOWELS = set("aeiou")


def has_devanagari(text: str) -> bool:
    return bool(_DEVANAGARI.search(text))


def romanize_word(word: str, rng: random.Random | None = None) -> str:
    """One Devanagari word → casual Roman spelling. Non-Devanagari words pass through unchanged."""
    if not has_devanagari(word):
        return word
    r = rng or random.Random(0)
    w = transliterate(word, sanscript.DEVANAGARI, sanscript.OPTITRANS)
    w = w.replace("M", "n").replace(".N", "n").replace("~N", "n").replace("~n", "n")
    w = w.replace("R^i", "ri").replace("Ri", "ri")
    w = re.sub(r"[.^~']", "", w)
    # Schwa deletion while long vowels are still capitals (A/I/U), so only the inherent short
    # "a" is dropped: kala → kal, subaha → subah, but jagA stays jaga/jagaa. Keep "na", "ka"…
    if len(w) > 2 and w.endswith("a") and w[-2].isalpha() and w[-2].lower() not in _VOWELS:
        w = w[:-1]
    # Long vowels: doubled ("aa") or plain ("a"), as people really type.
    w = re.sub("A", lambda _: "aa" if r.random() < 0.6 else "a", w)
    w = re.sub("I", lambda _: "ee" if r.random() < 0.4 else "i", w)
    w = re.sub("U", lambda _: "oo" if r.random() < 0.4 else "u", w)
    return w.lower()


def romanize(tokens: list[str], seed: int = 0) -> list[str]:
    rng = random.Random(seed)
    return [romanize_word(t, rng) for t in tokens]
