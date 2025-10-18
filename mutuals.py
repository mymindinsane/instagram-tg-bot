import re
import unicodedata
from typing import Set, List, Tuple

USERNAME_RE = re.compile(r"[a-z0-9._]+$")


def normalize(name: str) -> str:
    if not name:
        return ""
    t = unicodedata.normalize("NFKC", name)
    t = t.replace("\u200b", "").replace("\u200c", "").replace("\u200d", "").replace("\ufeff", "")
    t = t.replace(" ", "").lower()
    if t.startswith("@"): t = t[1:]
    t = re.sub(r"[^a-z0-9._]", "", t)
    return t


def compute(followers: Set[str], following: Set[str]) -> Tuple[List[str], List[str], List[str]]:
    f1 = {normalize(x) for x in followers if normalize(x)}
    f2 = {normalize(x) for x in following if normalize(x)}
    mutuals = sorted(f1 & f2, key=str.lower)
    not_following_back = sorted(f2 - f1, key=str.lower)  # you follow them, they don't follow you
    not_followed_by_you = sorted(f1 - f2, key=str.lower)  # they follow you, you don't follow them
    return mutuals, not_following_back, not_followed_by_you
