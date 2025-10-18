import os
import json
import time
from pathlib import Path
from typing import Optional, Set, Tuple
from instagrapi import Client
from instagrapi.exceptions import TwoFactorRequired, LoginRequired


class IGService:
    def __init__(self, session_path: str):
        self.session_path = Path(session_path)
        self.client = Client()
        self.pending_user = None
        self.pending_password = None
        self.pending_two_factor = False
        # gentler behavior
        self.client.delay_range = [1, 3]

    def _load_session(self) -> bool:
        try:
            if self.session_path.exists():
                settings = json.loads(self.session_path.read_text())
                self.client.set_settings(settings)
                self.client.login_by_sessionid(settings.get("cookie", {}).get("sessionid"))
                return True
        except Exception:
            pass
        return False

    def _save_session(self):
        self.session_path.parent.mkdir(parents=True, exist_ok=True)
        settings = self.client.get_settings()
        self.session_path.write_text(json.dumps(settings, ensure_ascii=False))

    def ensure_client(self):
        # try load existing session
        if self._load_session():
            try:
                self.client.get_timeline_feed()
                return
            except Exception:
                pass
        # else, will require login()

    def login(self, username: str, password: str) -> Optional[str]:
        """Returns None on success, or '2FA' if two-factor required, or raises on error."""
        self.pending_user = username
        self.pending_password = password
        self.pending_two_factor = False
        try:
            self.client.login(username, password)
            self._save_session()
            return None
        except TwoFactorRequired:
            self.pending_two_factor = True
            return "2FA"

    def submit_2fa(self, code: str):
        if not self.pending_two_factor or not self.pending_user or not self.pending_password:
            raise RuntimeError("2FA is not pending")
        self.client.two_factor_login(code)
        self._save_session()
        self.pending_two_factor = False

    def fetch_all(self, target_username: str) -> Tuple[Set[str], Set[str]]:
        # Ensure logged in
        self.ensure_client()
        if not self.client.user_id:
            raise LoginRequired("Not logged in")
        user_id = self.client.user_id_from_username(target_username)
        followers = set()
        following = set()
        # Fetch all with pagination
        followers_dict = self.client.user_followers(user_id, amount=0)
        following_dict = self.client.user_following(user_id, amount=0)
        followers = {u.username for u in followers_dict.values()}
        following = {u.username for u in following_dict.values()}
        # remove self target if present
        t = target_username.lower()
        followers.discard(t)
        following.discard(t)
        return followers, following
