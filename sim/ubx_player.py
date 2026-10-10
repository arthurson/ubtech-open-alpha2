#!/usr/bin/env python3
"""ubx拖放播放器: 揀.ubx -> 自動倒CSV+審計 -> 一個掣係mujoco播.
用法: python sim/ubx_player.py
"""
import csv
import os
import subprocess
import sys
import threading
import tkinter as tk
from tkinter import filedialog, scrolledtext

SIM = r'C:\Users\user\usb_driver\Downloads\ubtech-open-alpha2-beta5\sim'
SCRIPTS = r'C:\Users\user\usb_driver\Downloads\ubtech-open-alpha2-beta5\scripts'
UBXDIR = r'C:\Users\user\Desktop\alpha2\UBX\actions'
sys.path.insert(0, SCRIPTS)
from ubx_to_traj import export_ubx


class Player(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title('ubx -> MuJoCo 播放器')
        self.geometry('560x430')
        self.csv = None
        self.proc = None
        top = tk.Frame(self)
        top.pack(fill='x', padx=8, pady=8)
        tk.Button(top, text='開 ubx 檔…', command=self.open_ubx, width=14).pack(side='left')
        self.lbl = tk.Label(top, text='未載入', anchor='w')
        self.lbl.pack(side='left', padx=8, fill='x', expand=True)
        mid = tk.Frame(self)
        mid.pack(fill='x', padx=8)
        self.b_watch = tk.Button(mid, text='睇舞步', command=lambda: self.play(False), state='disabled', width=12)
        self.b_watch.pack(side='left', padx=4)
        self.b_pd = tk.Button(mid, text='真物理播', command=lambda: self.play(True), state='disabled', width=12)
        self.b_pd.pack(side='left', padx=4)
        self.b_run = tk.Button(mid, text='跑分', command=self.score, state='disabled', width=12)
        self.b_run.pack(side='left', padx=4)
        self.b_stop = tk.Button(mid, text='停', command=self.stop, state='disabled', width=8)
        self.b_stop.pack(side='left', padx=4)
        tk.Label(self, text='動作 (audited, 腳鎖定版都會一齊出):').pack(anchor='w', padx=8)
        lb = tk.Frame(self)
        lb.pack(fill='x', padx=8)
        self.b_upper = tk.Button(lb, text='播上身版(穩陣)', command=lambda: self.play_variant('_upper'),
                                 state='disabled', width=16)
        self.b_upper.pack(side='left', padx=4)
        self.log = scrolledtext.ScrolledText(self, height=14)
        self.log.pack(fill='both', expand=True, padx=8, pady=8)

    def msg(self, s):
        self.log.insert('end', s + '\n')
        self.log.see('end')
        self.update_idletasks()

    def open_ubx(self):
        fn = filedialog.askopenfilename(initialdir=UBXDIR, filetypes=[('ubx', '*.ubx')])
        if not fn:
            return
        base = os.path.splitext(os.path.basename(fn))[0]
        csvp = os.path.join(SIM, f'traj_{base}.csv')
        try:
            rows = export_ubx(fn)
        except Exception as e:
            self.msg(f'解唔到: {e}')
            return
        with open(csvp, 'w', newline='') as f:
            w = csv.writer(f)
            w.writerow(['t_ms'] + [f's{i}' for i in range(1, 21)] + ['move_ms'])
            for t, angs, mv in rows:
                w.writerow([t] + angs + [mv])
        dur = rows[-1][0] if rows else 0
        self.csv = csvp
        self.lbl.config(text=f'{base}: {len(rows)}格 {dur}ms')
        self.msg(f'已倒出 {csvp} ({len(rows)}格)')
        self.msg('審計緊…')
        for b in (self.b_watch, self.b_pd, self.b_run, self.b_upper):
            b.config(state='disabled')
        threading.Thread(target=self._audit, args=(csvp,), daemon=True).start()

    def _audit(self, csvp):
        import subprocess as sp
        r = sp.run([sys.executable, os.path.join(SIM, 'desc_play.py'), '--audit', csvp],
                   capture_output=True, text=True, timeout=600)
        self.msg(r.stdout.strip() or r.stderr.strip()[-500:])
        for b in (self.b_watch, self.b_pd, self.b_run, self.b_upper):
            b.config(state='normal')

    def play(self, pd):
        self.stop()
        cmd = [sys.executable, os.path.join(SIM, 'desc_play.py'), '--watch', self.csv, '--loop']
        if pd:
            cmd.append('--pd')
        self.proc = subprocess.Popen(cmd)
        self.b_stop.config(state='normal')
        self.msg('播緊 (關viewer窗即停)…' if not pd else '真物理播緊…')

    def play_variant(self, suffix):
        import csv as _csv
        INIT = [120, 120, 120, 120, 120, 120, 120, 120, 65, 145, 135, 120,
                120, 175, 95, 105, 120, 120, 120, 120]
        out = self.csv[:-4] + suffix + '.csv'
        with open(self.csv) as f, open(out, 'w', newline='') as g:
            rd = _csv.DictReader(f)
            w = _csv.writer(g)
            w.writerow(['t_ms'] + [f's{i}' for i in range(1, 21)] + ['move_ms'])
            for r in rd:
                w.writerow([r['t_ms']] + [INIT[i] if 7 <= i + 1 <= 16 else r[f's{i + 1}']
                                          for i in range(20)] + [r['move_ms']])
        self.msg(f'上身版: {out}')
        self.stop()
        self.proc = subprocess.Popen(
            [sys.executable, os.path.join(SIM, 'desc_play.py'), '--watch', out, '--loop'])
        self.b_stop.config(state='normal')

    def score(self):
        self.msg('跑分緊…')
        threading.Thread(target=self._score, daemon=True).start()

    def _score(self):
        import subprocess as sp
        out = self.csv[:-4] + '.npz'
        r = sp.run([sys.executable, os.path.join(SIM, 'desc_play.py'), '--replay',
                    self.csv, '--out', out], capture_output=True, text=True, timeout=900)
        tail = (r.stdout.strip() or r.stderr.strip()[-800:]).splitlines()
        for ln in tail[-6:]:
            self.msg(ln)

    def stop(self):
        if self.proc and self.proc.poll() is None:
            self.proc.terminate()
        self.proc = None
        self.b_stop.config(state='disabled')


if __name__ == '__main__':
    Player().mainloop()
