#!/usr/bin/env python3
"""Diagnose glitches in a steady tone: demodulate, per-window frequency,
plus waveform discontinuity stats."""
import sys, wave, math, statistics, array

F0 = 1000.0
FS = 48000.0
FC = 40.0
LWIN = 0.05          # analysis window, seconds


def read_channel(path):
    w = wave.open(path, "rb")
    nch, sw, sr = w.getnchannels(), w.getsampwidth(), w.getframerate()
    if sw != 2:
        raise SystemExit("need 16-bit")
    if sr != 48000:
        raise SystemExit("need 48000 Hz")
    raw = w.readframes(w.getnframes())
    w.close()
    a = array.array("h")
    a.frombytes(raw)
    if nch > 1:
        a = a[::nch]
    return a


def main():
    path = sys.argv[1]
    t0 = float(sys.argv[2]) if len(sys.argv) > 2 else 0.0
    t1 = float(sys.argv[3]) if len(sys.argv) > 3 else 1e9
    x = read_channel(path)
    a = int(t0 * FS)
    b = min(len(x), int(t1 * FS))
    x = x[a:b]
    n = len(x)
    print(f"file: {path}  {n/FS:.2f} s  peak={max(abs(v) for v in x)}")

    # waveform discontinuity: biggest jumps between neighbours
    peak = max(1, max(abs(v) for v in x))
    diffs = [abs(x[i] - x[i-1]) for i in range(1, n)]
    md = max(diffs)
    print(f"max sample delta: {md} ({md/peak*100:.1f}% of peak)")
    big = [i for i, d in enumerate(diffs) if d > 0.25 * peak]
    print(f"jumps >25% peak: {len(big)}")
    if big:
        t = [i / FS for i in big]
        print("  first 15 at:", " ".join(f"{v:.3f}" for v in t[:15]))

    # demodulate
    lut_n = 48
    lut = [complex(math.cos(-2*math.pi*k/lut_n), math.sin(-2*math.pi*k/lut_n))
           for k in range(lut_n)]
    alpha = 1.0 - math.exp(-2.0*math.pi*FC/FS)
    yi = yq = 0.0
    phases = []
    k = 0
    for s in x:
        z = lut[k]
        yi += alpha * (s*z.real - yi)
        yq += alpha * (s*z.imag - yq)
        phases.append(math.atan2(yq, yi))
        k += 1
        if k == lut_n:
            k = 0
    unw = [phases[0]]
    for i in range(1, n):
        dd = phases[i] - phases[i-1]
        while dd > math.pi:  dd -= 2*math.pi
        while dd < -math.pi: dd += 2*math.pi
        unw.append(unw[-1] + dd)

    ws = int(LWIN*FS)
    rows = []
    for st in range(0, n-ws, ws):
        f = F0 + (unw[st+ws]-unw[st]) / (2*math.pi*LWIN)
        rows.append(((st+ws/2)/FS, f))
    freqs = [f for _, f in rows]
    med = statistics.median(freqs)
    print(f"median {med:.3f} Hz  ({med-F0:+.1f} mHz)")
    bad = [(t, f) for t, f in rows if abs(f-med) > 3.0]
    print(f"windows off by >3 Hz: {len(bad)} / {len(rows)}")
    for t, f in bad[:25]:
        print(f"   t={t:6.2f}s  {f:9.3f} Hz")


main()
