"""Sleek dark-mode Floating Desktop HUD for EatVenture Bot.

Provides an always-on-top pill widget with:
- Instant [ ▶ BOT ON ] / [ ⏸ PAUSED ] toggle button (or F9 / Space hotkey)
- Real-time First & Last strategy status updates
- One-click [ ✈ OFFLINE AD-BLOCK: ON / OFF ] toggle (disables phone Wi-Fi/Data via ADB)
- Freeform dragging across the screen
"""

import threading
import tkinter as tk
from typing import Callable

class FloatingHUD:
    def __init__(
        self,
        on_toggle_bot: Callable[[bool], None],
        on_toggle_offline: Callable[[bool], None],
        on_reset_phase: Callable[[], None]
    ):
        self.on_toggle_bot = on_toggle_bot
        self.on_toggle_offline = on_toggle_offline
        self.on_reset_phase = on_reset_phase

        self.bot_active = True
        self.offline_active = False

        self.root = tk.Tk()
        self.root.title("EatVenture Bot Controller")
        self.root.overrideredirect(True)  # Frameless floating pill
        self.root.wm_attributes("-topmost", True)
        self.root.configure(bg="#141820")

        # Position near top right of desktop
        self.root.geometry("380x155+100+100")

        self._build_ui()
        self._setup_drag()

    def _build_ui(self):
        # Header / Title Bar
        header = tk.Frame(self.root, bg="#1E232E", height=28)
        header.pack(fill=tk.X)

        drag_lbl = tk.Label(header, text="⋮⋮ EatVenture Bot (F&L Strategy)", bg="#1E232E", fg="#A0AEC0", font=("Segoe UI", 9, "bold"))
        drag_lbl.pack(side=tk.LEFT, padx=10, pady=4)
        self.drag_lbl = drag_lbl

        close_btn = tk.Label(header, text="✕", bg="#1E232E", fg="#FF5252", font=("Segoe UI", 10, "bold"), cursor="hand2")
        close_btn.pack(side=tk.RIGHT, padx=10)
        close_btn.bind("<Button-1>", lambda e: self.root.destroy())

        # Main Body
        body = tk.Frame(self.root, bg="#141820", padx=12, pady=10)
        body.pack(fill=tk.BOTH, expand=True)

        # Primary Toggle Button
        self.btn_toggle = tk.Button(
            body,
            text="▶ BOT RUNNING (Press F9 / Space)",
            bg="#00E676",
            fg="#000000",
            font=("Segoe UI", 11, "bold"),
            relief=tk.FLAT,
            activebackground="#00C853",
            cursor="hand2",
            command=self._toggle_bot
        )
        self.btn_toggle.pack(fill=tk.X, pady=(0, 6))

        # Status Label
        self.lbl_status = tk.Label(
            body,
            text="Status: [1/4] St.1 Build",
            bg="#141820",
            fg="#64B5F6",
            font=("Segoe UI", 10, "bold")
        )
        self.lbl_status.pack(anchor="w")

        # Bottom Controls Frame
        bottom_frame = tk.Frame(body, bg="#141820")
        bottom_frame.pack(fill=tk.X, pady=(6, 0))

        # Offline Ad-Block Toggle
        self.btn_offline = tk.Button(
            bottom_frame,
            text="✈ Ad-Block: OFF",
            bg="#2D3748",
            fg="#CBD5E0",
            font=("Segoe UI", 9),
            relief=tk.FLAT,
            cursor="hand2",
            command=self._toggle_offline
        )
        self.btn_offline.pack(side=tk.LEFT, expand=True, fill=tk.X, padx=(0, 4))

        # Reset Phase Button
        btn_reset = tk.Button(
            bottom_frame,
            text="↺ Reset F&L Phase",
            bg="#2D3748",
            fg="#CBD5E0",
            font=("Segoe UI", 9),
            relief=tk.FLAT,
            cursor="hand2",
            command=self.on_reset_phase
        )
        btn_reset.pack(side=tk.RIGHT, expand=True, fill=tk.X, padx=(4, 0))

        # Hotkeys
        self.root.bind("<space>", lambda e: self._toggle_bot())
        self.root.bind("<F9>", lambda e: self._toggle_bot())

    def _setup_drag(self):
        def start_move(event):
            self.drag_x = event.x
            self.drag_y = event.y

        def do_move(event):
            deltax = event.x - self.drag_x
            deltay = event.y - self.drag_y
            x = self.root.winfo_x() + deltax
            y = self.root.winfo_y() + deltay
            self.root.geometry(f"+{x}+{y}")

        self.drag_lbl.bind("<Button-1>", start_move)
        self.drag_lbl.bind("<B1-Motion>", do_move)

    def _toggle_bot(self):
        self.bot_active = not self.bot_active
        if self.bot_active:
            self.btn_toggle.config(text="▶ BOT RUNNING (Press F9 / Space)", bg="#00E676")
        else:
            self.btn_toggle.config(text="⏸ BOT PAUSED (Press F9 / Space)", bg="#FF5252")
        self.on_toggle_bot(self.bot_active)

    def _toggle_offline(self):
        self.offline_active = not self.offline_active
        if self.offline_active:
            self.btn_offline.config(text="✈ Ad-Block: ON (Offline)", bg="#3182CE", fg="#FFFFFF")
        else:
            self.btn_offline.config(text="✈ Ad-Block: OFF", bg="#2D3748", fg="#CBD5E0")
        self.on_toggle_offline(self.offline_active)

    def update_status(self, text: str):
        self.root.after(0, lambda: self.lbl_status.config(text=f"Status: {text}"))

    def start(self):
        self.root.mainloop()
