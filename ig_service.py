import os
import json
import time
from pathlib import Path
import logging
from typing import Optional, Set, Tuple
from instagrapi import Client
from instagrapi.exceptions import TwoFactorRequired, LoginRequired, ChallengeRequired


class IGService:
    def __init__(self, session_path: str):
        self.persist = bool(session_path) and str(session_path).lower() not in {"", ":memory:", "memory", "none", "null"}
        self.session_path = Path(session_path) if self.persist else None
        self.client = Client()
        # prevent interactive input prompts in server logs (Render/Scalingo)
        try:
            def _no_interactive_code_handler(username, choice=None):
                # Do not attempt to read from stdin; let /2fa flow handle it
                raise ChallengeRequired("Challenge code required; provide via /2fa <code>")

            # Some versions pass only (username,), others (username, choice)
            self.client.challenge_code_handler = _no_interactive_code_handler
        except Exception:
            pass
        # reduce instagrapi logging noise
        try:
            logging.getLogger("instagrapi").setLevel(logging.ERROR)
        except Exception:
            pass
        self.pending_user = None
        self.pending_password = None
        self.pending_two_factor = False
        self.pending_challenge = False
        # gentler behavior
        self.client.delay_range = [1, 3]

    def _load_session(self) -> bool:
        try:
            if self.persist and self.session_path and self.session_path.exists():
                # session expires after 24h to avoid long-lived storage
                try:
                    mtime = self.session_path.stat().st_mtime
                    import time as _t
                    if (time.time() - mtime) > 24 * 3600:
                        return False
                except Exception:
                    pass
                settings = json.loads(self.session_path.read_text())
                self.client.set_settings(settings)
                self.client.login_by_sessionid(settings.get("cookie", {}).get("sessionid"))
                return True
        except Exception:
            pass
        return False

    def _save_session(self):
        if not self.persist or not self.session_path:
            return
        self.session_path.parent.mkdir(parents=True, exist_ok=True)
        settings = self.client.get_settings()
        self.session_path.write_text(json.dumps(settings, ensure_ascii=False))

    def ensure_client(self):
        # try load existing session, без сетевых запросов
        if self._load_session():
            return
        # else, will require login()

    def login(self, username: str, password: str) -> Optional[str]:
        """Returns None on success, or '2FA' if two-factor required, or raises on error."""
        self.pending_user = username
        self.pending_password = password
        self.pending_two_factor = False
        self.pending_challenge = False
        try:
            self.client.login(username, password)
            self._save_session()
            return None
        except TwoFactorRequired:
            self.pending_two_factor = True
            return "2FA"
        except ChallengeRequired:
            # Treat challenge as a 2FA step handled via /2fa <code>
            self.pending_two_factor = True
            self.pending_challenge = True
            return "2FA"

    def submit_2fa(self, code: str):
        if not self.pending_two_factor or not self.pending_user or not self.pending_password:
            raise RuntimeError("2FA is not pending")
        # Try both flows: classic 2FA and challenge resolution
        ok = False
        try:
            self.client.two_factor_login(code)
            ok = True
        except Exception:
            pass
        if not ok:
            try:
                # some challenges require resolving with a code
                self.client.challenge_resolve(code)
                ok = True
            except Exception:
                pass
        if not ok:
            raise RuntimeError("Неверный или просроченный код подтверждения")
        self._save_session()
        self.pending_two_factor = False
        self.pending_challenge = False

    def fetch_all(self, target_username: str) -> Tuple[Set[str], Set[str]]:
        # Ensure logged in
        self.ensure_client()
        if not self.client.user_id:
            raise LoginRequired("Not logged in")
        # resolve user_id with a few retries (avoid transient JSONDecodeError from public_request)
        user_id = None
        last_err = None
        for _ in range(3):
            try:
                user_id = self.client.user_id_from_username(target_username)
                break
            except ChallengeRequired as e:
                raise RuntimeError("Требуется подтверждение/2FA для аккаунта. Выполните /2fa <код> и повторите.")
            except Exception as e:
                last_err = e
                time.sleep(1.5)
        if user_id is None:
            raise last_err or RuntimeError("Failed to resolve user id")
        # Diagnostics: private + friendship
        try:
            info = self.client.user_info(user_id)
        except Exception:
            info = None
        try:
            friendship = self.client.user_friendship(user_id)
        except Exception:
            friendship = None
        if info is not None:
            try:
                if getattr(info, "is_private", False) and not (friendship and getattr(friendship, "following", False)):
                    raise RuntimeError("Профиль приватный и вы не подписаны — Instagram не отдаёт списки без доступа.")
            except Exception:
                pass

        followers = set()
        following = set()
        # Fetch all with pagination + retries
        last_err = None
        for _ in range(3):
            try:
                followers_dict = self.client.user_followers(user_id, amount=0)
                break
            except ChallengeRequired as e:
                raise RuntimeError("Требуется подтверждение/2FA для получения списка followers. Выполните /2fa <код> и повторите.")
            except Exception as e:
                last_err = e
                time.sleep(2)
        if last_err and not followers:
            raise RuntimeError(f"Не удалось получить followers: {last_err}")

        last_err = None
        for _ in range(3):
            try:
                following_dict = self.client.user_following(user_id, amount=0)
                break
            except ChallengeRequired as e:
                raise RuntimeError("Требуется подтверждение/2FA для получения списка following. Выполните /2fa <код> и повторите.")
            except Exception as e:
                last_err = e
                time.sleep(2)
        if last_err and not following:
            raise RuntimeError(f"Не удалось получить following: {last_err}")
        followers = {u.username for u in followers_dict.values()}
        following = {u.username for u in following_dict.values()}
        # remove self target if present
        t = target_username.lower()
        followers.discard(t)
        following.discard(t)
        return followers, following
