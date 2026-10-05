"""Exactly match Android WhisperMelSpectrogram.kt and compare encoder output."""
import numpy as np
import onnxruntime as ort
import os

os.chdir(os.path.dirname(os.path.abspath(__file__)))

ENCODER_PATH = "onnx_models/tiny/onnx/encoder_model.onnx"
DECODER_PATH = "onnx_models/tiny/onnx/decoder_model_merged.onnx"

def android_mel(pcm_float32):
    """Exact replica of Android WhisperMelSpectrogram.compute()"""
    SR = 16000; N_FFT = 400; HOP = 160; N_MEL = 80; N_FRAMES = 3000; N_SAMPLES = 480000
    
    # Pad/truncate to 30s
    padded = np.zeros(N_SAMPLES, dtype=np.float32)
    copy_len = min(len(pcm_float32), N_SAMPLES)
    padded[:copy_len] = pcm_float32[:copy_len]
    
    # Reflective padding (center=True)
    pad_len = N_FFT // 2  # 200
    padded_reflect = np.zeros(N_SAMPLES + N_FFT, dtype=np.float32)
    padded_reflect[:pad_len] = padded[pad_len:0:-1]  # reflect left
    padded_reflect[pad_len:pad_len + N_SAMPLES] = padded  # center
    padded_reflect[pad_len + N_SAMPLES:] = padded[-1:-pad_len-1:-1]  # reflect right
    
    # Hann window (periodic)
    hann = 0.5 * (1.0 - np.cos(2.0 * np.pi * np.arange(N_FFT) / N_FFT)).astype(np.float32)
    
    # Direct 400-point DFT (matching Android's pre-computed twiddle factors)
    n_freq = N_FFT // 2 + 1  # 201
    dft_cos = np.zeros((n_freq, N_FFT), dtype=np.float32)
    dft_sin = np.zeros((n_freq, N_FFT), dtype=np.float32)
    for k in range(n_freq):
        for n in range(N_FFT):
            angle = 2.0 * np.pi * k * n / N_FFT
            dft_cos[k, n] = np.cos(angle).astype(np.float32)
            dft_sin[k, n] = (-np.sin(angle)).astype(np.float32)
    
    # Mel filterbank (linear scale, htk=False)
    f_sp = 200.0 / 3.0
    low_mel = 0.0
    high_mel = 8000.0 / f_sp  # 120.0
    mel_points = np.array([low_mel + i * (high_mel - low_mel) / (N_MEL + 1) for i in range(N_MEL + 2)], dtype=np.float32)
    hz_points = mel_points * f_sp
    
    filterbank = np.zeros((N_MEL, n_freq), dtype=np.float32)
    for mel in range(N_MEL):
        low_freq = hz_points[mel]
        center_freq = hz_points[mel + 1]
        high_freq = hz_points[mel + 2]
        for k in range(n_freq):
            freq = float(k) * SR / N_FFT
            if freq < low_freq:
                filterbank[mel, k] = 0.0
            elif low_freq <= freq <= center_freq:
                diff = center_freq - low_freq
                filterbank[mel, k] = (freq - low_freq) / diff if diff > 0 else 0.0
            elif center_freq < freq <= high_freq:
                diff = high_freq - center_freq
                filterbank[mel, k] = (high_freq - freq) / diff if diff > 0 else 0.0
            else:
                filterbank[mel, k] = 0.0
    
    result = np.zeros(N_MEL * N_FRAMES, dtype=np.float32)
    
    for frame in range(N_FRAMES):
        offset = frame * HOP
        fft_real = padded_reflect[offset:offset + N_FFT].copy() * hann
        fft_imag = np.zeros(N_FFT, dtype=np.float32)
        
        power_spec = np.zeros(n_freq, dtype=np.float32)
        for k in range(n_freq):
            re = np.sum(fft_real * dft_cos[k] - fft_imag * dft_sin[k])
            im = np.sum(fft_real * dft_sin[k] + fft_imag * dft_cos[k])
            power_spec[k] = re * re + im * im
        
        for mel in range(N_MEL):
            mel_energy = np.sum(power_spec * filterbank[mel])
            if mel_energy > 1e-10:
                log_mel = np.log(mel_energy)
            else:
                log_mel = -23.0
            result[mel * N_FRAMES + frame] = log_mel
    
    return result

# Generate same test audio: 440Hz tone for 5s
SR = 16000
duration = 30.0
n_samples = int(SR * duration)
t = np.arange(n_samples) / SR
audio = np.zeros(n_samples, dtype=np.float32)
mask = t < 5.0
audio[mask] = 0.5 * np.sin(2 * np.pi * 440 * t[mask])

print("Computing mel spectrogram (Android-style)...")
mel = android_mel(audio)
mel_2d = mel.reshape(80, 3000)
print(f"Mel shape: {mel_2d.shape}, range: [{mel.min():.4f}, {mel.max():.4f}], mean: {mel.mean():.6f}")
print(f"mel[0,:5] = {mel_2d[0, :5]}")
print(f"mel[40,:5] = {mel_2d[40, :5]}")

# Now compute using HuggingFace-style for comparison
print("\n--- HuggingFace-style mel ---")
n_fft_hf = 400; hop_hf = 160
pad_len_hf = n_fft_hf // 2
audio_padded_hf = np.pad(audio, (pad_len_hf, pad_len_hf), mode='reflect')
n_frames_hf = 1 + (len(audio_padded_hf) - n_fft_hf) // hop_hf
window_hf = np.hanning(n_fft_hf).astype(np.float32)
stft_hf = np.zeros((n_frames_hf, n_fft_hf // 2 + 1), dtype=np.complex64)
for i in range(n_frames_hf):
    start = i * hop_hf
    frame = audio_padded_hf[start:start + n_fft_hf].astype(np.float32) * window_hf
    stft_hf[i] = np.fft.rfft(frame)
mag_hf = np.abs(stft_hf) ** 2

f_sp = 200.0 / 3.0
n_freqs = n_fft_hf // 2 + 1
low_freq_mel = 0.0; high_freq_mel = 8000.0 / f_sp
mel_pts = np.linspace(low_freq_mel, high_freq_mel, 82)
hz_pts = mel_pts * f_sp
fb_hf = np.zeros((80, n_freqs), dtype=np.float32)
for i in range(80):
    lo = hz_pts[i]; ct = hz_pts[i+1]; hi = hz_pts[i+2]
    for j in range(n_freqs):
        freq = j * SR / n_fft_hf
        if lo <= freq <= ct and ct > lo:
            fb_hf[i,j] = (freq - lo) / (ct - lo)
        elif ct < freq <= hi and hi > ct:
            fb_hf[i,j] = (hi - freq) / (hi - ct)

mel_energy_hf = mag_hf @ fb_hf.T
mel_energy_hf = np.clip(mel_energy_hf, 1e-9, None)
log_mel_hf = np.log(mel_energy_hf).T
if log_mel_hf.shape[1] > 3000:
    log_mel_hf = log_mel_hf[:, :3000]
elif log_mel_hf.shape[1] < 3000:
    log_mel_hf = np.pad(log_mel_hf, ((0,0),(0,3000-log_mel_hf.shape[1])))
print(f"HF mel shape: {log_mel_hf.shape}, range: [{log_mel_hf.min():.4f}, {log_mel_hf.max():.4f}], mean: {log_mel_hf.mean():.6f}")
print(f"HF mel[0,:5] = {log_mel_hf[0, :5]}")
print(f"HF mel[40,:5] = {log_mel_hf[40, :5]}")

# Compare
diff = np.abs(mel_2d - log_mel_hf)
print(f"\nMel comparison: max_diff={diff.max():.6f}, mean_diff={diff.mean():.6f}")

# Run encoder with Android mel
print("\n--- Encoder with Android mel ---")
enc = ort.InferenceSession(ENCODER_PATH)
enc_in = enc.get_inputs()[0]
hidden = enc.run(None, {enc_in.name: mel_2d.reshape(1, 80, 3000).astype(np.float32)})[0]
print(f"Shape: {hidden.shape}, Mean: {hidden.mean():.6f}, AbsMax: {np.abs(hidden).max():.6f}")
print(f"hidden[0,0,:5] = {hidden[0,0,:5]}")

# Run encoder with HF mel
print("\n--- Encoder with HF mel ---")
hidden_hf = enc.run(None, {enc_in.name: log_mel_hf.reshape(1, 80, 3000).astype(np.float32)})[0]
print(f"Shape: {hidden_hf.shape}, Mean: {hidden_hf.mean():.6f}, AbsMax: {np.abs(hidden_hf).max():.6f}")
print(f"hidden_hf[0,0,:5] = {hidden_hf[0,0,:5]}")

# Compare encoder outputs
enc_diff = np.abs(hidden - hidden_hf)
print(f"\nEncoder output diff: max={enc_diff.max():.6f}, mean={enc_diff.mean():.6f}")

# Run decoder step 0 with Android mel encoder output
print("\n--- Decoder step 0 with Android mel ---")
dec = ort.InferenceSession(DECODER_PATH)
dec_inputs = dec.get_inputs()
prompt = np.array([[50258, 50259, 50359, 50363]], dtype=np.int64)
feed = {}
for inp in dec_inputs:
    n = inp.name
    if n == "input_ids":
        feed[n] = prompt
    elif "encoder_hidden" in n:
        feed[n] = hidden
    elif "past" in n:
        shape = [1, 6, 0, 64]
        feed[n] = np.zeros(shape, dtype=np.float32)
    elif "use_cache" in n:
        feed[n] = np.array([False], dtype=np.bool_)

res = dec.run(None, feed)
logits = res[0][0, -1, :]
top5 = np.argsort(logits)[-5:][::-1]
for idx in top5:
    print(f"  token {idx}: {logits[idx]:.4f}")

# Run decoder step 0 with HF mel encoder output
print("\n--- Decoder step 0 with HF mel ---")
feed_hf = {}
for inp in dec_inputs:
    n = inp.name
    if n == "input_ids":
        feed_hf[n] = prompt
    elif "encoder_hidden" in n:
        feed_hf[n] = hidden_hf
    elif "past" in n:
        shape = [1, 6, 0, 64]
        feed_hf[n] = np.zeros(shape, dtype=np.float32)
    elif "use_cache" in n:
        feed_hf[n] = np.array([False], dtype=np.bool_)

res_hf = dec.run(None, feed_hf)
logits_hf = res_hf[0][0, -1, :]
top5_hf = np.argsort(logits_hf)[-5:][::-1]
for idx in top5_hf:
    print(f"  token {idx}: {logits_hf[idx]:.4f}")
