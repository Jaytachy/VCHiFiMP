#!/usr/bin/env python3
"""Track a steady ~1 kHz tone in a 48 kHz WAV via complex demodulation.

Multiply by exp(-j*2*pi*f0*n/fs), low-pass the I/Q pair, unwrap phase.
The low-pass rejects out-of-band noise and harmonics instead of letting
them leak into a short-block DFT.
"""
import sys, wave, math, statistics, array

F0 = 1000.0
FS = 48000.0
FC = 40.0            # low-pass cutoff after demodulation, Hz
WIN = 0.10           # frequency estimate window, seconds

def read_channel(path):
    w = wave.open(path, "rb")
    nch, sw, sr = w.getnchannels(), w.getsampwidth(), w.getframerate()
    if sw != 2:
        raise SystemExit(f"need 16-bit, got {sw*8}-bit")
    if sr != int(FS):
        raise SystemExit(f"need 48000 Hz, got {sr}")
    raw = w.readframes(w.getnframes())
    w.close()
    a = array.array("h")
    a.frombytes(raw)
    if nch > 1:
        a = a[::nch]
    return a

def track(path):
    x = read_channel(path)
    lut_n = 48                       # exp(-j*2*pi*n/48): 1000 Hz at 48 kHz
    lut = [complex(math.cos(-2*math.pi*k/lut_n), math.sin(-2*math.pi*k/lut_n))
           for k in range(lut_n)]
    alpha = 1.0 - math.exp(-2.0*math.pi*FC/FS)
    yi = yq = 0.0
    k = 0
    phases = []
    for s in x:
        z = lut[k]
        yi += alpha * (s*z.real - yi)
        yq += alpha * (s*z.imag - yq)
        phases.append(math.atan2(yq, yi))
        k += 1
        if k == lut_n:
            k = 0
    # unwrap in place
    unw = [phases[0]]
    for i in range(1, len(phases)):
        d = phases[i] - phases[i-1]
        while d > math.pi:  d -= 2*math.pi
        while d < -math.pi: d += 2*math.pi
        unw.append(unw[-1] + d)
    # frequency over sliding windows
    ws = int(WIN*FS)
    freqs, times = [], []
    for start in range(0, len(unw)-ws, ws):
        f = F0 + (unw[start+ws]-unw[start]) / (2*math.pi*WIN)
        freqs.append(f)
        times.append((start + ws/2)/FS)
    if not freqs:
        raise SystemExit("too short")
    mean = statistics.fmean(freqs)
    sd = statistics.pstdev(freqs)
    print(f"file      : {path}")
    print(f"duration  : {len(x)/FS:.1f} s")
    print(f"mean      : {mean:.6f} Hz  ({(mean-F0)*1e6/F0:+.1f} ppm)")
    print(f"std dev   : {sd*1000:.3f} mHz")
    print(f"min / max : {min(freqs):.6f} / {max(freqs):.6f} Hz")
    steps = [abs(freqs[i]-freqs[i-1]) for i in range(1, len(freqs))]
    if steps:
        j = max(range(len(steps)), key=lambda i: steps[i])
        print(f"max step  : {steps[j]*1000:.3f} mHz at t={times[j+1]:.1f}s")
    # 2-second running-average readout
    print("t(s)    freq(Hz)   dev(mHz)")
    win = 20
    for i in range(0, len(freqs)-win+1, win):
        m = statistics.fmean(freqs[i:i+win])
        print(f"{times[i]:6.1f} {m:10.4f} {(m-F0)*1000:+9.2f}")

if __name__ == "__main__":
    for p in sys.argv[1:]:
        track(p)
        print()
